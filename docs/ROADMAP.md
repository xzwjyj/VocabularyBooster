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
| **3（已完成，2026-09-04，见 docs/reports/PHASE_3_REPORT.md；最终验收 PASS）** | 学习引擎（纯逻辑，无音频） | 2 |
| **4（已完成，Step 0–4 分步验收，2026-09-11；并入 Phase 5 checkpoint `7ccb04c`，见 docs/reports/PHASE_4_STEP_4_REPORT.md）** | 播放引擎（TTS + 控制） | 3 |
| **5（已完成，2026-09-18，见 docs/reports/PHASE_5_REPORT.md；M1–M4 真机统计延后——用户裁决）** | 语音命令（"会了"） | 4 |
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

## Phase 3 — 学习引擎（headless）（已完成 2026-09-04）

**结果**：最终验收 PASS。TC-LE-01…11 全绿 + Phase 2 遗留 TC-DB-07 DB 级断言补齐（3 用例，不改 Phase 2 行为）；100 词 ×10 组全流程（掌握→组推进→派生→完成）真实 SQLite 集成通过；测试门禁 jvmTest 154 / commonTest(Android) 98 / app 1，0 失败，detekt/平台边界/assembleDebug/`git diff --check` 全绿；**schema 恒 v2（零 DDL、零迁移），.sq 变更全部 query-only**；app 层零改动。规格演进：LE spec v1.3 / DOMAIN_MODEL v1.4 / DATABASE_SCHEMA v1.5 / TEST_PLAN v1.4（Step 5D 交集语义裁决，2026-09-04）。偏差：原文「临时调试界面」未交付——引擎以 headless 方式由四层测试验证，正式学习 UI 在 Phase 4。详见 `docs/reports/PHASE_3_REPORT.md`。

**交付**：`StudyQueueBuilder / GroupSplitter / MasteryMarker / CompletionDetector / WordBookDeriver`（LEARNING_ENGINE_SPEC 全部规则）；会话持久化与崩溃恢复；临时调试界面驱动引擎（仅验证用，正式学习 UI 在 Phase 4）。
**出口**：TC-LE-01…11 全绿（TEST_PLAN §4.3 批准全集，覆盖 LEARNING_ENGINE_SPEC §10 十一条边界，含退出分支 C）；100 词 ×10 组全流程模拟（掌握→组推进→派生→完成）通过；FR-6/FR-8/FR-9 逻辑层验收。顺手补 Phase 2 遗留 TC-DB-07 的 DB 级断言（不改 Phase 2 行为）。

## Phase 4 — 播放引擎

**交付**：`SegmentBuilder / PlaybackOrchestrator`（**CommandWindow 纯倒计时，不接入 SpeechCommandRecognizer——Step 0 裁决 L1，识别器接线/CommandParser/权限流归 Phase 5**）；`TtsSpeechSynthesizer` Android actual（**双语段切换**）；播放控制 UI（六控制，FR-11）+ 正式学习会话屏（Phase 3 偏差补齐，含 BOOK_DELETED 恢复提示——Phase 3 延期项 3）；位置持久化（**恢复双源优先级 = SessionWord.PLAYING 词级真相，裁决 L3**）；`LearningSettingsRepository` 加法扩展（commandWindowMs / ttsRate / ttsPitch 读取，裁决 L6）；音频焦点处理；例句文件音频通道（`Media3AudioPlayer`，用种子数据占位音频验证）；书完成/退出「停止播放」接线（TC-LE-06/11 延期段）；逐词空 Segment 语义（`空段 → CommandWindow → advance()`，绝不 MASTERED，裁决 L2）与开关下一-Segment 生效粒度（裁决 L4）。
**出口**：TC-AE-01…03、07…19 绿（TC-AE-04…06、10 归 Phase 5）；手动矩阵"暂停恢复实感"通过；FR-10/FR-11 验收。

## Phase 5 — 语音命令

**交付**：`AndroidSpeechCommandRecognizer` actual；命令窗口接入编排器；`CommandParser` + 别名设置；屏幕"会了"按钮；`RECORD_AUDIO` 权限流与降级路径。
**收尾范围修订（2026-09-14，用户需求变更 + 裁决 E1/E2）**：语音命令须支持**中国大陆发售的所有安卓手机**——实测 vivo V2436A（Android 16）系统无任何标准 RecognitionService，系统 `SpeechRecognizer` 路径在国行主流机型不可用。新增交付：**App 内置离线识别引擎（Vosk + 中文小模型打包进 APK）**作为系统服务缺席时的兜底 actual（§8 双 actual + E2 选择规则：系统服务在场优先、行为零变化）；编排器/解析器/掌握语义零改动。**同日增补（裁决 E3）**：真机诊断证明 vivo 存在"注册了 `RecognitionService` 但绑定即硬失败"的厂商服务（蓝心 Copilot，探测被骗）→ 增交付**引擎回退代理**（主引擎窗口内可用性级失败 → 当场回退内置引擎完成同窗剩余预算 + 进程内降级主引擎；GMS 机零变化；E2 启动探测保留）。**二次增补（裁决 E4）**：坏服务另有静默死法（零回调零错误挂至窗口超时，E3 未触发、用户命令词被吞）→ 系统 actual 增**响应看门狗**（1500ms 零回调判死 → 按可用性级失败上报 → E3 同窗回退；GMS 零误触）。口径见 PROJECT_SPEC v1.7、AUDIO_ENGINE_SPEC v1.5。
**出口**：TC-AE-04…06、10、27/28/29 绿；真机识别率达标（安静环境 ≥ 90% 命中；含无系统服务及坏服务国行机型走内置引擎/窗口内回退路径）；FR-7/FR-12 验收。
**收尾（2026-09-18，用户裁决）**：自动化出口全绿（TC-AE-04…06、10、25…29，明细见 `PHASE_5_STEP_1_REPORT.md` §11/§14 与各 commit 门禁记录）；真机 vivo V2436A 多轮**取证性实测**（E3/E4 链路、近音证据链、轻声三窗取证——checklist 2026-09-14…18 记录）；**M1–M4 正式统计延后**：用户裁决当前语音效果自评够用，先交付 MVP，正式统计（≥90% 命中率）留待后续迭代按 checklist 原样补跑、结果不预填。收尾报告 `docs/reports/PHASE_5_REPORT.md`。

