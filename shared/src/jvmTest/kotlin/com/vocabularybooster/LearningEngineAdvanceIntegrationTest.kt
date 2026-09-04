package com.vocabularybooster

import com.vocabularybooster.data.SqlDelightLearningSessionRepository
import com.vocabularybooster.data.SqlDelightLearningSettingsRepository
import com.vocabularybooster.data.SqlDelightWordBookRepository
import com.vocabularybooster.learning.AdvanceResult
import com.vocabularybooster.learning.DefaultLearningEngine
import com.vocabularybooster.learning.MasteryMarker
import com.vocabularybooster.learning.MasteryResult
import com.vocabularybooster.learning.MasterySource
import com.vocabularybooster.learning.StartResult
import com.vocabularybooster.learning.WordBookDeriver
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * DefaultLearningEngine.advance 真实 SQLDelight 集成（Phase 3 Step 5A，TC-LE-05/06/09）：
 * 播放位持久化跨 close/reopen 续推、Q3 书级完成落终态（COMPLETED + endedAt）+ 幂等、
 * 会话耗尽但书中途新增词的规格未定义分支（保持 ACTIVE，deferred issue）。
 * 纯选择算法（回绕/边界规模）见 commonTest LearningEngineAdvanceTest。
 */
class LearningEngineAdvanceIntegrationTest {

    /** 与 DefaultLearningEngineIntegrationTest 同构的种子/装配助手（测试本地复制，不共享测试基类）。 */
    private fun TestDb.seedBookWithWords(wordCount: Int, label: String = "a"): Pair<Long, List<Long>> {
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
    fun advancePositionSurvivesCrashRestartAndNewEngineContinues() = runTest {
        // 真实持久化链路：start(10 词) → advance(w0) → 掌握 → advance(w1) → close（模拟进程死亡）
        // → reopen → 库中位 = PLAYING(w1) → 新引擎 advance 从该位顺延（w2），零内存态
        val db = TestDb.file()
        val clock = FixedClock()
        val (bookId, wordIds) = db.seedBookWithWords(10)
        var sessionId = 0L
        try {
            val engine = newEngine(db, clock)
            sessionId = assertIs<StartResult.Started>(engine.startSession(bookId)).snapshot.session.sessionId

            val first = assertIs<AdvanceResult.NextWord>(engine.advance(sessionId))
            assertEquals(wordIds[0], first.ref.wordId)
            assertEquals(0, first.ref.groupIndex)
            assertEquals(0, first.ref.orderInGroup)
            clock.advanceMillis(1_000)
            assertIs<MasteryResult.Marked>(engine.markMastered(sessionId, wordIds[0], MasterySource.VOICE))
            val second = assertIs<AdvanceResult.NextWord>(engine.advance(sessionId))
            assertEquals(wordIds[1], second.ref.wordId)
        } finally {
            db.close()
        }

        val reopened = TestDb.fileExisting(db.path!!)
        try {
            // 直接读库：推进位置已持久化（w0 MASTERED、w1 PLAYING、其余 PENDING）
            val rows = reopened.database.sessionWordQueries.selectSessionWords(sessionId).executeAsList()
            assertEquals("MASTERED", rows.first { it.wordId == wordIds[0] }.status)
            assertEquals("PLAYING", rows.first { it.wordId == wordIds[1] }.status)
            assertTrue(
                rows.filter { it.wordId != wordIds[0] && it.wordId != wordIds[1] }
                    .all { it.status == "PENDING" },
            )

            val engine2 = newEngine(reopened, FixedClock()) // 新进程新引擎，无内存位
            val third = assertIs<AdvanceResult.NextWord>(engine2.advance(sessionId))
            assertEquals(wordIds[2], third.ref.wordId) // §5 cur 语义：从持久化 PLAYING 位续推
            assertNull(third.completedGroupIndex) // 目标组 0：无前一组
        } finally {
            reopened.close()
        }
    }

    @Test
    fun bookCompletionWritesTerminalStateAndRepeatAdvanceStaysIdempotent() = runTest {
        val db = TestDb.inMemory()
        val clock = FixedClock()
        val (bookId, wordIds) = db.seedBookWithWords(3)
        val engine = newEngine(db, clock)
        val sessionId = assertIs<StartResult.Started>(engine.startSession(bookId)).snapshot.session.sessionId

        wordIds.forEach { wordId ->
            assertIs<AdvanceResult.NextWord>(engine.advance(sessionId))
            assertIs<MasteryResult.Marked>(engine.markMastered(sessionId, wordId, MasterySource.BUTTON))
        }

        clock.advanceMillis(2_000)
        val completedAt = clock.now().toEpochMilliseconds()
        assertEquals(AdvanceResult.BookComplete, engine.advance(sessionId))

        // §7 落库：Q3=0 → 会话 COMPLETED + endedAt（注入 Clock 时刻）
        val sessionRow = db.database.learningSessionQueries.selectSessionById(sessionId).executeAsOne()
        assertEquals("COMPLETED", sessionRow.status)
        assertEquals(completedAt, sessionRow.endedAt)
        assertEquals(0L, db.database.queriesQueries.countUnmastered(bookId).executeAsOne())

        // 重复 advance 幂等只读：endedAt 不刷新
        clock.advanceMillis(60_000)
        assertEquals(AdvanceResult.BookComplete, engine.advance(sessionId))
        assertEquals(
            completedAt,
            db.database.learningSessionQueries.selectSessionById(sessionId).executeAsOne().endedAt,
        )
    }

    @Test
    fun sessionExhaustedAfterMidSessionAdditionStaysActive() = runTest {
        // 规格未定义分支（deferred issue）：队列固化不含会话中途新增词——
        // Q3 > 0 → 不设终态；会话确无可播词 → BookComplete
        val db = TestDb.inMemory()
        val clock = FixedClock()
        val (bookId, wordIds) = db.seedBookWithWords(2)
        val engine = newEngine(db, clock)
        val sessionId = assertIs<StartResult.Started>(engine.startSession(bookId)).snapshot.session.sessionId

        wordIds.forEach { wordId ->
            assertIs<AdvanceResult.NextWord>(engine.advance(sessionId))
            assertIs<MasteryResult.Marked>(engine.markMastered(sessionId, wordId, MasterySource.BUTTON))
        }

        // 会话中途向书新增一词（entryOrder 接尾；不触碰既有会话）
        val now = clock.now().toEpochMilliseconds()
        db.database.wordQueries.insertWord("a-new", "a-new", null, null, null, now, now)
        val newWordId = db.database.wordQueries.selectLastInsertRowId().executeAsOne()
        db.database.wordBookEntryQueries.insertEntry(bookId, newWordId, 2, null, now)

        assertEquals(AdvanceResult.BookComplete, engine.advance(sessionId))

        assertEquals("ACTIVE", db.database.learningSessionQueries.selectSessionById(sessionId).executeAsOne().status)
        assertEquals(1L, db.database.queriesQueries.countUnmastered(bookId).executeAsOne())
    }
}
