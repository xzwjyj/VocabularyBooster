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
 * - **语言**：逐段 `setLanguage(en-US / zh-CN)`（双语段切换是硬性要求）；
 *   locale 不可用（LANG_MISSING_DATA / LANG_NOT_SUPPORTED）→ 抛异常，**不静默改播另一语言**。
 * - **rate / pitch**：`request.rate` 已由编排器组装为 `ttsRate × segment.rateScale`——
 *   本实现直接使用，**不重复乘算**。
 * - **完成**：UtteranceProgressListener 回调驱动 CompletableDeferred（不依赖固定 sleep）；
 *   `stop()` 真正调用 `TextToSpeech.stop()`（不只改 Kotlin 状态），在途 utterance 以
 *   `completed=false` 收场（onStop / 手动补全，先到先得）。
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
    private var pendingUtteranceId: String? = null
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
                if (utteranceId == pendingUtteranceId) {
                    pendingUtterance?.complete(
                        SegmentResult(utteranceId = utteranceId.orEmpty(), completed = true)
                    )
                }
            }

            @Deprecated("API 21 起由 onError(utteranceId, errorCode) 取代")
            override fun onError(utteranceId: String?) {
                if (utteranceId == pendingUtteranceId) {
                    pendingUtterance?.completeExceptionally(
                        IllegalStateException("TTS utterance 失败：$utteranceId")
                    )
                }
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                if (utteranceId == pendingUtteranceId) {
                    pendingUtterance?.completeExceptionally(
                        IllegalStateException("TTS utterance 失败（code=$errorCode）：$utteranceId")
                    )
                }
            }

            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                if (utteranceId == pendingUtteranceId) {
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

        val awaiter = CompletableDeferred<SegmentResult>()
        pendingUtteranceId = request.utteranceId
        pendingUtterance = awaiter
        val queued = tts.speak(request.text, TextToSpeech.QUEUE_FLUSH, /* params = */ null, request.utteranceId)
        if (queued != TextToSpeech.SUCCESS) {
            pendingUtteranceId = null
            pendingUtterance = null
            throw IllegalStateException("TTS speak 提交失败（result=$queued）")
        }
        try {
            awaiter.await() // onDone（完成）/ onError（异常）/ onStop+stop()（completed=false）
        } catch (e: CancellationException) {
            tts.stop() // 取消 = 立即真停（§8 stop 语义），位置概念不适用 TTS 段（ADR-09 重读）
            throw e
        } finally {
            if (pendingUtteranceId == request.utteranceId) {
                pendingUtteranceId = null
                pendingUtterance = null
            }
        }
    }

    /**
     * FR-19（Phase 8.6）：当前引擎音色枚举（en-US / zh-CN 各自列出，未就绪 → 空）。
     * 主线程限定；排除需联网的特征（`features` 含 network 前缀键）。
     * 排序：精确 locale（en_US / zh_CN）优先 → 品质高优先 → 名称稳定序。
     */
    override fun availableVoices(lang: Lang): List<TtsVoice> {
        ensureOnMainThread()
        if (_readiness.value != Readiness.READY || released) return emptyList()
        val target = TtsLocales.localeFor(lang)
        return tts.voices.orEmpty()
            .asSequence()
            .filter { it.locale.language == target.language }
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
     * 段前音色应用（FR-19）：已选音色且当前引擎仍可用 → `setVoice`（音色隐含 locale）；
     * 未选 / 已失效 / 应用失败 → 既有 `setLanguage` 兜底（行为零回归）。
     * 音色应用先行——部分引擎切换音色会重置语速/音调，随后统一重设（调用方顺序已定）。
     */
    private suspend fun applyVoiceOrLanguage(lang: Lang) {
        val selectedId = runCatching {
            when (lang) {
                Lang.EN_US -> settings.getTtsVoiceEn()
                Lang.ZH_CN -> settings.getTtsVoiceZh()
            }
        }.getOrNull()
        if (selectedId != null) {
            val voice = tts.voices.orEmpty().firstOrNull { it.name == selectedId }
            if (voice != null && tts.setVoice(voice) != TextToSpeech.ERROR) return
        }
        val available = tts.setLanguage(TtsLocales.localeFor(lang))
        if (available == TextToSpeech.LANG_MISSING_DATA || available == TextToSpeech.LANG_NOT_SUPPORTED) {
            throw IllegalStateException(
                "TTS 语言不可用（${lang.tag}，setLanguage=$available）——不静默改播其他语言"
            )
        }
    }

    /** 立即停止：真正调用 `TextToSpeech.stop()`（异步派发 onStop 收尾在途 await）。 */
    override fun stop() {
        ensureOnMainThread()
        if (released) return
        tts.stop()
        pendingUtterance?.complete(
            SegmentResult(utteranceId = pendingUtteranceId.orEmpty(), completed = false)
        )
        pendingUtteranceId = null
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
            SegmentResult(utteranceId = pendingUtteranceId.orEmpty(), completed = false)
        )
        pendingUtteranceId = null
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

    /** 未映射语言 → IllegalStateException（防御：端口层只有两值，未来扩展须显式登记）。 */
    public fun localeFor(lang: Lang): Locale = when (lang) {
        Lang.EN_US -> Locale.US
        Lang.ZH_CN -> Locale.SIMPLIFIED_CHINESE
    }
}