## Phase 6 — 勋章

**交付**：`AchievementEngine` + 授予事件链 + 勋章仪式页 + 勋章墙；"有勋章的书禁删"拦截。
**出口**：TC-AC 全绿；FR-13 验收。

## Phase 7 — TXT 导入

**交付**：`EncodingDetector / LineParser / ImportEngine` + 导入 UI（选文件→选本→预览→进度→报告）；事务回滚；大文件流式。
**出口**：TC-IMP-01…08 绿；10 万行 GBK 基准 ≤ 60s 且可取消；FR-14 验收。

## Phase 8 — 设置与打磨

**交付**（v1.9 收敛，MVP 口径）：设置页（FR-15 五项：groupSize / commandWindowMs / 六项播放开关 / TTS 语速 / 音调）——端口写方法 + 设置 UI + 第四 Tab；别名维持代码常量（可配置性再延后，PROJECT_SPEC v1.12）。
**延后**（用户裁决 2026-09-18，与 M1–M4 同批）：i18n 校对；TalkBack 无障碍 pass；NFR-2 性能预算逐项测量（导入性能项已于 Phase 7 实测达标）。
**出口**：FR-15 验收（自动化 TC-UI 设置 + 走查）；NFR-8 权限降级既有口径不变。

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
| 1.2 | 2026-09-03 | Phase 2 标记完成（验收记录见 PHASE_2_REPORT）；Phase 3 范围裁决 = 学习引擎 headless（不接 Dictionary API）；Phase 3 出口统一为 TC-LE-01…11 + TC-DB-07 遗留补齐说明 |
| 1.3 | 2026-09-04 | Phase 3 标记完成（最终验收 PASS，验收记录见 PHASE_3_REPORT）；如实登记偏差：调试界面未交付（headless 测试驱动替代） |
| 1.4 | 2026-09-05 | Phase 4 Step 0 裁决落地（L1–L7，详见 AUDIO_ENGINE_SPEC v1.1 / TEST_PLAN v1.5 变更记录）：Phase 4 交付/出口补齐——CommandWindow P4 纯倒计时边界、正式学习会话屏与 BOOK_DELETED 提示、位置持久化双源优先级、设置端口加法扩展、空段/开关粒度语义；出口全集更新为 TC-AE-01…03、07…19 |
| 1.5 | 2026-09-14 | **Phase 5 收尾范围修订（用户需求变更 + 裁决 E1/E2）：语音命令须支持中国大陆发售的所有安卓手机**——新增内置离线识别引擎（Vosk + 中文小模型打包 APK）兜底 actual 与引擎选择规则（系统服务在场优先零变化）；出口 +TC-AE-27、真机验收覆盖无系统服务国行机型（vivo V2436A 代表）。详见 PROJECT_SPEC v1.5 / AUDIO_ENGINE_SPEC v1.3 / TEST_PLAN v2.0 |
| 1.6 | 2026-09-14 | **裁决 E3（窗口内引擎回退）**：vivo 真机诊断——蓝心 Copilot 服务注册 `RecognitionService`（E2 探测被骗返回 true）但绑定即硬失败 = 「语音命令不可用」根因；Phase 5 收尾增交付引擎回退代理（TC-AE-28），出口 +28、真机覆盖坏服务机型。详见 PROJECT_SPEC v1.6 / AUDIO_ENGINE_SPEC v1.4 / TEST_PLAN v2.1 |
| 1.7 | 2026-09-14 | **裁决 E4（系统引擎响应看门狗）**：E3 后真机复测暴露坏服务静默死法（零回调零错误→窗口超时，用户说「会了」被吞）；系统 actual 增响应看门狗（1500ms 零回调判死→E3 同窗回退，GMS 零误触），出口 +TC-AE-29。详见 PROJECT_SPEC v1.7 / AUDIO_ENGINE_SPEC v1.5 / TEST_PLAN v2.2 |
| 1.8 | 2026-09-18 | **Phase 4/5 标记完成 + M1–M4 延后（用户裁决）**：Phase 4 Step 0–4 分步验收后并入 Phase 5 checkpoint（`7ccb04c`）；Phase 5 以 MVP 口径收尾（自动化出口全绿 + vivo 取证性实测；真机 M1–M4 正式统计延后至后续迭代——用户裁决语音效果自评够用，先交付 MVP；补跑按 checklist 原样执行、结果不预填）。收尾报告 `docs/reports/PHASE_5_REPORT.md`；TEST_PLAN v2.11 §7.1 同步注记 |
| 1.9 | 2026-09-18 | **Phase 8 范围收敛（用户裁决）**：交付收敛为设置页单项（FR-15 五项——端口写方法 + 设置 UI + 第四 Tab）；「会了」别名可配置性再延后（PROJECT_SPEC v1.12）；i18n 校对 / TalkBack / NFR-2 逐项测量延后至 MVP 后打磨批（与 M1–M4 同口径）。协议文档：docs/agent-sync/ `*_PHASE8.md` |
