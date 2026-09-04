package com.vocabularybooster.domain.model

import kotlinx.datetime.Instant

/**
 * LearningSession 聚合领域模型（DOMAIN_MODEL §2.8/§2.9）。
 * 与 SQLDelight 生成行类型解耦：db 行 → 领域映射见 data/Mappers.kt。
 */

/** 会话状态（DOMAIN_MODEL §8.2 退出三分支，决策 D1）。 */
public enum class SessionStatus { ACTIVE, COMPLETED, ABANDONED }

/**
 * 会话词状态（DOMAIN_MODEL §8.3）。
 * SKIPPED 为 schema 预留扩展状态，v1 引擎不产生（Next 不改状态，词保持 PENDING 留在组内循环）；
 * 仓储层可读取/表达该状态，但 v1 无任何产生它的业务入口。
 */
public enum class SessionWordStatus { PENDING, PLAYING, MASTERED, SKIPPED }

/** 学习会话（聚合根）：队列物化的载体，崩溃恢复以此为准（FR-6/FR-11）。 */
public data class LearningSession(
    val sessionId: Long,
    val wordBookId: Long,
    val status: SessionStatus,
    val groupSize: Int,
    val startedAt: Instant,
    val endedAt: Instant? = null,
)

/** 会话词（队列物化行）：groupIndex/orderInGroup 在会话开始时固化（FR-6）。 */
public data class SessionWord(
    val sessionId: Long,
    val wordId: Long,
    val groupIndex: Int,
    val orderInGroup: Int,
    val status: SessionWordStatus,
    val masteredAt: Instant? = null,
)

/**
 * 会话创建时的词放置指令（StudyQueueBuilder + GroupSplitter 的输出，LE spec §3/§4）：
 * 队列构成与分组计算属学习引擎，仓储只负责原子物化，不重算 group/order。
 */
public data class SessionWordPlacement(
    val wordId: Long,
    val groupIndex: Int,
    val orderInGroup: Int,
)

/** 恢复读取快照：会话 + 全部队列行（已按 (groupIndex, orderInGroup) 排序，读取确定性）。 */
public data class SessionSnapshot(
    val session: LearningSession,
    val words: List<SessionWord>,
)
