# 实现计划 — Phase 8.5 生词本词条选择编辑

> 状态：已执行完毕（2026-09-19，Step 0–3 全绿；收尾见 PHASE_8_5_REPORT.md） | 日期：2026-09-19 | 前置：SCR + AUDIT 已定稿

## 交付物

FR-17：本内词条「编辑」→ 预填选择编辑对话框 → 单事务整组替换（零迁移、零引擎改动）。

## Step 0 规格同步（批准后立即，先于代码）

PROJECT_SPEC v1.13（FR-17 全文 + 验收行 + FR-5 注记 + 版本记录）→ ROADMAP v1.10（Phase 8.5 节 + 总览行）→ TEST_PLAN v2.15（§4.8 词条编辑组）→ DATABASE_SCHEMA v1.9（Q7 注记 query-only）。

## Step 1 shared：查询 + 端口 + 实现

1. `WordBookEntryDefinition.sq` +：
   ```sql
   -- Q7 词条选择编辑（FR-17，Phase 8.5）：定向替换释义选择（query-only——schema 恒 v2）
   deleteEntryDefinitionsForEntry:
   DELETE FROM WordBookEntryDefinition WHERE wordBookEntryId = ?;
   ```
2. 端口 `WordBookRepository` +2（加法扩展，L6 先例；KDoc 注明 FR-17、事务性、不重建 entry 行）：
   - `public suspend fun getWordSelections(wordBookId: Long, wordId: Long): WordBookSelectionSnapshot?`——entry 不存在 → null；快照 = `Map<definitionEntryId, Set<exampleId>>`（新领域模型，domain/model）
   - `public suspend fun updateWordSelections(wordBookId: Long, wordId: Long, selections: List<DefinitionSelection>): Unit`——单事务：entry 存在守卫 → ≥1 释义守卫 → selections 校验（`SaveRequestValidator` 抽共用段）→ `deleteEntryDefinitionsForEntry` + `deleteExampleSelectionsForEntry` → 重插两类选择行
3. `SqlDelightWordBookRepository` 实现两方法（`withContext(dispatcher)` + `transactionWithResult`，镜像既有风格）

## Step 2 app：VM + 编辑 UI

1. `WordSelectionEditorViewModel`（AppViewModels.kt 追加，三拆手法同 Phase 8）：
   - 状态：loaded / detail(词库全部释义+例句，FR-2 排序) / selectedDefinitions / selectedExamples（复用 WordDetailViewModel 同构勾选态）/ message / saved
   - `load(bookId, wordId, wordText)`：`wordRepository.lookup` + `wordBookRepository.getWordSelections` 预填
   - `toggleDefinition` / `toggleExample` / `save()`（空选择拦截文案"至少保留一条释义"；成功 message="已保存"+saved=true）
2. `WordSelectionEditorDialog.kt`（新文件）：渲染与保存对话框同风格（释义行勾选 + 所属例句子勾选）；无词库释义 → 空态"该词暂无词库释义可编辑"；词行 = 词文本 + 已选计数
3. `BookDetailScreen` 词条行 +「编辑」TextButton（testTag `edit_word_button`）→ 打开对话框；关闭即弃（无半存态）
4. `AppModule.kt` + `viewModel { WordSelectionEditorViewModel(get(), get()) }`

## Step 3 测试

1. jvmTest `WordBookRepositoryTest` +4（真实 JDBC）：
   - 编辑替换往返：勾不同释义/例句保存 → 重读快照一致；未勾旧项消失
   - entry 行不重建：编辑前后 entryOrder / addedAt / pendingTranslation 逐字段不变
   - 空 selections / 词不在本 / 例句不属释义 → `RepositoryValidationException` 且原选择原样（事务回滚）
   - 掌握零接触：已掌握词编辑后 WordMastery 行仍在
2. app unit `WordSelectionEditorViewModelTest`（StandardTestDispatcher 模式）：预填投影 / 勾选增删 / 空选择拦截文案 / 保存成功态
3. androidTest 冒烟 `WordSelectionEditorUiSmokeTest`（模拟器）：进入本详情 → 编辑首词 → 取消一条释义 → DB 断言选择行减少
4. 全量门禁：`jvmTest` / `testDebugUnitTest` / app 单测 / detekt×2 / `checkPlatformBoundaries` / `assembleDebug` / connectedDebugAndroidTest（模拟器，vivo 拔出）

## Step 4 收尾

走查清单（vivo 或模拟器，用户裁决）→ DECISION_LOG ADR-006 → PHASE_8_5_REPORT.md + ROADMAP 状态 → commit `feat(wordbook)`。

## 边界与不做

- 不改 FR-5 查词保存流（不预填；规格注记正规编辑入口）
- 不做批量编辑 / 拖动排序 / 释义粒度外的任何 entry 字段编辑
- 不隐藏无释义词的编辑入口（空态提示即可）
