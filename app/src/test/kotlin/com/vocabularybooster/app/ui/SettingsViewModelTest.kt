package com.vocabularybooster.app.ui

import com.vocabularybooster.domain.model.Lang
import com.vocabularybooster.domain.model.PlaybackToggles
import com.vocabularybooster.speech.Readiness
import com.vocabularybooster.speech.SegmentResult
import com.vocabularybooster.speech.SpeakRequest
import com.vocabularybooster.speech.SpeechSynthesizer
import com.vocabularybooster.speech.TtsVoice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 8：SettingsViewModel——快照加载投影 / 变更即时持久化（FR-15 无保存按钮）/
 * 写失败提示并回滚显示。范围校验语义的权威测试在 jvmTest 真实仓储
 * （LearningSettingsRepositoryTest 写路径组）；此处 Fake 只注入成功/失败两态。
 * Phase 8.6（FR-19）：音色列表加载投影 + 选择/清除即时持久化。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val voiceEn = TtsVoice(id = "en-voice-1", displayName = "English A", qualityLabel = "高")
    private val voiceZh = TtsVoice(id = "zh-voice-1", displayName = "中文 A", qualityLabel = "标准")

    /** 音色枚举用最小 Fake（speak/stop 不可达——设置页不播段）；[enGb] 默认空 = 设备无英音。 */
    private class FakeVoiceSynthesizer(
        val en: List<TtsVoice>,
        val zh: List<TtsVoice>,
        val enGb: List<TtsVoice> = emptyList(),
    ) : SpeechSynthesizer {
        override val readiness = MutableStateFlow(Readiness.READY)
        override suspend fun speak(request: SpeakRequest): SegmentResult = error("not used")
        override fun stop() = Unit
        override fun availableVoices(lang: Lang): List<TtsVoice> = when (lang) {
            Lang.EN_US -> en
            Lang.EN_GB -> enGb
            Lang.ZH_CN -> zh
        }
    }

    private fun newFake(): FakeLearningSettingsRepository = FakeLearningSettingsRepository(
        toggles = PlaybackToggles.DEFAULT,
        groupSize = 7,
        commandWindowMs = 6_000L,
        ttsRate = 1.25f,
        ttsPitch = 0.9f,
    )

    private fun newSynth() = FakeVoiceSynthesizer(en = listOf(voiceEn), zh = listOf(voiceZh))

    private val voiceGb = TtsVoice(id = "en-gb-1", displayName = "English UK", qualityLabel = "高")

    @Test
    fun loadProjectsPersistedValues() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val fake = newFake()
            val vm = SettingsViewModel(fake, newSynth())
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
            val vm = SettingsViewModel(fake, newSynth())
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
            val vm = SettingsViewModel(fake, newSynth())
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
            val vm = SettingsViewModel(fake, newSynth())
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
            val vm = SettingsViewModel(fake, newSynth())
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
            val vm = SettingsViewModel(fake, newSynth())
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

    @Test
    fun voicesLoadAndSelectionPersistsImmediately() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val fake = newFake()
            val vm = SettingsViewModel(fake, newSynth())
            advanceUntilIdle()
            // 音色列表投影 + 持久选择预填（null = 跟随系统）
            assertEquals(listOf(voiceEn), vm.voicesEn)
            assertEquals(listOf(voiceZh), vm.voicesZh)
            assertNull(vm.voiceEnId)
            assertNull(vm.voiceZhId)
            vm.onVoiceEnChange(voiceEn.id)
            vm.onVoiceZhChange(voiceZh.id)
            advanceUntilIdle()
            assertEquals(voiceEn.id, fake.ttsVoiceEn)
            assertEquals(voiceZh.id, fake.ttsVoiceZh)
            assertEquals(voiceEn.id, vm.voiceEnId)
            assertEquals(voiceZh.id, vm.voiceZhId)
            assertNull(vm.message)
            // 清除 → 回到跟随系统
            vm.onVoiceEnChange(null)
            advanceUntilIdle()
            assertNull(fake.ttsVoiceEn)
            assertNull(vm.voiceEnId)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun failedVoiceWriteShowsMessage() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val fake = newFake()
            val vm = SettingsViewModel(fake, newSynth())
            advanceUntilIdle()
            fake.failWrites = true
            vm.onVoiceZhChange(voiceZh.id)
            advanceUntilIdle()
            assertNull(fake.ttsVoiceZh) // 未写入
            assertNull(vm.voiceZhId) // 显示不前滚
            assertEquals("保存失败：测试注入的写入失败", vm.message)
        } finally {
            Dispatchers.resetMain()
        }
    }

    // —— FR-22 发音口音：缺省美音、切换即时持久化、英音缺失提示 ——

    @Test
    fun accentDefaultsToEnUsAndSwitchPersistsImmediately() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val fake = newFake()
            val vm = SettingsViewModel(fake, newSynth())
            advanceUntilIdle()
            assertEquals(Lang.EN_US, vm.ttsAccent) // 缺省美音
            vm.onAccentChange(Lang.EN_GB)
            advanceUntilIdle()
            assertEquals(Lang.EN_GB, fake.ttsAccent)
            assertEquals(Lang.EN_GB, vm.ttsAccent)
            assertNull(vm.message)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun enGbMissingHintFollowsAccentAndDeviceVoices() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            // 设备无英音音色（典型国产 ROM 形态）
            val vm = SettingsViewModel(newFake(), newSynth())
            advanceUntilIdle()
            assertFalse(vm.enGbMissingHint) // 美音选中 → 不提示
            vm.onAccentChange(Lang.EN_GB)
            advanceUntilIdle()
            assertTrue(vm.enGbMissingHint) // 英音选中且设备无英音 → 提示回退美音
            vm.onAccentChange(Lang.EN_US)
            advanceUntilIdle()
            assertFalse(vm.enGbMissingHint) // 切回美音 → 提示消失

            // 设备有英音音色 → 英音选中也不提示
            val withGb = SettingsViewModel(
                newFake(),
                FakeVoiceSynthesizer(en = listOf(voiceEn), zh = listOf(voiceZh), enGb = listOf(voiceGb)),
            )
            advanceUntilIdle()
            withGb.onAccentChange(Lang.EN_GB)
            advanceUntilIdle()
            assertFalse(withGb.enGbMissingHint)
        } finally {
            Dispatchers.resetMain()
        }
    }
}
