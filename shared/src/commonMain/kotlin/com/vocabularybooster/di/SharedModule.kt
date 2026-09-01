package com.vocabularybooster.di

import kotlinx.datetime.Clock
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * 共享核心 DI 基线。
 * Phase 1 仅注入 Clock（ARCHITECTURE §4 铁律 5：时间注入保证可测性）；
 * 引擎 / 仓储依赖随各阶段实现加入，平台 actual 由 app/iosApp 各自 module 提供。
 */
public val sharedCoreModule: Module = module {
    single<Clock> { Clock.System }
}
