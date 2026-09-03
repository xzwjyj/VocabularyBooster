package com.vocabularybooster.app

import android.app.Application
import android.util.Log
import com.vocabularybooster.app.di.appModule
import com.vocabularybooster.data.seed.SeedDictionaryProvider
import com.vocabularybooster.data.seed.SeedImporter
import com.vocabularybooster.di.sharedCoreModule
import com.vocabularybooster.di.sharedDataModule
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
        startKoin {
            androidContext(this@VocabularyBoosterApp)
            modules(sharedCoreModule, sharedDataModule, appModule)
        }
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
