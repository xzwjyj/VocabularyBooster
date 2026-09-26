package com.vocabularybooster.speech

import com.vocabularybooster.domain.model.Lang
import kotlinx.coroutines.flow.StateFlow

/**
 * TTS 端口（ARCHITECTURE §5）。
 *
 * Android actual：`android.speech.tts.TextToSpeech` + `UtteranceProgressListener`
 * （AUDIO_ENGINE_SPEC §8）；iOS actual：`AVSpeechSynthesizer`。
 */
public interface SpeechSynthesizer {

    /** Ready / Initializing / Unavailable */
    public val readiness: StateFlow<Readiness>

    /** 播完返回（request 含 utteranceId/text/lang/rate/pitch）。 */
    public suspend fun speak(request: SpeakRequest): SegmentResult

    /** 立即停止（Pause/Exit 用）。 */
    public fun stop()

    /**
     * 当前引擎可用的音色枚举（FR-19，Phase 8.6 加法扩展，L6 先例）。
     * 引擎未就绪 → 空列表（UI 层等待 readiness 再刷新）；音色随设备已装引擎而异。
     */
    public fun availableVoices(lang: Lang): List<TtsVoice>
}

/** 平台中立音色描述（id = 平台音色标识，用于设置持久化与 speak 前应用）。 */
public data class TtsVoice(
    val id: String,
    val displayName: String,
    /** 品质标签（如「高」/「标准」），仅供 UI 展示。 */
    val qualityLabel: String? = null,
)

public enum class Readiness { INITIALIZING, READY, UNAVAILABLE }

public data class SpeakRequest(
    val utteranceId: String,
    val text: String,
    val lang: Lang,
    val rate: Float,
    val pitch: Float,
    /**
     * SPELLING 段字母间停顿时长（SCR-SPELLPAUSE，AUDIO_ENGINE_SPEC §8）：null = 普通段
     * 整段合成；非 null = 文本为逗号拼读格式，actual 逐字母合成/朗读并以此时长停顿。
     */
    val letterPauseMs: Int? = null,
)

/** 单段播放结果（TTS/文件音频段共用）。 */
public data class SegmentResult(
    val utteranceId: String,
    val completed: Boolean,
    val durationMs: Long? = null,
)
