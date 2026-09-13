package com.vocabularybooster.playback

import com.vocabularybooster.domain.model.LearningSession
import com.vocabularybooster.domain.model.PlaybackToggles
import com.vocabularybooster.domain.model.SessionSnapshot
import com.vocabularybooster.domain.model.SessionStatus
import com.vocabularybooster.domain.model.SessionWord
import com.vocabularybooster.domain.model.SessionWordStatus
import com.vocabularybooster.learning.AdvanceResult
import com.vocabularybooster.learning.ExitResult
import com.vocabularybooster.learning.LearningEngine
import com.vocabularybooster.learning.MasteryResult
import com.vocabularybooster.learning.MasterySource
import com.vocabularybooster.learning.ResumeResult
import com.vocabularybooster.learning.StartResult
import com.vocabularybooster.learning.WordRef
import com.vocabularybooster.speech.CommandParser
import com.vocabularybooster.speech.RecognitionResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * PlaybackOrchestrator 语音命令集成测试（Phase 5 Step 1，TC-AE-04/05/06 + 裁决 D2/D5/AUDIO §11）：
 * 真实 CommandParser + Fake 识别器（脚本化 Hit/Timeout/Unavailable）+ 记录型引擎 Fake——
 * 本文件测**命令路由/生命周期/防重**，推进与掌握的库级语义已由 StateTest（真实引擎）与 Phase 3 锁死。
 *
 * 时序（每词单段 PRONUNCIATION = 100ms）：t=0 起播 → t=100 段完 → 300ms guard →
 * t=400 窗口开（监听或降级）→ t=4400 窗口超时。虚拟时间逐格推进。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackOrchestratorSpeechTest {

    private val sessionId = 1L
    private val bookId = 9L
    private val wordFirst = 101L
    private val wordSecond = 102L

    /** 引擎记录 Fake：掌握调用（含来源）/ advance 脚本 / 终态裁决全部可观测。 */
    private class RecordingEngine(var snapshot: SessionSnapshot) : LearningEngine {
        val markMasteredCalls = mutableListOf<Triple<Long, Long, MasterySource>>()
        val advanceCalls = mutableListOf<Long>()
        val exitCalls = mutableListOf<Long>()

        /** advance 词序（循环脚本）；耗尽 → BookComplete。首元素 = 起始词。 */
        var advanceWordIds: List<Long> = emptyList()

        override suspend fun startSession(wordBookId: Long): StartResult = StartResult.Started(snapshot)

        override suspend fun resumeSession(sessionId: Long): ResumeResult = ResumeResult.Resumed(snapshot)

        override suspend fun markMastered(sessionId: Long, wordId: Long, source: MasterySource): MasteryResult {
            markMasteredCalls += Triple(sessionId, wordId, source)
            return MasteryResult.Marked(Instant.fromEpochMilliseconds(0))
        }

        override suspend fun advance(sessionId: Long): AdvanceResult {
            advanceCalls += sessionId
            val wordId = advanceWordIds.getOrNull(advanceCalls.size - 1)
            return if (wordId == null) {
                AdvanceResult.BookComplete
            } else {
                AdvanceResult.NextWord(WordRef(sessionId, wordId, groupIndex = 0, orderInGroup = advanceCalls.size - 1), null)
            }
        }

        override suspend fun exitSession(sessionId: Long): ExitResult {
            exitCalls += sessionId
            return ExitResult(derivedWordBookId = null)
        }
    }

    private class Harness(scope: CoroutineScope, words: List<Long>) {
        val engine = RecordingEngine(snapshot(words)).apply {
            advanceWordIds = words // 首个 advance 裁决起始词；后续窗口推进逐个出队，耗尽 BookComplete
        }
        val settings = FakeLearningSettingsRepository().apply {
            toggles = PlaybackToggles( // 每词单段（PRONUNCIATION）使时序确定
                pronunciation = true, spelling = false, meaningEn = false, meaningCn = false,
                example = false, exampleCn = false,
            )
        }
        val content = FakePlaybackContentRepository()
        val position = FakePlaybackPositionRepository()
        val recognizer = FakeSpeechCommandRecognizer()
        val orchestrator = PlaybackOrchestrator(
            engine = engine,
            contentRepository = content,
            positionRepository = position,
            settingsRepository = settings,
            audioPlayer = FakeAudioPlayer(),
            synthesizer = FakeSpeechSynthesizer(),
            recognizer = recognizer,
            commandParser = CommandParser(),
            scope = scope,
        )

        private fun snapshot(words: List<Long>): SessionSnapshot = SessionSnapshot(
            session = LearningSession(
                sessionId = SESSION_ID, wordBookId = BOOK_ID, status = SessionStatus.ACTIVE,
                groupSize = 10, startedAt = Instant.fromEpochMilliseconds(0),
            ),
            words = words.map {
                SessionWord(SESSION_ID, it, groupIndex = 0, orderInGroup = it.toInt() % 100, status = SessionWordStatus.PENDING)
            },
        )

        private companion object {
            const val SESSION_ID = 1L
            const val BOOK_ID = 9L
        }
    }

    /** 注册单段词内容（未注册 = L2 空词：specs 为空直通窗口）。 */
    private fun Harness.registerWord(wordId: Long, text: String) {
        content.contents[bookId to wordId] =
            com.vocabularybooster.domain.model.PlaybackContent(
                word = com.vocabularybooster.domain.model.Word(wordId, text, text),
                selectedDefinitions = emptyList(),
                examplesByDefinitionEntryId = emptyMap(),
            )
    }

    /** 起播并推进到窗口开启时刻（t=400：100ms 段 + 300ms guard）。 */
    private suspend fun TestScope.driveToWindow(orchestrator: PlaybackOrchestrator) {
        orchestrator.startSession(bookId)
        runCurrent()
        advanceTimeBy(100) // 唯一段播放完
        runCurrent()
        advanceTimeBy(300) // guard
        runCurrent()
    }

    // —— TC-AE-04：窗口命中「会了」→ markMastered(VOICE) 恰一次 + 立即换词；无重复监听 ——

    @Test
    fun masteredVoiceCommandMarksOnceAndAdvancesImmediately() = runTest {
        val h = Harness(backgroundScope, listOf(wordFirst, wordSecond))
        h.registerWord(wordFirst, "first")
        h.registerWord(wordSecond, "second")
        h.recognizer.isAvailable.value = true
        h.recognizer.script += RecognitionResult.Hit("会了")

        driveToWindow(h.orchestrator)
        runCurrent() // 窗口开 → listenOnce 立即命中 → 掌握 + 推进 → 词 2 起播

        assertEquals(listOf(Triple(sessionId, wordFirst, MasterySource.VOICE)), h.engine.markMasteredCalls)
        assertEquals(1, h.recognizer.listenCalls.size) // MASTERED 消费后不再监听（AUDIO §11 防重）
        val playing = assertIs<PlaybackState.Playing>(h.orchestrator.state.value)
        assertEquals(wordSecond, playing.wordRef.wordId) // 立即换词（FR-7 三步原子）
    }

    // —— TC-AE-05：噪音文本 → 不掌握、保持监听，窗口全程耗尽后才 advance ——

    @Test
    fun noiseTextKeepsWindowUntilTimeoutThenAdvancesWithoutMastery() = runTest {
        val h = Harness(backgroundScope, listOf(wordFirst, wordSecond))
        h.registerWord(wordFirst, "first")
        h.registerWord(wordSecond, "second")
        h.recognizer.isAvailable.value = true
        h.recognizer.script += RecognitionResult.Hit("你好")

        driveToWindow(h.orchestrator)
        runCurrent() // 窗口开 → 噪音命中（UNKNOWN，不误杀）→ 保持监听

        advanceTimeBy(1_000) // t=1400：窗口进行中
        runCurrent()
        val window = assertIs<PlaybackState.CommandWindow>(h.orchestrator.state.value)
        assertEquals(wordFirst, window.wordRef.wordId) // 未提前 advance
        assertTrue(window.listening)
        assertTrue(h.engine.markMasteredCalls.isEmpty())

        advanceTimeBy(3_000) // t=4400：窗口耗尽
        runCurrent()
        assertEquals(0, h.engine.markMasteredCalls.size) // progression（掌握）= 0
        val playing = assertIs<PlaybackState.Playing>(h.orchestrator.state.value)
        assertEquals(wordSecond, playing.wordRef.wordId) // 既有超时语义 advance
        assertEquals(2, h.recognizer.listenCalls.size) // 噪音后继续监听（第二次 = 静默至超时）
    }

    // —— §9 降级（前置）：识别器不可用 → 纯倒计时、零 listenOnce、按钮仍可用 ——

    @Test
    fun unavailableRecognizerDegradesToCountdownWithZeroListenCalls() = runTest {
        val h = Harness(backgroundScope, listOf(wordFirst, wordSecond))
        h.registerWord(wordFirst, "first")
        h.registerWord(wordSecond, "second")
        h.recognizer.isAvailable.value = false

        driveToWindow(h.orchestrator)
        advanceTimeBy(1_000) // t=1400
        runCurrent()
        val window = assertIs<PlaybackState.CommandWindow>(h.orchestrator.state.value)
        assertEquals(false, window.listening) // 降级 = P4 形态
        assertEquals(0, h.recognizer.listenCalls.size) // 前置门：零识别调用

        h.orchestrator.masterCurrentWord() // 降级窗口内按钮照常可用（§8）
        runCurrent()
        assertEquals(listOf(Triple(sessionId, wordFirst, MasterySource.BUTTON)), h.engine.markMasteredCalls)
        val playing = assertIs<PlaybackState.Playing>(h.orchestrator.state.value)
        assertEquals(wordSecond, playing.wordRef.wordId)
    }

    // —— D5 降级（中途）：listenOnce → Unavailable → 整窗倒计时、不掌握、不提前 advance ——

    @Test
    fun midWindowUnavailableDegradesRestOfWindowWithoutMastery() = runTest {
        val h = Harness(backgroundScope, listOf(wordFirst, wordSecond))
        h.registerWord(wordFirst, "first")
        h.registerWord(wordSecond, "second")
        h.recognizer.isAvailable.value = true
        h.recognizer.script += RecognitionResult.Unavailable

        driveToWindow(h.orchestrator)
        advanceTimeBy(1_000) // t=1400：Unavailable 已处理，剩余窗口降级倒计时
        runCurrent()
        val window = assertIs<PlaybackState.CommandWindow>(h.orchestrator.state.value)
        assertEquals(false, window.listening)
        assertTrue(h.engine.markMasteredCalls.isEmpty())
        assertEquals(1, h.engine.advanceCalls.size) // 仅起始 advance——错误绝不 advance

        advanceTimeBy(3_000) // 窗口耗尽 → 既有超时语义
        runCurrent()
        assertEquals(0, h.engine.markMasteredCalls.size)
        val playing = assertIs<PlaybackState.Playing>(h.orchestrator.state.value)
        assertEquals(wordSecond, playing.wordRef.wordId)
        assertEquals(2, h.engine.advanceCalls.size)
    }

    // —— TC-AE-06：Playing 态全程 recognizer 零调用（间谍断言；识别只存在于 CommandWindow）——

    @Test
    fun recognizerNeverCalledDuringPlayingPhase() = runTest {
        val h = Harness(backgroundScope, listOf(wordFirst, wordSecond))
        h.registerWord(wordFirst, "first")
        h.registerWord(wordSecond, "second")
        h.recognizer.isAvailable.value = true
        h.recognizer.script += RecognitionResult.Hit("会了") // 词 1 窗口命中

        h.orchestrator.startSession(bookId)
        runCurrent()
        advanceTimeBy(50) // 词 1 Playing 中
        runCurrent()
        assertIs<PlaybackState.Playing>(h.orchestrator.state.value)
        assertEquals(0, h.recognizer.listenCalls.size) // TTS/播放期间识别关闭（FR-12）

        advanceTimeBy(350) // t=400 窗口开 → 命中 → 词 2 Playing
        runCurrent()
        runCurrent()
        assertEquals(1, h.recognizer.listenCalls.size) // 唯一一次调用发生在窗口内
        val playing = assertIs<PlaybackState.Playing>(h.orchestrator.state.value)
        assertEquals(wordSecond, playing.wordRef.wordId)

        advanceTimeBy(50) // 词 2 Playing 中：再次零调用
        runCurrent()
        assertEquals(1, h.recognizer.listenCalls.size)
    }

    // —— D2/§14：按钮「会了」与语音同一执行路径（source=BUTTON）；执行即关识别 ——

    @Test
    fun buttonMasterSharesCommandPathAndStopsRecognition() = runTest {
        val h = Harness(backgroundScope, listOf(wordFirst, wordSecond))
        h.registerWord(wordFirst, "first")
        h.registerWord(wordSecond, "second")
        h.recognizer.isAvailable.value = true // 队列空：listenOnce 挂起（真实监听中）

        driveToWindow(h.orchestrator)
        advanceTimeBy(50) // 窗口监听中
        runCurrent()
        assertIs<PlaybackState.CommandWindow>(h.orchestrator.state.value)

        h.orchestrator.masterCurrentWord()
        runCurrent()
        assertEquals(listOf(Triple(sessionId, wordFirst, MasterySource.BUTTON)), h.engine.markMasteredCalls)
        assertEquals(1, h.recognizer.cancelledCount) // 按钮 = 关识别（listenOnce 被取消）
        val playing = assertIs<PlaybackState.Playing>(h.orchestrator.state.value)
        assertEquals(wordSecond, playing.wordRef.wordId)
    }

    // —— AUDIO §11 重复防护：按钮双击 / 语音后补按 → 同窗至多一次掌握 ——

    @Test
    fun doubleButtonTapMastersAtMostOnce() = runTest {
        val h = Harness(backgroundScope, listOf(wordFirst, wordSecond))
        h.registerWord(wordFirst, "first")
        h.registerWord(wordSecond, "second")
        h.recognizer.isAvailable.value = false

        driveToWindow(h.orchestrator)
        runCurrent()
        h.orchestrator.masterCurrentWord()
        runCurrent()
        h.orchestrator.masterCurrentWord() // 第二击：状态已 Playing → 幂等 no-op
        runCurrent()
        assertEquals(1, h.engine.markMasteredCalls.size)
        assertEquals(2, h.engine.advanceCalls.size) // 起始 + 一次推进
    }

    @Test
    fun buttonAfterVoiceConsumptionIsNoOp() = runTest {
        val h = Harness(backgroundScope, listOf(wordFirst, wordSecond))
        h.registerWord(wordFirst, "first")
        h.registerWord(wordSecond, "second")
        h.recognizer.isAvailable.value = true
        h.recognizer.script += RecognitionResult.Hit("会了")

        driveToWindow(h.orchestrator)
        runCurrent() // 语音已消费 → 词 2 Playing
        h.orchestrator.masterCurrentWord() // 迟到按钮：no-op
        runCurrent()
        assertEquals(listOf(Triple(sessionId, wordFirst, MasterySource.VOICE)), h.engine.markMasteredCalls)
        assertEquals(2, h.engine.advanceCalls.size) // 掌握推进仅一次
    }

    // —— D2：CommandWindow 之外按钮不可用（Playing 态 no-op）——

    @Test
    fun buttonOutsideCommandWindowIsNoOp() = runTest {
        val h = Harness(backgroundScope, listOf(wordFirst, wordSecond))
        h.registerWord(wordFirst, "first")
        h.registerWord(wordSecond, "second")
        h.recognizer.isAvailable.value = false

        h.orchestrator.startSession(bookId)
        runCurrent()
        assertIs<PlaybackState.Playing>(h.orchestrator.state.value)
        h.orchestrator.masterCurrentWord()
        runCurrent()
        assertTrue(h.engine.markMasteredCalls.isEmpty())
        assertEquals(1, h.engine.advanceCalls.size) // 仅起始裁决
    }

    // —— §3 窗口行：暂停关识别（窗口位）；恢复重开整窗（新 listenOnce，全窗口预算）——

    @Test
    fun pauseDuringListeningClosesRecognitionAndResumeReopensFullWindow() = runTest {
        val h = Harness(backgroundScope, listOf(wordFirst, wordSecond))
        h.registerWord(wordFirst, "first")
        h.registerWord(wordSecond, "second")
        h.recognizer.isAvailable.value = true

        driveToWindow(h.orchestrator)
        advanceTimeBy(50) // 监听中
        runCurrent()
        h.orchestrator.pause()
        runCurrent()
        val paused = assertIs<PlaybackState.Paused>(h.orchestrator.state.value)
        assertTrue(paused.atCommandWindow)
        assertEquals(1, h.recognizer.cancelledCount) // pause = 关识别
        assertTrue(h.engine.markMasteredCalls.isEmpty())

        h.orchestrator.resume()
        runCurrent()
        val window = assertIs<PlaybackState.CommandWindow>(h.orchestrator.state.value)
        assertEquals(2, h.recognizer.listenCalls.size) // 重开整窗 = 新识别会话
        assertEquals(4_000L, h.recognizer.listenCalls.last()) // 全窗口预算（不续旧窗剩余）
        assertTrue(window.listening)

        h.orchestrator.exit()
        runCurrent()
        assertIs<PlaybackState.Stopped>(h.orchestrator.state.value)
        assertEquals(1, h.engine.exitCalls.size)
        assertTrue(h.engine.markMasteredCalls.isEmpty()) // 全程无掌握
    }

    // —— §3 exit：窗口监听中退出 → 关识别 + 既有退出裁决，零掌握 ——

    @Test
    fun exitDuringListeningWindowStopsRecognitionWithoutMastery() = runTest {
        val h = Harness(backgroundScope, listOf(wordFirst, wordSecond))
        h.registerWord(wordFirst, "first")
        h.registerWord(wordSecond, "second")
        h.recognizer.isAvailable.value = true

        driveToWindow(h.orchestrator)
        advanceTimeBy(50)
        runCurrent()
        h.orchestrator.exit()
        runCurrent()
        assertIs<PlaybackState.Stopped>(h.orchestrator.state.value)
        assertEquals(1, h.recognizer.cancelledCount)
        assertTrue(h.engine.markMasteredCalls.isEmpty())
        assertEquals(1, h.engine.exitCalls.size)
    }

    // —— L2 边界：空段词直通窗口；窗口内显式「会了」= 正常命令（非空段自动掌握）——

    @Test
    fun emptySegmentWordWindowAcceptsExplicitVoiceCommand() = runTest {
        val h = Harness(backgroundScope, listOf(wordFirst, wordSecond))
        h.registerWord(wordSecond, "second") // 词 1 无内容 → specs 空 → L2 直通窗口
        h.recognizer.isAvailable.value = true
        h.recognizer.script += RecognitionResult.Hit("会了")

        h.orchestrator.startSession(bookId)
        runCurrent() // 空词：无段可播 → 窗口即开（skipGuard）
        runCurrent() // 命中 → 掌握 + 推进
        assertEquals(listOf(Triple(sessionId, wordFirst, MasterySource.VOICE)), h.engine.markMasteredCalls)
        val playing = assertIs<PlaybackState.Playing>(h.orchestrator.state.value)
        assertEquals(wordSecond, playing.wordRef.wordId)
    }

    // —— FR-8/分支 C：最后一词「会了」→ BookComplete → 先停端口清位置再 Completed ——

    @Test
    fun masteredLastWordCompletesBookAndClearsPosition() = runTest {
        val h = Harness(backgroundScope, listOf(wordFirst))
        h.registerWord(wordFirst, "first")
        h.recognizer.isAvailable.value = true
        h.recognizer.script += RecognitionResult.Hit("会了")

        driveToWindow(h.orchestrator)
        runCurrent() // 命中 → markMastered → advance → BookComplete → Completed
        assertIs<PlaybackState.Completed>(h.orchestrator.state.value)
        assertEquals(listOf(Triple(sessionId, wordFirst, MasterySource.VOICE)), h.engine.markMasteredCalls)
        assertTrue(h.position.clearCount >= 1) // §5：会话结束清 playback.position
    }
}
