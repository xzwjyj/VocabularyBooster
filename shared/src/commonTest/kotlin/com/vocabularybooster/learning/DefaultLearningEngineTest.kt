package com.vocabularybooster.learning

import com.vocabularybooster.domain.model.PlaybackToggles
import com.vocabularybooster.domain.model.SessionSnapshot
import com.vocabularybooster.domain.model.SessionStatus
import com.vocabularybooster.domain.model.SessionWordPlacement
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
 * DefaultLearningEngine（LE spec §3/§9/§11，TC-LE-01/02/09 编排侧）：
 * 引擎 = 纯编排——startSession 四拒绝分支 + 正常管线（Builder → Splitter → 仓储物化）、
 * resumeSession 只读快照不重建、markMastered 委托 MasteryMarker 且绝不走底层无守卫写。
 * 经手写 Fake 端口驱动；真实 SQLDelight 编排与崩溃恢复持久化见 jvmTest 集成测试。
 */
class DefaultLearningEngineTest {

    private val wordBookId = 1L

    /** 词表按 entryOrder 升序登记（orderedWordIds 即 Q2 顺序）。 */
    private fun repoWithQueue(
        vararg orderedWordIds: Long,
        wordBookId: Long = this.wordBookId,
        totalEntryCount: Int = orderedWordIds.size,
    ): FakeLearningSessionRepository = FakeLearningSessionRepository().apply {
        studySnapshots[wordBookId] = StudyQueueSnapshot(
            wordBookExists = true,
            totalEntryCount = totalEntryCount,
            unmasteredEntries = orderedWordIds.mapIndexed { index, id ->
                StudyQueueEntryRef(wordId = id, entryOrder = index)
            },
        )
    }

    private fun engine(
        repo: FakeLearningSessionRepository,
        settings: FakeLearningSettingsRepository = FakeLearningSettingsRepository(),
        clock: FixedClock = FixedClock(),
    ): LearningEngine = DefaultLearningEngine(
        sessionRepository = repo,
        settingsRepository = settings,
        masteryMarker = MasteryMarker(repo, clock),
        wordBookDeriver = WordBookDeriver(FakeWordBookRepository(), clock),
    )

    private fun placements(wordIds: List<Long>, groupSize: Int): List<SessionWordPlacement> =
        wordIds.mapIndexed { index, wordId ->
            SessionWordPlacement(wordId = wordId, groupIndex = index / groupSize, orderInGroup = index % groupSize)
        }

    // —— startSession：正常管线 ——

    @Test
    fun startSessionCreatesActiveSessionWithMaterializedQueue() = kotlinx.coroutines.test.runTest {
        val repo = repoWithQueue(101L, 102L, 103L)
        val engine = engine(repo)

        val result = engine.startSession(wordBookId)

        val started = assertIs<StartResult.Started>(result)
        assertEquals(1L, started.snapshot.session.sessionId)
        assertEquals(wordBookId, started.snapshot.session.wordBookId)
        assertEquals(SessionStatus.ACTIVE, started.snapshot.session.status)
        assertEquals(10, started.snapshot.session.groupSize) // 默认 groupSize 来自设置端口
        assertEquals(listOf(101L, 102L, 103L), started.snapshot.words.map { it.wordId })
        assertTrue(started.snapshot.words.all { it.status == SessionWordStatus.PENDING && it.masteredAt == null })
        assertEquals(started.snapshot, repo.getSessionWithWords(1L)) // 结果 = 库中真相
    }

    @Test
    fun queueFollowsEntryOrderNotWordIdOrder() = kotlinx.coroutines.test.runTest {
        // Q2 顺序（entryOrder ASC）与 wordId 数值序相反：引擎不重排，位置即真相
        val repo = repoWithQueue(30L, 20L, 10L)
        val engine = engine(repo)

        val started = assertIs<StartResult.Started>(engine.startSession(wordBookId))

        assertEquals(listOf(30L, 20L, 10L), started.snapshot.words.map { it.wordId })
    }

