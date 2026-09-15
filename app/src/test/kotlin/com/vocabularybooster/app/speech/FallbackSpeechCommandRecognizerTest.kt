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
 * FallbackSpeechCommandRecognizer 引擎回退代理 JVM 单测（TC-AE-28，裁决 E3，AUDIO_ENGINE_SPEC §8
 * 「引擎选择与回退规则」）：主引擎可用性级硬失败 → 同窗回退备引擎（剩余预算）/ 进程内降级粘滞 /
 * 健康主引擎备引擎零调用（GMS 机零变化）/ 并发守卫 Unavailable 不回退 / 剩余预算耗尽 → Timeout /
 * 双引擎皆硬失败 → Unavailable / isAvailable 投影降级前后随主备引擎。
 * 真实坏服务机型（vivo 蓝心 Copilot）的实际回退 = M1/M2 手动矩阵（不伪造）。
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class) // UnconfinedTestDispatcher：isAvailable 投影即时传播断言所需
class FallbackSpeechCommandRecognizerTest {

    /** 记录型 Fake 引擎：可编程终局结果 + 硬失败拉低 isAvailable + 记录每次 listenOnce 的窗口预算。 */
    private class FakeRecognizer(
        private val nextResult: RecognitionResult,
        private val pullLowOnUnavailable: Boolean = true,
    ) : SpeechCommandRecognizer {
        val windowBudgets = mutableListOf<Long>()
        private val _isAvailable = MutableStateFlow(true)
        override val isAvailable: StateFlow<Boolean> = _isAvailable
        var onListen: () -> Unit = {} // 测试钩子：推进虚拟时钟等

        override suspend fun listenOnce(windowMs: Long): RecognitionResult {
            windowBudgets += windowMs
            onListen()
            if (nextResult == RecognitionResult.Unavailable && pullLowOnUnavailable) {
                _isAvailable.value = false // D5 可用性级硬失败信号
            }
            return nextResult
        }

        fun setAvailable(value: Boolean) {
            _isAvailable.value = value
        }
    }

    @Test // 主引擎硬失败 → 同窗内以剩余预算改用备引擎，返回备引擎结果
    fun primaryHardFail_fallsBackWithinSameWindowWithRemainingBudget() = runTest {
        val primary = FakeRecognizer(RecognitionResult.Unavailable) // 拉低 isAvailable
        val secondary = FakeRecognizer(RecognitionResult.Hit("会了"))
        val proxy = newProxy(primary, secondary) { nowMs ->
            primary.onListen = { nowMs.value = PRIMARY_FAIL_ELAPSED_MS } // 硬失败耗时（vivo 实测 46ms）
        }

        val result = proxy.listenOnce(WINDOW_MS)

        assertTrue(result is RecognitionResult.Hit)
        assertEquals("会了", (result as RecognitionResult.Hit).text)
        assertEquals(listOf(WINDOW_MS), primary.windowBudgets)
        assertEquals(listOf(WINDOW_MS - PRIMARY_FAIL_ELAPSED_MS), secondary.windowBudgets)
    }

    @Test // 降级进程内粘滞：后续窗口主引擎零调用、直连备引擎（满预算）
    fun demotionIsSticky_subsequentWindowsBypassPrimary() = runTest {
        val primary = FakeRecognizer(RecognitionResult.Unavailable)
        val secondary = FakeRecognizer(RecognitionResult.Hit("会了"))
        val proxy = newProxy(primary, secondary) { nowMs ->
            primary.onListen = { nowMs.value = PRIMARY_FAIL_ELAPSED_MS }
        }

        proxy.listenOnce(WINDOW_MS) // 触发降级
        val second = proxy.listenOnce(WINDOW_MS)

        assertTrue(second is RecognitionResult.Hit)
        assertEquals(1, primary.windowBudgets.size) // 第二窗主引擎零调用
        assertEquals(
            listOf(WINDOW_MS - PRIMARY_FAIL_ELAPSED_MS, WINDOW_MS),
            secondary.windowBudgets, // 第二窗直连备引擎，满预算
        )
    }

    @Test // 健康主引擎（Hit）→ 备引擎零调用（GMS 机零变化）
    fun primaryHit_passesThrough_secondaryNeverCalled() = runTest {
        val primary = FakeRecognizer(RecognitionResult.Hit("会了"))
        val secondary = FakeRecognizer(RecognitionResult.Timeout)
        val proxy = newProxy(primary, secondary) { }

        val result = proxy.listenOnce(WINDOW_MS)

        assertEquals(RecognitionResult.Hit("会了"), result)
        assertTrue(secondary.windowBudgets.isEmpty())
    }

