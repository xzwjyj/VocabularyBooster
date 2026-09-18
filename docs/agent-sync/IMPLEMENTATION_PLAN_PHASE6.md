# 实现计划：Phase 6 勋章系统

> 关联：SPEC_CHANGE_REQUEST_PHASE6.md / AUDIT_REPORT_PHASE6.md | 状态：Draft — 待用户批准 | 日期：2026-09-18

## 设计约束

1. **业务规则只在 shared**（架构铁律 2）：授予/复核/幂等全部 `shared/achievement`；app 仅渲染与文案。
2. **TC-AC-05 顺序锚点**：`WordBookCompleted` 发布必须在 `stopPorts()` 之后（编排器两锚点：`finishAsCompleted()` / `exit()` 分支 C）。
3. **零迁移**：不新增/修改任何 DDL；`.sq` 仅在需要时加 query-only（书名查询若缺）。
4. **失败隔离**：授予路径异常 → LogSink 记录丢弃；事件发布永不阻断播放推进。
5. **幂等三态**：防御复核不过 → 丢弃；已授予 → no-op 零事件；首次 → 授予 + `AchievementUnlocked`。

## 事件流（as-built 目标）

```
advance 完成 / exit 分支 C
  → 编排器 stopPorts() 之后 publish WordBookCompleted(sessionId, wordBookId)
  → AchievementEngine（独立 scope 订阅 DomainEventBus）
      1. 复核：session.status == COMPLETED && isSessionComplete(words)（不信事件信数据）
      2. 快照：bookName（现名）/ wordCount=快照词数 / finishedAt=endedAt
      3. grantBookCompleted：selectByTypeAndBook 预检 + INSERT OR IGNORE（唯一索引兜底）
      4. 首次授予 → publish AchievementUnlocked(achievementId, type, wordBookId)
```

## 实施步骤（单批次，Step 内序贯）

| # | 内容 | 文件 | 备注 |
|---|---|---|---|
| 1 | 事件基建 | `shared/domain/event/DomainEvent.kt`、`DomainEventBus.kt`（端口 + DefaultDomainEventBus） | sealed + @Serializable（§9 口径）；explicitApi 显式 public |
| 2 | ExitResult 加法 | `learning/LearningEngine.kt`（+`sessionCompleted: Boolean = false`）、`DefaultLearningEngine.exitSessionLocked`（分支 C 置 true） | 默认值保源兼容；LE spec §11 同步注记 |
| 3 | 编排器接线 | `playback/PlaybackOrchestrator.kt`（构造 +`eventBus`；`finishAsCompleted`/`exit` 两锚点发布） | 发布在 stopPorts 之后、置终态之后；sessionId null 防御跳过 |
| 4 | 仓储 + 领域模型 | `domain/repository/AchievementRepository.kt`（grantBookCompleted / getAchievements / getBookCompletedFor）、`data/SqlDelightAchievementRepository.kt`（payloadJson kotlinx-serialization）、Mappers | 预检 + OR IGNORE 单事务；如缺书名查询则 WordBook.sq +query-only selectWordBookById |
| 5 | 引擎 | `achievement/AchievementEngine.kt`（start(scope) 订阅；复核/快照/授予/事件；LogSink 隔离） | CompletionDetector 复用（完成判据与引擎同源） |
| 6 | DI | `LearningModule`（bus/AchievementEngine）、`DataModule`（repository）、`AppModule`（orchestrator +eventBus；Application.onCreate eager 启动 AchievementEngine） | 订阅 scope = app 级 SupervisorJob scope |
| 7 | UI：勋章墙 | `MainActivity`（第 3 tab 勋章 EmojiEvents）、`AchievementsScreen.kt` + VM（列表：名称/词数/日期，空态文案） | selectAllAchievements 现成 |
| 8 | UI：仪式页 | `LearningSessionViewModel`（Completed 时查 getBookCompletedFor → Completed 状态携带书名/词数/日期）、`LearningSessionScreen` Completed 分支升级（🏆 + 书名 + N 词 + 日期 + 返回按钮保留） | 无对话框/自动跳转（现状保留） |
| 9 | UI：删除文案 | `AppViewModels.WordBooksViewModel.deleteBook`：`WordBookDeletionException` → 按 Reason 中文文案（勋章本不可删 / 先删派生本） | 其余 launchAction 调用点不动 |
| 10 | 规格 | ACHIEVEMENT_SPEC v1.1、LEARNING_ENGINE_SPEC §11 注记 + 版本、DOMAIN_MODEL §9 落地形态 + 版本、TEST_PLAN TC-AC 交付标注 + 版本 | PROJECT_SPEC 零改动（FR-13 语义不变） |

