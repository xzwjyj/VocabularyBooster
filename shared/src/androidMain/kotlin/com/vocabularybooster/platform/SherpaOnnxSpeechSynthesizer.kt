package com.vocabularybooster.platform

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsZipVoiceModelConfig
import com.vocabularybooster.domain.model.Lang
import com.vocabularybooster.domain.repository.LearningSettingsRepository
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
 * SpeechSynthesizer 的神经 TTS Android actual（FR-23 v2 + FR-24，AUDIO_ENGINE_SPEC §8）：
 * sherpa-onnx 双模型驻留——EN 族 = Piper VITS（口音 = 模型，FR-22 映射不变），ZH_CN =
 * zipvoice-distill-int8-zh-en-emilia（24kHz，encoder→decoder→vocos 零样本管线，flow-based；
 * melo 人耳否决（「读句子节奏不自然」）后 2026-09-21 换 zipvoice 复验）。
 * EN 槽 = piper 模型单文件 assets 直读（newFromAsset 混合路径，装机实证），ZH 槽 = zipvoice
 * 整槽 newFromFile——全部输入（encoder / decoder / vocos / tokens / lexicon + espeak-ng-data）
 * 一次性解包至 filesDir 绝对路径：sherpa native 对目录树只认真实文件系统路径，且 lexicon
 * 读取器在 assetManager 非空时一律走 assets 分支、绝对路径读失败 → native exit(-1) 拖崩全进程
 *（2026-09-20 vivo 装机坑 #5）。
 *
 * - **受理范围**：EN_US / EN_GB / ZH_CN。
 * - **模型驻留（按语言族双槽）**：EN 族槽内换口音 = 先载新成功后 release 旧；ZH 槽 = zipvoice
 *   单模型无换载；两槽各自独立单线程 dispatcher（预热并行、合成互不阻塞）。构造即并行预热
 *   双模型，`readiness` 整体口径：全部落定后任一成功即 READY、双败才 UNAVAILABLE——失败槽
 *   的 speak 抛异常由 [LangRoutedSpeechSynthesizer] 按语言族降级，两族互不牵连。
 * - **合成与播放**：zipvoice 走 `generateWithConfig`（带 referenceAudio 零样本 prompt），piper
 *   走 `generate`；整段合成后分块直写 AudioTrack（STREAM 模式，阻塞写即背压；缓冲 16KB +
 *   不足段补静音至超缓冲——vivo mixer 对未填满缓冲的轨道不启动消费，vivo 实证）；阻塞写在
 *   IO 线程执行（编排器经 Main 调入）；float → 16-bit LE 单声道统一管线；采样率按模型取自
 *   OfflineTts 实例（piper 22050 / zipvoice 24000）并随 WAV 缓存头往返。
 * - **中文音色**：zipvoice 零样本——每次合成需 referenceAudio + referenceText（prompt.wav）；
 *   `availableVoices(ZH_CN)` 恒一枚（zipvoice 零样本不限音色，固定用内置 prompt）。
 * - **暂停/恢复语义**（ADR-09，同系统 TTS 段）：stop = 标记 + AudioTrack pause/flush，
 *   在途段以 completed=false 收场；恢复由编排器重读整段——本 actual 不保留播放位置。
 * - **磁盘缓存**：`filesDir/tts-cache/{sha256(lang[|voice]|speed|text)}.wav`（16-bit PCM WAV，
 *   自写头）；命中免合成直播；LRU（mtime）上限 100MB。
 * - **rate/pitch**：rate → speed（clamp 0.5–2.0，两模型同参语义）；**pitch 不生效**（两模型均
 *   无基频参数，平台限制）。
 * - **音频焦点**：播放前 AUDIOFOCUS_GAIN，任何丢失 → stop（编排器 Paused，同 Media3 行为）。
 * - **线程契约**：无主线程限定（native 合成按模型串行于各自单线程 dispatcher；AudioTrack/焦点
 *   调用线程安全）；[stop] 可从任意线程调用。
 */
