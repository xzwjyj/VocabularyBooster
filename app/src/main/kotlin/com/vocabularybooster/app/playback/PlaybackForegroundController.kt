package com.vocabularybooster.app.playback

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.vocabularybooster.playback.PlaybackState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 前台媒体服务控制器（FR-25 / SCR-BGPLAYBACK，AUDIO_ENGINE_SPEC §8「熄屏/后台续播契约」）。
 *
 * 应用级 DI 单例，三职责：
 *
 * 1. **前台服务生命周期 ↔ 编排器传输态绑定**：采集 [PlaybackControls.state]，传输态
 *    （Playing/Paused/CommandWindow）→ `gateway.start()` 升前台服务；终态 → `gateway.stop()` 停服。
 *    `serviceActive` 防重入——同态多 tick（段推进、倒计时 tick）只 start 一次。
 * 2. **app 前台 / 后台标志源**：[ProcessLifecycleOwner] 的 ON_START→true / ON_STOP→false 投影为
 *    [appForeground] StateFlow，供 [BackgroundGatedSpeechCommandRecognizer] 与门（后台 → isAvailable=false，
 *    FR-12「绝无后台监听」红线硬守）。
 * 3. **通知栏 / 锁屏 transport 路由**：onNotificationPause/Resume/Next/Previous/Exit → [PlaybackControls]
 *    suspend 入口（编排器），与界面按钮走同一控制路径（铁律 5「会了」同源语义延伸）。
 *
 * **熄屏竞态守护**（TC-BG-04）：ON_STOP 时若恰处 CommandWindow 且 `listening=true`（识别窗口开着麦），
 * 立即 `pause()` 取消窗口 Job 关麦——[BackgroundGatedSpeechCommandRecognizer] 后台短路为兜底，
 * 此处为先行关闭（竞态窗口内绝不后台持麦）。Playing / 纯倒计时窗口（`listening=false`）**不** pause——
 * 熄屏续播是本 SCR 的目的。
 *
 * @param controls 编排器控制（app-local seam）
 * @param gateway 前台服务启停网关
 * @param lifecycle 应用级 [ProcessLifecycleOwner.get().lifecycle]（ON_START/ON_STOP 信号源）
 * @param appForegroundState 应用级前台/后台共享源（[BackgroundGatedSpeechCommandRecognizer] 与门同源——
 *   DI 无依赖单例，打破 recognizer → 控制器 → 编排器 → recognizer 循环）
 * @param scope 控制器作用域（DI 注入 SupervisorJob + Main.immediate；StateFlow 采集 + transport 协程）
 */
class PlaybackForegroundController(
    private val controls: PlaybackControls,
    private val gateway: PlaybackServiceGateway,
    lifecycle: Lifecycle,
    private val appForegroundState: AppForegroundState,
    private val scope: CoroutineScope,
) {
    public val appForeground: StateFlow<Boolean> = appForegroundState.flow

    @Volatile private var serviceActive: Boolean = false

    private val observer = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) = onAppForegroundChanged(true)
        override fun onStop(owner: LifecycleOwner) = onAppForegroundChanged(false)
    }

    init {
        lifecycle.addObserver(observer)
        scope.launch { controls.state.collect { state -> reflect(state) } }
    }

    /** 编排器状态变更 → 前台服务升/降（防重入）。 */
    private fun reflect(state: PlaybackState) {
        if (state.isForegroundActive()) {
            if (!serviceActive) {
                gateway.start()
                serviceActive = true
            }
        } else {
            if (serviceActive) {
                gateway.stop()
                serviceActive = false
            }
        }
    }

    /**
     * app 前台 / 后台翻转（ProcessLifecycleOwner ON_START/ON_STOP 驱动）。
     * 后台时若处 CommandWindow 且 listening=true → 立即 pause 关麦（TC-BG-04a 竞态守护）。
     */
    public fun onAppForegroundChanged(inForeground: Boolean) {
        appForegroundState.set(inForeground)
        if (!inForeground) {
            val s = controls.state.value
            if (s is PlaybackState.CommandWindow && s.listening) {
                scope.launch { controls.pause() } // 关麦——FR-12 后台持麦红线
            }
        }
    }

    // —— 通知栏 / 锁屏 transport 路由（→ 编排器 suspend 入口；与界面按钮同源） ——

    public fun onNotificationPause() { scope.launch { controls.pause() } }
    public fun onNotificationResume() { scope.launch { controls.resume() } }
    public fun onNotificationNext() { scope.launch { controls.next() } }
    public fun onNotificationPrevious() { scope.launch { controls.previous() } }
    public fun onNotificationExit() { scope.launch { controls.exit() } }
}
