package com.vocabularybooster

import com.vocabularybooster.data.SqlDelightLearningSessionRepository
import com.vocabularybooster.data.SqlDelightLearningSettingsRepository
import com.vocabularybooster.data.SqlDelightWordBookRepository
import com.vocabularybooster.learning.AdvanceResult
import com.vocabularybooster.learning.DefaultLearningEngine
import com.vocabularybooster.learning.ExitResult
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * TC-LE-11 退出生命周期真实 SQLDelight 集成（Phase 3 Step 5C，LE spec §8）：
 * ACTIVE→ABANDONED / ACTIVE→COMPLETED（分支 C）跨 close/reopen 的终态持久化、
 * endedAt 首次终态时刻不可刷新、学习历史（SessionWord/WordMastery）原样保留、
 * 终态会话不可 resume/advance/markMastered 复活、重复退出幂等。
 * Fake 驱动的行为矩阵见 commonTest LearningEngineExitTest。
 */
class LearningEngineExitIntegrationTest {

    /** 与既有集成测试同构的种子/装配助手（测试本地复制，不共享测试基类）。 */
    private fun TestDb.seedBookWithWords(wordCount: Int, label: String = "x"): Pair<Long, List<Long>> {
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
    fun exitAbandonedAcrossRestartPreservesHistoryAndBlocksResurrection() = runTest {
        val db = TestDb.file()
        val clock = FixedClock()
        val (bookId, wordIds) = db.seedBookWithWords(5)
        var sessionId = 0L
        try {
            val engine = newEngine(db, clock)
            sessionId = assertIs<StartResult.Started>(engine.startSession(bookId)).snapshot.session.sessionId
            // 部分掌握（w0/w1）+ 播放位停在 w2 → 退出时 Q3=3 > 0 → 分支 B = ABANDONED + 派生
            assertEquals(wordIds[0], assertIs<AdvanceResult.NextWord>(engine.advance(sessionId)).ref.wordId)
            assertIs<MasteryResult.Marked>(engine.markMastered(sessionId, wordIds[0], MasterySource.BUTTON))
            assertEquals(wordIds[1], assertIs<AdvanceResult.NextWord>(engine.advance(sessionId)).ref.wordId)
            assertIs<MasteryResult.Marked>(engine.markMastered(sessionId, wordIds[1], MasterySource.VOICE))
            assertEquals(wordIds[2], assertIs<AdvanceResult.NextWord>(engine.advance(sessionId)).ref.wordId)
            clock.advanceMillis(9_000)
            val exitResult = engine.exitSession(sessionId)
            assertNotNull(exitResult.derivedWordBookId) // 分支 B：部分掌握 → 派生
            assertEquals("ABANDONED", db.database.learningSessionQueries.selectSessionById(sessionId)
                .executeAsOne().status)
        } finally {
            db.close() // 模拟进程死亡
        }

        val expectedEndedAt = clock.now().toEpochMilliseconds()
        val reopened = TestDb.fileExisting(db.path!!)
        try {
            // 直读 DB：终态 + endedAt + 学习历史三表原样（SessionWord 状态冻结、WordMastery 保留）
            val sessionRow = reopened.database.learningSessionQueries.selectSessionById(sessionId).executeAsOne()
            assertEquals("ABANDONED", sessionRow.status)
            assertEquals(expectedEndedAt, sessionRow.endedAt)
            assertNull(reopened.database.learningSessionQueries.selectActiveSession().executeAsOneOrNull())

            val rows = reopened.database.sessionWordQueries.selectSessionWords(sessionId).executeAsList()
            assertEquals(5, rows.size)
            assertEquals("MASTERED", rows.first { it.wordId == wordIds[0] }.status)
            assertEquals("MASTERED", rows.first { it.wordId == wordIds[1] }.status)
            assertEquals(1_760_000_000_000L, rows.first { it.wordId == wordIds[0] }.masteredAt)
            assertEquals("PLAYING", rows.first { it.wordId == wordIds[2] }.status) // 播放位冻结不清理
            assertEquals("PENDING", rows.first { it.wordId == wordIds[3] }.status)
            assertEquals("PENDING", rows.first { it.wordId == wordIds[4] }.status)
            assertEquals(2L, reopened.database.wordMasteryQueries.countMastered(bookId).executeAsOne())

            // 重启后新引擎：终态不可 resume / advance / markMastered，重复退出幂等零写入
            val clock2 = FixedClock()
            clock2.advanceMillis(60_000)
            val engine2 = newEngine(reopened, clock2)
            assertEquals(
                ResumeResult.Reason.SESSION_NOT_ACTIVE,
                assertIs<ResumeResult.Rejected>(engine2.resumeSession(sessionId)).reason,
            )
            assertEquals(AdvanceResult.BookComplete, engine2.advance(sessionId))
            assertEquals(
                MasteryResult.Rejected(MasteryResult.Reason.SESSION_NOT_ACTIVE),
                engine2.markMastered(sessionId, wordIds[2], MasterySource.BUTTON),
            )
            assertEquals(ExitResult(derivedWordBookId = null), engine2.exitSession(sessionId))

            assertEquals("ABANDONED", reopened.database.learningSessionQueries.selectSessionById(sessionId)
                .executeAsOne().status) // 不改判
            assertEquals(expectedEndedAt, reopened.database.learningSessionQueries.selectSessionById(sessionId)
                .executeAsOne().endedAt) // endedAt 不被 clock2 刷新
            assertEquals(2L, reopened.database.wordMasteryQueries.countMastered(bookId).executeAsOne()) // 掌握不增不减
            assertEquals("PLAYING", reopened.database.sessionWordQueries
                .selectSessionWord(sessionId, wordIds[2]).executeAsOne().status) // 无 PLAYING 复活/迁移
        } finally {
            reopened.close()
        }
    }

    @Test
    fun exitCompletedBranchCAcrossRestartKeepsFirstEndedAt() = runTest {
        val db = TestDb.file()
        val clock = FixedClock()
        val (bookId, wordIds) = db.seedBookWithWords(3)
        var sessionId = 0L
        try {
            val engine = newEngine(db, clock)
            sessionId = assertIs<StartResult.Started>(engine.startSession(bookId)).snapshot.session.sessionId
            // 全词掌握但不跑最终 advance（§7 未触发）→ 退出瞬间 Q3=0 → 分支 C = COMPLETED
            wordIds.forEach { wordId ->
                assertIs<AdvanceResult.NextWord>(engine.advance(sessionId))
                assertIs<MasteryResult.Marked>(engine.markMastered(sessionId, wordId, MasterySource.BUTTON))
            }
            clock.advanceMillis(5_000)
            assertEquals(ExitResult(derivedWordBookId = null, sessionCompleted = true), engine.exitSession(sessionId)) // 分支 C：完成事件锚点（Phase 6）
        } finally {
            db.close()
        }

        val expectedEndedAt = clock.now().toEpochMilliseconds()
        val reopened = TestDb.fileExisting(db.path!!)
        try {
            val sessionRow = reopened.database.learningSessionQueries.selectSessionById(sessionId).executeAsOne()
            assertEquals("COMPLETED", sessionRow.status)
            assertEquals(expectedEndedAt, sessionRow.endedAt)
            assertTrue(
                reopened.database.sessionWordQueries.selectSessionWords(sessionId).executeAsList()
                    .all { it.status == "MASTERED" },
            )
            assertEquals(3L, reopened.database.wordMasteryQueries.countMastered(bookId).executeAsOne())
            assertNull(reopened.database.learningSessionQueries.selectActiveSession().executeAsOneOrNull())

            // 重启后重复退出：COMPLETED 保持 COMPLETED，endedAt 不刷新（时钟已前进 60s）
            val clock2 = FixedClock()
            clock2.advanceMillis(60_000)
            assertEquals(
                ExitResult(derivedWordBookId = null),
                newEngine(reopened, clock2).exitSession(sessionId),
            )
            assertEquals("COMPLETED", reopened.database.learningSessionQueries.selectSessionById(sessionId)
                .executeAsOne().status)
            assertEquals(expectedEndedAt, reopened.database.learningSessionQueries.selectSessionById(sessionId)
                .executeAsOne().endedAt)
        } finally {
            reopened.close()
        }
    }

    @Test
    fun terminalSessionCannotAdvanceOrMasterAfterExit() = runTest {
        val db = TestDb.inMemory()
        val clock = FixedClock()
        val (bookId, wordIds) = db.seedBookWithWords(2, label = "t")
        val engine = newEngine(db, clock)
        val sessionId = assertIs<StartResult.Started>(engine.startSession(bookId)).snapshot.session.sessionId
        assertEquals(wordIds[0], assertIs<AdvanceResult.NextWord>(engine.advance(sessionId)).ref.wordId)
        assertEquals(ExitResult(derivedWordBookId = null), engine.exitSession(sessionId)) // 零掌握 → ABANDONED

        assertEquals(AdvanceResult.BookComplete, engine.advance(sessionId)) // 终态不可推进
        assertEquals(
            MasteryResult.Rejected(MasteryResult.Reason.SESSION_NOT_ACTIVE),
            engine.markMastered(sessionId, wordIds[0], MasterySource.VOICE),
        )
        // 状态零变更：无 PLAYING 迁移、无 MASTERED 写入、无掌握行
        assertEquals("PLAYING", db.database.sessionWordQueries
            .selectSessionWord(sessionId, wordIds[0]).executeAsOne().status)
        assertEquals("PENDING", db.database.sessionWordQueries
            .selectSessionWord(sessionId, wordIds[1]).executeAsOne().status)
        assertEquals(0L, db.database.wordMasteryQueries.countMastered(bookId).executeAsOne())
        db.close()
    }
}
