package com.vocabularybooster.platform

import android.content.ContentResolver
import android.net.Uri
import com.vocabularybooster.importing.DetectedEncoding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.Charset

/**
 * 导入文件端口 Android actual（ARCHITECTURE §5，IMPORT_SPEC §1，Phase 7，FR-14）：
 * SAF `OpenDocument` 返回的 content Uri 经 ContentResolver 读取；解码全部在本层
 * （GB18030 = API 26+ 内置 charset，minSdk 26 起可用），引擎只见解码后的行。
 *
 * BOM 剥离在此（IMPORT_SPEC §2 / 边界 #3）：UTF-8 BOM 解码为行首 U+FEFF，
 * 首 emit 前剥除；UTF-16 的 BOM 检测即来自 BOM 本身，同样以行首 U+FEFF 兜底剥除。
 */
public class ContentResolverFileBytesSource(
    private val resolver: ContentResolver,
    private val uri: Uri,
) : FileBytesSource {

    private var offset = 0

    /** 无状态复访：每次读取重开流 + skip 到 [offset]（head ≤8KB，仅 detect 阶段使用，开销可忽略）。 */
    override suspend fun readChunk(maxBytes: Int): ByteArray? = withContext(Dispatchers.IO) {
        openStream().use { input ->
            var toSkip = offset.toLong()
            while (toSkip > 0) {
                val skipped = input.skip(toSkip)
                if (skipped <= 0) return@use null
                toSkip -= skipped
            }
            val buffer = ByteArray(maxBytes)
            var total = 0
            while (total < buffer.size) {
                val read = input.read(buffer, total, buffer.size - total)
                if (read < 0) break
                total += read
            }
            if (total == 0) null else buffer.copyOf(total).also { offset += total }
        }
    }

    private fun openStream(): InputStream =
        resolver.openInputStream(uri) ?: throw IOException("无法读取所选文件")
}

/** 解码行源：[lines] 每次调用重开流（预览/导入各自独立遍历），逐行惰性流出（NFR-2 流式）。 */
public class ContentResolverTextLineSource(
    private val resolver: ContentResolver,
    private val uri: Uri,
) : TextLineSource {

    override fun lines(encoding: DetectedEncoding): Flow<String> = flow {
        val charset = charsetOf(encoding)
        resolver.openInputStream(uri)?.use { raw ->
            BufferedReader(InputStreamReader(raw, charset), IO_BUFFER_BYTES).use { reader ->
                var first = true
                while (true) {
                    val line = reader.readLine() ?: break
                    val stripped = if (first && line.startsWith(BOM_CHAR)) line.substring(1) else line
                    first = false
                    emit(stripped)
                }
            }
        } ?: throw IOException("无法读取所选文件")
    }

    private fun charsetOf(encoding: DetectedEncoding): Charset = when (encoding) {
        is DetectedEncoding.Utf8 -> Charsets.UTF_8
        DetectedEncoding.Utf16LE -> Charset.forName("UTF-16LE")
        DetectedEncoding.Utf16BE -> Charset.forName("UTF-16BE")
        DetectedEncoding.Gb18030 -> Charset.forName("GB18030")
        is DetectedEncoding.Unsupported ->
            throw IllegalArgumentException("编码不受支持：${encoding.reason}")
    }

    private companion object {
        const val IO_BUFFER_BYTES = 8 * 1024
        const val BOM_CHAR = "\uFEFF"
    }
}
