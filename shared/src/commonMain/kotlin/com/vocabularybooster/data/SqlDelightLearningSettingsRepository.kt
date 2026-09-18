package com.vocabularybooster.data

import com.vocabularybooster.db.VocabularyDatabase
import com.vocabularybooster.domain.model.PlaybackToggles
import com.vocabularybooster.domain.repository.LearningSettingsRepository
import com.vocabularybooster.domain.repository.RepositoryValidationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * LearningSettingsRepository 的 SQLDelight 实现（Phase 3 Step 4，FR-15）。
 * 读 AppSetting KV（valueJson = kotlinx-serialization JSON，DATABASE_SCHEMA §2.11）：
 * 键缺失 → 内置默认值；值损坏（JSON 非法/超范围）→ [RepositoryValidationException]
 * （不静默吞损坏数据）。写方法自 Phase 8 设置页：同范围校验、拒绝越界写入，
 * `upsertSetting` 落库（query 自 Phase 1 在位——零迁移）。
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

    // —— Phase 4 Step 1 裁决 L6 加法扩展：缺键默认、损坏/越界按既有语义失败 ——

    override suspend fun getCommandWindowMs(): Long = withContext(dispatcher) {
        val raw = database.appSettingQueries.selectSetting(COMMAND_WINDOW_KEY).executeAsOneOrNull()
            ?: return@withContext LearningSettingsRepository.DEFAULT_COMMAND_WINDOW_MS
        val value = runCatching { json.decodeFromString<Long>(raw) }.getOrElse {
            throw RepositoryValidationException("设置值损坏（key=$COMMAND_WINDOW_KEY）：无法解析为命令窗口时长")
        }
        if (value <= 0L) {
            throw RepositoryValidationException("commandWindowMs 必须 > 0：$value（key=$COMMAND_WINDOW_KEY）")
        }
        value
    }

    override suspend fun getTtsRate(): Float = withContext(dispatcher) {
        val raw = database.appSettingQueries.selectSetting(TTS_RATE_KEY).executeAsOneOrNull()
            ?: return@withContext LearningSettingsRepository.DEFAULT_TTS_RATE
        val value = runCatching { json.decodeFromString<Float>(raw) }.getOrElse {
            throw RepositoryValidationException("设置值损坏（key=$TTS_RATE_KEY）：无法解析为 TTS 语速")
        }
        if (value <= 0f) {
            throw RepositoryValidationException("ttsRate 必须 > 0：$value（key=$TTS_RATE_KEY）")
        }
        value
    }

    override suspend fun getTtsPitch(): Float = withContext(dispatcher) {
        val raw = database.appSettingQueries.selectSetting(TTS_PITCH_KEY).executeAsOneOrNull()
            ?: return@withContext LearningSettingsRepository.DEFAULT_TTS_PITCH
        val value = runCatching { json.decodeFromString<Float>(raw) }.getOrElse {
            throw RepositoryValidationException("设置值损坏（key=$TTS_PITCH_KEY）：无法解析为 TTS 音调")
        }
        if (value <= 0f) {
            throw RepositoryValidationException("ttsPitch 必须 > 0：$value（key=$TTS_PITCH_KEY）")
        }
        value
    }

    // —— Phase 8 设置页写路径：校验镜像读侧（同范围拒绝），JSON 编码 + upsertSetting ——

    override suspend fun setGroupSize(value: Int): Unit = withContext(dispatcher) {
        if (value < 1) {
            throw RepositoryValidationException("groupSize 必须 ≥ 1：$value（key=$GROUP_SIZE_KEY）")
        }
        database.appSettingQueries.upsertSetting(GROUP_SIZE_KEY, json.encodeToString(value))
    }

    override suspend fun setPlaybackToggles(value: PlaybackToggles): Unit = withContext(dispatcher) {
        database.appSettingQueries.upsertSetting(PLAYBACK_TOGGLES_KEY, json.encodeToString(value))
    }

    override suspend fun setCommandWindowMs(value: Long): Unit = withContext(dispatcher) {
        if (value <= 0L) {
            throw RepositoryValidationException("commandWindowMs 必须 > 0：$value（key=$COMMAND_WINDOW_KEY）")
        }
        database.appSettingQueries.upsertSetting(COMMAND_WINDOW_KEY, json.encodeToString(value))
    }

    override suspend fun setTtsRate(value: Float): Unit = withContext(dispatcher) {
        if (value <= 0f) {
            throw RepositoryValidationException("ttsRate 必须 > 0：$value（key=$TTS_RATE_KEY）")
        }
        database.appSettingQueries.upsertSetting(TTS_RATE_KEY, json.encodeToString(value))
    }

    override suspend fun setTtsPitch(value: Float): Unit = withContext(dispatcher) {
        if (value <= 0f) {
            throw RepositoryValidationException("ttsPitch 必须 > 0：$value（key=$TTS_PITCH_KEY）")
        }
        database.appSettingQueries.upsertSetting(TTS_PITCH_KEY, json.encodeToString(value))
    }

    private companion object {
        const val GROUP_SIZE_KEY = "settings.groupSize"
        const val PLAYBACK_TOGGLES_KEY = "settings.playbackToggles"
        const val COMMAND_WINDOW_KEY = "settings.commandWindowMs"
        const val TTS_RATE_KEY = "settings.ttsRate"
        const val TTS_PITCH_KEY = "settings.ttsPitch"

        // 前向兼容：未来新增开关键不破坏旧值读取（未知键忽略，缺省字段取默认）
        val json = Json { ignoreUnknownKeys = true }
    }
}
