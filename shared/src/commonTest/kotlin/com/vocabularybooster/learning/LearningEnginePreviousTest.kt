package com.vocabularybooster.learning

import com.vocabularybooster.domain.model.SessionStatus
import com.vocabularybooster.domain.model.SessionWordStatus
import com.vocabularybooster.domain.model.StudyQueueEntryRef
import com.vocabularybooster.domain.model.StudyQueueSnapshot
import com.vocabularybooster.domain.repository.RepositoryValidationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * DefaultLearningEngine.previous（LE spec §5 previousWord，FR-11 v1.23 Previous，SCR-PREVWORD，
 * TC-AE-34 commonTest 层，2026-09-26）：advance 的镜像裁决——组内 orderInGroup 降序取最近
 * 未掌握前驱 / 跳过 MASTERED / 组首回绕组内最后一个未掌握词 / 恒组内（不回退进已掌握前组）/
 * 组内唯一未掌握词回绕自身 / 纯导航零掌握写入 / 无播放位取组内最大 / 终态幂等 / 零内存位续推。
 * 经手写 Fake 端口驱动；编排器层行为见 jvmTest PlaybackOrchestratorStateTest TC-AE-34 组。
 */
class LearningEnginePreviousTest {

    private val wordBookId = 1L

    /** 词表按 entryOrder 升序登记（orderedWordIds 即 Q2 顺序），默认 groupSize 10。 */
    private fun repoWithQueue(vararg orderedWordIds: Long): FakeLearningSessionRepository =
        FakeLearningSessionRepository().apply {
            studySnapshots[wordBookId] = StudyQueueSnapshot(
                wordBookExists = true,
                totalEntryCount = orderedWordIds.size,
                studyEntries = orderedWordIds.mapIndexed { index, id ->
                    StudyQueueEntryRef(wordId = id, entryOrder = index)
                },
            )
        }

    private fun engine(
        repo: FakeLearningSessionRepository,
        clock: FixedClock = FixedClock(),
    ): Pair<LearningEngine, FixedClock> =
        DefaultLearningEngine(
            repo,
            FakeLearningSettingsRepository(),
            MasteryMarker(repo, clock),
            WordBookDeriver(FakeWordBookRepository(), clock),
        ) to clock

    /** 推进并掌握一词（advance → NextWord → markMastered）。 */
    private suspend fun LearningEngine.playAndMaster(sessionId: Long, wordId: Long) {
        assertIs<AdvanceResult.NextWord>(advance(sessionId))
        assertIs<MasteryResult.Marked>(markMastered(sessionId, wordId, MasterySource.BUTTON))
    }

    @Test
    fun previousSelectsNearestUnmasteredBeforePlaying() = kotlinx.coroutines.test.runTest {
        val wordIds = (0L until 10L).toList()
        val repo = repoWithQueue(*wordIds.toLongArray())
        val (engine, _) = engine(repo)
        val sessionId = assertIs<StartResult.Started>(engine.startSession(wordBookId)).snapshot.session.sessionId

        repeat(4) { i -> engine.playAndMaster(sessionId, wordIds[i]) } // 掌握 0..3
        assertEquals(wordIds[4], assertIs<AdvanceResult.NextWord>(engine.advance(sessionId)).ref.wordId) // 位=4
        assertEquals(wordIds[5], assertIs<AdvanceResult.NextWord>(engine.advance(sessionId)).ref.wordId) // 纯跳转

        val previous = assertIs<AdvanceResult.NextWord>(engine.previous(sessionId))

        assertEquals(WordRef(sessionId, wordIds[4], groupIndex = 0, orderInGroup = 4), previous.ref)
        assertNull(previous.completedGroupIndex) // previous 恒组内，无组完成语义
        assertEquals(SessionWordStatus.PENDING, repo.getSessionWord(sessionId, wordIds[5])?.status) // 前位回 PENDING
        assertEquals(SessionWordStatus.PLAYING, repo.getSessionWord(sessionId, wordIds[4])?.status) // 位已迁移
        assertEquals(sessionId to wordIds[4], repo.setPlayingWordCalls.last()) // 专用通道
        assertTrue(repo.updateSessionWordStatusCalls.isEmpty()) // 不走底层无守卫写
    }

