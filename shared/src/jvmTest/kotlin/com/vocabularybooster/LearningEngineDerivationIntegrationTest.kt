package com.vocabularybooster

import com.vocabularybooster.data.SqlDelightLearningSessionRepository
import com.vocabularybooster.data.SqlDelightLearningSettingsRepository
import com.vocabularybooster.data.SqlDelightWordBookRepository
import com.vocabularybooster.learning.AdvanceResult
import com.vocabularybooster.learning.DefaultLearningEngine
import com.vocabularybooster.learning.ExitResult
import com.vocabularybooster.learning.MasteryMarker
import com.vocabularybooster.learning.MasterySource
import com.vocabularybooster.learning.ResumeResult
import com.vocabularybooster.learning.StartResult
import com.vocabularybooster.learning.WordBookDeriver
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * WordBookDeriver 真实 SQLDelight 集成（Phase 3 Step 5D，TC-LE-07/08、TC-DB-05 同源属性）：
 * 37/100 部分掌握 → 派生 63 词（entryOrder 原样保留不重编号、pendingTranslation 保留）、
 * 释义 + 例句选择关系逐 ID 一致（仅 entryId 不同）、血缘（type=DERIVED + parent + sourceSession）、
 * 命名精确断言（注入时区 yyyy-MM-dd HH:mm、重名 -2/-3）；母本/WordMastery/Word 三向隔离；
 * 跨 close/reopen 持久化；Q5 事务回滚无半本；边界规模；
 * mid-session 母本编辑三边界（2026-09-04 裁决，交集语义）：会话外新增/会话中移除 → 不复制；
 * 交集为空（Case 3）→ 不建空 DERIVED 本（derivedWordBookId=null、ABANDONED、endedAt 写入）。
 * Fake 驱动的分支路由/命名器见 commonTest WordBookDeriverTest + LearningEngineExitTest。
 */
class LearningEngineDerivationIntegrationTest {

    private val t0 = 1_760_000_000_000L // 2025-10-09T08:53:20Z（FixedClock 默认值；精确命名断言用 HH:mm=08:53）

    /**
     * 种子：1 本 N 词；每词 2 条释义（NOUN 0/0 + VERB 1/0），释义 1 带 2 例句、释义 2 带 1 例句；
     * 词条选择 = 两条释义全选 + 例句逐条选择（释义 1 的 2 条 + 释义 2 的 1 条）——
     * 选择关系刻意非对称，逐 ID 复制才可被断言区分。首词带 pendingTranslation 验证保留。
     */
    private fun TestDb.seedBookWithSelections(wordCount: Int, label: String = "d"): Pair<Long, List<Long>> {
        var bookId = 0L
        val wordIds = mutableListOf<Long>()
        database.transaction {
            database.wordBookQueries.insertOriginalWordBook("$label-book", null, t0, t0)
            bookId = database.wordBookQueries.selectLastInsertRowId().executeAsOne()
            repeat(wordCount) { i ->
                database.wordQueries.insertWord("$label-$i", "$label-$i", null, null, null, t0, t0)
                val wordId = database.wordQueries.selectLastInsertRowId().executeAsOne()
                wordIds += wordId
                database.wordBookEntryQueries.insertEntry(
                    wordBookId = bookId,
                    wordId = wordId,
                    entryOrder = i.toLong(),
                    pendingTranslation = if (i == 0) "$label-待译" else null,
                    addedAt = t0,
                )
                val entryId = database.wordBookEntryQueries.selectLastInsertRowId().executeAsOne()
                // 释义 1（NOUN）：例句 e1、e2；释义 2（VERB）：例句 e3
                listOf("NOUN" to 0L, "VERB" to 1L).forEach { (pos, posOrder) ->
                    database.definitionEntryQueries.insertDefinitionEntry(
                        wordId = wordId,
                        partOfSpeech = pos,
                        partOfSpeechOrder = posOrder,
                        definitionOrder = 0L,
                        meaningEN = "$label-m-en-$pos",
                        meaningCN = "$label-m-cn-$pos",
                    )
                    val definitionId = database.definitionEntryQueries.selectLastInsertRowId().executeAsOne()
                    database.wordBookEntryDefinitionQueries.insertEntryDefinition(entryId, definitionId)
                    val exampleCount = if (posOrder == 0L) 2 else 1
                    repeat(exampleCount) { e ->
                        database.exampleQueries.insertExample(
                            definitionEntryId = definitionId,
                            sentence = "$label-sentence-$e",
                            chineseTranslation = "$label-译文-$e",
                            sourceType = "TTS",
                            sourceRef = null,
                            licenseNote = null,
                            audioUri = null,
                            audioDurationMs = null,
                            exampleOrder = e.toLong(),
                        )
                        // last_insert_rowid() 为连接级全局值：同事务内借用任一文件的查询即可
                        // （Example.sq 未定义同名查询）
                        val exampleId = database.definitionEntryQueries.selectLastInsertRowId().executeAsOne()
                        database.wordBookEntryExampleSelectionQueries.insertExampleSelection(entryId, exampleId)
                    }
                }
            }
        }
        return bookId to wordIds
    }

