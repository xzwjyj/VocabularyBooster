package com.vocabularybooster

import com.vocabularybooster.di.sharedCoreModule
import kotlinx.datetime.Clock
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import kotlin.test.Test
import kotlin.test.assertNotNull

/**
 * Phase 1 冒烟：Koin 在共享模块可初始化并解析基线依赖（DoD）。
 */
class KoinSmokeTest {

    @Test
    fun sharedCoreModuleProvidesClock() {
        val koinApp = startKoin { modules(sharedCoreModule) }
        try {
            val clock = koinApp.koin.get<Clock>()
            assertNotNull(clock)
        } finally {
            stopKoin()
        }
    }
}