    @Test
    fun previousSkipsMasteredWords() = kotlinx.coroutines.test.runTest {
        // 组内 0..9，其中 2/3 已掌握，播放位 = 5：previous → 4；再 previous → 1（跳过 2/3）
        val wordIds = (0L until 10L).toList()
        val repo = repoWithQueue(*wordIds.toLongArray())
        val (engine, _) = engine(repo)
        val sessionId = assertIs<StartResult.Started>(engine.startSession(wordBookId)).snapshot.session.sessionId

        assertEquals(wordIds[0], assertIs<AdvanceResult.NextWord>(engine.advance(sessionId)).ref.wordId)
        assertIs<MasteryResult.Marked>(engine.markMastered(sessionId, wordIds[2], MasterySource.BUTTON))
        assertIs<MasteryResult.Marked>(engine.markMastered(sessionId, wordIds[3], MasterySource.BUTTON))
        assertEquals(wordIds[1], assertIs<AdvanceResult.NextWord>(engine.advance(sessionId)).ref.wordId)
        // 顺延到 5（advance 侧跳过 2/3 与 next 对称），再回退验证 previous 的跳过
        assertEquals(wordIds[4], assertIs<AdvanceResult.NextWord>(engine.advance(sessionId)).ref.wordId)
        assertEquals(wordIds[5], assertIs<AdvanceResult.NextWord>(engine.advance(sessionId)).ref.wordId)

        assertEquals(wordIds[4], assertIs<AdvanceResult.NextWord>(engine.previous(sessionId)).ref.wordId)
        assertEquals(wordIds[1], assertIs<AdvanceResult.NextWord>(engine.previous(sessionId)).ref.wordId) // 跳过 3/2
    }

    @Test
    fun wrapAtFirstUnmasteredLandsOnLastUnmasteredInGroup() = kotlinx.coroutines.test.runTest {
        // §5 previousWord 组首回绕：位=组内首个未掌握词（5）→ 回绕组内最后一个未掌握词（9）
        val wordIds = (0L until 10L).toList()
        val repo = repoWithQueue(*wordIds.toLongArray())
        val (engine, _) = engine(repo)
        val sessionId = assertIs<StartResult.Started>(engine.startSession(wordBookId)).snapshot.session.sessionId

        repeat(5) { i -> engine.playAndMaster(sessionId, wordIds[i]) } // 掌握 0..4
        assertEquals(wordIds[5], assertIs<AdvanceResult.NextWord>(engine.advance(sessionId)).ref.wordId)

        val wrapped = assertIs<AdvanceResult.NextWord>(engine.previous(sessionId))

        assertEquals(WordRef(sessionId, wordIds[9], groupIndex = 0, orderInGroup = 9), wrapped.ref)
        assertNull(wrapped.completedGroupIndex)
        assertEquals(SessionWordStatus.PENDING, repo.getSessionWord(sessionId, wordIds[5])?.status)
        assertEquals(SessionWordStatus.PLAYING, repo.getSessionWord(sessionId, wordIds[9])?.status)
    }

