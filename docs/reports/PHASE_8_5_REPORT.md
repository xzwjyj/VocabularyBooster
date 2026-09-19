# PHASE 8_5 REPORT — 生词本词条选择编辑（FR-17）

> 日期：2026-09-19 ｜ 协议文档：docs/agent-sync/`*_PHASE8_5.md` ｜ 决策：ADR-006
> 范围：用户 2026-09-19 vivo 走查后提出——已保存词可增删释义/例句选择

## 1. 交付摘要

| 层 | 交付 |
|---|---|
| .sq | Q7 `deleteEntryDefinitionsForEntry`（query-only，**schema 恒 v2 零迁移**） |
| 端口 | `WordBookRepository` +2：`getWordSelections`（只读快照，null=词不在本内）/ `updateWordSelections`（单事务定向替换） |
| 领域 | `WordBookSelectionSnapshot`（读/写往返同构 `DefinitionSelection`） |
| data | 实现两方法 + `SaveRequestValidator.validateSelections` 抽共用段（FR-5/FR-17 两路同规则） |
| app | `WordSelectionEditorViewModel` + `WordSelectionEditorDialog`（预填 + FR-2 排序渲染 + 导入词空态）+ BookDetail「编辑」入口 + 保存成功自动关对话框 |

## 2. 语义要点（FR-17，PROJECT_SPEC v1.13）

- 保存 = **单事务整组替换**两类选择行；≥1 释义守卫（VM 前置 + 端口双保险）
- **不重建词条行**：entryOrder / addedAt / pendingTranslation 逐字段不变（编辑绝不改队列位置）
- 掌握（WordMastery/SessionWord）零接触（D4 正交）
- 生效时点 = 该词下一次播放（Q4/Q4b 段构建重读，粒度同裁决 L4，零引擎改动）
- 查词保存流（FR-5）维持不预填——修改选择的正规入口 = 本详情「编辑」

## 3. 质量门禁（全绿）

| 门禁 | 结果 |
|---|---|
| :shared:jvmTest | **278/0**（+4 编辑组：替换往返 / 词条行不重建 / 校验拒绝回滚 / 掌握零接触） |
| :shared:testDebugUnitTest | 绿（commonTest Android 编译运行） |
| :app:testDebugUnitTest | **46/0**（+5：预填投影 / 勾选增删转发 / 空选择拦截 / 写失败文案 / 词库缺失提示） |
| detekt ×2 + checkPlatformBoundaries | 绿 |
| :app:assembleDebug | 绿 |
| :app:connectedDebugAndroidTest（模拟器） | **49 tests / 0 failed / 4 skipped**（+1 编辑冒烟：种子两释义 → 取消一条 → DB 选择行减少） |

## 4. 偏差与环境备注

- **无范围偏差**。计划外处理：AppModule import 漏项、两处编译笔误（`entries` 字段名 / 测试命名参数）、detekt 行长——即改即绿。
- **环境事件（非回归）**：当日 jvmTest 三次 native 崩溃于 `ImportPerfTest` 十万行基准（sqlite-jdbc `NativeDB.prepare_utf8`）。排查链：编辑组 11/0 绿 → 隔离跑 perf 通过 → **stash 基线（不含本次改动）复现崩溃** → 根因 = 系统内存压力（16G 总量仅 ~2G 空闲时必崩；hs_err 三份取证）。停 Gradle 守护进程（`--stop` 释放 ~2.5G）后全量重跑通过。**经验：门禁 native 崩溃先查空闲内存（powershell FreePhysicalMemory），低于 ~3G 时先 `./gradlew --stop` 或请用户关闭高占用应用。**

## 5. 走查清单（vivo，待用户执行）

1. 生词本 → 打开任一本 → 词条行有「编辑」→ 打开：预填与当前保存的勾选一致
2. 增删释义/例句 → 保存 → 重新打开编辑：与刚才保存一致；「开始学习」该词分段内容与勾选一致（下一次播放起）
3. 编辑某词后回到本详情：词条顺序不变（队列位置不变）；已掌握词编辑后掌握状态保持
4. 全不勾释义 → 保存被拒（提示"至少保留一条释义（不需要该词请用「移除」）"）
5. （如有导入词）编辑显示"该词暂无词库释义可编辑"

## 6. 文档同步

PROJECT_SPEC v1.13（FR-17 + FR-5 注记 + 验收行）/ ROADMAP v1.10（Phase 8.5 节）/ TEST_PLAN v2.15（§4.8 词条编辑组 + FR 矩阵）/ DATABASE_SCHEMA v1.9（Q7 + §6 映射）/ agent-sync 三件套 + ADR-006。
