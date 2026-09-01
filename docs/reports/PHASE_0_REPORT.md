# PHASE 0 REPORT — 阶段验证报告

> 日期：2026-09-01 ｜ 结论：**Phase 0（规格与架构）完成，全部验收检查通过；未编写任何业务代码。等待用户批准进入 Phase 1。**

---

## 1. 环境检查结果（2026-09-01，本机 Windows 10 Pro）

| 工具 | 要求 | 状态 |
|---|---|---|
| Git | 2.x | ✅ 2.46.2（user.name=Zack Lee 已配置） |
| JDK | 17+（推荐 21） | ❌ 未安装（PATH 与常见目录均未发现） |
| Kotlin CLI | 可选（Gradle 内置） | ❌ 未安装（非阻塞，Phase 1 由 Gradle/Kotlin 插件提供） |
| Gradle | wrapper 方式 | ❌ 未安装（非阻塞，Phase 1 生成 wrapper） |
| Android SDK | platform 36 / build-tools / platform-tools | ❌ 未安装 |
| Android Studio | 最新稳定版 | ❌ 未安装（`C:\Program Files\Android` 不存在） |
| macOS / Xcode | iOS 构建必需 | ❌ 本机为 Windows（风险 R2） |

**结论**：文档阶段不受影响（已完成）；**Phase 1 的第一件事就是安装 JDK 21 + Android Studio（自带 SDK 管理）**。

## 2. 创建的文件清单

| 文件 | 内容 |
|---|---|
| `CLAUDE.md` | AI 协作工程规范（阶段纪律、架构铁律、编码/测试规范） |
| `readme.md` | 项目总览 + 文档地图 + 环境状态（**用户原始需求笔记已原文保留存档**） |
| `.gitignore` | Gradle/Android/IDE/云盘同步忽略规则 |
| `docs/PROJECT_SPEC.md` | 产品需求规格：16 个 FR + 9 个 NFR + 追踪矩阵（唯一需求事实来源） |
| `docs/DOMAIN_MODEL.md` | 领域模型：4 聚合 10 实体、排序/展示不变量、状态机、领域事件 |
| `docs/DATABASE_SCHEMA.md` | SQLDelight schema：11 表 DDL + 5 关键查询 + 事务规则 + FR 映射检查表 |
| `docs/ARCHITECTURE.md` | KMP 架构：10 项 ADR、模块边界铁律、平台端口接口、iOS 复用策略 |
| `docs/LEARNING_ENGINE_SPEC.md` | 学习引擎：队列/分组/循环/掌握/完成/派生/恢复 + 10 条边界情形 |
| `docs/AUDIO_ENGINE_SPEC.md` | 播放引擎：Segment 模型、编排状态机、命令窗口、平台 actual 契约 |
| `docs/IMPORT_SPEC.md` | TXT 导入：编码检测/行解析/三层去重/事务回滚/性能预算 |
| `docs/ACHIEVEMENT_SPEC.md` | 勋章：幂等授予、快照、未来扩展架构 |
| `docs/TEST_PLAN.md` | 测试策略：TC 用例矩阵（DM/DB/LE/AE/IMP/AC/ARCH/UI）+ FR 追踪 + 覆盖率门 |
| `docs/ROADMAP.md` | Phase 1–9 + iOS + 显式延后项，均含出口条件 |
| `shared/` `app/` `iosApp/` | 模块骨架：README + KMP 包结构占位（.gitkeep，**零业务代码**） |

## 3. 当前项目结构

```
VocabularyBooster/
├── CLAUDE.md / readme.md / .gitignore
├── docs/                       # 全部规格文档
│   └── reports/PHASE_0_REPORT.md
├── shared/                     # KMP 共享模块（占位）
│   ├── README.md
│   └── src/
│       ├── commonMain/kotlin/com/vocabularybooster/
│       │   ├── domain/{model,repository}/   data/{db,repository}/
│       │   ├── learning/  playback/  speech/  achievement/  import/  settings/
│       ├── androidMain/…/platform/          iosMain/…/platform/
│       └── commonTest/kotlin/com/vocabularybooster/
├── app/                        # Android 模块（占位：README）
└── iosApp/                     # iOS 模块（占位：README）
```

## 4. Phase 0 验收检查（用户要求的 6 项）

