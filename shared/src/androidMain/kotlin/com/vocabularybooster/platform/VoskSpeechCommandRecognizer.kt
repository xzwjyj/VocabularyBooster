package com.vocabularybooster.platform

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import com.vocabularybooster.speech.RecognitionResult
import com.vocabularybooster.speech.SpeechCommandRecognizer
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.StorageService

/**
 * SpeechCommandRecognizer 的内置离线引擎实现（AUDIO_ENGINE_SPEC §8 `VoskSpeechCommandRecognizer`
 * 契约行 + 裁决 E1/E2/E3，2026-09-14）：Vosk + 中文小模型打包进 APK，**纯本地识别、无云端链路**
 * （NFR-1/NFR-4）。在 DI 装配时为**主引擎**；仅系统引擎硬失败时由
 * [FallbackSpeechCommandRecognizer] 回退到系统 actual。与 [AndroidSpeechCommandRecognizer]
 * 实现同一端口——编排器/解析器/掌握语义（D2–D5）零改动。
 *
 * - **模型**：`vosk-model-small-cn-0.22` 随 app assets 分发；[StorageService.sync] 解包至应用
 *   外部文件目录（按模型 `uuid` 增量同步，只解包一次）→ `Model` 进程内单例复用；解包/加载在
 *   IO 线程，**加载失败进程内粘滞**（isAvailable 拉低，不自动重试）。
 * - **自管音频管线（轻声/气音支持，用户裁决 2026-09-18）**：弃用库 `SpeechService`（其
 *   VOICE_RECOGNITION 源走 OEM 语音处理链，实测 vivo 04:41 把轻声/气音整窗抹成纯静默——
 *   零回调零 partial），改为自建 `AudioRecord`（**MIC 原始源**）50ms 块读入：
 *   ①**自适应预增益**——按块 RMS 归一到目标电平（上限 [MAX_GAIN]×，削波保护），把气音的
 *   微弱能量抬到模型可解码区；②`setEndpointerMode(SHORT)` 提高模型自身 VAD 灵敏度；
 *   ③**自端点强制终局（双触发）**——(a) **持续**语音能量（连续 ≥[SUSTAIN_ENERGY_CHUNKS] 块
 *   越过 [SPEECH_RMS_FLOOR] 才武装——单块噪声尖峰不算：2026-09-18 插桩静默窗实证底噪尖峰
 *   150–204 越过固定门限）后 [FLUSH_SILENCE_MS] 尾静默；(b) **假设稳定**——模型已解出非空
 *   partial 且 [PARTIAL_STABLE_MS] 无改进（不依赖声学门限：同日诊断文件再证底噪 min/avg
 *   117–142 **持续**贴着门限，能量尾静默永不积累 → 轻声冲刷失效）。任一触发即
 *   `getFinalResult()` 冲刷假设并重置（不依赖模型 VAD 自己下定决心），窗口内可继续听第二句。
 * - **窗口诊断文件**：窗口事件（RMS 摘要/能量武装/终局文本/终态）写入 `filesDir/vosk_diag.log`
 *   ——vivo logd 会间歇性**整进程吞掉应用日志**（2026-09-18 用户测试进程零日志、无法取证），
 *   logcat 之外的持久取证通道（debug 构建 `run-as` 可导出）。
 * - **生命周期**：每次 `listenOnce` = 一个识别会话（自建 `Recognizer` + `AudioRecord` →
 *   用后即毁 `stop + release + close`，取消同样触发，绝无泄漏录音）；同一时刻至多一个并发
 *   会话（[sessionMutex] tryLock 同步守卫——先于模型加载挂起点，重入 → Unavailable）。
 * - **窗口预算**：按读入样本数计窗（[SPEECH_RMS_FLOOR] 以下静默同样计入）+ 外层
 *   [withTimeout] 兜底（音频读取卡死等异常路径不至挂死窗口）。
 * - **错误映射（沿用 D5 精神）**：模型解包/加载失败、`AudioRecord` 创建或启动失败（权限
 *   缺失/麦克风占用）→ `Unavailable` 且拉低 [isAvailable]（进程内诚实降级）；窗口耗尽 →
 *   `Timeout`；读错误/零样本 → 按窗口耗尽处理。**任何错误都不产生命令语义；端口绝不抛异常**。
 */
