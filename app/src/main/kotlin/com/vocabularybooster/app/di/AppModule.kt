package com.vocabularybooster.app.di

import android.speech.SpeechRecognizer
import com.vocabularybooster.app.speech.FallbackSpeechCommandRecognizer
import com.vocabularybooster.data.dictionary.FallbackDictionaryProvider
import com.vocabularybooster.data.seed.SeedDictionaryProvider
import com.vocabularybooster.app.speech.SpeechEngineKind
import com.vocabularybooster.app.speech.SpeechEnginePolicy
import com.vocabularybooster.app.ui.AchievementsViewModel
import com.vocabularybooster.app.ui.BookDetailViewModel
import com.vocabularybooster.app.ui.ImportViewModel
import com.vocabularybooster.app.ui.LearningSessionViewModel
import com.vocabularybooster.app.ui.LookupViewModel
import com.vocabularybooster.app.ui.SettingsViewModel
import com.vocabularybooster.app.ui.StatsViewModel
import com.vocabularybooster.app.ui.WordBooksViewModel
import com.vocabularybooster.app.ui.WordSelectionEditorViewModel
import com.vocabularybooster.app.ui.WordDetailViewModel
import com.vocabularybooster.platform.BundledDictionaryProvider
import com.vocabularybooster.playback.AudioPlayer
import com.vocabularybooster.playback.PlaybackOrchestrator
import com.vocabularybooster.platform.AndroidDatabaseDriverFactoryProvider
import com.vocabularybooster.platform.AndroidLogSink
import com.vocabularybooster.platform.DatabaseDriverFactoryProvider
import com.vocabularybooster.platform.LogSink
import com.vocabularybooster.platform.AndroidSpeechCommandRecognizer
import com.vocabularybooster.platform.Media3AudioPlayer
import com.vocabularybooster.platform.SherpaOnnxSpeechSynthesizer
import com.vocabularybooster.platform.TtsSpeechSynthesizer
import com.vocabularybooster.platform.VoskSpeechCommandRecognizer
import com.vocabularybooster.speech.CommandParser
import com.vocabularybooster.speech.LangRoutedSpeechSynthesizer
import com.vocabularybooster.speech.SpeechCommandRecognizer
import com.vocabularybooster.speech.SpeechSynthesizer
import com.vocabularybooster.data.SqlDelightWordRepository
import com.vocabularybooster.data.seed.SeedImporter
import com.vocabularybooster.domain.dictionary.DictionaryProvider
import com.vocabularybooster.domain.repository.WordRepository
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
    // FR-23 v2（2026-09-20）：TTS 路由装配——EN_* → SherpaOnnx 神经引擎（口音=模型，vivo 无厂商
    // 英音包也生效）；ZH_CN → 系统引擎；神经非 READY/speak 异常 → EN 段回退系统（进程内粘滞）。
    single { TtsSpeechSynthesizer(androidContext(), get()) } // 系统引擎：ZH 主路 + EN 降级兜底（FR-19 音色应用仅系统路）
    single { SherpaOnnxSpeechSynthesizer(androidContext()) } // 神经引擎：assets 直读 Piper 模型，构造即预热
    single<SpeechSynthesizer> {
        // 复合绑定防自引用：裸 get() 按参数类型 SpeechSynthesizer 解析回本绑定自身 →
        // StackOverflowError（Koin 4 陷阱，同 FallbackDictionaryProvider 先例）——必须按具体类型解析
        LangRoutedSpeechSynthesizer(
            neural = get<SherpaOnnxSpeechSynthesizer>(),
            system = get<TtsSpeechSynthesizer>(),
            log = get<LogSink>(),
        )
    }
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
    // Phase 8.6（FR-18）：复合词典源——精选种子优先（含例句），随包全量词典兜底（无例句）；
    // DictionaryProvider 全库唯一绑定在此（Koin 4 无 override；sharedDataModule 不再定义）。
    // primary 必须按具体类型解析（get() 会按 DictionaryProvider 解析到本绑定自身 → 自引用 StackOverflow）
    single<DictionaryProvider> {
        FallbackDictionaryProvider(
            primary = get<SeedDictionaryProvider>(),
            fallback = BundledDictionaryProvider(androidContext()),
        )
    }
    // 修复：sharedDataModule 先于 appModule 加载，getOrNull() 取不到 DictionaryProvider → 词典永远 null。
    // 在 appModule 显式覆盖 WordRepository 绑定，确保 dictionaryProvider 参数在字典源就绪后构造。
    single<WordRepository> {
        SqlDelightWordRepository(
            database = get(),
            dictionaryProvider = get<DictionaryProvider>(),
            seedImporter = get(),
        )
    }
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
            eventBus = get(), // Phase 6：与勋章引擎同一总线实例（发布/订阅对偶）
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        )
    }

    viewModel { LookupViewModel(get()) }
    viewModel { WordDetailViewModel(get(), get()) }
    viewModel { WordBooksViewModel(get()) }
    viewModel { BookDetailViewModel(get()) }
    viewModel { WordSelectionEditorViewModel(get(), get()) } // Phase 8.5：词条选择编辑（FR-17）
    viewModel { AchievementsViewModel(get()) } // Phase 6：勋章墙
    viewModel { StatsViewModel(get()) } // Phase 8.6：学习统计（FR-20）
    viewModel { SettingsViewModel(get(), get()) } // Phase 8：设置页（FR-15）；Phase 8.6：+音色枚举（FR-19）
    single { ImportEngineFactory(androidContext(), get(), get(), get()) } // Phase 7：TXT 导入
    viewModel { ImportViewModel(get(), get()) }

    // Phase 4 Step 4：学习会话屏——ViewModel 只委托应用级 PlaybackOrchestrator 单例
    viewModel { LearningSessionViewModel(get(), get(), get()) } // Phase 6：+ 勋章仓储/事件总线（仪式页快照）
}
