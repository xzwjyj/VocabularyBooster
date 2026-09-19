# Decision Log

> 记录项目关键决策

---

| ADR | 标题 | 状态 | 日期 |
|-----|------|------|------|
| ADR-002 | 学习语义重构 - WordBook 与 WordMastery 解耦 | Accepted（commit `924fdc6`） | 2026-09-15 |
| ADR-003 | Phase 6 勋章：事件驱动幂等授予 + 零迁移 | Accepted（commit `a1d282b`） | 2026-09-18 |
| ADR-004 | Phase 7 TXT 导入：单一事务 + runBlocking 取消桥 + 零迁移 | Accepted（commit `55e5003`） | 2026-09-18 |
| ADR-005 | Phase 8 设置页：端口写方法 + 即时持久化 + 别名维持常量 | Accepted（commit `fed2594`） | 2026-09-18 |
| ADR-006 | Phase 8.5 词条选择编辑：定向替换 + 零迁移 + 保存流不预填 | Accepted | 2026-09-19 |

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

**状态**：已实现（commit `fed2594`）；vivo 真机走查通过（2026-09-19：设置页五项 + task#12 保存流两场景，task#12 修复随 `43be3dd` 提交）

### ADR-006: Phase 8.5 词条选择编辑落地形态

**日期**：2026-09-19（用户批准 IMPLEMENTATION_PLAN_PHASE8_5 后执行；需求源自用户 vivo 走查后提出——已保存词需可增删释义/例句选择）

**决策**：
- 审计确认仓储层替换语义已在位（`saveWordToBooks` 重存 = 删旧插新、保 entryOrder）→ 缺口收敛为 UI 入口 + 预填 + **定向写方法**（不重建 WordBookEntry 行——entryOrder/addedAt/pendingTranslation 逐字段不变，与重存路径并存）
- 端口 +2（加法扩展，L6 先例）：`getWordSelections`（只读快照单事务；例句经 Example.definitionEntryId 归属回释义——选择行不存归属，与 Q4/Q4b 播放装配同口径）/ `updateWordSelections`（单事务：entry 存在守卫 → `SaveRequestValidator.validateSelections` 抽共用段（保存流 FR-5 与编辑 FR-17 两路同规则）→ Q7 `deleteEntryDefinitionsForEntry` + 既有 `deleteExampleSelectionsForEntry` → 重插）
- .sq 仅 +1 删除查询（Q7，query-only）→ **schema 恒 v2 零迁移**
- ≥1 释义守卫：VM 前置拦截 + 端口双保险（同文案"至少保留一条释义（不需要该词请用「移除」）"）
- **查词保存流（FR-5）不预填**：修改选择的正规入口 = 本详情「编辑」（PROJECT_SPEC v1.13 FR-5 注记）——避免多本对话框按本差异化预填的复杂度
- 掌握/会话零接触（D4 正交）；生效时点 = 该词下一次播放（段构建重读 Q4/Q4b，粒度同裁决 L4，零引擎改动）
- 导入词空态提示（不隐藏入口）；VM load() 清旧状态 + saved 自动关对话框（task#12 手法复用）

**测试**：jvmTest +4（编辑组：替换往返 / 词条行不重建 / 校验拒绝事务回滚 / 掌握零接触）——278/0；app unit +5（WordSelectionEditorViewModelTest：预填投影 / 勾选增删转发 / 空选择前置拦截 / 写失败文案 / 词库缺失提示）——46/0；connected +1（WordSelectionEditorUiSmokeTest：种子两释义 → 取消一条 → DB 选择行减少）——49 tests/0 failed/4 skipped；detekt×2 / checkPlatformBoundaries / assembleDebug 全绿

**环境备注**：当日 jvmTest 曾三次 native 崩溃（sqlite-jdbc `NativeDB.prepare_utf8`，十万行导入基准）——stash 基线复现证明与本次改动无关，根因 = 系统内存压力（16G 仅 ~2G 空闲时必崩；停 Gradle 守护进程 + 重试即过）。后续门禁若再现，先查空闲内存。

**状态**：已实现；vivo 装包走查待用户执行（编辑预填一致 / 增删生效 / 队列位置不变）

### ADR-007: Phase 8.6 打磨批执行决策（bug list 四项）

**日期**：2026-09-19（用户批准 IMPLEMENTATION_PLAN_BUGLIST 后执行；四项实现顺序 图标→TTS→统计→词典，外部数据风险置底）

