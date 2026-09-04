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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * DefaultLearningEngine.exitSession（LE spec §8 退出三分支，Phase 3 Step 5C/5D）：
 * ACTIVE→ABANDONED（零掌握分支 A 不派生 / 部分掌握分支 B 派生 DERIVED 本 / PLAYING 冻结）、
 * ACTIVE→COMPLETED（分支 C：Q3=0，含最后一词已 master 未触发完成检测的退出瞬间）、
 * 终态幂等（endedAt 不刷新）、终态与 resume/advance/markMastered 的交互契约、学习历史保留。
 * 派生内容一致性（释义/例句逐 ID、mastery 隔离、母本隔离）属 WordBookDeriver/jvmTest
 * 派生测试（TC-LE-07）；真实 SQLDelight 原子性与重启持久化见 jvmTest 集成测试。
 */
class LearningEngineExitTest {

    private val wordBookId = 1L

    private fun repoWithQueue(vararg orderedWordIds: Long): FakeLearningSessionRepository =
        FakeLearningSessionRepository().apply {
            studySnapshots[wordBookId] = StudyQueueSnapshot(
                wordBookExists = true,
                totalEntryCount = orderedWordIds.size,
                unmasteredEntries = orderedWordIds.mapIndexed { index, id ->
                    StudyQueueEntryRef(wordId = id, entryOrder = index)
                },
            )
            bookEntryWordIds[wordBookId] = orderedWordIds.toSet()
            // Q3 退出裁决数据源的自然初值：全新本全部未掌握（掌握后由用例按场景递减）
            unmasteredEntryCounts[wordBookId] = orderedWordIds.size
        }

    private fun engine(
        repo: FakeLearningSessionRepository,
        clock: FixedClock = FixedClock(),
        wordBooks: FakeWordBookRepository = FakeWordBookRepository(),
    ): Pair<LearningEngine, FakeWordBookRepository> =
        DefaultLearningEngine(
            repo,
            FakeLearningSettingsRepository(),
            MasteryMarker(repo, clock),
            WordBookDeriver(wordBooks, clock),
        ) to wordBooks

    private suspend fun LearningEngine.startedSessionId(): Long =
        assertIs<StartResult.Started>(startSession(wordBookId)).snapshot.session.sessionId

    private suspend fun LearningEngine.playAndMaster(sessionId: Long, wordId: Long) {
        assertIs<AdvanceResult.NextWord>(advance(sessionId))
        assertIs<MasteryResult.Marked>(markMastered(sessionId, wordId, MasterySource.BUTTON))
    }

    // —— ACTIVE → ABANDONED（分支 A/B）——

    @Test
    fun exitWithZeroMasteryAbandonsPreservingAllHistory() = kotlinx.coroutines.test.runTest {
        val repo = repoWithQueue(101L, 102L, 103L)
        val (engine, wordBooks) = engine(repo)
        val sessionId = engine.startedSessionId()

        val result = engine.exitSession(sessionId)

        assertEquals(ExitResult(derivedWordBookId = null), result) // 分支 A：不派生
        assertEquals(SessionStatus.ABANDONED, repo.sessions[sessionId]?.status)
        assertEquals(0L, repo.sessions[sessionId]?.endedAt?.toEpochMilliseconds()) // endedAt 已写
        assertEquals(listOf(sessionId to SessionStatus.ABANDONED), repo.terminateSessionCalls)
        assertTrue(wordBooks.deriveCalls.isEmpty()) // 分支 A：零掌握不派生不建空本（Step 5D）
        // 学习历史保留：词状态原样（全 PENDING）、零掌握行
        assertTrue(repo.getSessionWords(sessionId).all { it.status == SessionWordStatus.PENDING })
        assertTrue(repo.mastery.isEmpty())
    }

    @Test
    fun exitWithPartialMasteryAbandonsKeepingMasteryAndProgress() = kotlinx.coroutines.test.runTest {
        val repo = repoWithQueue(101L, 102L, 103L)
        val clock = FixedClock()
        val wordBooks = FakeWordBookRepository().apply { bookNames[wordBookId] = "母本" }
        val (engine, _) = engine(repo, clock, wordBooks)
        val sessionId = engine.startedSessionId()
        clock.advanceMillis(5_000)
        val markedAt = clock.now()
        engine.playAndMaster(sessionId, 101L) // 部分掌握（分支 B）
        repo.unmasteredEntryCounts[wordBookId] = 2 // Q3 = 3 - 1 已掌握

        val result = engine.exitSession(sessionId)

        assertEquals(SessionStatus.ABANDONED, repo.sessions[sessionId]?.status)
        assertNotNull(result.derivedWordBookId) // 分支 B：派生 DERIVED 本（Step 5D）
        val derive = wordBooks.deriveCalls.single()
        assertEquals(wordBookId to sessionId, derive.first to derive.second) // 血缘：母本 + 来源会话
        val words = repo.getSessionWords(sessionId)
        assertEquals(SessionWordStatus.MASTERED, words.first { it.wordId == 101L }.status) // 掌握不被撤销
        assertEquals(markedAt, words.first { it.wordId == 101L }.masteredAt)
        assertTrue(words.filter { it.wordId != 101L }.all { it.status == SessionWordStatus.PENDING })
        assertEquals(markedAt.toEpochMilliseconds(), repo.mastery[wordBookId to 101L]) // WordMastery 保留
    }

