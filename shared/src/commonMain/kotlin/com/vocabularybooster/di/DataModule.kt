package com.vocabularybooster.di

import com.vocabularybooster.data.SqlDelightWordBookRepository
import com.vocabularybooster.data.SqlDelightWordRepository
import com.vocabularybooster.data.seed.SEED_DICTIONARY_JSON
import com.vocabularybooster.data.seed.SeedDictionaryProvider
import com.vocabularybooster.data.seed.SeedImporter
import com.vocabularybooster.db.VocabularyDatabase
import com.vocabularybooster.domain.dictionary.DictionaryProvider
import com.vocabularybooster.domain.repository.WordBookRepository
import com.vocabularybooster.domain.repository.WordRepository
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
    single<WordRepository> {
        SqlDelightWordRepository(database = get())
    }
    single<WordBookRepository> {
        SqlDelightWordBookRepository(database = get(), clock = get())
    }
    single {
        SeedDictionaryProvider(SEED_DICTIONARY_JSON)
    }
    single<DictionaryProvider> {
        get<SeedDictionaryProvider>()
    }
    single {
        SeedImporter(database = get(), clock = get())
    }
}
