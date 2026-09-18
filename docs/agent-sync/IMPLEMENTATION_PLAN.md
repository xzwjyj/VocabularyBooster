# 实现计划：WordBook 学习语义重构

> 关联审计报告：AUDIT_REPORT.md | 状态：Draft | 日期：2026-09-15

## 变更概述

修复 WordBook membership 与 WordMastery 耦合问题，实现"母本永远可学"。

## 设计约束

### 1. 核心概念区分

| 概念 | 定义 | 生命周期 |
|------|------|----------|
| **WordBook membership** | 词是否属于某 WordBook（WordBookEntry 表） | **永久**（除非用户手动删除） |
| **Mastery history** | 用户何时掌握某词（WordMastery 表） | **历史记录**（时间戳） |
| **StudySession snapshot** | 一次学习任务的词集合（SessionWord 表） | **会话级**（创建时快照） |

### 2. Snapshot 约束

- [x] **Snapshot 只保存 wordId 集合**，不复制 Word 内容
- [x] **Snapshot 只冻结 membership**，不冻结 word metadata（释义/例句等可独立更新）

### 3. 生命周期隔离约束

- [x] **原始 WordBook 与 Derived Review Book 必须隔离**
  - 删除/修改派生本不能影响原始词本
  - mastery 状态不能改变 membership
- [x] **LearningEngine.markMastered 不修改 WordBookEntry**

### 4. CompletionDetector 规则

**禁止**：
```
StudyQueue empty = WordBook completed  ❌
```

**必须**：
```
当前 StudySession snapshot consumed = completed  ✅
```

### 5. 验证场景

#### 场景 A：重新学习已掌握词

```
创建词本: A, B, C, D
学习: A, B, C, D 全部 markMastered
退出 → 派生复习本

重新进入原词本:
仍然可以学习 A, B, C, D  ✅
```

#### 场景 B：派生复习本筛选

```
创建派生复习本:
可以根据 WordMastery.masteredAt timestamp 筛选需要复习的词
```

### 6. 实现约束

- [x] **不增加 migration** - Schema 已支持
- [x] **不修改 LearningEngine.markMastered 语义** - 幂等保持
- [x] **不删除 WordBook membership** - WordBookEntry 表不变
- [x] **不引入第二套学习队列** - 复用现有 SessionWord

---

## 技术方案

### 核心改动

1. **Q2 查询**：移除 `wm.wordId IS NULL` 条件
2. **StudyQueueBuilder**：移除 ALL_MASTERED 拒绝
3. **CompletionDetector**：基于 SessionWord snapshot 判断完成

### 数据流变更

```
WordBook (A,B,C,D)
    ↓
SessionWord snapshot (A,B,C,D) ← 学习会话创建时快照
    ↓
markMastered(A), markMastered(B)
    ↓
SessionWord snapshot consumed (A,B mastered, C,D pending)
    ↓
Session completed (snapshot consumed) ✅
```

---

## 实施步骤

| 步骤 | 描述 | 文件 | 约束 |
|------|------|------|------|
| 1 | 修改 Q2 查询，移除 IS NULL 条件 | Queries.sq | 不修改语义 |
| 2 | StudyQueueBuilder 重命名并移除拒绝 | StudyQueueBuilder.kt | 不引入新队列 |
| 3 | DefaultLearningEngine 移除拒绝逻辑 | DefaultLearningEngine.kt | 不修改 markMastered |
| 4 | CompletionDetector 基于 snapshot | CompletionDetector.kt | 用 "snapshot consumed" 规则 |
| 5 | 更新测试用例 | TC-LE-* | - |

---

## 回归测试要求

实现阶段**必须**新增以下回归测试：

### A. mastered word remains in original WordBook

```
1. 创建词本 W，含词 A
2. 学习 A，markMastered(A)
3. 验证：WordBookEntry 中仍有 A 的记录
```

### B. study session completion does not empty WordBook

```
1. 词本含 A,B,C,D
2. 学习会话完成（全部 mastered）
3. 验证：WordBookEntry 仍含 A,B,C,D（不是空本）
```

### C. historical snapshot remains reproducible

```
1. 创建词本 W，含 A,B
2. 学习 A mastered，B pending
3. 派生本含 B
4. 验证：原始词本 W 仍含 A,B（快照可重现）
```

### D. derived review book generation does not mutate source WordBook

```
1. 创建词本 W，含 A,B
2. 生成派生复习本 D（含 A）
3. 验证：W 中 A,B 仍存在，D 是独立副本
4. 删除 D
5. 验证：W 中 A,B 不受影响
```

## 测试计划

- [ ] 单元测试：StudyQueueBuilder
- [ ] 单元测试：CompletionDetector (snapshot consumed)
- [ ] 集成测试：LearningSession 全流程
- [ ] 手动测试：场景 A + 场景 B
- [ ] 回归测试：A, B, C, D

---

## 验收标准

1. ✅ 已掌握词可重新学习（场景 A）
2. ✅ 母本始终可开始学习
3. ✅ 派生本使用快照（场景 B）
4. ✅ CompletionDetector 用 "snapshot consumed" 规则

---

## 涉及文件清单

### 核心文件
- `shared/src/commonMain/sqldelight/com/vocabularybooster/db/Queries.sq` (Q2)
- `shared/src/commonMain/kotlin/com/vocabularybooster/learning/StudyQueueBuilder.kt`
- `shared/src/commonMain/kotlin/com/vocabularybooster/learning/DefaultLearningEngine.kt`
- `shared/src/commonMain/kotlin/com/vocabularybooster/learning/CompletionDetector.kt`

### 测试文件
- `shared/src/jvmTest/kotlin/com/vocabularybooster/learning/StudyQueueBuilderTest.kt`
- `shared/src/jvmTest/kotlin/com/vocabularybooster/learning/CompletionDetectorTest.kt`

### 文档
- `docs/LEARNING_ENGINE_SPEC.md`
- `docs/DATABASE_SCHEMA.md`
- `docs/PROJECT_SPEC.md` (FR-6, FR-9)

---

## 风险分析

| 风险 | 描述 | 缓解 |
|------|------|------|
| 完成检测逻辑变更 | CompletionDetector 从 "empty queue" 改为 "snapshot consumed" | 完整测试覆盖 |
| UI 行为变化 | 已掌握词重新出现 | UI 适配 |
