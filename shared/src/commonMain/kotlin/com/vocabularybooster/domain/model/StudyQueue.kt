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
 * 建队输入快照（单事务一致读取）：本存在性 + 总词条数 + Q2 队列（Q2 原样，entryOrder ASC）。
 * 排序由 Q2 保证，消费方不重排；区分「本不存在 / 空本 / 有词」所需的二元信息齐备。
 * 2026-09-15: 移除未掌握过滤，实现"母本永远可学"。
 */
public data class StudyQueueSnapshot(
    public val wordBookExists: Boolean,
    public val totalEntryCount: Int,
    public val studyEntries: List<StudyQueueEntryRef>,  // 之前叫 unmasteredEntries，现为所有词
)
