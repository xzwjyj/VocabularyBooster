package com.vocabularybooster.platform

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.vocabularybooster.speech.RecognitionResult
import com.vocabularybooster.speech.SpeechCommandRecognizer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/**
 * SpeechCommandRecognizer 的 Android 实现（AUDIO_ENGINE_SPEC §8 平台 actual 契约 +
 * Phase 5 Step 1 裁决 D3/D5）：`android.speech.SpeechRecognizer`（Koin 单例，
 * applicationContext——不持 Activity；仅 CommandWindow 内被编排器调用，无后台监听）。
 *
 * - **生命周期（§6/§8）**：主线程限定；每次 `listenOnce` = 一个识别窗口
 *   （create → 可多次重挂的 startListening → destroy），同一时刻至多一个并发会话
 *   （[active] 守卫，重入 → Unavailable）；窗口结束/取消即 destroy——迟到回调被
 *   单次 resume 守卫拦截，不再触达业务层。
 * - **语言（裁决 D3）**：`EXTRA_LANGUAGE = "zh-CN"`（v1 命令词表为简体中文）+
 *   `EXTRA_PREFER_OFFLINE = true`（NFR-4 离线优先）；common 端口签名不变。
 * - **窗口预算**：deadline 制（elapsedRealtime 基准，重挂间隔计入）；预算静默耗尽 → Timeout。
 * - **错误映射（裁决 D5）**：软错误（NO_MATCH / SPEECH_TIMEOUT / BUSY / 网络/音频/服务瞬时）→
 *   「本次没有有效命令」，窗口预算内重挂监听；可用性级错误（权限不足 / ERROR_CLIENT /
 *   创建失败 / **看门狗判死（E4）**）→ Unavailable（编排器整窗降级纯倒计时，「会了」按钮仍可用）。
 *   **任何错误都不产生命令语义**。权限缺失导致的 Unavailable 会拉低 [isAvailable]：
 *   后续窗口保持降级（本进程内诚实降级，不做权限自愈/自动重试）。
 * - **响应看门狗（裁决 E4，2026-09-14，AUDIO_ENGINE_SPEC §8）**：[SERVICE_RESPONSIVENESS_MS] 内
 *   **零回调**（健康服务安静时也持续回调 onReadyForSpeech/onRmsChanged——零回调 ≠ 用户没说话）
 *   → 服务僵尸（实测 vivo 假 RecognitionService 静默挂死，端口层 Timeout 与安静窗口不可区分）
 *   → 按可用性级失败上报（HardFailure → E3 引擎回退代理同窗接管）；判死不算"识别了什么"。
 * - **回调纪律**：只收 final RESULTS_RECOGNITION（首个候选，空文本按软错误处理）；
 *   partial 一律忽略（端口契约）；单次 resume 守卫防重复回调。
 */
