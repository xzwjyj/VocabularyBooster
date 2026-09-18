# ACHIEVEMENT_SPEC — 勋章系统规格

> 状态：Phase 6 落地 ｜ 版本 1.1 ｜ 日期：2026-09-18
> 上游：PROJECT_SPEC FR-13 ｜ 模型：DOMAIN_MODEL §2.10、§9 事件 ｜ 存储：DATABASE_SCHEMA §2.10
> 位置：`shared/achievement/`，纯 Kotlin（事件驱动，无平台依赖）。

---

## 1. 范围

| 版本 | 勋章类型 |
|---|---|
| **v1 实现** | `BOOK_COMPLETED`——生词本完成勋章 |
| 预留（枚举与事件已定义，不实现） | `STREAK_DAYS`（连续学习天数）、`TOTAL_WORDS_MASTERED`（累计掌握词数）、`GROUPS_COMPLETED`（完成组数）、`BOOKS_COMPLETED`（完成生词本数） |

## 2. 触发与授予流程（v1.1 落地形态）

```
PlaybackOrchestrator（两个锚点，均在 stopPorts() 之后发布）:
  · advance 路径：finishAsCompleted()（BookComplete，状态置 Completed 后）
  · exit 分支 C：exit() 且 ExitResult.sessionCompleted == true（NonCancellable 发布——
    COMPLETED 会话不可 resume，事件丢失无自愈机会，必须越过取消）
        │  DomainEventBus.publish(WordBookCompleted(sessionId, wordBookId))
        ▼
AchievementEngine（订阅方，应用级 scope 常驻；授予失败只丢弃留痕，绝不影响学习/播放）:
  1. 防御性复核（不信事件、信数据，ADR-002 会话快照口径）：
     会话存在且 status == COMPLETED 且 CompletionDetector.isSessionComplete(snapshot)
     （快照全掌握；空快照不构成完成）；不通过 → 记日志丢弃（事件与数据不一致是上游 bug，绝不误发勋章）
  2. 快照装配：bookName = 生词本现名（getWordBookName）；
     wordCount = 会话快照词数（非书级词条数）；finishedAt = session.endedAt
  3. 幂等授予（仓储单事务三层兜底，DATABASE_SCHEMA §4）：
     事务内先查 → INSERT OR IGNORE INTO Achievement(type='BOOK_COMPLETED', wordBookId,
        payloadJson = {bookName, wordCount, finishedAt}, earnedAt = finishedAt)
     —— 唯一索引 (type, wordBookId) 兜底：重复事件 / 并发授予都不可能产生第二条；
     earnedAt 取会话完成时刻（确定性，可重放），非授予时刻
  4. 仅首次授予（firstGrant）→ publish(AchievementUnlocked(achievementId, type, wordBookId))
     —— 已授予重发（终态 resume 重入 / 同本重学再完成）= 零事件
        │
        ▼
UI：学习完成仪式页（Completed 态）呈现勋章快照（书名/词数/日期）；
    首查可能早于异步授予落库 → VM 同时订阅 AchievementUnlocked 兜住竞态后补填；
    事件在端口 stop 之后才发布（顺序由编排器锚点保证，FR-8，TC-AC-05）
```

- `payloadJson` 留存 **bookName / wordCount / finishedAt 快照**：此后生词本改名、派生、删除均不影响已获勋章的真实性。
- 事件与 UI 解耦：`AchievementUnlocked.type` 为 String（事件层不绑枚举，扩展新类型不改事件契约）。

## 3. 展示规则

- 授予瞬间：全屏 ceremony（勋章图形 + 生词本名 + 词数 + 日期）；
- 常驻入口：勋章墙列表（按 `earnedAt` 倒序，全部类型统一渲染）；
- 授予后 App 内**任何界面不提供删除入口**。

## 4. 永久性与数据约束

| 规则 | 依据 |
|---|---|
| Achievement 行**永不删除** | FR-13 |
| 已获 `BOOK_COMPLETED` 勋章的生词本**禁止删除** | FR-4 限制（UI 拦截 + DOMAIN_MODEL §7） |
| 生词本重命名不影响勋章 | 快照在 `payloadJson` |
| 同一生词本至多一枚完成勋章 | 唯一索引 |

## 5. 未来扩展架构（事件 → 条件 → 授予）

预留统一管线，扩展时**只加条件类，不改管线**：

```
领域事件（WordMasterConfirmed / GroupCompleted / WordBookCompleted / SessionStarted）
        ▼
AchievementEngine（订阅 EventBus，ARCHITECTURE 数据流）
  ├─ ConditionDefinition：type + 判定器（纯函数：事件累积进度 → Boolean）
  │    例：TOTAL_WORDS_MASTERED 阈值 100/500/1000；STREAK_DAYS 按天聚合"有掌握行为"
  ├─ 进度存储：预留 `AchievementProgress` 表（Phase 决策：届时增补迁移）
  └─ 授予路径复用 v1 的幂等 INSERT OR IGNORE
```

- 事件契约已在 DOMAIN_MODEL §9 定义，新增勋章类型 = 新增 `AchievementType` 枚举 + 新条件类 + 迁移文件；
- 「连续学习天数」的"学习"定义：当日至少 1 次 `WordMasterConfirmed`（v1 预约定，未来可改设置）。

## 6. 测试要点（详见 TEST_PLAN TC-AC-01…05，Phase 6 已全部落地）

- 幂等：同一 `WordBookCompleted` 事件重放 N 次 → 恰一枚；同本重学再完成 → 仍恰一枚、`AchievementUnlocked` 恰一次；
- 防御复核：ACTIVE 会话 / 伪 COMPLETED 带未掌握词 / 未知会话 → 零授予；
- 快照不可变：授予后改名/加词 → payload 不变；
- 删除拦截：真实授予落行后调用删除 → `BOOK_HAS_COMPLETION_MEDAL` 拒绝；
- 授予顺序：编排器两条完成路径（advance 自然完成 / exit 分支 C）事件到达时端口 stop 已发生。

---

| 版本 | 日期 | 变更 |
|---|---|---|
| 1.0 | 2026-09-01 | Phase 0 初版 |
| 1.1 | 2026-09-18 | Phase 6 落地形态：发布方 = 编排器双锚点（stop 后）；事件载荷 (sessionId, wordBookId)；防御复核改 ADR-002 会话快照口径（空快照不授勋）；wordCount = 会话快照词数；earnedAt = finishedAt（确定性）；firstGrant 才发 `AchievementUnlocked`；仪式页 = 完成页勋章快照（事件兜竞态） |
