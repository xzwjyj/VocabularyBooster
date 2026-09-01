package com.vocabularybooster.app

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase 1 冒烟（instrumented）：MainActivity 可启动（启动即崩溃则失败）。
 * Compose UI 断言随后续阶段 UI 测试引入。需要设备/模拟器运行。
 */
@RunWith(AndroidJUnit4::class)
class LaunchSmokeTest {

    @Test
    fun mainActivityLaunches() {
        ActivityScenario.launch(MainActivity::class.java)
    }
}
