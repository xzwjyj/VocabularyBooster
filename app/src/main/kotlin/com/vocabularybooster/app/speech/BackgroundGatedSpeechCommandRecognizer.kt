package com.vocabularybooster.app.speech

import com.vocabularybooster.speech.RecognitionResult
import com.vocabularybooster.speech.SpeechCommandRecognizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * 后台/熄屏语音门禁 wrapper（FR-25 / SCR-BGPLAYBACK，AUDIO_ENGINE_SPEC §8「熄屏/后台续播契约」）。
 *
 * 包住系统服务在场 / 缺席两条装配路径的产出（`FallbackSpeechCommandRecognizer` 或直连 Vosk actual），
 * 作为 `SpeechCommandRecognizer` 唯一绑定出口——编排器 §3 CommandWindow 双形态自然复用：
 * 后台（`appForeground=false`）→ `isAvailable=false` → CommandWindow 纯倒计时（`listening=false`），
 * 绝无后台持麦（FR-12 红线）；前台时 delegate 行为完全不变（D2–D5 / E2–E4 零改动）。
 *
 * - `isAvailable = delegate.isAvailable && appInForeground`（`combine` + `stateIn(Eagerly)`）。
 * - `listenOnce` 后台短路返回 `Unavailable`——**不调 delegate**（绝不后台持麦/试探）。
 * - 前台标志源 = `ProcessLifecycleOwner`（ON_START→true / ON_STOP→false），经 DI 注入为 StateFlow。
 * - **取消红线继承**：`CancellationException` 照常重抛（delegate 取消语义不变，wrapper 不吞——
 *   窗口中熄屏由 `PlaybackForegroundController` 的 `pause()` 取消窗口 Job，listenOnce 协程取消经
 *   `CancellationException` 自然传播至 actual `destroy()` 关麦，与既有 v1.8 取消语义一致）。
 */
public class BackgroundGatedSpeechCommandRecognizer(
    private val delegate: SpeechCommandRecognizer,
    private val appForeground: StateFlow<Boolean>,
    scope: CoroutineScope,
) : SpeechCommandRecognizer {

    override val isAvailable: StateFlow<Boolean> =
        combine(delegate.isAvailable, appForeground) { delegateAvailable, inForeground ->
            delegateAvailable && inForeground
        }.stateIn(scope, SharingStarted.Eagerly, delegate.isAvailable.value && appForeground.value)

    override suspend fun listenOnce(windowMs: Long): RecognitionResult =
        if (!appForeground.value) {
            RecognitionResult.Unavailable // 后台短路（FR-12：绝不后台持麦/试探），零调用 delegate
        } else {
            delegate.listenOnce(windowMs) // 前台透传——CancellationException 自然重抛，wrapper 不吞
        }
}