public class AndroidSpeechCommandRecognizer(
    context: Context,
) : SpeechCommandRecognizer {

    private val appContext: Context = context.applicationContext

    private val _isAvailable = MutableStateFlow(SpeechRecognizer.isRecognitionAvailable(appContext))
    override val isAvailable: StateFlow<Boolean> = _isAvailable

    /** 当前识别会话（主线程限定）：≤1 并发——§6 不允许多 recognition session。 */
    private var active: SpeechRecognizer? = null

    override suspend fun listenOnce(windowMs: Long): RecognitionResult = withContext(Dispatchers.Main.immediate) {
        refreshAvailability()
        if (!_isAvailable.value) return@withContext RecognitionResult.Unavailable // 前置门：服务缺失
        if (active != null) return@withContext RecognitionResult.Unavailable // 并发会话守卫（防御）
        val recognizer = createRecognizerOrNull()
            ?: return@withContext RecognitionResult.Unavailable
        active = recognizer
        try {
            listenWithinWindow(recognizer, windowMs)
        } finally {
            active = null
            recognizer.stopListening() // §8 关识别：取消/终局立即停（destroy 前）
            recognizer.setRecognitionListener(null) // 解绑：迟到回调不再触达业务层
            recognizer.destroy() // §8 每窗口用完即 destroy
        }
    }

    /** OEM 异常种类不可穷举（SecurityException/服务绑定失败等），统一按创建失败降级。 */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun createRecognizerOrNull(): SpeechRecognizer? = try {
        SpeechRecognizer.createSpeechRecognizer(appContext)
    } catch (e: Exception) {
        _isAvailable.value = false
        null
    }

    /** 窗口循环（deadline 制）：单会话限时；软错误重挂（预算内）；Text/终局即返回。 */
    private suspend fun listenWithinWindow(recognizer: SpeechRecognizer, windowMs: Long): RecognitionResult {
        val deadline = SystemClock.elapsedRealtime() + windowMs
        while (true) {
            val remaining = deadline - SystemClock.elapsedRealtime()
            if (remaining <= 0) return RecognitionResult.Timeout // 预算耗尽（含重挂间隔）
            val outcome = try {
                withTimeout(remaining) { listenForOneSession(recognizer) }
            } catch (e: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive() // 外层取消（按钮/暂停/退出）照常传播，不吞
                return RecognitionResult.Timeout
            }
            when (outcome) {
                is SessionOutcome.Text -> return RecognitionResult.Hit(outcome.text)
                SessionOutcome.HardFailure -> {
                    android.util.Log.i("VB-SysRec", "hard failure → Unavailable（拉低 isAvailable）")
                    _isAvailable.value = false // 权限/服务级：拉低可用性，后续窗口前置门降级
                    return RecognitionResult.Unavailable
                }
                SessionOutcome.SoftFailure -> delay(SOFT_RETRY_DELAY_MS) // 重挂限速：瞬时错误不空转
            }
        }
    }

    /**
     * 单次 startListening → 终局回调（final 结果 / 软错误 / 硬错误 / 看门狗判死）；partial 忽略。
     * 响应看门狗（E4）：[SERVICE_RESPONSIVENESS_MS] 内零回调（健康服务安静时也持续回调 ready/RMS）
     * → 服务僵尸 → HardFailure（触发 E3 同窗回退），不算"识别了什么"。
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException") // OEM 同步异常（如无权限 SecurityException）不可穷举，统一按硬失败以结果上报
    private suspend fun listenForOneSession(recognizer: SpeechRecognizer): SessionOutcome = coroutineScope {
        val firstSignal = CompletableDeferred<Unit>() // 看门狗判据：任何回调 = 服务活着
        val session = async {
            suspendCancellableCoroutine { cont ->
                var resumed = false // 单次 resume 守卫（AUDIO §11：onResults/onError 不重复触发）
                fun settle(outcome: SessionOutcome) {
                    if (!resumed) {
                        resumed = true
                        firstSignal.complete(Unit)
                        cont.resumeWith(Result.success(outcome))
                    }
                }

                recognizer.setRecognitionListener(object : RecognitionListener {
                    override fun onResults(results: Bundle?) {
                        val texts = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                        val text = texts.firstOrNull().orEmpty()
                        android.util.Log.i("VB-SysRec", "onResults text=$text")
                        if (text.isEmpty()) {
                            settle(SessionOutcome.SoftFailure) // 空结果 ≠ 命令：按「本次无有效命令」重挂
                        } else {
                            settle(SessionOutcome.Text(text))
                        }
                    }

                    override fun onError(error: Int) {
                        android.util.Log.i("VB-SysRec", "onError($error)")
                        when (error) {
                            SpeechRecognizer.ERROR_NO_MATCH,
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                            SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
                            SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                            SpeechRecognizer.ERROR_NETWORK,
                            SpeechRecognizer.ERROR_SERVER,
                            SpeechRecognizer.ERROR_AUDIO,
                            -> settle(SessionOutcome.SoftFailure) // D5：瞬时错误 → 窗口内重挂

                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS,
                            SpeechRecognizer.ERROR_CLIENT, // 服务缺失/绑定失败常以此码浮出
                            -> settle(SessionOutcome.HardFailure)

                            else -> settle(SessionOutcome.SoftFailure) // 未知码保守按软错误（不产生命令语义）
                        }
                    }

                    // 端口契约：partial 一律忽略（防抖）；onEndOfSpeech 后等待 final 结果，不提前定局。
                    // 被动回调喂看门狗（服务活着的证据），不触达业务层。
                    override fun onPartialResults(partialResults: Bundle?) {
                        firstSignal.complete(Unit)
                    }

                    override fun onEndOfSpeech() {
                        firstSignal.complete(Unit)
                    }

                    override fun onReadyForSpeech(params: Bundle?) {
                        firstSignal.complete(Unit)
                    }

                    override fun onBeginningOfSpeech() {
                        firstSignal.complete(Unit)
                    }

                    override fun onRmsChanged(rmsdB: Float) {
                        firstSignal.complete(Unit)
                    }

                    override fun onBufferReceived(buffer: ByteArray?) {
                        firstSignal.complete(Unit)
                    }

                    override fun onEvent(eventType: Int, params: Bundle?) {
                        firstSignal.complete(Unit)
                    }
                })
                try {
                    recognizer.startListening(listenIntent())
                } catch (e: Exception) {
                    // 部分 OEM 无 RECORD_AUDIO 时从 startListening 同步抛 SecurityException
                    // （而非走 onError）——§8 契约：actual 不抛异常，失败以结果上报
                    settle(SessionOutcome.HardFailure)
                }
            }
        }
        val first = withTimeoutOrNull(SERVICE_RESPONSIVENESS_MS) { firstSignal.await() }
        if (first == null && session.isActive) {
            android.util.Log.i(
                "VB-SysRec",
                "watchdog: ${SERVICE_RESPONSIVENESS_MS}ms 零回调 → 服务无响应（E4 hard failure → E3 同窗回退）",
            )
            session.cancel()
            return@coroutineScope SessionOutcome.HardFailure
        }
        session.await()
    }

    /** §8 + D3：识别意图（zh-CN 固定语言 + 离线优先 + 关闭 partial + 单候选）。 */
    private fun listenIntent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, LANGUAGE_ZH_CN)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
    }

    /** 每次窗口前刷新可用性（服务安装/卸载后如实反映；无轮询）。 */
    private fun refreshAvailability() {
        _isAvailable.value = SpeechRecognizer.isRecognitionAvailable(appContext)
        android.util.Log.i("VB-SysRec", "refreshAvailability: isAvailable=${_isAvailable.value}")
    }

    private sealed interface SessionOutcome {
        data class Text(val text: String) : SessionOutcome

        /** 软错误（D5）：本次无有效命令——窗口内重挂监听。 */
        data object SoftFailure : SessionOutcome

        /** 可用性级错误：权限/服务缺失 → Unavailable（编排器降级倒计时 + 按钮可用）。 */
        data object HardFailure : SessionOutcome
    }

    private companion object {
        /** v1 命令语言（裁决 D3）：简体中文命令词表「会了/记住了/掌握了」。 */
        const val LANGUAGE_ZH_CN: String = "zh-CN"

        /** 软错误重挂间隔：限速，防服务瞬时错误空转主线程。 */
        const val SOFT_RETRY_DELAY_MS: Long = 150L

        /**
         * 响应看门狗窗口（裁决 E4，2026-09-14）：startListening 后零回调判服务僵尸
         * （健康服务安静时亦持续回调 onReadyForSpeech/onRmsChanged——零回调 ≠ 用户没说话）。
         */
        const val SERVICE_RESPONSIVENESS_MS: Long = 1_500L
    }
}
