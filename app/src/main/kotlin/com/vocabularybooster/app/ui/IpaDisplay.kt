package com.vocabularybooster.app.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.vocabularybooster.app.R
import com.vocabularybooster.domain.model.Lang

/**
 * FR-22 双音标行 + 口音朗读小按钮（FR-22 扩展）：「US /…/ 🔊  UK /…/ 🔊」——
 * 有则双显、无则单显、全缺 → 不渲染（空行不占位）。斜杠兼容：库内两种存法并存
 * （种子数据带斜杠、词典导入不带），已带斜杠则原样。
 *
 * 🔊 = 一次性试听（onPronounce(wordText, EN_US|EN_GB) → ViewModel → WordPronouncer），
 * 不改设置口音；词文本不可播（空白 / CommandWindow 占位「…」）时按钮禁用置灰。
 */
@Composable
internal fun IpaSpeechRow(
    wordText: String,
    ipaAm: String?,
    ipaBr: String?,
    onPronounce: (String, Lang) -> Unit,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
) {
    val us = ipaAm?.trim()?.takeIf { it.isNotBlank() }?.let(::wrapIpa)
    val uk = ipaBr?.trim()?.takeIf { it.isNotBlank() }?.let(::wrapIpa)
    if (us == null && uk == null) return
    val speakable = wordText.isNotBlank() && wordText != COMMAND_WINDOW_WORD_PLACEHOLDER
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        if (us != null) {
            Text("US $us", style = style, color = color)
            AccentSpeakButton(speakable, "美音朗读", "ipa_speak_us") { onPronounce(wordText, Lang.EN_US) }
        }
        if (uk != null) {
            Text(
                "UK $uk",
                style = style,
                color = color,
                modifier = Modifier.padding(start = if (us != null) 8.dp else 0.dp),
            )
            AccentSpeakButton(speakable, "英音朗读", "ipa_speak_uk") { onPronounce(wordText, Lang.EN_GB) }
        }
    }
}

/** 音标旁朗读小按钮（紧凑 28dp 触达 + 18dp 图标；disabled 由 IconButton 自动置灰）。 */
@Composable
private fun AccentSpeakButton(enabled: Boolean, contentDescription: String, tag: String, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .size(28.dp)
            .testTag(tag),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_volume_up),
            contentDescription = contentDescription,
            modifier = Modifier.size(18.dp),
        )
    }
}

private fun wrapIpa(ipa: String): String =
    if (ipa.startsWith("/") && ipa.endsWith("/") && ipa.length > 1) ipa else "/$ipa/"
