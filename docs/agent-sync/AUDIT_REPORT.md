# 审计报告：WordBook 学习语义

> 范围：Learning Session / WordMastery / DerivedBook | 日期：2026-09-15

## 执行摘要

Schema 本身完全支持需求。问题在查询逻辑。

---

## A. 当前实现行为图

### A.1 数据模型

```
WordBook (type: ORIGINAL/DERIVED)
    ↓
WordBookEntry (wordId FK, entryOrder)
    ↓
WordMastery (masteredAt) ← 历史记录

LearningSession (status: ACTIVE/COMPLETED/ABANDONED)
    ↓
SessionWord (status: PENDING/PLAYING/MASTERED) ← 会话快照
```

### A.2 当前学习流程

```
1. startSession(wordBookId)
   └─> Q2: SELECT ... WHERE wm.wordId IS NULL  ← 排除已掌握
   
2. 队列 = 未掌握词列表
   └─> 如果空 → ALL_MASTERED 拒绝

3. markMastered()
   └─> INSERT WordMastery (幂等)

4. exitSession()
   └─> deriveWordBook() 使用 SessionWord 快照 ← 正确
```

### A.3 问题代码路径

| 位置 | 代码 | 问题 |
|------|------|------|
| `Queries.sq` Q2 | `wm.wordId IS NULL` | 排除已掌握词 |
| `StudyQueueBuilder.kt` | `unmasteredQueue.isEmpty()` | ALL_MASTERED 拒绝 |
| `DefaultLearningEngine.kt` | `ALL_MASTERED -> Rejected` | 拒绝入口 |

---

## B. 与新需求的差异

| # | 当前行为 | 新需求 | 差异 |
|---|---------|--------|------|
| 1 | Q2 排除已掌握词 | 队列包含所有词 | ❌ |
| 2 | 已掌握词无法学习 | 已掌握词可重新学 | ❌ |
| 3 | 派生本用会话快照 | 派生本用会话快照 | ✅ |
| 4 | ALL_MASTERED 拒绝 | 母本永远可学 | ❌ |

---

## C. 数据库 Migration 评估

**结论：无需 Migration**

Schema 已支持：
- ✅ WordBook membership 永久保存 (`WordBookEntry`)
- ✅ Mastery history (`WordMastery.masteredAt`)
- ✅ Immutable derived snapshot (`type=DERIVED`, 复制时点数据)

---

## D. 最小修改方案

### D.1 Q2 查询 (P0)

```sql
-- 当前：
WHERE wm.wordId IS NULL

-- 改为：
-- (移除此条件，包含所有词)
```

### D.2 StudyQueueBuilder (P0)

- 重命名 `unmasteredQueue` → `studyQueue`
- 移除 `ALL_MASTERED` 拒绝

### D.3 DefaultLearningEngine (P0)

- 移除 `ALL_MASTERED` 拒绝逻辑

### D.4 无需改动

- MasteryMarker (幂等正确)
- WordBookDeriver (使用会话快照正确)
- Schema

---

## E. 涉及文件

| 文件 | 改动 |
|------|------|
| `Queries.sq` (Q2) | 查询修改 |
| `StudyQueueBuilder.kt` | 重构 |
| `DefaultLearningEngine.kt` | 移除拒绝逻辑 |

---

## F. 风险

| 风险 | 描述 |
|------|------|
| 完成检测 | 需区分"之前已掌握"vs"本会话新增" |
| UI 变化 | 已掌握词重新出现需 UI 适配 |

---

## 结论

Schema ✅ | 查询逻辑 ❌ | 需修复 Q2 + ALL_MASTERED 拒绝
