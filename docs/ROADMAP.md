# ROADMAP — 开发路线图

> 状态：Phase 0 定稿 ｜ 版本 1.0 ｜ 日期：2026-09-01
> **阶段纪律**：每个 Phase 结束 → 对照出口条件逐项验收 → 写 `docs/reports/PHASE_N_REPORT.md` → **用户批准后才进入下一阶段**。禁止提前实现后续 Phase 的功能。

---

## 总览

| Phase | 主题 | 依赖 |
|---|---|---|
| **0（已完成）** | 环境/需求/架构/规格 | — |
| **1（已完成，2026-09-01，见 docs/reports/PHASE_1_REPORT.md；待用户验收确认）** | 环境安装 + 工程引导（KMP 骨架） | 环境安装 |
| **2（已完成，2026-09-03，见 docs/reports/PHASE_2_REPORT.md；待用户验收确认）** | 数据层 + 生词本基础 | 1 |
| 3 | 学习引擎（纯逻辑，无音频） | 2 |
| 4 | 播放引擎（TTS + 控制） | 3 |
| 5 | 语音命令（"会了"） | 4 |
| 6 | 勋章 | 3（事件）|
| 7 | TXT 导入 | 2 |
| 8 | 设置与打磨（i18n / 无障碍 / 性能） | 4–7 |
| 9 | 稳定化 + Alpha 发布 | 全部 |
| iOS | iOS 引导（需 macOS） | 核心稳定后 |

---

## Phase 1 — 环境安装与工程引导（已完成 2026-09-01）

**结果**：13/13 DoD 达成；测试 9 次执行 0 失败；模拟器实机装机启动验证通过。偏差（compileSdk 35、未装 Android Studio、iOS host-gated 等）与修复记录见 `docs/reports/PHASE_1_REPORT.md` §7/§8。

**前置**：用户批准进入 Phase 1。
**任务**：
1. 安装环境（本机当前**全部缺失**，PHASE_0_REPORT §环境）：
   - JDK 21（Temurin）+ `JAVA_HOME`；Android Studio 最新稳定版；SDK：platform 36、build-tools、platform-tools、command-line-tools；
   - 首次启动 Android Studio 完成 SDK/许可装定；
2. `git init` + 首次提交（.gitignore 已就绪）；建议 SynologyDrive 客户端排除 `build/`、`.gradle/` 同步（风险 R3）；
3. Gradle 工程引导：`settings.gradle.kts` + 根/`shared`/`app` 三处 `build.gradle.kts`；锁定版本矩阵（ARCHITECTURE §3）；Gradle Wrapper 引入；
4. `shared`：detekt `ForbiddenImport`（禁 `android.*`/`java.*`）+ `explicitApiMode` + commonTest 跑通（用一个纯函数样例验证整条测试链）；
5. SQLDelight 接入：DATABASE_SCHEMA 全部 11 表 `.sq` v1 + JVM 驱动 + 迁移基线测试（TC-DB-06）；
6. `app`：Compose 空壳（单屏 + 导航骨架）+ minSdk 26 / target 36，真机运行通过；
7. Koin 骨架：端口接口（ARCHITECTURE §5）编译通过 + `LogSink`/`DatabaseDriverFactoryProvider` Android actual；
8. （可选）GitHub Actions：Windows JVM 测试门禁。

**出口**：`assembleDebug` 装机可开；`:shared:jvmTest`、detekt、迁移基线全绿；**无任何业务功能**（本阶段硬约束）。

## Phase 2 — 数据层与生词本基础（已完成 2026-09-03）

**结果**：批准书 24/24 DoD 达成；测试 34 次执行 0 失败（含乱序排序必测、持久化 close/reopen、多本共享 + 删除安全、仪器 UI 流程）；schema v1→v2 迁移（FR-5 逐例句勾选粒度）带专项测试；JDBC 文件库 rowid 陷阱等问题与修复见 `docs/reports/PHASE_2_REPORT.md` §7。

**交付**：DOMAIN_MODEL 实体 + Repository 实现；词条详情页（FR-2 排序渲染）；生词本增删改（FR-4）；保存流（多本 + 释义选择 + 例句开关，FR-5）；`DictionaryProvider` 本地 JSON 种子实现（≥50 词样例库，含多词性/多例句/多来源）。
**出口**：TC-DM、TC-DB-01…03 绿；词条渲染通过排序专项验收；FR-1~FR-5 逐条签字。

