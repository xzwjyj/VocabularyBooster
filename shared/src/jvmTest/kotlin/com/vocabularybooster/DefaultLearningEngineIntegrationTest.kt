package com.vocabularybooster

import com.vocabularybooster.data.SqlDelightLearningSessionRepository
import com.vocabularybooster.data.SqlDelightLearningSettingsRepository
import com.vocabularybooster.data.SqlDelightWordBookRepository
import com.vocabularybooster.domain.model.PlaybackToggles
import com.vocabularybooster.domain.model.SessionWordStatus
import com.vocabularybooster.learning.DefaultLearningEngine
import com.vocabularybooster.learning.MasteryMarker
import com.vocabularybooster.learning.MasteryResult
import com.vocabularybooster.learning.MasterySource
import com.vocabularybooster.learning.ResumeResult
import com.vocabularybooster.learning.StartResult
import com.vocabularybooster.learning.WordBookDeriver
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * DefaultLearningEngine 真实 SQLDelight 集成（Phase 3 Step 4，TC-LE-09 编排侧）：
 * startSession → close → reopen → resumeSession 的崩溃/重启持久化链路，
 * 以及设置端口（AppSetting 真实 KV + 默认值）对引擎裁决的驱动。
 * 纯编排规则（拒绝分支/顺序/委托边界）见 commonTest DefaultLearningEngineTest。
 */
class DefaultLearningEngineIntegrationTest {

    /** 建本 + N 词 + N 词条关系（单事务，rowid 钉连接；entryOrder 即列表序）。 */
    private fun TestDb.seedBookWithWords(wordCount: Int, label: String = "s"): Pair<Long, List<Long>> {
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
    fun crashRestartResumePreservesEntireSnapshot() = runTest {
        // 真实持久化链路：start(100 词) → 掌握一词 → close（模拟进程死亡）→ reopen → resume 逐字段一致
        val db = TestDb.file()
        val clock = FixedClock()
        var expectedBookId = 0L
        var expectedWordIds = emptyList<Long>()
        var sessionId = 0L
        var expectedMasteredAt = 0L
        try {
            val engine = newEngine(db, clock)
            val (bookId, wordIds) = db.seedBookWithWords(100)
            expectedBookId = bookId
            expectedWordIds = wordIds

            val started = assertIs<StartResult.Started>(engine.startSession(bookId))
            sessionId = started.snapshot.session.sessionId

            // AppSetting 无任何行 → 内置默认生效：groupSize 10、六开关全开
            assertEquals(10, started.snapshot.session.groupSize)
            assertEquals(100, started.snapshot.words.size)
            assertEquals(10, started.snapshot.words.groupBy { it.groupIndex }.size)
            assertTrue(started.snapshot.words.groupBy { it.groupIndex }.values.all { it.size == 10 })

            clock.advanceMillis(4_500)
            assertIs<MasteryResult.Marked>(engine.markMastered(sessionId, wordIds[3], MasterySource.VOICE))
            expectedMasteredAt = clock.now().toEpochMilliseconds()
        } finally {
            db.close()
        }

        val reopened = TestDb.fileExisting(db.path!!)
        try {
            val engine2 = newEngine(reopened, FixedClock()) // 新进程新引擎，无内存态
            val resumed = assertIs<ResumeResult.Resumed>(engine2.resumeSession(sessionId))

            // 逐字段保持：sessionId/wordBookId/groupSize/status + 全部队列行（wordId/组/序/状态/masteredAt）
            assertEquals(sessionId, resumed.snapshot.session.sessionId)
            assertEquals(expectedBookId, resumed.snapshot.session.wordBookId)
            assertEquals(10, resumed.snapshot.session.groupSize)
            assertEquals(100, resumed.snapshot.words.size)
            assertEquals(expectedWordIds, resumed.snapshot.words.map { it.wordId })
            assertEquals((0 until 100).map { it / 10 }, resumed.snapshot.words.map { it.groupIndex })
            assertEquals((0 until 100).map { it % 10 }, resumed.snapshot.words.map { it.orderInGroup })
            val mastered = resumed.snapshot.words.first { it.wordId == expectedWordIds[3] }
            assertEquals(SessionWordStatus.MASTERED, mastered.status)
            assertEquals(expectedMasteredAt, mastered.masteredAt?.toEpochMilliseconds())
            assertTrue(
                resumed.snapshot.words
                    .filter { it.wordId != expectedWordIds[3] }
                    .all { it.status == SessionWordStatus.PENDING && it.masteredAt == null },
            )
            // 新引擎继续掌握：恢复后的会话仍可推进（同一 ACTIVE 不变量下）
            assertIs<MasteryResult.Marked>(engine2.markMastered(sessionId, expectedWordIds[7], MasterySource.BUTTON))
        } finally {
            reopened.close()
        }
    }

    @Test
    fun playbackTogglesAllOffInDbRejectsStartWithoutSideEffects() = runTest {
        val db = TestDb.inMemory()
        val clock = FixedClock()
        val (bookId, wordIds) = db.seedBookWithWords(3)

        // 真实 AppSetting KV：写入六开关全关（序列化格式即存储契约）
        db.database.appSettingQueries.upsertSetting(
            key = "settings.playbackToggles",
            valueJson = Json.encodeToString(
                PlaybackToggles(
                    pronunciation = false, spelling = false, meaningEn = false,
                    meaningCn = false, example = false, exampleCn = false,
                ),
            ),
        )

        val engine = newEngine(db, clock)
        val rejected = assertIs<StartResult.Rejected>(engine.startSession(bookId))

        assertEquals(StartResult.Reason.PLAYBACK_DISABLED, rejected.reason)
        assertNull(SqlDelightLearningSessionRepository(db.database, clock, DispatchersForTest).getActiveSession())
        assertTrue(
            SqlDelightLearningSessionRepository(db.database, clock, DispatchersForTest)
                .getSessionWords(1L).isEmpty(),
        )
        assertEquals(3, wordIds.size) // 词表原样，拒绝零写入
    }

    @Test
    fun groupSizeSettingValueDrivesGrouping() = runTest {
        val db = TestDb.inMemory()
        val clock = FixedClock()
        val (bookId, wordIds) = db.seedBookWithWords(25)

        db.database.appSettingQueries.upsertSetting(
            key = "settings.groupSize",
            valueJson = Json.encodeToString(25),
        )

        val engine = newEngine(db, clock)
        val started = assertIs<StartResult.Started>(engine.startSession(bookId))

        assertEquals(25, started.snapshot.session.groupSize) // 设置值固化
        assertEquals(1, started.snapshot.words.groupBy { it.groupIndex }.size) // 25 词一组
        assertEquals(wordIds, started.snapshot.words.map { it.wordId })
    }
}
