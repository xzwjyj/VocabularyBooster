package com.vocabularybooster

import com.vocabularybooster.data.SqlDelightLearningStatsRepository
import com.vocabularybooster.domain.model.StatsGranularity
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 学习统计聚合（FR-20，Phase 8.6；TEST_PLAN §4.8 统计组）：真实 JDBC 内存库 +
 * FixedClock + 注入 TimeZone（铁律 10）。六个边界：
 * 1) 首次掌握去重 = 学习（跨本同日重复掌握只计一次）
 * 2) 首掌日之前日已掌握 → 再掌握 = 复习；同日重复既非学亦非复
 * 3) 会话时长按结束日归集（跨午夜归结束日）
 * 4) endedAt NULL（未结束 ACTIVE）不计时长
 * 5) 空库零值 + 三档窗口零值填充
 * 6) 日界跟随注入时区（同一 epoch 在 UTC+14 落入次日）
 */
class LearningStatsRepositoryTest {

    private val zone = TimeZone.UTC
    private val nowMillis = 1_760_000_000_000L // UTC 时刻 08:53 —— UTC+14 内仍同日（用例 6 前提）
    private lateinit var db: TestDb
    private var bookId = 0L

    @BeforeTest
    fun setUp() {
        db = TestDb.inMemory()
        db.database.wordBookQueries.insertOriginalWordBook("stats", null, nowMillis, nowMillis)
        bookId = db.database.wordBookQueries.selectLastInsertRowId().executeAsOne()
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    private fun newRepo(tz: TimeZone = zone): SqlDelightLearningStatsRepository =
        SqlDelightLearningStatsRepository(db.database, FixedClock(nowMillis), tz, DispatchersForTest)

    private fun today(): LocalDate = Instant.fromEpochMilliseconds(nowMillis).toLocalDateTime(zone).date

    private fun dayAgo(n: Int): LocalDate = today().minus(DatePeriod(days = n))

    private fun at(day: LocalDate, hour: Int, minute: Int = 0): Long =
        day.atStartOfDayIn(zone).toEpochMilliseconds() + (hour * 60 + minute) * 60_000L

    private fun seedWord(name: String): Long {
        db.database.wordQueries.insertWord(name, name, null, null, null, nowMillis, nowMillis)
        return db.database.wordQueries.selectLastInsertRowId().executeAsOne()
    }

    private fun newSession(): Long {
        db.database.learningSessionQueries.insertSession(bookId, "ACTIVE", 10, nowMillis, null)
        return db.database.learningSessionQueries.selectLastInsertRowId().executeAsOne()
    }

    /** 一个掌握事件（独立会话，避免 (sessionId, wordId) 主键冲突）。 */
    private fun seedMastered(wordId: Long, at: Long) {
        val sessionId = newSession()
        db.database.sessionWordQueries.insertSessionWord(sessionId, wordId, 0, 0, "PENDING")
        db.database.sessionWordQueries.updateSessionWordStatus("MASTERED", at, sessionId, wordId)
    }

    private fun seedEndedSession(startedAt: Long, endedAt: Long?) {
        db.database.learningSessionQueries.insertSession(bookId, "ABANDONED", 10, startedAt, endedAt)
    }

    @Test
    fun firstMasteryDedupsAcrossBooksAndSessions() = runTest {
        val today = today()
        val w1 = seedWord("w1")
        val w2 = seedWord("w2")
        // w1 今日两次掌握（跨会话，跨本同理——去重按 wordId 全局）→ 学习只计 1；w2 今日一次 → 合计 2
        seedMastered(w1, at(today, 10))
        seedMastered(w1, at(today, 12))
        seedMastered(w2, at(today, 11))

        val summary = newRepo().summary()
        assertEquals(2, summary.todayLearnedWords)
        assertEquals(0, summary.todayReviewedWords)
        assertEquals(2, summary.totalLearnedWords)
    }

    @Test
    fun laterDayMasteryCountsAsReviewButSameDayDoesNot() = runTest {
        val today = today()
        val w1 = seedWord("w1")
        seedMastered(w1, at(dayAgo(1), 9))   // 昨日首次掌握 → 非今日学习
        seedMastered(w1, at(today, 14))      // 今日再掌握 → 今日复习 1
        seedMastered(w1, at(today, 16))      // 今日第三次 → 同日去重，复习仍 1

        val summary = newRepo().summary()
        assertEquals(0, summary.todayLearnedWords)
        assertEquals(1, summary.todayReviewedWords)
        assertEquals(1, summary.totalLearnedWords)
        // 昨日桶：学习 1（首掌日）、复习 0
        val yesterdayPoint = newRepo().series(StatsGranularity.DAY).last { it.key == dayAgo(1).toString() }
        assertEquals(1, yesterdayPoint.learnedWords)
    }

    @Test
    fun durationAggregatesByEndDayAndCrossesMidnightIntoEndDay() = runTest {
        val today = today()
        seedEndedSession(at(today, 10), at(today, 10, 5))                       // 全程今日 → 5 分钟
        seedEndedSession(at(dayAgo(1), 23), at(today, 0, 30))                   // 跨午夜（23:00→00:30 = 90 分钟）→ 整段归结束日
        seedEndedSession(at(dayAgo(1), 8), at(dayAgo(1), 8, 10))                // 昨日全程 → 10 分钟归昨日

        val summary = newRepo().summary()
        assertEquals(5 * 60_000L + 90 * 60_000L, summary.todayDurationMs)
        assertEquals(105 * 60_000L, summary.totalDurationMs)
        val points = newRepo().series(StatsGranularity.DAY)
        assertEquals(95 * 60_000L, points.last { it.key == today.toString() }.durationMs) // 5 分钟 + 跨午夜 90 分钟
        assertEquals(10 * 60_000L, points.last { it.key == dayAgo(1).toString() }.durationMs)
    }

    @Test
    fun unendedActiveSessionExcludesFromDuration() = runTest {
        seedEndedSession(at(today(), 9), null) // endedAt NULL → 不计
        val summary = newRepo().summary()
        assertEquals(0L, summary.todayDurationMs)
        assertEquals(0L, summary.totalDurationMs)
    }

    @Test
    fun emptyDatabaseYieldsZeroSummaryAndFilledWindows() = runTest {
        val summary = newRepo().summary()
        assertEquals(0, summary.todayLearnedWords)
        assertEquals(0, summary.todayReviewedWords)
        assertEquals(0, summary.totalLearnedWords)
        assertEquals(0L, summary.todayDurationMs)
        assertEquals(0L, summary.totalDurationMs)

        val days = newRepo().series(StatsGranularity.DAY)
        assertEquals(30, days.size)
        assertEquals(dayAgo(29).toString(), days.first().key)
        assertEquals(today().toString(), days.last().key)
        assertTrue(days.all { it.learnedWords == 0 && it.durationMs == 0L })

        assertEquals(12, newRepo().series(StatsGranularity.MONTH).size)
        assertEquals(listOf(today().year.toString()), newRepo().series(StatsGranularity.YEAR).map { it.key })
    }

    @Test
    fun monthAndYearSeriesRollUpDailyBuckets() = runTest {
        val w1 = seedWord("w1")
        val w2 = seedWord("w2")
        seedMastered(w1, at(today(), 10))
        val daysAgo40 = dayAgo(40) // 必然异月
        seedMastered(w2, at(daysAgo40, 10))

        fun monthKey(date: LocalDate) = "${date.year}-${date.monthNumber.toString().padStart(2, '0')}"

        val months = newRepo().series(StatsGranularity.MONTH)
        assertEquals(12, months.size)
        assertEquals(1, months.last { it.key == monthKey(today()) }.learnedWords)
        assertEquals(1, months.last { it.key == monthKey(daysAgo40) }.learnedWords)
        assertEquals(2, months.sumOf { it.learnedWords })

        val firstYear = minOf(today().year, daysAgo40.year)
        val years = newRepo().series(StatsGranularity.YEAR)
        assertEquals((firstYear..today().year).map { it.toString() }, years.map { it.key })
        assertEquals(2, years.sumOf { it.learnedWords })
    }

    @Test
    fun dayBoundaryFollowsInjectedTimeZone() = runTest {
        // 事件 = 今日 23:00 UTC：UTC 归今日；UTC+14（日界提前 10 小时）归明日
        val wordId = seedWord("tz")
        val eventAt = at(today(), 23)
        seedMastered(wordId, eventAt)

        assertEquals(1, newRepo(TimeZone.UTC).summary().todayLearnedWords)
        assertEquals(0, newRepo(TimeZone.of("UTC+14")).summary().todayLearnedWords)
    }
}
