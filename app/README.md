# app — Android 应用模块（Phase 0 占位）

Jetpack Compose 应用层。**Phase 1 之前不创建任何代码。**

## 职责（Phase 1+）

- UI：Jetpack Compose（Material 3）
- ViewModel 层：AndroidX ViewModel + StateFlow，委托给 shared 模块的用例
- 平台实现装配：`android.speech.tts` / `SpeechRecognizer` / Media3 / SQLDelight Android 驱动
- DI 装配：Koin Android 模块

## 禁止

- 业务规则、领域模型、学习 / 播放引擎逻辑（一律放 shared 模块）
- 在本模块出现任何需要 iOS 重写的核心状态机

## 计划结构

```
app/src/main/kotlin/com/vocabularybooster/app/
├── MainActivity.kt
├── di/
├── platform/    # TtsSpeechSynthesizer / AndroidSpeechCommandRecognizer / Media3AudioPlayer / DatabaseDriverFactory
├── viewmodel/
└── ui/          # theme / navigation / word / wordbook / learning / settings / achievement
```

详见 docs/ARCHITECTURE.md。
