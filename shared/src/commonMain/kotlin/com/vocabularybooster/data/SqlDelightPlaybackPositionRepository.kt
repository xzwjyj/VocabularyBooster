package com.vocabularybooster.data

import com.vocabularybooster.db.VocabularyDatabase
import com.vocabularybooster.domain.repository.PlaybackPositionRepository
import com.vocabularybooster.domain.repository.RepositoryValidationException
import com.vocabularybooster.playback.PlaybackPosition
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * PlaybackPositionRepository 的 SQLDelight 实现（Phase 4 Step 1，AUDIO §5）。
 * AppSetting KV 单键 `playback.position`，valueJson = kotlinx-serialization
 * [PlaybackPosition] JSON——零 DDL、零迁移（KV 新键免迁移，DATABASE_SCHEMA §2.11）。
 * 值损坏 → [RepositoryValidationException]（沿用设置仓储语义，不静默吞损坏数据）；
 * 恢复方（编排器）负责 L3 双源调和，本层不读 SessionWord、不做词级裁决。
 */
public class SqlDelightPlaybackPositionRepository(
    private val database: VocabularyDatabase,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : PlaybackPositionRepository {

    override suspend fun save(position: PlaybackPosition): Unit = withContext(dispatcher) {
        database.appSettingQueries.upsertSetting(POSITION_KEY, json.encodeToString(position))
    }

    override suspend fun get(): PlaybackPosition? = withContext(dispatcher) {
        val raw = database.appSettingQueries.selectSetting(POSITION_KEY).executeAsOneOrNull()
            ?: return@withContext null
        runCatching { json.decodeFromString<PlaybackPosition>(raw) }.getOrElse {
            throw RepositoryValidationException("播放位置数据损坏（key=$POSITION_KEY）：无法解析为 PlaybackPosition")
        }
    }

    override suspend fun clear(): Unit = withContext(dispatcher) {
        database.appSettingQueries.deleteSetting(POSITION_KEY)
    }

    private companion object {
        const val POSITION_KEY = "playback.position"

        // 与设置仓储同配置：未知字段忽略（前向兼容），缺省字段取默认
        val json = Json { ignoreUnknownKeys = true }
    }
}
