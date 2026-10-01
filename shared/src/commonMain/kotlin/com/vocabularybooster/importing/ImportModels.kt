package com.vocabularybooster.importing

import com.vocabularybooster.domain.dictionary.DictionaryDefinitionEntry

/**
 * 导入领域模型（IMPORT_SPEC §5/§6，FR-14，Phase 7）。
 */

/** 导入目标（FR-14：已有本，或借此新建）。 */
public sealed interface ImportTarget {

    public data class ExistingBook(val wordBookId: Long) : ImportTarget

    public data class NewBook(val name: String) : ImportTarget
}

/**
 * 结果报告（IMPORT_SPEC §6）。守恒恒等式（TC-IMP-03 强制）：
 * `totalLines = imported + reusedWords + duplicatesInFile + duplicatesInBook + invalid`；
 * `updated ⊆ duplicatesInBook`（补写译文行同时计入本内重复——复用「且不在本」口径，
 * IMPORT_SPEC §6 公式注记的落地裁决，1.1 记录）。
 */
public data class ImportReport(
    val targetWordBookId: Long,
    val totalLines: Long,          // 有效非空行数（Ignored 不计）
    val imported: Long,            // 新增词条（新词或复用词 + 新 entry）
    val reusedWords: Long,         // 复用全局已有词且新入本
    val duplicatesInFile: Long,    // 文件内重复（normalizedText 首见保留）
    val duplicatesInBook: Long,    // 目标本已存在（含补写译文的 updated 行）
    val updated: Long,            // 补写了临时译文（⊆ duplicatesInBook）
    val invalid: Long,
    val invalidSamples: List<String>, // ≤ 20 条原文，供 UI 呈现
    val enriched: Long = 0L,       // SCR-TXTDICTENRICH：词典富化成功行（⊆ imported + reusedWords）
    val enrichedMatched: Long = 0L, // 其中按译文匹配出释义子集的行（其余为裸词/零匹配兜底全量）
)

/** 进度回调载荷（IMPORT_SPEC §5：节流 100ms；行数计数即进度呈现——总量流式未知，v1 不做百分比）。 */
public data class ImportProgress(
    val linesRead: Long,
    val imported: Long,
    val invalid: Long,
)

/** 预览行（IMPORT_SPEC §5：解析前 20 行，合法/非法标记）。 */
public data class PreviewLine(
    val raw: String,
    val kind: ParsedLine,
)

/**
 * 导入仓储端口（IMPORT_SPEC §1/§5）：**单一大事务**承载全部写入——
 * 块内异常/协程取消 → 整体回滚，目标本保持导入前原样（取消/失败不残留半成品，FR-14）。
 * 事务内的去重裁决（四层规则）在 ImportEngine——本端口只提供原子操作面。
 */
public interface ImportRepository {

    /**
     * 单一大事务：[block] 在事务作用域内执行；正常返回即提交，
     * 抛异常（含 CancellationException）→ 整体回滚并向上传播。
     */
    public suspend fun <T> withImportTransaction(block: suspend ImportSession.() -> T): T
}

/** 事务作用域内的原子操作面（全部同步小查询/小写入，去重决策在引擎）。 */
@Suppress("TooManyFunctions") // 导入原子操作面：Phase 7 基础 8 + SCR-TXTDICTENRICH 富化 4（加法扩展，职责内聚不拆分）
public interface ImportSession {

    /**
     * 目标本词条当前态（[findEntry] 返回 null = 词条不存在）：临时译文 + 是否已有释义勾选。
     * SCR-TXTDICTENRICH：富化词条正式释义在位（勾选行非空）→ re-import 不再补写临时译文。
     */
    public data class ExistingEntry(
        val pendingTranslation: String?,
        val hasDefinitionSelections: Boolean = false,
    )

    /**
     * 释义 + 例句 id 视图（SCR-TXTDICTENRICH 富化）：既有行只读查询与词典导入
     * 新增行共用——meaningEN/CN 供 [SenseMatcher] 匹配，partOfSpeech 供 v2 词性
     * 过滤（行内标记 n./v./vt./vi./adj./adv./int. → 规范名），exampleIds 供勾选行写入。
     */
    public data class DefinitionWithExamples(
        val definitionEntryId: Long,
        val partOfSpeech: String,
        val meaningEN: String,
        val meaningCN: String,
        val exampleIds: List<Long>,
    )

    public fun createWordBook(name: String, nowMs: Long): Long

    public fun findWordIdByNormalizedText(normalizedText: String): Long?

    public fun insertWord(text: String, normalizedText: String, nowMs: Long): Long

    public fun findEntry(wordBookId: Long, wordId: Long): ExistingEntry?

    /** 本内当前最大 entryOrder（空本返回 null；引擎缓存自增，避免逐行 MAX 扫描）。 */
    public fun maxEntryOrder(wordBookId: Long): Long?

    /** 返回新建 entryId（SCR-TXTDICTENRICH 起富化需据此写勾选行）。 */
    public fun insertEntry(
        wordBookId: Long,
        wordId: Long,
        entryOrder: Long,
        pendingTranslation: String?,
        nowMs: Long,
    ): Long

    /** 补写译文（IMPORT_SPEC §4 规则 3；仅引擎在「本行带译文且库内为空」时调用）。 */
    public fun updateEntryPendingTranslation(wordBookId: Long, wordId: Long, pendingTranslation: String)

    /** 词的既有释义 + 例句（Q1 排序契约，同 DefinitionEntry.sq selectDefinitionsForWord）。 */
    public fun findDefinitionsForWord(wordId: Long): List<DefinitionWithExamples>

    /** 从词典释义建行（含例句）；返回新行视图（富化的「词缺释义」母数据补齐分支）。 */
    public fun importDefinitionWithExamples(
        wordId: Long,
        definition: DictionaryDefinitionEntry,
    ): DefinitionWithExamples

    /** 只补空缺音标（COALESCE 语义：已有值绝不覆盖，同 backfill 规则）。 */
    public fun fillBlankPronunciation(wordId: Long, ipaAm: String?, ipaBr: String?, nowMs: Long)

    /** 勾选行（FR-5 选择粒度）：释义勾选 + 例句勾选（视频导入全选同款写入）。 */
    public fun insertEntryDefinitionSelection(wordBookEntryId: Long, definitionEntryId: Long)

    public fun insertExampleSelection(wordBookEntryId: Long, exampleId: Long)
}
