package com.vocabularybooster.app

import com.vocabularybooster.app.di.appModule
import com.vocabularybooster.di.sharedCoreModule
import com.vocabularybooster.platform.LogSink
import kotlinx.datetime.Clock
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin

/**
 * Phase 1 冒烟（JVM）：Koin 装配 sharedCoreModule + appModule 后
 * 无 Context 依赖的绑定可解析（DoD）。DatabaseDriverFactoryProvider
 * 依赖 androidContext()，留待 androidTest 验证。
 */
class AppModuleSmokeTest {

    @Test
    fun platformBindingsResolve() {
        val koinApp = startKoin { modules(sharedCoreModule, appModule) }
        try {
            assertNotNull(koinApp.koin.get<Clock>())
            assertNotNull(koinApp.koin.get<LogSink>())
        } finally {
            stopKoin()
        }
    }
}