    private fun newEngine(
        db: TestDb,
        clock: FixedClock,
        timeZone: TimeZone = TimeZone.UTC, // 命名断言确定性；本地时区格式化测试注入其它时区
    ): DefaultLearningEngine {
        val sessionRepo = SqlDelightLearningSessionRepository(db.database, clock, DispatchersForTest)
        return DefaultLearningEngine(
            sessionRepository = sessionRepo,
            settingsRepository = SqlDelightLearningSettingsRepository(db.database, DispatchersForTest),
            masteryMarker = MasteryMarker(sessionRepo, clock),
            wordBookDeriver = WordBookDeriver(
                wordBookRepository = SqlDelightWordBookRepository(db.database, clock, DispatchersForTest),
                clock = clock,
                timeZone = timeZone,
            ),
        )
    }

    /** wordId → (选中释义 ID 集, 选中例句 ID 集)：母本/派生本选择一致性比对的数据源。 */
    private fun TestDb.selectionsByWord(bookId: Long): Map<Long, Pair<Set<Long>, Set<Long>>> =
        database.wordBookEntryQueries.selectEntriesForWordBook(bookId).executeAsList().associate { entry ->
            val definitions = database.wordBookEntryDefinitionQueries
                .selectEntryDefinitions(entry.wordBookEntryId).executeAsList()
                .map { it.definitionEntryId }.toSet()
            val examples = database.wordBookEntryExampleSelectionQueries
                .selectExampleSelections(entry.wordBookEntryId).executeAsList()
                .map { it.exampleId }.toSet()
            entry.wordId to (definitions to examples)
        }

