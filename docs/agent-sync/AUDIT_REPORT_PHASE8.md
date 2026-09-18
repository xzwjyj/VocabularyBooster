# 审计报告：Phase 8 设置页

> 关联：SPEC_CHANGE_REQUEST_PHASE8.md / IMPLEMENTATION_PLAN_PHASE8.md ｜ 日期：2026-09-18 ｜ 基线：`55e5003`
> **2026-09-18 范围裁决**：方案 A（别名可配置再延后）+ 打磨项延后——§2 缺口 #4 转 Phase 8 范围外。

## 1. 已就位（审计证据）

| # | 能力 | 证据 |
|---|---|---|
| 1 | 读端口五 getter 全在：groupSize / playbackToggles / commandWindowMs / ttsRate / ttsPitch，缺键→默认、损坏/越界→`RepositoryValidationException` | `domain/repository/LearningSettingsRepository.kt`（含五默认常量）；`data/SqlDelightLearningSettingsRepository.kt` |
| 2 | **写查询自 Phase 1 在位**：`upsertSetting` / `deleteSetting` | `AppSetting.sq:10-17` → **写路径零迁移、零 .sq 变更** |
| 3 | 别名键预登记：`settings.masteredAliases`（默认 会了、记住了、掌握了） | `AppSetting.sq:7` 注释 / DATABASE_SCHEMA §2.11 |
| 4 | **生效时机全部已符合 FR-15，无需引擎改动**：播放开关每段重读（L4，`PlaybackOrchestrator.kt:279`）；ttsRate/ttsPitch 每段 speak 组装时读（`:343-344`）；commandWindowMs 每窗读（`:362`）；groupSize 会话开始固化（`DefaultLearningEngine.startSession` → FR-6 仅新会话） | FR-15「即时持久化 + groupSize 只对新会话生效」由既有读取点天然满足 |
| 5 | 解析器已参数化：`CommandParser.parse(rawText, aliases, enabled)` | `speech/CommandParser.kt:37` |
| 6 | UI 挂载点模式：MainActivity 三 Tab（查词/生词本/勋章）+ 两全屏 overlay（学习/导入）——设置页可循 NavigationBar 第四项 | `MainActivity.kt:61-84` |

## 2. 缺失（= Phase 8 工作量）

| # | 缺口 | 归属方案 |
|---|---|---|
| 1 | 端口写方法（五个 set，同范围校验）+ 实端口 KDoc「本端口只读」注记更新 | A/B 共同 |
| 2 | 设置页 UI（SettingsScreen + SettingsViewModel）+ Tab 入口 | A/B 共同 |
| 3 | VM/仓储测试 + 设置 UI 冒烟 | A/B 共同 |
| 4 | 别名读 getter（`getMasteredAliases`，缺键→内置三别名）+ 编排器 `:414` 常量→每窗读 + 设置页别名编辑 | 仅 B |

## 3. 结论

- 五项设置 = **纯 UI + 端口加法扩展**：零迁移、零引擎行为改动、零 .sq 变更；
- 方案 B 增量 = 1 个端口方法 + 编排器一处常量替换（每窗一次 suspend 读，与 commandWindowMs 同点同模式）+ UI 一个编辑控件 + 测试；
- 两方案均不动近音兜底表、不动 D5 红线、不动 schema。
