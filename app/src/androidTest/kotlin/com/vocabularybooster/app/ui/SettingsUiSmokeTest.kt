package com.vocabularybooster.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vocabularybooster.app.MainActivity
import com.vocabularybooster.db.VocabularyDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

/**
 * 设置页 UI 冒烟（Phase 8，TC-UI 设置；需模拟器）：真实 MainActivity + 真实 Koin 图 +
 * 真实文件库——开关变更 → 即时持久化到 AppSetting（FR-15）。
 * @Before 删除开关键 → 缺键默认全开（确定性起点）；断言 = DB 内 JSON 含拼写关闭。
 */
@RunWith(AndroidJUnit4::class)
class SettingsUiSmokeTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()
    private val koin get() = GlobalContext.get()

    @Before
    fun setUp() {
        runBlocking {
            koin.get<VocabularyDatabase>().appSettingQueries.deleteSetting("settings.playbackToggles")
            koin.get<VocabularyDatabase>().appSettingQueries.deleteSetting("settings.ttsVoiceEn")
        }
    }

    @Test
    fun toggleChangePersistsImmediately() {
        rule.onNodeWithText("设置").performClick()
        rule.onNode(hasTestTag("settings_screen")).assertIsDisplayed()

        rule.onNode(hasTestTag("settings_toggle_spelling")).performClick()

        val db = koin.get<VocabularyDatabase>()
        rule.waitUntil(10_000) {
            db.appSettingQueries.selectSetting("settings.playbackToggles").executeAsOneOrNull()
                ?.contains("\"spelling\":false") == true
        }
    }

    /**
     * Phase 8.6（FR-19）音色冒烟：进入设置 → 打开英语音色选择 → 选第一个真实音色 →
     * KV 落库。模拟器引擎音色枚举可能为空（引擎未就绪/无音色）→ assume 跳过不判失败。
     */
    @Test
    fun voiceSelectionPersistsImmediately() {
        rule.onNodeWithText("设置").performClick()
        rule.onNode(hasTestTag("settings_voice_en_row")).performClick()

        val optionsReady = runCatching {
            rule.waitUntil(15_000) {
                runCatching { rule.onNode(hasTestTag("settings_voice_option_0")).assertExists() }.isSuccess
            }
        }.isSuccess
        org.junit.Assume.assumeTrue("engine reported no en-US voices", optionsReady)

        rule.onNode(hasTestTag("settings_voice_option_0")).performClick()

        val db = koin.get<VocabularyDatabase>()
        rule.waitUntil(10_000) {
            db.appSettingQueries.selectSetting("settings.ttsVoiceEn").executeAsOneOrNull() != null
        }
    }
}
