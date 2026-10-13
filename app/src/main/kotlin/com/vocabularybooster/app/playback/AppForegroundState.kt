package com.vocabularybooster.app.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 应用级前台 / 后台状态共享源（FR-25 / SCR-BGPLAYBACK，AUDIO_ENGINE_SPEC §8）。
 *
 * DI 无依赖单例——打破循环（recognizer → 控制器 → 编排器 → recognizer）：
 * [BackgroundGatedSpeechCommandRecognizer] 读取 [flow] 作与门后台短路判据（FR-12 后台持麦红线）；
 * [PlaybackForegroundController] 在 ProcessLifecycleOwner ON_START / ON_STOP 时 [set] 更新本源。
 * 两者共享同一实例（DI single），单一事实源。
 */
class AppForegroundState {
    private val _flow = MutableStateFlow(true)
    val flow: StateFlow<Boolean> = _flow
    fun set(inForeground: Boolean) { _flow.value = inForeground }
}
