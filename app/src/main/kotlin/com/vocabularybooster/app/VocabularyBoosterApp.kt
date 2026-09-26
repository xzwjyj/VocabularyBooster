package com.vocabularybooster.app

import android.app.Application
import android.util.Log
import com.vocabularybooster.app.di.appModule
import com.vocabularybooster.achievement.AchievementEngine
import com.vocabularybooster.data.seed.SeedDictionaryProvider
import com.vocabularybooster.data.seed.SeedImporter
import com.vocabularybooster.data.videoimport.VideoImportEngine
import com.vocabularybooster.data.videoimport.VideoImportProvider
import com.vocabularybooster.di.sharedCoreModule
import com.vocabularybooster.di.sharedDataModule
import com.vocabularybooster.di.sharedLearningModule
import com.vocabularybooster.playback.AudioPlayer
import com.vocabularybooster.speech.SpeechSynthesizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.core.context.startKoin
import org.koin.android.ext.koin.androidContext

/** 应用入口：DI 装配 + 首启种子导入（ARCHITECTURE：app 只做装配，零业务规则）。 */
class VocabularyBoosterApp : Application() {

    private val seedImporter: SeedImporter by inject()
    private val seedProvider: SeedDictionaryProvider by inject()
    private val videoImportEngine: VideoImportEngine by inject()

    override fun onCreate() {
        super.onCreate()
        val koinApp = startKoin {
            androidContext(this@VocabularyBoosterApp)
            modules(sharedCoreModule, sharedDataModule, sharedLearningModule, appModule)
        }
        // Phase 4 Step 3：播放后端在主线程预装配——ExoPlayer 构造线程确定（线程契约）+
        // TTS 异步初始化提前启动（speak 仍不假定 ready，见 TtsSpeechSynthesizer）
        koinApp.koin.get<AudioPlayer>()
        koinApp.koin.get<SpeechSynthesizer>()
        // Phase 6：勋章引擎订阅领域事件（DB 密集 → IO scope；冷启动即订阅，
        // 先于任何 UI 与完成事件——SharedFlow 无 replay，迟订阅丢历史事件）
        koinApp.koin.get<AchievementEngine>()
            .start(CoroutineScope(SupervisorJob() + Dispatchers.IO))
        // 首启词库导入：幂等（ensureSeeded 空库才导）；失败仅记日志，不阻断启动
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { seedImporter.ensureSeeded(seedProvider) }
                .onFailure { Log.w(TAG, "seed import failed", it) }
        }
        // 视频预置数据导入：检查 assets/video_import/data.json 并导入
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { importVideoData() }
                .onFailure { Log.w(TAG, "video import failed", it) }
        }
    }

    /** 导入预置的视频数据（如果有） */
    private suspend fun importVideoData() {
        val jsonFileName = "video_import/data.json"
        try {
            val jsonContent = assets.open(jsonFileName).bufferedReader().use { it.readText() }
            if (jsonContent.isNotBlank()) {
                val provider = VideoImportProvider(jsonContent)
                Log.i(TAG, "Found video import data: ${provider.getWordBookName()}, ${provider.getWordCount()} words")
                val report = videoImportEngine.import(provider.load())
                Log.i(TAG, "Video import completed: ${report.importedWords} new, ${report.reusedWords} reused")
            }
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            // 文件不存在或解析失败，静默跳过（可选导入任何失败不崩启动——IO/解析/DB 种类不可穷举）
            Log.d(TAG, "No video import data found or parse error: ${e.message}")
        }
    }

    private companion object {
        const val TAG = "VocabularyBoosterApp"
    }
}
