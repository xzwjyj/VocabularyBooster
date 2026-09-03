package com.vocabularybooster.domain.model

/**
 * WordBook 聚合领域模型（DOMAIN_MODEL §2.4–§2.6b，决策 D2/D3）。
 */

/** ORIGINAL = 用户创建的永久母本；DERIVED = 会话退出派生的未掌握快照（D3）。 */
public enum class WordBookType { ORIGINAL, DERIVED }

public data class WordBook(
    val wordBookId: Long,
    val type: WordBookType,
    val name: String,
    val description: String? = null,
    val parentWordBookId: Long? = null,
    val sourceSessionId: Long? = null,
)

/** 生词本列表摘要（Q6）。 */
public data class WordBookSummary(
    val wordBook: WordBook,
    val entryCount: Int,
)

/** 本内词条（本详情列表用；引用全局 Word，不复制）。 */
public data class WordBookWord(
    val wordBookEntryId: Long,
    val wordId: Long,
    val wordText: String,
    val entryOrder: Int,
    val pendingTranslation: String? = null,
)

/**
 * 一条释义的收藏选择（FR-5，PROJECT_SPEC v1.3）：
 * exampleIds = 勾选的例句集合（可空 = 只保存释义；Example 自身原子，见 FR-3）。
 */
public data class DefinitionSelection(
    val definitionEntryId: Long,
    val exampleIds: List<Long> = emptyList(),
)

/** 保存流请求：词 → 多个生词本 + 释义/例句选择（FR-5 事务单元，DATABASE_SCHEMA §4）。 */
public data class SaveWordRequest(
    val wordId: Long,
    val wordBookIds: List<Long>,
    val selections: List<DefinitionSelection>,
)