    @Test
    fun deriveThirtySevenOfHundredMirrorsTCLE07() = runTest {
        val db = TestDb.inMemory()
        val clock = FixedClock()
        val (bookId, wordIds) = db.seedBookWithSelections(100)
        val engine = newEngine(db, clock)
        val sessionId = assertIs<StartResult.Started>(engine.startSession(bookId)).snapshot.session.sessionId
        val motherBefore = db.selectionsByWord(bookId)
        clock.advanceMillis(9_000) // 派生时刻与种子时刻可区分

        repeat(37) { i ->
            assertIs<AdvanceResult.NextWord>(engine.advance(sessionId))
            engine.markMastered(sessionId, wordIds[i], MasterySource.BUTTON)
        }
        val result = engine.exitSession(sessionId)

        val derivedId = assertNotNull(result.derivedWordBookId) // 分支 B：37 掌握 + Q3=63 > 0
        // 血缘 + 命名（D3 / §8 步骤 1–2）
        val derived = db.database.wordBookQueries.selectWordBookById(derivedId).executeAsOne()
        assertEquals("DERIVED", derived.type)
        assertEquals(bookId, derived.parentWordBookId)
        assertEquals(sessionId, derived.sourceSessionId)
        // 命名精确断言（LE spec §8 步骤 1；UTC）：t0=08:53:20Z + 9s 推进仍在 08:53 分内
        assertEquals("d-book 2025-10-09 08:53", derived.name)
        assertTrue(derived.createdAt >= t0) // 时间戳已更新

        // 内容：恰 63 词 = 会话非 MASTERED 词；entryOrder 原样保留（37..99，不重编号）
        val derivedEntries = db.database.wordBookEntryQueries
            .selectEntriesForWordBook(derivedId).executeAsList()
        assertEquals(wordIds.drop(37), derivedEntries.map { it.wordId })
        assertEquals((37L until 100L).toList(), derivedEntries.map { it.entryOrder })
        // 母本首词（带 pendingTranslation）已掌握不进派生本：其母本行原样保留（不丢失）；
        // 派生侧 pendingTranslation 逐字保留见边界用例（末词掌握、首词进派生本场景）
        assertEquals(
            "d-待译",
            db.database.wordBookEntryQueries.selectEntriesForWordBook(bookId).executeAsList()
                .first { it.wordId == wordIds[0] }.pendingTranslation,
        )

        // 选择关系逐 ID 一致（TC-DB-05 同源属性：仅 entryId 不同）
        val motherAfter = db.selectionsByWord(bookId)
        assertEquals(motherBefore, motherAfter) // 母本选择零变更（D2）
        val derivedSelections = db.selectionsByWord(derivedId)
        assertEquals(motherBefore.filterKeys { it in wordIds.drop(37).toSet() }, derivedSelections)

        // Mastery 隔离（D4）：母本 37 行保留；派生本零行
        assertEquals(37L, db.database.wordMasteryQueries.countMastered(bookId).executeAsOne())
        assertEquals(0L, db.database.wordMasteryQueries.countMastered(derivedId).executeAsOne())
        // Word/DefinitionEntry 复用不复制：派生词条仍可经 Q1 读到同两条释义
        assertEquals(2, db.database.definitionEntryQueries
            .selectDefinitionsForWord(wordIds[50]).executeAsList().size)
        // 会话状态：ABANDONED（分支 B 的会话半程，Step 5C 语义不变）
        assertEquals("ABANDONED", db.database.learningSessionQueries.selectSessionById(sessionId)
            .executeAsOne().status)
        db.close()
    }

    @Test
    fun deriveSurvivesRestartWithFullSelectionsAndDeleteGuard() = runTest {
        val db = TestDb.file()
        val clock = FixedClock()
        val (bookId, wordIds) = db.seedBookWithSelections(10, label = "r")
        var sessionId = 0L
        var derivedBefore: Pair<List<Long>, Map<Long, Pair<Set<Long>, Set<Long>>>>? = null
        try {
            val engine = newEngine(db, clock)
            sessionId = assertIs<StartResult.Started>(engine.startSession(bookId)).snapshot.session.sessionId
            repeat(3) { i ->
                assertIs<AdvanceResult.NextWord>(engine.advance(sessionId))
                engine.markMastered(sessionId, wordIds[i], MasterySource.VOICE)
            }
            val derivedId = assertNotNull(engine.exitSession(sessionId).derivedWordBookId)
            derivedBefore = db.database.wordBookEntryQueries
                .selectEntriesForWordBook(derivedId).executeAsList().map { it.wordId } to
                db.selectionsByWord(derivedId)
        } finally {
            db.close() // 模拟进程死亡
        }

        val reopened = TestDb.fileExisting(db.path!!)
        try {
            val derivedRow = reopened.database.wordBookQueries.selectAllWordBooks().executeAsList()
                .single { it.type == "DERIVED" }
            assertEquals(bookId, derivedRow.parentWordBookId)
            assertEquals(sessionId, derivedRow.sourceSessionId)
            val entriesAfter = reopened.database.wordBookEntryQueries
                .selectEntriesForWordBook(derivedRow.wordBookId).executeAsList()
            assertEquals(derivedBefore?.first, entriesAfter.map { it.wordId }) // 完整持久化
            assertEquals(derivedBefore?.second, reopened.selectionsByWord(derivedRow.wordBookId))
            assertEquals(0L, reopened.database.wordMasteryQueries
                .countMastered(derivedRow.wordBookId).executeAsOne())
            // 派生子本挂起母本删除守卫（Phase 2 守卫数据链路打通）
            assertEquals(1L, reopened.database.wordBookQueries
                .countDerivedChildren(bookId).executeAsOne())
            // 会话终态不可复活
            assertEquals(
                ResumeResult.Reason.SESSION_NOT_ACTIVE,
                assertIs<ResumeResult.Rejected>(newEngine(reopened, FixedClock()).resumeSession(sessionId)).reason,
            )
        } finally {
            reopened.close()
        }
    }

