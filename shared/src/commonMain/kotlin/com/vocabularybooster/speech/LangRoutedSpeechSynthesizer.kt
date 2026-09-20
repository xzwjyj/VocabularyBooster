package com.vocabularybooster.speech

import com.vocabularybooster.domain.model.Lang
import com.vocabularybooster.platform.LogLevel
import com.vocabularybooster.platform.LogSink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow

/**
 * 按语言路由的 SpeechSynthesizer 组合实现（FR-23 v2，AUDIO_ENGINE_SPEC §8）：
 * EN_US / EN_GB → 神经 TTS（Android 装配侧注入 [com.vocabularybooster.platform.SherpaOnnxSpeechSynthesizer]，
 * 口音 = 模型，FR-22 消费时映射不变）；ZH_CN → 系统 TTS actual。
 *
 * - **降级**：神经引擎非 READY，或 speak 抛非取消异常 → EN 段**当场回退系统 TTS** 并进程内粘滞
 *   （记日志）；模型资产缺席/加载失败下行为 = 全系统 TTS，会话不中断（FR-23 验收 5）。
 * - **取消红线**：`CancellationException`（暂停/退出路径）照常重抛，绝不触发降级
 *   （先例：Vosk 取消被吞成降级的 vivo 回归，TC-AE-27 注记）。
 * - **readiness 投影** = 系统引擎（既有 UI 行为零变化；神经状态仅内部路由依据——
 *   INITIALIZING 期间 EN 段先走系统，预热完成后自动切换神经）。
 * - **availableVoices**：EN 由神经承接路由时取神经（每口音单条模型 voice），降级后取系统；
 *   ZH 恒系统（FR-19 语义不变）。
 */
public class LangRoutedSpeechSynthesizer(
    private val neural: SpeechSynthesizer,
    private val system: SpeechSynthesizer,
    private val log: LogSink? = null,
) : SpeechSynthesizer {

    /** 神经引擎进程内粘滞降级标记（speak 异常后置位；只升不降）。 */
    @Volatile
    private var neuralDegraded: Boolean = false

    override val readiness: StateFlow<Readiness>
        get() = system.readiness

    override suspend fun speak(request: SpeakRequest): SegmentResult =
        when (request.lang) {
            Lang.ZH_CN -> system.speak(request)
            Lang.EN_US, Lang.EN_GB -> speakEnglish(request)
        }

    private suspend fun speakEnglish(request: SpeakRequest): SegmentResult {
        if (!neuralDegraded && neural.readiness.value == Readiness.READY) {
            try {
                return neural.speak(request)
            } catch (e: CancellationException) {
                throw e // 取消 = 调用方生命周期，非引擎故障——不降级不兜底
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                // 平台 actual 故障种类不可穷举（native 加载/AudioTrack/焦点），降级语义须兜住全部
                neuralDegraded = true
                log?.log(LogLevel.WARN, TAG, "神经 TTS 失败，EN 段回退系统 TTS（进程内粘滞）：${e.message}", e)
            }
        }
        return system.speak(request)
    }

    /** 双转发（幂等）：未知当前路由落点时两侧全停，语义同既有 stop 立停。 */
    override fun stop() {
        neural.stop()
        system.stop()
    }

    override fun availableVoices(lang: Lang): List<TtsVoice> = when {
        lang == Lang.ZH_CN -> system.availableVoices(lang)
        !neuralDegraded && neural.readiness.value == Readiness.READY -> neural.availableVoices(lang)
        else -> system.availableVoices(lang)
    }

    private companion object {
        const val TAG: String = "LangRoutedTts"
    }
}