**决策**：
- **FR-18 词典**：数据源 = ECDICT 1.0.28 sqlite 发行包（MIT；GitHub release `ecdict-sqlite-28.zip`，无裸 CSV——stardict 包为二进制格式弃用）。子集口径 = `bnc IS NOT NULL`（入词频表 846,281 条，未入表长尾为噪声）+ 字符集 `[A-Za-z][A-Za-z \-']{0,39}` 过滤 → **836,398 条**（449,387 词 + 387,011 短语；含音标 217,591），落位于用户裁决"全量 ~77 万词"。义项上限 8/词；EN↔CN 仅双方都有第 i 行时按位配对，剩余单侧成条不伪造关联；无词性前缀行 → `other`@90（DOMAIN_MODEL §3.1 逃逸口）
- **资产形态偏差（对 PLAN 的两处修正）**：① definitions 列由对象 JSON 改为**紧凑数组 JSON** `[["pos","en","cn"],…]`（对象键开销 ~95B/义项会使资产膨胀至 ~200MB；数组编码后 ~90MB，APK 实测 +36MB）；② 产物 `app/src/main/assets/dict/ecdict.sqlite`（DictEntry WITHOUT ROWID，normalized PK ≡ `toNormalizedWordText()`）**不入 git**（.gitignore），再生 = `tools/dict/convert.py` + README 三步（~10s）
- **接线**：`FallbackDictionaryProvider`（种子优先→随包兜底）；DB miss → `SeedImporter.import(单词)` 幂等按需导入后重读；两依赖可空（手工 Koin 图保持纯 DB 行为）。**Koin 4 无 override** → `DictionaryProvider` 全库唯一绑定移至 appModule（DataModule 撤销绑定）；Android actual 资产缺席/损坏 → 永久 null 降级（查词增强非硬依赖，CI 无资产时冒烟 assume 跳过）
- **FR-20 统计口径**（Q8/Q9 query-only 零迁移）：学习 = 按 wordId 最早 masteredAt 的本地日（跨本去重）；复习 = 事件日**严格晚于**首掌日（同日重复既非学亦非复）；时长 = endedAt−startedAt 按结束日归集（ACTIVE 未结束行不计）。**分桶在 Kotlin**（注入 Clock/TimeZone——SQL strftime 'unixepoch' 为 UTC 会错本地日界）；窗口 日=近 30 天 / 月=近 12 个月 / 年=首事件年至今年，零值填充
- **FR-19 音色 + 图标**：详见 c991efb / 8d6c71f commit（音色键损坏读→null 防御性降级——设备数据可自然失效，与设置键"损坏即抛"语义不同，已在端口 KDoc 注明）

**测试**：jvmTest +11（统计 7：首掌去重/晚日复习+同日不算/时长归结束日含跨午夜/ACTIVE 不计/空库零值+窗口填充/月年上卷/注入时区日界；词典 3：命中导入后 DB 供数/未收录 null 且库零增长/DB 命中不打扰源）+ 既有 3 文件位置参数改命名；app unit +3（StatsViewModel 投影/粒度切换/失败提示）；androidTest +3（统计卡冒烟 / 词典资产冒烟 / 词典 Koin 全链路）；FR-19 组随 c991efb。**最终门禁（2026-09-19 收尾，含三个修复 commit 后树态）**：connected 54 执行/49 过/5 skip/0 失败；jvmTest 28/29 类分批全绿（ImportPerfTest 被机器级原生故障阻塞——当日 0 时段门禁 290/0 含它通过、import 路径零改动，复核命令与取证见 PHASE_8_6_REPORT §4）；detekt×2 / checkPlatformBoundaries / testDebugUnitTest×2 绿；APK 实测 **130.3MB**（词典资产 85.8MB）。**收尾补修**：Koin 复合绑定自引用（`get()` 按接口类型解析回自身 → StackOverflow，改 `get<SeedDictionaryProvider>()`——真实图首启即崩、connected 首跑捕获）；统计冒烟 unmerged 树断言（clickable 卡 mergeDescendants 吞内层 tag）。

**状态**：已实现并全量门禁收尾（`8d6c71f` 图标 + `c991efb` 音色 + `bd46150` 统计 + `dd17820` 词典 + 收尾修复三 commit）；ImportPerfTest 待重启机器后单跑复核；vivo 装机走查待用户执行（图标目视 / 音色切换 / 统计卡与图表 / 全量查词含短语）
