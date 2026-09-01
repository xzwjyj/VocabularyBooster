# shared — Kotlin Multiplatform 共享模块（Phase 0 占位）

全部核心业务逻辑所在模块：Domain / Data / Database / Repository / 学习引擎 / 播放编排 / 语音命令抽象 / 勋章 / 导入 / 设置。
**Phase 1 之前不创建任何代码；当前仅包含包结构占位（.gitkeep）。**

铁律（详见 docs/ARCHITECTURE.md 模块边界一节）：

1. `commonMain` 禁止 import 任何 `android.*` / `java.*`（Phase 1 起由 detekt 规则强制）
2. 平台能力只能通过 `expect/actual` 暴露的接口进入：`SpeechSynthesizer` / `AudioPlayer` / `SpeechCommandRecognizer` / `DatabaseDriverFactory` / `LogSink`
3. 所有引擎逻辑必须是纯 Kotlin，可在 JVM 上 100% 单元测试

## 包结构

```
shared/src/commonMain/kotlin/com/vocabularybooster/
├── domain/model        # Word / DefinitionEntry / Example / WordBook …
├── domain/repository   # Repository 接口（端口）
├── data/db             # SQLDelight 数据库（.sq schema 文件，Phase 1 引入）
├── data/repository     # Repository 实现
├── learning/           # 学习引擎：队列构建、分组、会话状态机
├── playback/           # 播放编排器：分段模型、控制语义
├── speech/             # 语音命令解析（纯 Kotlin）
├── achievement/        # 勋章引擎
├── import/             # TXT 导入
└── settings/           # 设置领域

shared/src/androidMain/kotlin/com/vocabularybooster/platform/   # Android actual 实现
shared/src/iosMain/kotlin/com/vocabularybooster/platform/       # iOS actual 实现（未来）
shared/src/commonTest/kotlin/com/vocabularybooster/             # 跨平台单元测试
```
