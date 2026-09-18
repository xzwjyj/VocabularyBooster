package com.vocabularybooster.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vocabularybooster.domain.model.Achievement
import org.koin.androidx.compose.koinViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 勋章墙（FR-13 / ACHIEVEMENT_SPEC §3，Phase 6）：只读列表（earnedAt 倒序，仓储排序）。
 * 全部类型统一渲染；授予后无删除入口（勋章永久性）。本屏零业务规则——查询在 VM → 仓储。
 */
@Composable
fun AchievementsScreen(viewModel: AchievementsViewModel = koinViewModel()) {
    val achievements = viewModel.achievements
    val message = viewModel.message

    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        Text("勋章", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 8.dp))
        message?.let {
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
        }
        if (achievements.isEmpty()) {
            Spacer(Modifier.padding(top = 48.dp))
            Text(
                "还没有勋章",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .testTag("achievements_empty"),
            )
            Text(
                "完整学完一个生词本即可获得第一枚完成勋章",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        } else {
            LazyColumn(
                Modifier
                    .fillMaxSize()
                    .padding(top = 16.dp)
                    .testTag("achievements_list"),
            ) {
                items(achievements, key = { it.achievementId }) { medal ->
                    MedalCard(medal)
                }
            }
        }
    }
}

@Composable
private fun MedalCard(medal: Achievement) {
    Card(Modifier
        .fillMaxWidth()
        .padding(vertical = 6.dp)
        .testTag("medal_card_${medal.achievementId}")
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("🏆", style = MaterialTheme.typography.displayMedium)
            Column(Modifier
                .padding(start = 16.dp)
            ) {
                Text(
                    medal.payload.bookName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "完整掌握 ${medal.payload.wordCount} 词 · ${formatDate(medal.earnedAt.toEpochMilliseconds())}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

/** epoch 毫秒 → 本地日期（minSdk 26，java.time 直用）。 */
private fun formatDate(epochMs: Long): String =
    MEDAL_DATE_FORMAT.format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))

private val MEDAL_DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
