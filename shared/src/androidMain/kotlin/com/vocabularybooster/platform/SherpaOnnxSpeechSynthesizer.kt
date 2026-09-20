package com.vocabularybooster.platform

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import com.vocabularybooster.domain.model.Lang
import com.vocabularybooster.speech.Readiness
import com.vocabularybooster.speech.SegmentResult
import com.vocabularybooster.speech.SpeakRequest
import com.vocabularybooster.speech.SpeechSynthesizer
import com.vocabularybooster.speech.TtsVoice
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * SpeechSynthesizer 的神经 TTS Android actual（FR-23 v2，AUDIO_ENGINE_SPEC §8）：
 * sherpa-onnx + Piper VITS 模型（模型 / tokens 单文件 assets 直读；espeak-ng-data 目录树
 * 一次性解包至 filesDir——native 只认真实路径，`tools/tts-neural/fetch_deps.py` 产物）。
 *
 * - **受理范围**：仅 EN_US / EN_GB（ZH_CN 由 [LangRoutedSpeechSynthesizer] 转发系统 actual）；
 *   口音 = 模型（FR-22 消费时映射不变：段 lang=EN_GB 即选英音模型——设备无厂商英音包也生效）。
 * - **模型驻留**：同时最多一个英文模型（双模型 RAM 峰值不可接受）；换口音 = 先载新成功后
 *   release 旧；构造即后台预热美音模型，`readiness` 反映预热结果（失败进程内粘滞 → 路由器降级）。
 * - **合成与播放**：整段一次合成（`generate`——流式回调的 JNI 精确签名在本工具链不可达，
 *   见 speak 内注）后分块直写 AudioTrack（STREAM 模式，阻塞写即背压；缓冲 16KB + 不足段
 *   补静音至超缓冲——vivo mixer 对未填满缓冲的轨道不启动消费，vivo 实证）；阻塞写在 IO
 *   线程执行（编排器经 Main 调入）；float → 16-bit LE 单声道统一管线。
 * - **暂停/恢复语义**（ADR-09，同系统 TTS 段）：stop = 标记 + AudioTrack pause/flush，
 *   在途段以 completed=false 收场；恢复由编排器重读整段——本 actual 不保留播放位置。
 * - **磁盘缓存**：`filesDir/tts-cache/{sha256(lang|speed|text)}.wav`（16-bit PCM WAV，自写头）；
 *   命中免合成直播；LRU（mtime）上限 100MB。
 * - **rate/pitch**：rate → VITS speed（clamp 0.5–2.0）；**pitch 不生效**（VITS 无基频参数，平台限制）。
 * - **音频焦点**：播放前 AUDIOFOCUS_GAIN，任何丢失 → stop（编排器 Paused，同 Media3 行为）。
 * - **线程契约**：无主线程限定（native 合成串行于专用单线程 dispatcher；AudioTrack/焦点调用
 *   线程安全）；[stop] 可从任意线程调用。
 */
