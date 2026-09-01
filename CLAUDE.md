# CLAUDE.md — VocabularyBooster 工程协作规范

英语单词学习 App：查词 → 生词本 → 分组循环朗读 + 语音命令"会了" → 完成勋章。Android（Compose）先行，核心逻辑 Kotlin Multiplatform 共享，未来 iOS（SwiftUI）复用核心、不重写。

## 当前状态与阶段纪律（最高优先级）

- **Phase 0（规格与架构）已完成；决策 D1–D4 已冻结（2026-09-01，见 PHASE_0_REPORT 决策记录）；Phase 1（工程基础）已获批进行中。**
- 严格按 `docs/ROADMAP.md` 推进：**未经用户批准进入下一 Phase，禁止编写该 Phase 的任何代码**。开始编码前先确认当前所处 Phase 与出口条件。
- 需求唯一事实来源：`docs/PROJECT_SPEC.md`（FR-01…FR-16 编号引用）。**任何行为疑问先查它**；要改需求 → 先改 PROJECT_SPEC（含版本记录）再改下游文档。
- 开放问题登记在 `docs/reports/PHASE_0_REPORT.md`，逐条决策后回写 PROJECT_SPEC。

## 文档地图

| 场景 | 查 |
|---|---|
| 需求/验收 | `docs/PROJECT_SPEC.md` |
| 领域实体/状态机/事件 | `docs/DOMAIN_MODEL.md` |
| 表结构/关键查询/事务 | `docs/DATABASE_SCHEMA.md` |
| 模块边界/选型/iOS 复用 | `docs/ARCHITECTURE.md` |
| 学习流程规则 | `docs/LEARNING_ENGINE_SPEC.md` |
| 播放分段/控制语义/命令窗口 | `docs/AUDIO_ENGINE_SPEC.md` |
| TXT 导入 | `docs/IMPORT_SPEC.md` |
| 勋章 | `docs/ACHIEVEMENT_SPEC.md` |
| 测试用例编号 | `docs/TEST_PLAN.md` |
| 阶段计划 | `docs/ROADMAP.md` |
| 阶段验收报告 | `docs/reports/PHASE_N_REPORT.md` |

## 开发环境（2026-09-01 Phase 1 安装完成）

- 本机：Windows 10。JDK：Temurin 21.0.12.1 便携版 `C:\Users\zack\.jdks\`（**未入系统 PATH，构建前 `export JAVA_HOME="C:/Users/zack/.jdks/jdk-21.0.12.1+1"`**）；Android SDK：`%LOCALAPPDATA%\Android\Sdk`（platform-35 / build-tools 35.0.0 / platform-tools）；Gradle wrapper 8.11.1；Git 2.46.2。Android Studio 未装（纯 CLI 构建不需要）。
- iOS 构建需 macOS：本机没有，iOS 阶段另行解决（`iosApp` 与 shared iOS targets 仅 macOS 宿主启用）。
- 仓库位于 Synology Drive 同步目录：**不要把 `build/`、`.gradle/` 交给同步**（已在 .gitignore 提示）。
- 构建命令：
  ```bash
  ./gradlew :shared:jvmTest            # 核心逻辑测试（Windows 可全量跑）
  ./gradlew :shared:testDebugUnitTest  # commonTest 的 Android 编译运行
  ./gradlew :shared:allTests           # 含 iOS 目标（需 macOS 宿主）
  ./gradlew :app:assembleDebug
  ./gradlew :shared:detekt :app:detekt # 静态检查 + commonMain 平台 import 禁令
  ./gradlew :shared:checkPlatformBoundaries  # 架构边界硬门禁
  ```

## 架构铁律（违反 = 返工）

1. `shared/commonMain` **禁止** import `android.*` / `java.*`（detekt 强制 fail）。平台能力只经 ARCHITECTURE §5 的端口接口进入。
2. 业务规则（排序规则、队列/分组/掌握/完成/派生、播放状态机、导入、勋章）只存在于 shared；`app`/`iosApp` 只做 UI + ViewModel 委托 + actual 装配。
3. 词条排序恒为 `(partOfSpeechOrder ASC, definitionOrder ASC)`，同词性连续、EN 先于 CN（PROJECT_SPEC FR-2）；改任何列表/播放序列前先读 DOMAIN_MODEL §4/§5。
4. MeaningEN/MeaningCN 不可拆分；Example（句+译文+音频）为原子单元；收藏选择粒度 = DefinitionEntry + includeExamples。
5. "会了"语音与按钮走同一 `markMastered()`；落库必须单事务（SessionWord + WordMastery）。
6. 语音识别只存在于 CommandWindow 态；TTS 播放期间 recognizer 必须关闭（FR-12）。
7. Pause/Resume 以 Segment 为粒度，绝不整词重播（TTS 段重读当前段是已批准的平台限制，ADR-09）。
8. 退出三分支（D1）：零掌握→不派生不建空本；部分掌握→派生 DERIVED 快照本（只复制关系行 WordBookEntry/EntryDefinition，**永不复制 Word**，不继承掌握状态）；全部掌握→COMPLETED+勋章不派生。**ORIGINAL 母本永不被会话修改（D2）**；DERIVED 本必须记录 parentWordBookId + sourceSessionId（D3）；掌握作用域=（生词本,词）（D4）。
9. DB 变更 = 新 schema 版本 + `.sqm` 迁移 + 迁移测试；禁止改历史建表语句。
10. 引擎内时间一律走注入的 `Clock`；测试用 TestDispatcher/虚拟时间（NFR-9）。

## 编码规范

- Kotlin 官方风格；shared 模块 `explicitApiMode`（public API 显式声明）；detekt 绿是合并门禁。
- 命名：领域名词与文档实体严格一致（Word / DefinitionEntry / Example / WordBook / WordBookEntry / WordBookEntryDefinition / WordMastery / LearningSession / SessionWord / Achievement）。
- 代码、标识符、commit message 用英文（Conventional Commits）；文档与 UI 文案用简体中文。
- 协程：suspend 到 Repository 层为止；引擎暴露 StateFlow；UI 层不碰引擎内部状态。

## 测试规范

- 每个引擎行为变更必须带 commonTest 用例（TEST_PLAN 用例编号）；边界情形表条目 = 必测。
- 只用手写 Fake 端口，不用 mock 框架；引擎测试不依赖 Android/模拟器（Windows CI 可跑）。
- 新 FR 功能合入前：相关 TC 组全绿 + 对应 FR 验收行更新。

## 何时更新文档

| 动作 | 必须同步 |
|---|---|
| 改需求/规则 | PROJECT_SPEC（+版本记录）→ 受影响的规格文档 |
| 改表结构 | DATABASE_SCHEMA + DOMAIN_MODEL + 迁移测试 |
| 加平台能力 | ARCHITECTURE §5 端口矩阵 + 平台 actual |
| 加/改用例 | TEST_PLAN 矩阵 |
| Phase 收尾 | docs/reports/PHASE_N_REPORT.md + ROADMAP 状态 |
