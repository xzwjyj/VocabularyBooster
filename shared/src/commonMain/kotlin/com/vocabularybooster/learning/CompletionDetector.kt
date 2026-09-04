package com.vocabularybooster.learning

import com.vocabularybooster.domain.model.SessionWord
import com.vocabularybooster.domain.model.SessionWordStatus

/**
 * 完成检测（LEARNING_ENGINE_SPEC §7，FR-8）。纯判断、零副作用：
 * 不查 SQLDelight、不读 Clock/AppSetting/平台 API；输入快照 → 布尔裁决。
 * 状态变更（会话 COMPLETED、勋章、进入下一组）属引擎/勋章层，本组件只裁决。
 */
public object CompletionDetector {

    /**
     * 组完成（LE spec §7）：该组 SessionWord 中**无未掌握词**——
     * 即（组非空 且 全部 status == MASTERED）。「未掌握」与 §5 nextWord 同判据
     * （status != MASTERED；v1 恒等价于 {PENDING, PLAYING}；SKIPPED 若出现按未掌握计）。
     *
     * 空列表 → false：规格未定义空集语义，按事件语义保守处理——
     * 不存在的组/无学习单元不构成"完成"，避免对空集发 GroupCompleted 噪声事件。
     */
    public fun isGroupComplete(groupWords: List<SessionWord>): Boolean =
        groupWords.isNotEmpty() && groupWords.all { it.status == SessionWordStatus.MASTERED }

    /**
     * 会话完成：会话队列全部 SessionWord 均 MASTERED（队列非空——createSession 保证 ≥1 词）。
     * 注意与书级完成的区别：书完成以 Q3（整本未掌握词条数 = 0）为准（见 [isBookComplete]），
     * 两者在「会话中途词被移出本」等边界（LE spec §10-9）下可能不一致，引擎按场景分别消费。
     */
    public fun isSessionComplete(sessionWords: List<SessionWord>): Boolean =
        sessionWords.isNotEmpty() && sessionWords.all { it.status == SessionWordStatus.MASTERED }

    /**
     * 书完成（LE spec §7 / Q3）：整本未掌握词条数 = 0 → WordBookCompleted。
     * 输入为 Q3 countUnmastered 查询结果（数据访问属仓储/引擎装配层）；
     * 负数 → [IllegalArgumentException]（快照自一致性 fail-fast）。
     */
    public fun isBookComplete(unmasteredEntryCount: Int): Boolean {
        require(unmasteredEntryCount >= 0) { "unmasteredEntryCount 不能为负：$unmasteredEntryCount" }
        return unmasteredEntryCount == 0
    }
}