    @Test
    fun exitFreezesPlayingWordStateUntouched() = kotlinx.coroutines.test.runTest {
        // 规格未规定退出清理 PLAYING → 原样冻结（终态会话不可再 advance，位无消费方）
        val repo = repoWithQueue(101L, 102L)
        val (engine, _) = engine(repo)
        val sessionId = engine.startedSessionId()
        assertEquals(101L, assertIs<AdvanceResult.NextWord>(engine.advance(sessionId)).ref.wordId)

        engine.exitSession(sessionId)

        assertEquals(SessionWordStatus.PLAYING, repo.getSessionWord(sessionId, 101L)?.status)
        assertEquals(SessionWordStatus.PENDING, repo.getSessionWord(sessionId, 102L)?.status)
    }

    // —— ACTIVE → COMPLETED（分支 C：Q3 = 0）——

    @Test
    fun exitAfterMasteringAllWordsCompletesWithoutAdvanceDetection() = kotlinx.coroutines.test.runTest {
        // §8 注记：最后一词已 master、完成检测（§7）尚未执行的退出瞬间 → 分支 C（只看数据库）；
        // 11 词（10+1 双组）= 末组单词全掌握的组边界
        val wordIds = (0L until 11L).toList()
        val repo = repoWithQueue(*wordIds.toLongArray())
        val (engine, _) = engine(repo)
        val sessionId = engine.startedSessionId()
        wordIds.forEach { engine.playAndMaster(sessionId, it) } // 全掌握，未跑最终 advance → 仍 ACTIVE
        repo.unmasteredEntryCounts[wordBookId] = 0 // Q3 = 0 → 分支 C

        assertEquals(ExitResult(derivedWordBookId = null), engine.exitSession(sessionId))

        assertEquals(SessionStatus.COMPLETED, repo.sessions[sessionId]?.status)
        assertEquals(listOf(sessionId to SessionStatus.COMPLETED), repo.terminateSessionCalls)
        assertTrue(repo.getSessionWords(sessionId).all { it.status == SessionWordStatus.MASTERED })
    }

    @Test
    fun exitOneWordShortAtMultiGroupBoundaryAbandonsNoOffByOne() = kotlinx.coroutines.test.runTest {
        // 11 词只差末词（Q3=1，末组词）→ 差一词也不判完成：分支 B = ABANDONED + 派生
        val wordIds = (0L until 11L).toList()
        val repo = repoWithQueue(*wordIds.toLongArray())
        val wordBooks = FakeWordBookRepository().apply { bookNames[wordBookId] = "母本" }
        val (engine, _) = engine(repo, FixedClock(), wordBooks)
        val sessionId = engine.startedSessionId()
        wordIds.dropLast(1).forEach { engine.playAndMaster(sessionId, it) }
        repo.unmasteredEntryCounts[wordBookId] = 1 // Q3 = 1（恰差末组最后一词）

        val result = engine.exitSession(sessionId)

        assertEquals(SessionStatus.ABANDONED, repo.sessions[sessionId]?.status)
        assertNotNull(result.derivedWordBookId) // 分支 B：部分掌握 → 派生
        assertEquals(listOf(sessionId to SessionStatus.ABANDONED), repo.terminateSessionCalls)
        assertTrue(
            repo.getSessionWords(sessionId).none {
                it.status == SessionWordStatus.MASTERED && it.wordId == wordIds.last()
            },
        )
    }

    @Test
    fun exitWithRemainingBookAdditionsAbandons() = kotlinx.coroutines.test.runTest {
        // 5A deferred 角落收口：会话队列耗尽但书有会话外新增词（Q3>0）→ 退出 = 分支 A/B；
        // MASTERED=2>0 且 Q3=3>0 按分支 B 字面裁决 → 派生（内容 = 会话非 MASTERED 词 = 0，
        // 规格字面结果：空派生本——见 Step 5D 报告 deferred）
        val repo = repoWithQueue(101L, 102L)
        repo.unmasteredEntryCounts[wordBookId] = 3
        val wordBooks = FakeWordBookRepository().apply { bookNames[wordBookId] = "母本" }
        val (engine, _) = engine(repo, FixedClock(), wordBooks)
        val sessionId = engine.startedSessionId()
        engine.playAndMaster(sessionId, 101L)
        engine.playAndMaster(sessionId, 102L)

        val result = engine.exitSession(sessionId)

        assertEquals(SessionStatus.ABANDONED, repo.sessions[sessionId]?.status) // Q3=3 → 非 COMPLETED
        assertNotNull(result.derivedWordBookId) // 分支 B 字面：MASTERED>0 && Q3>0 → 派生
    }