    @Test
    fun previousNeverRetreatsIntoMasteredEarlierGroup() = kotlinx.coroutines.test.runTest {
        // 20 词 2 组：组 0 全掌握、位 = 组 1 首词（10）→ previous 组内回绕到 19，绝不回退进组 0
        val wordIds = (0L until 20L).toList()
        val repo = repoWithQueue(*wordIds.toLongArray())
        val (engine, _) = engine(repo)
        val sessionId = assertIs<StartResult.Started>(engine.startSession(wordBookId)).snapshot.session.sessionId

        repeat(10) { i -> engine.playAndMaster(sessionId, wordIds[i]) } // 组 0 清空
        assertEquals(wordIds[10], assertIs<AdvanceResult.NextWord>(engine.advance(sessionId)).ref.wordId)

        val previous = assertIs<AdvanceResult.NextWord>(engine.previous(sessionId))

        assertEquals(WordRef(sessionId, wordIds[19], groupIndex = 1, orderInGroup = 9), previous.ref)
        assertNull(previous.completedGroupIndex)
        assertEquals(SessionWordStatus.PLAYING, repo.getSessionWord(sessionId, wordIds[19])?.status)
        assertEquals(SessionWordStatus.MASTERED, repo.getSessionWord(sessionId, wordIds[9])?.status) // 组 0 不被触碰
    }

    @Test
    fun singleUnmasteredWordPreviousWrapsToSelf() = kotlinx.coroutines.test.runTest {
        // 组内唯一未掌握词即当前词 → 回绕自身（播放层等效重播），零掌握写入
        val wordIds = (0L until 10L).toList()
        val repo = repoWithQueue(*wordIds.toLongArray())
        val (engine, _) = engine(repo)
        val sessionId = assertIs<StartResult.Started>(engine.startSession(wordBookId)).snapshot.session.sessionId

        repeat(5) { i -> engine.playAndMaster(sessionId, wordIds[i]) } // 掌握 0..4
        (6..9).forEach { i ->
            assertIs<MasteryResult.Marked>(engine.markMastered(sessionId, wordIds[i], MasterySource.BUTTON))
        }
        assertEquals(wordIds[5], assertIs<AdvanceResult.NextWord>(engine.advance(sessionId)).ref.wordId) // 唯一未掌握

        val previous = assertIs<AdvanceResult.NextWord>(engine.previous(sessionId))

        assertEquals(WordRef(sessionId, wordIds[5], groupIndex = 0, orderInGroup = 5), previous.ref)
        assertEquals(SessionWordStatus.PLAYING, repo.getSessionWord(sessionId, wordIds[5])?.status) // 回绕自身仍 PLAYING
        assertTrue(repo.mastery.keys.none { it.first == sessionId && it.second == wordIds[5] }) // 零掌握写入
    }

    @Test
    fun previousIsPureNavigationWithoutMasteryChange() = kotlinx.coroutines.test.runTest {
        val wordIds = (0L until 10L).toList()
        val repo = repoWithQueue(*wordIds.toLongArray())
        val (engine, _) = engine(repo)
        val sessionId = assertIs<StartResult.Started>(engine.startSession(wordBookId)).snapshot.session.sessionId

        repeat(5) { i -> engine.playAndMaster(sessionId, wordIds[i]) }
        assertEquals(wordIds[5], assertIs<AdvanceResult.NextWord>(engine.advance(sessionId)).ref.wordId)
        val masteredBefore = repo.mastery.keys.toSet()

        engine.previous(sessionId)
        engine.previous(sessionId)

        assertEquals(masteredBefore, repo.mastery.keys.toSet()) // 掌握账本零变化（FR-11）
        // 全部 SessionWord 仍 ∈ {PENDING, PLAYING, MASTERED}，无新增 MASTERED
        val masteredCount = repo.getSessionWords(sessionId).count { it.status == SessionWordStatus.MASTERED }
        assertEquals(5, masteredCount)
    }

    @Test
    fun noPlayingWordPreviousSelectsLastUnmasteredInGroup() = kotlinx.coroutines.test.runTest {
        // 无播放位（开场，advance 对称取组内最小的镜像）→ 取组内最大
        val wordIds = (0L until 10L).toList()
        val repo = repoWithQueue(*wordIds.toLongArray())
        val (engine, _) = engine(repo)
        val sessionId = assertIs<StartResult.Started>(engine.startSession(wordBookId)).snapshot.session.sessionId

        val previous = assertIs<AdvanceResult.NextWord>(engine.previous(sessionId))

        assertEquals(WordRef(sessionId, wordIds[9], groupIndex = 0, orderInGroup = 9), previous.ref)
        assertEquals(SessionWordStatus.PLAYING, repo.getSessionWord(sessionId, wordIds[9])?.status)
    }

