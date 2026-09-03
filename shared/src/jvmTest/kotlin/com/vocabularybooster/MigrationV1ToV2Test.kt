package com.vocabularybooster

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.vocabularybooster.db.VocabularyDatabase
import kotlin.io.path.createTempFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * TC-DB-06 首个迁移链节点：v1 → v2（PROJECT_SPEC v1.3 例句选择粒度）。
 * 以手抄 v1 DDL 建库（git 28cc991 逐字基线）→ 落 v1 数据 → Schema.migrate(1, 2) → 断言数据保留 + v2 结构可用。
 * v1 的 includeExamples=0 语义在 v2 不可表达，迁移不产生例句选择行（DATABASE_SCHEMA §5，v1 从未发布）。
 */
class MigrationV1ToV2Test {

    /** git 28cc991 的 v1 建表语句（逐字基线，禁止改动）。 */
    private val v1Ddl: List<String> = listOf(
        """
        CREATE TABLE Word (
          wordId                 INTEGER PRIMARY KEY AUTOINCREMENT,
          text                   TEXT NOT NULL,
          normalizedText         TEXT NOT NULL,
          ipaAm                  TEXT,
          ipaBr                  TEXT,
          pronunciationAudioUri  TEXT,
          createdAt              INTEGER NOT NULL,
          updatedAt              INTEGER NOT NULL
        )
        """.trimIndent(),
        "CREATE UNIQUE INDEX Word_normalizedText ON Word(normalizedText);",
        """
        CREATE TABLE DefinitionEntry (
          definitionEntryId  INTEGER PRIMARY KEY AUTOINCREMENT,
          wordId             INTEGER NOT NULL REFERENCES Word(wordId) ON DELETE CASCADE,
          partOfSpeech       TEXT NOT NULL,
          partOfSpeechOrder  INTEGER NOT NULL,
          definitionOrder    INTEGER NOT NULL,
          meaningEN          TEXT NOT NULL,
          meaningCN          TEXT NOT NULL
        )
        """.trimIndent(),
        "CREATE UNIQUE INDEX DefinitionEntry_unique ON DefinitionEntry(wordId, partOfSpeech, definitionOrder);",
        "CREATE INDEX DefinitionEntry_displayOrder ON DefinitionEntry(wordId, partOfSpeechOrder, definitionOrder);",
        """
        CREATE TABLE Example (
          exampleId          INTEGER PRIMARY KEY AUTOINCREMENT,
          definitionEntryId  INTEGER NOT NULL REFERENCES DefinitionEntry(definitionEntryId) ON DELETE CASCADE,
          sentence           TEXT NOT NULL,
          chineseTranslation TEXT NOT NULL,
          sourceType         TEXT NOT NULL,
          sourceRef          TEXT,
          licenseNote        TEXT,
          audioUri           TEXT,
          audioDurationMs    INTEGER,
          exampleOrder       INTEGER NOT NULL DEFAULT 0
        )
        """.trimIndent(),
        "CREATE INDEX Example_byEntry ON Example(definitionEntryId, exampleOrder);",
        """
        CREATE TABLE WordBook (
          wordBookId            INTEGER PRIMARY KEY AUTOINCREMENT,
          type                  TEXT NOT NULL DEFAULT 'ORIGINAL',
          name                  TEXT NOT NULL,
          description           TEXT,
          parentWordBookId      INTEGER REFERENCES WordBook(wordBookId) ON DELETE RESTRICT,
          sourceSessionId       INTEGER REFERENCES LearningSession(sessionId),
          createdAt             INTEGER NOT NULL,
          updatedAt             INTEGER NOT NULL,
          CHECK (type IN ('ORIGINAL','DERIVED'))
        )
        """.trimIndent(),
        "CREATE INDEX WordBook_parent ON WordBook(parentWordBookId);",
        """
        CREATE TABLE WordBookEntry (
          wordBookEntryId  INTEGER PRIMARY KEY AUTOINCREMENT,
          wordBookId       INTEGER NOT NULL REFERENCES WordBook(wordBookId) ON DELETE CASCADE,
          wordId           INTEGER NOT NULL REFERENCES Word(wordId),
          entryOrder       INTEGER NOT NULL,
          pendingTranslation  TEXT,
          addedAt          INTEGER NOT NULL
        )
        """.trimIndent(),
        "CREATE UNIQUE INDEX WordBookEntry_unique ON WordBookEntry(wordBookId, wordId);",
        "CREATE INDEX WordBookEntry_order ON WordBookEntry(wordBookId, entryOrder);",
        """
        CREATE TABLE WordBookEntryDefinition (
          wordBookEntryDefinitionId  INTEGER PRIMARY KEY AUTOINCREMENT,
          wordBookEntryId            INTEGER NOT NULL REFERENCES WordBookEntry(wordBookEntryId) ON DELETE CASCADE,
          definitionEntryId          INTEGER NOT NULL REFERENCES DefinitionEntry(definitionEntryId),
          includeExamples            INTEGER NOT NULL DEFAULT 1
        )
        """.trimIndent(),
        "CREATE UNIQUE INDEX WordBookEntryDefinition_unique ON WordBookEntryDefinition(wordBookEntryId, definitionEntryId);",
        """
        CREATE TABLE WordMastery (
          wordBookId  INTEGER NOT NULL REFERENCES WordBook(wordBookId) ON DELETE CASCADE,
          wordId      INTEGER NOT NULL REFERENCES Word(wordId),
          masteredAt  INTEGER NOT NULL,
          PRIMARY KEY (wordBookId, wordId)
        )
        """.trimIndent(),
        """
        CREATE TABLE LearningSession (
          sessionId   INTEGER PRIMARY KEY AUTOINCREMENT,
          wordBookId  INTEGER NOT NULL REFERENCES WordBook(wordBookId) ON DELETE RESTRICT,
          status      TEXT NOT NULL,
          groupSize   INTEGER NOT NULL,
          startedAt   INTEGER NOT NULL,
          endedAt     INTEGER
        )
        """.trimIndent(),
        """
        CREATE TABLE SessionWord (
          sessionId     INTEGER NOT NULL REFERENCES LearningSession(sessionId) ON DELETE CASCADE,
          wordId        INTEGER NOT NULL REFERENCES Word(wordId),
          groupIndex    INTEGER NOT NULL,
          orderInGroup  INTEGER NOT NULL,
          status        TEXT NOT NULL,
          masteredAt    INTEGER,
          PRIMARY KEY (sessionId, wordId)
        )
        """.trimIndent(),
        "CREATE INDEX SessionWord_queue ON SessionWord(sessionId, groupIndex, orderInGroup);",
        """
        CREATE TABLE Achievement (
          achievementId  INTEGER PRIMARY KEY AUTOINCREMENT,
          type            TEXT NOT NULL,
          wordBookId      INTEGER REFERENCES WordBook(wordBookId),
          payloadJson     TEXT NOT NULL,
          earnedAt        INTEGER NOT NULL
        )
        """.trimIndent(),
        "CREATE UNIQUE INDEX Achievement_unique ON Achievement(type, wordBookId);",
        "CREATE TABLE AppSetting (key TEXT PRIMARY KEY, valueJson TEXT NOT NULL);",
    )

