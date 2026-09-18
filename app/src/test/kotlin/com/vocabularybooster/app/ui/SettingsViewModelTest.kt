package com.vocabularybooster.app.ui

import com.vocabularybooster.domain.model.PlaybackToggles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 8：SettingsViewModel——快照加载投影 / 变更即时持久化（FR-15 无保存按钮）/
 * 写失败提示并回滚显示。范围校验语义的权威测试在 jvmTest 真实仓储
 * （LearningSettingsRepositoryTest 写路径组）；此处 Fake 只注入成功/失败两态。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private fun newFake(): FakeLearningSettingsRepository = FakeLearningSettingsRepository(
        toggles = PlaybackToggles.DEFAULT,
        groupSize = 7,
        commandWindowMs = 6_000L,
        ttsRate = 1.25f,
        ttsPitch = 0.9f,
    )

    @Test
    fun loadProjectsPersistedValues() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val vm = SettingsViewModel(newFake())
            advanceUntilIdle()
            assertTrue(vm.loaded)
            assertEquals("7", vm.groupSizeText)
            assertEquals(7, vm.groupSize)
            assertEquals(6_000L, vm.commandWindowMs)
            assertEquals(1.25f, vm.ttsRate)
            assertEquals(0.9f, vm.ttsPitch)
            assertEquals(PlaybackToggles.DEFAULT, vm.toggles)
            assertNull(vm.message)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun validGroupSizeTextPersistsImmediately() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val fake = newFake()
            val vm = SettingsViewModel(fake)
            advanceUntilIdle()
            vm.onGroupSizeChange("12")
            advanceUntilIdle()
            assertEquals(12, fake.groupSize)
            assertEquals(12, vm.groupSize)
            assertEquals("12", vm.groupSizeText)
            assertNull(vm.message)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun nonNumericTextOnlyUpdatesDisplay() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val fake = newFake()
            val vm = SettingsViewModel(fake)
            advanceUntilIdle()
            vm.onGroupSizeChange("abc")
            advanceUntilIdle()
            assertEquals("abc", vm.groupSizeText) // 输入中的非数字态不持久化、不报错
            assertEquals(7, fake.groupSize)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun failedWriteShowsMessageAndRevertsDisplay() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val fake = newFake()
            val vm = SettingsViewModel(fake)
            advanceUntilIdle()
            fake.failWrites = true
            vm.onGroupSizeChange("9")
            advanceUntilIdle()
            assertEquals(7, fake.groupSize) // 未写入
            assertEquals("保存失败：测试注入的写入失败", vm.message)
            assertEquals("7", vm.groupSizeText) // 回滚显示当前持久值
            assertEquals(7, vm.groupSize)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun toggleChangePersistsImmediately() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val fake = newFake()
            val vm = SettingsViewModel(fake)
            advanceUntilIdle()
            val updated = PlaybackToggles.DEFAULT.copy(spelling = false)
            vm.updateToggles(updated)
            advanceUntilIdle()
            assertEquals(updated, fake.toggles)
            assertEquals(updated, vm.toggles)
            assertNull(vm.message)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun windowAndTtsChangesPersistImmediately() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val fake = newFake()
            val vm = SettingsViewModel(fake)
            advanceUntilIdle()
            vm.onWindowChange(8_000L)
            vm.onRateChange(1.5f)
            vm.onPitchChange(0.75f)
            advanceUntilIdle()
            assertEquals(8_000L, fake.commandWindowMs)
            assertEquals(1.5f, fake.ttsRate)
            assertEquals(0.75f, fake.ttsPitch)
            assertEquals(8_000L, vm.commandWindowMs)
            assertEquals(1.5f, vm.ttsRate)
            assertEquals(0.75f, vm.ttsPitch)
        } finally {
            Dispatchers.resetMain()
        }
    }
}
