package com.vocabularybooster

import com.vocabularybooster.db.VocabularyDatabase
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * schema v2 基线（DATABASE_SCHEMA 全 12 表 + Q1–Q6）：
 * 在 JVM 内存 SQLite 上验证建表、排序、幂等与派生复制（决策 D1–D4 落地）；
 * v2 变更：WordBookEntryDefinition 去 includeExamples，新增 WordBookEntryExampleSelection。
 *
 * 注：JVM 驱动默认不开启外键，FK/CHECK 约束行为（TC-DB-07）在
 * WordBookTypeAndLineageConstraintTest 以连接属性等价开启后于 JVM 验证
 * （生产端 Android 驱动 PRAGMA foreign_keys=ON，DATABASE_SCHEMA §1）。
 */
class SchemaSmokeTest {

    private fun newDatabase(): VocabularyDatabase = TestDb.inMemory().database

    @Test
    fun schemaV2SupportsAllTwelveTables() {
        val db = newDatabase()
        val now = 1_760_000_000_000L

        // 1 WordBook（ORIGINAL 母本，D2/D3）
        db.wordBookQueries.insertOriginalWordBook("TOEFL Core", null, now, now)
        val bookId = db.wordBookQueries.selectLastInsertRowId().executeAsOne()

        // 2 Word
        db.wordQueries.insertWord("booster", "booster", null, null, null, now, now)
        val wordId = db.wordQueries.selectLastInsertRowId().executeAsOne()

        // 3 DefinitionEntry —— Q1 排序：partOfSpeechOrder 升序（I-4：POS 分组连续）
        db.definitionEntryQueries.insertDefinitionEntry(wordId, "noun", 1, 0, "one that boosts", "推动者")
        db.definitionEntryQueries.insertDefinitionEntry(wordId, "verb", 0, 0, "to push up", "增强")
        val definitions = db.definitionEntryQueries.selectDefinitionsForWord(wordId).executeAsList()
        assertEquals(listOf("verb", "noun"), definitions.map { it.partOfSpeech })

        // 4 Example
        db.exampleQueries.insertExample(
            definitions.first().definitionEntryId,
            "a booster rocket lifted the capsule",
            "助推火箭升空",
            "TTS",
            null,
            null,
            null,
            null,
            0,
        )
        val exampleId = db.exampleQueries
            .selectExamplesForEntry(definitions.first().definitionEntryId)
            .executeAsList().single().exampleId

        // 5 WordBookEntry（entryOrder 队列序 + pendingTranslation 导入暂存，FR-14）
        db.wordBookEntryQueries.insertEntry(bookId, wordId, 0, "增强器（导入暂存）", now)

        // 6 WordBookEntryDefinition（释义选择，FR-5 v2：无 includeExamples）
        val entryId = db.wordBookEntryQueries.selectLastInsertRowId().executeAsOne()
        db.wordBookEntryDefinitionQueries.insertEntryDefinition(
            entryId,
            definitions.first().definitionEntryId,
        )

        // 6b WordBookEntryExampleSelection（例句逐条选择，PROJECT_SPEC v1.3）：
        // INSERT OR IGNORE 幂等（TC-DB-01：复合主键去重）
        db.wordBookEntryExampleSelectionQueries.insertExampleSelection(entryId, exampleId)
        db.wordBookEntryExampleSelectionQueries.insertExampleSelection(entryId, exampleId)
        assertEquals(
            1,
            db.wordBookEntryExampleSelectionQueries
                .selectExampleSelections(entryId).executeAsList().size,
        )

        // 7 LearningSession
        db.learningSessionQueries.insertSession(bookId, "ACTIVE", 10, now, null)
        val sessionId = db.learningSessionQueries.selectLastInsertRowId().executeAsOne()

        // 8 SessionWord
        db.sessionWordQueries.insertSessionWord(sessionId, wordId, 0, 0, "PENDING")

        // 9 WordMastery —— 行存在=MASTERED；重复标记幂等（FR-7 / D4）
        db.wordMasteryQueries.markMastered(bookId, wordId, now)
        db.wordMasteryQueries.markMastered(bookId, wordId, now)
        assertEquals(1L, db.wordMasteryQueries.countMastered(bookId).executeAsOne())

        // 10 Achievement —— UNIQUE(type, wordBookId) 幂等（FR-13）
        db.achievementQueries.insertAchievement("BOOK_COMPLETED", bookId, "{\"bookName\":\"TOEFL Core\"}", now)
        db.achievementQueries.insertAchievement("BOOK_COMPLETED", bookId, "{}", now)
        assertEquals(1, db.achievementQueries.selectByTypeAndBook("BOOK_COMPLETED", bookId).executeAsList().size)

        // 11 AppSetting —— KV 覆盖写
        db.appSettingQueries.upsertSetting("playback.position", "{\"sessionId\":1}")
        db.appSettingQueries.upsertSetting("playback.position", "{\"sessionId\":2}")
        assertEquals("{\"sessionId\":2}", db.appSettingQueries.selectSetting("playback.position").executeAsOne())
    }

