package com.vocabularybooster.app.speech

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * SpeechEnginePolicy 纯函数 JVM 单测（TC-AE-27，裁决 E2，AUDIO_ENGINE_SPEC §8 引擎选择规则）：
 * 系统识别服务在场 → 系统 actual（行为零变化）；缺席（国行无标准 RecognitionService 机型）→ 内置 Vosk。
 * 真机两路径的实际行为 = M1–M4 手动矩阵（不伪造）。
 */
class SpeechEnginePolicyTest {

    @Test
    fun systemServicePresent_selectsSystemServiceEngine() {
        assertEquals(SpeechEngineKind.SYSTEM_SERVICE, SpeechEnginePolicy.select(systemServiceAvailable = true))
    }

    @Test
    fun systemServiceAbsent_selectsEmbeddedVoskEngine() {
        assertEquals(SpeechEngineKind.EMBEDDED_VOSK, SpeechEnginePolicy.select(systemServiceAvailable = false))
    }
}