    @Test
    fun completedSessionRepeatPreviousIsIdempotentReadOnly() = kotlinx.coroutines.test.runTest {
        val repo = repoWithQueue(101L)
        val (engine, _) = engine(repo)
        val sessionId = assertIs<StartResult.Started>(engine.startSession(wordBookId)).snapshot.session.sessionId
        engine.playAndMaster(sessionId, 101L)
        assertEquals(AdvanceResult.BookComplete, engine.previous(sessionId)) // §5 → §7（与 advance 同前置）
        val writesAfterCompletion = repo.setPlayingWordCalls.size

        assertEquals(AdvanceResult.BookComplete, engine.previous(sessionId)) // 重复 previous 幂等

        assertEquals(1, repo.updateSessionStatusCalls.size) // 终态只写一次
        assertEquals(writesAfterCompletion, repo.setPlayingWordCalls.size) // 只读：零位置迁移
        assertEquals(SessionStatus.COMPLETED, repo.sessions[sessionId]?.status)
    }

    @Test
    fun nonActiveSessionPreviousReturnsBookCompleteWithoutWrites() = kotlinx.coroutines.test.runTest {
        val repo = repoWithQueue(101L, 102L, 103L)
        val (engine, _) = engine(repo)
        val sessionId = assertIs<StartResult.Started>(engine.startSession(wordBookId)).snapshot.session.sessionId
        engine.advance(sessionId) // 有播放位
        repo.updateSessionStatus(sessionId, SessionStatus.ABANDONED) // 会话中途被终止
        val writesAfterAbandon = repo.setPlayingWordCalls.size

        assertEquals(AdvanceResult.BookComplete, engine.previous(sessionId))

        assertEquals(writesAfterAbandon, repo.setPlayingWordCalls.size) // 终态会话零位置写入
        assertEquals(SessionStatus.ABANDONED, repo.sessions[sessionId]?.status) // 状态不被改写
    }

    @Test
    fun missingSessionPreviousThrowsContractException() = kotlinx.coroutines.test.runTest {
        val (engine, _) = engine(repoWithQueue(101L))

        assertFailsWith<RepositoryValidationException> { engine.previous(999L) }
    }

    @Test
    fun newEngineInstanceContinuesPreviousFromPersistedPlayingPosition() = kotlinx.coroutines.test.runTest {
        // 引擎零内存位：重建引擎（模拟进程重启）后 previous 从持久化位回退，非从组尾重推
        val wordIds = (0L until 10L).toList()
        val repo = repoWithQueue(*wordIds.toLongArray())
        val clock = FixedClock()
        val (engine1, _) = engine(repo, clock)
        val sessionId = assertIs<StartResult.Started>(engine1.startSession(wordBookId)).snapshot.session.sessionId
        assertEquals(wordIds[0], assertIs<AdvanceResult.NextWord>(engine1.advance(sessionId)).ref.wordId) // 位=0
        assertEquals(wordIds[1], assertIs<AdvanceResult.NextWord>(engine1.advance(sessionId)).ref.wordId) // 纯跳转

        val engine2 = DefaultLearningEngine(
            repo,
            FakeLearningSettingsRepository(),
            MasteryMarker(repo, clock),
            WordBookDeriver(FakeWordBookRepository(), clock),
        )

        val previous = assertIs<AdvanceResult.NextWord>(engine2.previous(sessionId))
        assertEquals(wordIds[0], previous.ref.wordId) // 从持久化 PLAYING（wordIds[1]）回退到未掌握的 0
        assertEquals(0, previous.ref.groupIndex)
    }
}
