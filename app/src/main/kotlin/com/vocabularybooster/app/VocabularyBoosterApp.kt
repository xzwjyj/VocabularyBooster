package com.vocabularybooster.app

import android.app.Application
import android.util.Log
import com.vocabularybooster.app.di.appModule
import com.vocabularybooster.achievement.AchievementEngine
import com.vocabularybooster.data.seed.SeedDictionaryProvider
import com.vocabularybooster.data.seed.SeedImporter
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
    }

    private companion object {
        const val TAG = "VocabularyBoosterApp"
    }
}
