package com.vocabularybooster.importing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * 编码检测（IMPORT_SPEC §2，TC-IMP-01）：逐字节样例断言——BOM 三型 / 纯 ASCII /
 * UTF-8 中文严格校验 / GB18030 兜底（2/4 字节结构）/ 非法序列零容忍 Unsupported /
 * head 末尾不完整序列 = 截断容忍。BOM 剥离在解码侧（TextLineSource actual，边界 #3），
 * 本组只锁 [DetectedEncoding.Utf8.hasBom] 标记。
 */
class EncodingDetectorTest {

    @Test
    fun utf8BomIsDetectedAndFlagged() {
        val head = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
            "apple pie".encodeToByteArray()
        assertEquals(DetectedEncoding.Utf8(hasBom = true), EncodingDetector.detect(head))
    }

    @Test
    fun utf16BomsAreDetected() {
        assertEquals(
            DetectedEncoding.Utf16LE,
            EncodingDetector.detect(byteArrayOf(0xFF.toByte(), 0xFE.toByte())),
        )
        assertEquals(
            DetectedEncoding.Utf16BE,
            EncodingDetector.detect(byteArrayOf(0xFE.toByte(), 0xFF.toByte())),
        )
    }

    @Test
    fun asciiAndUtf8ChinesePassStrictValidation() {
        assertEquals(
            DetectedEncoding.Utf8(hasBom = false),
            EncodingDetector.detect("apple pie".encodeToByteArray()),
        )
        assertEquals(
            DetectedEncoding.Utf8(hasBom = false),
            EncodingDetector.detect("苹果梨".encodeToByteArray()),
        )
    }

    @Test
    fun gb18030SampleFallsBackWithTwoByteAndFourByteSequences() {
        // GBK「苹果」= C6 BD B9 FB（双字节）+ ASCII 混排
        val twoByte = byteArrayOf(
            0xC6.toByte(), 0xBD.toByte(), 0xB9.toByte(), 0xFB.toByte(), 'A'.code.toByte(),
        )
        assertIs<DetectedEncoding.Gb18030>(EncodingDetector.detect(twoByte))
        // 四字节序列 81 30 81 30 合法
        val fourByte = byteArrayOf(0x81.toByte(), 0x30, 0x81.toByte(), 0x30)
        assertIs<DetectedEncoding.Gb18030>(EncodingDetector.detect(fourByte))
    }

    @Test
    fun utf8InvalidButGb18030LegalBytesFallBackToGb18030() {
        // 0x81 0x40：UTF-8 孤立续字节非法；GB18030 合法双字节
        val head = byteArrayOf(0x81.toByte(), 0x40)
        assertIs<DetectedEncoding.Gb18030>(EncodingDetector.detect(head))
    }

    @Test
    fun illegalSequencesAreUnsupportedZeroTolerance() {
        // GB18030 trail 0x7F 被排除，也不是四字节第二位 0x30..0x39
        assertIs<DetectedEncoding.Unsupported>(
            EncodingDetector.detect(byteArrayOf(0x81.toByte(), 0x7F)),
        )
        // 孤立 0x80：既非 ASCII 也非 GB18030 lead
        assertIs<DetectedEncoding.Unsupported>(
            EncodingDetector.detect(byteArrayOf(0x80.toByte())),
        )
        // UTF-16 代理区 ED A0 80：UTF-8 非法；GB18030 解到第三字节 0x80 非法
        assertIs<DetectedEncoding.Unsupported>(
            EncodingDetector.detect(
                byteArrayOf(0xED.toByte(), 0xA0.toByte(), 0x80.toByte()),
            ),
        )
        // 超 U+10FFFF 的 F4 90..：UTF-8 非法；GB18030 同样解不动 0x80
        assertIs<DetectedEncoding.Unsupported>(
            EncodingDetector.detect(
                byteArrayOf(0xF4.toByte(), 0x90.toByte(), 0x80.toByte(), 0x80.toByte()),
            ),
        )
    }

    @Test
    fun truncatedSequenceAtHeadEndIsTolerated() {
        // head 是文件前缀切块：末尾不完整序列不算非法（UTF-8 与 GB18030 同口径）
        val utf8Chinese = "苹".encodeToByteArray() // E8 8B B9 → 截前 2 字节
        assertIs<DetectedEncoding.Utf8>(
            EncodingDetector.detect(utf8Chinese.copyOf(2)),
        )
        // UTF-8 校验先行：孤立 GBK lead（C6）也是合法 UTF-8 前缀 → Utf8；
        // GB18030 截断判定只在 UTF-8 已失败后到达（GBK「苹」= C6 BD，截末字节）
        assertIs<DetectedEncoding.Gb18030>(
            EncodingDetector.detect(byteArrayOf(0xC6.toByte(), 0xBD.toByte(), 0xB9.toByte())),
        )
        // 四字节序列只剩前 3 字节
        assertIs<DetectedEncoding.Gb18030>(
            EncodingDetector.detect(byteArrayOf(0x81.toByte(), 0x30, 0x81.toByte())),
        )
    }

    @Test
    fun emptyHeadDefaultsToUtf8() {
        assertEquals(DetectedEncoding.Utf8(hasBom = false), EncodingDetector.detect(byteArrayOf()))
    }
}