    @Test
    fun deriveDuplicateNameAppendsSuffix2Then3() = runTest {
        // 命名冲突（LE spec §8 步骤 1，精确断言）：基名被预占 → 第一次派生 -2；
        // 第二次派生（第二次会话）基名与 -2 均被占 → -3
        val db = TestDb.inMemory()
        val clock = FixedClock()
        val (bookId, wordIds) = db.seedBookWithSelections(4, label = "n")
        // 预占精确基名（另一本 ORIGINAL 恰好叫派生将得之名）
        db.database.wordBookQueries.insertOriginalWordBook("n-book 2025-10-09 08:53", null, t0, t0)
        val engine = newEngine(db, clock)

        val session1 = assertIs<StartResult.Started>(engine.startSession(bookId)).snapshot.session.sessionId
        assertIs<AdvanceResult.NextWord>(engine.advance(session1))
        engine.markMastered(session1, wordIds[0], MasterySource.BUTTON)
        val derived1 = assertNotNull(engine.exitSession(session1).derivedWordBookId)

        assertEquals("n-book 2025-10-09 08:53-2", // 撞基名 → -2
            db.database.wordBookQueries.selectWordBookById(derived1).executeAsOne().name)

        val session2 = assertIs<StartResult.Started>(engine.startSession(bookId)).snapshot.session.sessionId
        assertIs<AdvanceResult.NextWord>(engine.advance(session2))
        engine.markMastered(session2, wordIds[1], MasterySource.BUTTON)
        val derived2 = assertNotNull(engine.exitSession(session2).derivedWordBookId)

        assertEquals("n-book 2025-10-09 08:53-3", // 基名与 -2 均被占 → -3
            db.database.wordBookQueries.selectWordBookById(derived2).executeAsOne().name)
        db.close()
    }

    @Test
    fun deriveNamesInInjectedLocalTimeZone() = runTest {
        // 本地时区格式化（LE spec §8 步骤 1：注入时区）：同一 Instant（UTC 08:53）在
        // Asia/Shanghai 墙上时间为 16:53——期望值从 LocalDateTime 反推，不做 epoch 心算
        val db = TestDb.inMemory()
        val at = LocalDateTime(2025, 10, 9, 8, 53).toInstant(TimeZone.UTC)
        val clock = FixedClock(at.toEpochMilliseconds())
        val (bookId, wordIds) = db.seedBookWithSelections(2, label = "tz")
        val engine = newEngine(db, clock, timeZone = TimeZone.of("Asia/Shanghai"))
        val sessionId = assertIs<StartResult.Started>(engine.startSession(bookId)).snapshot.session.sessionId
        assertIs<AdvanceResult.NextWord>(engine.advance(sessionId))
        engine.markMastered(sessionId, wordIds[0], MasterySource.BUTTON)

        val derivedId = assertNotNull(engine.exitSession(sessionId).derivedWordBookId)

        assertEquals("tz-book 2025-10-09 16:53",
            db.database.wordBookQueries.selectWordBookById(derivedId).executeAsOne().name)
        db.close()
    }

    @Test
    fun deriveKeepsSourceOrderAndPendingTranslationAtBoundaries() = runTest {
        // 边界规模（§14 Basic）：10/11/20/21 词各掌握**末**词 → 派生 N-1 词、entryOrder 原样、
        // 首词（带 pendingTranslation）进派生本逐字保留。markMastered 不要求词处于 PLAYING
        // （Step 3 契约：条件 UPDATE 按 (sessionId, wordId)），直接掌握末词无需推进
        listOf(10, 11, 20, 21).forEach { wordCount ->
            val db = TestDb.inMemory()
            val clock = FixedClock()
            val (bookId, wordIds) = db.seedBookWithSelections(wordCount, label = "b$wordCount")
            val engine = newEngine(db, clock)
            val sessionId = assertIs<StartResult.Started>(engine.startSession(bookId)).snapshot.session.sessionId
            engine.markMastered(sessionId, wordIds[wordCount - 1], MasterySource.BUTTON)

            val derivedId = assertNotNull(engine.exitSession(sessionId).derivedWordBookId)

            val entries = db.database.wordBookEntryQueries
                .selectEntriesForWordBook(derivedId).executeAsList()
            assertEquals(wordIds.dropLast(1), entries.map { it.wordId }) // 派生 = 除末词外全部
            assertEquals((0 until wordCount - 1).map { it.toLong() }, entries.map { it.entryOrder })
            assertEquals("b$wordCount-待译", entries.first().pendingTranslation) // 译文暂存逐字保留
            db.close()
        }
    }

