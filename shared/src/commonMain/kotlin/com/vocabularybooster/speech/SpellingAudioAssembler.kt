package com.vocabularybooster.speech

/**
 * 拼读段字母拆分与音频拼接（SCR-SPELLPAUSE，AUDIO_ENGINE_SPEC §8）：
 * SPELLING 段文本为逗号拼读格式 `b, o, o, s, t, e, r`（SegmentBuilder §1 表），
 * 字母间停顿由设置项 `settings.spellingPauseMs` 精确控制（逗号自然停顿时长是模型韵律，
 * 不可调——精确停顿必须逐字母合成 + 显式静音）。纯 PCM 数学，无平台依赖；
 * 神经 actual 以 FloatArray PCM 使用，系统 actual 只用 [lettersOf]。
 */
public object SpellingAudioAssembler {

    /**
     * 拼读文本 → 字母序列（`b, o, o` → [b, o, o]）；片段含字母/数字才保留
     *（撇号 / 空格 / 连字符等非字母字符片段剔除——神经合成必空、系统 TTS 无以朗读）。
     * 非逗号格式文本 → 单元素（整段语义，调用方零特判）。
     */
    public fun lettersOf(spellingText: String): List<String> =
        spellingText.split(", ").filter { it.any(Char::isLetterOrDigit) }

    /**
     * 字母 PCM 相邻拼接，字母间插入 `pauseMs` 静音（仅相邻间，无首尾静音——
     * 段尾停顿由编排器 SEGMENT_GAP 负责）；空序列 → 空音频（调用方防御兜底）。
     */
    public fun joinWithSilence(parts: List<FloatArray>, pauseMs: Int, sampleRate: Int): FloatArray {
        if (parts.isEmpty()) return FloatArray(0)
        val silenceLength = pauseMs * sampleRate / MILLIS_PER_SECOND
        val total = parts.sumOf { it.size } + silenceLength * (parts.size - 1)
        val out = FloatArray(total)
        var offset = 0
        for ((index, part) in parts.withIndex()) {
            part.copyInto(out, offset)
            offset += part.size
            val last = index == parts.size - 1
            if (!last && silenceLength > 0) offset += silenceLength // 静音区本为零值，跳写即可
        }
        return out
    }

    private const val MILLIS_PER_SECOND: Int = 1_000
}
