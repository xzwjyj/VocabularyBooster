package com.vocabularybooster.app.ui

import com.vocabularybooster.domain.model.Lang
import com.vocabularybooster.speech.Readiness
import com.vocabularybooster.speech.SegmentResult
import com.vocabularybooster.speech.SpeakRequest
import com.vocabularybooster.speech.SpeechSynthesizer
import com.vocabularybooster.speech.TtsVoice
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * androidTest 冒烟装配用零副作用 TTS Fake（手写 Fake，不用 mock 框架——TEST_PLAN §1）：
 * 音标旁朗读按钮（FR-22 扩展）不在这些用例的断言路径，仅满足 ViewModel 构造依赖。
 */
object NoopSpeechSynthesizer : SpeechSynthesizer {
    override val readiness = MutableStateFlow(Readiness.READY)
    override suspend fun speak(request: SpeakRequest): SegmentResult =
        SegmentResult(request.utteranceId, completed = true)
    override fun stop() {}
    override fun availableVoices(lang: Lang): List<TtsVoice> = emptyList()
}
