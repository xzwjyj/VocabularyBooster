package com.vocabularybooster.speech

import com.vocabularybooster.domain.model.Lang
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertSame
import kotlin.test.assertFailsWith

/**
 * TC-AE-30（重写，FR-23 v2）：语言路由与降级。
 * 路由表（EN_*→神经 / ZH→系统）、readiness 路由、speak 异常降级粘滞、
 * 取消红线（重抛不降级）、stop 双转发、availableVoices 合并、readiness 投影。
 */
class LangRoutedSpeechSynthesizerTest {

    private fun request(lang: Lang, id: String = "u-$lang") =
        SpeakRequest(utteranceId = id, text = "text", lang = lang, rate = 1.0f, pitch = 1.0f)

    @Test
    fun englishRoutesToNeuralAndChineseToSystem() = runTest {
        val neural = FakeSynthesizer()
        val system = FakeSynthesizer()
        val router = LangRoutedSpeechSynthesizer(neural, system)

        router.speak(request(Lang.EN_US))
        router.speak(request(Lang.EN_GB))
        router.speak(request(Lang.ZH_CN))

        assertEquals(listOf(Lang.EN_US, Lang.EN_GB), neural.spoken.map { it.lang })
        assertEquals(listOf(Lang.ZH_CN), system.spoken.map { it.lang })
    }

    @Test
    fun neuralNotReadyRoutesEnglishToSystem() = runTest {
        val neural = FakeSynthesizer(initialReadiness = Readiness.INITIALIZING)
        val system = FakeSynthesizer()
        val router = LangRoutedSpeechSynthesizer(neural, system)

        router.speak(request(Lang.EN_US))

        assertTrue(neural.spoken.isEmpty(), "INITIALIZING 期间 EN 段应先走系统（预热完成后自动切换）")
        assertEquals(listOf(Lang.EN_US), system.spoken.map { it.lang })
    }

    @Test
    fun neuralUnavailableRoutesEnglishToSystem() = runTest {
        val neural = FakeSynthesizer(initialReadiness = Readiness.UNAVAILABLE)
        val system = FakeSynthesizer()
        val router = LangRoutedSpeechSynthesizer(neural, system)

        router.speak(request(Lang.EN_GB))

        assertTrue(neural.spoken.isEmpty())
        assertEquals(listOf(Lang.EN_GB), system.spoken.map { it.lang })
    }

    @Test
    fun readyNeuralMeansZeroSystemCallsForEnglish() = runTest {
        val neural = FakeSynthesizer()
        val system = FakeSynthesizer()
        val router = LangRoutedSpeechSynthesizer(neural, system)

        router.speak(request(Lang.EN_US, id = "seg-1"))

        assertTrue(system.spoken.isEmpty())
        assertEquals("seg-1", neural.spoken.single().utteranceId)
    }

    @Test
    fun neuralSpeakFailureFallsBackSameSegmentAndDegradesSticky() = runTest {
        val neural = FakeSynthesizer().apply { error = IllegalStateException("model gone") }
        val system = FakeSynthesizer()
        val router = LangRoutedSpeechSynthesizer(neural, system)

        val first = router.speak(request(Lang.EN_US, id = "seg-1"))
        val second = router.speak(request(Lang.EN_GB, id = "seg-2"))

        assertEquals("seg-1", first.utteranceId)
        assertEquals("seg-2", second.utteranceId)
        assertEquals(1, neural.spoken.size, "降级后神经零调用（进程内粘滞）")
        assertEquals(listOf(Lang.EN_US, Lang.EN_GB), system.spoken.map { it.lang }, "当场同段回退")
    }

    @Test
    fun cancellationRethrowsWithoutDegrading() = runTest {
        val neural = FakeSynthesizer().apply { cancellation = CancellationException("pause") }
        val system = FakeSynthesizer()
        val router = LangRoutedSpeechSynthesizer(neural, system)

        assertFailsWith<CancellationException> { router.speak(request(Lang.EN_US)) }

        // 取消不降级：神经恢复后下一 EN 段仍走神经
        neural.cancellation = null
        router.speak(request(Lang.EN_US, id = "seg-2"))
        assertEquals(2, neural.spoken.size)
        assertTrue(system.spoken.isEmpty())
    }

    @Test
    fun stopForwardsToBoth() {
        val neural = FakeSynthesizer()
        val system = FakeSynthesizer()
        val router = LangRoutedSpeechSynthesizer(neural, system)

        router.stop()

        assertEquals(1, neural.stopCalls)
        assertEquals(1, system.stopCalls)
    }

    @Test
    fun availableVoicesFollowRoutingAndChineseAlwaysSystem() = runTest {
        val neuralVoice = TtsVoice(id = "piper-en_GB-alan-medium", displayName = "alan（神经）")
        val systemVoice = TtsVoice(id = "vendor-en", displayName = "vendor")
        val neural = FakeSynthesizer().apply { voices = listOf(neuralVoice) }
        val system = FakeSynthesizer().apply { voices = listOf(systemVoice) }
        val router = LangRoutedSpeechSynthesizer(neural, system)

        assertSame(neuralVoice, router.availableVoices(Lang.EN_GB).single())
        assertSame(systemVoice, router.availableVoices(Lang.ZH_CN).single())

        neural.error = IllegalStateException("boom")
        router.speak(request(Lang.EN_US)) // 触发粘滞降级
        assertSame(systemVoice, router.availableVoices(Lang.EN_US).single(), "降级后 EN 音色走系统")
    }

    @Test
    fun readinessProjectsSystemEngine() {
        val systemState = MutableStateFlow(Readiness.INITIALIZING)
        val system = FakeSynthesizer().apply { overrideReadiness = systemState }
        val router = LangRoutedSpeechSynthesizer(FakeSynthesizer(), system)

        assertSame(Readiness.INITIALIZING, router.readiness.value)
        systemState.value = Readiness.READY
        assertSame(Readiness.READY, router.readiness.value)
    }

    private class FakeSynthesizer(
        initialReadiness: Readiness = Readiness.READY,
    ) : SpeechSynthesizer {
        private val state = MutableStateFlow(initialReadiness)
        var overrideReadiness: MutableStateFlow<Readiness>? = null
        override val readiness: StateFlow<Readiness>
            get() = overrideReadiness ?: state

        val spoken = mutableListOf<SpeakRequest>()
        var stopCalls = 0
        var error: Exception? = null
        var cancellation: CancellationException? = null
        var voices: List<TtsVoice> = emptyList()

        override suspend fun speak(request: SpeakRequest): SegmentResult {
            spoken += request
            cancellation?.let { throw it }
            error?.let { throw it }
            return SegmentResult(utteranceId = request.utteranceId, completed = true)
        }

        override fun stop() {
            stopCalls++
        }

        override fun availableVoices(lang: Lang): List<TtsVoice> = voices
    }
}