public class SherpaOnnxSpeechSynthesizer(
    context: Context,
) : SpeechSynthesizer {

    private val appContext: Context = context.applicationContext

    private val _readiness = MutableStateFlow<Readiness>(Readiness.INITIALIZING)
    override val readiness: StateFlow<Readiness> = _readiness

    /** native 串行线程：OfflineTts 单实例非并发安全，模型换载与合成同队列。 */
    private val nativeDispatcher = Dispatchers.IO.limitedParallelism(1)

    /** 串行 speak（合成 + 播放互斥）。 */
    private val speakMutex = Mutex()

    /** 预热 / 缓存写入 / 清理的后台作用域（nativeDispatcher 单线程）。 */
    private val scope = CoroutineScope(SupervisorJob() + nativeDispatcher)

    @Volatile private var released = false
    @Volatile private var stopRequested = false

    /** 当前驻留模型（nativeDispatcher 线程限定读写；对外只读快照经返回值传递）。 */
    private var activeLang: Lang? = null
    private var activeTts: OfflineTts? = null

    @Volatile private var track: AudioTrack? = null

    private val audioManager: AudioManager =
        appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    @Volatile private var focusRequest: AudioFocusRequest? = null

    private val cacheDir: File = File(appContext.filesDir, CACHE_DIR_NAME)

    /** espeak-ng-data 解包目标（piper phonemize 只认真实文件系统路径，见 [ensureEspeakData]）。 */
    private val espeakDir: File = File(appContext.filesDir, ESPEAK_DIR_NAME)

    init {
        scope.launch {
            val t0 = System.currentTimeMillis()
            _readiness.value =
                if (runCatching { modelFor(Lang.EN_US) }.isSuccess) Readiness.READY
                else Readiness.UNAVAILABLE
            diag("prewarm result=${_readiness.value} ms=${System.currentTimeMillis() - t0}")
        }
        scope.launch { runCatching { trimCache() } }
    }

    override suspend fun speak(request: SpeakRequest): SegmentResult = speakMutex.withLock {
        ensureNotReleased()
        if (request.lang == Lang.ZH_CN) {
            throw IllegalStateException("SherpaOnnxSpeechSynthesizer 仅受理英文段（ZH_CN 走系统 TTS）")
        }
        awaitReady()
        stopRequested = false

        val speed = request.rate.coerceIn(MIN_SPEED, MAX_SPEED)
        val cacheFile = cacheFileFor(request.lang, request.text, speed)
        val cached = readCache(cacheFile)
        diag("speak lang=${request.lang} len=${request.text.length} speed=$speed cache=${cached != null}")

        acquireFocus()
        var localTrack: AudioTrack? = null
        try {
            val model = if (cached == null) modelFor(request.lang) else null
            val sampleRate = cached?.second ?: model!!.sampleRate()

            val data: ByteArray
            if (cached != null) {
                data = cached.first
            } else {
                val audio = withContext(nativeDispatcher) {
                    // 整段一次合成（非流式回调）：native 对回调按精确签名
                    // invoke([F)Ljava/lang/Integer; 查找（jni/offline-tts.cc CallCallback），
                    // Kotlin lambda 经 kotlinc invokedynamic + D8 脱糖后只剩擦除签名 →
                    // NoSuchMethodError 全进程崩（vivo 2026-09-20 装机实证）；该桥只能用 Java
                    // 源声明而 KMP androidMain 不支持 Kotlin→Java 同模块解析 → 弃流式。
                    // 段文本短 + 磁盘缓存弥补首播延迟；stop 期合成在后台自然完成（仅浪费少量 CPU）。
                    model!!.generate(text = request.text, speed = speed)
                }
                diag("generated samples=${audio.samples.size} rate=${audio.sampleRate} ms=${audio.samples.size * 1000 / audio.sampleRate}")
                data = audio.samples.toPcm16Le()
                // 后台写缓存（失败仅记忽略——缓存是优化非正确性）
                scope.launch {
                    runCatching {
                        writeCache(cacheFile, audio.samples, audio.sampleRate)
                        trimCache()
                    }
                }
            }

            val newTrack = buildTrack(sampleRate)
            localTrack = newTrack
            track = newTrack
            var writtenShorts = 0L
            /** 写一块 PCM（阻塞写即背压；stopRequested 即弃写）。 */
            fun writeBytes(bytes: ByteArray) {
                var offset = 0
                while (offset < bytes.size && !stopRequested) {
                    val n = newTrack.write(bytes, offset, bytes.size - offset, AudioTrack.WRITE_BLOCKING)
                    if (n <= 0) throw IllegalStateException("AudioTrack write 失败（result=$n）")
                    offset += n
                }
                writtenShorts += offset / BYTES_PER_SHORT
            }
            withContext(Dispatchers.IO) {
                // vivo HAL（装机实证 2026-09-20，audio_flinger track Flushed=全部帧）：STREAM
                // 轨道只有在缓冲被填满 / 出现阻塞写后才开始被 mixer 消费——整段数据小于缓冲
                // 容量的段（如单词拼读）填到 66% 也不被启动，纯静音 30s 后被 flush 丢弃。
                // 对策：缓冲缩至 16KB，且不足一缓冲的段补静音至超出缓冲（必然填满 + 阻塞写
                // 各至少一次）；补静音 ≤0.46s 尾巴仅落在 <16KB 的微型段。阻塞写在 IO 线程执行
                //（编排器经 Main 调 speak，大段可写数秒）。
                val first = minOf(data.size, WRITE_CHUNK_BYTES)
                writeBytes(data.copyOfRange(0, first))
                newTrack.play()
                var offset = first
                while (offset < data.size && !stopRequested) {
                    val len = minOf(data.size - offset, WRITE_CHUNK_BYTES)
                    writeBytes(data.copyOfRange(offset, offset + len))
                    offset += len
                }
                val padNeeded = TRACK_BUFFER_BYTES + PAD_EXTRA_BYTES - writtenShorts.toInt() * BYTES_PER_SHORT
                if (padNeeded > 0 && !stopRequested) {
                    writeBytes(ByteArray(padNeeded))
                }
            }
            diag("write bytes=${data.size} writtenShorts=$writtenShorts stop=$stopRequested")

            val drainStart = System.currentTimeMillis()
            val completed = awaitDrain(newTrack, writtenShorts)
            diag(
                "drain completed=$completed head=${newTrack.playbackHeadPosition} " +
                    "written=$writtenShorts ms=${System.currentTimeMillis() - drainStart} stop=$stopRequested",
            )
            SegmentResult(utteranceId = request.utteranceId, completed = completed)
        } finally {
            track = null
            localTrack?.run { runCatching { pause(); flush(); release() } }
            abandonFocus()
        }
    }

    /** 立即停止：标记中止（native 回调返回 1）+ AudioTrack pause/flush（在途段 completed=false）。 */
    override fun stop() {
        diag("stop invoked")
        stopRequested = true
        track?.run { runCatching { pause(); flush() } }
    }

    /** 释放（宿主销毁）：stop + 释放模型；此后 speak 抛异常、readiness=UNAVAILABLE。 */
    public fun release() {
        if (released) return
        released = true
        stop()
        scope.cancel()
        runCatching { activeTts?.release() }
        activeTts = null
        activeLang = null
        _readiness.value = Readiness.UNAVAILABLE
    }

    /** FR-19 兼容：每口音单条神经 voice（id=模型名；实际模型选择由段 lang 决定）。 */
    override fun availableVoices(lang: Lang): List<TtsVoice> = when (lang) {
        Lang.EN_US -> listOf(
            TtsVoice(id = "piper-en_US-lessac-medium", displayName = "lessac（神经）", qualityLabel = "高"),
        )
        Lang.EN_GB -> listOf(
            TtsVoice(id = "piper-en_GB-alan-medium", displayName = "alan（神经）", qualityLabel = "高"),
        )
        Lang.ZH_CN -> emptyList()
    }

    // ---- 模型管理（nativeDispatcher 串行）----

    /** 取当前口音的模型：不匹配则先载新、成功后释放旧（加载失败抛异常，旧模型保持不动）。 */
    private suspend fun modelFor(lang: Lang): OfflineTts = withContext(nativeDispatcher) {
        activeTts?.takeIf { activeLang == lang }?.let { return@withContext it }
        val tts = createTts(lang)
        activeTts?.release()
        activeTts = tts
        activeLang = lang
        tts
    }

    private fun createTts(lang: Lang): OfflineTts {
        val (dir, onnx) = when (lang) {
            Lang.EN_US -> "$ASSET_ROOT/en_US" to "en_US-lessac-medium.onnx"
            Lang.EN_GB -> "$ASSET_ROOT/en_GB" to "en_GB-alan-medium.onnx"
            Lang.ZH_CN -> throw IllegalStateException("无中文神经模型（ZH_CN 走系统 TTS）")
        }
        val config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(
                    model = "$dir/$onnx",
                    tokens = "$dir/tokens.txt",
                    dataDir = ensureEspeakData().absolutePath,
                ),
                numThreads = NUM_THREADS,
            ),
        )
        return OfflineTts(appContext.assets, config)
    }

    // ---- espeak-ng-data 解包（vivo 2026-09-20 装机实证驱动）----
    // sherpa native 对 piper 的 dataDir 只认真实文件系统路径：模型 / tokens（单文件）可 assets
    // 直读，espeak-ng-data（355 文件目录树）不可——传 assets 路径时 phonemize 失败并内部
    // exit(-1) 拖崩全进程（hwuiTask 撞已销毁 mutex 的 FORTIFY SIGABRT）。故一次性解包到
    // filesDir，完成标记增量复用；解包失败抛异常 → 预热失败粘滞 → 路由器降级系统 TTS（不崩）。
    private fun ensureEspeakData(): File {
        val marker = File(espeakDir, ESPEAK_MARKER)
        if (espeakDir.isDirectory && marker.isFile) return espeakDir
        espeakDir.deleteRecursively()
        espeakDir.mkdirs()
        check(espeakDir.isDirectory) { "espeak-ng-data 目录创建失败：$espeakDir" }
        copyAssetsTree(ESPEAK_ASSET_DIR, espeakDir)
        marker.writeText("ok")
        return espeakDir
    }

    private fun copyAssetsTree(assetPath: String, target: File) {
        val children = appContext.assets.list(assetPath).orEmpty()
        if (children.isEmpty()) {
            appContext.assets.open(assetPath).use { input ->
                target.outputStream().use { input.copyTo(it) }
            }
            return
        }
        check(target.isDirectory || target.mkdirs()) { "目录创建失败：$target" }
        for (child in children) {
            copyAssetsTree("$assetPath/$child", File(target, child))
        }
    }

    private suspend fun awaitReady() {
        when (_readiness.value) {
            Readiness.READY -> Unit
            Readiness.UNAVAILABLE ->
                throw IllegalStateException("神经 TTS 模型初始化失败（进程内粘滞，已由路由器降级）")
            Readiness.INITIALIZING -> {
                val state = withTimeoutOrNull(PREWARM_TIMEOUT_MS) {
                    readiness.first { it != Readiness.INITIALIZING }
                }
                if (state != Readiness.READY) {
                    throw IllegalStateException("神经 TTS 模型预加载超时（${PREWARM_TIMEOUT_MS}ms）或失败")
                }
            }
        }
    }

    /** 排空等待：播放头追平已写样本即完成；stopRequested 优先判（flush 会清零播放头）。 */
    private suspend fun awaitDrain(audioTrack: AudioTrack, writtenShorts: Long): Boolean {
        val deadline = System.currentTimeMillis() + DRAIN_TIMEOUT_MS
        while (true) {
            if (stopRequested) return false
            if (audioTrack.playbackHeadPosition >= writtenShorts) return true
            if (System.currentTimeMillis() > deadline) return true // 兜底不挂死（按完成处理）
            delay(DRAIN_POLL_MS)
        }
    }

    // ---- 磁盘缓存（16-bit PCM WAV，自写头；命中免合成）----

    private fun cacheFileFor(lang: Lang, text: String, speed: Float): File {
        val key = MessageDigest.getInstance("SHA-256")
            .digest("${lang.tag}|${String.format(Locale.ROOT, "%.2f", speed)}|$text".toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(cacheDir, "$key.wav")
    }

    private fun readCache(file: File): Pair<ByteArray, Int>? = runCatching {
        if (!file.isFile) return null
        val bytes = file.readBytes()
        if (bytes.size <= WAV_HEADER_BYTES || !bytes.sliceArray(0 until 4).contentEquals("RIFF".toByteArray()) ||
            !bytes.sliceArray(36 until 40).contentEquals("data".toByteArray())
        ) {
            return null
        }
        val channels = readLeU16(bytes, 22)
        val bitsPerSample = readLeU16(bytes, 34)
        val dataLen = readLeInt(bytes, 40)
        if (channels != 1 || bitsPerSample != 16 || dataLen <= 0 || 44 + dataLen > bytes.size) return null
        bytes.copyOfRange(WAV_HEADER_BYTES, WAV_HEADER_BYTES + dataLen) to readLeInt(bytes, 24)
    }.getOrNull()

    private fun writeCache(file: File, samples: FloatArray, sampleRate: Int) {
        val pcm = samples.toPcm16Le()
        file.parentFile?.mkdirs()
        file.writeBytes(wavHeader(pcm.size, sampleRate) + pcm)
    }

    /** LRU（mtime 升序淘汰）至 90% 上限以下。 */
    private fun trimCache() {
        val files = cacheDir.listFiles { f -> f.isFile } ?: return
        var total = files.sumOf { it.length() }
        if (total <= MAX_CACHE_BYTES) return
        files.sortedBy { it.lastModified() }.forEach {
            if (total <= MAX_CACHE_BYTES * 9 / 10) return
            if (it.delete()) total -= it.length()
        }
    }

    // ---- 音频管线 ----

    private fun buildTrack(sampleRate: Int): AudioTrack =
        AudioTrack.Builder()
            .setAudioAttributes(PLAYBACK_ATTRIBUTES)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            // 16KB（≈0.37s @22050）而非 1s：缓冲必须小于常见段数据量，否则小段永远填不满
            // → vivo mixer 不启动消费（见 speak 内补静音注）
            .setBufferSizeInBytes(TRACK_BUFFER_BYTES)
            .build()

    private fun acquireFocus() {
        if (focusRequest != null) return
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(PLAYBACK_ATTRIBUTES)
            .setOnAudioFocusChangeListener { change ->
                // 任何丢失（瞬时/闪避/永久）→ stop：在途段 completed=false → 编排器 Paused（同 Media3）
                if (change != AudioManager.AUDIOFOCUS_GAIN) {
                    diag("focus lost change=$change")
                    stop()
                }
            }
            .build()
        if (audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            focusRequest = request
        } else {
            diag("focus DENIED")
        }
    }

    private fun abandonFocus() {
        focusRequest?.let { runCatching { audioManager.abandonAudioFocusRequest(it) } }
        focusRequest = null
    }

    private fun ensureNotReleased() {
        check(!released) { "SherpaOnnxSpeechSynthesizer 已 release，禁止继续调用" }
    }

    /** 诊断文件（验收期定位用，同 vosk_diag.log 先例）：vivo logd 间歇吞进程日志的取证对冲。 */
    private fun diag(message: String) {
        runCatching {
            val line = "${System.currentTimeMillis()} $message\n"
            val file = File(appContext.filesDir, DIAG_FILE)
            if (file.length() > MAX_DIAG_BYTES) {
                file.writeText(line)
            } else {
                file.appendText(line)
            }
        }
    }

    private companion object {
        const val ASSET_ROOT: String = "tts/piper"
        const val ESPEAK_ASSET_DIR: String = "$ASSET_ROOT/espeak-ng-data"
        const val ESPEAK_DIR_NAME: String = "tts/espeak-ng-data"
        const val ESPEAK_MARKER: String = ".complete"
        const val CACHE_DIR_NAME: String = "tts-cache"
        const val NUM_THREADS: Int = 4 // 非流式整段合成——多线程降低首播延迟
        const val MIN_SPEED: Float = 0.5f
        const val MAX_SPEED: Float = 2.0f
        const val PREWARM_TIMEOUT_MS: Long = 15_000L
        const val DRAIN_TIMEOUT_MS: Long = 30_000L
        const val DRAIN_POLL_MS: Long = 20L
        const val WAV_HEADER_BYTES: Int = 44
        const val BYTES_PER_SHORT: Int = 2
        const val WRITE_CHUNK_BYTES: Int = 16 * 1024
        const val TRACK_BUFFER_BYTES: Int = WRITE_CHUNK_BYTES // 轨道缓冲=一块（见 buildTrack 注）
        const val PAD_EXTRA_BYTES: Int = 4 * 1024 // 补静音超出缓冲的余量（见 speak 内注）
        const val MAX_CACHE_BYTES: Long = 100L * 1024 * 1024
        const val DIAG_FILE: String = "tts_diag.log"
        const val MAX_DIAG_BYTES: Long = 512L * 1024

        val PLAYBACK_ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
    }
}