    @Test
    fun exitZeroMasteryCreatesNoDerivedBook() = runTest {
        val db = TestDb.inMemory()
        val clock = FixedClock()
        val (bookId, _) = db.seedBookWithSelections(5, label = "z")
        val engine = newEngine(db, clock)
        val sessionId = assertIs<StartResult.Started>(engine.startSession(bookId)).snapshot.session.sessionId

        assertEquals(ExitResult(derivedWordBookId = null), engine.exitSession(sessionId)) // 分支 A

        assertEquals(1, db.database.wordBookQueries.selectAllWordBooks().executeAsList().size) // 无新本
        assertEquals(0L, db.database.wordBookQueries.countDerivedChildren(bookId).executeAsOne())
        assertEquals("ABANDONED", db.database.learningSessionQueries.selectSessionById(sessionId)
            .executeAsOne().status) // 会话状态与学习历史保留（TC-LE-08）
        assertEquals(0L, db.database.wordMasteryQueries.countMastered(bookId).executeAsOne())
        db.close()
    }

    @Test
    fun deriveTransactionRollbackLeavesNoPartialBook() = runTest {
        // Q5 事务机制级回滚验证：repository.deriveWordBook 将同样四条语句包在一个
        // transactionWithResult 内且无中途失败注入点（by design），故在语句序列层
        // 直接强制中途失败，断言全有或全无（JDBC 驱动 FK 关闭 → 孤儿行若未回滚必可见）
        val db = TestDb.inMemory()
        val clock = FixedClock()
        val (bookId, wordIds) = db.seedBookWithSelections(4, label = "k")
        val engine = newEngine(db, clock)
        val sessionId = assertIs<StartResult.Started>(engine.startSession(bookId)).snapshot.session.sessionId
        assertIs<AdvanceResult.NextWord>(engine.advance(sessionId))
        engine.markMastered(sessionId, wordIds[0], MasterySource.BUTTON)
        val booksBefore = db.database.wordBookQueries.selectAllWordBooks().executeAsList().size
        val entriesBefore = db.database.wordBookEntryQueries
            .countEntriesForWordBook(bookId).executeAsOne()

        val boom = RuntimeException("模拟派生中途失败")
        try {
            db.database.transactionWithResult<Long> {
                db.database.wordBookQueries.insertDerivedWordBook(
                    name = "k-book 派生", description = null,
                    parentWordBookId = bookId, sourceSessionId = sessionId,
                    createdAt = t0, updatedAt = t0,
                )
                val newBookId = db.database.wordBookQueries.selectLastInsertRowId().executeAsOne()
                db.database.queriesQueries.copyEntryRelations(
                    newBookId = newBookId, sessionId = sessionId, sourceBookId = bookId, now = t0,
                )
                db.database.queriesQueries.copyEntryDefinitionRelations(newBookId = newBookId, sourceBookId = bookId)
                db.database.queriesQueries.copyExampleSelections(newBookId = newBookId, sourceBookId = bookId)
                throw boom
            }
        } catch (e: RuntimeException) {
            assertEquals(boom, e) // 事务以异常收尾 → 回滚
        }

        assertEquals(booksBefore, db.database.wordBookQueries.selectAllWordBooks().executeAsList().size) // 无半本
        assertEquals(entriesBefore, db.database.wordBookEntryQueries
            .countEntriesForWordBook(bookId).executeAsOne()) // 无孤儿 entry
        assertEquals(0L, db.database.wordBookQueries.countBooksNamed("k-book 派生").executeAsOne())
        // 失败后状态干净：真实路径派生仍可成功
        val derivedId = assertNotNull(engine.exitSession(sessionId).derivedWordBookId)
        assertEquals(3L, db.database.wordBookEntryQueries
            .countEntriesForWordBook(derivedId).executeAsOne())
        db.close()
    }

