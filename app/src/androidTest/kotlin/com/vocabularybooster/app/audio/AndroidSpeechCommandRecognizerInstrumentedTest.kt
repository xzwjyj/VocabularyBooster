package com.vocabularybooster.app.audio

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vocabularybooster.platform.AndroidSpeechCommandRecognizer
import com.vocabularybooster.speech.RecognitionResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * AndroidSpeechCommandRecognizer 平台契约 instrumented 测试（Phase 5 Step 1，AUDIO_ENGINE_SPEC §8 +
 * 裁决 D3/D5）。环境诚实原则（同 TtsSpeechSynthesizerInstrumentedTest）：
 * - 识别服务缺席（部分 OEM/CI 环境）→ Assume 跳过（转手动冒烟），不伪造 green；
 * - 真实语音「会了」识别（需人对麦克风说话）= 手动矩阵，不在自动化内伪造；
 * - **运行时 pm revoke 会杀死已授权运行中的 app 进程（Android 11+ 平台行为，插桩进程与 app 同进程）→
 *   全套件禁止 revoke**；权限拒绝 → Unavailable 映射因此独立成 PermissionDeniedSpeechInstrumentedTest
 *   （单类隔离运行 = Gradle 重装 APK 的新装拒绝态，确定性执行；套件内则由本类 pm grant 后续跑真实
 *   listen 路径，拒绝态呈现 smokeJ 同法自适应）。
 */
@RunWith(AndroidJUnit4::class)
class AndroidSpeechCommandRecognizerInstrumentedTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** 可用性探测：AVD 预装识别服务 → true；无服务环境由各用例 Assume 诚实跳过。 */
    @Test
    fun availabilityProbe_reportsServicePresence() = runBlocking {
        val recognizer = withContext(Dispatchers.Main.immediate) { AndroidSpeechCommandRecognizer(context) }
        assumeTrue("设备无语音识别服务：识别路径转手动冒烟", recognizer.isAvailable.value)
    }

    /** 静默窗口 → Timeout（deadline 制：软错误重挂不越过窗口预算，绝不产生命令）。 */
    @Test
    fun silenceWithinWindow_returnsTimeout() = runBlocking {
        grantRecordAudio()
        val recognizer = withContext(Dispatchers.Main.immediate) { AndroidSpeechCommandRecognizer(context) }
        assumeTrue("设备无语音识别服务：识别路径转手动冒烟", recognizer.isAvailable.value)

        val startedAt = System.currentTimeMillis()
        val result = withTimeout(15_000) { recognizer.listenOnce(windowMs = 2_000) }

        assertEquals(RecognitionResult.Timeout, result)
        val elapsed = System.currentTimeMillis() - startedAt
        assertTrue("窗口预算耗尽应约 2s（软错误重挂在预算内），实际 ${elapsed}ms", elapsed in 1_500..6_000)
    }

    /** §8 取消语义：listenOnce 被取消 → destroy 收尾（finally 必跑），active 守卫复位（下次受理不误降级）。 */
    @Test
    fun cancelledListenOnce_releasesSession_thenNextListenIsAccepted() = runBlocking {
        grantRecordAudio()
        val recognizer = withContext(Dispatchers.Main.immediate) { AndroidSpeechCommandRecognizer(context) }
        assumeTrue("设备无语音识别服务：识别路径转手动冒烟", recognizer.isAvailable.value)

        val scope = CoroutineScope(Dispatchers.Main.immediate + Job())
        val job = scope.launch { recognizer.listenOnce(windowMs = 10_000) }
        delay(500) // 会话已建立并挂起监听（active 非 null）
        job.cancelAndJoin() // 取消 = 关识别 + destroy（§3 pause/按钮/exit 的真实路径）

        // 守卫复位回归：紧接的 listenOnce 正常受理（若 destroy/守卫复位缺失则 Unavailable——即回归）
        val result = withTimeout(15_000) { recognizer.listenOnce(windowMs = 1_000) }
        assertEquals(RecognitionResult.Timeout, result)
        scope.cancel()
    }

    /** §6 单会话约束：并发第二调用者立即 Unavailable（不排队、不抛异常）。 */
    @Test
    fun concurrentListenOnce_secondCallerGetsUnavailable() = runBlocking {
        grantRecordAudio()
        val recognizer = withContext(Dispatchers.Main.immediate) { AndroidSpeechCommandRecognizer(context) }
        assumeTrue("设备无语音识别服务：识别路径转手动冒烟", recognizer.isAvailable.value)

        val scope = CoroutineScope(Dispatchers.Main.immediate + Job())
        val first = scope.async { recognizer.listenOnce(windowMs = 10_000) }
        delay(500) // 首调用越过 active 赋值并挂起（Main 单线程序确定）
        val second = scope.async { recognizer.listenOnce(windowMs = 10_000) }

        assertEquals(RecognitionResult.Unavailable, withTimeout(5_000) { second.await() })

        first.cancel()
        scope.cancel()
    }

    // —— fixture ——（权限拒绝用例见 PermissionDeniedSpeechInstrumentedTest 类注）

    /** 授予 RECORD_AUDIO（幂等；授予不会杀进程——revoke 才会，全套件禁用 revoke）。 */
    private fun grantRecordAudio() {
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
            "pm grant ${context.packageName} android.permission.RECORD_AUDIO",
        )
    }
}
