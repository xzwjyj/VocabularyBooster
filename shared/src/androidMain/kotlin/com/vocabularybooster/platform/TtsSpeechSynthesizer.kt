package com.vocabularybooster.platform

import android.content.Context
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.vocabularybooster.domain.model.Lang
import com.vocabularybooster.domain.repository.LearningSettingsRepository
import com.vocabularybooster.speech.Readiness
import com.vocabularybooster.speech.SegmentResult
import com.vocabularybooster.speech.SpeakRequest
import com.vocabularybooster.speech.SpeechSynthesizer
import com.vocabularybooster.speech.SpellingAudioAssembler
import com.vocabularybooster.speech.TtsVoice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

/**
 * SpeechSynthesizer 的 Android 实现（AUDIO_ENGINE_SPEC §8 平台 actual 契约）：
 * `android.speech.tts.TextToSpeech`（Koin 单例，applicationContext——不持 Activity）。
 *
 * - **初始化**：异步——`readiness` 经 OnInitListener 暴露 INITIALIZING → READY / UNAVAILABLE；
 *   `speak()` 绝不假定已初始化完成（等待 ready，失败/超时抛异常，不静默降级）。
 * - **语言**：逐段 `setLanguage(en-US / en-GB / zh-CN)`（双语段切换是硬性要求）；
 *   locale 不可用（LANG_MISSING_DATA / LANG_NOT_SUPPORTED）→ 抛异常，**不静默改播另一语言**
 *   （唯一例外 FR-22：en-GB 缺失回退 en-US 继续播——口音是偏好非双语硬要求）。
 * - **rate / pitch**：`request.rate` 已由编排器组装为 `ttsRate × segment.rateScale`——
 *   本实现直接使用，**不重复乘算**。
 * - **完成**：UtteranceProgressListener 回调驱动 CompletableDeferred（不依赖固定 sleep）；
 *   `stop()` 真正调用 `TextToSpeech.stop()`（不只改 Kotlin 状态），在途 utterance 以
 *   `completed=false` 收场（onStop / 手动补全，先到先得）。
 * - **拼读段多 utterance**（SCR-SPELLPAUSE）：`letterPauseMs` 非空且 ≥2 个有效字母时，
 *   逐字母 `speak(QUEUE_ADD)` + 字母间 `playSilentUtterance(pauseMs)`，等待**末字母** id
 *   收尾；监听器按「段 id 集合」匹配（集合内任一 onError → 整段异常，中间字母 onDone 忽略）。
 *   普通段（letterPauseMs = null）单 utterance 路径语义零变化。
 * - **生命周期**：initialize → speak → stop → `release()`（shutdown）。release 后 speak 抛异常。
 */
