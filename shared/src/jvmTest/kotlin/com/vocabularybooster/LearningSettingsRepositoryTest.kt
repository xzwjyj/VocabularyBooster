package com.vocabularybooster

import com.vocabularybooster.data.SqlDelightLearningSettingsRepository
import com.vocabularybooster.domain.model.PlaybackToggles
import com.vocabularybooster.domain.repository.LearningSettingsRepository
import com.vocabularybooster.domain.repository.RepositoryValidationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * LearningSettingsRepository（Phase 4 Step 1 裁决 L6 加法扩展）：
 * 新增 commandWindowMs / ttsRate / ttsPitch 三读取——缺键默认、存量读取、
 * 损坏值按既有设置仓储语义失败（RepositoryValidationException，不静默吞损坏数据）；
 * Phase 3 既有 groupSize / playbackToggles 语义回归不变。
 */
class LearningSettingsRepositoryTest {

    private fun newRepo(db: TestDb): LearningSettingsRepository =
        SqlDelightLearningSettingsRepository(db.database, DispatchersForTest)

    @Test
    fun missingKeysReturnBuiltInDefaults() = runTest {
        val repo = newRepo(TestDb.inMemory())
        assertEquals(4_000L, repo.getCommandWindowMs())
        assertEquals(1.0f, repo.getTtsRate())
        assertEquals(1.0f, repo.getTtsPitch())
    }

    @Test
    fun storedValuesAreReadBack() = runTest {
        val db = TestDb.inMemory()
        db.database.appSettingQueries.upsertSetting("settings.commandWindowMs", "6000")
        db.database.appSettingQueries.upsertSetting("settings.ttsRate", "1.25")
        db.database.appSettingQueries.upsertSetting("settings.ttsPitch", "0.9")
        val repo = newRepo(db)
        assertEquals(6_000L, repo.getCommandWindowMs())
        assertEquals(1.25f, repo.getTtsRate())
        assertEquals(0.9f, repo.getTtsPitch())
    }

    @Test
    fun corruptValuesFailLikeExistingSettingsSemantics() = runTest {
        val db = TestDb.inMemory()
        db.database.appSettingQueries.upsertSetting("settings.commandWindowMs", "not-json")
        db.database.appSettingQueries.upsertSetting("settings.ttsRate", "not-json")
        db.database.appSettingQueries.upsertSetting("settings.ttsPitch", "not-json")
        val repo = newRepo(db)
        assertFailsWith<RepositoryValidationException> { repo.getCommandWindowMs() }
        assertFailsWith<RepositoryValidationException> { repo.getTtsRate() }
        assertFailsWith<RepositoryValidationException> { repo.getTtsPitch() }
    }

    @Test
    fun outOfRangeValuesFail() = runTest {
        val db = TestDb.inMemory()
        val repo = newRepo(db)
        db.database.appSettingQueries.upsertSetting("settings.commandWindowMs", "0")
        assertFailsWith<RepositoryValidationException> { repo.getCommandWindowMs() }
        db.database.appSettingQueries.upsertSetting("settings.ttsRate", "-1.0")
        assertFailsWith<RepositoryValidationException> { repo.getTtsRate() }
        db.database.appSettingQueries.upsertSetting("settings.ttsPitch", "0")
        assertFailsWith<RepositoryValidationException> { repo.getTtsPitch() }
    }

    // —— Phase 3 语义回归（裁决 L6：原方法语义完全不变）——

    @Test
    fun phaseThreeGroupSizeAndTogglesSemanticsUnchanged() = runTest {
        val db = TestDb.inMemory()
        val repo = newRepo(db)
        assertEquals(LearningSettingsRepository.DEFAULT_GROUP_SIZE, repo.getGroupSize())
        assertEquals(PlaybackToggles.DEFAULT, repo.getPlaybackToggles())

        db.database.appSettingQueries.upsertSetting("settings.groupSize", "7")
        db.database.appSettingQueries.upsertSetting(
            "settings.playbackToggles",
            """{"pronunciation":false,"spelling":true,"meaningEn":true,"meaningCn":true,"example":true,"exampleCn":false}""",
        )
        assertEquals(7, repo.getGroupSize())
        assertEquals(PlaybackToggles(pronunciation = false, exampleCn = false), repo.getPlaybackToggles())

        db.database.appSettingQueries.upsertSetting("settings.groupSize", "0")
        assertFailsWith<RepositoryValidationException> { repo.getGroupSize() }
        db.database.appSettingQueries.upsertSetting("settings.groupSize", "broken")
        assertFailsWith<RepositoryValidationException> { repo.getGroupSize() }
    }
}
