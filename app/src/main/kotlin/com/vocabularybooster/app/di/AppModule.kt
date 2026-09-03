package com.vocabularybooster.app.di

import com.vocabularybooster.app.ui.BookDetailViewModel
import com.vocabularybooster.app.ui.LookupViewModel
import com.vocabularybooster.app.ui.WordBooksViewModel
import com.vocabularybooster.app.ui.WordDetailViewModel
import com.vocabularybooster.platform.AndroidDatabaseDriverFactoryProvider
import com.vocabularybooster.platform.AndroidLogSink
import com.vocabularybooster.platform.DatabaseDriverFactoryProvider
import com.vocabularybooster.platform.LogSink
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.dsl.module

/**
 * Android 平台装配：提供 :shared 中定义端口的 Android actual（ARCHITECTURE §5 平台矩阵）
 * + UI 层 ViewModel（ADR-06：ViewModel 只放 app 层，委托仓储）。
 * TTS / Media3 / SpeechRecognizer actual 随 Phase 4/5 加入。
 */
val appModule = module {
    single<LogSink> { AndroidLogSink() }
    single<DatabaseDriverFactoryProvider> { AndroidDatabaseDriverFactoryProvider(androidContext()) }

    viewModel { LookupViewModel(get()) }
    viewModel { WordDetailViewModel(get(), get()) }
    viewModel { WordBooksViewModel(get()) }
    viewModel { BookDetailViewModel(get()) }
}
