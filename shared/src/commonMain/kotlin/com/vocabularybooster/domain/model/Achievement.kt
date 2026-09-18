package com.vocabularybooster.domain.model

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

/**
 * Achievement 聚合领域模型（DOMAIN_MODEL §2.10，FR-13，Phase 6）。
 * 与 SQLDelight 生成行类型解耦：db 行（payloadJson TEXT）→ 领域映射见 data/Mappers.kt。
 */

/** 勋章类型（v1：BOOK_COMPLETED；预留类型随未来 Phase 增补，ACHIEVEMENT_SPEC §1）。 */
public enum class AchievementType { BOOK_COMPLETED }

/**
 * BOOK_COMPLETED 载荷快照（授予时点冻结，ACHIEVEMENT_SPEC §2）：
 * 此后生词本改名、派生、词条增删均不影响已获勋章的真实性。
 * finishedAt = 会话 endedAt（本次完成掌握的时点）；earnedAt 与之同值（确定性，v1.1）。
 */
@Serializable
public data class BookCompletedPayload(
    val bookName: String,
    val wordCount: Int,
    val finishedAt: Instant,
)

public data class Achievement(
    val achievementId: Long,
    val type: AchievementType,
    val wordBookId: Long?,
    val payload: BookCompletedPayload,
    val earnedAt: Instant,
)