public class VoskSpeechCommandRecognizer(
    context: Context,
) : SpeechCommandRecognizer {

    private val appContext: Context = context.applicationContext

    private val _isAvailable = MutableStateFlow(computeAvailability())
    override val isAvailable: StateFlow<Boolean> = _isAvailable

    /**
     * 会话守卫：≤1 并发（与系统 actual 同守卫语义）。用 [Mutex.tryLock] **同步**获取——
     * 守卫必须先于任何挂起点（模型加载是挂起的），否则并发双开录音；并发第二调用者 →
     * 立即 Unavailable（不排队、不抛异常）。
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
            diag("listenOnce: windowMs=$windowMs available=${_isAvailable.value}")
            if (!_isAvailable.value) return@withContext RecognitionResult.Unavailable // 前置门：权限/资产/模型
            val model = obtainModelOrNull()
            if (model == null) {
                _isAvailable.value = false // 模型加载失败（粘滞）：后续窗口前置门降级
                diag("listenOnce: model unavailable → Unavailable")
                return@withContext RecognitionResult.Unavailable
            }
            val recognizer = createRecognizerOrNull(model)
                ?: return@withContext RecognitionResult.Unavailable // Recognizer 构造失败 → 内部已拉低 isAvailable
            try {
                withTimeout(windowMs + OUTER_DEADLINE_GRACE_MS) { recordAndListen(recognizer, windowMs) }
            } catch (e: TimeoutCancellationException) {
                // 库未按时回调（音频读取卡死等）→ 兜底终局；外层取消（按钮/暂停/退出）照常传播，不吞
                currentCoroutineContext().ensureActive()
                RecognitionResult.Timeout
            } catch (e: CancellationException) {
                // 外层取消（按钮/暂停/退出/dispose 关窗）是端口契约内的正常生命周期：重抛（协程契约）。
                // 绝不按故障处理——若在此拉低 isAvailable，回退代理会把取消误判为主引擎硬失败，
                // 进程内粘滞降级到备引擎（坏服务机型 = 后续窗口全降级零识别，2026-09-17 vivo 实证）。
                throw e
            } catch (e: Exception) {
                android.util.Log.e("VB-Vosk", "listenOnce unexpected failure", e)
                _isAvailable.value = false
                RecognitionResult.Unavailable
            } finally {
                runCatching { recognizer.close() } // 取消路径同样抵达；自管管线无回调，无迟到结果
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
     * 单次监听（自管录音循环）：录音器启动失败 → Unavailable；循环内非空终局文本 → Hit；
     * 窗口样本预算耗尽 / 读错误 → Timeout。读循环在 IO 线程阻塞取块（50ms/块）。
     */
    private suspend fun recordAndListen(recognizer: Recognizer, windowMs: Long): RecognitionResult {
        val recorder = createRecorderOrNull()
            ?: return RecognitionResult.Unavailable // 创建失败（权限/占用/未初始化）→ 内部已拉低 isAvailable
        try {
            val started = runCatching { recorder.startRecording(); true }.getOrDefault(false)
            if (!started) {
                _isAvailable.value = false // 启动失败同样可用性级
                diag("recorder: startRecording failed → Unavailable")
                return RecognitionResult.Unavailable
            }
            return withContext(Dispatchers.IO) { listeningLoop(recognizer, recorder, windowMs) }
        } finally {
            // 用后即毁：停录音 → 释放录音器（幂等；取消路径同样抵达 finally——结构化并发保证）
            runCatching { recorder.stop() }
            runCatching { recorder.release() }
        }
    }

    /**
     * 读循环：读块 → 能量统计与预增益 → 喂识别器 → 终局/强制冲刷裁决；预算耗尽 → Timeout。
     *
     * 冲刷双触发（任一成立即强制终局）：①**能量尾静默**——持续能量武装（[SUSTAIN_ENERGY_CHUNKS]
     * 连块越限，单块噪声尖峰不算）后 [FLUSH_SILENCE_MS] 静默；②**假设稳定**——模型已解出非空
     * partial 且连续 [PARTIAL_STABLE_MS] 无改进（不依赖声学门限：环境底噪持续越过能量门限的
     * 场景——2026-09-18 诊断文件实证底噪 min/avg 117–142 贴着门限 120，能量尾静默永不积累，
     * 轻声冲刷失效 = 首词零识别候选根因——假设稳定性仍可裁决）。
     */
    private suspend fun listeningLoop(
        recognizer: Recognizer,
        recorder: AudioRecord,
        windowMs: Long,
    ): RecognitionResult {
        val budgetFrames = (windowMs * SAMPLES_PER_MS).toInt()
        val chunk = ShortArray(CHUNK_FRAMES)
        var readFrames = 0
        var speechSeen = false
        var trailingSilenceMs = 0
        var consecutiveEnergyChunks = 0
        var lastPartial = ""
        var partialStableMs = 0
        var statMs = 0
        var statMinRms = Double.MAX_VALUE
        var statMaxRms = 0.0
        var statSumRms = 0.0
        diag("window: start windowMs=$windowMs")
        while (currentCoroutineContext().isActive && readFrames < budgetFrames) {
            val frames = recorder.read(chunk, 0, chunk.size)
            if (frames <= 0) {
                android.util.Log.w("VB-Vosk", "read failed: $frames → 按窗口耗尽处理（零命令语义）")
                diag("window: read failed frames=$frames")
                break
            }
            readFrames += frames
            val rawRms = rmsOf(chunk, frames)
            statMs += CHUNK_MS
            statMinRms = minOf(statMinRms, rawRms)
            statMaxRms = maxOf(statMaxRms, rawRms)
            statSumRms += rawRms
            if (statMs >= DIAG_SUMMARY_MS) {
                diag(
                    "window: rms ${statMs}ms min=${statMinRms.toInt()} max=${statMaxRms.toInt()} " +
                        "avg=${(statSumRms / (statMs / CHUNK_MS)).toInt()}",
                )
                statMs = 0
                statMinRms = Double.MAX_VALUE
                statMaxRms = 0.0
                statSumRms = 0.0
            }
            consecutiveEnergyChunks = if (rawRms >= SPEECH_RMS_FLOOR) consecutiveEnergyChunks + 1 else 0
            val speechActive = consecutiveEnergyChunks >= SUSTAIN_ENERGY_CHUNKS
            if (speechActive) {
                if (!speechSeen) {
                    android.util.Log.i("VB-Vosk", "speech energy: rms=$rawRms (sustained $consecutiveEnergyChunks chunks)")
                    diag("window: speech armed rms=${rawRms.toInt()}")
                }
                speechSeen = true
                trailingSilenceMs = 0
            } else if (speechSeen) {
                trailingSilenceMs += CHUNK_MS
            }
            amplifyChunk(chunk, frames, gainFactorFor(rawRms))
            val endpointed = recognizer.acceptWaveForm(chunk, frames)
            if (endpointed) {
                val text = parseFinalText(recognizer.getResult())
                android.util.Log.i("VB-Vosk", "final(endpoint) text=$text")
                diag("window: final(endpoint) text=$text")
                if (text.isNotEmpty()) return RecognitionResult.Hit(text)
                speechSeen = false // 模型已自行分句：能量跟踪随段重置
                trailingSilenceMs = 0
                lastPartial = ""
                partialStableMs = 0
            } else {
                // 假设稳定跟踪（partial 只作冲刷裁决信号，命令语义仍只认 final 文本——防抖契约不变）
                val partial = parsePartialText(recognizer.getPartialResult())
                if (partial.isNotEmpty() && partial == lastPartial) {
                    partialStableMs += CHUNK_MS
                } else {
                    lastPartial = partial
                    partialStableMs = 0
                }
                val energyFlush = speechSeen && trailingSilenceMs >= FLUSH_SILENCE_MS
                val stableFlush = partial.isNotEmpty() && partialStableMs >= PARTIAL_STABLE_MS
                if (energyFlush || stableFlush) {
                    // 自端点强制终局：模型 VAD 未下定决心（轻声/气音/噪声环境常态）→ 冲刷当前假设并重置
                    val text = parseFinalText(recognizer.getFinalResult())
                    android.util.Log.i("VB-Vosk", "final(forced-flush) text=$text")
                    diag("window: final(forced-flush ${if (stableFlush) "stable" else "energy"}) text=$text")
                    speechSeen = false
                    trailingSilenceMs = 0
                    lastPartial = ""
                    partialStableMs = 0
                    if (text.isNotEmpty()) return RecognitionResult.Hit(text)
                }
            }
        }
        diag("window: budget exhausted → Timeout")
        return RecognitionResult.Timeout
    }

    /** 块 RMS（16-bit PCM 标度）；恒 ≥1 防除零。 */
    private fun rmsOf(chunk: ShortArray, frames: Int): Double {
        var sum = 0.0
        for (i in 0 until frames) {
            val sample = chunk[i].toInt()
            sum += sample.toDouble() * sample
        }
        return maxOf(1.0, kotlin.math.sqrt(sum / frames))
    }

    /** 自适应增益：把弱信号归到目标电平（上限 [MAX_GAIN]×）；已达标的信号原样通过。 */
    private fun gainFactorFor(rawRms: Double): Float =
        (TARGET_RMS / rawRms).coerceIn(1.0, MAX_GAIN.toDouble()).toFloat()

    /** 增益就地放大（削波保护到 Short 值域）。 */
    private fun amplifyChunk(chunk: ShortArray, frames: Int, gain: Float) {
        if (gain <= 1.01f) return
        for (i in 0 until frames) {
            chunk[i] = (chunk[i] * gain).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }

    /** Vosk result JSON：{"text": "..."}；畸形 JSON / 缺键 → 空串（不产生命令语义）。 */
    private fun parseFinalText(hypothesis: String): String = runCatching {
        JSONObject(hypothesis).optString(FIELD_TEXT, "").trim()
    }.getOrDefault("")

    /** Vosk partial JSON：{"partial": "..."}；仅作冲刷裁决信号（命令语义只认 final 文本）。 */
    private fun parsePartialText(hypothesis: String): String = runCatching {
        JSONObject(hypothesis).optString(FIELD_PARTIAL, "").trim()
    }.getOrDefault("")

    private val diagLock = Any()

    /**
     * 窗口事件诊断文件（[filesDir]/[DIAG_FILE]，追加式，超 [DIAG_MAX_BYTES] 截断重开）：
     * vivo logd 间歇性整进程吞应用日志（2026-09-18 用户测试进程零日志）——logcat 之外的
     * 持久取证通道，debug 构建 `run-as` 可导出。仅记窗口级事件（每窗 ~10 行），不逐块写；
     * 写失败静默（诊断本身绝不影响识别路径）。
     */
    private fun diag(message: String) {
        runCatching {
            synchronized(diagLock) {
                val file = File(appContext.filesDir, DIAG_FILE)
                if (file.length() > DIAG_MAX_BYTES) file.writeText("")
                file.appendText("${System.currentTimeMillis()} $message\n")
            }
        }
    }

    init {
        diag("constructed: available=${_isAvailable.value}")
    }

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
                diag("model: unpack/load failed: ${e.message}")
                modelLoadFailed = true
                null
            }
        }
    }

    /** 建识别器（SHORT 端点模式：最短尾静默即分句，弱语音更快出终局）。构造失败 → null。 */
    private fun createRecognizerOrNull(model: Model): Recognizer? = try {
        Recognizer(model, SAMPLE_RATE).apply {
            setEndpointerMode(Recognizer.EndpointerMode.SHORT)
        }
    } catch (e: IOException) {
        android.util.Log.e("VB-Vosk", "Recognizer construct failed", e)
        null
    }

    /**
     * 建录音器：**MIC 原始源**（绕开 OEM VOICE_RECOGNITION 处理链——其降噪会把轻声/气音
     * 抹成纯静默，2026-09-18 vivo 04:41 实证）。创建失败/未初始化 → null 且拉低 isAvailable。
     */
    private fun createRecorderOrNull(): AudioRecord? {
        val minBufferBytes = AudioRecord.getMinBufferSize(
            SAMPLE_RATE.toInt(),
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val bufferBytes = maxOf(minBufferBytes, CHUNK_FRAMES * BYTES_PER_FRAME * 2)
        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE.toInt(),
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferBytes,
            )
        } catch (e: Exception) {
            android.util.Log.e("VB-Vosk", "AudioRecord construct failed", e)
            diag("recorder: construct failed: ${e.message}")
            null
        }
        if (recorder == null || recorder.state != AudioRecord.STATE_INITIALIZED) {
            // 权限缺失/麦克风占用/构造失败同按可用性级（拉低，进程内诚实降级）
            android.util.Log.e("VB-Vosk", "AudioRecord not initialized")
            diag("recorder: not initialized → Unavailable")
            _isAvailable.value = false
            return null
        }
        return recorder
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

    private companion object {
        /** Vosk 中文小模型（assets 目录名 = 模型名；StorageService.sync 按模型内 uuid 文件增量同步）。 */
        const val MODEL_ASSET_DIR: String = "vosk-model-small-cn-0.22"

        /** 解包目标目录（getExternalFilesDir 下应用专属目录，无需任何存储权限）。 */
        const val MODEL_TARGET_DIR: String = "models"

        /** 16kHz 单声道 PCM——Vosk small-cn 模型采样率。 */
        const val SAMPLE_RATE: Float = 16_000f

        /** Vosk result JSON 的文本字段。 */
        const val FIELD_TEXT: String = "text"

        /** Vosk partial JSON 的假设字段。 */
        const val FIELD_PARTIAL: String = "partial"

        /** 外层兜底超时宽限：正常由样本预算先终止循环；仅音频读取卡死等异常路径生效。 */
        const val OUTER_DEADLINE_GRACE_MS: Long = 2_000L

        /** 每块 50ms × 16 帧/ms = 800 帧（16-bit 单声道）。 */
        const val CHUNK_FRAMES: Int = 800
        const val CHUNK_MS: Int = 50
        const val SAMPLES_PER_MS: Int = 16
        const val BYTES_PER_FRAME: Int = 2

        /**
         * 语音能量门限（16-bit RMS 原始值）：高于此 = 单块有能量（用于自端点跟踪）。
         * 安静房间底噪典型 ~20–80，但**噪声尖峰**可达 150–204（2026-09-18 vivo 插桩静默窗
         * 实证）——故须配合 [SUSTAIN_ENERGY_CHUNKS] 持续判据，单块越限不武装。
         */
        const val SPEECH_RMS_FLOOR: Double = 120.0

        /**
         * 能量持续判据：连续 ≥3 块（150ms）越过 [SPEECH_RMS_FLOOR] 才武装冲刷跟踪——
         * 单块噪声尖峰既不武装、也不重置尾静默计数；真人声（含轻声）持续 ≥300ms。
         */
        const val SUSTAIN_ENERGY_CHUNKS: Int = 3

        /** 预增益目标电平与上限：弱块归一到目标（模型可解码区），最多放大 [MAX_GAIN] 倍。 */
        const val TARGET_RMS: Double = 2_500.0
        const val MAX_GAIN: Float = 10f

        /** 自端点尾静默：语音能量结束后静默满此时长即强制冲刷终局假设。 */
        const val FLUSH_SILENCE_MS: Int = 500

        /**
         * 假设稳定冲刷：模型已解出非空 partial 且连续无改进满此时长 → 强制终局——不依赖
         * 声学门限的第二冲刷触发（环境底噪持续越过能量门限时能量尾静默永不积累）。
         */
        const val PARTIAL_STABLE_MS: Int = 700

        /** 窗口诊断文件（filesDir 下）与其容量上限 / RMS 摘要周期。 */
        const val DIAG_FILE: String = "vosk_diag.log"
        const val DIAG_MAX_BYTES: Long = 512 * 1024
        const val DIAG_SUMMARY_MS: Int = 500
    }
}