    @Test
    fun enginePassesSplitterOutputToRepository_25WordsDefaultGroups() = kotlinx.coroutines.test.runTest {
        val wordIds = (1L..25L).toList()
        val repo = repoWithQueue(*wordIds.toLongArray())
        val engine = engine(repo)

        val started = assertIs<StartResult.Started>(engine.startSession(wordBookId))

        // 25 词 groupSize 10 → 10/10/5（LE spec §4；引擎把 Step 2 结果原样交给仓储物化）
        assertEquals(placements(wordIds, 10).map { Triple(it.wordId, it.groupIndex, it.orderInGroup) },
            started.snapshot.words.map { Triple(it.wordId, it.groupIndex, it.orderInGroup) })
        assertEquals(listOf(10, 10, 5), started.snapshot.words.groupBy { it.groupIndex }
            .toSortedMap().values.map { it.size })
    }

    @Test
    fun hundredWordsFormExactlyTenFullGroups() = kotlinx.coroutines.test.runTest {
        // 100×10 engine 级验证：wordId 与 entryOrder 互为乱序（×37 mod 100 为置换），防按 wordId 排序伪装通过
        val wordIds = List(100) { (it * 37 % 100).toLong() + 1L }
        assertEquals(100, wordIds.toSet().size) // 置换自检：无重复即无遗漏
        val repo = repoWithQueue(*wordIds.toLongArray())
        val engine = engine(repo)

        val started = assertIs<StartResult.Started>(engine.startSession(wordBookId))
        val words = started.snapshot.words

        assertEquals(100, words.size)
        assertEquals(wordIds, words.map { it.wordId }) // 队列顺序 = entryOrder 序，无遗漏无重复
        assertEquals((0 until 100).map { it / 10 }, words.map { it.groupIndex }) // 组号连续 0..9
        assertEquals((0 until 100).map { it % 10 }, words.map { it.orderInGroup }) // 组内序连续 0..9
        assertEquals(10, words.groupBy { it.groupIndex }.size) // 恰 10 组
        assertTrue(words.groupBy { it.groupIndex }.values.all { it.size == 10 }) // 每组恰 10 词
    }

    @Test
    fun groupSizeComesFromSettingsNotHardcoded() = kotlinx.coroutines.test.runTest {
        val wordIds = (1L..20L).toList()
        val repo = repoWithQueue(*wordIds.toLongArray())
        val settings = FakeLearningSettingsRepository(groupSize = 7)
        val engine = engine(repo, settings)

        val started = assertIs<StartResult.Started>(engine.startSession(wordBookId))

        assertEquals(7, started.snapshot.session.groupSize) // 会话固化设置值
        assertEquals(listOf(7, 7, 6), started.snapshot.words.groupBy { it.groupIndex }
            .toSortedMap().values.map { it.size })
    }

    // —— startSession：拒绝分支（LE spec §3 顺序）——

    @Test
    fun emptyBookIsRejected() = kotlinx.coroutines.test.runTest {
        val repo = FakeLearningSessionRepository().apply {
            studySnapshots[wordBookId] =
                StudyQueueSnapshot(wordBookExists = true, totalEntryCount = 0, unmasteredEntries = emptyList())
        }
        val engine = engine(repo)

        assertEquals(
            StartResult.Rejected(StartResult.Reason.EMPTY_BOOK),
            engine.startSession(wordBookId),
        )
        assertNull(repo.getActiveSession()) // 拒绝零写入
    }

    @Test
    fun allMasteredBookIsRejected() = kotlinx.coroutines.test.runTest {
        val repo = FakeLearningSessionRepository().apply {
            studySnapshots[wordBookId] = StudyQueueSnapshot(
                wordBookExists = true,
                totalEntryCount = 5,
                unmasteredEntries = emptyList(), // Q2 为空 = 全部已掌握
            )
        }
        val engine = engine(repo)

        assertEquals(
            StartResult.Rejected(StartResult.Reason.ALL_MASTERED),
            engine.startSession(wordBookId),
        )
        assertNull(repo.getActiveSession())
    }

