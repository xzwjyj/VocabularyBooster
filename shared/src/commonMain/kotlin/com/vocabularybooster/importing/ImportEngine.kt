package com.vocabularybooster.importing

import com.vocabularybooster.domain.dictionary.DictionaryProvider
import com.vocabularybooster.domain.dictionary.DictionaryWord
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
 * - 事件：仅在事务**成功提交且至少写入一行**后发布 [DomainEvent.ImportFinished]（取消/回滚/零写入零事件）；
 * - 词典富化（SCR-TXTDICTENRICH）：[dictionaryProvider] 非空且命中时，为新建 entry 落
 *   释义 + 例句 + 勾选行（裸词/零匹配兜底全量，带译文走 [SenseMatcher] 子集）；miss → 精确退化
 *   为 Phase 7 原行为。
 */
public class ImportEngine(
    private val bytesSource: FileBytesSource,
    private val lineSource: TextLineSource,
    private val repository: ImportRepository,
    private val eventBus: DomainEventBus,
    private val clock: Clock,
    private val dictionaryProvider: DictionaryProvider? = null,
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
        val state = RunState(
            nowMs = clock.now().toEpochMilliseconds(),
            dictionaryProvider = dictionaryProvider,
        ).apply {
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
                state.consumeParsed(this@withImportTransaction, LineParser.parse(raw))
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

    /** 单次导入的可变累积态（引擎内私有；守恒计数 + 去重集 + 延迟建本 + 富化计数）。 */
    private class RunState(
        val nowMs: Long,
        private val dictionaryProvider: DictionaryProvider?,
    ) {
        val seenInFile = HashSet<String>()
        val invalidSamples = mutableListOf<String>()
        var totalLines = 0L
        var imported = 0L
        var reusedWords = 0L
        var duplicatesInFile = 0L
        var duplicatesInBook = 0L
        var updated = 0L
        var invalid = 0L
        var enriched = 0L
        var enrichedMatched = 0L
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
            enriched = enriched,
            enrichedMatched = enrichedMatched,
        )

        /** 单行裁决入口（IMPORT_SPEC §3/§4）：Ignored 跳过、Invalid 计数封样、有效行走去重四层 [consume]。 */
        suspend fun consumeParsed(session: ImportSession, parsed: ParsedLine) {
            when (parsed) {
                is ParsedLine.Ignored -> return
                is ParsedLine.Invalid -> {
                    totalLines++
                    invalid++
                    if (invalidSamples.size < MAX_INVALID_SAMPLES) {
                        invalidSamples += parsed.raw
                    }
                }
                is ParsedLine.WordOnly -> consume(
                    session, parsed.normalizedText, parsed.text,
                    translation = null, partOfSpeech = null,
                )
                is ParsedLine.WordWithTranslation -> consume(
                    session, parsed.normalizedText, parsed.text,
                    parsed.translation, parsed.partOfSpeech,
                )
            }
        }

        /**
         * 去重四层（IMPORT_SPEC §4 顺序裁决；每行恰落一个桶，守恒由 TC-IMP-03 锁定）。
         * [partOfSpeech] = 行内词性标记规范名（v2，无标记 null）；词性独占行 translation=null
         * → ④ 分支不补写译文（null 守卫天然短路）。
         */
        suspend fun consume(
            session: ImportSession,
            normalizedText: String,
            text: String,
            translation: String?,
            partOfSpeech: String?,
        ) {
            totalLines++
            // ① 文件内：normalizedText 首见保留
            if (seenInFile.add(normalizedText)) {
                val wordId = session.findWordIdByNormalizedText(normalizedText)
                if (wordId == null) {
                    // ② 新词：插 Word + entry（新建本在此延迟创建——空本防线，边界 #1）
                    ensureBook(session)
                    val newWordId = session.insertWord(text, normalizedText, nowMs)
                    insertEntryEnriched(session, newWordId, text, translation, partOfSpeech)
                    imported++
                } else {
                    val entry = targetBookId?.let { session.findEntry(it, wordId) }
                    if (entry == null) {
                        // ③ 全局复用（且不在本）：复用 wordId + 新 entry
                        ensureBook(session)
                        insertEntryEnriched(session, wordId, text, translation, partOfSpeech)
                        reusedWords++
                    } else if (translation != null && entry.pendingTranslation == null &&
                        !entry.hasDefinitionSelections
                    ) {
                        // ④ 本内已有且译文缺位 → 补写（updated ⊆ duplicatesInBook）；
                        //    富化词条（释义勾选在位）不补——正式释义优先，临时译文不得回流；
                        //    勾选不动（宁不动——富化只发生在新建 entry）
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

        /**
         * 新建 entry + 词典富化（SCR-TXTDICTENRICH，IMPORT_SPEC §3/§8）：
         * - provider 未装配 / 未收录 / 释义为空 → 原 Phase 7 行为（pendingTranslation 落库）；
         * - 富化成功 → pendingTranslation 置 null（正式释义在位，§8）；
         * - 母数据 vs 勾选两层分离：词表 Word/DefinitionEntry/Example 是全局母数据——
         *   词无释义（新词/TXT 旧行）时**全量**导入词典释义例句 + 只补空缺 IPA（与种子/
         *   按需导入同构，母数据永不残缺）；生词本侧勾选 = 裸词/零匹配全量（D1 兜底），
         *   带译文命中 = [SenseMatcher] 子集；
         * - 词性标记（v2）：[partOfSpeech] 非空 → 勾选池先收窄到该词性（词性零命中同样
         *   兜底全量，与 D1 同一理由）；母数据导入不受词性影响（全局词条仍全量）；
         * - 词已有释义（查词/视频/种子导入）→ 绝不重建，只在既有行上勾选（FR-5 复用原则）。
         */
        private suspend fun insertEntryEnriched(
            session: ImportSession,
            wordId: Long,
            text: String,
            translation: String?,
            partOfSpeech: String?,
        ) {
            val dict = dictionaryProvider?.lookup(text)
                ?.takeIf { it.definitions.isNotEmpty() }
            val entryId = session.insertEntry(
                targetBookId!!,
                wordId,
                nextEntryOrder++,
                if (dict != null) null else translation,
                nowMs,
            )
            if (dict == null) return

            val matchedSubset = selectSenses(session, entryId, wordId, dict, translation, partOfSpeech)
            enriched++
            if (matchedSubset) enrichedMatched++
        }

        /**
         * 母数据就位（缺则全量补齐）→ 勾选池收窄（词性 → 译文两级过滤，各自零命中
         * 兜底到上一级全量）→ 勾选行写入；返回是否为译文匹配子集（enrichedMatched 口径：
         * 词性独占行不算译文匹配——它没有译文）。
         */
        @Suppress("LongParameterList") // 富化裁决内聚集参数（词定位 + 词典 + 行内两级过滤输入），内聚小函数不拆参
        private suspend fun selectSenses(
            session: ImportSession,
            entryId: Long,
            wordId: Long,
            dict: DictionaryWord,
            translation: String?,
            partOfSpeech: String?,
        ): Boolean {
            val defs = session.findDefinitionsForWord(wordId).ifEmpty {
                session.fillBlankPronunciation(wordId, dict.ipaAm, dict.ipaBr, nowMs)
                dict.definitions.map { session.importDefinitionWithExamples(wordId, it) }
            }
            val posMatched = partOfSpeech
                ?.let { pos -> defs.filter { it.partOfSpeech == pos } }
                .orEmpty()
            val pool = if (posMatched.isNotEmpty()) posMatched else defs // 词性零命中 → 兜底全量（同 D1 理由）
            val matched = translation
                ?.let { t -> pool.filter { SenseMatcher.matches(t, it.meaningEN, it.meaningCN) } }
                .orEmpty()
            val chosen = if (matched.isEmpty()) pool else matched
            chosen.forEach { def ->
                session.insertEntryDefinitionSelection(entryId, def.definitionEntryId)
                def.exampleIds.forEach { session.insertExampleSelection(entryId, it) }
            }
            return matched.isNotEmpty()
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
