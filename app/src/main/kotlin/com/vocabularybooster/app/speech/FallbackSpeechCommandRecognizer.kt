package com.vocabularybooster.app.speech

import com.vocabularybooster.speech.RecognitionResult
import com.vocabularybooster.speech.SpeechCommandRecognizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * 引擎回退代理（裁决 E3，2026-09-14，AUDIO_ENGINE_SPEC §8「引擎选择与回退规则」）：
 * E2 启动探测"服务在场" ≠ "服务可用"——厂商助手可注册不可用的 `RecognitionService`
 * （实测 vivo V2436A 蓝心 Copilot 唤醒服务可解析、绑定后 46ms 即硬失败）。
 * 本代理包住主（系统）+ 备（Vosk）两个 actual，实现同一端口——编排器/解析器/掌握语义零改动。
 *
 * - **硬失败信号** = 主引擎返回 `Unavailable` **且**拉低了自身 `isAvailable`（D5 可用性级错误语义）。
 * - **窗口内回退**：硬失败当场改用备引擎完成同一窗口的**剩余预算**（窗口总时长不变）。
 * - **进程内降级（粘滞）**：降级后主引擎零调用、直连备引擎；重启进程回到 E2 启动探测。
 * - **GMS 健康设备零变化**：主引擎 Hit/Timeout → 备引擎零调用，全透传。
 * - 并发守卫触发的 `Unavailable`（不拉低 `isAvailable`）→ 如实透传，**不**回退。
 * - 剩余预算 ≤ 0 → `Timeout`（预算耗尽，不再调备引擎）；**任何路径不产生命令语义**（D5 红线）。
 * - `isAvailable` 投影：降级前随主引擎、降级后随备引擎。
 */
public class FallbackSpeechCommandRecognizer(
    private val primary: SpeechCommandRecognizer,
    private val secondary: SpeechCommandRecognizer,
    private val timeSourceMs: () -> Long = DEFAULT_TIME_SOURCE_MS,
    scope: CoroutineScope,
) : SpeechCommandRecognizer {

    /** 进程内降级标记（置位后不复位；重启进程回到 E2 启动探测）。
     * 2026-09-14 用户裁决：vivo 坏服务机型第一窗直接走 Vosk，跳过系统引擎的 1.5s 看门狗等待。 */
    private val demoted = MutableStateFlow(false)

    override val isAvailable: StateFlow<Boolean> =
        combine(primary.isAvailable, secondary.isAvailable, demoted) { p, s, d -> if (d) s else p }
            .stateIn(scope, SharingStarted.Eagerly, primary.isAvailable.value)

    override suspend fun listenOnce(windowMs: Long): RecognitionResult =
        if (demoted.value) {
            secondary.listenOnce(windowMs) // 已降级：直连备引擎（不试探主引擎）
        } else {
            listenWithPrimaryFirst(windowMs)
        }

    /** 主引擎优先 + 硬失败同窗回退（E3 核心；调用前提 = 主引擎尚未降级）。 */
    private suspend fun listenWithPrimaryFirst(windowMs: Long): RecognitionResult {
        val startMs = timeSourceMs()
        val outcome = primary.listenOnce(windowMs)
        val hardFailed = outcome == RecognitionResult.Unavailable && !primary.isAvailable.value
        if (!hardFailed) return outcome // Hit/Timeout/并发守卫 Unavailable → 透传，备引擎零调用
        demoted.value = true // 粘滞降级：后续窗口直连备引擎
        return fallbackWithRemainingBudget(windowMs - (timeSourceMs() - startMs))
    }

    /** 同窗回退：剩余预算交备引擎；预算已耗尽 → Timeout（不调备引擎、零命令语义）。 */
    private suspend fun fallbackWithRemainingBudget(remainingMs: Long): RecognitionResult =
        if (remainingMs <= 0) RecognitionResult.Timeout else secondary.listenOnce(remainingMs)

    private companion object {
        /** 默认单调时源（窗口剩余预算计量；单测注入虚拟时源）。 */
        val DEFAULT_TIME_SOURCE_MS: () -> Long = { System.nanoTime() / NANOS_PER_MILLI }

        const val NANOS_PER_MILLI: Long = 1_000_000L
    }
}
