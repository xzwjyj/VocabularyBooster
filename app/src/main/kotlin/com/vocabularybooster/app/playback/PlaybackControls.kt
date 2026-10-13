package com.vocabularybooster.app.playback

import com.vocabularybooster.playback.PlaybackOrchestrator
import com.vocabularybooster.playback.PlaybackState
import kotlinx.coroutines.flow.StateFlow

/**
 * 编排器控制抽象（app-local testability seam，FR-25 / SCR-BGPLAYBACK，AUDIO_ENGINE_SPEC §8）。
 *
 * 前台控制器（[PlaybackForegroundController]）依赖本接口而非具体 [PlaybackOrchestrator]——
 * **commonMain 零改动**（不向 shared 引入 app-local 接口），同时让控制器单测（TC-BG-02/04）可用
 * 手写 Fake 喂入任意 [PlaybackState] + 记录控制调用，无需驱动真实会话状态机（其语义已由
 * `PlaybackOrchestratorStateTest` 全锁）。生产装配 = [OrchestratorPlaybackControls] 适配器
 * 1:1 委托应用级 [PlaybackOrchestrator] 单例（DI 绑定）。
 *
 * 通知栏 Pause/Resume/Previous/Next/Exit 五 action + 熄屏竞态 pause 全部经本接口转发至编排器。
 */
public interface PlaybackControls {
    public val state: StateFlow<PlaybackState>
    public suspend fun pause()
    public suspend fun resume()
    public suspend fun next()
    public suspend fun previous()
    public suspend fun exit()
}

/**
 * [PlaybackControls] 生产适配器：1:1 委托应用级 [PlaybackOrchestrator] 单例。
 * 控制语义零变化——全部直接转发编排器既有 suspend 入口（§6 七控制子集；ExitResult 丢弃）。
 */
public class OrchestratorPlaybackControls(
    private val orchestrator: PlaybackOrchestrator,
) : PlaybackControls {
    override val state: StateFlow<PlaybackState> = orchestrator.state
    override suspend fun pause(): Unit = orchestrator.pause()
    override suspend fun resume(): Unit = orchestrator.resume()
    override suspend fun next(): Unit = orchestrator.next()
    override suspend fun previous(): Unit = orchestrator.previous()
    override suspend fun exit() { orchestrator.exit() }
}

/** 前台服务保活作用域：传输态（Playing/Paused/CommandWindow）需前台服务 + MediaSession；终态停服。 */
public fun PlaybackState.isForegroundActive(): Boolean =
    this is PlaybackState.Playing || this is PlaybackState.Paused || this is PlaybackState.CommandWindow
