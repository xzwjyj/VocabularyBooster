package com.vocabularybooster.di

import com.vocabularybooster.data.SqlDelightAchievementRepository
import com.vocabularybooster.data.SqlDelightImportRepository
import com.vocabularybooster.data.SqlDelightLearningSessionRepository
import com.vocabularybooster.data.SqlDelightLearningSettingsRepository
import com.vocabularybooster.data.SqlDelightLearningStatsRepository
import com.vocabularybooster.data.SqlDelightPlaybackContentRepository
import com.vocabularybooster.data.SqlDelightPlaybackPositionRepository
import com.vocabularybooster.data.SqlDelightWordBookRepository
import com.vocabularybooster.data.SqlDelightWordRepository
import com.vocabularybooster.data.seed.SEED_DICTIONARY_JSON
import com.vocabularybooster.data.seed.SeedDictionaryProvider
import com.vocabularybooster.data.seed.SeedImporter
import com.vocabularybooster.db.VocabularyDatabase
import com.vocabularybooster.domain.repository.AchievementRepository
import com.vocabularybooster.domain.repository.LearningSessionRepository
import com.vocabularybooster.domain.repository.LearningSettingsRepository
import com.vocabularybooster.domain.repository.LearningStatsRepository
import com.vocabularybooster.domain.repository.PlaybackContentRepository
import com.vocabularybooster.domain.repository.PlaybackPositionRepository
import com.vocabularybooster.domain.repository.WordBookRepository
import com.vocabularybooster.domain.repository.WordRepository
import com.vocabularybooster.importing.ImportRepository
import com.vocabularybooster.platform.DatabaseDriverFactoryProvider
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * 数据层 DI（Phase 2）：DB（驱动经平台端口）+ 仓储 + 词典 Provider + 种子导入。
 * 平台 actual（DatabaseDriverFactoryProvider）由 app/iosApp module 提供。
 */
public val sharedDataModule: Module = module {
    single {
        VocabularyDatabase(get<DatabaseDriverFactoryProvider>().create())
    }
    // Phase 8.6（FR-18）：WordRepository 按需导入装配——DictionaryProvider 绑定由平台
    // 模块提供（Android = FallbackDictionaryProvider 种子优先→随包全量兜底；Koin 4 无
    // override，全库只此一处定义）；手工 Koin 图缺绑定时 getOrNull 降级纯 DB 模式
    single<WordRepository> {
        SqlDelightWordRepository(
            database = get(),
            dictionaryProvider = getOrNull(),
            seedImporter = get(),
        )
    }
    single<WordBookRepository> {
        SqlDelightWordBookRepository(database = get(), clock = get())
    }
    single<LearningSessionRepository> {
        SqlDelightLearningSessionRepository(database = get(), clock = get())
    }
    // Phase 6：勋章仓储（表/索引自 schema v1 在位，零迁移）
    single<AchievementRepository> {
        SqlDelightAchievementRepository(database = get())
    }
    single<LearningSettingsRepository> {
        SqlDelightLearningSettingsRepository(database = get())
    }
    // Phase 8.6：学习统计仓储（FR-20；Q8/Q9 query-only 零迁移，分桶走注入 Clock/TimeZone）
    single<LearningStatsRepository> {
        SqlDelightLearningStatsRepository(database = get(), clock = get())
    }
    single<PlaybackPositionRepository> {
        SqlDelightPlaybackPositionRepository(database = get())
    }
    single<PlaybackContentRepository> {
        SqlDelightPlaybackContentRepository(database = get())
    }
    // Phase 7：TXT 导入仓储（单一大事务 + 整体回滚，IMPORT_SPEC §5）
    single<ImportRepository> {
        SqlDelightImportRepository(database = get())
    }
    single {
        SeedDictionaryProvider(SEED_DICTIONARY_JSON)
    }
    single {
        SeedImporter(database = get(), clock = get())
    }
}
