package com.vocabularybooster.platform

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import com.vocabularybooster.speech.RecognitionResult
import com.vocabularybooster.speech.SpeechCommandRecognizer
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService

/**
 * SpeechCommandRecognizer 的内置离线引擎实现（AUDIO_ENGINE_SPEC §8 `VoskSpeechCommandRecognizer`
 * 契约行 + 裁决 E1/E2/E3，2026-09-14）：Vosk + 中文小模型打包进 APK，**纯本地识别、无云端链路**
 * （NFR-1/NFR-4）。在 DI 装配时为**主引擎**（窗口开启前已完成 prewarmModel 预加载，响应≈0ms、零盲区）；
 * 仅系统引擎硬失败时由 [FallbackSpeechCommandRecognizer] 回退到系统 actual。
 * 与 [AndroidSpeechCommandRecognizer] 实现同一端口——编排器/解析器/掌握语义（D2–D5）零改动。
 *
 * - **模型**：`vosk-model-small-cn-0.22` 随 app assets 分发；首个窗口前懒加载——
 *   [StorageService.sync] 解包至应用外部文件目录（按模型 `uuid` 增量同步，只解包一次）
 *   → `Model` 进程内单例复用。解包/加载在 IO 线程；**加载失败进程内粘滞**（isAvailable 拉低，不自动重试）。
 * - **生命周期**：每次 `listenOnce` = 一个识别会话（新建 `Recognizer` + `SpeechService` →
 *   用后即毁 `stop + shutdown + close`，取消同样触发，绝无泄漏录音）；同一时刻至多一个并发会话
 *   （[sessionMutex] tryLock 同步守卫——先于模型加载挂起点，重入 → Unavailable）。
 * - **窗口预算**：`startListening(listener, windowMs)` 库 timeout 变体按音频样本数计窗口
 *   （静默计入预算）+ 外层 [withTimeout] 兜底（音频读取卡死等异常路径不至挂死窗口）。
 * - **回调纪律**：只取 final 文本（`onResult` / 终局 `onFinalResult` 的 JSON `text`）；
 *   `onPartialResult` 一律忽略（端口契约）；空 final ≠ 命令，继续等至窗口终结；
 *   单次 settle 守卫拦截迟到回调。
 * - **错误映射（沿用 D5 精神）**：模型解包/加载失败、`AudioRecord` 创建或启动失败（权限缺失/
 *   麦克风占用）、`onError` → `Unavailable` 且拉低 [isAvailable]（本进程内诚实降级）；
 *   窗口静默耗尽 → `Timeout`。**任何错误都不产生命令语义；端口绝不抛异常**。
 */
