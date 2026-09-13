package com.vocabularybooster.domain.repository

import com.vocabularybooster.domain.model.PlaybackContent

/**
 * 播放内容读取端口（Phase 4 Step 1，AUDIO §2）：
 * 按（生词本, 词）装配 SegmentBuilder 输入——Word + 选中释义（Q4）+ 选中例句（Q4b），
 * 单事务一致性读取（definitions 与 examples 取自同一快照，无可观察错位）。
 * 纯读路径：不引入任何写事务。
 */
public interface PlaybackContentRepository {

    /**
     * 播放内容；词条不存在于该本（WordBookEntry 缺失）或词行缺失 → null
     * （沿用仓储读取惯例：读不命中返回 null，不抛）。
     * entry 存在但零选择（导入词）→ 空 [PlaybackContent]（合法，L2 由编排层处理）。
     */
    public suspend fun getPlaybackContent(wordBookId: Long, wordId: Long): PlaybackContent?
}
