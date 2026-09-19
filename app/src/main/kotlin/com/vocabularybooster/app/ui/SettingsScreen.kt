package com.vocabularybooster.app.ui

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.vocabularybooster.speech.TtsVoice
import org.koin.androidx.compose.koinViewModel
import kotlin.math.round

/**
 * 设置页（FR-15，Phase 8）：五项设置查看 + 变更即时持久化（无保存按钮）。
 * 本屏零业务规则——范围校验在端口（铁律 2）；生效时机由引擎既有读取点保证：
 * 开关/语速/音调下一 Segment、窗口时长下一窗口、groupSize 仅新会话（FR-6，supportingText 固定提示）。
 * 「会了」命令别名 = 方案 A 只读展示（可配置性再延后，PROJECT_SPEC 版本记录）。
 */
@Composable
fun SettingsScreen(viewModel: SettingsViewModel = koinViewModel()) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .testTag("settings_screen"),
    ) {
        Text("设置", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 8.dp))
        viewModel.message?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .padding(top = 8.dp)
                    .testTag("settings_message"),
            )
        }

        SectionHeader("学习")
        OutlinedTextField(
            value = viewModel.groupSizeText,
            onValueChange = viewModel::onGroupSizeChange,
            label = { Text("每组词数") },
            supportingText = { Text("只对新会话生效；进行中的会话保持原分组") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("settings_group_size_input"),
        )

        SectionHeader("播放内容")
        ToggleRow("发音", viewModel.toggles.pronunciation, "settings_toggle_pronunciation") {
            viewModel.updateToggles(viewModel.toggles.copy(pronunciation = it))
        }
        ToggleRow("拼写", viewModel.toggles.spelling, "settings_toggle_spelling") {
            viewModel.updateToggles(viewModel.toggles.copy(spelling = it))
        }
        ToggleRow("英文释义", viewModel.toggles.meaningEn, "settings_toggle_meaning_en") {
            viewModel.updateToggles(viewModel.toggles.copy(meaningEn = it))
        }
        ToggleRow("中文释义", viewModel.toggles.meaningCn, "settings_toggle_meaning_cn") {
            viewModel.updateToggles(viewModel.toggles.copy(meaningCn = it))
        }
        ToggleRow("例句原文", viewModel.toggles.example, "settings_toggle_example") {
            viewModel.updateToggles(viewModel.toggles.copy(example = it))
        }
        ToggleRow("例句译文", viewModel.toggles.exampleCn, "settings_toggle_example_cn") {
            viewModel.updateToggles(viewModel.toggles.copy(exampleCn = it))
        }
        Text(
            "变更在下一个朗读段落生效",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.padding(top = 4.dp),
        )

        SectionHeader("语音命令")
        Column(
            Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("命令窗口时长", Modifier.weight(1f))
                Text("${"%.1f".format(viewModel.commandWindowMs / MILLIS_PER_SECOND)} 秒")
            }
            Slider(
                value = viewModel.commandWindowMs.coerceIn(WINDOW_MIN_MS, WINDOW_MAX_MS).toFloat(),
                onValueChange = { viewModel.onWindowChange(snapToWindowStep(it)) },
                valueRange = WINDOW_MIN_MS.toFloat()..WINDOW_MAX_MS.toFloat(),
                steps = WINDOW_STEP_COUNT,
                modifier = Modifier.testTag("settings_window_slider"),
            )
            Text(
                "每个词读完后的等待确认时间；变更在下一个窗口生效",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
            )
        }
        Column(
            Modifier
                .fillMaxWidth()
                .padding(top = 16.dp)
                .testTag("settings_alias_row"),
        ) {
            Text("「会了」命令别名", style = MaterialTheme.typography.bodyLarge)
            Text(
                "会了 / 记住了 / 掌握了（固定，暂不支持自定义）",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        SectionHeader("朗读")
        SliderRow(
            label = "语速",
            valueText = "%.2f".format(viewModel.ttsRate),
            value = viewModel.ttsRate.coerceIn(RATE_MIN, RATE_MAX),
            onValueChange = { viewModel.onRateChange(snapToRateStep(it)) },
            tag = "settings_rate_slider",
        )
        SliderRow(
            label = "音调",
            valueText = "%.2f".format(viewModel.ttsPitch),
            value = viewModel.ttsPitch.coerceIn(RATE_MIN, RATE_MAX),
            onValueChange = { viewModel.onPitchChange(snapToRateStep(it)) },
            tag = "settings_pitch_slider",
        )

        SectionHeader("语音音色")
        Text(
            "音色随设备语音引擎而异；更高自然度的音色可在系统设置下载后回到此处选择，变更下一朗读段生效",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary,
        )
        var voiceDialogLang by remember { mutableStateOf<String?>(null) }
        VoiceRow(
            label = "英语音色",
            current = voiceDisplayName(viewModel.voicesEn, viewModel.voiceEnId),
            tag = "settings_voice_en_row",
        ) { voiceDialogLang = "en" }
        VoiceRow(
            label = "中文音色",
            current = voiceDisplayName(viewModel.voicesZh, viewModel.voiceZhId),
            tag = "settings_voice_zh_row",
        ) { voiceDialogLang = "zh" }
        val context = LocalContext.current
        TextButton(
            onClick = {
                // 直达系统语音设置（下载高自然度音色）；无对应设置页的 ROM 静默失败不崩溃
                runCatching { context.startActivity(Intent("com.android.settings.TTS_SETTINGS")) }
            },
            modifier = Modifier.testTag("settings_open_system_tts"),
        ) { Text("打开系统语音设置") }

        voiceDialogLang?.let { langKey ->
            val voices = if (langKey == "en") viewModel.voicesEn else viewModel.voicesZh
            val selectedId = if (langKey == "en") viewModel.voiceEnId else viewModel.voiceZhId
            val onSelect: (String?) -> Unit = if (langKey == "en") {
                viewModel::onVoiceEnChange
            } else {
                viewModel::onVoiceZhChange
            }
            AlertDialog(
                onDismissRequest = { voiceDialogLang = null },
                title = { Text(if (langKey == "en") "英语音色" else "中文音色") },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        VoiceOptionRow(
                            label = "跟随系统（默认）",
                            selected = selectedId == null,
                            tag = "settings_voice_option_default",
                        ) {
                            onSelect(null)
                            voiceDialogLang = null
                        }
                        if (voices.isEmpty()) {
                            Text(
                                "引擎暂无可用音色（可在系统语音设置检查引擎）",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                        voices.forEachIndexed { index, voice ->
                            VoiceOptionRow(
                                label = voice.displayName +
                                    (voice.qualityLabel?.let { " · $it" } ?: ""),
                                selected = selectedId == voice.id,
                                tag = "settings_voice_option_$index",
                            ) {
                                onSelect(voice.id)
                                voiceDialogLang = null
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { voiceDialogLang = null }) { Text("取消") }
                },
            )
        }
    }
}

/** 当前音色显示名（未选/已失效 → 跟随系统）。 */
private fun voiceDisplayName(voices: List<TtsVoice>, selectedId: String?): String =
    voices.firstOrNull { it.id == selectedId }?.displayName ?: "跟随系统（默认）"

@Composable
private fun VoiceRow(label: String, current: String, tag: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f))
        Text(current, color = MaterialTheme.colorScheme.secondary)
    }
}