| # | 检查 | 结果 |
|---|---|---|
| 1 | 项目结构检查 | ✅ 12 文档 + 3 模块骨架 + 13 包占位全部就位；无任何 .kt/.java 源码 |
| 2 | 文档间冲突检查 | ✅ 全库扫描：派生命名格式、guardDelay=300ms / 窗口=4000ms、`includeExamples`/`pendingTranslation`、"原书不动/复用不复制" 在 4–6 份文档中表述一致。发现并已修复 1 处不一致（TEST_PLAN TC-AC 未编号，已对齐 ACHIEVEMENT_SPEC 引用） |
| 3 | 数据库模型需求核对 | ✅ DATABASE_SCHEMA §6 映射表逐条核对 FR-1…FR-15 全覆盖；规格期发现缺口并补齐：`WordBookEntry.pendingTranslation`（TXT 导入译文暂存）。16 项原始需求全部落表 |
| 4 | 跨平台边界检查 | ✅ 平台能力收敛为 5 个端口接口（TTS / 音频 / 识别 / DB 驱动 / 日志）；学习、播放编排、命令解析、导入、勋章全部纯 Kotlin；detekt 边界规则纳入 Phase 1 出口条件 |
| 5 | iOS 是否需要重写核心 | ✅ **零核心重写**。iOS 工作量 = 5 个 actual 类 + SwiftUI UI + Koin module；行为正确性由同一套 commonTest 在 iOS 目标复验（需 macOS，见风险 R2） |
| 6 | Phase 禁令遵守 | ✅ 无业务代码、无 UI 实现、无第三方 API/AI/音频接入、无完整播放器 |

**2026-09-01 决策冻结（D1–D4）后复检**：旧字段名 `derivedFrom` 全库清零；`parentWordBookId`（20 处 / 7 文档）、`sourceSessionId`（19 处 / 7 文档）、"三分支/DERIVED/母本"术语在 PROJECT_SPEC、DOMAIN_MODEL、DATABASE_SCHEMA、LEARNING_ENGINE_SPEC、TEST_PLAN、PHASE_0_REPORT、CLAUDE.md 全部同步一致（grep 验证）。

## 5. 核心架构决策（摘要，详见 ARCHITECTURE ADR 表）

| 决策 | 要点 |
|---|---|
| KMP 单核心 | 全部业务逻辑在 `shared/commonMain`，Android/iOS 只做 UI 与平台装配 |
| SQLDelight | 一套 `.sq` schema 双平台复用，11 表见 DATABASE_SCHEMA |
| 端口注入（弱 expect/actual） | 平台能力=commonMain 接口 + 各平台实现 + Koin 装配 |
| 引擎分离 | 学习引擎（规则裁决）与播放引擎（时序驱动）通过接口协作 |
| 命令窗口式识别 | TTS 期间识别器强制关闭，杜绝自识别（FR-12） |
| Segment 级暂停恢复 | 文件音频精确 offset；TTS 段重读当前段（ADR-09，已接受的平台限制） |
| 掌握按（生词本,词） | `WordMastery` 行存在即掌握；幂等、不可逆 |
| 派生=关系复制（D1/D2/D3 冻结） | 退出三分支（零掌握=不派生不建空本；部分掌握=派生 DERIVED 快照本；全部掌握=COMPLETED+勋章不派生）；ORIGINAL 母本永不被会话修改；DERIVED 本记录 parentWordBookId+sourceSessionId；掌握作用域=（生词本,词）（D4） |
| Long 自增 ID | 无同步需求；引入同步需 ID 策略 RFC |

## 6. 尚未解决的问题（需用户逐条决策）

| # | 问题 | 当前规格默认值 |
|---|---|---|
| **Q1** | ~~退出时若本次零掌握，是否仍创建派生生词本？~~ | ✅ **已冻结（D1）**：不创建、不建空本；保存会话状态与学习历史；母本不变 |
| **Q2** | ~~退出语义歧义~~ | ✅ **已冻结（D2）**：Original WordBook = 永久母本，退出**永不修改**；产生的是 Derived WordBook（未掌握词快照） |
| **Q3** | ~~掌握作用域与继承~~ | ✅ **已冻结（D4）**：MASTERED 属于（WordBook, Word）——TOEFL→MASTERED 与 GRE→NOT_MASTERED 互不影响；派生本不继承母本状态 |
| **Q4** | Android SpeechRecognizer 部分厂商走云端识别（隐私）：是否接受并在隐私声明披露？规格已要求优先 `EXTRA_PREFER_OFFLINE` | 接受 + 披露 |
| **Q5** | GB18030 流式解码依赖平台原生编码器（JVM/iOS 行为差异） | Phase 7 前 PoC 验证；检测逻辑已为共享纯 Kotlin |
| **Q6** | ID 策略：Long 自增 vs UUID | Long；触发条件=引入云同步 |
| **Q7** | 例句音频格式选型（aac/mp3 双平台支持矩阵） | Phase 4（播放引擎）前定 |
| **Q8** | minSdk 26 是否需要更低（技术上 21+ 可行） | 26 |
| **Q9** | "会了"误触（环境人声/电视）风险：命令窗口短 + 精确匹配已降低风险 | v1 接受；v2 可加二次确认选项 |

