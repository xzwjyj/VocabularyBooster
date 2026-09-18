package com.vocabularybooster.domain.event

import kotlinx.serialization.Serializable

/**
 * 领域事件（DOMAIN_MODEL §9，Phase 6/7 落地）：进程内通知媒介，
 * @Serializable 面向未来持久化/跨端复用；发送方不依赖消费结果（失败隔离，授予路径异常只记日志）。
 * v1 事件集：[WordBookCompleted]（编排器发布）→ [AchievementUnlocked]（勋章引擎发布）；
 * [ImportFinished]（ImportEngine 发布，Phase 7）。
 */
@Serializable
public sealed interface DomainEvent {

    /**
     * 生词本学习完成（FR-8/TC-AC-05 顺序锚点）：由 PlaybackOrchestrator 在
     * 播放端口全部停止**之后**发布（advance 完成路径 / exit 分支 C 两处锚点）。
     * 只携带定位 ID——消费方（AchievementEngine）防御复核以库中数据为准，不信事件载荷。
     */
    @Serializable
    public data class WordBookCompleted(
        val sessionId: Long,
        val wordBookId: Long,
    ) : DomainEvent

    /**
     * 勋章首次授予（幂等语义：已授予路径重复授予不发本事件，ACHIEVEMENT_SPEC §2）。
     * type = AchievementType 名称（事件层不依赖枚举，未来新增类型零事件结构变更）。
     */
    @Serializable
    public data class AchievementUnlocked(
        val achievementId: Long,
        val type: String,
        val wordBookId: Long?,
    ) : DomainEvent

    /**
     * TXT 导入完成（FR-14，IMPORT_SPEC §5/§6，Phase 7）：仅在导入事务**成功提交后**发布
     * （取消/失败/回滚零事件）；载荷 = 七项计数（守恒口径见 IMPORT_SPEC §6）。
     */
    @Serializable
    public data class ImportFinished(
        val targetWordBookId: Long,
        val imported: Long,
        val reusedWords: Long,
        val duplicatesInFile: Long,
        val duplicatesInBook: Long,
        val updated: Long,
        val invalid: Long,
    ) : DomainEvent
}