public class TtsSpeechSynthesizer(
    context: Context,
    private val settings: LearningSettingsRepository,
) : SpeechSynthesizer {

    private val appContext: Context = context.applicationContext

    private val _readiness = MutableStateFlow<Readiness>(Readiness.INITIALIZING)
    override val readiness: StateFlow<Readiness> = _readiness

    /** 初始化闸门：OnInitListener 完成时落定（true = SUCCESS）。 */
    private val initGate = CompletableDeferred<Boolean>()

    // 主线程限定（speak/stop/release 均主线程触达）；released 另被 init 回调线程只读 → @Volatile
    @Volatile
    private var released = false

    // 在途段 utterance 集合（拼读段 = 字母 + 静音多 utterance，SCR-SPELLPAUSE）：
    // 末字母 id = 完成信号；集合内任一 onError → 整段异常；任一 onStop → 整段中止。
    private var pendingFinalId: String? = null
    private var pendingSegmentIds: Set<String> = emptySet()
    private var pendingUtterance: CompletableDeferred<SegmentResult>? = null

    private val tts: TextToSpeech = TextToSpeech(appContext) { status ->
        val ok = status == TextToSpeech.SUCCESS && !released
        _readiness.value = if (ok) Readiness.READY else Readiness.UNAVAILABLE
        initGate.complete(ok)
    }

    /** 测试/冒烟观测：TTS 引擎是否正在朗读。主线程限定。 */
    public val isSpeaking: Boolean
        get() {
            ensureOnMainThread()
            return tts.isSpeaking
        }

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            // 回调由引擎派发（非协程上下文）；CompletableDeferred 线程安全，先到先得。
            override fun onStart(utteranceId: String?) = Unit

            override fun onDone(utteranceId: String?) {
                if (utteranceId == pendingFinalId) {
                    pendingUtterance?.complete(
                        SegmentResult(utteranceId = utteranceId.orEmpty(), completed = true)
                    )
                }
            }

            @Deprecated("API 21 起由 onError(utteranceId, errorCode) 取代")
            override fun onError(utteranceId: String?) {
                failPendingSegment(utteranceId, detail = "")
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                failPendingSegment(utteranceId, detail = "（code=$errorCode）")
            }

            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                if (utteranceId in pendingSegmentIds) {
                    pendingUtterance?.complete(
                        SegmentResult(utteranceId = utteranceId.orEmpty(), completed = false)
                    )
                }
            }
        })
    }

    override suspend fun speak(request: SpeakRequest): SegmentResult = withContext(Dispatchers.Main.immediate) {
        ensureNotReleased()
        awaitReady()
        applyVoiceOrLanguage(request.lang)
        tts.setSpeechRate(request.rate)
        tts.setPitch(request.pitch)

        // 拼读段（≥2 个有效字母）走多 utterance 队列；单字母词/普通段走单 utterance 既有路径
        val letters =
            if (request.letterPauseMs != null) SpellingAudioAssembler.lettersOf(request.text) else emptyList()
        val spelling = letters.size > 1
        val finalId = if (spelling) "${request.utteranceId}-l${letters.size - 1}" else request.utteranceId
        val segmentIds = if (spelling) {
            buildSet {
                letters.indices.forEach { add("${request.utteranceId}-l$it") }
                (0 until letters.size - 1).forEach { add("${request.utteranceId}-s$it") }
            }
        } else {
            setOf(request.utteranceId)
        }

        val awaiter = CompletableDeferred<SegmentResult>()
        pendingFinalId = finalId
        pendingSegmentIds = segmentIds
        pendingUtterance = awaiter
        try {
            if (spelling) {
                submitSpellingQueue(request.utteranceId, letters, request.letterPauseMs!!)?.let { error ->
                    tts.stop() // 清半截队列（已入队字母不得在本段收尾后冒播）
                    throw IllegalStateException(error)
                }
            } else {
                val queued = tts.speak(request.text, TextToSpeech.QUEUE_FLUSH, /* params = */ null, finalId)
                if (queued != TextToSpeech.SUCCESS) {
                    throw IllegalStateException("TTS speak 提交失败（result=$queued）")
                }
            }
            awaiter.await() // 末 utterance onDone（完成）/ 段内 onError（异常）/ onStop+stop()（completed=false）
        } catch (e: CancellationException) {
            tts.stop() // 取消 = 立即真停（§8 stop 语义），位置概念不适用 TTS 段（ADR-09 重读）
            throw e
        } finally {
            if (pendingFinalId == finalId) {
                pendingFinalId = null
                pendingSegmentIds = emptySet()
                pendingUtterance = null
            }
        }
    }

    /**
     * 拼读队列提交（SCR-SPELLPAUSE）：逐字母 `speak`（首个 QUEUE_FLUSH 清残留，其余
     * QUEUE_ADD 保序）+ 字母间 `playSilentUtterance(pauseMs)`（平台原生静音 utterance，
     * onDone 按时长触发）。返回 null = 全部入队成功；非 null = 失败描述（调用方 stop 清队列后抛）。
     */
    private fun submitSpellingQueue(
        utteranceId: String,
        letters: List<String>,
        pauseMs: Int,
    ): String? {
        letters.forEachIndexed { index, letter ->
            val mode = if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            val spoken = tts.speak(letter, mode, /* params = */ null, "$utteranceId-l$index")
            if (spoken != TextToSpeech.SUCCESS) {
                return "TTS speak 提交失败（result=$spoken，letter=$letter）"
            }
            if (index < letters.size - 1) {
                val silent = tts.playSilentUtterance(
                    pauseMs.toLong(), TextToSpeech.QUEUE_ADD, "$utteranceId-s$index",
                )
                if (silent != TextToSpeech.SUCCESS) {
                    return "TTS playSilentUtterance 提交失败（result=$silent）"
                }
            }
        }
        return null
    }

    /** 段内任一 utterance 失败 → 整段异常（多 utterance 拼读段中间失败不得静默挂等末字母）。 */
    private fun failPendingSegment(utteranceId: String?, detail: String) {
        if (utteranceId != null && utteranceId in pendingSegmentIds) {
            pendingUtterance?.completeExceptionally(
                IllegalStateException("TTS utterance 失败$detail：$utteranceId")
            )
        }
    }

    /**
     * FR-19（Phase 8.6）：当前引擎音色枚举（en-US / zh-CN 各自列出，未就绪 → 空）。
     * 主线程限定；排除需联网的特征（`features` 含 network 前缀键）。
     * 排序：精确 locale（en_US / zh_CN）优先 → 品质高优先 → 名称稳定序。
     * FR-22：EN_GB 只列真英音音色（country=GB）——空列表 = 设备无英音（设置页提示回退美音）；
     * EN_US/ZH_CN 维持 FR-19 语言级过滤不变。
     */
    override fun availableVoices(lang: Lang): List<TtsVoice> {
        ensureOnMainThread()
        if (_readiness.value != Readiness.READY || released) return emptyList()
        val target = TtsLocales.localeFor(lang)
        return tts.voices.orEmpty()
            .asSequence()
            .filter { it.locale.language == target.language }
            .filter { lang != Lang.EN_GB || it.locale.country == target.country }
            .filterNot { voice -> voice.features.any { it.startsWith("network") } }
            .sortedWith(
                compareByDescending<Voice> { it.locale == target }
                    .thenByDescending { it.quality >= VOICE_QUALITY_HIGH }
                    .thenBy { it.name },
            )
            .map { voice ->
                TtsVoice(
                    id = voice.name,
                    displayName = "${voice.name}（${voice.locale.toLanguageTag()}）",
                    qualityLabel = if (voice.quality >= VOICE_QUALITY_HIGH) "高" else "标准",
                )
            }
            .toList()
    }

    /**
     * 段前音色应用（FR-19）：已选音色、当前引擎仍可用且**音色 locale 与段语言一致**（FR-22 口音正交）
     * → `setVoice`（音色隐含 locale）；未选 / 已失效 / locale 不一致 / 应用失败 → `setLanguage` 兜底。
     * 音色应用先行——部分引擎切换音色会重置语速/音调，随后统一重设（调用方顺序已定）。
     * EN_GB 语言缺失 → **回退 en-US 继续播**（FR-22 裁决：口音是偏好非双语硬要求，不进 Paused(error)）；
     * zh-CN 缺失仍硬失败（既有语义，TC-AE-21）。
     */
    private suspend fun applyVoiceOrLanguage(lang: Lang) {
        val selectedId = runCatching {
            when (lang) {
                Lang.EN_US, Lang.EN_GB -> settings.getTtsVoiceEn()
                Lang.ZH_CN -> settings.getTtsVoiceZh()
            }
        }.getOrNull()
        if (selectedId != null) {
            val voice = tts.voices.orEmpty().firstOrNull { it.name == selectedId }
            if (voice != null && voice.locale == TtsLocales.localeFor(lang) &&
                tts.setVoice(voice) != TextToSpeech.ERROR
            ) {
                return
            }
        }
        val available = tts.setLanguage(TtsLocales.localeFor(lang))
        if (available == TextToSpeech.LANG_MISSING_DATA || available == TextToSpeech.LANG_NOT_SUPPORTED) {
            if (lang == Lang.EN_GB) {
                // FR-22：en-GB 语音缺失 → 回退 en-US（回退也缺失才硬失败）
                val fallback = tts.setLanguage(TtsLocales.localeFor(Lang.EN_US))
                if (fallback != TextToSpeech.LANG_MISSING_DATA &&
                    fallback != TextToSpeech.LANG_NOT_SUPPORTED
                ) {
                    return
                }
            }
            throw IllegalStateException(
                "TTS 语言不可用（${lang.tag}，setLanguage=$available）——不静默改播其他语言"
            )
        }
    }

    /** 立即停止：真正调用 `TextToSpeech.stop()`（异步派发 onStop 收尾在途 await + 即时补全兜底）。 */
    override fun stop() {
        ensureOnMainThread()
        if (released) return
        tts.stop()
        pendingUtterance?.complete(
            SegmentResult(utteranceId = pendingFinalId.orEmpty(), completed = false)
        )
        pendingFinalId = null
        pendingSegmentIds = emptySet()
        pendingUtterance = null
    }

    /** 释放（宿主销毁）：stop + shutdown；此后 speak 抛异常、readiness=UNAVAILABLE。主线程限定。 */
    public fun release() {
        ensureOnMainThread()
        if (released) return
        released = true
        tts.stop()
        tts.shutdown()
        pendingUtterance?.complete(
            SegmentResult(utteranceId = pendingFinalId.orEmpty(), completed = false)
        )
        pendingFinalId = null
        pendingSegmentIds = emptySet()
        pendingUtterance = null
        _readiness.value = Readiness.UNAVAILABLE
    }

    private suspend fun awaitReady() {
        when (_readiness.value) {
            Readiness.READY -> Unit
            Readiness.UNAVAILABLE -> throw IllegalStateException("TTS 引擎不可用（初始化失败/超时）")
            Readiness.INITIALIZING -> {
                val ok = withTimeoutOrNull(INIT_TIMEOUT_MS) { initGate.await() }
                if (ok != true) {
                    _readiness.value = Readiness.UNAVAILABLE
                    throw IllegalStateException("TTS 初始化超时（${INIT_TIMEOUT_MS}ms）或失败")
                }
            }
        }
    }

    private fun ensureNotReleased() {
        check(!released) { "TtsSpeechSynthesizer 已 release，禁止继续调用" }
    }

    private fun ensureOnMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) { "TtsSpeechSynthesizer 平台调用必须在主线程" }
    }

    private companion object {
        /** 初始化等待上限：模拟器引擎绑定可能偏慢；超时 → UNAVAILABLE（speak 抛异常，不挂死）。 */
        const val INIT_TIMEOUT_MS: Long = 10_000L

        /** 平台 Voice.QUALITY_HIGH（400）：音色排序与品质标签依据。 */
        const val VOICE_QUALITY_HIGH: Int = 400
    }
}

/** Lang → Android Locale 映射（§8 双语段切换；纯函数，app 层 JVM 单测覆盖）。 */
public object TtsLocales {

    /** 未映射语言 → IllegalStateException（防御：未来扩展须显式登记）。 */
    public fun localeFor(lang: Lang): Locale = when (lang) {
        Lang.EN_US -> Locale.US
        Lang.EN_GB -> Locale.UK
        Lang.ZH_CN -> Locale.SIMPLIFIED_CHINESE
    }
}
