package com.vocabularybooster.platform

import com.vocabularybooster.importing.DetectedEncoding
import kotlinx.coroutines.flow.Flow

/**
 * 导入文件端口（ARCHITECTURE §5，IMPORT_SPEC §1，Phase 7）：
 * 字节读取与按编码解码均为平台能力，commonMain 只定义契约；
 * 解析/去重/事务/报告全部在共享 ImportEngine（纯 Kotlin，JVM 可测）。
 *
 * Android actual：`ContentResolver.openInputStream`（SAF Uri）+ `InputStreamReader`
 * （GB18030 为 API 26+ 内置 charset；BOM 剥离在 actual 首行完成——UTF-8 BOM 绝不进词）。
 * iOS actual：`FileHandle` + 对应编码器（Phase iOS）。
 */

/** 分块字节源（不整载内存）：[readChunk] 返回 null = EOF；块大小由实现裁量。 */
public interface FileBytesSource {
    public suspend fun readChunk(maxBytes: Int): ByteArray?
}

/**
 * 解码行源：按检测结果逐行流出（天然处理跨块字符边界）。
 * 行**不含**行尾符；首行若带 BOM 已由实现剥离（IMPORT_SPEC §2/边界 #3）。
 */
public interface TextLineSource {
    public fun lines(encoding: DetectedEncoding): Flow<String>
}
