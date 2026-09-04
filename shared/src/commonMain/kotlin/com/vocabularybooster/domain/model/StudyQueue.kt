package com.vocabularybooster.domain.model

/**
 * 建队输入数据（LEARNING_ENGINE_SPEC §3，FR-6）：
 * Q2 `selectStudyQueue` 结果的最小消费面（wordId + entryOrder），供引擎装配纯 Builder 输入。
 */
public data class StudyQueueEntryRef(
    public val wordId: Long,
    public val entryOrder: Int,
)

/**
 * 建队输入快照（单事务一致读取）：本存在性 + 总词条数 + 未掌握队列（Q2 原样，entryOrder ASC）。
 * 排序由 Q2 保证，消费方不重排；区分「本不存在 / 空本 / 全掌握 / 有未掌握」所需的三元信息齐备。
 */
public data class StudyQueueSnapshot(
    public val wordBookExists: Boolean,
    public val totalEntryCount: Int,
    public val unmasteredEntries: List<StudyQueueEntryRef>,
)
