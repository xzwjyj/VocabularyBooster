package com.vocabularybooster.di

import com.vocabularybooster.achievement.AchievementEngine
import com.vocabularybooster.domain.event.DomainEventBus
import com.vocabularybooster.domain.event.DefaultDomainEventBus
import com.vocabularybooster.learning.DefaultLearningEngine
import com.vocabularybooster.learning.LearningEngine
import com.vocabularybooster.learning.MasteryMarker
import com.vocabularybooster.learning.WordBookDeriver
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * 学习引擎 DI（Phase 3 Step 4/5D + Phase 6 勋章）：MasteryMarker + WordBookDeriver +
 * LearningEngine 编排装配 + 领域事件总线/勋章引擎。
 * 仓储/设置来自 sharedDataModule；app 装配随 Phase 4 播放 UI 接入。
 * AchievementEngine 订阅启动在 Application.onCreate（app 装配层）。
 */
public val sharedLearningModule: Module = module {
    single {
        MasteryMarker(repository = get(), clock = get())
    }
    single {
        WordBookDeriver(wordBookRepository = get(), clock = get())
    }
    single<LearningEngine> {
        DefaultLearningEngine(
            sessionRepository = get(),
            settingsRepository = get(),
            masteryMarker = get(),
            wordBookDeriver = get(),
        )
    }
    // Phase 6：领域事件总线（单例——编排器发布与勋章引擎订阅必须同一实例）
    single<DomainEventBus> {
        DefaultDomainEventBus()
    }
    single {
        AchievementEngine(
            eventBus = get(),
            sessionRepository = get(),
            wordBookRepository = get(),
            achievementRepository = get(),
            logSink = get(),
        )
    }
}
