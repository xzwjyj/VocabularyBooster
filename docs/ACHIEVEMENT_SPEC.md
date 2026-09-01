# ACHIEVEMENT_SPEC — 勋章系统规格

> 状态：Phase 0 定稿 ｜ 版本 1.0 ｜ 日期：2026-09-01
> 上游：PROJECT_SPEC FR-13 ｜ 模型：DOMAIN_MODEL §2.10、§9 事件 ｜ 存储：DATABASE_SCHEMA §2.10
> 位置：`shared/achievement/`，纯 Kotlin（事件驱动，无平台依赖）。

---

## 1. 范围

| 版本 | 勋章类型 |
|---|---|
| **v1 实现** | `BOOK_COMPLETED`——生词本完成勋章 |
| 预留（枚举与事件已定义，不实现） | `STREAK_DAYS`（连续学习天数）、`TOTAL_WORDS_MASTERED`（累计掌握词数）、`GROUPS_COMPLETED`（完成组数）、`BOOKS_COMPLETED`（完成生词本数） |

## 2. 触发与授予流程（v1）

```
CompletionDetector 发出 WordBookCompleted(wordBookId, bookName, wordCount)
        │
        ▼
AchievementEngine.onEvent:
  1. 防御性复核：countUnmastered(wordBookId) == 0（不信事件、信数据）；
     不为 0 → 记日志丢弃（事件与数据不一致是上游 bug，绝不误发勋章）
  2. 幂等授予（单条 SQL，DATABASE_SCHEMA §4）：
     INSERT OR IGNORE INTO Achievement(type='BOOK_COMPLETED', wordBookId,
        payloadJson = {bookName, wordCount, finishedAt}, earnedAt=now)
     —— 唯一索引 (type, wordBookId) 兜底：重复事件 / 并发授予都不可能产生第二条
  3. 发出 AchievementUnlocked(achievementId, type, wordBookId)
        │
        ▼
UI：全屏勋章仪式（必须发生在播放已停止之后——顺序由学习引擎保证，FR-8）
```

- `payloadJson` 留存 **bookName / wordCount / finishedAt 快照**：此后生词本改名、派生、删除均不影响已获勋章的真实性。

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

## 6. 测试要点（详见 TEST_PLAN TC-AC-01…05）

- 幂等：同一 `WordBookCompleted` 事件重放 N 次 → 恰一枚；
- 防御复核：伪造"未完成却发事件" → 不授予；
- 快照不可变：授予后改名/派生 → payload 不变；
- 删除拦截：有勋章的书调用删除 → 拒绝；
- 授予顺序：必须发生在会话 COMPLETED 与播放停止之后（集成测试断言事件顺序）。

---

| 版本 | 日期 | 变更 |
|---|---|---|
| 1.0 | 2026-09-01 | Phase 0 初版 |
