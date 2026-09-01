package com.vocabularybooster.app

import android.app.Application
import com.vocabularybooster.app.di.appModule
import com.vocabularybooster.di.sharedCoreModule
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

/**
 * 应用入口：装配 Koin（共享核心 + Android 平台模块）。
 */
class VocabularyBoosterApp : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@VocabularyBoosterApp)
            modules(sharedCoreModule, appModule)
        }
    }
}
