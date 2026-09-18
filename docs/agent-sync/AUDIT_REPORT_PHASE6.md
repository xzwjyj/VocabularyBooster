# 审计报告：Phase 6 勋章接线点核查

> 范围：事件链 / Achievement DB 层 / 删书守卫 / UI 挂载点 / DI / 测试基建 | 日期：2026-09-18
> 方法：全库代码走查（探索代理 48 处核查）+ 关键路径人工复读（DefaultLearningEngine / PlaybackOrchestrator）

## 执行摘要

DB 层与删书守卫**自 schema v1 全部在位且有测试**，零迁移。缺口 = 事件机制（零实现）、引擎/仓储/UI（零实现）、以及 ACHIEVEMENT_SPEC §2 防御复核与 ADR-002 的**规格冲突**。

## A. 现状盘点

| 项 | 现状 | 结论 |
|---|---|---|
| Achievement 表/唯一索引/INSERT OR IGNORE | `Achievement.sq`：表 + `Achievement_unique(type, wordBookId)` + `insertAchievement`（OR IGNORE）+ `selectByTypeAndBook` + `selectAllAchievements`（earnedAt DESC） | ✅ 全在位，勋章墙查询现成 |
| 删书双守卫 | `SqlDelightWordBookRepository.deleteWordBook`：勋章守卫在派生守卫**之前**，`WordBookDeletionException.Reason` 两枚举齐备；`WordBookRepositoryTest.deleteGuardsRejectMedalAndDerivedParent` 已测（raw 插勋章行） | ✅ shared 侧零改动（仅 UI 文案） |
| 领域事件 | **不存在**：无事件类/无总线（commonMain 唯一 SharedFlow 用途 = 播放状态）；`WordBookCompleted`/`AchievementUnlocked` 仅存在于文档 | ❌ 需新建 |
| `shared/achievement/` | 空包（仅 .gitkeep） | ❌ 需新建 |
| CompletionDetector | 纯判断零副作用；`isSessionComplete` 引擎在用 | ✅ 防御复核可直接复用 |

## B. 完成语义核查（ADR-002 后）

`DefaultLearningEngine`（2026-09-15 注释在案）：

- **advance 路径**（`advanceLocked` → `completeSessionLocked`）：快照全掌握 → `updateSessionStatus(COMPLETED)` → 返回 `BookComplete`。**BookComplete ⟺ 会话已 COMPLETED**（旧 KDoc「Q3 书级裁决/中途新增词保持 ACTIVE」已过时——中途加词永不入会话快照）。
- **exit 路径**（`exitSessionLocked`）：快照全掌握 → COMPLETED（分支 C）；否则 ABANDONED + 派生。`ExitResult` 不携带完成标志 → **分支 C 在调用方不可辨识**。
- **顺序事实**：引擎置 COMPLETED **先于**编排器停端口 → 引擎直接发布会使勋章先于停播（违反 TC-AC-05/FR-8）→ **发布点必须落在编排器**（两处锚点均在 `stopPorts()` 之后：`finishAsCompleted()`；`exit()` 于 `engine.exitSession` 之后）。

## C. 规格冲突（必须修正）

| 项 | ACHIEVEMENT_SPEC v1.0 原文 | ADR-002 后现实 | 处置 |
|---|---|---|---|
| 防御复核 | `countUnmastered(wordBookId) == 0`（书级 Q3） | 会话完成 ≠ 书 Q3==0（中途加词不入快照，会话完成时书可含未掌握新增词） | §2 改会话快照复核（status==COMPLETED && isSessionComplete），v1.1 |
| payload wordCount | 「wordCount」未定义口径 | 快照词数 ≠ 书词条数 | v1.1 明确 = 会话快照词数 |
| `.sq countUnmastered` | — | 已标注「已废弃，新逻辑不应依赖」 | 防御复核不触碰该查询 |

## D. UI 挂载点

- 导航：`MainActivity.VocabularyBoosterRoot` 纯状态切换（tab + 三个 overlay 变量），底部栏现 2 tab → **勋章 = 第 3 个 NavigationBarItem**（无导航库负担）。
- 仪式：`LearningSessionScreen` Completed 分支现为静态文案 + 返回按钮（无对话框/自动跳转）→ 升级为仪式呈现（VM Completed 时查 `getBookCompletedFor(bookId)` 填充书名/词数/日期）。
- 删除拒绝：`WordBooksViewModel.launchAction` 现展示**英文原始异常串**（`"WordBook 3 cannot be deleted: HAS_DERIVED_CHILDREN"`）→ 按 `Reason` 分支中文文案。
- 幂等重入：`exit()` 对 Completed/Stopped 短路（`PlaybackOrchestrator.kt:215`）；`beginFromSnapshot` 对终态会话 resume 也走 `finishAsCompleted` → 事件可能重复发布 → 防御复核 + INSERT OR IGNORE 天然幂等（无需去重器）。

## E. DI / 测试基建

- DI：`sharedLearningModule`（engine/deriver 注册处）+ `sharedDataModule`（仓储注册处）+ `appModule`（orchestrator 单例 + VM）；`Application.onCreate` 已有 eager get 先例（orchestrator）→ AchievementEngine 同法启动订阅。
- 测试：jvmTest JDBC（`TestDb.inMemory/file` + `FixedClock` + 事务内 `last_insert_rowid` 陷阱）；编排器测试基建（Fake 端口 + 事件顺序可断言）齐备。

## 结论

DB ✅ 零迁移 ｜ 事件链 ❌ 全新建（本变更主体）｜ 规格 ❌ 防御复核一处必改 ｜ UI 三点小改 ｜ 风险低（纯加法 + 失败隔离）