## 测试计划（jvmTest 为主，TC-AC-01…05 + 新增）

| 用例 | 断言 | 层 |
|---|---|---|
| TC-AC-01 | 完成事件重放 N 次 / 同本重学再完成 → 恰一行；`AchievementUnlocked` 恰一次 | jvmTest 集成（真实 JDBC + 默认总线） |
| TC-AC-02 | 会话 ACTIVE / 快照有未掌握词 → 零授予（LogSink 丢弃） | 同上 |
| TC-AC-03 | 授予后改书名/派生 → payloadJson 快照不变 | 同上 |
| TC-AC-04 | 真实授予后 `deleteWordBook` 抛 `BOOK_HAS_COMPLETION_MEDAL`（引擎级闭环，repo 级已有） | 同上 |
| TC-AC-05 | 编排器推进至完成：端口 stop 记录 **先于** 事件与授予记录（共享时序日志断言）；exit 分支 C 同断言 | jvmTest 编排器（Fake 端口 + 记录型总线订阅者） |
| 新增 | `ExitResult.sessionCompleted`：分支 C=true / A/B/终态幂等=false | 引擎既有测试扩展 |
| 新增 | 勋章墙 VM 列表映射 + 删除拒绝两 Reason 文案 | app 单测 |

门禁：jvmTest / shared+app 单测 / 双 detekt / checkPlatformBoundaries / assembleDebug / `git diff --check` 全绿；schema 恒 v2（迁移目录不变）。

## 涉及文件清单

**新建**（shared）：`domain/event/DomainEvent.kt`、`domain/event/DomainEventBus.kt`、`domain/repository/AchievementRepository.kt`、`data/SqlDelightAchievementRepository.kt`、`achievement/AchievementEngine.kt`
**修改**（shared）：`learning/LearningEngine.kt`、`learning/DefaultLearningEngine.kt`、`playback/PlaybackOrchestrator.kt`、`di/LearningModule.kt`、`di/DataModule.kt`、`data/Mappers.kt`（+Achievement 映射）
**修改**（app）：`MainActivity.kt`、`ui/AchievementsScreen.kt`（新）、`ui/AppViewModels.kt`、`ui/LearningSessionScreen.kt`、`ui/LearningSessionViewModel.kt`、`di/AppModule.kt`、`VocabularyBoosterApp.kt`
**测试**：jvmTest 新 `AchievementEngineTest` + 编排器事件顺序用例 + 引擎 ExitResult 扩展；app 单测扩展
**文档**：ACHIEVEMENT_SPEC / LEARNING_ENGINE_SPEC / DOMAIN_MODEL / TEST_PLAN

## 风险与缓解

| 风险 | 缓解 |
|---|---|
| 事件发布协程被慢消费者反压阻塞播放推进 | 总线 extraBufferCapacity=64 + 授予独立 scope；发布点仅书完成（极低频） |
| 终态会话 resume → `finishAsCompleted` 重复发布 | 防御复核 + 预检 + 唯一索引三层幂等（审计 §D） |
| UI 层误植业务规则 | 授予路径全在 shared；UI 仅查询展示 |

## 验收流程

全门禁绿 → 装机（vivo，`pm grant` 补授权）→ 用户真机走查：整本学完 → 停播 → 仪式页（书名/词数/日期）→ 勋章墙可见 → 删该本被拒（中文文案）→ 用户确认 → commit（`feat(achievements)`）→ DECISION_LOG 登记。
