package com.vocabularybooster.domain.repository

/** 领域层业务校验失败（引擎/仓储对 UI 暴露的受控错误，非平台异常）。 */
public class RepositoryValidationException(public override val message: String) : Exception(message)

/** 删除生词本被拒（FR-4 两类守卫）。 */
public class WordBookDeletionException(
    public val wordBookId: Long,
    public val reason: Reason,
) : Exception("WordBook $wordBookId cannot be deleted: $reason") {

    public enum class Reason {
        /** 已获完成勋章——勋章永久性（FR-13）。 */
        BOOK_HAS_COMPLETION_MEDAL,

        /** 存在派生子本——血缘完整，先处理子本。 */
        HAS_DERIVED_CHILDREN,
    }
}

/**
 * 已存在 ACTIVE 会话，创建被拒（LE spec §3 全局唯一不变量：同一时刻最多一个 ACTIVE）。
 * 约束由引擎在物化事务内检查保证，不依赖 DB 级约束（DATABASE_SCHEMA §2.8 注记）；
 * 引擎捕获后映射为 Rejected(ACTIVE_SESSION_EXISTS)，UI 引导「恢复」或「放弃旧的」。
 */
public class ActiveSessionExistsException(public val activeSessionId: Long) :
    Exception("已存在进行中的学习会话（sessionId=$activeSessionId），请先恢复或放弃该会话")
