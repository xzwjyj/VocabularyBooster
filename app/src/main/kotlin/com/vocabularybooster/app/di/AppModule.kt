package com.vocabularybooster.app.di

import com.vocabularybooster.app.ui.BookDetailViewModel
import com.vocabularybooster.app.ui.LearningSessionViewModel
import com.vocabularybooster.app.ui.LookupViewModel
import com.vocabularybooster.app.ui.WordBooksViewModel
import com.vocabularybooster.app.ui.WordDetailViewModel
import com.vocabularybooster.playback.AudioPlayer
import com.vocabularybooster.playback.PlaybackOrchestrator
import com.vocabularybooster.platform.AndroidDatabaseDriverFactoryProvider
import com.vocabularybooster.platform.AndroidLogSink
import com.vocabularybooster.platform.DatabaseDriverFactoryProvider
import com.vocabularybooster.platform.LogSink
import com.vocabularybooster.platform.AndroidSpeechCommandRecognizer
import com.vocabularybooster.platform.Media3AudioPlayer
import com.vocabularybooster.platform.TtsSpeechSynthesizer
import com.vocabularybooster.speech.CommandParser
import com.vocabularybooster.speech.SpeechCommandRecognizer
import com.vocabularybooster.speech.SpeechSynthesizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.dsl.module

/**
 * Android 平台装配：提供 :shared 中定义端口的 Android actual（ARCHITECTURE §5 平台矩阵）
 * + UI 层 ViewModel（ADR-06：ViewModel 只放 app 层，委托仓储）。
 *
 * Phase 4 Step 3：播放后端装配——Media3AudioPlayer / TtsSpeechSynthesizer 两个 actual +
 * PlaybackOrchestrator 单例（唯一实例；scope = 应用级 Main.immediate，平台调用主线程限定契约）。
 * Phase 5 Step 1：语音命令接入——AndroidSpeechCommandRecognizer（识别只存在于 CommandWindow）
 * + CommandParser（唯一命令解释器）；编排器 = 唯一播放状态机，命令汇合于 masterCurrentWord 路径。
 */
val appModule = module {
    single<LogSink> { AndroidLogSink() }
    single<DatabaseDriverFactoryProvider> { AndroidDatabaseDriverFactoryProvider(androidContext()) }
    single<AudioPlayer> { Media3AudioPlayer(androidContext()) }
    single<SpeechSynthesizer> { TtsSpeechSynthesizer(androidContext()) }
    single<SpeechCommandRecognizer> { AndroidSpeechCommandRecognizer(androidContext()) }
    single { CommandParser() }
    single {
        PlaybackOrchestrator(
            engine = get(),
            contentRepository = get(),
            positionRepository = get(),
            settingsRepository = get(),
            audioPlayer = get(),
            synthesizer = get(),
            recognizer = get(),
            commandParser = get(),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        )
    }

    viewModel { LookupViewModel(get()) }
    viewModel { WordDetailViewModel(get(), get()) }
    viewModel { WordBooksViewModel(get()) }
    viewModel { BookDetailViewModel(get()) }

    // Phase 4 Step 4：学习会话屏——ViewModel 只委托应用级 PlaybackOrchestrator 单例
    viewModel { LearningSessionViewModel(get()) }
}
