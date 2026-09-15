package com.vocabularybooster.app.speech

/**
 * 语音识别引擎选择策略（裁决 E2，2026-09-14，AUDIO_ENGINE_SPEC §8 引擎选择规则）：
 * 系统识别服务在场 → 系统 actual（既有行为**零变化**）；缺席（国行无 GMS / 无标准
 * RecognitionService 机型，如实测 vivo V2436A / Android 16）→ 内置 Vosk 离线引擎 actual。
 *
 * 选择只在进程启动 DI 装配时做一次；两 actual 实现同一端口，编排器/解析器/掌握语义零改动。
 * 纯函数（无 Android 依赖）——TC-AE-27 两分支单测在 JVM 直接覆盖。
 */
enum class SpeechEngineKind {
    /** 系统 `SpeechRecognizer` 路径（`AndroidSpeechCommandRecognizer`）。 */
    SYSTEM_SERVICE,

    /** 内置 Vosk 离线引擎路径（`VoskSpeechCommandRecognizer`，模型打包 APK）。 */
    EMBEDDED_VOSK,
}

object SpeechEnginePolicy {
    fun select(systemServiceAvailable: Boolean): SpeechEngineKind =
        if (systemServiceAvailable) SpeechEngineKind.SYSTEM_SERVICE else SpeechEngineKind.EMBEDDED_VOSK
}