    @Test // 健康主引擎（Timeout）→ 备引擎零调用
    fun primaryTimeout_passesThrough_secondaryNeverCalled() = runTest {
        val primary = FakeRecognizer(RecognitionResult.Timeout)
        val secondary = FakeRecognizer(RecognitionResult.Hit("会了"))
        val proxy = newProxy(primary, secondary) { }

        val result = proxy.listenOnce(WINDOW_MS)

        assertEquals(RecognitionResult.Timeout, result)
        assertTrue(secondary.windowBudgets.isEmpty())
    }

    @Test // 并发守卫触发的 Unavailable（不拉低 isAvailable）→ 如实透传、不回退、不降级
    fun concurrencyGuardUnavailable_doesNotFallBackOrDemote() = runTest {
        val primary = FakeRecognizer(RecognitionResult.Unavailable, pullLowOnUnavailable = false)
        val secondary = FakeRecognizer(RecognitionResult.Hit("会了"))
        val proxy = newProxy(primary, secondary) { }

        val first = proxy.listenOnce(WINDOW_MS)

        assertEquals(RecognitionResult.Unavailable, first)
        assertTrue(secondary.windowBudgets.isEmpty())
        proxy.listenOnce(WINDOW_MS) // 未降级：第二窗主引擎照常被调用
        assertEquals(2, primary.windowBudgets.size)
    }

    @Test // 硬失败时剩余预算 ≤ 0 → Timeout（不调备引擎、零命令语义）；降级照常粘滞
    fun exhaustedBudget_returnsTimeoutWithoutSecondary_stillDemotes() = runTest {
        val primary = FakeRecognizer(RecognitionResult.Unavailable)
        val secondary = FakeRecognizer(RecognitionResult.Hit("会了"))
        val proxy = newProxy(primary, secondary) { nowMs ->
            primary.onListen = { nowMs.value = WINDOW_MS } // 主引擎耗尽全部预算才硬失败
        }

        val result = proxy.listenOnce(WINDOW_MS)

        assertEquals(RecognitionResult.Timeout, result)
        assertTrue(secondary.windowBudgets.isEmpty()) // 本窗不调备引擎
        proxy.listenOnce(WINDOW_MS) // 但降级已粘滞：下一窗直连备引擎
        assertEquals(listOf(WINDOW_MS), secondary.windowBudgets)
    }

    @Test // 双引擎皆硬失败 → Unavailable，且代理 isAvailable 投影为 false（§9 双引擎皆不可用）
    fun bothEnginesHardFail_returnsUnavailableAndProjectsUnavailable() = runTest {
        val primary = FakeRecognizer(RecognitionResult.Unavailable)
        val secondary = FakeRecognizer(RecognitionResult.Unavailable)
        val proxy = newProxy(primary, secondary) { }

        val result = proxy.listenOnce(WINDOW_MS)

        assertEquals(RecognitionResult.Unavailable, result)
        assertFalse(proxy.isAvailable.value) // 降级后随备引擎（已拉低）
    }

    @Test // isAvailable 投影：降级前随主引擎、降级后随备引擎
    fun isAvailableProjection_followsPrimaryBeforeDemotion_secondaryAfter() = runTest {
        val primary = FakeRecognizer(RecognitionResult.Unavailable)
        val secondary = FakeRecognizer(RecognitionResult.Hit("会了"))
        val proxy = newProxy(primary, secondary) { }

        primary.setAvailable(false)
        assertFalse(proxy.isAvailable.value) // 降级前：随主引擎

        primary.setAvailable(true)
        proxy.listenOnce(WINDOW_MS) // 触发降级（主引擎硬失败拉低自身）
        assertTrue(proxy.isAvailable.value) // 降级后：随备引擎（true）

        secondary.setAvailable(false)
        assertFalse(proxy.isAvailable.value)
    }

    /** 统一装配：注入虚拟单调时钟（初始 0）+ Unconfined 测试作用域（isAvailable 投影即时传播）。 */
    private fun TestScope.newProxy(
        primary: FakeRecognizer,
        secondary: FakeRecognizer,
        hook: (MutableStateFlow<Long>) -> Unit,
    ): FallbackSpeechCommandRecognizer {
        val nowMs = MutableStateFlow(0L)
        hook(nowMs)
        return FallbackSpeechCommandRecognizer(
            primary = primary,
            secondary = secondary,
            timeSourceMs = { nowMs.value },
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
        )
    }

    private companion object {
        const val WINDOW_MS: Long = 4_000L

        /** 主引擎硬失败耗时（vivo V2436A 实测：绑定后 46ms onError）。 */
        const val PRIMARY_FAIL_ELAPSED_MS: Long = 46L
    }
}