    @Test
    fun existingActiveSessionIsRejectedWithItsId() = kotlinx.coroutines.test.runTest {
        val repo = repoWithQueue(101L)
        repo.createSession(wordBookId = wordBookId, groupSize = 10, words = placements(listOf(101L), 10))
        // 同一仓储登记第二本书（有未掌握词）：startSession(2L) 应撞上 ACTIVE 唯一性
        repo.studySnapshots[2L] = StudyQueueSnapshot(
            wordBookExists = true,
            totalEntryCount = 2,
            unmasteredEntries = listOf(StudyQueueEntryRef(201L, 0), StudyQueueEntryRef(202L, 1)),
        )
        val engine = engine(repo)

        // ACTIVE 唯一性经仓储事务内检查（防线 1）→ 引擎映射为携带既有会话 ID 的拒绝结果
        val rejected = assertIs<StartResult.Rejected>(engine.startSession(2L))

        assertEquals(StartResult.Reason.ACTIVE_SESSION_EXISTS(1L), rejected.reason)
        assertEquals(1L, repo.getActiveSession()?.sessionId) // 旧会话原样保留
    }

    @Test
    fun missingWordBookPropagatesExistingContract() = kotlinx.coroutines.test.runTest {
        val repo = FakeLearningSessionRepository() // 未登记任何快照 → 本不存在
        val engine = engine(repo)

        // 不吞异常、不伪装 EMPTY_BOOK：沿用 domain 既有契约抛出
        assertFailsWith<RepositoryValidationException> { engine.startSession(999L) }
        assertNull(repo.getActiveSession())
    }

    @Test
    fun playbackTogglesAllOffIsRejectedWithoutSession() = kotlinx.coroutines.test.runTest {
        val repo = repoWithQueue(101L, 102L)
        val settings = FakeLearningSettingsRepository(
            playbackToggles = PlaybackToggles(
                pronunciation = false, spelling = false, meaningEn = false,
                meaningCn = false, example = false, exampleCn = false,
            ),
        )
        val engine = engine(repo, settings)

        assertEquals(
            StartResult.Rejected(StartResult.Reason.PLAYBACK_DISABLED),
            engine.startSession(wordBookId),
        )
        assertNull(repo.getActiveSession()) // 拒绝零写入
        assertEquals(0, repo.createSessionCalls)
    }

    @Test
    fun createSessionFailurePropagatesUnchanged() = kotlinx.coroutines.test.runTest {
        val repo = repoWithQueue(101L).apply {
            createSessionFailure = IllegalStateException("磁盘已满（注入）")
        }
        val engine = engine(repo)

        // 底层异常原样传播（包装策略属引擎错误态/Phase 4，本步不吞不改）
        assertFailsWith<IllegalStateException> { engine.startSession(wordBookId) }
        assertNull(repo.getActiveSession())
    }

    // —— resumeSession：只读恢复，绝不重建（LE spec §9）——

    @Test
    fun activeSessionResumesWithExactPersistedSnapshot() = kotlinx.coroutines.test.runTest {
        val repo = repoWithQueue(101L, 102L, 103L)
        val clock = FixedClock()
        val engine = engine(repo, clock = clock)
        val started = assertIs<StartResult.Started>(engine.startSession(wordBookId))

        // 推进学习状态：掌握一词（Marked 携带注入 Clock 时刻）
        clock.advanceMillis(3_000)
        assertIs<MasteryResult.Marked>(engine.markMastered(1L, 101L, MasterySource.BUTTON))
        val persisted: SessionSnapshot = repo.getSessionWithWords(1L)!!

        val resumed = assertIs<ResumeResult.Resumed>(engine.resumeSession(1L))

        assertEquals(persisted, resumed.snapshot) // 逐字段读回：状态/masteredAt/分组/顺序全保留
        assertEquals(SessionWordStatus.MASTERED, resumed.snapshot.words.first { it.wordId == 101L }.status)
        assertEquals(clock.now(), resumed.snapshot.words.first { it.wordId == 101L }.masteredAt)
        assertEquals(started.snapshot.session.groupSize, resumed.snapshot.session.groupSize)
    }