public class SherpaOnnxSpeechSynthesizer(
    context: Context,
    private val settings: LearningSettingsRepository,
) : SpeechSynthesizer {

    private val appContext: Context = context.applicationContext

    private val _readiness = MutableStateFlow<Readiness>(Readiness.INITIALIZING)
    override val readiness: StateFlow<Readiness> = _readiness

    /** native 串行线程（按模型族各一）：OfflineTts 单实例非并发安全，换载与合成同队列。 */
    private val piperDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val zipvoiceDispatcher = Dispatchers.IO.limitedParallelism(1)

    /** 串行 speak（合成 + 播放互斥——会话段按序播放，跨引擎也无需并发）。 */
    private val speakMutex = Mutex()

    /** 预热 / 缓存写入 / 清理的后台作用域。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var released = false
    @Volatile private var stopRequested = false

    /** piper 槽（piperDispatcher 线程限定读写；对外只读快照经返回值传递）。 */
    private var piperLang: Lang? = null
    private var piperTts: OfflineTts? = null

    /** zipvoice 槽（zipvoiceDispatcher 线程限定）。 */
    private var zipvoiceTts: OfflineTts? = null

    /** 预热结果（进程内粘滞）：失败槽 speak 直接抛，避免每段重试加载。 */
    @Volatile private var piperWarmupFailed = false
    @Volatile private var zipvoiceWarmupFailed = false
    private val warmupLock = Any()
    private var warmupPending = WARMUP_JOBS

    @Volatile private var track: AudioTrack? = null

    private val audioManager: AudioManager =
        appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    @Volatile private var focusRequest: AudioFocusRequest? = null

    private val cacheDir: File = File(appContext.filesDir, CACHE_DIR_NAME)

    /** piper espeak-ng-data 解包目标（phonemize 只认真实文件系统路径，见 [ensureUnpacked]）。 */
    private val espeakDir: File = File(appContext.filesDir, ESPEAK_DIR_NAME)

    /** zipvoice 数据解包根（encoder / decoder / vocos / tokens / lexicon / espeak-ng-data / prompt.wav）。 */
    private val zipvoiceDataDir: File = File(appContext.filesDir, ZIPVOICE_DATA_DIR_NAME)

    /** zipvoice 参考音色（news-female.wav，6.8s 新闻女声）。 */
    private var zipvoicePrompt: FloatArray? = null
    private var zipvoicePromptSampleRate: Int = 0

    /** ZH 音色条目（zipvoice 零样本 → 恒一枚；id 兼具 WAV 缓存键隔离作用）。 */
    private data class ZhVoice(val id: String, val displayName: String)

    private val zhVoices = listOf(
        ZhVoice(id = "zipvoice-zh", displayName = "ZipVoice（神经·女）"),
    )

    init {
        val t0 = System.currentTimeMillis()
        // 双模型并行预热（各自 dispatcher，互不阻塞）；全部落定后 readiness 才离开 INITIALIZING
        scope.launch { warmupSettle(runCatching { modelFor(Lang.EN_US) }.isSuccess, en = true, t0) }
        scope.launch { warmupSettle(runCatching { modelFor(Lang.ZH_CN) }.isSuccess, en = false, t0) }
        scope.launch { runCatching { trimCache() } }
        // melo 批次装机遗留解包目录回收（~191MB；被否决模型不再使用）
        scope.launch {
            runCatching { File(appContext.filesDir, LEGACY_MELO_DATA_DIR).deleteRecursively() }
        }
    }

    /** 预热落定汇合点：两作业全落定 → 任一成功 READY / 双败 UNAVAILABLE（失败槽先置粘滞标记）。 */
    private fun warmupSettle(ok: Boolean, en: Boolean, t0: Long) {
        synchronized(warmupLock) {
            if (!ok) {
                if (en) piperWarmupFailed = true else zipvoiceWarmupFailed = true
            }
            warmupPending--
            if (warmupPending == 0) {
                _readiness.value =
                    if (piperWarmupFailed && zipvoiceWarmupFailed) Readiness.UNAVAILABLE
                    else Readiness.READY
                diag(
                    "prewarm en=${!piperWarmupFailed} zh=${!zipvoiceWarmupFailed} " +
                        "result=${_readiness.value} ms=${System.currentTimeMillis() - t0}",
                )
            }
        }
    }

    override suspend fun speak(request: SpeakRequest): SegmentResult = speakMutex.withLock {
        ensureNotReleased()
        awaitReady()
        stopRequested = false

        val speed = request.rate.coerceIn(MIN_SPEED, MAX_SPEED)
        // 中文音色：zipvoice 零样本固定用内置 prompt；voice id 进缓存键隔离
        val voiceId: String? = if (request.lang == Lang.ZH_CN) {
            zhVoices.first().id
        } else {
            null
        }
        val engineTag = if (request.lang == Lang.ZH_CN) "zipvoice" else "vits"
        val cacheFile = cacheFileFor(request.lang, request.text, speed, voiceId)
        val cached = readCache(cacheFile)
        diag(
            "speak lang=${request.lang} engine=$engineTag " +
                "len=${request.text.length} speed=$speed cache=${cached != null}",
        )

        acquireFocus()
        var localTrack: AudioTrack? = null
        try {
            val model = if (cached == null) modelFor(request.lang) else null
            val sampleRate = cached?.second ?: model!!.sampleRate()

            val data: ByteArray
            if (cached != null) {
                data = cached.first
            } else {
                val audio = withContext(dispatcherFor(request.lang)) {
                    // zipvoice 走 generateWithConfig（带 referenceAudio 零样本 prompt）；piper 走 generate。
                    // 非流式回调：native 对回调按精确签名 invoke([F)Ljava/lang/Integer; 查找
                    //（jni/offline-tts.cc CallCallback），Kotlin lambda 经 kotlinc invokedynamic + D8
                    // 脱糖后只剩擦除签名 → NoSuchMethodError 全进程崩（vivo 2026-09-20 装机实证）；
                    // 该桥只能用 Java 源声明而 KMP androidMain 不支持 Kotlin→Java 同模块解析 → 弃流式。
                    // 段文本短 + 磁盘缓存弥补首播延迟；stop 期合成在后台自然完成（仅浪费少量 CPU）。
                    if (request.lang == Lang.ZH_CN) {
                        val prompt = loadZipvoicePrompt()
                        val genConfig = GenerationConfig(
                            referenceAudio = prompt.first,
                            referenceSampleRate = prompt.second,
                            referenceText = ZIPVOICE_PROMPT_TEXT,
                            numSteps = 4,
                        )
                        model!!.generateWithConfig(text = request.text, config = genConfig)
                    } else {
                        model!!.generate(text = request.text, sid = 0, speed = speed)
                    }
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
                // 容量的段（如单词拼读）填到 66% 也不被被启动，纯静音 30s 后被 flush 丢弃。
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

    /** 释放（宿主销毁）：stop + 释放双模型；此后 speak 抛异常、readiness=UNAVAILABLE。 */
    public fun release() {
        if (released) return
        released = true
        stop()
        scope.cancel()
        runCatching { piperTts?.release() }
        runCatching { zipvoiceTts?.release() }
        piperTts = null
        piperLang = null
        zipvoiceTts = null
        _readiness.value = Readiness.UNAVAILABLE
    }

    /** FR-19 兼容：EN 每口音单条神经 voice（id=模型名，实际模型由段 lang 决定）；ZH = zipvoice 零样本。 */
    override fun availableVoices(lang: Lang): List<TtsVoice> = when (lang) {
        Lang.EN_US -> listOf(
            TtsVoice(id = "piper-en_US-lessac-medium", displayName = "lessac（神经）", qualityLabel = "高"),
        )
        Lang.EN_GB -> listOf(
            TtsVoice(id = "piper-en_GB-alan-medium", displayName = "alan（神经）", qualityLabel = "高"),
        )
        Lang.ZH_CN -> zhVoices.map { TtsVoice(id = it.id, displayName = it.displayName, qualityLabel = "高") }
    }

    // ---- 模型管理（各自 dispatcher 串行）----

    /** 取该语言的驻留模型：piper 槽内换口音先载新成功后释旧（失败抛异常，旧模型保持不动）。 */
    private suspend fun modelFor(lang: Lang): OfflineTts = when (lang) {
        Lang.ZH_CN -> withContext(zipvoiceDispatcher) {
            check(!zipvoiceWarmupFailed) { "zipvoice 模型预热失败（进程内粘滞，已由路由器降级）" }
            zipvoiceTts?.let { return@withContext it }
            val tts = createZipvoiceTts()
            zipvoiceTts?.release() // 防御（单模型无换载，正常不达）
            zipvoiceTts = tts
            tts
        }
        Lang.EN_US, Lang.EN_GB -> withContext(piperDispatcher) {
            check(!piperWarmupFailed) { "piper 模型预热失败（进程内粘滞，已由路由器降级）" }
            piperTts?.takeIf { piperLang == lang }?.let { return@withContext it }
            val tts = createPiperTts(lang)
            piperTts?.release()
            piperTts = tts
            piperLang = lang
            tts
        }
    }

    private fun dispatcherFor(lang: Lang) = if (lang == Lang.ZH_CN) zipvoiceDispatcher else piperDispatcher

    private fun createPiperTts(lang: Lang): OfflineTts {
        val (dir, onnx) = when (lang) {
            Lang.EN_US -> "$ASSET_ROOT/en_US" to "en_US-lessac-medium.onnx"
            Lang.EN_GB -> "$ASSET_ROOT/en_GB" to "en_GB-alan-medium.onnx"
            Lang.ZH_CN -> throw IllegalStateException("ZH_CN 走 zipvoice 槽")
        }
        val config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(
                    model = "$dir/$onnx",
                    tokens = "$dir/tokens.txt",
                    dataDir = ensureUnpacked(ESPEAK_ASSET_DIR, espeakDir).absolutePath,
                ),
                numThreads = NUM_THREADS,
            ),
        )
        return OfflineTts(appContext.assets, config)
    }

    /** melo vits-zh_en（44.1kHz / 单说话人；zh+en 混合 lexicon）：整槽 newFromFile 全绝对路径。 */
    /** zipvoice：encoder→decoder→vocos 零样本管线。 */
    private fun createZipvoiceTts(): OfflineTts {
        // 与 melo 同理：sherpa native 对目录树只认真实文件系统路径 → 整槽 newFromFile。
        val data = zipvoiceDataDir.apply { mkdirs() }
        val encoder = ensureUnpacked("$ZIPVOICE_ASSET_ROOT/encoder.int8.onnx", File(data, "encoder.int8.onnx"))
        val decoder = ensureUnpacked("$ZIPVOICE_ASSET_ROOT/decoder.int8.onnx", File(data, "decoder.int8.onnx"))
        val vocos = ensureUnpacked("$ZIPVOICE_ASSET_ROOT/vocos_24khz.onnx", File(data, "vocos_24khz.onnx"))
        val tokens = ensureUnpacked("$ZIPVOICE_ASSET_ROOT/tokens.txt", File(data, "tokens.txt"))
        val lexicon = ensureUnpacked("$ZIPVOICE_ASSET_ROOT/lexicon.txt", File(data, "lexicon.txt"))
        val espeak = ensureUnpacked("$ZIPVOICE_ASSET_ROOT/espeak-ng-data", File(data, "espeak-ng-data"))
        // 加载参考音色（news-female.wav，6.8s 新闻女声）
        loadZipvoicePromptAssets()
        val config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                zipvoice = OfflineTtsZipVoiceModelConfig(
                    tokens = tokens.absolutePath,
                    encoder = encoder.absolutePath,
                    decoder = decoder.absolutePath,
                    vocoder = vocos.absolutePath,
                    dataDir = espeak.absolutePath,
                    lexicon = lexicon.absolutePath,
                ),
                numThreads = NUM_THREADS,
            ),
        )
        val tts = OfflineTts(assetManager = null, config = config)
        diag("zipvoice loaded speakers=${tts.numSpeakers()} rate=${tts.sampleRate()}")
        return tts
    }

    /** 从 assets 加载 zipvoice 参考音色到内存（只加载一次）。 */
    private fun loadZipvoicePromptAssets() {
        if (zipvoicePrompt != null) return
        val promptFile = ensureUnpacked("$ZIPVOICE_ASSET_ROOT/prompt.wav", File(zipvoiceDataDir, "prompt.wav"))
        val bytes = promptFile.readBytes()
        // 解析 WAV：44 字节头 + data
        if (bytes.size < 44) return
        val sampleRate = bytes[24].toInt() and 0xFF or ((bytes[25].toInt() and 0xFF) shl 8) or
            ((bytes[26].toInt() and 0xFF) shl 16) or ((bytes[27].toInt() and 0xFF) shl 24)
        val dataSize = bytes[40].toInt() and 0xFF or ((bytes[41].toInt() and 0xFF) shl 8) or
            ((bytes[42].toInt() and 0xFF) shl 16) or ((bytes[43].toInt() and 0xFF) shl 24)
        if (dataSize <= 0 || 44 + dataSize > bytes.size) return
        val pcm = ShortArray(dataSize / 2)
        var i = 0
        for (offset in 44 until 44 + dataSize step 2) {
            val sample = bytes[offset].toInt() and 0xFF or ((bytes[offset + 1].toInt() and 0xFF) shl 8)
            pcm[i++] = sample.toShort()
        }
        // Short [-32768,32767] → Float [-1,1]
        zipvoicePrompt = FloatArray(pcm.size) { pcm[it] / 32768f }
        zipvoicePromptSampleRate = sampleRate
        diag("zipvoice prompt loaded samples=${zipvoicePrompt!!.size} rate=$sampleRate")
    }

    /** 取已加载的 prompt（FloatArray + sampleRate）。 */
    private fun loadZipvoicePrompt(): Pair<FloatArray, Int> {
        check(zipvoicePrompt != null) { "zipvoice prompt 未加载" }
        return zipvoicePrompt!! to zipvoicePromptSampleRate
    }

    // ---- assets 解包（vivo 2026-09-20 装机实证驱动）----
    // sherpa native 对目录树（piper espeak-ng-data、melo jieba dict）与逗号列表只认真实
    // 文件系统路径：传 assets 路径时 phonemize 失败并内部 exit(-1) 拖崩全进程（hwuiTask
    // 撞已销毁 mutex 的 FORTIFY SIGABRT）。故一次性解包到 filesDir；完成标记（<target>.complete，
    // 目标邻侧——单文件与目录树统一口径）增量复用；解包失败抛异常 → 预热失败粘滞 →
    // 路由器降级系统 TTS（不崩）。
    private fun ensureUnpacked(assetPath: String, target: File): File {
        val marker = File(target.parentFile, target.name + UNPACK_MARKER_SUFFIX)
        if (marker.isFile && (target.isDirectory || target.isFile)) return target
        target.deleteRecursively()
        target.parentFile?.let { check(it.isDirectory || it.mkdirs()) { "目录创建失败：$it" } }
        copyAssetsTree(assetPath, target)
        marker.writeText("ok")
        return target
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

    private fun cacheFileFor(lang: Lang, text: String, speed: Float, voiceKey: String?): File {
        // voiceKey：ZH 段音色 id——切换音色不得命中旧缓存；EN 无音色维度，键格式不变
        val voicePart = voiceKey?.let { "|$it" } ?: ""
        val key = MessageDigest.getInstance("SHA-256")
            .digest("${lang.tag}$voicePart|${String.format(Locale.ROOT, "%.2f", speed)}|$text".toByteArray())
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
        const val ZIPVOICE_ASSET_ROOT: String = "tts/zipvoice"
        const val ZIPVOICE_DATA_DIR_NAME: String = "tts/zipvoice-data"
        const val LEGACY_MELO_DATA_DIR: String = "tts/melo-data"
        const val ZIPVOICE_PROMPT_TEXT: String = "各位村民,大家新年好! 近期,湖北省武汉市等多个地区"
        const val UNPACK_MARKER_SUFFIX: String = ".complete"
        const val CACHE_DIR_NAME: String = "tts-cache"
        const val WARMUP_JOBS: Int = 2
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
