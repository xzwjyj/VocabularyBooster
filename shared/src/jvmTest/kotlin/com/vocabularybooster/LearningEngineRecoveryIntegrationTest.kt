package com.vocabularybooster

import com.vocabularybooster.data.SqlDelightLearningSessionRepository
import com.vocabularybooster.data.SqlDelightLearningSettingsRepository
import com.vocabularybooster.data.SqlDelightWordBookRepository
import com.vocabularybooster.learning.AdvanceResult
import com.vocabularybooster.learning.DefaultLearningEngine
import com.vocabularybooster.learning.MasteryMarker
import com.vocabularybooster.learning.MasteryResult
import com.vocabularybooster.learning.MasterySource
import com.vocabularybooster.learning.ResumeResult
import com.vocabularybooster.learning.StartResult
import com.vocabularybooster.learning.WordBookDeriver
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * TC-LE-10 恢复完整性真实 SQLDelight 集成（Phase 3 Step 5B）：
 * Case A 书删（FK 关闭驱动下可达的悬挂态）→ 跨 close/reopen 恢复 → 单事务 ABANDONED 落库 + 幂等；
 * Case B 经真实移词路径（removeWordFromWordBook：entry + mastery 同事务删除）→ 恢复剔除悬挂行、
 * placement 不重编号、其余照常；100 词删 5 词全队列续学无跳跃。
 * Fake 驱动的行为矩阵见 commonTest LearningEngineRecoveryTest。
 */
class LearningEngineRecoveryIntegrationTest {

    /** 与 DefaultLearningEngineIntegrationTest 同构的种子/装配助手（测试本地复制，不共享测试基类）。 */
    private fun TestDb.seedBookWithWords(wordCount: Int, label: String = "r"): Pair<Long, List<Long>> {
        var bookId = 0L
        val wordIds = mutableListOf<Long>()
        database.transaction {
            val now = 1_760_000_000_000L
            database.wordBookQueries.insertOriginalWordBook("$label-book", null, now, now)
            bookId = database.wordBookQueries.selectLastInsertRowId().executeAsOne()
            repeat(wordCount) { i ->
                database.wordQueries.insertWord("$label-$i", "$label-$i", null, null, null, now, now)
                val wordId = database.wordQueries.selectLastInsertRowId().executeAsOne()
                wordIds += wordId
                database.wordBookEntryQueries.insertEntry(bookId, wordId, i.toLong(), null, now)
            }
        }
        return bookId to wordIds
    }

    private fun newEngine(db: TestDb, clock: FixedClock): DefaultLearningEngine {
        val sessionRepo = SqlDelightLearningSessionRepository(db.database, clock, DispatchersForTest)
        val settingsRepo = SqlDelightLearningSettingsRepository(db.database, DispatchersForTest)
        return DefaultLearningEngine(
            sessionRepository = sessionRepo,
            settingsRepository = settingsRepo,
            masteryMarker = MasteryMarker(sessionRepo, clock),
            wordBookDeriver = WordBookDeriver(
                wordBookRepository = SqlDelightWordBookRepository(db.database, clock, DispatchersForTest),
                clock = clock,
            ),
        )
    }

    @Test
    fun bookDeletedRecoveryAbandonsAcrossRestart() = runTest {
        // JDBC 驱动 FK 关闭：直接删书行 = 生产端 FK 开启时不可达、但数据损坏/FK 关库下可达的悬挂态
        val db = TestDb.file()
        val clock = FixedClock()
        val (bookId, wordIds) = db.seedBookWithWords(5)
        var sessionId = 0L
        try {
            val engine = newEngine(db, clock)
            sessionId = assertIs<StartResult.Started>(engine.startSession(bookId)).snapshot.session.sessionId
            assertEquals(wordIds[0], assertIs<AdvanceResult.NextWord>(engine.advance(sessionId)).ref.wordId)
            db.database.wordBookQueries.deleteWordBook(bookId) // 书在会话 ACTIVE 期间消失
        } finally {
            db.close() // 模拟进程死亡
        }

        val reopened = TestDb.fileExisting(db.path!!)
        try {
            val clock2 = FixedClock()
            clock2.advanceMillis(7_000)
            val expectedEndedAt = clock2.now().toEpochMilliseconds()
            val engine2 = newEngine(reopened, clock2)

            val rejected = assertIs<ResumeResult.Rejected>(engine2.resumeSession(sessionId))

            assertEquals(ResumeResult.Reason.BOOK_DELETED, rejected.reason)
            val sessionRow = reopened.database.learningSessionQueries.selectSessionById(sessionId).executeAsOne()
            assertEquals("ABANDONED", sessionRow.status) // 恢复事务内落库（书删检查与 ABANDON 同事务）
            assertEquals(expectedEndedAt, sessionRow.endedAt)

            // 重复恢复幂等：NOT_ACTIVE 短路，endedAt 不刷新；不可继续 progression
            assertEquals(ResumeResult.Reason.SESSION_NOT_ACTIVE,
                assertIs<ResumeResult.Rejected>(engine2.resumeSession(sessionId)).reason)
            assertEquals(expectedEndedAt,
                reopened.database.learningSessionQueries.selectSessionById(sessionId).executeAsOne().endedAt)
            assertEquals(AdvanceResult.BookComplete, engine2.advance(sessionId))
        } finally {
            reopened.close()
        }
    }

