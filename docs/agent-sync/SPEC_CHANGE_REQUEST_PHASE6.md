# 规格变更请求：Phase 6 勋章系统（含 ACHIEVEMENT_SPEC 防御复核修正）

> 状态：Draft — 待用户批准 | 日期：2026-09-18 | 关联：ROADMAP Phase 6（用户已批准进入 MVP 后续开发）

## 变更概述

**类型**：
- [x] 新功能（Phase 6 交付：AchievementEngine + 事件链 + 勋章墙 + 仪式页）
- [x] 需求修正（ACHIEVEMENT_SPEC §2 防御复核与 ADR-002 对齐）
- [ ] 架构调整
- [ ] Bug 修复
- [ ] 其他

**影响范围**：shared（domain 事件 / achievement / learning / playback / data / DI）+ app（UI 三处）+ 四份规格文档

## 变更原因

### 背景

Phase 6（勋章）为 ROADMAP 既有阶段，用户 2026-09-18 裁决 MVP 先行，Phase 6 为 MVP 第一步。ACHIEVEMENT_SPEC v1.0（2026-09-01）定稿于 ADR-002（2026-09-15，`924fdc6` 已落地）之前。

### 问题描述

1. **规格过时（必须修）**：ACHIEVEMENT_SPEC §2 防御复核要求 `countUnmastered(wordBookId) == 0`（书级 Q3）。ADR-002 后会话完成判据 = **会话快照全掌握**（`DefaultLearningEngine.completeSessionLocked`/`exitSessionLocked`，2026-09-15 注释在案）：会话完成时书可含中途新增的未掌握词 → 按旧文复核会**误丢合法勋章**。
2. **事件机制不存在**：DOMAIN_MODEL §9 规划的领域事件（WordBookCompleted → AchievementEngine → AchievementUnlocked）零实现（无事件总线、无事件类，完成仅以 `AdvanceResult.BookComplete` 返回值传播）。
3. **退出分支 C 不可辨识**：`ExitResult` 只带 `derivedWordBookId`，分支 C（退出瞬间全掌握 → COMPLETED + 勋章）与分支 A/终态幂等返回值不可区分，编排器无从发布完成事件。

## 变更内容

### 需求变更（ACHIEVEMENT_SPEC v1.1）

1. **防御复核改会话快照**：`getSessionWithWords(sessionId)` → `session.status == COMPLETED && CompletionDetector.isSessionComplete(words)`（与引擎完成判据同源，「不信事件、信数据」语义不变）。
2. **触发点定版（TC-AC-05 顺序锚点）**：编排器在**停止播放端口之后**发布 `WordBookCompleted(sessionId, wordBookId)`——① `finishAsCompleted()`（advance 完成路径）；② `exit()` 且 `ExitResult.sessionCompleted == true`（退出分支 C）。引擎自身不在 COMPLETED 落库时发布（否则勋章先于停播，违反 FR-8 顺序）。
3. **payload 语义**：`{bookName(授予时点快照), wordCount(=会话快照词数), finishedAt(=session.endedAt)}`——wordCount 在「母本永远可学 + 中途加词」语义下 = 本次完成所掌握的会话词数。
4. **幂等与重学**：重学同本再次完成 → `INSERT OR IGNORE`（唯一索引 (type, wordBookId)）零新行、零 `AchievementUnlocked` 重复发布。
5. **失败隔离**：授予路径任何异常 → LogSink 记录丢弃，绝不阻断学习/播放。
6. **加法 API**：`ExitResult` + `sessionCompleted: Boolean = false`（分支 C = true）。

### 事件机制（DOMAIN_MODEL §9 落地形态）

- `shared/domain/event/`：`DomainEvent`（sealed，@Serializable：WordBookCompleted / AchievementUnlocked）+ `DomainEventBus` 端口（`events: SharedFlow` + `suspend publish`）+ `DefaultDomainEventBus`（进程内 MutableSharedFlow，extraBufferCapacity=64）。纯 Kotlin 零平台依赖。

### 影响分析

- 受影响的 FR：FR-13（实现交付；语义不变，运营判据细化）、FR-8（顺序锚点落位）、FR-4（删书拦截 UI 文案落地，shared 守卫已存在）
- 受影响的规格文档：ACHIEVEMENT_SPEC（v1.1）、LEARNING_ENGINE_SPEC（§11 ExitResult 注记）、DOMAIN_MODEL（§9 落地形态）、TEST_PLAN（TC-AC-01…05 交付标注 + 新用例）
- 受影响的代码模块：shared/domain/event（新）、shared/achievement（新）、LearningEngine/ExitResult（+字段）、PlaybackOrchestrator（+eventBus 参数与两处发布）、data（AchievementRepository + DI）、app（勋章 tab + 仪式页 + 删除文案）
- **数据库：零迁移**（Achievement 表、唯一索引、INSERT OR IGNORE、勋章墙查询、两个删书守卫全部自 schema v1 在位且有测试）

## 风险评估

- [x] 低风险

**说明**：纯加法（新包/新端口/新字段带默认值）；发布点在既有 stopPorts 之后不改变播放语义；授予失败隔离；DB 零迁移。唯一行为变化 = 完成时多一次事件发布与一行插入。

## 验收标准

1. TC-AC-01…05 全绿（幂等/防御复核/快照不可变/删除拦截/授予顺序）+ 事件顺序断言（停播 → 事件 → 授予）；
2. 真机：整本学完 → 停播 → 全屏仪式（书名/词数/日期）→ 勋章墙可见 → 该本删除被拒（中文文案）；
3. 全门禁绿（jvmTest / 单测 / detekt / 边界 / assembleDebug / git diff --check）。

## 附件

- [x] 审计报告（AUDIT_REPORT_PHASE6.md — 探索代理全量接线点核查）
- [x] 实现计划（IMPLEMENTATION_PLAN_PHASE6.md）
