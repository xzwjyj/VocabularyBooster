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
