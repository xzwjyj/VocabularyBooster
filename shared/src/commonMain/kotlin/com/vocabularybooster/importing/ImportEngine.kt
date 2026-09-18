package com.vocabularybooster.importing

import com.vocabularybooster.domain.event.DomainEvent
import com.vocabularybooster.domain.event.DomainEventBus
import com.vocabularybooster.platform.FileBytesSource
import com.vocabularybooster.platform.TextLineSource
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.datetime.Clock

/**
 * TXT 导入引擎（IMPORT_SPEC §5，FR-14，Phase 7）：流程编排 + 去重四层裁决（业务规则只在引擎）。
 *
 * - 流式：逐行消费 [TextLineSource]（内存占用与文件大小无关，仅去重 Set 随词数增长，NFR-2）；
 * - 原子：全部写入发生在 [ImportRepository.withImportTransaction] 单一大事务内——
 *   取消（协程取消，每 [CANCEL_CHECK_EVERY_LINES] 行主动 ensureActive）/ 任何异常 → 整体回滚，
 *   目标本零残留（取消 ≤1s 生效，NFR-2/TC-IMP-04）；
 * - 空本防线（边界 #1）：新建本**延迟到首条 entry 插入**——全空/全非法/全重复文件不建空本；
 * - 守恒（TC-IMP-03）：totalLines = imported + reusedWords + duplicatesInFile + duplicatesInBook + invalid
 *   （updated ⊆ duplicatesInBook，见 [ImportReport]）；
 * - 事件：仅在事务**成功提交且至少写入一行**后发布 [DomainEvent.ImportFinished]（取消/回滚/零写入零事件）。
 */
