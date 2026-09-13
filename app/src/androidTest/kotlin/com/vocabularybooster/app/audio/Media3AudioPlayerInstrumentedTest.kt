package com.vocabularybooster.app.audio

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vocabularybooster.app.MainActivity
import com.vocabularybooster.playback.TrackDescriptor
import com.vocabularybooster.platform.Media3AudioPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Media3AudioPlayer 平台契约 instrumented 测试（Phase 4 Step 3，AUDIO_ENGINE_SPEC §8）：
 * prepare / play / pause(offset) / playAt / stop / 完成回调 / 无效 URI 失败 / release 守卫。
 * 全部在主线程执行（平台线程契约）；完成断言由 Player.Listener 驱动（无固定 sleep、无轮询完成）。
 */
@RunWith(AndroidJUnit4::class)
class Media3AudioPlayerInstrumentedTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var player: Media3AudioPlayer
    private lateinit var scenario: ActivityScenario<MainActivity>

    @Before
    fun setUp() {
        // Android 12+ 将音频焦点授予与前台状态绑定：headless 插桩进程（无 Activity）会被
        // 拒焦（AUDIOFOCUS_REQUEST_FAILED）。启动 app 真实 Activity 对齐前台使用场景（插桩线程调用）。
        scenario = ActivityScenario.launch(MainActivity::class.java)
        runBlocking { withContext(Dispatchers.Main.immediate) { player = Media3AudioPlayer(context) } }
    }

    @After
    fun tearDown() {
        runBlocking { withContext(Dispatchers.Main.immediate) { player.release() } }
        scenario.close()
    }

    @Test
    fun prepareValidLocalAsset_completes() = runBlocking {
        withContext(Dispatchers.Main.immediate) {
            withTimeout(TIMEOUT_MS) { player.prepare(placeholderTrack()) } // 不抛异常 = 预载成功
        }
    }

    @Test
    fun playFromZero_completesWithCallbackDrivenResult() = runBlocking {
        withContext(Dispatchers.Main.immediate) {
            player.prepare(placeholderTrack())
            val result = withTimeout(TIMEOUT_MS) { player.playAt(0L) }
            assertTrue(result.completed)
            assertEquals("ex-placeholder", result.utteranceId)
            assertNotNull(result.durationMs)
            assertTrue("占位音频应为 4000ms，实际 ${result.durationMs}", result.durationMs!! in 3_900L..4_100L)
        }
    }

    @Test
    fun pauseReturnsRealCurrentPosition() = runBlocking {
        withContext(Dispatchers.Main.immediate) {
            player.prepare(placeholderTrack())
            val job = launch { player.playAt(0L) }
            withTimeout(TIMEOUT_MS) { player.progressMs.firstGreaterThan(300L) } // 真实播放时钟前进
            val offset = player.pause()
            assertTrue("pause 应返回真实位置，实际 $offset", offset in 300..3_999)
            job.cancel() // 驱动协程取消（CE 路径内已 pause，幂等）
        }
    }

    @Test
    fun playAtOffset_continuesInsteadOfRestarting() = runBlocking {
        withContext(Dispatchers.Main.immediate) {
            player.prepare(placeholderTrack())
            val job = launch { player.playAt(0L) }
            withTimeout(TIMEOUT_MS) { player.progressMs.firstGreaterThan(1_500L) }
            val offset = player.pause()
            job.cancel()

            val startedAt = System.currentTimeMillis()
            val result = withTimeout(TIMEOUT_MS) { player.playAt(offset) }
            val elapsed = System.currentTimeMillis() - startedAt
            assertTrue(result.completed)
            // 从 offset 续播：剩余时长 ≈ 4000 - offset（< 2600）；整段重播则 ≥ 4000
            assertTrue("应从 offset=$offset 续播，实际耗时 ${elapsed}ms", elapsed < 3_600)
        }
    }

    @Test
    fun stop_thenPrepareAgain_works() = runBlocking {
        withContext(Dispatchers.Main.immediate) {
            player.prepare(placeholderTrack())
            val job = launch { player.playAt(0L) }
            withTimeout(TIMEOUT_MS) { player.progressMs.firstGreaterThan(200L) }
            player.stop()
            job.cancel()
            assertNull(player.progressMs.value)

            withTimeout(TIMEOUT_MS) { player.prepare(placeholderTrack()) }
            val result = withTimeout(TIMEOUT_MS) { player.playAt(0L) }
            assertTrue(result.completed)
        }
    }

    @Test
    fun invalidUri_prepareFailsWithException() = runBlocking {
        withContext(Dispatchers.Main.immediate) {
            try {
                withTimeout(TIMEOUT_MS) {
                    player.prepare(TrackDescriptor("ex-bogus", "res://vb_missing"))
                }
                fail("无效 audioUri 应抛出异常（→ 编排器 TTS 兜底）")
            } catch (expected: IllegalStateException) {
                assertNotNull(expected.cause) // Media3 PlaybackException 归因
            }
        }
    }

    @Test
    fun release_thenUse_throws() = runBlocking {
        withContext(Dispatchers.Main.immediate) {
            player.release()
            try {
                player.prepare(placeholderTrack())
                fail("release 后 prepare 应抛异常")
            } catch (expected: IllegalStateException) {
                assertEquals("Media3AudioPlayer 已 release，禁止继续使用", expected.message)
            }
            try {
                player.playAt(0L)
                fail("release 后 playAt 应抛异常")
            } catch (expected: IllegalStateException) {
                // prepare 前置缺失或 release 守卫，均为失败契约
            }
            player.stop() // 幂等 no-op，不崩
        }
    }

    private fun placeholderTrack(): TrackDescriptor = TrackDescriptor(
        trackId = "ex-placeholder",
        audioUri = "res://vb_placeholder_audio", // 种子存储形态（逻辑 scheme，actual 解析）
        durationMs = 4_000L,
    )

    private suspend fun StateFlow<Long?>.firstGreaterThan(min: Long): Long? =
        withTimeout(TIMEOUT_MS) { first { (it ?: 0L) > min } }

    private companion object {
        const val TIMEOUT_MS: Long = 15_000L
    }
}
