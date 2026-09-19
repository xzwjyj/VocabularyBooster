package com.vocabularybooster.domain.repository

import com.vocabularybooster.domain.model.LearningStatsSummary
import com.vocabularybooster.domain.model.StatsGranularity
import com.vocabularybooster.domain.model.StatsPoint

/**
 * 学习统计端口（FR-20，Phase 8.6）：只读聚合，零写路径。
 * 分桶在共享层完成（注入 TimeZone + Clock，铁律 10；SQL strftime 'unixepoch'
 * 为 UTC 会错本地日界——见 DATABASE_SCHEMA Q8 注记）。
 * 序列窗口含零值填充：DAY = 近 30 天含今日；MONTH = 近 12 个月含当月；YEAR = 首事件年至今年。
 */
public interface LearningStatsRepository {

    /** 当前时点的汇总卡四数（+累计时长），口径见 [LearningStatsSummary]。 */
    public suspend fun summary(): LearningStatsSummary

    /** 指定粒度的学习词数 + 学习时长序列（升序、窗口内零值填充）。 */
    public suspend fun series(granularity: StatsGranularity): List<StatsPoint>
}
