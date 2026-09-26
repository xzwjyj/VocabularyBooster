package com.vocabularybooster.app.ui

import com.vocabularybooster.domain.model.Lang
import com.vocabularybooster.speech.Readiness
import com.vocabularybooster.speech.SegmentResult
import com.vocabularybooster.speech.SpeakRequest
import com.vocabularybooster.speech.SpeechSynthesizer
import com.vocabularybooster.speech.TtsVoice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * WordPronouncer 单测（FR-22 扩展：US/UK 音标旁朗读小按钮）：
 * 一次性口音试听的请求形态 / 设置语速随行 / 重听取消上一次 / 空白词防误触 / 失败不崩溃。
 * 用例编号 TC-IPAPRON-01…04（TEST_PLAN）；取消重抛红线（不吞 CancellationException）用 03 锁定。
 */
class WordPronouncerTest {

    /** 试听 Fake：speak 记录请求、可设门挂起；协程取消被观测（防「吞取消」回归——03 的断言依据）。 */
    private class FakePreviewSynth(
        var gate: CompletableDeferred<Unit>? = null,
        var throwOnSpeak: Boolean = false,
    ) : SpeechSynthesizer {
        override val readiness = MutableStateFlow(Readiness.READY)
        val requests = mutableListOf<SpeakRequest>()
        val cancelled = mutableListOf<SpeakRequest>()
        var failures = 0
            private set
        var stopCount = 0
            private set

        override suspend fun speak(request: SpeakRequest): SegmentResult {
            requests += request
            if (throwOnSpeak) {
                failures++
                error("preview unavailable (fake)")
            }
            try {
                gate?.await()
            } catch (e: CancellationException) {
                cancelled += request
                throw e
            }
            return SegmentResult(request.utteranceId, completed = true)
        }

        override fun stop() {
            stopCount++
        }

        override fun availableVoices(lang: Lang): List<TtsVoice> = emptyList()
    }

    /** 与 LearningSessionViewModelTest 同约束：backgroundScope 任务不被 advanceUntilIdle 执行——专用 scope 共享调度器。 */
    private fun TestScope.pronouncerWith(synth: FakePreviewSynth): Pair<WordPronouncer, CoroutineScope> {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        return WordPronouncer(synth, FakeLearningSettingsRepository(), scope) to scope
    }

    // —— TC-IPAPRON-01：请求形态（词文本 / 点击口音 / 设置语速音调随行 / 唯一 utteranceId）——

    @Test
    fun speakCarriesWordAccentAndConfiguredRatePitch() = runTest {
        val synth = FakePreviewSynth()
        val settings = FakeLearningSettingsRepository(ttsRate = 0.8f, ttsPitch = 1.2f)
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val pronouncer = WordPronouncer(synth, settings, scope)

        pronouncer.pronounce("boost", Lang.EN_GB)
        advanceUntilIdle() // 先跑完第一次——重听会取消上一次，两次连发 boost 会被 legion 的取消吞掉
        pronouncer.pronounce("legion", Lang.EN_US)
        advanceUntilIdle()
        scope.cancel()

        assertEquals(listOf("boost", "legion"), synth.requests.map { it.text })
        assertEquals(listOf(Lang.EN_GB, Lang.EN_US), synth.requests.map { it.lang })
        assertEquals(listOf(0.8f, 0.8f), synth.requests.map { it.rate }) // 语速/音调沿用用户设置
        assertEquals(listOf(1.2f, 1.2f), synth.requests.map { it.pitch })
        assertEquals(2, synth.requests.map { it.utteranceId }.distinct().size) // utteranceId 唯一（系统 TTS 回调匹配依赖）
    }

    // —— TC-IPAPRON-02：空白词防误触 ——

    @Test
    fun blankTextIsIgnored() = runTest {
        val synth = FakePreviewSynth()
        val (pronouncer, scope) = pronouncerWith(synth)

        pronouncer.pronounce("   ", Lang.EN_US)
        advanceUntilIdle()
        scope.cancel()

        assertEquals(0, synth.requests.size)
    }

    // —— TC-IPAPRON-03：重听取消上一次（取消重抛不被吞）——

    @Test
    fun retapCancelsPreviousPreview() = runTest {
        val gate = CompletableDeferred<Unit>()
        val synth = FakePreviewSynth(gate = gate)
        val (pronouncer, scope) = pronouncerWith(synth)

        pronouncer.pronounce("boost", Lang.EN_US)
        advanceUntilIdle() // 第一次试听挂起在门上（播放中）
        pronouncer.pronounce("legion", Lang.EN_GB) // 重听 → 取消 boost
        advanceUntilIdle()
        gate.complete(Unit) // 放行第二次（第一次已因取消结束）
        advanceUntilIdle()
        scope.cancel()

        assertEquals(listOf("boost", "legion"), synth.requests.map { it.text })
        assertEquals(listOf("boost"), synth.cancelled.map { it.text }) // 取消传播到 speak 内（重抛红线）
    }

    // —— TC-IPAPRON-04：speak 失败仅记日志不崩溃 ——

    @Test
    fun speakFailureIsSwallowedWithoutCrash() = runTest {
        val synth = FakePreviewSynth(throwOnSpeak = true)
        val (pronouncer, scope) = pronouncerWith(synth)

        pronouncer.pronounce("boost", Lang.EN_US)
        advanceUntilIdle()
        scope.cancel()

        assertEquals(1, synth.failures) // 异常路径确实走到且协程正常结束（runTest 无未捕获异常）
        assertEquals(listOf("boost"), synth.requests.map { it.text })
    }
}
