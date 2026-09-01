package com.vocabularybooster.playback

import com.vocabularybooster.speech.SegmentResult
import kotlinx.coroutines.flow.StateFlow

/**
 * 文件音频端口（例句原声；FR-3：audioUri 为空则 TTS 朗读 sentence 兜底）。
 *
 * Android actual：Media3 ExoPlayer；iOS actual：AVPlayer。
 */
public interface AudioPlayer {

    /** 预加载轨道（audioUri 等）。 */
    public suspend fun prepare(track: TrackDescriptor)

    /** 支持 seek（Resume 精确恢复，FR-11 / ADR-09）。 */
    public suspend fun playAt(offsetMs: Long = 0): SegmentResult

    /** 暂停并返回当前 offsetMs。 */
    public fun pause(): Long

    public fun stop()

    public val progressMs: StateFlow<Long?>
}

public data class TrackDescriptor(
    val trackId: String,
    val audioUri: String,
    val durationMs: Long? = null,
)
