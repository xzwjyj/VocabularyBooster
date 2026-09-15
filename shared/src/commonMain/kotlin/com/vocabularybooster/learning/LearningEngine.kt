package com.vocabularybooster.learning

import com.vocabularybooster.domain.model.SessionSnapshot

/**
 * 学习引擎公共 API（LEARNING_ENGINE_SPEC §11 接口草图，Phase 3 起分步交付）。
 * Step 4 交付 startSession / resumeSession / markMastered；
 * Step 5A 交付 advance（§5 nextWord 推进 + §7 完成接入）；
 * Step 5B 交付恢复完整性（resume 内 §9 检查，TC-LE-10）；
 * Step 5C 交付 exitSession（§8 退出三分支的终态裁决）；
 * Step 5D 交付分支 B 派生（[WordBookDeriver]，TC-LE-07/08）；
 * StateFlow state 随后续 Step 落地（§11 草图的 abandon(reason) 规格未定义行为，暂不实现）。
 */
public interface LearningEngine {

    /**
     * 开始会话（LE spec §3）：编排 StudyQueueBuilder → GroupSplitter → 会话物化。
     * 前置裁决顺序：本不存在（抛 [com.vocabularybooster.domain.repository.RepositoryValidationException]，
     * 沿用 domain 契约）→ EMPTY_BOOK → PLAYBACK_DISABLED → ACTIVE_SESSION_EXISTS。
     * 2026-09-15: 移除 ALL_MASTERED，实现"母本永远可学"。
     */
    public suspend fun startSession(wordBookId: Long): StartResult

    /**
     * 恢复会话（LE spec §9）：读回既有 ACTIVE 会话的持久化快照——
     * 不重建会话、不重算分组、不触碰 masteredAt；会话是学习过程的固化快照，真相在库。
     * 恢复时执行完整性检查（TC-LE-10，Step 5B）：书已删 → 会话安全 ABANDONED +
     * [ResumeResult.Reason.BOOK_DELETED]（提示不静默丢数据）；词已从本移除 →
     * 该词从会话剔除（悬挂行删除，placement 不重编号），其余照常恢复。
     */
    public suspend fun resumeSession(sessionId: Long): ResumeResult

    /**
     * 标记掌握（LE spec §6）：唯一入口，委托 [MasteryMarker]；
     * 语音与按钮完全等价（NFR-8）。
     */
    public suspend fun markMastered(sessionId: Long, wordId: Long, source: MasterySource): MasteryResult

    /**
     * 推进到下一个待播词（LE spec §5 nextWord / FR-7）：纯位置推进，不改掌握状态。
     * 当前位 = SessionWord 持久化 PLAYING 状态推导（§8.3）——选中词置 PLAYING、
     * 原播放词回到 PENDING（Next 跳过留在组内循环），全程零引擎内存位，重启后续推。
     * 完成判据唯一权威 = [CompletionDetector]；会话队列全部掌握 → §7 书级裁决。
     * 会话不存在 → [com.vocabularybooster.domain.repository.RepositoryValidationException]。
     */
    public suspend fun advance(sessionId: Long): AdvanceResult

    /**
     * 退出会话（LE spec §8，Phase 3 Step 5C/5D）：会话 ACTIVE 时按**数据库实时状态**三分支裁决——
     * 分支 C（REMAINING = Q3 = 0）→ COMPLETED；分支 A（零掌握，Q3 > 0）→ ABANDONED，
     * [ExitResult.derivedWordBookId] = null；分支 B（部分掌握，Q3 > 0）→ ABANDONED +
     * [WordBookDeriver] 派生 DERIVED 快照本（返回其 ID；复制交集为空 → null，Case 3 裁决
     * 2026-09-04，不建空本）。均保存学习历史（SessionWord/WordMastery 不动）。
     * 已终态会话重复退出 = 幂等 no-op（endedAt 保持首次终态时刻，不刷新；派生不重复执行，
     * 既有派生本可经 [com.vocabularybooster.domain.repository.WordBookRepository] 血缘查询）。
     * 会话不存在 → [com.vocabularybooster.domain.repository.RepositoryValidationException]。
     * 停止播放通知属 Phase 4；SessionExited 事件/勋章属 EventBus/Phase 6 后续 Step。
     */
    public suspend fun exitSession(sessionId: Long): ExitResult
}

