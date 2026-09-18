package com.vocabularybooster.importing

/**
 * 编码检测（IMPORT_SPEC §2，FR-14）：确定性——只依赖文件头字节（≤ 8KB），输出可复现。
 * commonMain 纯 Kotlin：UTF-8 严格校验与 GB18030 合法性判定均为字节结构级
 * （不触平台 charset；实际解码在 TextLineSource actual，ARCHITECTURE §5）。
 *
 * 算法（优先级）：
 * 1. BOM：`EF BB BF` → UTF-8(+BOM)；`FF FE` → UTF-16LE；`FE FF` → UTF-16BE
 *    （UTF-16 不在需求内，检测到即支持解码；BOM 剥离在解码侧）；
 * 2. 整个 head 通过 UTF-8 严格校验 → UTF-8；
 * 3. 兜底 → GB18030；head 内出现**任一** GB18030 非法序列 → [DetectedEncoding.Unsupported]
 *    （v1 零容忍：UI 引导用户强制指定编码或取消，IMPORT_SPEC §2 第 4 步）；
 *    head 末尾的不完整多字节序列视为截断（head 是前缀切块，不算非法）。
 */
// 数值即规格本体：UTF-8 严格校验逐条对应 RFC 3629 的首字节/续字节边界，
// GB18030 对应双/四字节结构表——命名常量只会遮蔽与规格的逐字节对照；
// 校验器以提前返回短路非法序列（detect 的优先级瀑布同构），复杂度是表驱动的固有形态。
@Suppress("MagicNumber", "ReturnCount", "CyclomaticComplexMethod", "LoopWithTooManyJumpStatements")
public object EncodingDetector {

    /** 检测输入上限（IMPORT_SPEC §1：文件头字节 ≤ 8KB）。 */
    public const val HEAD_MAX_BYTES: Int = 8 * 1024

    public fun detect(head: ByteArray): DetectedEncoding {
        if (head.isEmpty()) return DetectedEncoding.Utf8(hasBom = false) // 空文件：空集恒过 UTF-8 校验
        bomOf(head)?.let { return it }
        if (isStrictUtf8(head)) return DetectedEncoding.Utf8(hasBom = false)
        return if (isLegalGb18030(head)) {
            DetectedEncoding.Gb18030
        } else {
            DetectedEncoding.Unsupported(reason = "GB18030 校验失败：文件头含非法字节序列")
        }
    }

    // —— BOM（优先级最高）——

    private fun bomOf(head: ByteArray): DetectedEncoding? = when {
        head.startsWith(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())) ->
            DetectedEncoding.Utf8(hasBom = true)
        head.startsWith(byteArrayOf(0xFF.toByte(), 0xFE.toByte())) -> DetectedEncoding.Utf16LE
        head.startsWith(byteArrayOf(0xFE.toByte(), 0xFF.toByte())) -> DetectedEncoding.Utf16BE
        else -> null
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
        if (size < prefix.size) return false
        for (i in prefix.indices) if (this[i] != prefix[i]) return false
        return true
    }

    // —— UTF-8 严格校验（RFC 3629；末尾不完整序列 = 截断，合法）——

    private fun isStrictUtf8(head: ByteArray): Boolean {
        var i = 0
        while (i < head.size) {
            val b = head[i].toInt() and 0xFF
            val len = when {
                b < 0x80 -> 1 // ASCII
                b in 0xC2..0xDF -> 2
                b in 0xE0..0xEF -> 3
                b in 0xF0..0xF4 -> 4
                else -> return false // 0x80..0xC1（孤立续字节/超长）/ > 0xF4（超 U+10FFFF）
            }
            if (i + len > head.size) return true // 末尾截断的多字节序列：head 是前缀，不算非法
            for (k in 1 until len) {
                val cont = head[i + k].toInt() and 0xFF
                if (cont and 0xC0 != 0x80) return false
            }
            if (len == 1) { // ASCII：无续字节，越过首续字节检查（否则末字节越界读）
                i++
                continue
            }
            // 首续字节下界只约束特定 lead：overlong 只可能出自 E0/F0（E1..EF、F1..F3 天然达标）
            val first = head[i + 1].toInt() and 0xFF
            if (b == 0xE0 && first < 0xA0) return false // E0 80..9F = overlong
            if (b == 0xF0 && first < 0x90) return false // F0 80..8F = overlong
            if (b == 0xED && first > 0x9F) return false // ED A0..BF = 代理区
            if (b == 0xF4 && first > 0x8F) return false // F4 90.. = 超 U+10FFFF
            i += len
        }
        return true
    }

    // —— GB18030 合法性（字节结构级；末尾不完整序列 = 截断，合法）——
    // 单字节 0x00..0x7F；双字节 lead 0x81..0xFE + trail 0x40..0xFE(≠0x7F)；
    // 四字节 lead 0x81..0xFE + 0x30..0x39 + 0x81..0xFE + 0x30..0x39。

    private fun isLegalGb18030(head: ByteArray): Boolean {
        var i = 0
        while (i < head.size) {
            val b = head[i].toInt() and 0xFF
            if (b < 0x80) { // ASCII
                i++
                continue
            }
            if (b !in 0x81..0xFE) return false
            if (i + 1 >= head.size) return true // 末尾截断
            val b2 = head[i + 1].toInt() and 0xFF
            if (b2 in 0x40..0xFE && b2 != 0x7F) { // 双字节序列
                i += 2
                continue
            }
            if (b2 in 0x30..0x39) { // 四字节序列
                if (i + 3 >= head.size) return true // 末尾截断
                val b3 = head[i + 2].toInt() and 0xFF
                val b4 = head[i + 3].toInt() and 0xFF
                if (b3 in 0x81..0xFE && b4 in 0x30..0x39) {
                    i += 4
                    continue
                }
            }
            return false
        }
        return true
    }
}

/** 检测决策（IMPORT_SPEC §2）：解码侧据此选 charset；[Unsupported] → UI 引导强制指定/取消。 */
public sealed interface DetectedEncoding {

    /** UTF-8；[hasBom] = 文件头带 EF BB BF（解码侧剥离，绝不进词——边界 #3）。 */
    public data class Utf8(val hasBom: Boolean) : DetectedEncoding

    /** UTF-16（BOM 检出即支持解码；不在 FR-14 需求内，无兜底检测）。 */
    public data object Utf16LE : DetectedEncoding

    public data object Utf16BE : DetectedEncoding

    /** 兜底 GB18030（GBK 超集，简体环境最常见）。 */
    public data object Gb18030 : DetectedEncoding

    /** 无法安全解码（v1：GB18030 零容忍非法序列）。 */
    public data class Unsupported(val reason: String) : DetectedEncoding
}
