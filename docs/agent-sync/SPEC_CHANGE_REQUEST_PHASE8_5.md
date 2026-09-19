# 规格变更请求 — Phase 8.5 生词本词条选择编辑

> 状态：已批准并执行（2026-09-19，ADR-006；自动化门禁全绿，vivo 走查待用户） | 作者：Claude（用户 2026-09-19 走查后提出） | 日期：2026-09-19

## 变更概述

**类型**：
- [x] 新功能
- [ ] 需求修正
- [ ] 架构调整
- [ ] Bug 修复
- [ ] 其他

**影响范围**：
- 新增 **FR-17 生词本词条选择编辑**：对已保存进生词本的词，增删其释义/例句选择（WordBookEntryDefinition / WordBookEntryExampleSelection 两类关系行）
- 不改 FR-5 保存流语义（重存 = 替换选择的既有仓储行为维持，见影响分析）

## 变更原因

### 背景

- FR-5 保存流只在**首次收藏**时选择释义/例句；收藏后没有任何入口修改选择集
- 用户场景（原话）：已保存一个词汇进生词本后，希望"可以对其中不同释义和例句进行增删"

### 问题描述

- `BookDetailScreen` 词条行只有「移除」，无编辑入口
- 查词页再次保存同一词：对话框选中态恒为空 → 用户须凭记忆重勾全部；**少勾的部分会被替换语义静默删除**（`saveWordToBooks` 重存 = 删旧插新，`SqlDelightWordBookRepository.kt:105` 注释）——既无预填也无提示，是当前唯一"改选择"路径且易丢数据

## 变更内容

### 需求变更

新增 **FR-17**（PROJECT_SPEC v1.13）：

1. 本内词条行提供「编辑」入口 → 编辑界面渲染该词**全部词库释义与例句**（与查词详情同源、同排序 FR-2），**预填当前已保存的选择**
2. 保存 = **单事务整组替换**该 (生词本, 词) 的两类选择行：勾选增删并存；至少保留 1 条释义（全不勾 = 请改用「移除」）
3. 校验镜像 FR-5 保存流（释义属词、例句属释义；词/本存在）
4. **不重建 WordBookEntry 行**：entryOrder / addedAt / pendingTranslation 原样保留（编辑不改队列位置）
5. 掌握状态（WordMastery / SessionWord）不受编辑影响（掌握作用域 D4 与选择集正交）
6. 进行中会话：编辑对该词的生效时点 = 该词**下一次开始播放**（段构建时重读选择，粒度同裁决 L4）；当前播放段不中断
7. 导入词（无词库释义）：编辑界面显示空态提示"该词暂无词库释义可编辑"（导入词的 pendingTranslation 译文展示维持本详情现状）

### 影响分析

- 受影响的 FR：新增 FR-17；FR-5 维持现状（重存替换语义既有事实，规格显式注记"编辑的正规入口是本详情编辑界面"）
- 受影响的规格文档：PROJECT_SPEC（v1.13，FR-17 + 版本记录）、ROADMAP（v1.10，Phase 8.5 节）、TEST_PLAN（v2.15，§4.8 词条编辑组）、DATABASE_SCHEMA（v1.9，Q7 注记——query-only）；DOMAIN_MODEL / ARCHITECTURE 零变更（无新实体、无新平台能力）
- 受影响的代码模块：shared `WordBookRepository` 端口 +2 读/写方法（加法扩展，L6 先例）、`SqlDelightWordBookRepository` 实现、`WordBookEntryDefinition.sq` +1 删除查询（query-only）；app `BookDetailScreen`（编辑入口）+ 新编辑对话框 + VM

## 风险评估

- [x] 低风险
- [ ] 中风险
- [ ] 高风险

**说明**：
- **零迁移**：仅新增 `deleteEntryDefinitionsForEntry` 查询（`deleteExampleSelectionsForEntry` 已在位）；schema 恒 v2（Q6 先例）
- 替换语义与既有重存行为同构，无新不变量；掌握/会话数据零接触
- UI 为新增对话框 + 行内按钮，不动既有屏

## 验收标准

1. 本内词条「编辑」→ 预填当前选择；勾/取消任意释义与例句 → 保存后重开编辑界面，选择集与刚才保存一致
2. 编辑后开始学习/播放：分段内容与新的选择集一致（该词下一次播放起）
3. 编辑不改词条在队列中的位置（entryOrder 不变）；已掌握词编辑后掌握状态保持
4. 全不勾释义 → 保存被拒（中文提示）；例句独立于所属释义勾选（取消释义 = 该释义例句随之消失，镜像保存流交互）
5. 既有测试全绿（jvmTest / shared unit / app unit / detekt×2 / checkPlatformBoundaries / assembleDebug）

## 附件

- [x] 审计报告（AUDIT_REPORT_PHASE8_5.md）
- [x] 实现计划（IMPLEMENTATION_PLAN_PHASE8_5.md）
- [ ] 其他
