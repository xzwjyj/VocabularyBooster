package com.vocabularybooster.domain.repository

import com.vocabularybooster.domain.model.Achievement
import com.vocabularybooster.domain.model.BookCompletedPayload

/**
 * 勋章仓储端口（Phase 6，FR-13；表/唯一索引/查询自 schema v1 在位，零迁移）。
 * 授予幂等三层（ACHIEVEMENT_SPEC v1.1）：预检 selectByTypeAndBook → INSERT OR IGNORE →
 * 唯一索引 (type, wordBookId) 兜底——重复事件 / 重学同本再完成都不可能产生第二行。
 */
public interface AchievementRepository {

    /**
     * 幂等授予 BOOK_COMPLETED 勋章（单事务：预检 → 插入 → 回读）。
     * 已授予 → 零写入、返回既有行且 [GrantAchievementResult.firstGrant] = false。
     */
    public suspend fun grantBookCompleted(
        wordBookId: Long,
        payload: BookCompletedPayload,
    ): GrantAchievementResult

    /** 勋章墙（earnedAt 倒序，全部类型统一渲染，ACHIEVEMENT_SPEC §3）。 */
    public suspend fun getAchievements(): List<Achievement>

    /** 指定生词本的 BOOK_COMPLETED 勋章；未获得 → null（完成仪式页查询）。 */
    public suspend fun getBookCompletedFor(wordBookId: Long): Achievement?
}

/** 授予结果：[achievement] 恒为该书勋章行（本次新授或既有）；[firstGrant] = 本次为新授予。 */
public data class GrantAchievementResult(
    val achievement: Achievement,
    val firstGrant: Boolean,
)
