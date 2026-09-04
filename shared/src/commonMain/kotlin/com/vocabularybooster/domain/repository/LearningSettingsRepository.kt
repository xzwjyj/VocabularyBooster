package com.vocabularybooster.domain.repository

import com.vocabularybooster.domain.model.PlaybackToggles

/**
 * 学习设置读取端口（FR-15 / DATABASE_SCHEMA §2.11 AppSetting 内置键）。
 * 引擎只经此端口取设置（LE spec §12：不直读 SQLDelight / Android）；
 * 设置写入（设置页）属 Phase 8，本端口只读。
 */
public interface LearningSettingsRepository {

    /** `settings.groupSize`：会话分组大小来源（LE spec §4，调用前已确定值）。 */
    public suspend fun getGroupSize(): Int

    /** `settings.playbackToggles`：六项播放开关。 */
    public suspend fun getPlaybackToggles(): PlaybackToggles

    public companion object {
        /** 内置默认值（DATABASE_SCHEMA §2.11）；不进入 GroupSplitter——纯函数保持由调用方传入。 */
        public const val DEFAULT_GROUP_SIZE: Int = 10
    }
}