    @Test
    fun q2StudyQueueIncludesAllWords() {
        // 2026-09-15: ADR-002 - Q2 返回所有词（不管 mastery 状态），实现"母本永远可学"
        val db = newDatabase()
        val now = 1_760_000_000_000L
        db.wordBookQueries.insertOriginalWordBook("GRE Core", null, now, now)
        val bookId = db.wordBookQueries.selectLastInsertRowId().executeAsOne()
        repeat(3) { i ->
            db.wordQueries.insertWord("word$i", "word$i", null, null, null, now, now)
            val wordId = db.wordQueries.selectLastInsertRowId().executeAsOne()
            db.wordBookEntryQueries.insertEntry(bookId, wordId, i.toLong(), null, now)
        }
        db.wordMasteryQueries.markMastered(bookId, 2L, now) // word1 已掌握

        val queue = db.queriesQueries.selectStudyQueue(bookId).executeAsList()
        // Q2 现在返回所有词，不管 mastery 状态
        assertEquals(listOf(1L, 2L, 3L), queue.map { it.wordId }) // entryOrder 升序，包含已掌握词
        // Q3 countUnmastered 仍返回未掌握数（用于其他目的）
        assertEquals(2L, db.queriesQueries.countUnmastered(bookId).executeAsOne())
    }

    @Test
    fun q5DerivedWordBookCopiesRelationsWithoutMastery() {
        val db = newDatabase()
        val now = 1_760_000_000_000L
        db.wordBookQueries.insertOriginalWordBook("TOEFL Core", null, now, now)
        val bookId = db.wordBookQueries.selectLastInsertRowId().executeAsOne()
        repeat(3) { i ->
            db.wordQueries.insertWord("w$i", "w$i", null, null, null, now, now)
            val wordId = db.wordQueries.selectLastInsertRowId().executeAsOne()
            db.wordBookEntryQueries.insertEntry(bookId, wordId, i.toLong(), "pending-$i", now)
        }
        db.definitionEntryQueries.insertDefinitionEntry(1L, "noun", 1, 0, "meaning", "释义")
        val defId = db.definitionEntryQueries.selectLastInsertRowId().executeAsOne()
        db.exampleQueries.insertExample(defId, "a sentence", "例句", "TTS", null, null, null, null, 0)
        val exampleId = db.exampleQueries.selectExamplesForEntry(defId).executeAsList().single().exampleId
        db.wordBookEntryDefinitionQueries.insertEntryDefinition(1L, defId)
        db.wordBookEntryExampleSelectionQueries.insertExampleSelection(1L, exampleId)

        db.learningSessionQueries.insertSession(bookId, "ACTIVE", 10, now, null)
        val sessionId = db.learningSessionQueries.selectLastInsertRowId().executeAsOne()
        db.sessionWordQueries.insertSessionWord(sessionId, 1L, 0, 0, "PENDING")
        db.sessionWordQueries.insertSessionWord(sessionId, 2L, 0, 1, "MASTERED")
        db.sessionWordQueries.insertSessionWord(sessionId, 3L, 0, 2, "PENDING")
        db.wordMasteryQueries.markMastered(bookId, 2L, now)

        // 派生（D1 分支 B）：type=DERIVED + parentWordBookId + sourceSessionId
        db.wordBookQueries.insertDerivedWordBook("TOEFL Core 2026-09-01 08:30", null, bookId, sessionId, now, now)
        val derivedId = db.wordBookQueries.selectLastInsertRowId().executeAsOne()
        db.queriesQueries.copyEntryRelations(derivedId, now, bookId, sessionId)
        db.queriesQueries.copyEntryDefinitionRelations(bookId, derivedId)
        db.queriesQueries.copyExampleSelections(bookId, derivedId)

        val derived = db.wordBookQueries.selectWordBookById(derivedId).executeAsOne()
        assertEquals("DERIVED", derived.type)
        assertEquals(bookId, derived.parentWordBookId)
        assertEquals(sessionId, derived.sourceSessionId)

        // 只含未掌握词（w1、w3），entryOrder / pendingTranslation 保留（D3）
        val entries = db.wordBookEntryQueries.selectEntriesForWordBook(derivedId).executeAsList()
        assertEquals(listOf(0L, 2L), entries.map { it.entryOrder })
        assertEquals(listOf("pending-0", "pending-2"), entries.map { it.pendingTranslation })

        // 释义选择 + 例句选择关系同步复制（v2：逐例句选择）
        val w1Entry = entries.first { it.wordId == 1L }
        assertEquals(1, db.wordBookEntryDefinitionQueries
            .selectEntryDefinitions(w1Entry.wordBookEntryId).executeAsList().size)
        assertEquals(1, db.wordBookEntryExampleSelectionQueries
            .selectExampleSelections(w1Entry.wordBookEntryId).executeAsList().size)

        // D4：派生本不继承掌握状态；D2：母本不变
        assertEquals(0L, db.wordMasteryQueries.countMastered(derivedId).executeAsOne())
        assertEquals(3, db.wordBookEntryQueries.selectEntriesForWordBook(bookId).executeAsList().size)
    }
}