/** 开始结果（LE spec §3 四拒绝原因，与 StudyQueueResult / 设置 / 会话仓储的原因一一映射）。 */
public sealed interface StartResult {

    /** 成功：持久化快照读回（会话 ACTIVE + 物化队列，(groupIndex, orderInGroup) 升序）。 */
    public data class Started(val snapshot: SessionSnapshot) : StartResult

    /** 拒绝（非异常的业务否决，UI 按原因引导）。 */
    public data class Rejected(val reason: Reason) : StartResult

    public sealed interface Reason {

        /** 本无词条（LE spec §10-1）。 */
        public data object EMPTY_BOOK : Reason

        /** 六项播放开关全关（FR-10 验收 / TC-AE-02）。 */
        public data object PLAYBACK_DISABLED : Reason

        /** 已存在 ACTIVE 会话（携带其 ID），UI 引导「恢复」或「放弃旧的」。 */
        public data class ACTIVE_SESSION_EXISTS(val sessionId: Long) : Reason
    }
}

/** 恢复结果：Resumed 携带逐字段读回的持久化快照；拒绝原因沿用领域既有语义。 */
public sealed interface ResumeResult {

    /** 恢复成功：快照即真相（sessionId/wordId/groupIndex/orderInGroup/status/masteredAt/groupSize）。 */
    public data class Resumed(val snapshot: SessionSnapshot) : ResumeResult

    /** 拒绝（会话不存在 / 非 ACTIVE——恢复只针对 ACTIVE 会话，LE spec §9）。 */
    public data class Rejected(val reason: Reason) : ResumeResult

    public enum class Reason {
        /** 会话不存在。 */
        SESSION_NOT_FOUND,

        /** 会话已 COMPLETED/ABANDONED（ACTIVE 无入边，DOMAIN_MODEL §8.2）。 */
        SESSION_NOT_ACTIVE,

        /**
         * 恢复时书已删除（LE spec §10-8，TC-LE-10 Case A）：会话已在恢复事务内
         * 安全 ABANDONED + endedAt——UI 据此提示（不静默丢数据），学习历史保留可查。
         */
        BOOK_DELETED,
    }
}

/** 推进结果（LE spec §11 草图：WordRef | BookComplete）。 */
public sealed interface AdvanceResult {

    /**
     * 下一个待播词。[completedGroupIndex]：目标组存在前一组且该组已无未掌握词时
     * 携带该组号（LE spec §7 GroupCompleted 的无事件载体，UI/统计可用；
     * 推进本身由 §5 自动完成，事件总线属后续 Step）。目标组为 0 组 / 前一组非空集完成
     * （规格外脏数据）为 null。值按每次调用的库中状态纯推导——零内存态的代价是
     * 它是稳定的位置信息而非一次性事件：组内重复 advance 会重复携带同一组号。
     */
    public data class NextWord(val ref: WordRef, val completedGroupIndex: Int?) : AdvanceResult

    /**
     * 会话队列已无未掌握词（LE spec §5 → §7）：Q3 书级裁决后返回——
     * 书完成 → 会话已置 COMPLETED；书在会话中途新增词（规格未定义分支）→ 会话保持 ACTIVE，
     * 终态留待退出三分支（§8）裁决。终态会话重复 advance 同样返回本值（幂等只读）。
     */
    public data object BookComplete : AdvanceResult
}

/** 待播词引用（LE spec §11：只带定位四元组，不携带词条内容——内容装配属播放层 Q4/FR-5）。 */
public data class WordRef(
    val sessionId: Long,
    val wordId: Long,
    val groupIndex: Int,
    val orderInGroup: Int,
)

/**
 * 退出结果（LE spec §11 草图：`ExitResult(derivedWordBookId: Long?)`）。
 * 分支 A（零掌握）/ 分支 C（全部掌握）/ **分支 B 复制交集为空（Case 3 裁决 2026-09-04：
 * 当前母本词条 ∩ SessionWord 非 MASTERED = ∅ → 不建空本）**与终态幂等重入 → null；
 * 分支 B（部分掌握且交集非空）→ 派生 DERIVED 本的 ID（Step 5D，WordBookDeriver）。
 */
public data class ExitResult(
    val derivedWordBookId: Long?,
)
