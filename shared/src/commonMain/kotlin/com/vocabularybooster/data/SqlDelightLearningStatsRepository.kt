package com.vocabularybooster.data

import com.vocabularybooster.db.VocabularyDatabase
import com.vocabularybooster.domain.model.LearningStatsSummary
import com.vocabularybooster.domain.model.StatsGranularity
import com.vocabularybooster.domain.model.StatsPoint
import com.vocabularybooster.domain.repository.LearningStatsRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime

/**
 * LearningStatsRepository 的 SQLDelight 实现（Phase 8.6，FR-20）。
 * 事件源 = Q8 掌握事件流 + Q9 已结束会话流（DATABASE_SCHEMA §3；query-only 零迁移）。
 * 全部分桶/去重在 Kotlin 完成：注入 [TimeZone] 保证本地日界正确
 * （SQL strftime 'unixepoch' 为 UTC 会错日界——铁律 10：引擎内时间走注入抽象）。
 *
 * 口径（与 [LearningStatsSummary] 一致）：
 * - 学习 = 按 wordId 取最早 masteredAt 的本地日（跨本同日重复掌握只计一次）；
 * - 复习 = 事件本地日**严格晚于**该词首次掌握日的事件，按 (日, wordId) 去重；
 * - 时长 = endedAt − startedAt ≥ 0，按 endedAt 本地日归集（ACTIVE 未结束行不计）。
 */
public class SqlDelightLearningStatsRepository(
    private val database: VocabularyDatabase,
    private val clock: Clock,
    private val timeZone: TimeZone = TimeZone.currentSystemDefault(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : LearningStatsRepository {

    override suspend fun summary(): LearningStatsSummary = withContext(dispatcher) {
        val today = today()
        val mastery = aggregateMastery()
        val durationByDay = durationByDay()
        LearningStatsSummary(
            todayLearnedWords = mastery.learnedByDay[today] ?: 0,
            todayReviewedWords = mastery.reviewedByDay[today] ?: 0,
            totalLearnedWords = mastery.totalLearnedWords,
            todayDurationMs = durationByDay[today] ?: 0L,
            totalDurationMs = durationByDay.values.sum(),
        )
    }

    override suspend fun series(granularity: StatsGranularity): List<StatsPoint> = withContext(dispatcher) {
        val today = today()
        val mastery = aggregateMastery()
        val durationByDay = durationByDay()
        val earliestDay = (mastery.learnedByDay.keys + durationByDay.keys).minOrNull()
        // 日粒度明细按目标粒度键上卷（月 = 当月各日求和，年 = 当年各日求和）
        val learnedByBucket = mastery.learnedByDay.entries.groupBy({ bucketKey(it.key, granularity) }, { it.value })
        val durationByBucket = durationByDay.entries.groupBy({ bucketKey(it.key, granularity) }, { it.value })
        windowDates(granularity, today, earliestDay).map { date ->
            val key = bucketKey(date, granularity)
            StatsPoint(
                key = key,
                learnedWords = learnedByBucket[key]?.sum() ?: 0,
                durationMs = durationByBucket[key]?.sum() ?: 0L,
            )
        }
    }

    // —— 聚合内核：日粒度明细，供 summary 与三档粒度上卷共用 ——

    private data class MasteryAggregate(
        val learnedByDay: Map<LocalDate, Int>,
        val reviewedByDay: Map<LocalDate, Int>,
        val totalLearnedWords: Int,
    )

    private fun aggregateMastery(): MasteryAggregate {
        val events = database.sessionWordQueries.selectMasteredEvents().executeAsList()
            .mapNotNull { row -> row.masteredAt?.let { row.wordId to it } } // ?: 行受 WHERE 保护，双保险
        val firstInstantByWord = HashMap<Long, Long>()
        for ((wordId, masteredAt) in events) {
            val known = firstInstantByWord[wordId]
            if (known == null || masteredAt < known) firstInstantByWord[wordId] = masteredAt
        }
        val firstDayByWord = firstInstantByWord.mapValues { (_, instant) -> localDateOf(instant) }
        val learnedByDay = HashMap<LocalDate, Int>()
        firstDayByWord.values.forEach { day -> learnedByDay[day] = (learnedByDay[day] ?: 0) + 1 }
        val reviewedWordsByDay = HashMap<LocalDate, MutableSet<Long>>()
        for ((wordId, masteredAt) in events) {
            val day = localDateOf(masteredAt)
            if (day > firstDayByWord.getValue(wordId)) { // 同日重复掌握：既非学习也非复习
                reviewedWordsByDay.getOrPut(day) { mutableSetOf() }.add(wordId)
            }
        }
        return MasteryAggregate(
            learnedByDay = learnedByDay,
            reviewedByDay = reviewedWordsByDay.mapValues { it.value.size },
            totalLearnedWords = firstDayByWord.size,
        )
    }

    private fun durationByDay(): Map<LocalDate, Long> {
        val result = HashMap<LocalDate, Long>()
        database.learningSessionQueries.selectEndedSessions().executeAsList().forEach { row ->
            val endedAt = row.endedAt ?: return@forEach // 同上，WHERE 保护下的双保险
            val day = localDateOf(endedAt)
            result[day] = (result[day] ?: 0L) + (endedAt - row.startedAt).coerceAtLeast(0L)
        }
        return result
    }

    // —— 窗口与分桶键 ——

    private fun today(): LocalDate = clock.now().toLocalDateTime(timeZone).date

    private fun localDateOf(epochMillis: Long): LocalDate =
        Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(timeZone).date

    private fun bucketKey(date: LocalDate, granularity: StatsGranularity): String = when (granularity) {
        StatsGranularity.DAY -> date.toString() // ISO yyyy-MM-dd
        StatsGranularity.MONTH -> "${date.year}-${date.monthNumber.toString().padStart(2, '0')}"
        StatsGranularity.YEAR -> "${date.year}"
    }

    /** 零值填充窗口（升序）：日 = 近 30 天含今日；月 = 近 12 个月含当月；年 = 首事件年至今年。 */
    private fun windowDates(
        granularity: StatsGranularity,
        today: LocalDate,
        earliestDay: LocalDate?,
    ): List<LocalDate> = when (granularity) {
        StatsGranularity.DAY -> List(DAY_WINDOW_DAYS) { today.minus(DatePeriod(days = DAY_WINDOW_DAYS - 1 - it)) }
        StatsGranularity.MONTH ->
            List(MONTH_WINDOW_MONTHS) { today.minus(DatePeriod(months = MONTH_WINDOW_MONTHS - 1 - it)) }
        StatsGranularity.YEAR -> {
            val firstYear = minOf(today.year, earliestDay?.year ?: today.year)
            (firstYear..today.year).map { LocalDate(it, 1, 1) }
        }
    }

    private companion object {
        const val DAY_WINDOW_DAYS = 30
        const val MONTH_WINDOW_MONTHS = 12
    }
}
