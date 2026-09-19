package com.vocabularybooster.app.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vocabularybooster.domain.model.Achievement
import com.vocabularybooster.domain.model.LearningStatsSummary
import org.koin.androidx.compose.koinViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 勋章墙（FR-13 / ACHIEVEMENT_SPEC §3，Phase 6）：只读列表（earnedAt 倒序，仓储排序）。
 * 全部类型统一渲染；授予后无删除入口（勋章永久性）。本屏零业务规则——查询在 VM → 仓储。
 * Phase 8.6（FR-20）：顶部统计卡（今日学习/复习、累计学习、今日/累计时长），点击进入统计详情。
 */
@Composable
fun AchievementsScreen(
    viewModel: AchievementsViewModel = koinViewModel(),
    statsViewModel: StatsViewModel = koinViewModel(),
    onOpenStats: () -> Unit = {},
) {
    val achievements = viewModel.achievements
    val message = viewModel.message
    LaunchedEffect(Unit) { statsViewModel.refresh() }

    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        Text("勋章", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 8.dp))
        message?.let {
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
        }
        StatsSummaryCard(summary = statsViewModel.summary, onOpen = onOpenStats)
        statsViewModel.message?.let {
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

/** 统计汇总卡（FR-20）：五个口径值 + 点击进入日/月/年曲线详情；null = 首帧加载中。 */
@Composable
private fun StatsSummaryCard(summary: LearningStatsSummary?, onOpen: () -> Unit) {
    Card(
        Modifier
            .fillMaxWidth()
            .padding(top = 16.dp)
            .testTag("stats_summary_card")
            .clickable(onClick = onOpen),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("统计", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (summary == null) {
                Text(
                    "加载中…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(top = 8.dp),
                )
            } else {
                StatsValueRow(
                    StatsValue("今日学习", "${summary.todayLearnedWords} 词", "stats_today_learned_value"),
                    StatsValue("今日复习", "${summary.todayReviewedWords} 词", "stats_today_reviewed_value"),
                    StatsValue("今日时长", formatDurationMs(summary.todayDurationMs), "stats_today_duration_value"),
                )
                StatsValueRow(
                    StatsValue("累计学习", "${summary.totalLearnedWords} 词", "stats_total_learned_value"),
                    StatsValue("累计时长", formatDurationMs(summary.totalDurationMs), "stats_total_duration_value"),
                    null,
                )
            }
            Text(
                "点击查看学习曲线",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.padding(top = 8.dp).testTag("stats_summary_hint"),
            )
        }
    }
}

private data class StatsValue(val label: String, val value: String, val testTag: String)

/** 一行至多三个指标（label + value 纵排，等宽三列；空位占位保持对齐）。 */
@Composable
private fun StatsValueRow(first: StatsValue?, second: StatsValue? = null, third: StatsValue? = null) {
    Row(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        listOf(first, second, third).forEach { item ->
            Column(Modifier.weight(1f)) {
                item?.let {
                    Text(
                        it.label,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                    Text(
                        it.value,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 2.dp).testTag(it.testTag),
                    )
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
