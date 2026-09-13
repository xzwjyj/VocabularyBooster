package com.vocabularybooster.app.audio

import android.content.Context
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vocabularybooster.app.MainActivity
import com.vocabularybooster.playback.TrackDescriptor
import com.vocabularybooster.platform.Media3AudioPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 音频焦点 instrumented 测试（Phase 4 Step 3，AUDIO_ENGINE_SPEC §8 / TC-AE-16 平台侧）：
 * transient 焦点丢失 → 自动 Pause（位置保留、进度冻结）；焦点回归 → **不**自动恢复播放。
 * 焦点完全在平台层处理（Media3AudioPlayer 内部），不上漏 commonMain 端口。
 */
@RunWith(AndroidJUnit4::class)
class AudioFocusInstrumentedTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var player: Media3AudioPlayer
    private lateinit var audioManager: AudioManager
    private lateinit var scenario: ActivityScenario<MainActivity>

    @Before
    fun setUp() {
        // Android 12+ 焦点授予与前台状态绑定：无 Activity 的插桩进程会被拒焦——启动真实 Activity 对齐前台场景
        scenario = ActivityScenario.launch(MainActivity::class.java)
        runBlocking {
            withContext(Dispatchers.Main.immediate) { player = Media3AudioPlayer(context) }
            audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        }
    }

    @After
    fun tearDown() {
        runBlocking { withContext(Dispatchers.Main.immediate) { player.release() } }
        scenario.close()
    }

    @Test
    fun transientFocusLoss_autoPauses_andRegainDoesNotResume() = runBlocking {
        withContext(Dispatchers.Main.immediate) {
            player.prepare(placeholderTrack())
            val job = launch { player.playAt(0L) }
            withTimeout(TIMEOUT_MS) { player.progressMs.first { (it ?: 0L) > 400L } }
            assertTrue(player.isPlaying)

            // 同 app 竞争者请求 transient 焦点 → 我们的请求收到 LOSS_TRANSIENT
            val competitor = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setOnAudioFocusChangeListener({ }, Handler(Looper.getMainLooper()))
                .build()
            assertEquals(AudioManager.AUDIOFOCUS_REQUEST_GRANTED, audioManager.requestAudioFocus(competitor))

            // §8：transient 丢失 → 自动 Pause（回调驱动，非轮询断言完成）
            withTimeout(5_000) { while (player.isPlaying) delay(50) }
            val frozenAt = player.progressMs.value
            assertTrue("暂停位置应保留（>0），实际 $frozenAt", (frozenAt ?: 0L) > 0L)
            delay(500)
            assertEquals("暂停后进度应冻结", frozenAt, player.progressMs.value)

            // TC-AE-16：焦点回归（竞争者放弃）不自动播放
            audioManager.abandonAudioFocusRequest(competitor)
            delay(800)
            assertFalse("焦点回归不得自动恢复播放", player.isPlaying)

            job.cancel()
        }
    }

    private fun placeholderTrack(): TrackDescriptor = TrackDescriptor(
        trackId = "ex-placeholder",
        audioUri = "res://vb_placeholder_audio",
        durationMs = 4_000L,
    )

    private companion object {
        const val TIMEOUT_MS: Long = 15_000L
    }
}