    @Test
    fun exitWithAllSessionWordsMasteredCompletes() = runTest {
        // 2026-09-15: ADR-002 - 退出基于 session snapshot，不基于 Q3
        // 会话全部 mastered → COMPLETED，不再因 Q3>0 而 ABANDONED
        val db = TestDb.inMemory()
        val clock = FixedClock()
        val (bookId, wordIds) = db.seedBookWithSelections(2, label = "g")
        val engine = newEngine(db, clock)
        val sessionId = assertIs<StartResult.Started>(engine.startSession(bookId)).snapshot.session.sessionId
        wordIds.forEach { wordId ->
            assertIs<AdvanceResult.NextWord>(engine.advance(sessionId))
            engine.markMastered(sessionId, wordId, MasterySource.BUTTON)
        }
        // 会话中途书新增 3 词（会话队列已固化，不含新词）
        repeat(3) { i ->
            db.database.wordQueries.insertWord("g-new-$i", "g-new-$i", null, null, null, t0, t0)
            val newWordId = db.database.wordQueries.selectLastInsertRowId().executeAsOne()
            db.database.wordBookEntryQueries.insertEntry(bookId, newWordId, (10 + i).toLong(), null, t0)
        }

        val exitResult = engine.exitSession(sessionId)

        assertNull(exitResult.derivedWordBookId) // COMPLETED 不派生
        val sessionRow = db.database.learningSessionQueries.selectSessionById(sessionId).executeAsOne()
        assertEquals("COMPLETED", sessionRow.status) // snapshot 全部 mastered → COMPLETED
        assertNotNull(sessionRow.endedAt) // endedAt 正常写入
        assertEquals(0L, db.database.wordBookQueries // 零派生子本
            .countDerivedChildren(bookId).executeAsOne())
        assertEquals(3L, db.database.queriesQueries.countUnmastered(bookId).executeAsOne()) // 3 个新词未掌握
        assertNull(db.database.learningSessionQueries.selectActiveSession().executeAsOneOrNull())
        db.close()
    }

    @Test
    fun deriveExcludesSessionWordRemovedFromMother() = runTest {
        // Case 2 裁决定案（2026-09-04，交集语义，LE spec v1.3 §8 步骤 3）：会话中词 w2
        // 从母本删除（SessionWord 行仍在）→ **不复制**——复制集合 = 当前母本现存词条 ∩
        // SessionWord 非 MASTERED；w2 母本行已删（entryOrder/pendingTranslation 无处取，
        // 亦不得改用 SessionWord 组序）。正常恢复路径由 Step 5B resume recovery 清理悬挂行。
        val db = TestDb.inMemory()
        val clock = FixedClock()
        val (bookId, wordIds) = db.seedBookWithSelections(5, label = "m")
        val engine = newEngine(db, clock)
        val sessionId = assertIs<StartResult.Started>(engine.startSession(bookId)).snapshot.session.sessionId
        assertIs<AdvanceResult.NextWord>(engine.advance(sessionId))
        engine.markMastered(sessionId, wordIds[0], MasterySource.BUTTON)
        // Phase 2 真实移词路径（entry + 本内 mastery 同一事务删除；Word 不动）
        SqlDelightWordBookRepository(db.database, clock, DispatchersForTest)
            .removeWordFromWordBook(bookId, wordIds[2])
        assertIs<AdvanceResult.NextWord>(engine.advance(sessionId))
        engine.markMastered(sessionId, wordIds[1], MasterySource.BUTTON)

        val derivedId = assertNotNull(engine.exitSession(sessionId).derivedWordBookId)

        val derivedEntries = db.database.wordBookEntryQueries
            .selectEntriesForWordBook(derivedId).executeAsList().map { it.wordId }
        assertTrue(wordIds[2] !in derivedEntries) // 已删词不进派生本（当前行为）
        assertEquals(listOf(wordIds[3], wordIds[4]), derivedEntries) // 派生 = 交集 {w3,w4}
        // 母本不被派生触碰：现存词条仍为 4（删除操作所致，非派生副作用）
        assertEquals(4L, db.database.wordBookEntryQueries
            .countEntriesForWordBook(bookId).executeAsOne())
        db.close()
    }

