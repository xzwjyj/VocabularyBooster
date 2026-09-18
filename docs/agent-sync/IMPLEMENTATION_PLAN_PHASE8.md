# 实现计划：Phase 8 设置页

> 关联：SPEC_CHANGE_REQUEST_PHASE8.md / AUDIT_REPORT_PHASE8.md ｜ 状态：范围已裁决（方案 A + 打磨项延后，2026-09-18），待批准执行 ｜ 日期：2026-09-18

## 设计约束

1. **业务规则只在 shared**（铁律 2）：设置范围校验在端口实现；app 只做 UI 渲染 + 意图转发 + 文案。
2. **零迁移**：不动 DDL、不动 `.sq`（`upsertSetting` 自 Phase 1 在位）；schema 恒 v2。
3. **加法扩展**（L6 先例）：`LearningSettingsRepository` 只增五个写方法，既有 getter 语义零变化；KDoc「本端口只读」注记更新为「写自 Phase 8」。
4. **即时持久化**（FR-15）：每个控件变更即写库（无保存按钮）；groupSize 控件旁固定提示「只对新会话生效」（FR-6）。
5. **写侧校验镜像读侧**：groupSize ≥1、commandWindowMs >0、ttsRate/ttsPitch >0；越界 → 端口抛 `RepositoryValidationException`，UI 提示并回显当前持久值。
6. **别名维持代码常量**（方案 A）：`CommandParser` / 编排器 / 近音表零改动。

## 模块结构（as-built 目标）

```
domain/repository/LearningSettingsRepository.kt   # +setGroupSize/setPlaybackToggles/setCommandWindowMs/setTtsRate/setTtsPitch
data/SqlDelightLearningSettingsRepository.kt       # 写 = json 编码 + upsertSetting；校验镜像读侧
app/ui/SettingsScreen.kt                           # 设置页（新文件）：分组渲染 + 控件 + 校验提示
app/ui/AppViewModels.kt                            # +SettingsViewModel（load 一次快照 → 控件变更 → 立即写）
app/MainActivity.kt                                # 第四 Tab「设置」
```

## 实施步骤（单批次，Step 内序贯）

| # | 内容 | 文件 | 备注 |
|---|---|---|---|
| 1 | 端口写方法 + 实现 | `LearningSettingsRepository.kt` + `SqlDelightLearningSettingsRepository.kt` | 五 setter；suspend + dispatcher；显式 public；FakeLearningSettingsRepository 同步 +5 |
| 2 | jvmTest 仓储测试 | `LearningSettingsRepositoryTest.kt` 扩展 | 五键往返（写→close/reopen→读回替换值）；越界写拒绝且原值不变；缺键默认既有用例保持 |
| 3 | SettingsViewModel | `AppViewModels.kt` | load 五值一次快照；每个变更即写 + 失败提示回滚显示；app 单测（Fake 端口）：加载投影 / 变更持久化 / 越界提示 |
| 4 | 设置页 UI | `SettingsScreen.kt` + `MainActivity.kt` 第四 Tab | groupSize（数值输入+「只对新会话生效」提示）/ commandWindowMs（滑条 1–10s，步长 500ms）/ 六项播放开关（Switch）/ 语速·音调（滑条 0.5–2.0，步长 0.05）；androidTest 冒烟 1 条（开页→改开关→重开库验证持久） |
| 5 | 文档同步 | SCR §3 清单 | PROJECT_SPEC 版本记录（FR-15 别名行注记「可配置性再延后」+ 验收行）+ ROADMAP 版本记录（Phase 8 收敛 + 打磨延后）+ TEST_PLAN TC-UI 设置落点 + TC-LE-02 复核注记 + LEARNING_ENGINE_SPEC §12 注记 |
| 6 | 门禁 + 走查 | 全量测试 / 双 detekt / checkPlatformBoundaries / assembleDebug → vivo 走查（MVP 口径可延后）→ 用户确认 → `feat(settings)` 提交 + DECISION_LOG | — |

## 验收对照

- FR-15 验收（收敛后口径）：五项设置可查看、可变更、即时持久化、重启后保持；groupSize 变更不影响进行中会话（TC-LE-02 既有断言）；
- 六项播放开关变更 → 下一 Segment 生效（L4 既有）；commandWindowMs 变更 → 下一窗口生效（每窗读既有）。

## 已裁决记录（2026-09-18 用户）

1. **别名可配置 = 方案 A 再延后**（三别名代码常量 + 近音兜底维持；FR-15 第六行注记再延后）；
2. **i18n 校对 / TalkBack / NFR-2 逐项测量 = 延后**（与 M1–M4 同口径，MVP 后打磨批）。
