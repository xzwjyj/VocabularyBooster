package com.vocabularybooster.app.speech

import com.vocabularybooster.speech.RecognitionResult
import com.vocabularybooster.speech.SpeechCommandRecognizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BackgroundGatedSpeechCommandRecognizer 后台语音门禁 JVM 单测（TC-BG-01，FR-25 / SCR-BGPLAYBACK，
 * AUDIO_ENGINE_SPEC §8「熄屏/后台续播契约」）：前台 true → `isAvailable` 随 delegate / `listenOnce`
 * 透传 delegate；后台 false → `isAvailable` 恒 false / `listenOnce` 短路 `Unavailable` 零调用 delegate
 * （FR-12「绝无后台监听」红线硬守）。`isAvailable` 投影随与门（combine + Eagerly）即时传播。
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class) // UnconfinedTestDispatcher：isAvailable 投影即时传播断言所需
class BackgroundGatedSpeechCommandRecognizerTest {

    /** 记录型 Fake delegate：可编程终局结果 + 可变 isAvailable + 记录每次 listenOnce 的窗口预算。 */
    private class FakeRecognizer(
        private val nextResult: RecognitionResult,
    ) : SpeechCommandRecognizer {
        val windowBudgets = mutableListOf<Long>()
        private val _isAvailable = MutableStateFlow(true)
        override val isAvailable: StateFlow<Boolean> = _isAvailable

        override suspend fun listenOnce(windowMs: Long): RecognitionResult {
            windowBudgets += windowMs
            return nextResult
        }

        fun setAvailable(value: Boolean) {
            _isAvailable.value = value
        }
    }

    @Test // 前台 + delegate 可用 → isAvailable=true；listenOnce 透传 delegate 结果
    fun foregroundAndDelegateAvailable_passesThrough_listensOnce() = runTest {
        val delegate = FakeRecognizer(RecognitionResult.Hit("会了"))
        val appForeground = MutableStateFlow(true)
        val proxy = BackgroundGatedSpeechCommandRecognizer(delegate, appForeground, scope())

        assertTrue(proxy.isAvailable.value)
        val result = proxy.listenOnce(WINDOW_MS)

        assertEquals(RecognitionResult.Hit("会了"), result)
        assertEquals(listOf(WINDOW_MS), delegate.windowBudgets) // 透传：恰一次调用
    }

    @Test // 后台 → isAvailable 恒 false（即便 delegate 可用）；listenOnce 短路 Unavailable 零调用 delegate
    fun background_shortCircuitsUnavailable_delegateNeverCalled() = runTest {
        val delegate = FakeRecognizer(RecognitionResult.Hit("会了")) // 命中结果被短路忽略
        val appForeground = MutableStateFlow(false)
        val proxy = BackgroundGatedSpeechCommandRecognizer(delegate, appForeground, scope())

        assertFalse(proxy.isAvailable.value) // 后台：与门 false
        val result = proxy.listenOnce(WINDOW_MS)

        assertEquals(RecognitionResult.Unavailable, result)
        assertTrue(delegate.windowBudgets.isEmpty()) // 零调用 delegate（FR-12 后台持麦红线）
    }

    @Test // delegate 不可用（前台）→ isAvailable=false（与门）
    fun delegateUnavailable_foreground_gatesIsAvailableFalse() = runTest {
        val delegate = FakeRecognizer(RecognitionResult.Timeout)
        delegate.setAvailable(false)
        val appForeground = MutableStateFlow(true)
        val proxy = BackgroundGatedSpeechCommandRecognizer(delegate, appForeground, scope())

        assertFalse(proxy.isAvailable.value) // delegate=false ∧ 前台=true → false
    }

    @Test // appForeground 翻转 → isAvailable 随与门即时传播（combine + Eagerly）
    fun isAvailableProjection_followsAndGateAsForegroundToggles() = runTest {
        val delegate = FakeRecognizer(RecognitionResult.Hit("会了"))
        val appForeground = MutableStateFlow(true)
        val proxy = BackgroundGatedSpeechCommandRecognizer(delegate, appForeground, scope())

        assertTrue(proxy.isAvailable.value) // 前台 + delegate 可用

        appForeground.value = false // 切后台
        assertFalse(proxy.isAvailable.value) // 与门翻 false

        appForeground.value = true // 回前台
        assertTrue(proxy.isAvailable.value) // 与门回 true
    }

    @Test // 后台 listenOnce 短路返回 Unavailable——即便 delegate 编程为 Timeout 也不调用
    fun backgroundShortCircuit_overridesAnyDelegateResult() = runTest {
        val delegate = FakeRecognizer(RecognitionResult.Timeout)
        val appForeground = MutableStateFlow(false)
        val proxy = BackgroundGatedSpeechCommandRecognizer(delegate, appForeground, scope())

        val result = proxy.listenOnce(WINDOW_MS)

        assertEquals(RecognitionResult.Unavailable, result) // 短路 Unavailable，非 delegate 的 Timeout
        assertTrue(delegate.windowBudgets.isEmpty())
    }

    /** 统一装配：Unconfined 测试作用域（isAvailable 投影即时传播）。 */
    private fun TestScope.scope(): CoroutineScope =
        CoroutineScope(UnconfinedTestDispatcher(testScheduler))

    private companion object {
        const val WINDOW_MS: Long = 4_000L
    }
}
