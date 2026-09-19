package com.vocabularybooster.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vocabularybooster.domain.model.StatsGranularity
import com.vocabularybooster.domain.model.StatsPoint
import org.koin.androidx.compose.koinViewModel

/**
 * 统计详情（FR-20，Phase 8.6）：日（近 30 天）/ 月（近 12 个月）/ 年粒度的
 * 学习词数 + 学习时长柱状图。只读渲染 StatsViewModel 投影；口径聚合全在共享层仓储
 * （本屏零业务规则）。与勋章页统计卡共享同一 activity 作用域 StatsViewModel。
 */
@Composable
fun StatsDetailScreen(
    viewModel: StatsViewModel = koinViewModel(),
    onBack: () -> Unit,
) {
    LaunchedEffect(Unit) { viewModel.refresh() }

    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(
                onClick = onBack,
                modifier = Modifier.testTag("stats_back"),
            ) { Text("返回") }
            Text(
                "学习统计",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        viewModel.message?.let {
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 4.dp))
        }
        GranularitySelector(selected = viewModel.granularity, onSelect = viewModel::onGranularityChange)
        LazyColumn {
            item {
                ChartCard(
                    title = "学习词数（不含重复复习）",
                    points = viewModel.points,
                    valueOf = { it.learnedWords.toFloat() },
                    formatValue = { "${it.learnedWords} 词" },
                    testTag = "stats_chart_words",
                )
            }
            item {
                ChartCard(
                    title = "学习时长",
                    points = viewModel.points,
                    valueOf = { it.durationMs.toFloat() },
                    formatValue = { formatDurationMs(it.durationMs) },
                    testTag = "stats_chart_duration",
                )
            }
        }
    }
}

@Composable
private fun GranularitySelector(selected: StatsGranularity, onSelect: (StatsGranularity) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        StatsGranularity.entries.forEach { granularity ->
            val isSelected = granularity == selected
            TextButton(
                onClick = { onSelect(granularity) },
                modifier = Modifier
                    .padding(end = 8.dp)
                    .testTag("stats_granularity_${granularity.name.lowercase()}"),
                colors = if (isSelected) {
                    ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.primary)
                } else {
                    ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant)
                },
            ) {
                Text(
                    granularity.label(),
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}

private fun StatsGranularity.label(): String = when (this) {
    StatsGranularity.DAY -> "日"
    StatsGranularity.MONTH -> "月"
    StatsGranularity.YEAR -> "年"
}

/** 柱状图卡片：Canvas 画柱 + 首末 key 标签 + 峰值说明；空数据/全零画空画布。 */
@Composable
private fun ChartCard(
    title: String,
    points: List<StatsPoint>,
    valueOf: (StatsPoint) -> Float,
    formatValue: (StatsPoint) -> String,
    testTag: String,
) {
    val barColor = MaterialTheme.colorScheme.primary
    Card(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .testTag(testTag),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            val maxValue = points.maxOfOrNull(valueOf) ?: 0f
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(140.dp)
                    .padding(top = 12.dp),
            ) {
                if (points.isEmpty() || maxValue <= 0f) return@Canvas
                val slotWidth = size.width / points.size
                val barWidth = slotWidth * BAR_WIDTH_RATIO
                points.forEachIndexed { index, point ->
                    val barHeight = (valueOf(point) / maxValue) * size.height * BAR_HEIGHT_RATIO
                    drawRoundRect(
                        color = barColor,
                        topLeft = Offset(index * slotWidth + (slotWidth - barWidth) / 2f, size.height - barHeight),
                        size = Size(barWidth, barHeight),
                        cornerRadius = CornerRadius(4.dp.toPx()),
                    )
                }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(shortKey(points.firstOrNull()?.key), style = MaterialTheme.typography.labelSmall)
                Text(shortKey(points.lastOrNull()?.key), style = MaterialTheme.typography.labelSmall)
            }
            points.maxByOrNull(valueOf)?.let { peak ->
                if (valueOf(peak) > 0f) {
                    Text(
                        "峰值 ${peak.key}：${formatValue(peak)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

/** 日期 key 缩短显示：日 `yyyy-MM-dd` → `MM-dd`；月/年原样。 */
private fun shortKey(key: String?): String = when {
    key == null -> ""
    key.length > MONTH_KEY_LENGTH -> key.takeLast(DAY_KEY_TAIL_LENGTH)
    else -> key
}

/** 毫秒 → 中文时长（统计卡与图表共用；不足 1 小时按分钟）。 */
internal fun formatDurationMs(ms: Long): String {
    val totalMinutes = ms / MILLIS_PER_MINUTE
    val hours = totalMinutes / MINUTES_PER_HOUR
    val minutes = totalMinutes % MINUTES_PER_HOUR
    return when {
        hours > 0 && minutes > 0 -> "$hours 小时 $minutes 分"
        hours > 0 -> "$hours 小时"
        else -> "$minutes 分钟"
    }
}

private const val MONTH_KEY_LENGTH = 7 // "yyyy-MM"
private const val DAY_KEY_TAIL_LENGTH = 5 // "MM-dd"
private const val MILLIS_PER_MINUTE = 60_000L
private const val MINUTES_PER_HOUR = 60
private const val BAR_WIDTH_RATIO = 0.6f
private const val BAR_HEIGHT_RATIO = 0.9f
