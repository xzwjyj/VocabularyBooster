package com.vocabularybooster.playback

import kotlinx.serialization.Serializable

/**
 * 位置持久化（AUDIO_ENGINE_SPEC §5）：
 * `AppSetting["playback.position"]`；每次段切换/暂停写入（NFR-3）；
 * 会话结束（COMPLETED/ABANDONED）清除该键。
 */
@Serializable
public data class PlaybackPosition(
    val sessionId: Long,
    val wordId: Long,
    val segmentIndex: Int,
    val offsetMs: Long,
    val phase: PlaybackPhase,
)

@Serializable
public enum class PlaybackPhase { PLAYING, WINDOW }
