package com.vocabularybooster.importing

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
public interface ImportSession {

    /** 目标本词条的当前 pendingTranslation（null = 无译文；[findEntry] 返回 null = 词条不存在）。 */
    public data class ExistingEntry(val pendingTranslation: String?)

    public fun createWordBook(name: String, nowMs: Long): Long

    public fun findWordIdByNormalizedText(normalizedText: String): Long?

    public fun insertWord(text: String, normalizedText: String, nowMs: Long): Long

    public fun findEntry(wordBookId: Long, wordId: Long): ExistingEntry?

    /** 本内当前最大 entryOrder（空本返回 null；引擎缓存自增，避免逐行 MAX 扫描）。 */
    public fun maxEntryOrder(wordBookId: Long): Long?

    public fun insertEntry(
        wordBookId: Long,
        wordId: Long,
        entryOrder: Long,
        pendingTranslation: String?,
        nowMs: Long,
    )

    /** 补写译文（IMPORT_SPEC §4 规则 3；仅引擎在「本行带译文且库内为空」时调用）。 */
    public fun updateEntryPendingTranslation(wordBookId: Long, wordId: Long, pendingTranslation: String)
}
