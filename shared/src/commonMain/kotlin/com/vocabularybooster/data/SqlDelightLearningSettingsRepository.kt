package com.vocabularybooster.data

import com.vocabularybooster.db.VocabularyDatabase
import com.vocabularybooster.domain.model.PlaybackToggles
import com.vocabularybooster.domain.repository.LearningSettingsRepository
import com.vocabularybooster.domain.repository.RepositoryValidationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * LearningSettingsRepository 的 SQLDelight 实现（Phase 3 Step 4，FR-15）。
 * 读 AppSetting KV（valueJson = kotlinx-serialization JSON，DATABASE_SCHEMA §2.11）：
 * 键缺失 → 内置默认值；值损坏（JSON 非法/超范围）→ [RepositoryValidationException]
 * （不静默吞损坏数据）。设置写入属 Phase 8 设置页，本实现只读。
 */
public class SqlDelightLearningSettingsRepository(
    private val database: VocabularyDatabase,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : LearningSettingsRepository {

    override suspend fun getGroupSize(): Int = withContext(dispatcher) {
        val raw = database.appSettingQueries.selectSetting(GROUP_SIZE_KEY).executeAsOneOrNull()
            ?: return@withContext LearningSettingsRepository.DEFAULT_GROUP_SIZE
        val value = runCatching { json.decodeFromString<Int>(raw) }.getOrElse {
            throw RepositoryValidationException("设置值损坏（key=$GROUP_SIZE_KEY）：无法解析为分组大小")
        }
        if (value < 1) {
            throw RepositoryValidationException("groupSize 必须 ≥ 1：$value（key=$GROUP_SIZE_KEY）")
        }
        value
    }

    override suspend fun getPlaybackToggles(): PlaybackToggles = withContext(dispatcher) {
        val raw = database.appSettingQueries.selectSetting(PLAYBACK_TOGGLES_KEY).executeAsOneOrNull()
            ?: return@withContext PlaybackToggles.DEFAULT
        runCatching { json.decodeFromString<PlaybackToggles>(raw) }.getOrElse {
            throw RepositoryValidationException("设置值损坏（key=$PLAYBACK_TOGGLES_KEY）：无法解析为播放开关")
        }
    }

    private companion object {
        const val GROUP_SIZE_KEY = "settings.groupSize"
        const val PLAYBACK_TOGGLES_KEY = "settings.playbackToggles"

        // 前向兼容：未来新增开关键不破坏旧值读取（未知键忽略，缺省字段取默认）
        val json = Json { ignoreUnknownKeys = true }
    }
}
