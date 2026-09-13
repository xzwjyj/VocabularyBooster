package com.vocabularybooster.domain.repository

import com.vocabularybooster.playback.PlaybackPosition

/**
 * 播放位置持久化端口（Phase 4 Step 1，AUDIO §5 / NFR-3）：
 * `AppSetting["playback.position"]` KV——每次段切换/暂停写入，会话终态清除。
 *
 * 只承载**段级恢复信息**；**词级真相源 = SessionWord.PLAYING**（裁决 L3，2026-09-05）：
 * 本端口的数据永远不能改变 SessionWord.PLAYING，恢复方负责双源调和
 * （wordId 匹配才采用 segmentIndex/offsetMs，否则忽略，见 AUDIO §5 五步规则）。
 * 全局单键 upsert（ACTIVE 会话唯一性使然），不按 sessionId 分键。
 */
public interface PlaybackPositionRepository {

    /** 写入/覆盖当前位置（同一逻辑位覆盖即最新，AUDIO §5「每次段切换/暂停写入」）。 */
    public suspend fun save(position: PlaybackPosition)

    /** 读取当前位置；键缺失返回 null；值损坏 → [RepositoryValidationException]（沿用设置仓储语义，不静默吞）。 */
    public suspend fun get(): PlaybackPosition?

    /** 清除位置（会话 COMPLETED/ABANDONED 后调用；缺键时为 no-op）。 */
    public suspend fun clear()
}
