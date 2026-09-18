package com.vocabularybooster

import com.vocabularybooster.achievement.AchievementEngine
import com.vocabularybooster.data.SqlDelightAchievementRepository
import com.vocabularybooster.data.SqlDelightLearningSessionRepository
import com.vocabularybooster.data.SqlDelightWordBookRepository
import com.vocabularybooster.domain.event.DefaultDomainEventBus
import com.vocabularybooster.domain.event.DomainEvent
import com.vocabularybooster.domain.model.AchievementType
import com.vocabularybooster.domain.model.BookCompletedPayload
import com.vocabularybooster.domain.model.SessionStatus
import com.vocabularybooster.domain.repository.WordBookDeletionException
import com.vocabularybooster.learning.AdvanceResult
import com.vocabularybooster.learning.DefaultLearningEngine
import com.vocabularybooster.learning.MasteryMarker
import com.vocabularybooster.learning.MasteryResult
import com.vocabularybooster.learning.MasterySource
import com.vocabularybooster.learning.StartResult
import com.vocabularybooster.learning.WordBookDeriver
import com.vocabularybooster.playback.FakeLearningSettingsRepository
import com.vocabularybooster.platform.LogLevel
import com.vocabularybooster.platform.LogSink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/**
 * AchievementEngine 集成测试（ACHIEVEMENT_SPEC v1.1 §2，TEST_PLAN TC-AC-01…04，Phase 6）：
 * 真实 JDBC 内存库 + 真实仓储 + 默认领域事件总线——
 * TC-AC-01 幂等（事件重放/同本重学再完成 → 恰一行、AchievementUnlocked 恰一次）；
 * TC-AC-02 防御复核（不信事件、信数据：ACTIVE/伪 COMPLETED 带 unmastered/未知会话 → 零授予）；
 * TC-AC-03 快照不可变（授予后改名/加词 → payloadJson 不变）；
 * TC-AC-04 删除拦截（引擎级闭环：真实授予后 deleteWordBook 抛 BOOK_HAS_COMPLETION_MEDAL）。
 * 授予顺序锚点（TC-AC-05）见 PlaybackOrchestratorStateTest 编排器用例。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AchievementEngineTest {

    private val dispatcher = StandardTestDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    private val db = TestDb.inMemory()
    private val clock = FixedClock()
    private val sessionRepo = SqlDelightLearningSessionRepository(db.database, clock, DispatchersForTest)
    private val bookRepo = SqlDelightWordBookRepository(db.database, clock, DispatchersForTest)
    private val achievementRepo = SqlDelightAchievementRepository(db.database, DispatchersForTest)
    private val bus = DefaultDomainEventBus()
    private val logSink = RecordingLogSink()
    private val engine = AchievementEngine(
        eventBus = bus,
        sessionRepository = sessionRepo,
        wordBookRepository = bookRepo,
        achievementRepository = achievementRepo,
        logSink = logSink,
    )

    private val learningEngine = DefaultLearningEngine(
        sessionRepository = sessionRepo,
        settingsRepository = FakeLearningSettingsRepository(),
        masteryMarker = MasteryMarker(sessionRepo, clock),
        wordBookDeriver = WordBookDeriver(bookRepo, clock),
    )

    private val recordedEvents = mutableListOf<DomainEvent>()

    init {
        engine.start(scope)
        scope.launch { bus.events.collect { recordedEvents += it } }
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        db.close()
    }

    /** 建本 + 裸插词条（引擎建队输入）；返回 bookId 与 wordIds。 */
    private fun seedBook(name: String, labels: List<String>): Pair<Long, List<Long>> {
        var bookId = 0L
        val wordIds = mutableListOf<Long>()
        db.database.transaction {
            val now = clock.now().toEpochMilliseconds()
            db.database.wordBookQueries.insertOriginalWordBook(name, null, now, now)
            bookId = db.database.wordBookQueries.selectLastInsertRowId().executeAsOne()
            labels.forEachIndexed { index, label ->
                db.database.wordQueries.insertWord(label, label, null, null, null, now, now)
                val wordId = db.database.wordQueries.selectLastInsertRowId().executeAsOne()
                wordIds += wordId
                db.database.wordBookEntryQueries.insertEntry(bookId, wordId, index.toLong(), null, now)
            }
        }
        return bookId to wordIds
    }

    /** 完成（advance 路径）：全部词掌握 → advance → BookComplete（会话已 COMPLETED + endedAt）。 */
    private suspend fun completeSession(bookId: Long, wordIds: List<Long>): Pair<Long, DomainEvent.WordBookCompleted> {
        val sessionId = assertIs<StartResult.Started>(learningEngine.startSession(bookId))
            .snapshot.session.sessionId
        wordIds.forEach { learningEngine.markMastered(sessionId, it, MasterySource.VOICE) }
        assertIs<AdvanceResult.BookComplete>(learningEngine.advance(sessionId))
        return sessionId to DomainEvent.WordBookCompleted(sessionId = sessionId, wordBookId = bookId)
    }

    private suspend fun publishAndFlush(event: DomainEvent) {
        bus.publish(event)
        dispatcher.scheduler.runCurrent()
    }

    // —— TC-AC-01 幂等：事件重放 N 次 + 同本重学再完成 → 恰一行、AchievementUnlocked 恰一次 ——

    @Test
    fun completionEventReplayGrantsExactlyOnce() = runTest(dispatcher.scheduler) {
        val (bookId, wordIds) = seedBook("考研核心词", listOf("boost", "abandon"))
        val (_, event) = completeSession(bookId, wordIds)

        repeat(3) { publishAndFlush(event) } // 重放（终态 resume 重发/重复事件同语义）

        val medals = achievementRepo.getAchievements()
        assertEquals(1, medals.size, "事件重放必须收敛为恰一行（唯一索引兜底）")
        val medal = medals.single()
        assertEquals(AchievementType.BOOK_COMPLETED, medal.type)
        val payload = medal.payload
        assertEquals("考研核心词", payload.bookName)
        assertEquals(2, payload.wordCount) // wordCount = 会话快照词数（ADR-002 口径）
        assertEquals(medal.earnedAt, payload.finishedAt) // earnedAt = 会话完成时刻（确定性，v1.1）
        assertEquals(payload.finishedAt.toEpochMilliseconds(), clock.now().toEpochMilliseconds())
        assertEquals(
            1,
            recordedEvents.filterIsInstance<DomainEvent.AchievementUnlocked>().size,
            "重复授予路径零事件",
        )
    }

    @Test
    fun relearningSameBookCompletesAgainWithoutSecondRowOrEvent() = runTest(dispatcher.scheduler) {
        val (bookId, wordIds) = seedBook("考研核心词", listOf("boost", "abandon"))
        val (_, first) = completeSession(bookId, wordIds)
        publishAndFlush(first)

        // 重学路径：Q2 = 本内全部词（924fdc6 解耦：会话词不继承掌握行）→ 整本再学 → 完成（退出分支 C）
        val restarted = assertIs<StartResult.Started>(learningEngine.startSession(bookId))
        val sessionId = restarted.snapshot.session.sessionId
        restarted.snapshot.words.forEach { word ->
            assertIs<MasteryResult.Marked>(learningEngine.markMastered(sessionId, word.wordId, MasterySource.BUTTON))
        }
        val exit = learningEngine.exitSession(sessionId)
        assertEquals(true, exit.sessionCompleted, "退出分支 C：快照全掌握")
        publishAndFlush(DomainEvent.WordBookCompleted(sessionId, bookId))

        assertEquals(1, achievementRepo.getAchievements().size, "同本再完成不产生第二行")
        assertEquals(
            1,
            recordedEvents.filterIsInstance<DomainEvent.AchievementUnlocked>().size,
            "已授予路径零 AchievementUnlocked",
        )
    }

    // —— TC-AC-02 防御复核：不信事件、信数据 ——

    @Test
    fun activeSessionEventIsDroppedWithoutGrant() = runTest(dispatcher.scheduler) {
        val (bookId, wordIds) = seedBook("书", listOf("boost"))
        val sessionId = assertIs<StartResult.Started>(learningEngine.startSession(bookId))
            .snapshot.session.sessionId

        publishAndFlush(DomainEvent.WordBookCompleted(sessionId, bookId))

        assertEquals(0, achievementRepo.getAchievements().size, "ACTIVE 会话事件必须丢弃")
        assertContains(logSink.warnings.single(), "防御复核")
    }

    @Test
    fun forgedCompletedWithUnmasteredWordsIsDropped() = runTest(dispatcher.scheduler) {
        val (bookId, wordIds) = seedBook("书", listOf("boost", "abandon"))
        val sessionId = assertIs<StartResult.Started>(learningEngine.startSession(bookId))
            .snapshot.session.sessionId
        learningEngine.markMastered(sessionId, wordIds[0], MasterySource.VOICE) // 部分掌握
        // 伪造：绕过引擎直接置 COMPLETED（事件与数据不一致场景）
        sessionRepo.terminateSessionIfActive(sessionId, SessionStatus.COMPLETED)

        publishAndFlush(DomainEvent.WordBookCompleted(sessionId, bookId))

        assertEquals(0, achievementRepo.getAchievements().size, "快照有未掌握词 → 零授予")
    }

    @Test
    fun unknownSessionEventIsDropped() = runTest(dispatcher.scheduler) {
        val (bookId, _) = seedBook("书", listOf("boost"))

        publishAndFlush(DomainEvent.WordBookCompleted(sessionId = 999L, wordBookId = bookId))

        assertEquals(0, achievementRepo.getAchievements().size)
    }

    // —— TC-AC-03 快照不可变：授予后改名/加词 → payload 不变 ——

    @Test
    fun renameAndAddWordAfterGrantKeepsPayloadSnapshot() = runTest(dispatcher.scheduler) {
        val (bookId, wordIds) = seedBook("旧书名", listOf("boost", "abandon"))
        val (_, event) = completeSession(bookId, wordIds)
        publishAndFlush(event)

        bookRepo.renameWordBook(bookId, "新书名")
        db.database.transaction {
            db.database.wordQueries.insertWord("crunch", "crunch", null, null, null, 1L, 1L)
            val wordId = db.database.wordQueries.selectLastInsertRowId().executeAsOne()
            db.database.wordBookEntryQueries.insertEntry(bookId, wordId, 99L, null, 1L)
        }

        val medal = achievementRepo.getBookCompletedFor(bookId)
        assertEquals(
            BookCompletedPayload(
                bookName = "旧书名",
                wordCount = 2,
                finishedAt = Instant.fromEpochMilliseconds(clock.now().toEpochMilliseconds()),
            ),
            medal?.payload,
            "改名/加词不影响已获勋章快照",
        )
        assertEquals("新书名", bookRepo.getWordBookName(bookId), "书本体照常可改名（母本永远可学）")
    }

    // —— TC-AC-04 删除拦截（引擎级闭环：真实授予路径落行后守卫生效）——

    @Test
    fun deleteBookAfterRealGrantIsRejectedByMedalGuard() = runTest(dispatcher.scheduler) {
        val (bookId, wordIds) = seedBook("书", listOf("boost", "abandon"))
        val (_, event) = completeSession(bookId, wordIds)
        publishAndFlush(event)

        val failure = assertFailsWith<WordBookDeletionException> { bookRepo.deleteWordBook(bookId) }
        assertEquals(
            WordBookDeletionException.Reason.BOOK_HAS_COMPLETION_MEDAL,
            failure.reason,
        )
    }

    /** 观测丢弃路径的日志 Fake（勋章授予失败隔离语义的可见性）。 */
    private class RecordingLogSink : LogSink {
        val warnings = mutableListOf<String>()
        override fun log(level: LogLevel, tag: String, message: String, error: Throwable?) {
            if (level == LogLevel.WARN) warnings += message
        }
    }
}
