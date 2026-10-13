package com.vocabularybooster.app.playback

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import com.vocabularybooster.learning.WordRef
import com.vocabularybooster.playback.PlaybackState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [PlaybackForegroundController] JVM 单测（TC-BG-02 / TC-BG-04，FR-25 / SCR-BGPLAYBACK，
 * AUDIO_ENGINE_SPEC §8「熄屏/后台续播契约」）。
 *
 * - **TC-BG-02**：传输态 ↔ 前台服务 start/stop 绑定——传输态首现 start 一次（防重入），终态 stop 一次，
 *   再活跃 restart；同态多 tick / 段推进 / 倒计时 tick 不重复 start/stop。
 * - **TC-BG-04**：熄屏竞态守护——ON_STOP 时若处 CommandWindow 且 `listening=true`（识别窗口开着麦）
 *   立即 pause 关麦（TC-BG-04a）；Playing / 纯倒计时窗口（`listening=false`）/ 回前台（TC-BG-04b/c/d）不 pause。
 *
 * 用手写 Fake 端口（无 mock 框架，规范铁律）：[FakeControls] 喂任意 [PlaybackState] + 记录控制调用；
 * [FakeGateway] 计数 start/stop；[NoOpLifecycle] 占位（lifecycle-runtime 的 [LifecycleRegistry] 是
 * android-only AAR，JVM 单测不可用——`addObserver` no-op，测试直接驱动 [PlaybackForegroundController.onAppForegroundChanged]）。
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class) // UnconfinedTestDispatcher：reflect 即时传播断言所需
class PlaybackForegroundControllerTest {

    /** 记录型 Fake 控制：可变 state + 五控制调用计数。 */
    private class FakeControls(initial: PlaybackState) : PlaybackControls {
        private val _state = MutableStateFlow(initial)
        override val state: StateFlow<PlaybackState> = _state
        var pauseCount = 0
            private set
        var resumeCount = 0
            private set
        var nextCount = 0
            private set
        var previousCount = 0
            private set
        var exitCount = 0
            private set
        override suspend fun pause() { pauseCount++ }
        override suspend fun resume() { resumeCount++ }
        override suspend fun next() { nextCount++ }
        override suspend fun previous() { previousCount++ }
        override suspend fun exit() { exitCount++ }
        fun setState(s: PlaybackState) { _state.value = s }
    }

    /** 记录型 Fake 网关：start/stop 计数（零 Android 侧效应）。 */
    private class FakeGateway : PlaybackServiceGateway {
        var startCount = 0
            private set
        var stopCount = 0
            private set
        override fun start() { startCount++ }
        override fun stop() { stopCount++ }
    }

    /** 占位 [Lifecycle]——lifecycle-runtime 的 LifecycleRegistry 是 android-only AAR，JVM 单测不可用。 */
    private class NoOpLifecycle : Lifecycle() {
        override fun addObserver(observer: LifecycleObserver) = Unit
        override fun removeObserver(observer: LifecycleObserver) = Unit
        override val currentState: Lifecycle.State = Lifecycle.State.CREATED
    }

    @Test // TC-BG-02：传输态首现 start 一次（防重入）；终态 stop 一次；再活跃 restart；同态多 tick 不重复
    fun transportActiveStartsOnceTerminalStopsOnceNewActiveRestarts() = runTest {
        val controls = FakeControls(PlaybackState.Idle)
        val gateway = FakeGateway()
        val controller = controller(controls, gateway)
        runCurrent() // flush 初始 Idle collect → 无 start/stop

        controls.setState(playing("apple")) // Playing → start
        runCurrent()
        assertEquals(1, gateway.startCount)
        assertEquals(0, gateway.stopCount)

        controls.setState(playing("apple", segIdx = 1)) // 同词新段 → 仍 Playing → 不重复 start
        runCurrent()
        assertEquals(1, gateway.startCount)

        controls.setState(playing("banana")) // 新词 → 仍 Playing → 不重复 start
        runCurrent()
        assertEquals(1, gateway.startCount)

        controls.setState(paused("banana")) // Paused → 传输态，不变
        runCurrent()
        assertEquals(1, gateway.startCount)
        assertEquals(0, gateway.stopCount)

        controls.setState(commandWindow(listening = true)) // CommandWindow → 传输态，不变
        runCurrent()
        assertEquals(1, gateway.startCount)

        controls.setState(PlaybackState.Stopped) // 终态 → stop
        runCurrent()
        assertEquals(1, gateway.stopCount)

        controls.setState(PlaybackState.Stopped) // Stopped 再来 → 幂等，不重复 stop
        runCurrent()
        assertEquals(1, gateway.stopCount)

        controls.setState(playing("cherry")) // 再活跃 → restart
        runCurrent()
        assertEquals(2, gateway.startCount)
        assertEquals(1, gateway.stopCount)
    }

