package com.vocabularybooster

import com.vocabularybooster.data.SqlDelightPlaybackPositionRepository
import com.vocabularybooster.domain.repository.PlaybackPositionRepository
import com.vocabularybooster.domain.repository.RepositoryValidationException
import com.vocabularybooster.playback.PlaybackPhase
import com.vocabularybooster.playback.PlaybackPosition
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * PlaybackPositionRepository（Phase 4 Step 1，AUDIO §5 / 裁决 L3）：
 * AppSetting["playback.position"] KV 持久化——写读全字段精确、覆盖语义、
 * 清除、缺键 null、损坏值按设置仓储惯例失败、close/reopen 真库持久。
 * 本仓储只管段级恢复信息的存取；词级真相源 = SessionWord.PLAYING（不在本层）。
 */
class PlaybackPositionRepositoryTest {

    private fun newRepo(db: TestDb): PlaybackPositionRepository =
        SqlDelightPlaybackPositionRepository(db.database, DispatchersForTest)

    @Test
    fun missingPositionReturnsNull() = runTest {
        assertNull(newRepo(TestDb.inMemory()).get())
    }

    @Test
    fun saveThenReadRoundTripsAllFields() = runTest {
        val repo = newRepo(TestDb.inMemory())
        val position = PlaybackPosition(
            sessionId = 42L,
            wordId = 7L,
            segmentIndex = 3,
            offsetMs = 12_345L,
            phase = PlaybackPhase.PLAYING,
        )
        repo.save(position)
        assertEquals(position, repo.get())

        val windowPosition = position.copy(segmentIndex = 0, offsetMs = 0L, phase = PlaybackPhase.WINDOW)
        repo.save(windowPosition)
        assertEquals(windowPosition, repo.get())
    }

    @Test
    fun overwriteKeepsLatestLogicalPosition() = runTest {
        val repo = newRepo(TestDb.inMemory())
        val first = PlaybackPosition(1L, 1L, 0, 0L, PlaybackPhase.PLAYING)
        repo.save(first)
        val second = PlaybackPosition(1L, 2L, 4, 999L, PlaybackPhase.PLAYING)
        repo.save(second)
        assertEquals(second, repo.get())
    }

    @Test
    fun clearRemovesPositionAndIsNoOpWhenMissing() = runTest {
        val repo = newRepo(TestDb.inMemory())
        repo.clear() // 缺键清除 = no-op，不抛
        assertNull(repo.get())
        repo.save(PlaybackPosition(1L, 1L, 2, 500L, PlaybackPhase.PLAYING))
        repo.clear()
        assertNull(repo.get())
    }

    @Test
    fun survivesCloseAndReopen() = runTest {
        val db = TestDb.file()
        val position = PlaybackPosition(9L, 5L, 6, 65_432L, PlaybackPhase.PLAYING)
        SqlDelightPlaybackPositionRepository(db.database, DispatchersForTest).save(position)
        db.close()

        val reopened = TestDb.fileExisting(db.path!!)
        val reread = SqlDelightPlaybackPositionRepository(reopened.database, DispatchersForTest).get()
        assertEquals(position, reread)
        reopened.close()
    }

    @Test
    fun corruptValueFailsLikeSettingsConvention() = runTest {
        val db = TestDb.inMemory()
        db.database.appSettingQueries.upsertSetting("playback.position", "{not-json")
        assertFailsWith<RepositoryValidationException> { newRepo(db).get() }
    }

    @Test
    fun saveUsesSingleGlobalKeySlot() = runTest {
        // AUDIO §5：全局单键 upsert——不存在按 sessionId 并列的多行残留
        val db = TestDb.inMemory()
        val repo = newRepo(db)
        repo.save(PlaybackPosition(1L, 1L, 0, 0L, PlaybackPhase.PLAYING))
        repo.save(PlaybackPosition(2L, 3L, 1, 10L, PlaybackPhase.WINDOW))
        repo.clear()
        assertNull(repo.get())
        // 键位唯一性由 KV 主键保证；显式查键防止实现改为多键存储
        assertNull(db.database.appSettingQueries.selectSetting("playback.position").executeAsOneOrNull())
    }
}