    // —— 终态幂等与 endedAt 不可变 ——

    @Test
    fun exitTwiceKeepsFirstTerminalStateAndSingleWrite() = kotlinx.coroutines.test.runTest {
        val repo = repoWithQueue(101L, 102L)
        val (engine, _) = engine(repo)
        val sessionId = engine.startedSessionId()
        engine.exitSession(sessionId) // → ABANDONED（1 次写入）

        val second = engine.exitSession(sessionId)

        assertEquals(ExitResult(derivedWordBookId = null), second)
        assertEquals(SessionStatus.ABANDONED, repo.sessions[sessionId]?.status) // 不改判为其它终态
        assertEquals(1, repo.terminateSessionCalls.size) // 幂等：零重复写入，endedAt 不刷新
    }

    @Test
    fun exitAfterNaturalCompletionIsIdempotentNoEndedAtRefresh() = kotlinx.coroutines.test.runTest {
        val repo = repoWithQueue(101L)
        val (engine, _) = engine(repo)
        val sessionId = engine.startedSessionId()
        engine.playAndMaster(sessionId, 101L)
        repo.unmasteredEntryCounts[wordBookId] = 0 // Q3 = 0（全书掌握）
        assertEquals(AdvanceResult.BookComplete, engine.advance(sessionId)) // §7 自然完成 → COMPLETED
        assertEquals(1, repo.updateSessionStatusCalls.size)

        val result = engine.exitSession(sessionId)

        assertEquals(ExitResult(derivedWordBookId = null), result) // COMPLETED 保持 COMPLETED
        assertEquals(SessionStatus.COMPLETED, repo.sessions[sessionId]?.status)
        assertEquals(1, repo.updateSessionStatusCalls.size) // 完成写入不被触碰
        assertTrue(repo.terminateSessionCalls.isEmpty()) // 幂等短路：零终态写入
    }

    // —— 终态与 resume/advance/markMastered 交互（既有契约保持）——

    @Test
    fun exitThenResumeRejectedNotActive() = kotlinx.coroutines.test.runTest {
        val repo = repoWithQueue(101L, 102L)
        val (engine, _) = engine(repo)
        val sessionId = engine.startedSessionId()
        engine.exitSession(sessionId)

        assertEquals(
            ResumeResult.Rejected(ResumeResult.Reason.SESSION_NOT_ACTIVE),
            engine.resumeSession(sessionId),
        )
    }

    @Test
    fun exitThenAdvanceReturnsBookCompleteWithoutWrites() = kotlinx.coroutines.test.runTest {
        val repo = repoWithQueue(101L, 102L, 103L)
        val (engine, _) = engine(repo)
        val sessionId = engine.startedSessionId()
        assertEquals(101L, assertIs<AdvanceResult.NextWord>(engine.advance(sessionId)).ref.wordId)
        engine.exitSession(sessionId)
        val writesAfterExit = repo.setPlayingWordCalls.size

        assertEquals(AdvanceResult.BookComplete, engine.advance(sessionId)) // 不可继续学习
        assertEquals(writesAfterExit, repo.setPlayingWordCalls.size) // 无 PLAYING 复活
    }

    @Test
    fun exitThenMarkMasteredRejectedWithoutMasteryWrite() = kotlinx.coroutines.test.runTest {
        val repo = repoWithQueue(101L, 102L)
        val (engine, _) = engine(repo)
        val sessionId = engine.startedSessionId()
        engine.exitSession(sessionId)

        assertEquals(
            MasteryResult.Rejected(MasteryResult.Reason.SESSION_NOT_ACTIVE),
            engine.markMastered(sessionId, 101L, MasterySource.VOICE),
        )
        assertEquals(SessionWordStatus.PENDING, repo.getSessionWord(sessionId, 101L)?.status)
        assertNull(repo.mastery[wordBookId to 101L]) // 终态不产生 mastery
    }

    @Test
    fun exitMissingSessionThrowsContractException() = kotlinx.coroutines.test.runTest {
        val (engine, _) = engine(repoWithQueue(101L))

        assertFailsWith<RepositoryValidationException> { engine.exitSession(999L) }
    }
}