    private val v1Seed: List<String> = listOf(
        "INSERT INTO Word(wordId, text, normalizedText, ipaAm, ipaBr, pronunciationAudioUri, createdAt, updatedAt)" +
            " VALUES (1, 'boost', 'boost', '/buːst/', NULL, NULL, 1, 1);",
        "INSERT INTO DefinitionEntry(definitionEntryId, wordId, partOfSpeech, partOfSpeechOrder, definitionOrder, meaningEN, meaningCN)" +
            " VALUES (10, 1, 'verb', 0, 1, 'to push up', '增强');",
        "INSERT INTO Example(exampleId, definitionEntryId, sentence, chineseTranslation, sourceType, sourceRef, licenseNote, audioUri, audioDurationMs, exampleOrder)" +
            " VALUES (100, 10, 'Sales boosted sharply.', '销售急剧提升。', 'TTS', NULL, NULL, NULL, NULL, 0);",
        "INSERT INTO WordBook(wordBookId, type, name, description, parentWordBookId, sourceSessionId, createdAt, updatedAt)" +
            " VALUES (5, 'ORIGINAL', 'Old Book', NULL, NULL, NULL, 1, 1);",
        "INSERT INTO WordBookEntry(wordBookEntryId, wordBookId, wordId, entryOrder, pendingTranslation, addedAt)" +
            " VALUES (50, 5, 1, 0, NULL, 1);",
        "INSERT INTO WordBookEntryDefinition(wordBookEntryDefinitionId, wordBookEntryId, definitionEntryId, includeExamples)" +
            " VALUES (500, 50, 10, 1);",
        "INSERT INTO WordMastery(wordBookId, wordId, masteredAt) VALUES (5, 1, 1);",
    )

    @Test
    fun migrateV1ToV2PreservesDataAndEnablesExampleSelection() {
        assertEquals(2L, VocabularyDatabase.Schema.version)

        // 原始驱动建 v1 库（不建 v2 schema），落 v1 数据后走正式迁移
        val path = createTempFile(prefix = "vb-mig-", suffix = ".sqlite").toFile().absolutePath.replace('\\', '/')
        val v1Driver = JdbcSqliteDriver("jdbc:sqlite:$path")
        try {
            (v1Ddl + v1Seed).forEach { sql -> v1Driver.execute(null, sql, 0) }
            VocabularyDatabase.Schema.migrate(v1Driver, 1, 2)
        } finally {
            v1Driver.close()
        }

        // 迁移后重开，用 v2 生成代码断言
        val reopened = TestDb.fileExisting(path)
        try {
            val db = reopened.database
            assertEquals(1L, db.wordQueries.countAll().executeAsOne())
            assertEquals("/buːst/", db.wordQueries.selectByNormalizedText("boost").executeAsOne().ipaAm)

            assertEquals(listOf("boost"), db.wordBookEntryQueries.selectEntryWordsForBook(5L).executeAsList().map { it.wordText })

            // 释义选择行保留（includeExamples 列已随表重建消失）
            assertEquals(1, db.wordBookEntryDefinitionQueries.selectEntryDefinitions(50L).executeAsList().size)

            // 掌握行保留（D4 作用域不受迁移影响）
            assertEquals(1L, db.wordMasteryQueries.countMastered(5L).executeAsOne())

            // v1 includeExamples=1 的例句语义不自动转译为例句选择行（如实丢弃，§5）
            assertTrue(db.wordBookEntryExampleSelectionQueries.selectExampleSelections(50L).executeAsList().isEmpty())
            // 新表可用：补一行例句选择
            db.wordBookEntryExampleSelectionQueries.insertExampleSelection(50L, 100L)
            assertEquals(1, db.wordBookEntryExampleSelectionQueries.selectExampleSelections(50L).executeAsList().size)
        } finally {
            reopened.close()
        }
    }
}
