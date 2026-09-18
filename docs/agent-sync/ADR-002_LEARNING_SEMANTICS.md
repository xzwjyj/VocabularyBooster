# ADR-002: 学习语义重构 - WordBook 与 WordMastery 解耦

> 状态：Draft | 日期：2026-09-15

## 背景

当前实现将 WordBook membership 与 WordMastery status 耦合，导致以下问题：
- 已掌握的词无法重新学习
- 派生本动态计算而非快照

## 决策

### 新语义

1. **WordBook membership ≠ WordMastery status**
   - 词是否属于某 WordBook：仅由 WordBookEntry 决定
   - 用户是否曾掌握该词：由 WordMastery 记录

2. **母本学习规则**
   - 任何存在于 WordBook 中的词都可学习
   - 无论之前是否已掌握

3. **派生本规则**
   - 派生本 = 创建时点的 immutable snapshot
   - 不随母本 mastery 状态变化

4. **WordMastery 职责**
   - 影响：派生生成规则、复习策略、统计
   - 不影响：学习入口、可学习性

## 影响

### 需要修改

- Queries.sq: Q2 查询
- StudyQueueBuilder
- CompletionDetector
- WordBookDeriver
- DefaultLearningEngine

### 不需要修改

- Database Schema（已支持）
- MasteryMarker（幂等性已正确）

## 状态

- [x] 审计报告完成 (2026-09-15)
- [x] 实现计划完成 (2026-09-15)
- [ ] 用户批准
- [ ] 实现
- [ ] 测试
- [ ] 验收
