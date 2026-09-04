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
 * DefaultLearningEngine.advance（LE spec §5/§7/§11，TC-LE-03/05/06，Phase 3 Step 5A）：
 * 组内顺序推进 / 回绕循环 / 组空自然进组 / 混合状态按 status 选择 / 边界规模（1/10/11/20/21）
 * / 完成判据唯一权威 CompletionDetector / 位置持久化推导（引擎重建续推）/ 终态幂等。
 * 经手写 Fake 端口驱动；真实 SQLDelight 推进 + 崩溃重启链路见 jvmTest 集成测试。
 */
class LearningEngineAdvanceTest {

    private val wordBookId = 1L

    /** 词表按 entryOrder 升序登记（orderedWordIds 即 Q2 顺序），默认 groupSize 10。 */
    private fun repoWithQueue(vararg orderedWordIds: Long): FakeLearningSessionRepository =
        FakeLearningSessionRepository().apply {
            studySnapshots[wordBookId] = StudyQueueSnapshot(
                wordBookExists = true,
                totalEntryCount = orderedWordIds.size,
                unmasteredEntries = orderedWordIds.mapIndexed { index, id ->
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
    fun firstAdvanceSelectsLowestGroupFirstWordAsPlaying() = kotlinx.coroutines.test.runTest {
        val wordIds = listOf(101L, 102L, 103L)
        val repo = repoWithQueue(*wordIds.toLongArray())
        val (engine, _) = engine(repo)

        val sessionId = assertIs<StartResult.Started>(engine.startSession(wordBookId)).snapshot.session.sessionId

        val next = assertIs<AdvanceResult.NextWord>(engine.advance(sessionId))

        assertEquals(WordRef(sessionId, 101L, groupIndex = 0, orderInGroup = 0), next.ref)
        assertNull(next.completedGroupIndex) // 会话起点无离开的组
        assertEquals(SessionWordStatus.PLAYING, repo.getSessionWord(sessionId, 101L)?.status) // 位已持久化
        assertEquals(listOf(sessionId to 101L), repo.setPlayingWordCalls)
        assertTrue(repo.updateSessionWordStatusCalls.isEmpty()) // 位置写走专用通道，非底层无守卫写
    }

    @Test
    fun singleWordSessionCompletesAfterMastery() = kotlinx.coroutines.test.runTest {
        val repo = repoWithQueue(101L)
        val (engine, clock) = engine(repo)
        val sessionId = assertIs<StartResult.Started>(engine.startSession(wordBookId)).snapshot.session.sessionId

        assertEquals(101L, assertIs<AdvanceResult.NextWord>(engine.advance(sessionId)).ref.wordId)
        clock.advanceMillis(1_000)
        engine.playAndMaster(sessionId, 101L)

        assertEquals(AdvanceResult.BookComplete, engine.advance(sessionId)) // 唯一词掌握 → §5 → §7
        assertEquals(SessionStatus.COMPLETED, repo.sessions[sessionId]?.status) // Q3 默认 0 → 终态
        assertEquals(listOf(sessionId to SessionStatus.COMPLETED), repo.updateSessionStatusCalls)
    }

    @Test
    fun tenWordsSingleGroupSequentialToCompletion() = kotlinx.coroutines.test.runTest {
        val wordIds = (0L until 10L).toList()
        val repo = repoWithQueue(*wordIds.toLongArray())
        val (engine, _) = engine(repo)
        val sessionId = assertIs<StartResult.Started>(engine.startSession(wordBookId)).snapshot.session.sessionId

        wordIds.forEachIndexed { i, wordId ->
            val next = assertIs<AdvanceResult.NextWord>(engine.advance(sessionId))
            assertEquals(wordId, next.ref.wordId)
            assertEquals(i, next.ref.orderInGroup)
            assertNull(next.completedGroupIndex) // 单组内推进永不报组完成
            assertIs<MasteryResult.Marked>(engine.markMastered(sessionId, wordId, MasterySource.BUTTON))
        }

        assertEquals(AdvanceResult.BookComplete, engine.advance(sessionId))
        assertEquals(SessionStatus.COMPLETED, repo.sessions[sessionId]?.status)
    }

    @Test
    fun elevenWordsBoundaryLastGroupHasSingleWordNoOffByOne() = kotlinx.coroutines.test.runTest {
        val wordIds = (0L until 11L).toList()
        val repo = repoWithQueue(*wordIds.toLongArray())
        val (engine, _) = engine(repo)
        val sessionId = assertIs<StartResult.Started>(engine.startSession(wordBookId)).snapshot.session.sessionId

        repeat(10) { i -> engine.playAndMaster(sessionId, wordIds[i]) } // 组 0 清空

        val next = assertIs<AdvanceResult.NextWord>(engine.advance(sessionId))
        assertEquals(wordIds[10], next.ref.wordId)
        assertEquals(1, next.ref.groupIndex) // 11 = 10 + 1：末组恰一词，无差一
        assertEquals(0, next.ref.orderInGroup)
        assertEquals(0, next.completedGroupIndex) // §7 GroupCompleted 载体
        assertIs<MasteryResult.Marked>(engine.markMastered(sessionId, wordIds[10], MasterySource.BUTTON))

        assertEquals(AdvanceResult.BookComplete, engine.advance(sessionId)) // 末组单词不等待幻影词
        assertEquals(SessionStatus.COMPLETED, repo.sessions[sessionId]?.status)
    }

    @Test
    fun twentyWordsCrossIntoSecondFullGroupExactlyAtEleventh() = kotlinx.coroutines.test.runTest {
        val wordIds = (0L until 20L).toList()
        val repo = repoWithQueue(*wordIds.toLongArray())
        val (engine, _) = engine(repo)
        val sessionId = assertIs<StartResult.Started>(engine.startSession(wordBookId)).snapshot.session.sessionId

        wordIds.forEachIndexed { i, wordId ->
            val next = assertIs<AdvanceResult.NextWord>(engine.advance(sessionId))
            assertEquals(wordId, next.ref.wordId)
            // 组 0 内推进为 null；进入组 1 后前一组（组 0）保持完成态 → 稳定携带 0
            // （纯推导的位置信息，非一次性事件——零内存位语义，见 AdvanceResult.NextWord KDoc）
            assertEquals(if (i >= 10) 0 else null, next.completedGroupIndex)
            assertIs<MasteryResult.Marked>(engine.markMastered(sessionId, wordId, MasterySource.BUTTON))
        }

        assertEquals(AdvanceResult.BookComplete, engine.advance(sessionId))
    }

    @Test
    fun twentyOneWordsPartialFinalGroupCompletesWithoutWaiting() = kotlinx.coroutines.test.runTest {
        val wordIds = (0L until 21L).toList() // 10 + 10 + 1
        val repo = repoWithQueue(*wordIds.toLongArray())
        val (engine, _) = engine(repo)
        val sessionId = assertIs<StartResult.Started>(engine.startSession(wordBookId)).snapshot.session.sessionId

        wordIds.forEachIndexed { i, wordId ->
            val next = assertIs<AdvanceResult.NextWord>(engine.advance(sessionId))
            assertEquals(wordId, next.ref.wordId)
            // 纯推导位置信息：组 0 内 null；组 1 内前一组（组 0）完成 → 0；组 2 内 → 1
            assertEquals(when { i < 10 -> null; i < 20 -> 0; else -> 1 }, next.completedGroupIndex)
            assertIs<MasteryResult.Marked>(engine.markMastered(sessionId, wordId, MasterySource.BUTTON))
        }

        // 不完全末组（1 词）正确完成：不等待不存在的第 2 词，也不跳过末组
        assertEquals(AdvanceResult.BookComplete, engine.advance(sessionId))
        assertEquals(SessionStatus.COMPLETED, repo.sessions[sessionId]?.status)
    }

    @Test
    fun mixedStatusSelectsByStatusNotCountHeuristic() = kotlinx.coroutines.test.runTest {
        // 组内 M M P M：唯一未掌握词被选中——组未被误判完成、也不按计数跳组
        val wordIds = listOf(10L, 11L, 12L, 13L)
        val repo = repoWithQueue(*wordIds.toLongArray())
        val (engine, _) = engine(repo)
        val sessionId = assertIs<StartResult.Started>(engine.startSession(wordBookId)).snapshot.session.sessionId

        listOf(10L, 11L, 13L).forEach { engine.playAndMaster(sessionId, it) }

        val next = assertIs<AdvanceResult.NextWord>(engine.advance(sessionId))
        assertEquals(12L, next.ref.wordId)
        assertEquals(0, next.ref.groupIndex) // 仍组 0：12L 未掌握，组不完成
        assertNull(next.completedGroupIndex)
    }

    @Test
    fun wrapAroundLandsOnFirstUnmasteredInGroup() = kotlinx.coroutines.test.runTest {
        // §5 回绕：播到组尾 → 组内首个未掌握词；Next（不掌握连续 advance）按播放位顺延
        val wordIds = (0L until 10L).toList()
        val repo = repoWithQueue(*wordIds.toLongArray())
        val (engine, _) = engine(repo)
        val sessionId = assertIs<StartResult.Started>(engine.startSession(wordBookId)).snapshot.session.sessionId

        repeat(5) { i -> engine.playAndMaster(sessionId, wordIds[i]) } // 掌握 0..4

        assertEquals(wordIds[5], assertIs<AdvanceResult.NextWord>(engine.advance(sessionId)).ref.wordId)
        // 不掌握连续推进：从持久化 PLAYING 位（5）顺延 6→7→8→9
        (6..9).forEach { i ->
            assertEquals(wordIds[i], assertIs<AdvanceResult.NextWord>(engine.advance(sessionId)).ref.wordId)
        }
        // 组尾回绕：组内唯一/首个未掌握 = wordIds[5]（4..9 中 5 为最小未掌握）
        val wrapped = assertIs<AdvanceResult.NextWord>(engine.advance(sessionId))
        assertEquals(wordIds[5], wrapped.ref.wordId)
        assertEquals(SessionWordStatus.PLAYING, repo.getSessionWord(sessionId, wordIds[5])?.status)
    }

    @Test
    fun completedSessionRepeatAdvanceIsIdempotentReadOnly() = kotlinx.coroutines.test.runTest {
        val repo = repoWithQueue(101L)
        val (engine, _) = engine(repo)
        val sessionId = assertIs<StartResult.Started>(engine.startSession(wordBookId)).snapshot.session.sessionId
        engine.playAndMaster(sessionId, 101L)
        assertEquals(AdvanceResult.BookComplete, engine.advance(sessionId))
        val writesAfterCompletion = repo.setPlayingWordCalls.size

        assertEquals(AdvanceResult.BookComplete, engine.advance(sessionId)) // 重复 advance 幂等

        assertEquals(1, repo.updateSessionStatusCalls.size) // 终态只写一次，endedAt 不刷新
        assertEquals(writesAfterCompletion, repo.setPlayingWordCalls.size) // 只读：零位置迁移
        assertEquals(SessionStatus.COMPLETED, repo.sessions[sessionId]?.status)
    }

    @Test
    fun nonActiveSessionAdvanceReturnsBookCompleteWithoutWrites() = kotlinx.coroutines.test.runTest {
        val repo = repoWithQueue(101L, 102L, 103L)
        val (engine, _) = engine(repo)
        val sessionId = assertIs<StartResult.Started>(engine.startSession(wordBookId)).snapshot.session.sessionId
        engine.advance(sessionId) // 有播放位
        repo.updateSessionStatus(sessionId, SessionStatus.ABANDONED) // 会话中途被终止
        val writesAfterAbandon = repo.setPlayingWordCalls.size

        assertEquals(AdvanceResult.BookComplete, engine.advance(sessionId))

        assertEquals(writesAfterAbandon, repo.setPlayingWordCalls.size) // 终态会话零位置写入
        assertEquals(SessionStatus.ABANDONED, repo.sessions[sessionId]?.status) // 状态不被改写
    }

    @Test
    fun missingSessionAdvanceThrowsContractException() = kotlinx.coroutines.test.runTest {
        val (engine, _) = engine(repoWithQueue(101L))

        assertFailsWith<RepositoryValidationException> { engine.advance(999L) }
    }

    @Test
    fun sessionExhaustedWithMidSessionBookAdditionStaysActive() = kotlinx.coroutines.test.runTest {
        // 规格未定义分支：会话队列耗尽但书在会话中途新增词（队列固化，新词不入本会话）——
        // Q3 > 0 → 不设终态（退出三分支裁决），但本会话确无可播词
        val repo = repoWithQueue(101L, 102L)
        repo.unmasteredEntryCounts[wordBookId] = 3
        val (engine, _) = engine(repo)
        val sessionId = assertIs<StartResult.Started>(engine.startSession(wordBookId)).snapshot.session.sessionId
        engine.playAndMaster(sessionId, 101L)
        engine.playAndMaster(sessionId, 102L)

        assertEquals(AdvanceResult.BookComplete, engine.advance(sessionId))

        assertEquals(SessionStatus.ACTIVE, repo.sessions[sessionId]?.status) // deferred：不 COMPLETED
        assertTrue(repo.updateSessionStatusCalls.isEmpty())
    }

    @Test
    fun newEngineInstanceContinuesFromPersistedPlayingPosition() = kotlinx.coroutines.test.runTest {
        // 引擎零内存位：播放位只在库中——重建引擎（模拟进程重启）后 advance 从持久化位续推
        val wordIds = (0L until 10L).toList()
        val repo = repoWithQueue(*wordIds.toLongArray())
        val clock = FixedClock()
        val (engine1, _) = engine(repo, clock)
        val sessionId = assertIs<StartResult.Started>(engine1.startSession(wordBookId)).snapshot.session.sessionId
        engine1.playAndMaster(sessionId, wordIds[0])
        assertEquals(wordIds[1], assertIs<AdvanceResult.NextWord>(engine1.advance(sessionId)).ref.wordId) // 位=1

        val engine2 = DefaultLearningEngine(
            repo,
            FakeLearningSettingsRepository(),
            MasteryMarker(repo, clock),
            WordBookDeriver(FakeWordBookRepository(), clock),
        )

        val next = assertIs<AdvanceResult.NextWord>(engine2.advance(sessionId))
        assertEquals(wordIds[2], next.ref.wordId) // 从持久化 PLAYING（wordIds[1]）顺延，非重头播
        assertEquals(0, next.ref.groupIndex)
    }
}
