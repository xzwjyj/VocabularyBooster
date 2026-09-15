package com.vocabularybooster.app.di

import android.speech.SpeechRecognizer
import com.vocabularybooster.app.speech.FallbackSpeechCommandRecognizer
import com.vocabularybooster.app.speech.SpeechEngineKind
import com.vocabularybooster.app.speech.SpeechEnginePolicy
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
import com.vocabularybooster.platform.VoskSpeechCommandRecognizer
import com.vocabularybooster.speech.CommandParser
import com.vocabularybooster.speech.SpeechCommandRecognizer
import com.vocabularybooster.speech.SpeechSynthesizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
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
 * Phase 5 收尾（E1/E2/E3/E4，2026-09-14）：识别引擎启动时一次选择——系统识别服务在场 →
 * 内置 Vosk 离线引擎为主（已预加载、响应瞬时、零盲区），系统 actual 为备（仅主引擎硬失败时
 * 同窗回退）；缺席（国行无标准 RecognitionService 机型）→ 直连 Vosk（无回退路径）。
 * 首窗即时命中：Vosk 主引擎在窗口开启前已完成预加载（prewarmModel），首个 CommandWindow
 * 响应时间≈0ms，彻底消除系统坏服务的 1500ms E4 看门狗盲区。
 */
val appModule = module {
    single<LogSink> { AndroidLogSink() }
    single<DatabaseDriverFactoryProvider> { AndroidDatabaseDriverFactoryProvider(androidContext()) }
    single<AudioPlayer> { Media3AudioPlayer(androidContext()) }
    single<SpeechSynthesizer> { TtsSpeechSynthesizer(androidContext()) }
    single<SpeechCommandRecognizer> {
        val ctx = androidContext()
        val systemAvailable = SpeechRecognizer.isRecognitionAvailable(ctx)
        android.util.Log.i("VB-SpeechEngine", "E2 selection: isRecognitionAvailable=$systemAvailable")
        val vosk = VoskSpeechCommandRecognizer(ctx)
        val speechScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        speechScope.launch {
            // 预热异常种类不可穷举（StorageService/模型加载仅声明 IOException，OEM 路径防御）——
            // 取消照常传播（ensureActive），其余仅记日志：预加载失败不阻断装配，留给窗口内懒加载路径
            @Suppress("TooGenericExceptionCaught")
            try {
                vosk.prewarmModel() // E3：预解包/预加载模型（仅文件/CPU，不开麦克风）——首个真窗口免加载等待
            } catch (e: Exception) {
                coroutineContext.ensureActive()
                android.util.Log.w("VB-SpeechEngine", "vosk prewarm failed", e)
            }
            android.util.Log.i("VB-SpeechEngine", "vosk prewarm finished: isAvailable=${vosk.isAvailable.value}")
        }
        when (SpeechEnginePolicy.select(systemAvailable)) {
            SpeechEngineKind.SYSTEM_SERVICE -> FallbackSpeechCommandRecognizer(
                primary = vosk,                    // Vosk：已预加载、响应瞬时、零盲区（主引擎）
                secondary = AndroidSpeechCommandRecognizer(ctx),  // 系统引擎：仅主引擎硬失败时回退
                scope = speechScope,
            )
            SpeechEngineKind.EMBEDDED_VOSK -> vosk
        }
    }
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
