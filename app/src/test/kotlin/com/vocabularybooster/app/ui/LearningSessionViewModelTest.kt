package com.vocabularybooster.app.ui

import androidx.lifecycle.ViewModelStore
import com.vocabularybooster.domain.event.DefaultDomainEventBus
import com.vocabularybooster.domain.event.DomainEvent
import com.vocabularybooster.domain.model.Achievement
import com.vocabularybooster.domain.model.AchievementType
import com.vocabularybooster.domain.model.BookCompletedPayload
import com.vocabularybooster.domain.model.Lang
import com.vocabularybooster.learning.MasterySource
import com.vocabularybooster.learning.ResumeResult
import com.vocabularybooster.learning.StartResult
import com.vocabularybooster.playback.PlaybackOrchestrator
import com.vocabularybooster.speech.CommandParser
import com.vocabularybooster.speech.RecognitionResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Phase 4 Step 4 ViewModel 单测（JVM，无模拟器）：
 * 真实 PlaybackOrchestrator + 手写 Fake 端口（LearningSessionTestSupport），
 * 覆盖映射（A–F）+ 命令转发（G–K）+ BOOK_DELETED（L）+ 生命周期（M）+ ERROR 投影。
 * 状态机/段时序语义不在此重测（Step 2 已锁死）；每词单段（PRONUNCIATION）使时序确定。
 *
 * 引擎 Fake 契约注记：startSession 成功后编排器用 advance() 裁决起始词
 * （快照无 PLAYING 词），故 advanceWordIds[0] = 起始词 boost、[1] = 第二词 abandon。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LearningSessionViewModelTest {

    private lateinit var engine: FakeLearningEngine
    private lateinit var synthesizer: FakeSpeechSynthesizer
    private lateinit var audioPlayer: FakeAudioPlayer
    private lateinit var settings: FakeLearningSettingsRepository
    private lateinit var content: FakePlaybackContentRepository
    private lateinit var position: FakePlaybackPositionRepository
    private lateinit var recognizer: FakeSpeechCommandRecognizer
    private lateinit var viewModelStore: ViewModelStore
    private lateinit var orchestrator: PlaybackOrchestrator
    private lateinit var achievements: FakeAchievementRepository
    private lateinit var eventBus: DefaultDomainEventBus

    /**
     * 编排器驱动 scope：共享 runTest 调度器（advanceUntilIdle 可推进其任务）的独立 scope。
     * 不能用 backgroundScope——本 coroutines-test 版本下其任务不被 advanceUntilIdle 执行
     * （驱动协程会永久停在队列里，state 停留 Idle）。
     */
    private lateinit var orchestratorScope: CoroutineScope

    @Before
    fun setUp() {
        viewModelStore = ViewModelStore()
        engine = FakeLearningEngine(startResult = StartResult.Started(LearningSessionFixtures.snapshot())).apply {
            advanceWordIds = listOf(LearningSessionFixtures.WORD_BOOST, LearningSessionFixtures.WORD_ABANDON)
        }
        synthesizer = FakeSpeechSynthesizer()
        audioPlayer = FakeAudioPlayer()
        settings = FakeLearningSettingsRepository()
        content = FakePlaybackContentRepository().apply { textsByWordId = LearningSessionFixtures.contentTexts() }
        position = FakePlaybackPositionRepository()
        recognizer = FakeSpeechCommandRecognizer() // 默认不可用 = P4 纯倒计时（既有用例时序不变）
        achievements = FakeAchievementRepository()
        eventBus = DefaultDomainEventBus()
    }

    @After
    fun tearDown() {
        viewModelStore.clear() // 触发 onCleared → dispose（M 用例在用例体内先行 clear，此处兜底）
        if (::orchestratorScope.isInitialized) orchestratorScope.cancel()
        Dispatchers.resetMain()
    }

    /** runTest 作用域内装配：真实编排器（共享调度器的独立 scope 驱动）+ Main 虚拟调度器 + 注册 ViewModelStore。 */
    private fun TestScope.assembleWithStore(): LearningSessionViewModel {
        orchestratorScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        orchestrator = PlaybackOrchestrator(
            engine = engine,
            contentRepository = content,
            positionRepository = position,
            settingsRepository = settings,
            audioPlayer = audioPlayer,
            synthesizer = synthesizer,
            recognizer = recognizer,
            commandParser = CommandParser(),
            eventBus = eventBus,
            scope = orchestratorScope,
        )
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        return LearningSessionViewModel(
            orchestrator,
            achievements,
            eventBus,
            WordPronouncer(synthesizer, settings, orchestratorScope), // FR-22 扩展：音标旁试听（共享调度器 scope）
        )
            .also { viewModelStore.put("learning", it) }
    }

    // —— A. 初始状态 ——

    @Test
    fun initialStateIsLoading() = runTest {
        val vm = assembleWithStore()
        assertEquals(LearningUiState.Loading, vm.ui) // A：未接入会话前不呈现播放状态
        assertNull(vm.conflictSessionId)
        assertNull(vm.notice)
    }

    // —— B. PLAYING → UI state ——

    @Test
    fun playingMapsToUiWithWordGroupAndSegment() = runTest {
        val vm = assembleWithStore()
        vm.start(LearningSessionFixtures.BOOK_ID)
        advanceUntilIdle()

        val playing = vm.ui as LearningUiState.Playing // B：首词首段 Playing 映射
        assertEquals("boost", playing.wordText)
        assertEquals(1, playing.groupIndex) // 用户可见组号 1 起
        assertEquals("单词发音", playing.segmentLabel) // PRONUNCIATION 段
        assertEquals(1, playing.segmentIndex) // 用户可见段号 1 起
        assertTrue(!playing.degraded)
        assertEquals(1, synthesizer.requests.size) // 首段在播（驱动协程挂起在 speak）
    }

    // —— C. PAUSED 投影 + G. Pause 命令转发 ——

    @Test
    fun pauseMapsToUiAndForwardsToOrchestrator() = runTest {
        val vm = assembleWithStore()
        vm.start(LearningSessionFixtures.BOOK_ID)
        advanceUntilIdle()

        vm.pause() // G：UI action → ViewModel → orchestrator.pause()
        advanceUntilIdle()

        val paused = vm.ui as LearningUiState.Paused // C
        assertEquals("boost", paused.wordText)
        assertEquals(1, paused.segmentIndex) // 段粒度暂停（未完成段 = 用户可见 1）
        assertTrue(synthesizer.stopCount > 0) // 编排器停 TTS 段端口（§3 TTS 段 stop()）
        assertTrue(position.saveCount > 0) // 暂停位持久化被触发（§5）
    }

    // —— H. Resume 命令转发 ——

    @Test
    fun resumeForwardsAndReturnsToPlaying() = runTest {
        val vm = assembleWithStore()
        vm.start(LearningSessionFixtures.BOOK_ID)
        advanceUntilIdle()
        vm.pause()
        advanceUntilIdle()
        val speakCountBefore = synthesizer.requests.size

        vm.resume() // H：Paused → orchestrator.resume() → TTS 重读本段
        advanceUntilIdle()

        val playing = vm.ui as LearningUiState.Playing
        assertEquals("boost", playing.wordText)
        assertEquals(1, playing.segmentIndex) // 同段重读（ADR-09：绝不整词重播）
        assertTrue(synthesizer.requests.size > speakCountBefore)
    }

    // —— I. Replay 命令转发 ——

    @Test
    fun replayForwardsAndRestartsWordFromFirstSegment() = runTest {
        val vm = assembleWithStore()
        vm.start(LearningSessionFixtures.BOOK_ID)
        advanceUntilIdle()

        vm.replay() // I：编排器 replay 语义 = 当前词 seg0 重播
        advanceUntilIdle()

        val playing = vm.ui as LearningUiState.Playing
        assertEquals("boost", playing.wordText) // 词不变
        assertEquals(1, playing.segmentIndex) // 回到第一段
        assertTrue(engine.exitCalls.isEmpty()) // replay 不触碰退出/掌握
    }

    // —— J. Next 命令转发 ——

    @Test
    fun nextForwardsToAdvanceWithoutMastery() = runTest {
        val vm = assembleWithStore()
        vm.start(LearningSessionFixtures.BOOK_ID)
        advanceUntilIdle()

        vm.next() // J：UI → ViewModel → orchestrator.next()（不标记掌握，纯跳转）
        advanceUntilIdle()

        val playing = vm.ui as LearningUiState.Playing
        assertEquals("abandon", playing.wordText) // 第二词（advance #2）
        assertEquals(2, engine.advanceCalls.size) // 推进由引擎裁决：起始 advance + next advance
        assertEquals(1, playing.segmentIndex)
    }

    // —— J2. Previous 命令转发（SCR-PREVWORD，TC-AE-34）——

    @Test
    fun previousForwardsToEnginePreviousWithoutMastery() = runTest {
        engine.previousWordIds = listOf(LearningSessionFixtures.WORD_BOOST)
        val vm = assembleWithStore()
        vm.start(LearningSessionFixtures.BOOK_ID)
        advanceUntilIdle()
        vm.next() // 起始 advance → boost；next advance → abandon（回退的出发点）
        advanceUntilIdle()

        vm.previous() // J2：UI → ViewModel → orchestrator.previous()（纯跳转，零掌握）
        advanceUntilIdle()

        val playing = vm.ui as LearningUiState.Playing
        assertEquals("boost", playing.wordText) // 回退词（previous #1）
        assertEquals(1, playing.segmentIndex) // 新词 seg0（用户可见 1 起）
        assertEquals(listOf(LearningSessionFixtures.SESSION_ID), engine.previousCalls) // 恰转发一次
        assertTrue(engine.markMasteredCalls.isEmpty()) // FR-11：previous 不触碰掌握
    }

    // —— N. 音标旁朗读转发（FR-22 扩展，TC-IPAPRON-05）——

    @Test
    fun pronounceWordSpeaksWithAccentWithoutTouchingSessionOrMastery() = runTest {
        settings.ttsRate = 0.8f // 用户语速随试听（与会话朗读听感一致）
        val vm = assembleWithStore()
        vm.start(LearningSessionFixtures.BOOK_ID)
        advanceUntilIdle()

        vm.pronounceWord("boost", Lang.EN_GB) // N：一次性试听——不经编排器、零掌握
        advanceUntilIdle()

        val preview = synthesizer.requests.last() // 会话段之后追加的试听请求
        assertEquals("boost", preview.text)
        assertEquals(Lang.EN_GB, preview.lang)
        assertEquals(0.8f, preview.rate, 0f)
        assertEquals(1, engine.advanceCalls.size) // 会话零推进（纯预览不入段状态机）
        assertTrue(engine.markMasteredCalls.isEmpty())
        val playing = vm.ui as LearningUiState.Playing
        assertEquals("boost", playing.wordText) // UI 状态不被试听扰动
    }

    // —— D. COMMAND_WINDOW 倒计时投影 ——

    @Test
    fun commandWindowCountdownUsesOrchestratorState() = runTest {
        val vm = assembleWithStore()
        vm.start(LearningSessionFixtures.BOOK_ID)
        advanceUntilIdle()

        synthesizer.releaseLast() // 首段完成 → 词内段尽 → guard 300ms → 窗口开启
        testScheduler.advanceTimeBy(400)
        testScheduler.runCurrent()

        val window = vm.ui as LearningUiState.CommandWindow // D：窗口态直接消费编排器倒计时
        assertEquals("boost", window.wordText) // 词文本延续自携带态缓存
        assertEquals(1, window.groupIndex)
        assertEquals(4_000L, window.totalMs) // 时长来自设置源（VM 无自有计时）
        val firstRemaining = window.remainingMs
        assertTrue("首采样应接近满窗：$firstRemaining", firstRemaining in 3_500..4_000)

        testScheduler.advanceTimeBy(1_000) // 虚拟时间推进 1s（编排器 100ms tick）
        testScheduler.runCurrent()
        val later = vm.ui as LearningUiState.CommandWindow
        assertTrue("倒计时应递减：$firstRemaining → ${later.remainingMs}", later.remainingMs < firstRemaining)
    }

    // —— E. COMPLETED 投影 ——

    @Test
    fun completedMapsWhenEngineReportsBookComplete() = runTest {
        engine.advanceWordIds = listOf(LearningSessionFixtures.WORD_BOOST) // advance 耗尽 → BookComplete
        val vm = assembleWithStore()
        vm.start(LearningSessionFixtures.BOOK_ID)
        advanceUntilIdle()

        synthesizer.releaseLast() // 段完成 → 窗口倒计时走完 → advance → BookComplete → Completed
        testScheduler.advanceTimeBy(300 + 4_000 + 100)
        testScheduler.runCurrent()
        advanceUntilIdle()

        assertEquals(LearningUiState.Completed(medal = null), vm.ui) // E：完成权威 = 引擎裁决（UI 不重判）
        assertTrue(position.clearCount > 0) // 完成清除位置存档（§5）
    }

    // —— E2/E3. 完成仪式勋章快照（Phase 6：首查命中 + AchievementUnlocked 事件刷新）——

    @Test
    fun completedShowsCeremonyMedalWhenAlreadyGranted() = runTest {
        engine.advanceWordIds = listOf(LearningSessionFixtures.WORD_BOOST)
        achievements.medalsByBook[LearningSessionFixtures.BOOK_ID] = grantedMedal()
        val vm = assembleWithStore()
        vm.start(LearningSessionFixtures.BOOK_ID)
        advanceUntilIdle()

        synthesizer.releaseLast() // 段完成 → 窗口倒计时走完 → advance → BookComplete → Completed
        testScheduler.advanceTimeBy(300 + 4_000 + 100)
        testScheduler.runCurrent()
        advanceUntilIdle()

        val medal = (vm.ui as LearningUiState.Completed).medal ?: error("仪式页应呈现勋章快照")
        assertEquals("考研核心词", medal.bookName)
        assertEquals(2, medal.wordCount)
        assertEquals(1_760_000_000_000L, medal.finishedAtEpochMs)
    }

    @Test
    fun achievementUnlockedEventRefreshesCeremonyAfterInitialMiss() = runTest {
        engine.advanceWordIds = listOf(LearningSessionFixtures.WORD_BOOST)
        val vm = assembleWithStore()
        vm.start(LearningSessionFixtures.BOOK_ID)
        advanceUntilIdle()
        synthesizer.releaseLast()
        testScheduler.advanceTimeBy(300 + 4_000 + 100)
        testScheduler.runCurrent()
        advanceUntilIdle()
        assertEquals(LearningUiState.Completed(medal = null), vm.ui) // 首查未命中（授予竞态窗口）

        // 异步授予完成（勋章引擎发布事件）→ 仪式页刷新补填
        achievements.medalsByBook[LearningSessionFixtures.BOOK_ID] = grantedMedal()
        eventBus.publish(
            DomainEvent.AchievementUnlocked(
                achievementId = 1L,
                type = "BOOK_COMPLETED",
                wordBookId = LearningSessionFixtures.BOOK_ID,
            ),
        )
        advanceUntilIdle()

        assertEquals("考研核心词", (vm.ui as LearningUiState.Completed).medal?.bookName)
    }

    private fun grantedMedal(): Achievement = Achievement(
        achievementId = 1L,
        type = AchievementType.BOOK_COMPLETED,
        wordBookId = LearningSessionFixtures.BOOK_ID,
        payload = BookCompletedPayload(
            bookName = "考研核心词",
            wordCount = 2,
            finishedAt = Instant.fromEpochMilliseconds(1_760_000_000_000L),
        ),
        earnedAt = Instant.fromEpochMilliseconds(1_760_000_000_000L),
    )

    // —— F. STOPPED 投影 + K. Exit 命令转发 ——

    @Test
    fun exitForwardsClearsPositionAndStops() = runTest {
        val vm = assembleWithStore()
        vm.start(LearningSessionFixtures.BOOK_ID)
        advanceUntilIdle()

        vm.exit() // K：UI → ViewModel → orchestrator.exit()（三分支裁决在引擎）
        advanceUntilIdle()

        assertEquals(LearningUiState.Stopped, vm.ui) // F：Stopped = 导航离开信号
        assertNotNull(vm.exitResult)
        assertEquals(listOf(LearningSessionFixtures.SESSION_ID), engine.exitCalls)
        assertTrue(position.clearCount > 0) // 退出清除位置存档（§5）
        assertTrue(synthesizer.stopCount > 0) // exit 停全部端口
        assertTrue(audioPlayer.stopCount > 0)
    }

    // —— ERROR 投影（TTS 失败 → Paused(error=TTS_FAILED) → Error UI，§9 可表达子集）——

    @Test
    fun ttsFailureMapsToErrorPause() = runTest {
        synthesizer.throwOnSpeak = true
        val vm = assembleWithStore()
        vm.start(LearningSessionFixtures.BOOK_ID)
        advanceUntilIdle()

        val error = vm.ui as LearningUiState.Error
        assertEquals("boost", error.wordText)
        assertEquals(1, error.groupIndex)
        assertEquals(1, error.segmentIndex)
        assertEquals(0, engine.exitCalls.size) // §9：会话/掌握状态不动，非终态
    }

    // —— L. BOOK_DELETED 呈现（TC-AE-15 UI 侧）——

    @Test
    fun resumeRejectedBookDeletedShowsNoticeWithoutProgression() = runTest {
        // 场景：编排器 Idle（进程重建）+ 库中遗留 ACTIVE 会话 → start 被拒 → 恢复 → 书已删
        engine = FakeLearningEngine(
            startResult = StartResult.Rejected(
                StartResult.Reason.ACTIVE_SESSION_EXISTS(LearningSessionFixtures.SESSION_ID),
            ),
            resumeResult = ResumeResult.Rejected(ResumeResult.Reason.BOOK_DELETED),
        ).apply { advanceWordIds = emptyList() }
        val vm = assembleWithStore()

        vm.start(LearningSessionFixtures.BOOK_ID)
        advanceUntilIdle()
        assertEquals(LearningSessionFixtures.SESSION_ID, vm.conflictSessionId) // 冲突对话框

        vm.resumeExistingSession()
        advanceUntilIdle()

        assertEquals(LearningNotice.BookDeleted, vm.notice) // L：BOOK_DELETED 提示
        assertEquals(LearningUiState.Loading, vm.ui) // 无会话操作入口（未起播）
        assertEquals(0, engine.advanceCalls.size) // 不驱动引擎推进
        assertEquals(0, synthesizer.requests.size) // 不出声
    }

    // —— 冲突「放弃旧的并开始」：resume → exit → start 全走编排器公开 API ——

    @Test
    fun abandonExistingThenStartRunsThroughOrchestratorOnly() = runTest {
        // 第一段：start 被拒（已有 ACTIVE 会话）→ 冲突对话框就绪
        engine.startResult = StartResult.Rejected(
            StartResult.Reason.ACTIVE_SESSION_EXISTS(LearningSessionFixtures.SESSION_ID),
        )
        val vm = assembleWithStore()
        vm.start(LearningSessionFixtures.BOOK_ID)
        advanceUntilIdle()
        assertEquals(LearningSessionFixtures.SESSION_ID, vm.conflictSessionId)

        // 第二段：脚本翻转——旧会话可恢复、旧书可退出、新书可开始
        engine.startResult = StartResult.Started(LearningSessionFixtures.snapshot())
        engine.resumeResult = ResumeResult.Resumed(LearningSessionFixtures.snapshot())
        engine.advanceWordIds = listOf(LearningSessionFixtures.WORD_BOOST, LearningSessionFixtures.WORD_BOOST)

        vm.abandonExistingAndStart()
        advanceUntilIdle()

        assertTrue(engine.resumeCalls.contains(LearningSessionFixtures.SESSION_ID)) // 先恢复旧会话
        assertTrue(engine.exitCalls.contains(LearningSessionFixtures.SESSION_ID)) // 旧会话经既有 exit 三分支
        assertTrue(engine.startCalls.contains(LearningSessionFixtures.BOOK_ID)) // 再开始新会话
        assertNull(vm.conflictSessionId)
        val playing = vm.ui as LearningUiState.Playing
        assertEquals("boost", playing.wordText) // 新会话重新起播
    }

    // —— M. 生命周期 / dispose ——

    @Test
    fun onClearedDisposesOrchestratorPorts() = runTest {
        val vm = assembleWithStore()
        vm.start(LearningSessionFixtures.BOOK_ID)
        advanceUntilIdle()
        val stopsBefore = synthesizer.stopCount + audioPlayer.stopCount

        viewModelStore.clear() // M：宿主销毁 → onCleared → dispose（停驱动 Job + 播放端口）

        assertTrue(synthesizer.stopCount + audioPlayer.stopCount > stopsBefore)
        // dispose 后驱动协程不再推进：放行挂起 utterance 也不产生新请求
        val requestsBefore = synthesizer.requests.size
        synthesizer.releaseLast()
        advanceUntilIdle()
        assertEquals(requestsBefore, synthesizer.requests.size)
    }

    // —— Phase 5 Step 1：语音「会了」/ 按钮同路径（TC-AE-04/05/06 UI 侧 + 裁决 D2/D5/AUDIO §11）——

    /** 「boost 词被掌握」的期望调用记录（source 区分语音/按钮入口）。 */
    private fun boostMasteredCall(source: MasterySource): Triple<Long, Long, MasterySource> =
        Triple(LearningSessionFixtures.SESSION_ID, LearningSessionFixtures.WORD_BOOST, source)

    /** 驱动到 boost 词的 CommandWindow（release 首段 → 300ms guard → 窗口开）。 */
    private fun TestScope.driveBoostToWindow(vm: LearningSessionViewModel) {
        vm.start(LearningSessionFixtures.BOOK_ID)
        advanceUntilIdle()
        synthesizer.releaseLast()
        testScheduler.advanceTimeBy(400)
        testScheduler.runCurrent()
    }

    @Test
    fun voiceMasteredCommandMarksOnceAndAdvances() = runTest {
        recognizer.isAvailable.value = true
        recognizer.script += RecognitionResult.Hit("会了")
        val vm = assembleWithStore()

        driveBoostToWindow(vm) // 窗口开 → Hit（会了）→ 唯一路径 markMastered(VOICE) → advance → 词 2 起播
        advanceUntilIdle()

        assertEquals(
            listOf(boostMasteredCall(MasterySource.VOICE)),
            engine.markMasteredCalls,
        ) // 「会了」→ progression 恰一次
        assertEquals(1, recognizer.listenCalls.size) // 消费后不再监听（AUDIO §11 防重）
        val playing = vm.ui as LearningUiState.Playing
        assertEquals("abandon", playing.wordText)
    }

    @Test
    fun noiseTextNeverMastersAndWindowRunsToTimeoutAdvance() = runTest {
        recognizer.isAvailable.value = true
        recognizer.script += RecognitionResult.Hit("你好")
        val vm = assembleWithStore()

        driveBoostToWindow(vm)
        testScheduler.runCurrent() // 噪音命中（UNKNOWN）→ 保持监听

        testScheduler.advanceTimeBy(1_000)
        testScheduler.runCurrent()
        val window = vm.ui as LearningUiState.CommandWindow
        assertTrue(window.listening) // 窗口未结束、仍在监听
        assertTrue(engine.markMasteredCalls.isEmpty()) // 「你好」→ progression 0

        testScheduler.advanceTimeBy(3_000) // 窗口耗尽 → 既有超时 advance
        testScheduler.runCurrent()
        advanceUntilIdle()
        assertTrue(engine.markMasteredCalls.isEmpty()) // 全程零掌握
        val playing = vm.ui as LearningUiState.Playing
        assertEquals("abandon", playing.wordText)
    }

    @Test
    fun recognitionUnavailableDegradesWindowButButtonStillMasters() = runTest {
        recognizer.isAvailable.value = true
        recognizer.script += RecognitionResult.Unavailable
        val vm = assembleWithStore()

        driveBoostToWindow(vm)
        testScheduler.advanceTimeBy(1_000)
        testScheduler.runCurrent()
        val window = vm.ui as LearningUiState.CommandWindow
        assertTrue(!window.listening) // D5：降级纯倒计时（诚实呈现）
        assertTrue(engine.markMasteredCalls.isEmpty()) // 识别错误绝不掌握/推进

        vm.masterCurrentWord() // 降级窗口内按钮照常可用
        advanceUntilIdle()
        assertEquals(
            listOf(boostMasteredCall(MasterySource.BUTTON)),
            engine.markMasteredCalls,
        )
        val playing = vm.ui as LearningUiState.Playing
        assertEquals("abandon", playing.wordText)
    }

    @Test
    fun masterButtonForwardsAndDoubleTapMastersAtMostOnce() = runTest {
        recognizer.isAvailable.value = false // 降级窗口（按钮为主要入口）
        val vm = assembleWithStore()
        driveBoostToWindow(vm)
        testScheduler.runCurrent()

        vm.masterCurrentWord() // VM → 编排器 masterCurrentWord（与语音同一执行路径）
        testScheduler.runCurrent()
        vm.masterCurrentWord() // AUDIO §11 重复防护：第二击 no-op
        advanceUntilIdle()

        assertEquals(
            listOf(boostMasteredCall(MasterySource.BUTTON)),
            engine.markMasteredCalls,
        ) // 重复触发 → progression 恰一次
        val playing = vm.ui as LearningUiState.Playing
        assertEquals("abandon", playing.wordText)
    }

    @Test
    fun masterButtonOutsideCommandWindowIsNoOp() = runTest {
        recognizer.isAvailable.value = false
        val vm = assembleWithStore()
        vm.start(LearningSessionFixtures.BOOK_ID)
        advanceUntilIdle()
        assertTrue(vm.ui is LearningUiState.Playing) // D2：按钮仅窗口态可用

        vm.masterCurrentWord()
        advanceUntilIdle()

        assertTrue(engine.markMasteredCalls.isEmpty()) // 窗口外 → progression 0
        assertEquals("boost", (vm.ui as LearningUiState.Playing).wordText)
    }
}
