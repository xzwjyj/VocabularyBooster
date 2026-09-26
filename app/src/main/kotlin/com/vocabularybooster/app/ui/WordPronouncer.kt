package com.vocabularybooster.app.ui

import android.util.Log
import com.vocabularybooster.domain.model.Lang
import com.vocabularybooster.domain.repository.LearningSettingsRepository
import com.vocabularybooster.speech.SpeakRequest
import com.vocabularybooster.speech.SpeechSynthesizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * 单词口音试听（FR-22 扩展：US/UK 音标旁朗读小按钮，查词结果 / 词条详情 / 会话词卡三处共用，
 * 经各 ViewModel 委托）。
 *
 * 一次性预览，**不是播放段**：直接以点击口音调 SpeechSynthesizer 端口（LangRouted →
 * piper 神经引擎按口音路由，FR-23），不读也不改 `settings.ttsAccent`——点英音试听不会
 * 把会话口音切成英音；语速/音调沿用用户设置（与会话朗读听感一致）。
 * 重复点击取消上一次未播完的试听（SCR-BUTTONLAG 后协程取消即时停声）。
 *
 * 已知边界（v1 记录不修）：会话 TTS 段播放中试听经 Sherpa speakMutex 自然排队（不抢当前段，
 * 顺序插入其后）；例句原声段（Media3）不经该锁，可能短暂叠加。试听英音会触发神经引擎换载
 * 英音模型（下次会话英文段再换回，秒级加载延迟）。
 */
class WordPronouncer(
    private val synthesizer: SpeechSynthesizer,
    private val settingsRepository: LearningSettingsRepository,
    private val scope: CoroutineScope,
) {

    private var job: Job? = null

    /** 以 [lang] 口音朗读 [text]（空白词不播，防误触占用 AudioTrack）。 */
    fun pronounce(text: String, lang: Lang) {
        val word = text.trim()
        if (word.isEmpty()) return
        job?.cancel()
        job = scope.launch {
            try {
                synthesizer.speak(
                    SpeakRequest(
                        utteranceId = "ipa-preview-${seq.incrementAndGet()}",
                        text = word,
                        lang = lang,
                        rate = settingsRepository.getTtsRate(),
                        pitch = settingsRepository.getTtsPitch(),
                    ),
                )
            } catch (e: CancellationException) {
                throw e // 取消红线：不吞（重听/换口音的中断信号须照常传播）
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                // 一次性试听无 UI 状态可回退：降级语义已在 LangRouted 内消化（神经失败回退系统），
                // 残余异常种类不可穷举（IO/引擎内部）——仅记日志不崩溃
                Log.w(TAG, "ipa preview speak failed: ${e.message}")
            }
        }
    }

    private companion object {
        const val TAG = "VB-Pronouncer"
        val seq = AtomicLong()
    }
}