@Composable
private fun VoiceOptionRow(label: String, selected: Boolean, tag: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label)
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 24.dp, bottom = 4.dp),
    )
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, tag: String, onCheckedChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange, modifier = Modifier.testTag(tag))
    }
}

/** 语速/音调共用滑条形态（同范围同档位 0.5–2.0、步进 0.05）。 */
@Composable
private fun SliderRow(
    label: String,
    valueText: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    tag: String,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f))
            Text(valueText)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = RATE_MIN..RATE_MAX,
            steps = RATE_STEP_COUNT,
            modifier = Modifier.testTag(tag),
        )
    }
}

/** 拖动值吸附到 500ms 档位（M3 步进吸附兜底——中途值不落库）。 */
private fun snapToWindowStep(value: Float): Long =
    WINDOW_MIN_MS + (round((value - WINDOW_MIN_MS) / WINDOW_STEP_MS) * WINDOW_STEP_MS).toLong()

/** 拖动值吸附到 0.05 档位（同上）。 */
private fun snapToRateStep(value: Float): Float =
    RATE_MIN + round((value - RATE_MIN) / RATE_STEP) * RATE_STEP

private const val MILLIS_PER_SECOND: Float = 1_000f
private const val WINDOW_MIN_MS: Long = 1_000L
private const val WINDOW_MAX_MS: Long = 10_000L
private const val WINDOW_STEP_MS: Float = 500f

/** 滑条中间档位数 = (10_000−1_000)/500 − 1 = 17（端点不计）。 */
private const val WINDOW_STEP_COUNT: Int = 17
private const val RATE_MIN: Float = 0.5f
private const val RATE_MAX: Float = 2.0f
private const val RATE_STEP: Float = 0.05f

/** 滑条中间档位数 = (2.0−0.5)/0.05 − 1 = 29（浮点步进用整数常量避免舍入漂移）。 */
private const val RATE_STEP_COUNT: Int = 29
