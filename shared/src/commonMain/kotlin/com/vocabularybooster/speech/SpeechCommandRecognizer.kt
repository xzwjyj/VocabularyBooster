package com.vocabularybooster.speech

import kotlinx.coroutines.flow.StateFlow

/**
 * 语音命令识别端口（ADR-08：命令窗口式识别，杜绝 TTS 自识别，FR-12）。
 *
 * 只允许在 PlaybackOrchestrator 的 CommandWindow 态被调用（AUDIO_ENGINE_SPEC §3）。
 * Android actual：`SpeechRecognizer`（EXTRA_PREFER_OFFLINE）；iOS actual：`SFSpeechRecognizer`。
 */
public interface SpeechCommandRecognizer {

    /** Hit(text) / Timeout / Unavailable。识别结果只有最终文本参与解析（partial 一律忽略）。 */
    public suspend fun listenOnce(windowMs: Long): RecognitionResult

    public val isAvailable: StateFlow<Boolean>
}

public sealed interface RecognitionResult {
    public data class Hit(val text: String) : RecognitionResult
    public object Timeout : RecognitionResult
    public object Unavailable : RecognitionResult
}
