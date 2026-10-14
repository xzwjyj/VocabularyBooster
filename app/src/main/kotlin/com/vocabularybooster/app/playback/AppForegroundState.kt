package com.vocabularybooster.app.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 应用级前台 / 后台状态共享源（FR-25 / SCR-BGVOICE，AUDIO_ENGINE_SPEC §8）。
 *
 * DI 无依赖单例——SCR-BGVOICE 后唯一消费者 = [com.vocabularybooster.platform.VoskSpeechCommandRecognizer]
 * 窗口诊断 `fg=` 前后台标记（OEM 后台静默取证：`vosk_diag.log` `fg=false` 窗口 RMS 恒零 = 静默实证）；
 * [PlaybackForegroundController] 在 ProcessLifecycleOwner ON_START / ON_STOP 时 [set] 更新本源。
 * v2.13 的后台语音门禁（`BackgroundGatedSpeechCommandRecognizer` 与门判据）已随 SCR-BGVOICE 撤销。
 */
class AppForegroundState {
    private val _flow = MutableStateFlow(true)
    val flow: StateFlow<Boolean> = _flow
    fun set(inForeground: Boolean) { _flow.value = inForeground }
}
