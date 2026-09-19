package com.vocabularybooster.app.ui

import com.vocabularybooster.domain.model.LearningStatsSummary
import com.vocabularybooster.domain.model.StatsGranularity
import com.vocabularybooster.domain.model.StatsPoint
import com.vocabularybooster.domain.repository.LearningStatsRepository
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
 * Phase 8.6（FR-20）：StatsViewModel——汇总/序列投影 + 粒度切换重载 + 失败提示。
 * 口径聚合的权威测试在 jvmTest 真实仓储（LearningStatsRepositoryTest）；
 * 此处 Fake 只注入数据/失败两态。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StatsViewModelTest {

    private class FakeStatsRepository(
        private val summaryResult: LearningStatsSummary = LearningStatsSummary(
            todayLearnedWords = 2,
            todayReviewedWords = 1,
            totalLearnedWords = 10,
            todayDurationMs = 300_000L,
            totalDurationMs = 9_000_000L,
        ),
        private val seriesResult: List<StatsPoint> = listOf(StatsPoint("2026-09-19", 2, 300_000L)),
        private val fail: Boolean = false,
    ) : LearningStatsRepository {
        var seriesCalls: Int = 0
            private set
        var lastGranularity: StatsGranularity? = null
            private set

        override suspend fun summary(): LearningStatsSummary {
            if (fail) error("boom")
            return summaryResult
        }

        override suspend fun series(granularity: StatsGranularity): List<StatsPoint> {
            if (fail) error("boom")
            seriesCalls++
            lastGranularity = granularity
            return seriesResult
        }
    }

    @Test
    fun initProjectsSummaryAndDaySeries() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val fake = FakeStatsRepository()
            val vm = StatsViewModel(fake)
            advanceUntilIdle()

            assertEquals(2, vm.summary?.todayLearnedWords)
            assertEquals(1, vm.summary?.todayReviewedWords)
            assertEquals(10, vm.summary?.totalLearnedWords)
            assertEquals(listOf(StatsPoint("2026-09-19", 2, 300_000L)), vm.points)
            assertEquals(StatsGranularity.DAY, vm.granularity)
            assertEquals(StatsGranularity.DAY, fake.lastGranularity)
            assertNull(vm.message)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun granularityChangeReloadsSeriesAndIgnoresRepeat() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val fake = FakeStatsRepository()
            val vm = StatsViewModel(fake)
            advanceUntilIdle()
            val callsAfterInit = fake.seriesCalls

            vm.onGranularityChange(StatsGranularity.MONTH)
            advanceUntilIdle()
            assertEquals(StatsGranularity.MONTH, vm.granularity)
            assertEquals(StatsGranularity.MONTH, fake.lastGranularity)
            assertEquals(callsAfterInit + 1, fake.seriesCalls)

            vm.onGranularityChange(StatsGranularity.MONTH) // 重复选择不重载
            advanceUntilIdle()
            assertEquals(callsAfterInit + 1, fake.seriesCalls)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun repositoryFailureShowsMessage() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val vm = StatsViewModel(FakeStatsRepository(fail = true))
            advanceUntilIdle()
            assertTrue(vm.message!!.contains("统计加载失败"))
            assertEquals(emptyList<StatsPoint>(), vm.points)
        } finally {
            Dispatchers.resetMain()
        }
    }
}