## Phase 3 — 学习引擎（headless）

**交付**：`StudyQueueBuilder / GroupSplitter / MasteryMarker / CompletionDetector / WordBookDeriver`（LEARNING_ENGINE_SPEC 全部规则）；会话持久化与崩溃恢复；临时调试界面驱动引擎（仅验证用，正式学习 UI 在 Phase 4）。
**出口**：TC-LE-01…10 全绿；100 词 ×10 组全流程模拟（掌握→组推进→派生→完成）通过；FR-6/FR-8/FR-9 逻辑层验收。

## Phase 4 — 播放引擎

**交付**：`SegmentBuilder / PlaybackOrchestrator`；`TtsSpeechSynthesizer` Android actual（**双语段切换**）；播放控制 UI（六控制，FR-11）；位置持久化；音频焦点处理；例句文件音频通道（`Media3AudioPlayer`，用种子数据占位音频验证）。
**出口**：TC-AE-01…03、07…09、11、12 绿；手动矩阵"暂停恢复实感"通过；FR-10/FR-11 验收。

## Phase 5 — 语音命令

**交付**：`AndroidSpeechCommandRecognizer` actual；命令窗口接入编排器；`CommandParser` + 别名设置；屏幕"会了"按钮；`RECORD_AUDIO` 权限流与降级路径。
**出口**：TC-AE-04…06、10 绿；真机识别率达标（安静环境 ≥ 90% 命中）；FR-7/FR-12 验收。

## Phase 6 — 勋章

**交付**：`AchievementEngine` + 授予事件链 + 勋章仪式页 + 勋章墙；"有勋章的书禁删"拦截。
**出口**：TC-AC 全绿；FR-13 验收。

## Phase 7 — TXT 导入

**交付**：`EncodingDetector / LineParser / ImportEngine` + 导入 UI（选文件→选本→预览→进度→报告）；事务回滚；大文件流式。
**出口**：TC-IMP-01…08 绿；10 万行 GBK 基准 ≤ 60s 且可取消；FR-14 验收。

## Phase 8 — 设置与打磨

**交付**：设置页全套（FR-15）；i18n 校对；TalkBack 无障碍 pass；性能预算逐项测量（NFR-2）。
**出口**：FR-15 + NFR-2/8 验收。

## Phase 9 — 稳定化与 Alpha

**交付**：全手动矩阵回归（TEST_PLAN §7）；完整 FR 验收清单走查；alpha 出包。
**出口**：PROJECT_SPEC 全部 FR 验收记录归档。

## Phase iOS — iOS 引导（需 macOS）

**前置**：macOS + Xcode 15+（实体 Mac 或 CI macOS runner，风险 R2）。
**交付**：XCFramework + SPM 集成；SwiftUI 壳 + 核心界面；5 个 actual 类（ARCHITECTURE §5 矩阵）；Flow→SwiftUI 桥接方案定型。
**出口**：`:shared:allTests`（iosSimulatorArm64）全绿；核心流程 iOS 手动走查通过。

## 显式延后（需求变更流程后才立项）

| 主题 | 现有预留 |
|---|---|
| 词典 API / AI 内容接入 | `DictionaryProvider` 端口 + 导入词回填任务（IMPORT_SPEC §8） |
| 授权影视/演讲/TED/有声书原声 | `Example.sourceType/sourceRef/licenseNote/audioUri` 字段已建 |
| 云同步 | 触发 ID 策略 RFC（ADR-07） |
| 更多勋章类型 | 事件架构（ACHIEVEMENT_SPEC §5） |
| 语音命令 PAUSE/RESUME/NEXT/REPLAY/EXIT | 枚举 + 关键词表已定义（DOMAIN_MODEL §3.3） |

---

| 版本 | 日期 | 变更 |
|---|---|---|
| 1.0 | 2026-09-01 | Phase 0 初版 |
| 1.1 | 2026-09-01 | Phase 1 标记完成（验收记录见 PHASE_1_REPORT） |
