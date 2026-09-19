package com.vocabularybooster.app.audio

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vocabularybooster.domain.model.Lang
import com.vocabularybooster.domain.repository.LearningSettingsRepository
import com.vocabularybooster.platform.TtsSpeechSynthesizer
import org.koin.core.context.GlobalContext
import com.vocabularybooster.speech.Readiness
import com.vocabularybooster.speech.SpeakRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * TtsSpeechSynthesizer 平台契约 instrumented 测试（Phase 4 Step 3，AUDIO_ENGINE_SPEC §8）。
 * 环境诚实原则（不伪造 green test）：
 * - 引擎缺席（readiness=UNAVAILABLE）→ Assume 跳过 speak 用例（转手动冒烟），契约"失败抛异常"仍验证；
 * - zh-CN 语音数据缺席 → 断言"抛异常、不静默改播英语"（这本身就是被测契约）。
 * 完成断言全部由 UtteranceProgressListener 回调驱动（无固定 sleep）。
 */
@RunWith(AndroidJUnit4::class)
class TtsSpeechSynthesizerInstrumentedTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun initialization_reachesTerminalState() = runBlocking {
        val tts = withContext(Dispatchers.Main.immediate) { TtsSpeechSynthesizer(context, GlobalContext.get().get<LearningSettingsRepository>()) }
        val terminal = withTimeout(20_000) { tts.readiness.first { it != Readiness.INITIALIZING } }
        assertTrue(terminal == Readiness.READY || terminal == Readiness.UNAVAILABLE)
        withContext(Dispatchers.Main.immediate) { tts.release() }
    }

    @Test
    fun initializationFailure_contractIsException() = runBlocking {
        val tts = withContext(Dispatchers.Main.immediate) { TtsSpeechSynthesizer(context, GlobalContext.get().get<LearningSettingsRepository>()) }
        val terminal = withTimeout(20_000) { tts.readiness.first { it != Readiness.INITIALIZING } }
        if (terminal == Readiness.UNAVAILABLE) {
            try {
                withTimeout(5_000) { tts.speak(englishRequest("unavailable-init")) }
                error("UNAVAILABLE 下 speak 应抛异常")
            } catch (expected: IllegalStateException) {
                assertTrue(expected.message.orEmpty().contains("不可用"))
            }
        } else {
            // 引擎可用的环境无法稳定伪造初始化失败——该路径留给手动矩阵，不伪造
            assumeTrue("设备 TTS 引擎可用：初始化失败路径转手动验证", false)
        }
        withContext(Dispatchers.Main.immediate) { tts.release() }
    }

    @Test
    fun englishSpeak_completesViaUtteranceCallback() = runBlocking {
        withContext(Dispatchers.Main.immediate) {
            val tts = TtsSpeechSynthesizer(context, GlobalContext.get().get<LearningSettingsRepository>())
            try {
                val terminal = withTimeout(20_000) { tts.readiness.first { it != Readiness.INITIALIZING } }
                assumeTrue("设备无 TTS 引擎：英语朗读转手动冒烟", terminal == Readiness.READY)
                val result = withTimeout(20_000) { tts.speak(englishRequest("smoke-en-1")) }
                assertTrue(result.completed)
                assertEquals("smoke-en-1", result.utteranceId)
            } finally {
                tts.release()
            }
        }
    }

    @Test
    fun chineseSpeak_languageContract() = runBlocking {
        withContext(Dispatchers.Main.immediate) {
            val tts = TtsSpeechSynthesizer(context, GlobalContext.get().get<LearningSettingsRepository>())
            try {
                val terminal = withTimeout(20_000) { tts.readiness.first { it != Readiness.INITIALIZING } }
                assumeTrue("设备无 TTS 引擎：中文朗读转手动冒烟", terminal == Readiness.READY)
                try {
                    val result = withTimeout(25_000) {
                        tts.speak(
                            SpeakRequest(
                                utteranceId = "smoke-zh-1",
                                text = "你好，世界。",
                                lang = Lang.ZH_CN,
                                rate = 1.0f,
                                pitch = 1.0f,
                            )
                        )
                    }
                    println("chineseSpeak: zh-CN 语音可用，utterance 正常完成")
                    assertTrue("zh-CN 语音可用：utterance 应完成", result.completed)
                } catch (expected: IllegalStateException) {
                    // 契约：zh-CN 不可达 → 显式失败抛异常，绝不静默改播其他语言。
                    // 两种合法形态：setLanguage 拒绝（语言不可用）/ 引擎无 zh 语音数据、合成 onError（本模拟器实测）。
                    val msg = expected.message.orEmpty()
                    println("chineseSpeak: zh-CN 大声失败——$msg")
                    assertTrue(
                        "zh-CN 失败应为显式异常（语言不可用/合成失败），实际：$msg",
                        msg.contains("语言不可用") || msg.contains("失败"),
                    )
                }
            } finally {
                tts.release()
            }
        }
    }

    @Test
    fun rateAndPitch_appliedWithoutFailure() = runBlocking {
        withContext(Dispatchers.Main.immediate) {
            val tts = TtsSpeechSynthesizer(context, GlobalContext.get().get<LearningSettingsRepository>())
            try {
                val terminal = withTimeout(20_000) { tts.readiness.first { it != Readiness.INITIALIZING } }
                assumeTrue("设备无 TTS 引擎：rate/pitch 用例转手动冒烟", terminal == Readiness.READY)
                val result = withTimeout(20_000) {
                    tts.speak(englishRequest("smoke-rate-1", rate = 1.5f, pitch = 1.2f))
                }
                assertTrue(result.completed) // rate/pitch 被引擎接受并正常完成
            } finally {
                tts.release()
            }
        }
    }

    @Test
    fun stop_actuallyStopsUtterance() = runBlocking {
        withContext(Dispatchers.Main.immediate) {
            val tts = TtsSpeechSynthesizer(context, GlobalContext.get().get<LearningSettingsRepository>())
            try {
                val terminal = withTimeout(20_000) { tts.readiness.first { it != Readiness.INITIALIZING } }
                assumeTrue("设备无 TTS 引擎：stop 用例转手动冒烟", terminal == Readiness.READY)
                val speaking = async {
                    tts.speak(
                        SpeakRequest(
                            utteranceId = "smoke-stop-1",
                            text = "This is a long sentence that takes several seconds to speak, " +
                                "so that stop can be exercised while the utterance is still running.",
                            lang = Lang.EN_US,
                            rate = 0.6f, // 放慢拉长，确保 stop 落在朗读中
                            pitch = 1.0f,
                        )
                    )
                }
                withTimeout(10_000) { while (!tts.isSpeaking) delay(50) }
                tts.stop()
                val result = withTimeout(5_000) { speaking.await() }
                assertFalse("stop 后 utterance 不得标记 completed", result.completed)
                withTimeout(3_000) { while (tts.isSpeaking) delay(50) } // 引擎层面真停
            } finally {
                tts.release()
            }
        }
    }

    @Test
    fun release_thenSpeak_throws() = runBlocking {
        withContext(Dispatchers.Main.immediate) {
            val tts = TtsSpeechSynthesizer(context, GlobalContext.get().get<LearningSettingsRepository>())
            tts.release()
            try {
                withTimeout(5_000) { tts.speak(englishRequest("released")) }
                error("release 后 speak 应抛异常")
            } catch (expected: IllegalStateException) {
                assertTrue(expected.message.orEmpty().contains("release"))
            }
        }
    }

    private fun englishRequest(id: String, rate: Float = 1.0f, pitch: Float = 1.0f): SpeakRequest =
        SpeakRequest(
            utteranceId = id,
            text = "The marketing campaign boosted sales.",
            lang = Lang.EN_US,
            rate = rate,
            pitch = pitch,
        )
}
