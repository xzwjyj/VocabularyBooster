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
}

public enum class Readiness { INITIALIZING, READY, UNAVAILABLE }

public data class SpeakRequest(
    val utteranceId: String,
    val text: String,
    val lang: Lang,
    val rate: Float,
    val pitch: Float,
)

/** 单段播放结果（TTS/文件音频段共用）。 */
public data class SegmentResult(
    val utteranceId: String,
    val completed: Boolean,
    val durationMs: Long? = null,
)