public class ImportEngine(
    private val bytesSource: FileBytesSource,
    private val lineSource: TextLineSource,
    private val repository: ImportRepository,
    private val eventBus: DomainEventBus,
    private val clock: Clock,
) {

    /** 编码检测：读文件头（≤ [EncodingDetector.HEAD_MAX_BYTES]）→ 决策（确定性，可复现）。 */
    public suspend fun detectEncoding(): DetectedEncoding {
        val chunks = mutableListOf<ByteArray>()
        var collected = 0
        while (collected < EncodingDetector.HEAD_MAX_BYTES) {
            val chunk = bytesSource.readChunk(EncodingDetector.HEAD_MAX_BYTES - collected)
                ?.takeIf { it.isNotEmpty() } // 防御：空块 ≠ EOF
                ?: break
            chunks += chunk
            collected += chunk.size
        }
        val head = ByteArray(collected)
        var offset = 0
        chunks.forEach { it.copyInto(head, offset); offset += it.size }
        return EncodingDetector.detect(head)
    }

    /** 预览（IMPORT_SPEC §5：解析前 [maxLines] 行，合法/非法标记由 UI 呈现）。 */
    public suspend fun preview(encoding: DetectedEncoding, maxLines: Int = 20): List<PreviewLine> =
        lineSource.lines(encoding).take(maxLines)
            .map { PreviewLine(raw = it, kind = LineParser.parse(it)) }
            .toList()

    /**
     * 执行导入。取消 = 调用方协程取消（当前事务整体回滚后向上传播
     * [kotlinx.coroutines.CancellationException]）；[onProgress] 节流 [PROGRESS_THROTTLE_MS]（注入 [Clock]，铁律 10）。
     */
    public suspend fun import(
        encoding: DetectedEncoding,
        target: ImportTarget,
        onProgress: (ImportProgress) -> Unit = {},
    ): ImportReport {
        val state = RunState(nowMs = clock.now().toEpochMilliseconds()).apply {
            targetBookId = (target as? ImportTarget.ExistingBook)?.wordBookId
            pendingBookName = (target as? ImportTarget.NewBook)?.name
        }
        val report = repository.withImportTransaction {
            if (state.targetBookId != null) {
                state.nextEntryOrder = (maxEntryOrder(state.targetBookId!!) ?: -1L) + 1L
            }
            lineSource.lines(encoding).collect { raw ->
                if (++state.linesSinceActiveCheck >= CANCEL_CHECK_EVERY_LINES) {
                    state.linesSinceActiveCheck = 0
                    currentCoroutineContext().ensureActive() // 取消 ≤1s 生效（NFR-2）
                }
                when (val parsed = LineParser.parse(raw)) {
                    is ParsedLine.Ignored -> return@collect
                    is ParsedLine.Invalid -> {
                        state.totalLines++
                        state.invalid++
                        if (state.invalidSamples.size < MAX_INVALID_SAMPLES) {
                            state.invalidSamples += parsed.raw
                        }
                    }
                    is ParsedLine.WordOnly ->
                        state.consume(this@withImportTransaction, parsed.normalizedText, parsed.text, null)
                    is ParsedLine.WordWithTranslation -> state.consume(
                        this@withImportTransaction,
                        parsed.normalizedText,
                        parsed.text,
                        parsed.translation,
                    )
                }
                val nowMs = clock.now().toEpochMilliseconds()
                if (nowMs - state.lastProgressAtMs >= PROGRESS_THROTTLE_MS) {
                    state.lastProgressAtMs = nowMs
                    onProgress(ImportProgress(state.totalLines, state.imported, state.invalid))
                }
            }
            state.toReport()
        }
        if (report.imported + report.reusedWords + report.updated > 0) {
            eventBus.publish(
                DomainEvent.ImportFinished(
                    targetWordBookId = report.targetWordBookId,
                    imported = report.imported,
                    reusedWords = report.reusedWords,
                    duplicatesInFile = report.duplicatesInFile,
                    duplicatesInBook = report.duplicatesInBook,
                    updated = report.updated,
                    invalid = report.invalid,
                ),
            )
        }
        return report
    }

    /** 单次导入的可变累积态（引擎内私有；守恒计数 + 去重集 + 延迟建本）。 */
    private class RunState(val nowMs: Long) {
        val seenInFile = HashSet<String>()
        val invalidSamples = mutableListOf<String>()
        var totalLines = 0L
        var imported = 0L
        var reusedWords = 0L
        var duplicatesInFile = 0L
        var duplicatesInBook = 0L
        var updated = 0L
        var invalid = 0L
        var targetBookId: Long? = null
        var pendingBookName: String? = null
        var nextEntryOrder = 0L
        var linesSinceActiveCheck = 0
        var lastProgressAtMs = 0L

        /** 提交前汇总（守恒计数 → 报告；invalidSamples 封样）。 */
        fun toReport(): ImportReport = ImportReport(
            targetWordBookId = targetBookId ?: 0L,
            totalLines = totalLines,
            imported = imported,
            reusedWords = reusedWords,
            duplicatesInFile = duplicatesInFile,
            duplicatesInBook = duplicatesInBook,
            updated = updated,
            invalid = invalid,
            invalidSamples = invalidSamples.toList(),
        )

        /** 去重四层（IMPORT_SPEC §4 顺序裁决；每行恰落一个桶，守恒由 TC-IMP-03 锁定）。 */
        fun consume(session: ImportSession, normalizedText: String, text: String, translation: String?) {
            totalLines++
            // ① 文件内：normalizedText 首见保留
            if (seenInFile.add(normalizedText)) {
                val wordId = session.findWordIdByNormalizedText(normalizedText)
                if (wordId == null) {
                    // ② 新词：插 Word + entry（新建本在此延迟创建——空本防线，边界 #1）
                    ensureBook(session)
                    val newWordId = session.insertWord(text, normalizedText, nowMs)
                    session.insertEntry(targetBookId!!, newWordId, nextEntryOrder++, translation, nowMs)
                    imported++
                } else {
                    val entry = targetBookId?.let { session.findEntry(it, wordId) }
                    if (entry == null) {
                        // ③ 全局复用（且不在本）：复用 wordId + 新 entry
                        ensureBook(session)
                        session.insertEntry(targetBookId!!, wordId, nextEntryOrder++, translation, nowMs)
                        reusedWords++
                    } else if (translation != null && entry.pendingTranslation == null) {
                        // ④ 本内已有且译文缺位 → 补写（updated ⊆ duplicatesInBook）
                        session.updateEntryPendingTranslation(targetBookId!!, wordId, translation)
                        duplicatesInBook++
                        updated++
                    } else {
                        duplicatesInBook++
                    }
                }
            } else {
                duplicatesInFile++
            }
        }

        private fun ensureBook(session: ImportSession) {
            if (targetBookId == null) {
                targetBookId = session.createWordBook(pendingBookName!!, nowMs)
                nextEntryOrder = 0L
            }
        }
    }

    private companion object {
        const val CANCEL_CHECK_EVERY_LINES = 256
        const val MAX_INVALID_SAMPLES = 20
        const val PROGRESS_THROTTLE_MS = 100L
    }
}
