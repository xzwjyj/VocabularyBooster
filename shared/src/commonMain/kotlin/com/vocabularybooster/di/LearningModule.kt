package com.vocabularybooster.di

import com.vocabularybooster.learning.DefaultLearningEngine
import com.vocabularybooster.learning.LearningEngine
import com.vocabularybooster.learning.MasteryMarker
import com.vocabularybooster.learning.WordBookDeriver
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * 学习引擎 DI（Phase 3 Step 4/5D）：MasteryMarker + WordBookDeriver + LearningEngine 编排装配。
 * 仓储/设置来自 sharedDataModule；app 装配随 Phase 4 播放 UI 接入。
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
}
