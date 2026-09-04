package com.vocabularybooster.learning

import com.vocabularybooster.domain.repository.RepositoryValidationException

/**
 * 学习队列构建（LEARNING_ENGINE_SPEC §3，FR-6）：队列 = 生词本当前未掌握词，entryOrder ASC。
 * 纯函数组件（LE spec §12）：只消费数据快照做裁决，不读数据库/设置/时钟/随机源；
 * 快照装配（Q2 结果 + 总词数 + 本存在性）由引擎/数据层负责，排序由 Q2 保证，本层不重排。
 */

/** 队列词引用：Q2 结果的最小消费面（DOMAIN_MODEL §2.5）。 */
public data class StudyQueueWord(
    val wordId: Long,
    val entryOrder: Int,
)

/** 建队输入快照：目标本学习状态（unmasteredQueue = Q2 原样，entryOrder ASC）。 */
public data class WordBookStudySnapshot(
    val wordBookId: Long,
    val wordBookExists: Boolean,
    val totalEntryCount: Int,
    val unmasteredQueue: List<StudyQueueWord>,
)

/**
 * 建队结果：拒绝原因与 LearningEngine.startSession 规格原因一致（LE spec §3），不新增错误类型。
 * 本层只裁决书侧两因；PLAYBACK_DISABLED（设置）与 ACTIVE_SESSION_EXISTS（会话仓储）属引擎层。
 */
public sealed interface StudyQueueResult {

    /** 有序学习队列（entryOrder ASC，未掌握词）。 */
    public data class Queue(val words: List<StudyQueueWord>) : StudyQueueResult

    /** 拒绝（引导 UI 给出明确原因）。 */
    public data class Rejected(val reason: Reason) : StudyQueueResult

    public enum class Reason {
        /** 本无词条（LE spec §10-1）。 */
        EMPTY_BOOK,

        /** 队列为空：全部已掌握，引导查看勋章（LE spec §10-1）。 */
        ALL_MASTERED,
    }
}

public object StudyQueueBuilder {

    /**
     * 快照 → 队列裁决：
     * - 本不存在 → [RepositoryValidationException]（沿用 domain 既有契约，同
     *   WordBookRepository / LearningSessionRepository 的「生词本不存在」路径）；
     * - 本无词条 → [StudyQueueResult.Rejected] EMPTY_BOOK；
     * - 有词但全掌握（队列为空）→ Rejected ALL_MASTERED；
     * - 有未掌握词 → [StudyQueueResult.Queue]（Q2 顺序原样，防御性拷贝，不按 wordId 重排）。
     */
    public fun build(snapshot: WordBookStudySnapshot): StudyQueueResult {
        if (!snapshot.wordBookExists) {
            throw RepositoryValidationException("生词本不存在：wordBookId=${snapshot.wordBookId}")
        }
        require(snapshot.totalEntryCount >= 0) { "totalEntryCount 不能为负：${snapshot.totalEntryCount}" }
        require(snapshot.unmasteredQueue.size <= snapshot.totalEntryCount) {
            "快照不一致：未掌握词数(${snapshot.unmasteredQueue.size}) 超过总词数(${snapshot.totalEntryCount})"
        }
        return when {
            snapshot.totalEntryCount == 0 -> StudyQueueResult.Rejected(StudyQueueResult.Reason.EMPTY_BOOK)
            snapshot.unmasteredQueue.isEmpty() -> StudyQueueResult.Rejected(StudyQueueResult.Reason.ALL_MASTERED)
            else -> StudyQueueResult.Queue(snapshot.unmasteredQueue.toList())
        }
    }
}
