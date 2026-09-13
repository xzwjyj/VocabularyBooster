package com.vocabularybooster.app.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vocabularybooster.platform.AndroidSpeechCommandRecognizer
import com.vocabularybooster.speech.RecognitionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 权限拒绝 → Unavailable 映射的独立插桩类（Phase 5 Step 1，裁决 D5；AUDIO_ENGINE_SPEC §8/§9）。
 *
 * **为何独立成类**：运行时 `pm revoke` 会杀死已授权运行中的 app 进程（Android 11+ 平台行为，
 * 插桩进程与 app 同进程）——全套件禁止 revoke。拒绝态只能由「新装即拒」提供；而完整套件内
 * 本类按发现序晚于 AndroidSpeechCommandRecognizerInstrumentedTest（其用例会 pm grant）→
 * Assume 诚实跳过。**确定性执行法（两段式，报告/CI 注记）**——单类隔离运行，Gradle 每次插桩
 * 前重装 APK = 全新拒绝态：
 * ```
 * ./gradlew :app:connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.class=com.vocabularybooster.app.audio.PermissionDeniedSpeechInstrumentedTest
 * ```
 */
@RunWith(AndroidJUnit4::class)
class PermissionDeniedSpeechInstrumentedTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    /**
     * D5 权限拒绝：listenOnce 以 Unavailable 上报（startListening 同步 SecurityException /
     * onError(INSUFFICIENT_PERMISSIONS) 均映射硬失败），绝不抛异常、绝不 Hit；
     * 硬失败拉低 isAvailable（编排器后续窗口前置门降级，AUDIO §9）。
     */
    @Test
    fun permissionDenied_mapsToUnavailable() = runBlocking {
        val denied = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        assumeTrue("RECORD_AUDIO 已被授予（非全新安装态）：转手动矩阵", denied)
        val recognizer = withContext(Dispatchers.Main.immediate) { AndroidSpeechCommandRecognizer(context) }
        assumeTrue("设备无语音识别服务：权限拒绝路径转手动冒烟", recognizer.isAvailable.value)

        val result = withTimeout(10_000) { recognizer.listenOnce(windowMs = 3_000) }

        assertEquals(RecognitionResult.Unavailable, result)
        assertFalse("硬失败应拉低 isAvailable（后续窗口前置门降级）", recognizer.isAvailable.value)
    }
}