    @Test
    fun resumeDoesNotCreateSessionOrRecomputePlacements() = kotlinx.coroutines.test.runTest {
        val repo = repoWithQueue(101L, 102L, 103L)
        val engine = engine(repo)
        engine.startSession(wordBookId)
        assertEquals(1, repo.createSessionCalls)

        // 模拟持久层既有值与重算值不同（groupIndex 9 = 重算法绝不会产出的结构）：
        // 恢复必须逐字信任库中放置，不重跑 Builder/Splitter（会话 = 固化快照）
        repo.words[1L]!![1] = repo.words[1L]!![1].copy(groupIndex = 9, orderInGroup = 9)

        val resumed = assertIs<ResumeResult.Resumed>(engine.resumeSession(1L))

        assertEquals(1, repo.createSessionCalls) // 未新建会话
        assertEquals(9, resumed.snapshot.words.first { it.wordId == 102L }.groupIndex) // 原样读回
        assertEquals(9, resumed.snapshot.words.first { it.wordId == 102L }.orderInGroup)
    }

    @Test
    fun missingSessionIsRejectedNotFound() = kotlinx.coroutines.test.runTest {
        val engine = engine(FakeLearningSessionRepository())

        assertEquals(
            ResumeResult.Rejected(ResumeResult.Reason.SESSION_NOT_FOUND),
            engine.resumeSession(999L),
        )
    }

    @Test
    fun nonActiveSessionIsRejectedNotActive() = kotlinx.coroutines.test.runTest {
        val repo = repoWithQueue(101L)
        val engine = engine(repo)
        engine.startSession(wordBookId)
        repo.updateSessionStatus(1L, SessionStatus.ABANDONED) // 会话已终止（数据不一致分支）

        assertEquals(
            ResumeResult.Rejected(ResumeResult.Reason.SESSION_NOT_ACTIVE),
            engine.resumeSession(1L),
        )
    }

    // —— mastery 委托边界（Step 3 锁定：引擎不得绕过 MasteryMarker）——

    @Test
    fun markMasteredDelegatesToMasteryMarkerSemantics() = kotlinx.coroutines.test.runTest {
        val repo = repoWithQueue(101L, 102L)
        val clock = FixedClock()
        val engine = engine(repo, clock = clock)
        engine.startSession(wordBookId)

        clock.advanceMillis(2_000)
        val firstMarkedAt = clock.now()
        assertEquals(
            MasteryResult.Marked(firstMarkedAt),
            engine.markMastered(1L, 101L, MasterySource.VOICE),
        )
        // MasteryMarker 语义：三处状态齐备（SessionWord + masteredAt + WordMastery）
        assertEquals(SessionWordStatus.MASTERED, repo.getSessionWord(1L, 101L)?.status)
        assertEquals(firstMarkedAt, repo.getSessionWord(1L, 101L)?.masteredAt)
        assertEquals(firstMarkedAt.toEpochMilliseconds(), repo.mastery[wordBookId to 101L])

        // 幂等同样经 MasteryMarker：重复标记保持首次时刻，不刷新
        clock.advanceMillis(90_000)
        assertEquals(MasteryResult.AlreadyMastered, engine.markMastered(1L, 101L, MasterySource.BUTTON))
        assertEquals(firstMarkedAt.toEpochMilliseconds(), repo.mastery[wordBookId to 101L])
    }

    @Test
    fun engineNeverCallsUnguardedStatusUpdate() = kotlinx.coroutines.test.runTest {
        val repo = repoWithQueue(101L, 102L)
        val engine = engine(repo)

        engine.startSession(wordBookId)
        engine.markMastered(1L, 101L, MasterySource.BUTTON)
        engine.markMastered(1L, 101L, MasterySource.VOICE) // 重复标记也只走 MasteryMarker
        engine.resumeSession(1L)

        // 全程零底层无守卫写调用（updateSessionWordStatus(MASTERED) 会刷新 masteredAt——被禁止的旁路）
        assertEquals(0, repo.updateSessionWordStatusCalls.size)
    }
}
