package com.vocabularybooster.learning

import com.vocabularybooster.domain.model.SessionStatus
import com.vocabularybooster.domain.model.SessionWordStatus
import com.vocabularybooster.domain.model.StudyQueueEntryRef
import com.vocabularybooster.domain.model.StudyQueueSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * TC-LE-10 恢复完整性（LE spec §9/§10-8/§10-9，Phase 3 Step 5B）：
 * Case A 书已删 → resume 时安全 ABANDONED + BOOK_DELETED 提示（幂等、不可继续 progression）；
 * Case B 词已从本移除 → 该词从会话剔除（悬挂行删除、placement 不重编号、不伪 MASTERED/SKIPPED）、
 * 其余照常——中/首/末/全部/MASTERED/PLAYING/跨组场景。
 * 经手写 Fake 端口驱动；真实 SQLDelight 事务与重启链路见 jvmTest LearningEngineRecoveryIntegrationTest。
 */
class LearningEngineRecoveryTest {

    private val wordBookId = 1L

    /** 词表按 entryOrder 升序登记（orderedWordIds 即 Q2 顺序），并登记本内现存词条全集。 */
    private fun repoWithQueue(vararg orderedWordIds: Long): FakeLearningSessionRepository =
        FakeLearningSessionRepository().apply {
            studySnapshots[wordBookId] = StudyQueueSnapshot(
                wordBookExists = true,
                totalEntryCount = orderedWordIds.size,
                studyEntries = orderedWordIds.mapIndexed { index, id ->
                    StudyQueueEntryRef(wordId = id, entryOrder = index)
                },
            )
            bookEntryWordIds[wordBookId] = orderedWordIds.toSet()
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

    private suspend fun LearningEngine.startedSessionId(): Long =
        assertIs<StartResult.Started>(startSession(wordBookId)).snapshot.session.sessionId

    // —— Case A：书已删 → 安全 ABANDON（§10-8）——

    @Test
    fun bookDeletedResumeAbandonsSessionAndRejectsWithPromptReason() = kotlinx.coroutines.test.runTest {
        val repo = repoWithQueue(101L, 102L, 103L)
        val (engine, _) = engine(repo)
        val sessionId = engine.startedSessionId()
        repo.deletedBooks += wordBookId // 书在会话 ACTIVE 期间被删除

        val rejected = assertIs<ResumeResult.Rejected>(engine.resumeSession(sessionId))

        assertEquals(ResumeResult.Reason.BOOK_DELETED, rejected.reason) // 提示载体，不静默
        assertEquals(SessionStatus.ABANDONED, repo.sessions[sessionId]?.status) // 状态转换已持久化
        assertEquals(0L, repo.sessions[sessionId]?.endedAt?.toEpochMilliseconds()) // endedAt 已写（fake 纪元）
        assertEquals(listOf(sessionId to SessionStatus.ABANDONED), repo.updateSessionStatusCalls)
    }

    @Test
    fun bookDeletedRecoveryRepeatResumeIsIdempotent() = kotlinx.coroutines.test.runTest {
        val repo = repoWithQueue(101L, 102L)
        val (engine, _) = engine(repo)
        val sessionId = engine.startedSessionId()
        repo.deletedBooks += wordBookId
        engine.resumeSession(sessionId) // 第一次：ABANDON + BOOK_DELETED

        // 第二次恢复：已非 ACTIVE → 既有 NOT_ACTIVE 契约短路，ABANDON 不重复落库
        val second = assertIs<ResumeResult.Rejected>(engine.resumeSession(sessionId))

        assertEquals(ResumeResult.Reason.SESSION_NOT_ACTIVE, second.reason)
        assertEquals(1, repo.updateSessionStatusCalls.size) // endedAt 不被刷新
        assertEquals(SessionStatus.ABANDONED, repo.sessions[sessionId]?.status)
    }

    @Test
    fun abandonedByRecoverySessionCannotContinueProgression() = kotlinx.coroutines.test.runTest {
        val repo = repoWithQueue(101L, 102L)
        val (engine, _) = engine(repo)
        val sessionId = engine.startedSessionId()
        repo.deletedBooks += wordBookId
        engine.resumeSession(sessionId)

        // 不允许继续以 ACTIVE 状态学习不存在的书：advance/mark 均走终态/拒绝语义
        assertEquals(AdvanceResult.BookComplete, engine.advance(sessionId))
        assertEquals(MasteryResult.Rejected(MasteryResult.Reason.SESSION_NOT_ACTIVE),
            engine.markMastered(sessionId, 101L, MasterySource.BUTTON))
        assertTrue(repo.setPlayingWordCalls.isEmpty()) // 零位置写入
    }

    // —— Case B：词已从本移除 → 剔除该词其余照常（§10-9，不整会话 ABANDON）——

    @Test
    fun removedMiddleWordPurgedOthersKeepOriginalPlacement() = kotlinx.coroutines.test.runTest {
        val wordIds = listOf(11L, 12L, 13L, 14L, 15L)
        val repo = repoWithQueue(*wordIds.toLongArray())
        val (engine, _) = engine(repo)
        val sessionId = engine.startedSessionId()
        repo.bookEntryWordIds[wordBookId] = (wordIds - 13L).toSet() // 13 从本中移除

        val resumed = assertIs<ResumeResult.Resumed>(engine.resumeSession(sessionId))

        assertEquals(listOf(11L, 12L, 14L, 15L), resumed.snapshot.words.map { it.wordId }) // 1 2 4 5
        assertEquals(listOf(0, 1, 3, 4), resumed.snapshot.words.map { it.orderInGroup }) // 原位保留，不重编号
        assertTrue(resumed.snapshot.words.all { it.status == SessionWordStatus.PENDING }) // 无伪 MASTERED/SKIPPED
        assertEquals(SessionStatus.ACTIVE, repo.sessions[sessionId]?.status) // 不整会话 ABANDON
    }

    @Test
    fun removedFirstWordProgressionStartsAtSecond() = kotlinx.coroutines.test.runTest {
        val wordIds = listOf(11L, 12L, 13L)
        val repo = repoWithQueue(*wordIds.toLongArray())
        val (engine, _) = engine(repo)
        val sessionId = engine.startedSessionId()
        repo.bookEntryWordIds[wordBookId] = (wordIds - 11L).toSet()

        engine.resumeSession(sessionId)

        assertEquals(12L, assertIs<AdvanceResult.NextWord>(engine.advance(sessionId)).ref.wordId)
    }

    @Test
    fun removedLastWordRemainingQueueCompletesCorrectly() = kotlinx.coroutines.test.runTest {
        val wordIds = listOf(11L, 12L, 13L, 14L, 15L)
        val repo = repoWithQueue(*wordIds.toLongArray())
        val (engine, _) = engine(repo)
        val sessionId = engine.startedSessionId()
        repo.bookEntryWordIds[wordBookId] = (wordIds - 15L).toSet()
        engine.resumeSession(sessionId)

        listOf(11L, 12L, 13L, 14L).forEach { wordId ->
            assertIs<AdvanceResult.NextWord>(engine.advance(sessionId))
            assertIs<MasteryResult.Marked>(engine.markMastered(sessionId, wordId, MasterySource.BUTTON))
        }

        // 剩余队列学完 → Q3=0（fake 默认）→ COMPLETED：不等待被移除的 15L
        assertEquals(AdvanceResult.BookComplete, engine.advance(sessionId))
        assertEquals(SessionStatus.COMPLETED, repo.sessions[sessionId]?.status)
    }

    @Test
    fun allWordsRemovedResumeContinuesEmptyQueueToCompletion() = kotlinx.coroutines.test.runTest {
        val wordIds = listOf(11L, 12L, 13L)
        val repo = repoWithQueue(*wordIds.toLongArray())
        val (engine, _) = engine(repo)
        val sessionId = engine.startedSessionId()
        repo.bookEntryWordIds[wordBookId] = emptySet() // 有效 SessionWord 归零
        val resumed = assertIs<ResumeResult.Resumed>(engine.resumeSession(sessionId))

        assertTrue(resumed.snapshot.words.isEmpty()) // 会话仍 ACTIVE、快照如实为空（其余照常 = 无词可学）

        // §5 words.isEmpty() → BookComplete → §7 Q3=0（本内词条已全部移除）→ COMPLETED
        assertEquals(AdvanceResult.BookComplete, engine.advance(sessionId))
        assertEquals(SessionStatus.COMPLETED, repo.sessions[sessionId]?.status)
    }

    @Test
    fun masteredWordRemovedPurgeKeepsMasteryUntouchedAndOthersProgress() = kotlinx.coroutines.test.runTest {
        val wordIds = listOf(11L, 12L)
        val repo = repoWithQueue(*wordIds.toLongArray())
        val (engine, _) = engine(repo)
        val sessionId = engine.startedSessionId()
        assertIs<AdvanceResult.NextWord>(engine.advance(sessionId))
        assertIs<MasteryResult.Marked>(engine.markMastered(sessionId, 11L, MasterySource.BUTTON))
        repo.bookEntryWordIds[wordBookId] = setOf(12L) // 已掌握的 11L 从本移除

        val resumed = assertIs<ResumeResult.Resumed>(engine.resumeSession(sessionId))

        assertEquals(listOf(12L), resumed.snapshot.words.map { it.wordId }) // 11L 剔除、不重建
        assertNull(resumed.snapshot.words.firstOrNull { it.wordId == 11L }) // 行已删除，非状态伪装
        // 剔除不触碰 WordMastery（移词路径自理；此处未模拟移词删掌握 → 原样保留）
        assertEquals(setOf(wordBookId to 11L), repo.mastery.keys)
        // 12L 不被错误牵连：照常推进并完成
        assertEquals(12L, assertIs<AdvanceResult.NextWord>(engine.advance(sessionId)).ref.wordId)
        assertIs<MasteryResult.Marked>(engine.markMastered(sessionId, 12L, MasterySource.BUTTON))
        assertEquals(AdvanceResult.BookComplete, engine.advance(sessionId))
        assertEquals(SessionStatus.COMPLETED, repo.sessions[sessionId]?.status)
    }

    @Test
    fun playingWordRemovedLeavesNoDanglingPlayingAndContinuesAtNext() = kotlinx.coroutines.test.runTest {
        val wordIds = listOf(11L, 12L, 13L)
        val repo = repoWithQueue(*wordIds.toLongArray())
        val (engine, _) = engine(repo)
        val sessionId = engine.startedSessionId()
        assertEquals(11L, assertIs<AdvanceResult.NextWord>(engine.advance(sessionId)).ref.wordId) // 11L PLAYING
        repo.bookEntryWordIds[wordBookId] = setOf(12L, 13L) // 播放中的 11L 被移除

        val resumed = assertIs<ResumeResult.Resumed>(engine.resumeSession(sessionId))

        assertTrue(resumed.snapshot.words.none { it.status == SessionWordStatus.PLAYING }) // 无悬挂 PLAYING
        assertTrue(resumed.snapshot.words.none { it.wordId == 11L })

        assertEquals(12L, assertIs<AdvanceResult.NextWord>(engine.advance(sessionId)).ref.wordId) // 继续到 B
        assertEquals(SessionWordStatus.PLAYING, repo.getSessionWord(sessionId, 12L)?.status)
    }

    @Test
    fun crossGroupRemovalKeepsGroupIndexAndOrderWithoutRenumbering() = kotlinx.coroutines.test.runTest {
        val wordIds = (0L until 20L).toList() // group 0: 0..9，group 1: 10..19
        val repo = repoWithQueue(*wordIds.toLongArray())
        val (engine, _) = engine(repo)
        val sessionId = engine.startedSessionId()
        val removed = setOf(wordIds[5], wordIds[15]) // 跨组删 5、15
        repo.bookEntryWordIds[wordBookId] = wordIds.toSet() - removed
        val original = repo.getSessionWithWords(sessionId)!!

        val resumed = assertIs<ResumeResult.Resumed>(engine.resumeSession(sessionId))

        // placement 逐词保持：剩余词的 (groupIndex, orderInGroup) 与原值一致（空洞保留，无重编号）
        assertEquals(
            original.words.filter { it.wordId !in removed }
                .map { Triple(it.wordId, it.groupIndex, it.orderInGroup) },
            resumed.snapshot.words.map { Triple(it.wordId, it.groupIndex, it.orderInGroup) },
        )
        // 无重复 orderInGroup（每组内）
        resumed.snapshot.words.groupBy { it.groupIndex }.values.forEach { group ->
            assertEquals(group.size, group.map { it.orderInGroup }.toSet().size)
        }
        // progression 跨组正确：组 0 剩余 9 词学完 → 进组 1 首个未掌握词（= 原 order 0 的词）
        wordIds.filter { it !in removed && it < 10L }.forEach { wordId ->
            assertIs<AdvanceResult.NextWord>(engine.advance(sessionId))
            assertIs<MasteryResult.Marked>(engine.markMastered(sessionId, wordId, MasterySource.BUTTON))
        }
        val crossed = assertIs<AdvanceResult.NextWord>(engine.advance(sessionId))
        assertEquals(wordIds[10], crossed.ref.wordId)
        assertEquals(1, crossed.ref.groupIndex)
        assertEquals(0, crossed.completedGroupIndex) // 组 0 完成（删除不算掌握，余 9 词全掌握才算）
    }

    @Test
    fun resumeWithoutDeletionsIsPurgeNoOp() = kotlinx.coroutines.test.runTest {
        val wordIds = listOf(11L, 12L, 13L)
        val repo = repoWithQueue(*wordIds.toLongArray())
        val (engine, _) = engine(repo)
        val sessionId = engine.startedSessionId()
        assertEquals(11L, assertIs<AdvanceResult.NextWord>(engine.advance(sessionId)).ref.wordId) // 有播放位/掌握差异

        val resumed = assertIs<ResumeResult.Resumed>(engine.resumeSession(sessionId))

        assertEquals(repo.getSessionWithWords(sessionId), resumed.snapshot) // 全量原样（含 PLAYING 11L）
        assertEquals(SessionWordStatus.PLAYING, resumed.snapshot.words.first { it.wordId == 11L }.status)
        assertNull(resumed.snapshot.words.firstOrNull { it.wordId == 12L }?.masteredAt)
        assertTrue(repo.updateSessionStatusCalls.isEmpty()) // 零终态写入
    }
}
