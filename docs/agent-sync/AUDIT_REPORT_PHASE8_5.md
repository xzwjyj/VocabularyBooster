# 审计报告 — Phase 8.5 生词本词条选择编辑

> 状态：完成 | 日期：2026-09-19 | 对应 SCR：SPEC_CHANGE_REQUEST_PHASE8_5.md

## 1. 现状盘点

### 1.1 数据层（已具备替换语义，缺定向写入）

| 项 | 现状 | 结论 |
|---|---|---|
| 重存语义 | `saveWordToBooks`（`SqlDelightWordBookRepository.kt:98-135`）：词已在本 → 删旧 entry（级联清两类选择）→ 保留 entryOrder/pendingTranslation 重插；事务内 `SaveRequestValidator` 校验 | **编辑语义已存在**，但走的是"删 entry 行重建"路径：`wordBookEntryId` 换新、`addedAt` 刷新 |
| 选择读取查询 | `selectEntryDefinitions` / `selectExampleSelections`（按 wordBookEntryId）+ `selectEntryByWord`（(bookId, wordId) → entry） | 全部在位，读路径零新增查询 |
| 例句选择删除 | `deleteExampleSelectionsForEntry`（`WordBookEntryExampleSelection.sq:17-18`，removeWord 流遗留） | 已在位 |
| 释义选择删除 | **无** `WordBookEntryDefinition` 的按 entry 删除查询 | 唯一 .sq 缺口，+1 query-only |
| 校验器 | `SaveRequestValidator`：词/本存在 + 释义属词 + 例句属释义 | 可复用（selections 校验段与 wordBookIds 解耦，改造入参即可） |

### 1.2 UI 层（零编辑能力）

- `BookDetailScreen`（`WordBooksScreen.kt:151-199`）：词条行 = 词文本 + 「移除」按钮，无编辑入口
- `SaveToWordBookDialog`：选中态初始化恒为空（task#12 后 load() 主动清空），无"读取已存在选择"路径
- 词库详情渲染（释义分组 + 例句勾选行）已有可参照实现（WordDetailScreen 保存对话框），可提取同风格行渲染

### 1.3 引擎/会话影响面

- 播放分段按 wordBookEntryId 查 Q4/Q4b → 编辑替换选择行后，**下一次段构建**（该词下一次开始播放）自然取新选择集；当前播放中的词段已构建，不受影响——粒度与裁决 L4（Toggle 下一 Segment）同构，无引擎改动
- 掌握（WordMastery）/会话（SessionWord）主键均不含 wordBookEntryId → 编辑零接触
- 若采用"删 entry 重建"路径（不推荐）：wordBookEntryId 变化同样无害（无表引用它持久化），但 addedAt 被刷新、行号抖动——定向更新路径更干净

## 2. 缺口清单（= 实现范围）

| # | 缺口 | 层 |
|---|---|---|
| 1 | `deleteEntryDefinitionsForEntry` 查询 | .sq（query-only，零迁移） |
| 2 | 端口 `getWordSelections(bookId, wordId)`（读当前选择，entry 缺 → null） | shared port |
| 3 | 端口 `updateWordSelections(bookId, wordId, selections)`（单事务定向替换，不重建 entry 行） | shared port |
| 4 | 编辑入口 + 编辑对话框（预填 + 全词库选项渲染 + 空态） | app UI |
| 5 | 编辑 VM（加载投影 / 勾选态 / 保存转发） | app VM |
| 6 | 测试电池（仓储 jvmTest + VM 单测） | tests |

## 3. 风险与裁决点

| 点 | 裁决（本报告建议） |
|---|---|
| 写路径形态 | 定向替换（两 delete + 重插），**不重建 WordBookEntry 行**——entryOrder/addedAt/pendingTranslation 原样；与重存路径（重建）并存不冲突 |
| ≥1 释义守卫 | 镜像保存流（`RepositoryValidationException`），UI 侧同步拦截文案"至少保留一条释义" |
| 导入词（无释义） | 编辑对话框空态提示；不隐藏入口（入口可见性 = 词条行恒有「编辑」） |
| 查词保存流是否预填 | **不预填**（维持 FR-5 现状）；FR-17 注记"修改选择的正规入口 = 本详情编辑"；避免多本选择对话框按本差异化预填的复杂度 |
| DERIVED 派生本词条 | 同机制可编辑（普通词条行）；无规格限制，不特殊处理 |
| 校验复用 | `SaveRequestValidator` 抽 selections 校验段供两路共用（internal 类内重构，无 API 变化） |

## 4. 结论

变更可行且低风险：数据层语义已在位（重存替换），缺口集中在 1 条 query-only 查询、2 个端口方法、UI 入口与编辑界面。**零迁移、零引擎改动、零架构边界变化。**