    @Test // TC-BG-02b：Idle → Completed 全程不 start/stop（无传输态即不升前台）
    fun idleToCompletedNeverStartsOrStops() = runTest {
        val controls = FakeControls(PlaybackState.Idle)
        val gateway = FakeGateway()
        val controller = controller(controls, gateway)
        runCurrent()

        controls.setState(PlaybackState.Completed)
        runCurrent()

        assertEquals(0, gateway.startCount)
        assertEquals(0, gateway.stopCount)
    }

    @Test // TC-BG-04a：熄屏时处 CommandWindow 且 listening=true → 立即 pause 关麦（FR-12 后台持麦红线）
    fun screenOffDuringListeningWindowPausesToCloseMic() = runTest {
        val controls = FakeControls(commandWindow(listening = true))
        val gateway = FakeGateway()
        val controller = controller(controls, gateway)
        runCurrent()

        controller.onAppForegroundChanged(false) // 熄屏竞态：识别窗口开着麦 → pause 关麦
        runCurrent()

        assertEquals(1, controls.pauseCount)
    }

    @Test // TC-BG-04b：熄屏时处 Playing → 不 pause（续播是目的，Playing 无持麦）
    fun screenOffDuringPlayingContinuesWithoutPause() = runTest {
        val controls = FakeControls(playing("apple"))
        val gateway = FakeGateway()
        val controller = controller(controls, gateway)
        runCurrent()

        controller.onAppForegroundChanged(false)
        runCurrent()

        assertEquals(0, controls.pauseCount)
    }

    @Test // TC-BG-04c：熄屏时处纯倒计时窗口（listening=false）→ 不 pause（未开麦，无可关）
    fun screenOffDuringPureCountdownWindowContinuesWithoutPause() = runTest {
        val controls = FakeControls(commandWindow(listening = false))
        val gateway = FakeGateway()
        val controller = controller(controls, gateway)
        runCurrent()

        controller.onAppForegroundChanged(false)
        runCurrent()

        assertEquals(0, controls.pauseCount)
    }

    @Test // TC-BG-04d：回前台（ON_START）→ 不 pause（守护仅在切后台时触发）
    fun screenOnDoesNotPause() = runTest {
        val controls = FakeControls(commandWindow(listening = true))
        val gateway = FakeGateway()
        val controller = controller(controls, gateway)
        runCurrent()

        controller.onAppForegroundChanged(true)
        runCurrent()

        assertEquals(0, controls.pauseCount)
    }

    // —— 测试桩 —— //

    /** 统一装配：Unconfined 测试作用域（reflect 即时传播）。 */
    private fun TestScope.controller(
        controls: PlaybackControls,
        gateway: PlaybackServiceGateway,
    ): PlaybackForegroundController {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        return PlaybackForegroundController(controls, gateway, NoOpLifecycle(), AppForegroundState(), scope)
    }

    private fun playing(text: String, segIdx: Int = 0): PlaybackState.Playing = PlaybackState.Playing(
        wordRef = WordRef(1L, 100L, 0, 0),
        wordText = text,
        segmentIndex = segIdx,
        segment = null,
        offsetMs = 0L,
        degraded = false,
    )

    private fun paused(text: String): PlaybackState.Paused = PlaybackState.Paused(
        wordRef = WordRef(1L, 100L, 0, 0),
        wordText = text,
        segmentIndex = 0,
        offsetMs = 0L,
        atCommandWindow = false,
        error = null,
    )

    private fun commandWindow(listening: Boolean): PlaybackState.CommandWindow =
        PlaybackState.CommandWindow(
            wordRef = WordRef(1L, 100L, 0, 0),
            remainingMs = 3_000L,
            totalMs = 4_000L,
            listening = listening,
        )
}
