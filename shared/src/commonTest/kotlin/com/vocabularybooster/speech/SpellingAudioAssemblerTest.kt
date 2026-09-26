package com.vocabularybooster.speech

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * SpellingAudioAssembler（TC-AE-33，SCR-SPELLPAUSE，AUDIO_ENGINE_SPEC §8）：
 * 拼读段逗号格式拆字母 + 逐字母 PCM 静音拼接——字母间精确 pauseMs、无首尾静音、
 * 空白片段剔除、空序列兜底。
 */
class SpellingAudioAssemblerTest {

    @Test
    fun lettersSplitOnCommaAndSkipBlanks() {
        assertEquals(listOf("b", "o", "o", "s", "t", "e", "r"), SpellingAudioAssembler.lettersOf("b, o, o, s, t, e, r"))
        // 撇号/空格字符片段剔除（带撇号词与短语防御）
        assertEquals(listOf("d", "o", "n", "t"), SpellingAudioAssembler.lettersOf("d, o, n, ', t"))
        assertEquals(
            listOf("i", "c", "e", "c", "r", "e", "a", "m"),
            SpellingAudioAssembler.lettersOf("i, c, e,  , c, r, e, a, m"),
        )
        // 非逗号格式文本 → 单元素（调用方零特判的整段语义）
        assertEquals(listOf("plain text"), SpellingAudioAssembler.lettersOf("plain text"))
    }

    @Test
    fun silenceInsertedOnlyBetweenLetters() {
        val a = floatArrayOf(0.5f, -0.5f)
        val b = floatArrayOf(0.25f)
        val joined = SpellingAudioAssembler.joinWithSilence(listOf(a, b), pauseMs = 400, sampleRate = 22_050)
        // 总长 = 2 + 8820(=400ms×22050Hz/1000) + 1；首尾无静音
        assertEquals(2 + 8_820 + 1, joined.size)
        assertEquals(0.5f, joined[0])
        assertEquals(-0.5f, joined[1])
        assertEquals(0f, joined[2]) // 静音区零值
        assertEquals(0f, joined[8_821]) // 静音区最后一样本
        assertEquals(0.25f, joined[8_822]) // 末字母紧随静音区，无尾停顿
    }

    @Test
    fun threeLettersInsertTwoSilenceRuns() {
        val part = floatArrayOf(1f)
        val joined = SpellingAudioAssembler.joinWithSilence(listOf(part, part, part), pauseMs = 50, sampleRate = 1_000)
        // 布局：L1[0] 静音[1..50] L2[51] 静音[52..101] L3[102]
        assertEquals(3 + 50 * 2, joined.size)
        assertEquals(1f, joined[0])
        assertEquals(0f, joined[1])
        assertEquals(0f, joined[50])
        assertEquals(1f, joined[51])
        assertEquals(0f, joined[52])
        assertEquals(0f, joined[101])
        assertEquals(1f, joined[102])
    }

    @Test
    fun singleLetterHasNoSilence() {
        val part = floatArrayOf(0.1f, 0.2f)
        val joined = SpellingAudioAssembler.joinWithSilence(listOf(part), pauseMs = 400, sampleRate = 22_050)
        assertEquals(2, joined.size)
        assertEquals(0.2f, joined[1])
    }

    @Test
    fun emptyPartsReturnEmptyAudio() {
        val joined = SpellingAudioAssembler.joinWithSilence(emptyList(), pauseMs = 400, sampleRate = 22_050)
        assertEquals(0, joined.size)
        assertTrue(joined.isEmpty())
    }

    @Test
    fun roundingTruncatesSubSampleSilence() {
        // 400ms@22050 = 8820 整；非整除场景（50ms@22050=1102.5 → 截断 1102）确定性
        val part = floatArrayOf(1f)
        val joined = SpellingAudioAssembler.joinWithSilence(listOf(part, part), pauseMs = 50, sampleRate = 22_050)
        assertEquals(1 + 1_102 + 1, joined.size)
    }
}
