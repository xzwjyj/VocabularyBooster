# Decision Log

> 记录项目关键决策

---

| ADR | 标题 | 状态 | 日期 |
|-----|------|------|------|
| ADR-002 | 学习语义重构 - WordBook 与 WordMastery 解耦 | Accepted（commit `924fdc6`） | 2026-09-15 |
| ADR-003 | Phase 6 勋章：事件驱动幂等授予 + 零迁移 | Accepted（commit `a1d282b`） | 2026-09-18 |
| ADR-004 | Phase 7 TXT 导入：单一事务 + runBlocking 取消桥 + 零迁移 | Accepted（commit `55e5003`） | 2026-09-18 |
| ADR-005 | Phase 8 设置页：端口写方法 + 即时持久化 + 别名维持常量 | Accepted | 2026-09-18 |

---

## 决策记录

### ADR-002: 学习语义重构

**日期**：2026-09-15

**审计结果**：
- Schema 已支持需求 ✅
- 问题：Q2 查询排除已掌握词 ❌
- 问题：ALL_MASTERED 拒绝入口 ❌

**决策**（2026-09-15 批准）：
- 修改 Q2 查询移除 IS NULL 条件
- 移除 ALL_MASTERED 拒绝逻辑
- CompletionDetector 用 "snapshot consumed" 规则

**设计约束**：
- Snapshot 只保存 wordId 集合，不复制 Word 内容
- Snapshot 只冻结 membership，不冻结 word metadata
- 原始 WordBook 与 Derived Review Book 生命周期隔离

**回归测试**：
- A. mastered word remains in original WordBook
- B. study session completion does not empty WordBook
- C. historical snapshot remains reproducible
- D. derived review book generation does not mutate source WordBook

**状态**：已实现（refactor(learning) commit `924fdc6`，掌握态与生词本成员关系解耦）；Phase 6 授予防御复核沿用其会话快照口径

### ADR-003: Phase 6 勋章落地形态

**日期**：2026-09-18（用户批准 IMPLEMENTATION_PLAN_PHASE6 后执行）

**决策**：
- 授予 = 事件驱动：`WordBookCompleted` → AchievementEngine 幂等授予（唯一索引 + INSERT OR IGNORE 双保险），重放/同本重学均恰一枚
- 防御复核采用 ADR-002 会话快照口径：ACTIVE 会话 / 伪 COMPLETED（带未掌握词）/ 未知会话 → 零授予 + WARN 留痕
- 事件时序锚点：编排器两条完成路径（advance 自然完成 / exit 分支 C）都在端口 stop 之后发布事件
- 引擎冷启动订阅（Application onCreate，IO scope）——SharedFlow 无 replay，迟订阅丢历史事件
- Achievement 表/索引自 schema v1 在位：**零迁移**（query-only）
- 删除拦截：勋章本不可删 / 有派生子本先删派生本（WordBookDeletionException → 中文文案）

**测试**：TC-AC-01…05 全绿（AchievementEngineTest 集成 + 编排器顺序 + VM 文案/仪式页）

**状态**：已实现（commit `a1d282b`）；真机勋章走查按 MVP 口径延后

### ADR-004: Phase 7 TXT 导入落地形态

**日期**：2026-09-18（用户批准 IMPLEMENTATION_PLAN_PHASE7 后执行）

**决策**：
- 包名取 `importing`（`import` 是 Kotlin 关键字，不可作包名；IMPORT_SPEC v1.1 注记）
- 编码检测确定性三级瀑布：BOM → UTF-8 严格校验（RFC 3629，head 末尾截断容忍）→ GB18030 兜底（**零容忍**：任一非法序列 → Unsupported，v1 不提供强制指定编码）；无置信度字段
- 事务 = **单一大事务**（弃分块草案）：SQLDelight 2.0.2 同步驱动无 suspend 事务 → `transactionWithResult` + `runBlocking(outerJob)` 桥（父 Job 传播协程取消 → 每 256 行 ensureActive → 整体回滚 ≤1s）
- 去重四层 + `updated ⊆ duplicatesInBook` 口径（守恒式：totalLines = imported + reusedWords + duplicatesInFile + duplicatesInBook + invalid）
- 新建本**延迟创建**（首条 entry 插入时）——空文件/全重复/全非法不建空本
- `ImportFinished` 仅在事务提交且至少一行有效写入后发布
- `updateEntryPendingTranslation` 为 query-only：**schema 恒 v2 零迁移**
- 译文超限（>256）→ 整行 Invalid，不再尝试更宽分割（IMPORT_SPEC §3）

**测试**：TC-IMP-01…08 全绿（EncodingDetectorTest 8 / LineParserTest 11 / ImportEngineTest 6 / ImportPerfTest 2——10 万行 ~3s ≤60s、取消回滚 ≤1s）

**状态**：已实现（commit `55e5003`）；vivo 真机走查按 MVP 口径延后

### ADR-005: Phase 8 设置页落地形态

**日期**：2026-09-18（用户批准 IMPLEMENTATION_PLAN_PHASE8 + 范围裁决「方案 A + 打磨项延后」后执行）

**决策**：
- 范围 = **方案 A**：五项设置（groupSize / commandWindowMs / 六项播放开关 / ttsRate / ttsPitch）读写 + 设置页（MainActivity 第四 Tab）；「会了」别名**可配置性再延后**（PROJECT_SPEC v1.12——三别名代码常量 + 近音兜底维持，避免编辑别名集意外丢失 vivo 实证调优的误听容错）；i18n / TalkBack / NFR-2 逐项测量延后至 MVP 后打磨批（ROADMAP v1.9）
- 写路径 = 端口五 setter（L6 加法扩展先例，既有 getter 语义零变化）；写侧范围校验**镜像读侧**（groupSize ≥1、其余 >0，越界拒绝写入且原值不变）；`upsertSetting` 自 Phase 1 在位 → **零迁移零 .sq 变更**
- 生效时机**零引擎改动**：开关/语速/音调每段重读（L4）、commandWindowMs 每窗重读、groupSize 会话开始固化（FR-6）——均为既有读取点事实，写入无失效广播
- 六项播放开关**写侧不拦截全关**（会话开始 PLAYBACK_DISABLED 裁决保持，LE spec §3）
- VM 形态：快照加载 → 控件变更即时持久化（FR-15 无保存按钮）→ 失败中文提示并回滚显示持久值；范围校验只在 shared 端口（铁律 2）
- 附带修复：`BookDeletedRecoveryUiSmokeTest` 三参化漏改（Phase 6 起 androidTest 编译损坏——`LearningSessionViewModel` +achievementRepository/eventBus 后未同步；此前无 connected 运行故未暴露）

**测试**：jvmTest +3（五键 close/reopen 往返 / 越界写拒绝原值不变 / upsert 覆盖）；app unit +6（SettingsViewModelTest：加载投影/合法即时持久化/非数字不落库/写失败提示+回滚/开关持久化/窗口与语速·音调持久化）；connected +1（SettingsUiSmokeTest 真实 App 流程 KV 落库断言）；全量 connected 44/0 failed/4 skipped 复验

**状态**：已实现；vivo 真机走查同批执行（设置页五项 + task#12 保存流两场景）
