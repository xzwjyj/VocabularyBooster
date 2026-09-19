package com.vocabularybooster.domain.model

/**
 * 学习统计领域模型（FR-20，Phase 8.6）。
 * 口径锚定 SessionWord 掌握事件 + LearningSession 时长（DATABASE_SCHEMA Q8/Q9）：
 * - **学习**（learned）= 当日**首次掌握**的去重词数（跨生词本去重）；
 * - **复习**（reviewed）= 掌握事件的发生日**严格晚于**该词首次掌握日的事件（按词去重）——
 *   同日多次掌握不重复计数也不计复习；
 * - **时长** = 已结束会话（endedAt 非空，含 COMPLETED/ABANDONED）的 endedAt−startedAt，
 *   按会话**结束日**归集（跨午夜会话整体归入结束日；崩溃未恢复的 ACTIVE 行不计）。
 * 日界按设备本地时区（仓储构造注入 TimeZone，可测）。
 */
public data class LearningStatsSummary(
    val todayLearnedWords: Int,
    val todayReviewedWords: Int,
    val totalLearnedWords: Int,
    val todayDurationMs: Long,
    val totalDurationMs: Long,
)

/** 统计图粒度：日（近 30 天）/ 月（近 12 个月）/ 年（首事件年至今）。 */
public enum class StatsGranularity { DAY, MONTH, YEAR }

/** 图表单点：key = 日 `yyyy-MM-dd` / 月 `yyyy-MM` / 年 `yyyy`（本地时区）。 */
public data class StatsPoint(
    val key: String,
    val learnedWords: Int,
    val durationMs: Long,
)