## 7. 风险登记

| # | 风险 | 影响 | 缓解 |
|---|---|---|---|
| R1 | **开发环境完全未安装**（JDK/AS/SDK） | 阻塞 Phase 1 | Phase 1 第一步：Adoptium JDK 21 + Android Studio |
| R2 | **无 macOS** | 阻塞 iOS 阶段（不影响 Android 与共享模块开发） | iOS 阶段前备 Mac 或 CI macOS runner；Windows 可完成全部核心开发 |
| R3 | 仓库在 **Synology Drive 同步目录** | Gradle 构建（.gradle/build 大量小文件）可能文件锁/同步冲突 | 同步客户端排除 `build/`、`.gradle/`；必要时迁出同步目录 |
| R4 | Android **TTS 碎片化**（厂商引擎质量参差） | 双语切换/发音质量 | 真机矩阵尽早（Phase 4）；语速可调；缺失引导安装 |
| R5 | **SpeechRecognizer 中文"会了"识别率**因厂商差异 | 语音核心体验 | 别名扩展 + 手动"会了"按钮兜底（NFR-8）；Phase 5 真机达标门禁 |
| R6 | 外部词典 API 延后 → **种子词库需人工整理** | 内容生产成本 | Phase 2 先建 50 词高质量样例库；API 接入已预留端口 |
| R7 | 未来音频素材**授权合规** | 法务风险 | `sourceType/sourceRef/licenseNote` 字段已强制；接入前过合规评审 |
| R8 | Windows CI 无快速 Android 模拟器路径 | UI 自动化受限 | 核心回归全在 JVM（设计已保证）；UI 测试本地跑 |

## 决策记录（Phase 0 Review，2026-09-01 冻结）

| # | 决策 | 内容摘要 |
|---|---|---|
| **D1** | 退出三分支 | MASTERED=0 → 保存 Session 状态/学习历史，不创建派生本、不建空本，母本不变；MASTERED>0 且 REMAINING>0 → 创建 Derived WordBook；MASTERED=ALL → Session 标记 COMPLETED、停止播放、解锁勋章，不创建派生本 |
| **D2** | 母本不变性 | Original WordBook **永不**因学习会话退出而改变；退出产生 Derived WordBook（某次会话的未掌握词快照），而非修改 Original |
| **D3** | WordBook 类型化 | `type ∈ {ORIGINAL, DERIVED}`；Derived 记录 `parentWordBookId + sourceSessionId + createdAt`；命名 `{originalName} yyyy-MM-dd HH:mm`；只复制关系行（WordBook / WordBookEntry / 释义与例句选择），复用 Word / DefinitionEntry / Example，不复制底层数据 |
| **D4** | 掌握作用域 | MASTERED 属于（WordBook + Word）二元组，不属于全局 Word；派生本不继承母本掌握状态 |

---

## 8. 下一阶段建议（Phase 1 —— 已批准，进行中）

**Phase 1 已获用户批准**（2026-09-01，决策 D1–D4 冻结后启动）。

1. 安装 JDK 21（Adoptium）→ Android Studio → SDK 36 组件；
2. `git init` + 首次提交（规格文档入库）+ SynologyDrive 排除配置（R3）；
3. Gradle KMP 工程引导（version catalog 锁版本矩阵）+ detekt 边界规则；
4. SQLDelight schema v1 全部 11 表 + 迁移基线测试；
5. Compose 空壳 App 装机运行 + 端口接口编译验证；
6. 出口验收见 ROADMAP Phase 1。

> **未执行 `git init`**（Phase 0 范围未包含建库，且初始化属 Phase 1 计划）——如你希望现在就纳入版本管理，我可以立即执行。

---

| 版本 | 日期 | 备注 |
|---|---|---|
| 1.0 | 2026-09-01 | Phase 0 验收报告 |
| 1.1 | 2026-09-01 | 冻结决策 D1–D4 落档；开放问题 Q1–Q3 关闭；Phase 1 获批进行中 |