    @Test
    fun wordRemovedThroughRealPathPurgesAndContinues() = runTest {
        val db = TestDb.inMemory()
        val clock = FixedClock()
        val (bookId, wordIds) = db.seedBookWithWords(5)
        val engine = newEngine(db, clock)
        val sessionId = assertIs<StartResult.Started>(engine.startSession(bookId)).snapshot.session.sessionId
        assertEquals(wordIds[0], assertIs<AdvanceResult.NextWord>(engine.advance(sessionId)).ref.wordId)

        // 真实移词路径（Phase 2）：WordBookEntry 删除 + WordMastery 同事务清理
        SqlDelightWordBookRepository(db.database, clock, DispatchersForTest)
            .removeWordFromWordBook(bookId, wordIds[2])

        val resumed = assertIs<ResumeResult.Resumed>(engine.resumeSession(sessionId))

        assertEquals(
            wordIds.filterIndexed { i, _ -> i != 2 },
            resumed.snapshot.words.map { it.wordId },
        )
        // placement 原样（不重编号）且在册词的 PLAYING 位保留
        assertEquals(listOf(0, 1, 3, 4), resumed.snapshot.words.map { it.orderInGroup })
        assertEquals(
            "PLAYING",
            db.database.sessionWordQueries.selectSessionWord(sessionId, wordIds[0]).executeAsOne().status,
        )

        // 其余照常：续学 4 词 → Q3=0 → COMPLETED（不等待已移除的词）
        wordIds.filterIndexed { i, _ -> i != 2 }.forEach { wordId ->
            assertIs<AdvanceResult.NextWord>(engine.advance(sessionId))
            assertIs<MasteryResult.Marked>(engine.markMastered(sessionId, wordId, MasterySource.BUTTON))
        }
        assertEquals(AdvanceResult.BookComplete, engine.advance(sessionId))
        assertEquals("COMPLETED",
            db.database.learningSessionQueries.selectSessionById(sessionId).executeAsOne().status)
        assertEquals(0L, db.database.queriesQueries.countUnmastered(bookId).executeAsOne())
    }

    @Test
    fun masteredThenRemovedWordPathDeletesMasteryAndPurges() = runTest {
        val db = TestDb.inMemory()
        val clock = FixedClock()
        val (bookId, wordIds) = db.seedBookWithWords(2)
        val engine = newEngine(db, clock)
        val sessionId = assertIs<StartResult.Started>(engine.startSession(bookId)).snapshot.session.sessionId
        assertIs<AdvanceResult.NextWord>(engine.advance(sessionId))
        assertIs<MasteryResult.Marked>(engine.markMastered(sessionId, wordIds[0], MasterySource.BUTTON))
        assertEquals(1L, db.database.wordMasteryQueries.countMastered(bookId).executeAsOne())

        // 已掌握词被移出本：真实路径同事务删 entry + mastery（Phase 2 语义）
        SqlDelightWordBookRepository(db.database, clock, DispatchersForTest)
            .removeWordFromWordBook(bookId, wordIds[0])

        val resumed = assertIs<ResumeResult.Resumed>(engine.resumeSession(sessionId))

        assertEquals(listOf(wordIds[1]), resumed.snapshot.words.map { it.wordId })
        assertNull(db.database.wordMasteryQueries.isMastered(bookId, wordIds[0]).executeAsOneOrNull())
        assertEquals(0L, db.database.wordMasteryQueries.countMastered(bookId).executeAsOne())
        // 另一词不被错误牵连：正常推进并完成
        assertEquals(wordIds[1], assertIs<AdvanceResult.NextWord>(engine.advance(sessionId)).ref.wordId)
        assertIs<MasteryResult.Marked>(engine.markMastered(sessionId, wordIds[1], MasterySource.BUTTON))
        assertEquals(AdvanceResult.BookComplete, engine.advance(sessionId))
        assertEquals("COMPLETED",
            db.database.learningSessionQueries.selectSessionById(sessionId).executeAsOne().status)
    }

    @Test
    fun hundredWordsAfterFiveRemovalsResumeAndCompleteWithoutGaps() = runTest {
        val db = TestDb.inMemory()
        val clock = FixedClock()
        val (bookId, wordIds) = db.seedBookWithWords(100)
        val engine = newEngine(db, clock)
        val started = assertIs<StartResult.Started>(engine.startSession(bookId))
        val sessionId = started.snapshot.session.sessionId
        val removed = listOf(5, 15, 25, 55, 95).map { wordIds[it] }
        val bookRepo = SqlDelightWordBookRepository(db.database, clock, DispatchersForTest)
        removed.forEach { bookRepo.removeWordFromWordBook(bookId, it) }

        val resumed = assertIs<ResumeResult.Resumed>(engine.resumeSession(sessionId))

        val expected = wordIds.filter { it !in removed }
        // 95 个有效 SessionWord；placement 与原始物化逐词一致（空洞保留，无重编号/重排）
        assertEquals(95, resumed.snapshot.words.size)
        assertEquals(
            started.snapshot.words.filter { it.wordId !in removed }
                .map { Triple(it.wordId, it.groupIndex, it.orderInGroup) },
            resumed.snapshot.words.map { Triple(it.wordId, it.groupIndex, it.orderInGroup) },
        )
        // 全队列续学无跳跃：95 词按原序逐词掌握 → Q3=0 → COMPLETED
        expected.forEach { wordId ->
            assertEquals(wordId, assertIs<AdvanceResult.NextWord>(engine.advance(sessionId)).ref.wordId)
            assertIs<MasteryResult.Marked>(engine.markMastered(sessionId, wordId, MasterySource.BUTTON))
        }
        assertEquals(AdvanceResult.BookComplete, engine.advance(sessionId))
        assertEquals("COMPLETED",
            db.database.learningSessionQueries.selectSessionById(sessionId).executeAsOne().status)
        assertEquals(0L, db.database.queriesQueries.countUnmastered(bookId).executeAsOne())
        assertTrue(resumed.snapshot.words.groupBy { it.groupIndex }.values.all { group ->
            group.map { it.orderInGroup }.toSet().size == group.size
        })
    }
}
