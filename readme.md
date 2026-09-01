# VocabularyBooster

英语单词学习 App（Android 优先，核心代码跨平台复用于未来 iOS 版本）。

> **当前状态：Phase 1（工程基础）进行中——环境已安装（JDK 21 / Gradle wrapper / Android SDK），KMP 工程骨架、SQLDelight schema v1、Compose 壳模块与测试基础设施已落地，业务功能尚未编写。**
> 下一步：阅读 [docs/ROADMAP.md](docs/ROADMAP.md) 的 Phase 1 验收条件；阶段报告见 [docs/reports/PHASE_1_REPORT.md](docs/reports/PHASE_1_REPORT.md)。

## 文档地图

| 文档 | 内容 |
|---|---|
| [docs/PROJECT_SPEC.md](docs/PROJECT_SPEC.md) | 产品需求规格（FR / NFR 编号，唯一需求事实来源） |
| [docs/DOMAIN_MODEL.md](docs/DOMAIN_MODEL.md) | 领域模型、实体、不变量、状态机 |
| [docs/DATABASE_SCHEMA.md](docs/DATABASE_SCHEMA.md) | 数据库表结构 DDL（SQLDelight） |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | 跨平台架构、模块边界、技术选型决策 |
| [docs/LEARNING_ENGINE_SPEC.md](docs/LEARNING_ENGINE_SPEC.md) | 学习引擎：队列 / 分组 / 会话状态机 |
| [docs/AUDIO_ENGINE_SPEC.md](docs/AUDIO_ENGINE_SPEC.md) | 播放引擎：分段模型、控制语义、语音抽象 |
| [docs/IMPORT_SPEC.md](docs/IMPORT_SPEC.md) | TXT 导入规格 |
| [docs/ACHIEVEMENT_SPEC.md](docs/ACHIEVEMENT_SPEC.md) | 勋章系统规格 |
| [docs/TEST_PLAN.md](docs/TEST_PLAN.md) | 测试策略与用例矩阵 |
| [docs/ROADMAP.md](docs/ROADMAP.md) | 分阶段开发路线图 |
| [docs/reports/PHASE_0_REPORT.md](docs/reports/PHASE_0_REPORT.md) | Phase 0 验证报告 |
| [CLAUDE.md](CLAUDE.md) | AI 协作工程规范（开发前必读） |

## 目录结构

```
VocabularyBooster/
├── CLAUDE.md              # AI 协作工程规范
├── readme.md              # 本文件
├── .gitignore
├── docs/                  # 全部规格文档（Phase 0 产出）
│   └── reports/           # 阶段验证报告
├── shared/                # Kotlin Multiplatform 共享模块（核心业务逻辑，Phase 1 初始化）
│   └── src/{commonMain, androidMain, iosMain, commonTest}/
├── app/                   # Android 应用模块（Jetpack Compose，Phase 1 初始化）
└── iosApp/                # iOS 应用（SwiftUI，未来阶段，占位）
```

## 开发环境（本机 2026-09-01 安装结果，均为用户目录安装、未改系统配置）

| 工具 | 要求 | 本机状态 |
|---|---|---|
| JDK | 17+（推荐 21） | ✅ Temurin 21.0.12.1（便携 zip 解压至 `C:\Users\zack\.jdks\`，未入系统 PATH） |
| Android SDK | compileSdk 35 / minSdk 26 | ✅ `%LOCALAPPDATA%\Android\Sdk`（platform-35 / build-tools 35.0.0 / platform-tools，许可已接受） |
| Gradle | 通过 Gradle Wrapper 引入，无需全局安装 | ✅ wrapper 8.11.1（`gradle/wrapper/`） |
| Android Studio | 可选（纯 CLI 构建不需要） | ❌ 未安装 |
| Git | 2.x | ✅ 2.46.2 |
| iOS 构建 | macOS + Xcode 15+ | ❌ 本机为 Windows，iOS 阶段另行解决（`iosApp` 模块仅 macOS 宿主启用） |

## 构建命令

JDK 未入系统 PATH，构建前先设置 `JAVA_HOME`（Git Bash 示例）：

```bash
export JAVA_HOME="C:/Users/zack/.jdks/jdk-21.0.12.1+1"

./gradlew :shared:jvmTest            # 核心逻辑测试（Windows 可全量跑）
./gradlew :shared:testDebugUnitTest  # commonTest 的 Android 编译运行
./gradlew :shared:allTests           # 含 iOS 目标（需 macOS 宿主）
./gradlew :app:assembleDebug         # Android Debug 构建
./gradlew :app:testDebugUnitTest     # app 模块单元测试
./gradlew :shared:detekt :app:detekt # 静态检查（shared 含 commonMain 平台 import 禁令）
./gradlew :shared:checkPlatformBoundaries  # 架构边界硬门禁（Gradle 任务）
```

---

## 原始产品需求笔记（保留存档）

以下为本项目最初的产品功能笔记，原文保留；详细规格以 [docs/PROJECT_SPEC.md](docs/PROJECT_SPEC.md) 为准：

- 用于背英语单词的安卓app,但代码架构可复用于后续ios app
- 该app可用于查英语单词，查到的英语单词包含中文和英文释义，每种中英文释义都包含相应例句及例句译文
- 可将查到的英语单词保存到生词本中（可选择添加到多个不同的生词本中，且生词本可以自定义命名），另外保存单词时可以选择添加其中的哪些中英文释义（也包括释义对应的例句及例句译文）
- 可对指定的生词本进行分组顺序朗读，可以设置每组包含多少单词（默认每组10个单词）
- 每组单词进行顺序循环朗读，同时监听语音指令，如果听到“会了”指令，则该单词剔除朗读列表，然后跳到下一个组内单词进行朗读。当所有组内单词都“会了”，则开始下一组单词的顺序循环朗读
- 当朗读时点击退出按钮，则自动将该单词本剔除“会了”的单词后，另存为“该单词本名称+日期+年月日时分”的新单词本
- 单词播放设置包括：单词拼读开关，播放中文释义开关，播放英文释义开关，播放例句译文开关
- 该生词本中的所有组的所有单词都“会了”后，停止播放并在app界面显示并保存永久勋章
- 可导入txt格式的单词表，并可选择导入到哪个生词本中
- 例句最好是来自现实中的电影、电视剧、名人演讲、TED演讲或者有声书中的句子，声音也来自这些场景的原声
- 具体播放暂停功能
