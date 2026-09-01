package com.vocabularybooster

import com.vocabularybooster.playback.PlaybackPhase
import com.vocabularybooster.playback.PlaybackPosition
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Phase 1 冒烟：kotlinx-serialization 在共享模块可用（DoD），
 * 以 AUDIO_ENGINE_SPEC §5 的 playback.position 载荷为样本。
 */
class SerializationSmokeTest {

    @Test
    fun playbackPositionRoundTripsThroughJson() {
        val original = PlaybackPosition(
            sessionId = 7L,
            wordId = 42L,
            segmentIndex = 3,
            offsetMs = 1_250L,
            phase = PlaybackPhase.WINDOW,
        )
        val encoded = Json.encodeToString(PlaybackPosition.serializer(), original)
        assertTrue(encoded.contains("\"phase\":\"WINDOW\""))
        assertEquals(original, Json.decodeFromString(PlaybackPosition.serializer(), encoded))
    }
}
