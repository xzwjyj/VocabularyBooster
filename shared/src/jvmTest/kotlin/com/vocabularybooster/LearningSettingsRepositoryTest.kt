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
 * Phase 8 写路径：五 setter 往返（文件库 close/reopen 验证持久化）、
 * 越界写拒绝且原值不变、upsert 覆盖旧值。
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

    // —— Phase 8.6（FR-19）：音色两键——写/读/清除（null）/损坏防御性降级（与其他键语义不同：热路径不抛） ——

    @Test
    fun voiceKeysRoundTripAndClear() = runTest {
        val db = TestDb.inMemory()
        val repo = newRepo(db)
        assertEquals(null, repo.getTtsVoiceEn()) // 缺键 = null（跟随系统）
        assertEquals(null, repo.getTtsVoiceZh())
        repo.setTtsVoiceEn("en-voice-1")
        repo.setTtsVoiceZh("zh-voice-1")
        assertEquals("en-voice-1", repo.getTtsVoiceEn())
        assertEquals("zh-voice-1", repo.getTtsVoiceZh())
        repo.setTtsVoiceEn(null) // 清除 = 删键
        repo.setTtsVoiceZh(null)
        assertEquals(null, repo.getTtsVoiceEn())
        assertEquals(null, repo.getTtsVoiceZh())
        assertEquals(null, db.database.appSettingQueries.selectSetting("settings.ttsVoiceEn").executeAsOneOrNull())
        // 空白拒绝（清除请传 null）
        assertFailsWith<RepositoryValidationException> { repo.setTtsVoiceEn(" ") }
    }

    @Test
    fun corruptVoiceValueDegradesToNullNotException() = runTest {
        val db = TestDb.inMemory()
        db.database.appSettingQueries.upsertSetting("settings.ttsVoiceEn", "not-json")
        db.database.appSettingQueries.upsertSetting("settings.ttsVoiceZh", "not-json")
        val repo = newRepo(db)
        // 音色为设备相关数据可自然失效——热路径防御性降级，不抛损坏异常（端口 KDoc 注记）
        assertEquals(null, repo.getTtsVoiceEn())
        assertEquals(null, repo.getTtsVoiceZh())
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

    // —— Phase 8 写路径（FR-15 即时持久化）——

    @Test
    fun writesRoundTripAcrossReopen() = runTest {
        val db = TestDb.file()
        val repo = newRepo(db)
        repo.setGroupSize(7)
        repo.setCommandWindowMs(6_000L)
        repo.setTtsRate(1.25f)
        repo.setTtsPitch(0.9f)
        repo.setPlaybackToggles(PlaybackToggles(pronunciation = false, exampleCn = false))
        db.close()

        val reopened = TestDb.fileExisting(db.path!!)
        val reread = newRepo(reopened)
        assertEquals(7, reread.getGroupSize())
        assertEquals(6_000L, reread.getCommandWindowMs())
        assertEquals(1.25f, reread.getTtsRate())
        assertEquals(0.9f, reread.getTtsPitch())
        assertEquals(PlaybackToggles(pronunciation = false, exampleCn = false), reread.getPlaybackToggles())
        reopened.close()
    }

    @Test
    fun outOfRangeWritesAreRejectedAndLeaveStoredValueIntact() = runTest {
        val repo = newRepo(TestDb.inMemory())
        repo.setGroupSize(5)
        repo.setCommandWindowMs(5_000L)
        repo.setTtsRate(1.5f)
        repo.setTtsPitch(1.2f)

        assertFailsWith<RepositoryValidationException> { repo.setGroupSize(0) }
        assertFailsWith<RepositoryValidationException> { repo.setCommandWindowMs(0L) }
        assertFailsWith<RepositoryValidationException> { repo.setTtsRate(0f) }
        assertFailsWith<RepositoryValidationException> { repo.setTtsPitch(-1f) }

        assertEquals(5, repo.getGroupSize())
        assertEquals(5_000L, repo.getCommandWindowMs())
        assertEquals(1.5f, repo.getTtsRate())
        assertEquals(1.2f, repo.getTtsPitch())
    }

    @Test
    fun upsertOverwritesPreviousValue() = runTest {
        val repo = newRepo(TestDb.inMemory())
        repo.setCommandWindowMs(6_000L)
        repo.setCommandWindowMs(8_000L)
        assertEquals(8_000L, repo.getCommandWindowMs())
    }
}
