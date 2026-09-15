package com.vocabularybooster.app.audio

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vocabularybooster.platform.VoskSpeechCommandRecognizer
import com.vocabularybooster.speech.RecognitionResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * VoskSpeechCommandRecognizer（内置离线引擎兜底 actual）平台契约 instrumented 测试
 * （TC-AE-27，裁决 E1/E2，AUDIO_ENGINE_SPEC §8 `VoskSpeechCommandRecognizer` 契约行）。
 *
 * 环境诚实原则（同 AndroidSpeechCommandRecognizerInstrumentedTest）：
 * - Vosk 不依赖系统识别服务（本类在任何设备/AVD 均确定性可跑，无 Assume 服务项）；
 * - 首个 listenOnce 含模型解包（首次 ~66MB 拷贝）+ 加载，时长断言取宽区间；
 * - 真实人声「会了」识别与国行真机覆盖 = M1/M2 手动矩阵（vivo V2436A 代表机型），不在自动化内伪造；
 * - 权限拒绝分支（RECORD_AUDIO 未授予 → 构造即 isAvailable=false、listenOnce 前置门 Unavailable）
 *   随 PermissionDeniedSpeechInstrumentedTest 单类隔离运行（套件内禁止 pm revoke——会杀进程）。
 */
@RunWith(AndroidJUnit4::class)
class VoskSpeechCommandRecognizerInstrumentedTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** §8 可用性：模型资产在场 + 权限已授予 → true（模型懒加载，isAvailable 不等待加载）。 */
    @Test
    fun availability_afterGrant_reportsAvailable() = runBlocking {
        grantRecordAudio()
        val recognizer = withContext(Dispatchers.Main.immediate) { VoskSpeechCommandRecognizer(context) }

        assertTrue("Vosk actual 应可用（模型随 APK 分发 + 权限已授予）", recognizer.isAvailable.value)
    }

    /** §8 窗口预算（库 timeout 变体按音频样本计）：静默窗口 → Timeout；静默 ≠ 失败，不拉低 isAvailable。 */
    @Test
    fun silenceWithinWindow_returnsTimeout_withoutPullingDownAvailability() = runBlocking {
        grantRecordAudio()
        val recognizer = withContext(Dispatchers.Main.immediate) { VoskSpeechCommandRecognizer(context) }
        assertTrue(recognizer.isAvailable.value)

        val startedAt = System.currentTimeMillis()
        val result = withTimeout(45_000) { recognizer.listenOnce(windowMs = 2_000) }

        assertEquals(RecognitionResult.Timeout, result)
        val elapsed = System.currentTimeMillis() - startedAt
        assertTrue(
            "窗口预算应约 2s（首跑含解包+模型加载，取宽区间），实际 ${elapsed}ms",
            elapsed in 1_500..20_000,
        )
        assertTrue("静默 Timeout 是正常终局，不得拉低 isAvailable", recognizer.isAvailable.value)
    }

    /** §8 取消语义：listenOnce 被取消 → 会话释放（守卫解锁），下次受理不误降级。 */
    @Test
    fun cancelledListenOnce_releasesSession_thenNextListenIsAccepted() = runBlocking {
        grantRecordAudio()
        val recognizer = withContext(Dispatchers.Main.immediate) { VoskSpeechCommandRecognizer(context) }
        assertTrue(recognizer.isAvailable.value)

        val scope = CoroutineScope(Dispatchers.Main.immediate + Job())
        val job = scope.launch { recognizer.listenOnce(windowMs = 10_000) }
        delay(500) // 首调用已持有会话守卫（模型加载/监听中）
        job.cancelAndJoin() // 取消 = cancel 录音线程 + finally 全释放（§3 pause/按钮/exit 的真实路径）

        // 守卫解锁回归：紧接的 listenOnce 正常受理（若守卫未释放则 Unavailable——即回归）
        val result = withTimeout(45_000) { recognizer.listenOnce(windowMs = 1_000) }
        assertEquals(RecognitionResult.Timeout, result)
        scope.cancel()
    }

    /** §8 单会话约束：并发第二调用者立即 Unavailable（tryLock 同步守卫，模型加载挂起期间同样生效）。 */
    @Test
    fun concurrentListenOnce_secondCallerGetsUnavailable() = runBlocking {
        grantRecordAudio()
        val recognizer = withContext(Dispatchers.Main.immediate) { VoskSpeechCommandRecognizer(context) }
        assertTrue(recognizer.isAvailable.value)

        val scope = CoroutineScope(Dispatchers.Main.immediate + Job())
        val first = scope.async { recognizer.listenOnce(windowMs = 10_000) }
        delay(500) // 首调用已越过 tryLock（此后可能仍挂起于模型加载——守卫仍被持有）
        val second = scope.async { recognizer.listenOnce(windowMs = 10_000) }

        assertEquals(RecognitionResult.Unavailable, withTimeout(5_000) { second.await() })

        // 取消可能需等阻塞中的解包/加载走到挂起点才生效——等其收尾，不跨用例泄漏会话
        first.cancel()
        withTimeout(45_000) { first.join() }
        scope.cancel()
    }

    // —— fixture ——

    /** 授予 RECORD_AUDIO（幂等；授予不会杀进程——revoke 才会，全套件禁用 revoke）。 */
    private fun grantRecordAudio() {
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
            "pm grant ${context.packageName} android.permission.RECORD_AUDIO",
        )
    }
}
