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
 *    [appForeground] StateFlow——SCR-BGVOICE 后唯一消费者 = Vosk actual 窗口诊断 `fg=` 标记
 *    （OEM 后台静默取证；v2.13 的后台语音门禁与熄屏竞态关麦守护已随 SCR-BGVOICE 撤销：
 *    熄屏/后台时 CommandWindow 照常开麦监听，`isAvailable` 不再受前后台影响）。
 * 3. **通知栏 / 锁屏 transport 路由**：onNotificationPause/Resume/Next/Previous → [PlaybackControls]
 *    suspend 入口（编排器），与界面按钮走同一控制路径（铁律 5「会了」同源语义延伸）。
 *    （SCR-BGPLAYBACK 追补：通知栏三大键不含退出——退出仅 app 内按钮，2026-10-14 用户裁决。）
 *
 * **熄屏/后台（SCR-BGVOICE，2026-10-14）**：ON_STOP **零控制**——音频续播、命令窗口照常开麦监听
 * （熄屏语音「会了」即掌握换词；识别仍只存在于 CommandWindow 态，绝无常驻监听）。
 * 若机型 OEM 后台静默麦（`vosk_diag.log` `fg=false` 窗口 RMS 恒零实证）→ 窗口自然退化纯倒计时
 * （听不到 = 超时 advance，无功能损害），机型登记另立项。
 *
 * @param controls 编排器控制（app-local seam）
 * @param gateway 前台服务启停网关
 * @param lifecycle 应用级 [ProcessLifecycleOwner.get().lifecycle]（ON_START/ON_STOP 信号源）
 * @param appForegroundState 应用级前台/后台共享源（Vosk actual 诊断 `fg=` 标记源——
 *   DI 无依赖单例，控制器与其共享同一实例）
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
     * SCR-BGVOICE：仅更新诊断标志源——熄屏/后台时 CommandWindow 照常开麦监听（窗口走完：
     * 命中掌握 / 超时 advance，与前台同语义）；v2.13 的「ON_STOP + listening 窗口 → pause 关麦」
     * 守护已撤销（TC-BG-04 断言翻转：ON_STOP 零控制）。
     */
    public fun onAppForegroundChanged(inForeground: Boolean) {
        appForegroundState.set(inForeground)
    }

    // —— 通知栏 / 锁屏 transport 路由（→ 编排器 suspend 入口；与界面按钮同源） ——

    public fun onNotificationPause() { scope.launch { controls.pause() } }
    public fun onNotificationResume() { scope.launch { controls.resume() } }
    public fun onNotificationNext() { scope.launch { controls.next() } }
    public fun onNotificationPrevious() { scope.launch { controls.previous() } }
}