    @Test
    fun deriveExcludesMidSessionMotherAdditions() = runTest {
        // Case 1 裁决定案（2026-09-04，交集语义）：会话开始后母本新增未掌握词 D'
        //（不在 SessionWord）→ **不复制**——D' 不属本次学习产生的快照（DOMAIN_MODEL v1.4
        // §2.4），留母本待下次会话（Q3 计入）。
        val db = TestDb.inMemory()
        val clock = FixedClock()
        val (bookId, wordIds) = db.seedBookWithSelections(5, label = "p")
        val engine = newEngine(db, clock)
        val sessionId = assertIs<StartResult.Started>(engine.startSession(bookId)).snapshot.session.sessionId
        assertIs<AdvanceResult.NextWord>(engine.advance(sessionId))
        engine.markMastered(sessionId, wordIds[0], MasterySource.BUTTON)
        // 会话外新增 D'：母本现存、未掌握、不在 SessionWord
        db.database.wordQueries.insertWord("p-new", "p-new", null, null, null, t0, t0)
        val additionId = db.database.wordQueries.selectLastInsertRowId().executeAsOne()
        db.database.wordBookEntryQueries.insertEntry(bookId, additionId, 100L, null, t0)

        val derivedId = assertNotNull(engine.exitSession(sessionId).derivedWordBookId)

        val derivedEntries = db.database.wordBookEntryQueries
            .selectEntriesForWordBook(derivedId).executeAsList().map { it.wordId }
        assertTrue(additionId !in derivedEntries) // D' 不进派生本（当前行为）
        assertEquals(wordIds.drop(1), derivedEntries) // 派生 = 会话未掌握 ∩ 母本现存 = {w1..w4}
        // D' 仍留在母本待下次会话学习（Q3 计入）
        assertEquals(5L, db.database.queriesQueries.countUnmastered(bookId).executeAsOne())
        db.close()
    }

    @Test
    fun exitEmptyIntersectionCreatesNoDerivedBookWhenRemaindersDeleted() = runTest {
        // Case 3 裁决（2026-09-04，交集语义）：会话剩余词 w1/w2 全部移出母本 + 母本新增
        // 2 词保持 REMAINING>0 → 分支 B 字面成立，但交集 = ∅ → **不建空 DERIVED 本**
        //（derivedWordBookId=null、会话 ABANDONED、endedAt 写入；母本新增词留待下次会话）
        val db = TestDb.inMemory()
        val clock = FixedClock()
        val (bookId, wordIds) = db.seedBookWithSelections(3, label = "q")
        val engine = newEngine(db, clock)
        val sessionId = assertIs<StartResult.Started>(engine.startSession(bookId)).snapshot.session.sessionId
        assertIs<AdvanceResult.NextWord>(engine.advance(sessionId))
        engine.markMastered(sessionId, wordIds[0], MasterySource.BUTTON) // MASTERED=1>0
        // 剩余词 w1/w2 全部从母本删除（真实路径）
        SqlDelightWordBookRepository(db.database, clock, DispatchersForTest)
            .removeWordFromWordBook(bookId, wordIds[1])
        SqlDelightWordBookRepository(db.database, clock, DispatchersForTest)
            .removeWordFromWordBook(bookId, wordIds[2])
        // 母本新增 2 词：REMAINING=Q3=2>0 → 分支 B 字面成立
        repeat(2) { i ->
            db.database.wordQueries.insertWord("q-new-$i", "q-new-$i", null, null, null, t0, t0)
            val newId = db.database.wordQueries.selectLastInsertRowId().executeAsOne()
            db.database.wordBookEntryQueries.insertEntry(bookId, newId, (10 + i).toLong(), null, t0)
        }

        val exitResult = engine.exitSession(sessionId)

        assertNull(exitResult.derivedWordBookId) // 交集为空 → 不建空本
        val sessionRow = db.database.learningSessionQueries.selectSessionById(sessionId).executeAsOne()
        assertEquals("ABANDONED", sessionRow.status)
        assertNotNull(sessionRow.endedAt) // endedAt 正常写入
        assertEquals(0L, db.database.wordBookQueries // 零派生子本（含无空 DERIVED 行）
            .countDerivedChildren(bookId).executeAsOne())
        assertEquals(2L, db.database.queriesQueries.countUnmastered(bookId).executeAsOne()) // 母本待学=新增 2 词
        db.close()
    }
}