public class VoskSpeechCommandRecognizer(
    context: Context,
) : SpeechCommandRecognizer {

    private val appContext: Context = context.applicationContext

    private val _isAvailable = MutableStateFlow(computeAvailability())
    override val isAvailable: StateFlow<Boolean> = _isAvailable

    /**
     * 会话守卫：≤1 并发（与系统 actual 同守卫语义）。用 [Mutex.tryLock] **同步**获取——
     * 守卫必须先于任何挂起点（模型加载是挂起的），否则并发双开 SpeechService；
     * 并发第二调用者 → 立即 Unavailable（不排队、不抛异常）。
     */
    private val sessionMutex = Mutex()

    /** 模型进程内单例（懒加载；加载失败粘滞——届时双引擎皆不可用，窗口降级纯倒计时）。 */
    private var model: Model? = null
    private var modelLoadFailed = false
    private val modelMutex = Mutex()

    override suspend fun listenOnce(windowMs: Long): RecognitionResult = withContext(Dispatchers.Main.immediate) {
        if (!sessionMutex.tryLock()) return@withContext RecognitionResult.Unavailable // 并发会话守卫（同步，无竞态窗口）
        try {
            refreshAvailability()
            if (!_isAvailable.value) return@withContext RecognitionResult.Unavailable // 前置门：权限/资产/模型
            val model = obtainModelOrNull()
            if (model == null) {
                _isAvailable.value = false // 模型加载失败（粘滞）：后续窗口前置门降级
                return@withContext RecognitionResult.Unavailable
            }
            val session = createSessionOrNull(model)
                ?: return@withContext RecognitionResult.Unavailable // 录音器创建失败 → 内部已拉低 isAvailable
            try {
                withTimeout(windowMs + OUTER_DEADLINE_GRACE_MS) { awaitOutcome(session.service, windowMs) }
            } catch (e: TimeoutCancellationException) {
                // 库未按时回调（音频读取卡死等）→ 兜底终局；外层取消（按钮/暂停/退出）照常传播，不吞
                currentCoroutineContext().ensureActive()
                RecognitionResult.Timeout
            } catch (e: Exception) {
                android.util.Log.e("VB-Vosk", "listenOnce unexpected failure", e)
                _isAvailable.value = false
                RecognitionResult.Unavailable
            } finally {
                // 用后即毁：停识别线程 → 释放录音器 → 释放识别器（幂等/无害；recognizer.close() 全文件仅此一次，
                // 取消路径同样抵达 finally）。迟到回调由单次 settle 守卫吸收。
                runCatching { session.service.stop() }
                runCatching { session.service.shutdown() }
                runCatching { session.recognizer.close() }
            }
        } finally {
            sessionMutex.unlock()
        }
    }

    /**
     * E3 引擎回退代理装配用：启动阶段后台预解包/预加载模型——仅文件与 CPU，**绝不开启麦克风**
     * （与 [listenOnce] 共用 [modelMutex] 与粘滞失败语义；使回退/直连路径的首个真窗口免加载等待）。
     */
    public suspend fun prewarmModel() {
        obtainModelOrNull()
    }

    /**
     * 单次监听：库 timeout 变体驱动窗口预算；非空 final 文本 → Hit；空 final / 窗口耗尽 → Timeout；
     * onError（录音启动失败等）→ Unavailable + 拉低 isAvailable。回调经库内 mainHandler 回主线程。
     */
    private suspend fun awaitOutcome(service: SpeechService, windowMs: Long): RecognitionResult =
        suspendCancellableCoroutine { cont ->
            var settled = false // 单次 settle 守卫：onResult/onFinalResult/onTimeout/onError 至多一次生效
            fun settle(result: RecognitionResult) {
                if (!settled) {
                    settled = true
                    cont.resumeWith(Result.success(result))
                }
            }

            val listener = object : RecognitionListener {
                override fun onResult(hypothesis: String) {
                    android.util.Log.i("VB-Vosk", "onResult raw=$hypothesis")
                    val text = parseFinalText(hypothesis)
                    android.util.Log.i("VB-Vosk", "onResult parsed=$text")
                    if (text.isNotEmpty()) {
                        android.util.Log.i("VB-Vosk", "Hit: $text")
                        settle(RecognitionResult.Hit(text))
                    }
                    // 空 final ≠ 命令（软失败语义）：继续监听至窗口终结（库不因 onResult 停止）
                }

                override fun onFinalResult(hypothesis: String) {
                    // 终局 final（窗口耗尽或 stop）：非空文本按 final 采纳，否则窗口自然结束
                    // 注意：onResult 可能已经 settle，此处不再重复 settle（settled 守卫已保护）
                    val text = parseFinalText(hypothesis)
                    android.util.Log.i("VB-Vosk", "onFinalResult text=$text")
                    if (text.isNotEmpty() && !settled) {
                        android.util.Log.i("VB-Vosk", "Hit: $text")
                        settle(RecognitionResult.Hit(text))
                    }
                    // 若 settled 已为 true（onResult 已命中），此处忽略；否则空文本按 Timeout
                }

                override fun onTimeout() {
                    settle(RecognitionResult.Timeout) // 窗口样本预算耗尽
                }

                override fun onError(exception: Exception) {
                    android.util.Log.i("VB-Vosk", "onError: ${exception.message}")
                    _isAvailable.value = false // 录音启动失败等可用性级错误：拉低，后续窗口前置门降级
                    settle(RecognitionResult.Unavailable)
                }

                override fun onPartialResult(hypothesis: String) = Unit // 端口契约：partial 一律忽略
            }

            // 取消（按钮/暂停/退出/外层兜底超时）：立即打断录音线程——麦克风指示即刻消失
            cont.invokeOnCancellation { runCatching { service.cancel() } }

            val started = runCatching { service.startListening(listener, windowMs.toInt()) }.getOrDefault(false)
            if (!started) settle(RecognitionResult.Unavailable)
        }

    /** Vosk result JSON：{"text": "..."}；畸形 JSON / 缺键 → 空串（不产生命令语义）。 */
    private fun parseFinalText(hypothesis: String): String = runCatching {
        JSONObject(hypothesis).optString(FIELD_TEXT, "").trim()
    }.getOrDefault("")

    /** 模型懒加载（互斥防并发解包；IO 线程解包+加载；IOException 粘滞失败，取消照常传播）。 */
    private suspend fun obtainModelOrNull(): Model? = modelMutex.withLock {
        model?.let { return it }
        if (modelLoadFailed) return null
        withContext(Dispatchers.IO) {
            try {
                val modelPath = StorageService.sync(appContext, MODEL_ASSET_DIR, MODEL_TARGET_DIR)
                Model(modelPath).also { loaded -> model = loaded }
            } catch (e: IOException) {
                android.util.Log.e("VB-Vosk", "model unpack/load failed → 粘滞不可用", e)
                modelLoadFailed = true
                null
            }
        }
    }

    /** 建会话（Recognizer + SpeechService）。AudioRecord 创建失败（权限缺失/麦克风占用）→ null 且拉低。 */
    private fun createSessionOrNull(model: Model): Session? = try {
        val recognizer = Recognizer(model, SAMPLE_RATE)
        val service = SpeechService(recognizer, SAMPLE_RATE) // 构造即建 AudioRecord，失败抛 IOException
        Session(service, recognizer)
    } catch (e: IOException) {
        _isAvailable.value = false
        null
    }

    /** 可用性 = 模型资产在场 + RECORD_AUDIO 已授予 + 模型未发生粘滞加载失败。 */
    private fun computeAvailability(): Boolean {
        val assets = hasModelAssets()
        val permission = hasRecordPermission()
        val available = !modelLoadFailed && assets && permission
        android.util.Log.i(
            "VB-Vosk",
            "computeAvailability: available=$available (modelLoadFailed=$modelLoadFailed, assets=$assets, recordPermission=$permission)",
        )
        return available
    }

    /** 每次窗口前刷新（授权/资产变化如实反映；无轮询）。 */
    private fun refreshAvailability() {
        _isAvailable.value = computeAvailability()
    }

    private fun hasRecordPermission(): Boolean = appContext.checkPermission(
        Manifest.permission.RECORD_AUDIO,
        Process.myPid(),
        Process.myUid(),
    ) == PackageManager.PERMISSION_GRANTED

    private fun hasModelAssets(): Boolean = runCatching {
        !appContext.assets.list(MODEL_ASSET_DIR).isNullOrEmpty()
    }.getOrDefault(false)

    private class Session(val service: SpeechService, val recognizer: Recognizer)

    private companion object {
        /** Vosk 中文小模型（assets 目录名 = 模型名；StorageService.sync 按模型内 uuid 文件增量同步）。 */
        const val MODEL_ASSET_DIR: String = "vosk-model-small-cn-0.22"

        /** 解包目标目录（getExternalFilesDir 下应用专属目录，无需任何存储权限）。 */
        const val MODEL_TARGET_DIR: String = "models"

        /** 16kHz 单声道 PCM——Vosk small-cn 模型采样率（SpeechService 内建 AudioRecord 同参数）。 */
        const val SAMPLE_RATE: Float = 16_000f

        /** Vosk result JSON 的文本字段。 */
        const val FIELD_TEXT: String = "text"

        /** 外层兜底超时宽限：库 timeout 变体按音频样本计窗、正常先于本兜底回调；仅音频读取卡死等异常路径生效。 */
        const val OUTER_DEADLINE_GRACE_MS: Long = 2_000L
    }
}