/** float [-1,1] → 16-bit 小端单声道字节（统一播放与缓存管线）。 */
private fun FloatArray.toPcm16Le(): ByteArray {
    val out = ByteArray(size * 2)
    var i = 0
    for (v in this) {
        val sample = (v.coerceIn(-1f, 1f) * 32767f).toInt()
        out[i++] = (sample and 0xFF).toByte()
        out[i++] = ((sample shr 8) and 0xFF).toByte()
    }
    return out
}

/** 规范单声道 16-bit WAV 头（44 字节；只写只读自家缓存，不做通用解析）。 */
private fun wavHeader(dataLen: Int, sampleRate: Int): ByteArray {
    val byteRate = sampleRate * 2
    val header = ByteArray(44)
    fun le16(offset: Int, value: Int) {
        header[offset] = (value and 0xFF).toByte()
        header[offset + 1] = ((value shr 8) and 0xFF).toByte()
    }
    fun le32(offset: Int, value: Int) {
        header[offset] = (value and 0xFF).toByte()
        header[offset + 1] = ((value shr 8) and 0xFF).toByte()
        header[offset + 2] = ((value shr 16) and 0xFF).toByte()
        header[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }
    "RIFF".toByteArray().copyInto(header, 0)
    le32(4, 36 + dataLen)
    "WAVE".toByteArray().copyInto(header, 8)
    "fmt ".toByteArray().copyInto(header, 12)
    le32(16, 16)
    le16(20, 1) // PCM
    le16(22, 1) // mono
    le32(24, sampleRate)
    le32(28, byteRate)
    le16(32, 2) // block align
    le16(34, 16) // bits per sample
    "data".toByteArray().copyInto(header, 36)
    le32(40, dataLen)
    return header
}

private fun readLeU16(bytes: ByteArray, offset: Int): Int =
    (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

private fun readLeInt(bytes: ByteArray, offset: Int): Int =
    readLeU16(bytes, offset) or (readLeU16(bytes, offset + 2) shl 16)
