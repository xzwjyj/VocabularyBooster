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

### ADR-008: FR-18 例句增强（Tatoeba 对齐 + 例句回填）

**日期**：2026-09-19（用户批准 SPEC_CHANGE_REQUEST_TATOEBA 后执行）

**决策**：
- **数据管线**：Tatoeba 全量 `sentences.csv`（13.5M 行）+ `links.csv`（~20M 句对）；匹配 = 句子词 **n-gram（n=1..5，词/短语统一，含 `-`/`'` 变体）**，词边界粗口/成人内容屏蔽表过滤；候选超采（词 12 / 短语 6）→ 构建时 **zh 优先、次短句优先** 选 ≤4 条/词；译文经 links 配对 eng↔cmn。**实绩：836,398 行重建，107,094 词条（词+短语）带例句，30,516 例句带中文译文，资产 123MB（APK 143.9MB）**。`PRAGMA user_version` 版本化，设备旧缓存自动重拷。**装机验证教训（同日修正）：版本号 = 修订号而非纯格式号**——中间版资产（v2，含粗口漏网）与终版同为 user_version=2，`>=2` 过期检查放行了 11:08 拷入的旧缓存 → 资产重打 user_version=3、`DICT_ASSET_VERSION` 同步 3，此后**数据重建必须递增版本号**；已被旧资产回填进 app DB 的粗口例句需定向清理（回填只补不删）
- **挂载粒度**：句子级数据无词性归属 → 挂**首释义**（provider 侧注入，经既有 `DictionaryDefinitionEntry.examples` 通道）；**不加** `DictionaryWord.examples` 字段——新导入对 SeedImporter/领域模型零改动（曾实现后回退）
- **回填（增强前已导入词条）**：`SeedImporter.importExamplesOnly()` 单事务——同句去重（跨释义）、缺句补到首释义（order 续排）、译文空只 UPDATE 译文（.sq +1 query-only 查询，schema 恒 v2）；`WordRepository.lookup` DB 命中但零例句/Tatoeba 译文空时触发，补齐后重读。完整词条不打扰词典源（不变量保留，测试口径更新）
- **运行时逃逸（机器故障应对，当日实锤三级）**：本机 CPython 在语料扫描上**确定性段错误**（同点 6/6，含"不可能"TypeError 与陌生路径 traceback）→ 子进程分片+重试仅部分缓解 → **改用 Node/V8 单进程跑扫描+links**（90 秒完成 Python 一小时未竟的全量扫描；V8 亦曾一次 fatal，重试即过）。同日 Gradle Test Executor JVM 连环原生崩溃（hs_err ×8）。结论：**机器级 RAM/硬件疑似故障**（三运行时同日中招），建议 memtest86+ 检查；工程上一切长任务必须有重试+断点

**测试**：jvmTest SeedImporterTest +4（回填幂等/译文补齐不重建行/未导入词零建词/lookup 端到端）；WordRepositoryOnDemandImportTest 词条补例句维持"DB 命中不打扰源"口径

**v4 全量例句翻译（2026-09-20 后续批）**：Tatoeba links 配对仅覆盖 3.05 万 / 29.9 万例句 → 用户要求自动补齐；**opus-mt-en-zh** 批量机器翻译全部空译文例句（人译优先、机译兜底），**覆盖率 299,558/299,576（100%）**；资产 `user_version=4`（数据重建递增铁律执行，装机旧缓存自动重拷实测）；管线重活延续 Node 化（`enrich_node.js`，本批 CPython/V8/翻译子进程多次段错误——分批 + 断点 + 勤落盘完成）；vivo 实测 karma/beautiful 例句中文正常显示

**状态**：已实现并装机验证通过（2026-09-19 vivo：karma 回填 4 条干净例句幂等稳定；短语 take off 新导入 3 义项 + 首释义 4 例句含 3 条中文；user_version 2→3 后旧缓存 118MB→129,961,984 重拷实测；2026-09-20 v4 翻译装机验证通过）；commit 待用户确认

### ADR-009: FR-21 学习会话词内容面板 + FR-18 音标回填

**日期**：2026-09-20（bug list 第 5 项，用户选择"滚动卡片列表"布局）

**决策**：
- **数据通路（零引擎改动）**：编排器新增只读公开挂起 `getCurrentContent()`（当前词 PlaybackContent 复用既有 `PlaybackContentRepository` 读路径）；ViewModel 按 wordId 去重异步装载 app 层展示投影 `LearningWordDetail`（音标 + 释义卡 + 例句卡），加载间隙收起（不闪旧词），结果按 wordId 守卫丢弃过期加载。**不引入第二播放状态源**——卡片数据不参与任何播放/掌握裁决
- **高亮联动**：`LearningUiState.Playing/Paused/Error` 增 `currentSegment: Segment?`（owner + type）；Playing 直取状态段；**Paused 不携带段（PlaybackState 契约不动）→ ViewModel 缓存最近 Playing 段补齐**（暂停位 = 该段，ADR-09 同口径）；CommandWindow 不高亮（窗口期无播放内容）。卡片命中 owner → 主题容器色 + 边框；段内行（EN 释义/CN 释义/例句/例句译文，按 SegmentType）加粗；词头段（owner=Word）词文本变色
- **音标回填（FR-18 扩展）**：装机发现增强前导入词条 ipaAm=NULL——`import()` 复用不回填，且例句回填闸门（例句齐备）不再触发，音标永远缺席。修复：`importExamplesOnly` 改名 **`backfillEnhancements`**（例句/译文/音标三合一，同事务）；音标**只补空缺**（ipaAm NULL 且词典有值才写，绝不覆盖已有值）；零释义 TXT 导入词（FR-14 形态）同样适用（音标块前置于释义早退）；闸门 `needsEnhancementBackfill` = 音标缺失 ∨ 零例句 ∨ Tatoeba 译文空。`.sq` +1 query-only 查询（`updateWordIpa`），schema 零迁移
- **测试**：SeedImporterTest 回填组 4→7（+音标补空缺不覆盖幂等 / 零释义词可补 / lookup 端到端例句完整但音标缺失自动补）；jvmTest + detekt + app 编译全绿（jvmTest 首跑 0xC0000374 = 机器级故障窗口，重试即过）

**状态**：已实现并装机验证通过（2026-09-20 vivo：释义段→释义卡高亮行加粗 / 例句段→例句卡高亮译文加粗 / 词头段变色 / 暂停保持 / 换词重载 / 滚动正常 / beautiful 查词后音标回填详情页与 take off 面板均显示）；commit 待用户确认

### ADR-010: FR-22 双音标显示与发音口音设置 + FR-18 音标增强（ipa-dict）

**日期**：2026-09-20（用户批准 SPEC_CHANGE_REQUEST_DUAL_IPA 后执行；音标数据源经选项裁决 = ipa-dict）

**决策**：
- **数据源与实绩**：ipa-dict（open-dict-data，MIT）——en_UK 64,307 条 → 词典资产新增 `ipaBr` 列（真 IPA 记法，ɹ 等）；en_US 12.6 万条**只补 `ipa` 空缺绝不覆盖**（含音标词条 217,591 → 250,616）；双音标齐备 59,387 条。`PRAGMA user_version=5`（数据重建递增铁律，设备旧缓存自动重拷）。ipa-dict 只收单词 → **短语恒无英音属预期**，UI 有则双显无则单显全缺不渲染（斜杠兼容库内带/不带两种存法）
- **记法取舍**：ipaAm（ECDICT phonetic，类 DJ）与 ipaBr（ipa-dict，真 IPA）**两类记法同屏并存不归一化**——无权威映射表且归一化破坏原数据保真；ECDICT 既有 phonetic 口音归属含糊（未标注美/英）→ **维持现状存 ipaAm 不重标注**
- **口音生效粒度（对齐 L4 下一 Segment）**：SegmentSpec 构建保持中性 EN_US（specs 每词在 loadWord 预建一次，构建期注参只能下一词生效）→ 改**消费时映射**：`playSegmentAt` 每段经纯函数 `withEnglishAccent(accent)` 懒读 `settings.ttsAccent` 重写英文段语言（中文段不动），天然达成下一 Segment 生效；currentSpec/state/speakRequest 全走映射后 spec（单一映射点）。`Lang.EN_GB` 仅为口音映射目标（TtsLocales → Locale.UK），段规格与持久化不落 EN_GB
- **设置键**：`settings.ttsAccent` L6 加法扩展——缺键默认 EN_US、损坏抛 RepositoryValidationException、ZH_CN 非口音选项拒写
- **en-GB 语音缺失回退**：口音是用户偏好而非双语硬要求——setLanguage(UK) LANG_MISSING_DATA/NOT_SUPPORTED → **回退 setLanguage(US) 继续播，不进 Paused(error)**；回退也缺失才硬失败。zh-CN 缺失仍硬失败（TC-AE-21 语义不变）
- **音色口音正交（FR-19 交互）**：已选音色仅在 `voice.locale == 段语言 locale` 时应用（setVoice），不一致走 setLanguage（失效回退语义自然覆盖）；availableVoices(EN_GB) **精确国家过滤**（country=="GB"——FR-19 语言级过滤测不出英音缺席）→ 空列表 = 设备无真英音 → 设置页「设备未安装英音语音，将回退美音」提示（枚举完成前置位避免闪现）
- **种子**：59 词 ipaBr 全带（类 DJ BrE 风格与既有美音一致；手工归一 ipa-dict——其 ɐ 记法与重读标注差异不照搬，5 词 ipa-dict 未收录者手工转写）；subject 顺带补漏存的 ipaAm

**测试**：jvmTest +7（SeedImporterTest 回填组 7→9：英音只补空缺不覆盖幂等 / lookup 端到端英音缺失自动补 + 种子 59 词 ipaBr 全带契约断言；SegmentBuilderTest +2 `withEnglishAccent` 映射（EN_GB 只改英文段语言、缺省与 ZH_CN 原样）；PlaybackOrchestratorStateTest +1 开局英音六段语言逐段断言 + 窗口中切回美音下一英文段生效；LearningSettingsRepositoryTest +2 往返拒写/损坏抛，缺省与重启往返两既有用例扩断言）+ app 单测 +3（TtsLocales EN_GB→UK / VM 口音缺省切换即时持久化 / 英音缺失提示随口音与设备音色联动）+ androidTest 设置口音冒烟（切英音 → KV 含 EN_GB）。**全门禁绿**：jvmTest / testDebugUnitTest×2 / detekt×2 / checkPlatformBoundaries / assembleDebug×2；en-GB→en-US 回退在 TtsSpeechSynthesizer 逻辑注释锁定（同 FR-19 失效回退口径，引擎差异不设真机自动化）

**状态**：已实现全门禁绿；commit 待用户确认；vivo 装机验证待执行（双音标显示 / 口音切换朗读与生效粒度 / 资产 v5 旧缓存重拷 / 回填幂等 / 无英音语音设备回退美音不中断）

### ADR-011: FR-23 英文段端内实时神经 TTS（取代 v1 预录方案）

**日期**：2026-09-20（用户批准 SPEC_CHANGE_REQUEST_PIPER_TTS v2 + IMPLEMENTATION_PLAN_PIPER_RT_TTS 后执行；同日 v1 预录方案装机实证「与之前完全没有区别」证伪撤销）

**决策**：
- **v1 预录证伪与根因**：预录 4999 高频词资产装机后用户听感零变化——预录管线与实际播放内容的**覆盖错位**（命不中即静默回退系统 TTS），且预录天然无法覆盖长尾词与英文释义 / 例句任意文本。教训：**TTS 覆盖面必须与播放内容全集对齐，任意文本只有实时合成能做到**。v1 全部代码与资产未提交，全量回退（git checkout + 删除资产，零残留）
- **v2 实时合成选型**：sherpa-onnx v1.13.8（Apache-2.0，官方无 Maven Central 工件 → **vendored Kotlin API 源码 + jniLibs .so 从官方 release tarball 入库**，`tools/tts-neural/fetch_deps.py` 可再生产出）+ Piper VITS medium 模型 en_US lessac / en_GB alan 各 ~63MB（MIT）**打包 app assets——模型 / tokens 单文件直读；espeak-ng-data 目录树一次性解包至 filesDir**（装机实证修正：native piper-phonemize 只认真实文件系统路径，传 assets 路径 → phonemize 失败 → native `exit(-1)` 拖崩全进程；SCR 的「assets 直读免解包」仅对单文件成立）；espeak-ng-data 两模型逐字节相同（sha256 实证）→ 共享一份；APK 196MB → ~324MB（用户批准）。备选 fp16 模型（体积减半、音质损）留作未来选项
- **路由架构**：`LangRoutedSpeechSynthesizer`（commonMain 组合实现，app 装配 `SpeechSynthesizer` 唯一绑定）——EN_US / EN_GB → `SherpaOnnxSpeechSynthesizer` 神经 actual；ZH_CN → `TtsSpeechSynthesizer` 系统 actual 零变化。**SpeechSynthesizer 端口契约零改动**（编排器 / 会话零感知）
- **口音 = 模型**：FR-22 消费时映射不变（段 lang=EN_GB → 英音模型）——**无厂商英音包设备（vivo）也生效**，用户根诉求解决；同时最多驻留一个英文模型（双模型 RAM 峰值不可接受），换口音 = 先载新成功后 release 旧；生效粒度仍为下一英文 Segment
- **合成管线**（装机实证两处修正）：整段 `generate` 一次产出 PCM 分块直写 AudioTrack（STREAM、阻塞写即背压、**阻塞写在 IO 线程**——编排器经 Main 调 speak；**轨道缓冲 16KB + 不足一缓冲的微型段补静音至超缓冲**——vivo mixer 对未填满缓冲 / 无阻塞写的轨道不启动消费，<缓冲段（如单词拼读 0.6s）全静音、audio_flinger `Flushed=全部帧` 实证）；流式回调方案弃用：native `CallCallback` 按精确签名 `invoke([F)Ljava/lang/Integer;` 查回调，Kotlin lambda 经 kotlinc invokedynamic + D8 脱糖只剩擦除签名 → `NoSuchMethodError` 全进程崩；该桥只能以 Java 源声明而 KMP androidMain 不支持 Kotlin→Java 同模块联合编译（kotlinc 先于 javac，无前向解析）——vivo 装机实证；stop 期在途合成后台自然完成仅浪费少量 CPU；float → 16-bit LE 单声道统一播放与缓存管线；完成判定 = 播放头追平已写样本；暂停 / 恢复 ADR-09 段语义（stop 立停、completed=false、恢复重读整段）；rate → speed clamp 0.5–2.0，**pitch 不生效（VITS 无基频参数，平台限制——无 UI 暴露面）**
- **磁盘缓存**：`filesDir/tts-cache/{sha256(lang|speed@2位|text)}.wav`（自写 44 字节头，只读写自家缓存），命中免合成整段直播；LRU（mtime）上限 100MB（构造时 + 写后清理）；缓存是优化非正确性，写失败仅记忽略
- **降级语义**：神经非 READY（INITIALIZING / UNAVAILABLE 粘滞）或 speak 非取消异常 → **EN 段当场回退系统 TTS 同段播完** + 进程内粘滞（模型缺席下行为 = 全系统 TTS，会话不中断）；**取消红线**：`CancellationException` 照常重抛绝不降级（Vosk 取消被吞成降级的 vivo 回归先例）；readiness 对 UI 投影系统引擎（既有界面零变化）
- **线程模型**：native 合成与模型换载串行于专用单线程 dispatcher（OfflineTts 单实例非并发安全）+ speak 互斥；AudioTrack / 焦点每段自建自毁，无跨段状态

**测试**：jvmTest `LangRoutedSpeechSynthesizerTest` 路由组 9（TC-AE-30：路由表 / 非 READY 走系统 / READY 英文零系统调用 / 异常同段回退 + 粘滞 / 取消重抛不降级 / stop 双转发 / 音色联动 / readiness 投影）。**全门禁绿**：jvmTest / testDebugUnitTest / detekt×2 / checkPlatformBoundaries / assembleDebug（APK 324MB，内容验证含双模型 + 355 espeak 文件 + 4 个 .so）。神经 actual 平台管线不设模拟器自动化 = vivo 装机走查

**状态**：✅ 已交付——vivo 装机验收通过（用户确认 2026-09-20），commit `e38c2a2`

### ADR-012: FR-24 中文段端内实时神经 TTS（kokoro int8 双模型驻留）

**日期**：2026-09-20（用户批准 SPEC_CHANGE_REQUEST_NEURAL_ZH_TTS + IMPLEMENTATION_PLAN_NEURAL_ZH_TTS（选型 kokoro int8）后执行）

**决策**：
- **选型**：kokoro-int8-multi-lang-v1_1（hexgrad Kokoro-82M-v1.1-zh，Apache-2.0，sherpa-onnx 官方转换）——2025 StyleTTS2 系、开源端内 zh 质量第一梯队；140MB 级体积 < melo 159MB 且**多说话人**（103 sid：zf 女 3-57 / zm 男 58-102——FR-19 中文音色可暴露，本批精选 4 枚）；int8 量化损失轻微、fp32 347.9MB 排除；**备胎 vits-melo-tts-zh_en**（VITS 路径一行 config 差异，fetch_deps 层切换代码零改动）；fanchen / matcha 质量与「去机械感」目标冲突排除。装机实证纪律：听感 / RTF 不达标 → 换 melo 复验
- **双模型驻留（ADR-011「单模型驻留」裁决按语言族细化）**：piper 槽（EN 双口音，换载语义不变）+ kokoro 槽（ZH 单模型）各自独立单线程 dispatcher——**预热并行、合成互不阻塞**（EN↔ZH 交替段是词循环常态）；跨族不再互斥；RAM 由装机 PSS 实证裁决，超预期则 ZH 改懒加载
- **kokoro 数据布局（FR-23 解包结论推广）**：模型 / voices / tokens 单文件 assets 直读（piper 实证路径）；espeak-ng-data / jieba dict 目录树 + 双 lexicon（us-en,zh 逗号列表）+ 3 rule fst（date / number / phone-zh）解包至 filesDir `tts/kokoro-data/`（完成标记 `<target>.complete` 邻侧统一，单文件与目录树同口径）——native 对目录树与逗号列表只认真实路径（ruleFsts 的 kaldifst 读取无 assets 回退）；gb-en 词典不随包（EN 段由 piper 承接，ZH 段拉丁词 us-en 覆盖）
- **中文音色（sid）**：`settings.ttsVoiceZh`（FR-19 键复用，**设置页零改动**）→ 精选 4 枚（zf_001(3) 女·缺省 / zf_051(33) 女 / zm_009(58) 男 / zm_068(91) 男，官方 v1.1-zh sid 表）；**sid 进 WAV 缓存键**（切换音色不播旧缓存）；rate → speed 同 EN；pitch 不生效（两模型均无基频参数）
- **readiness 与降级**：双预热落定后任一成功 READY / 双败 UNAVAILABLE（INITIALIZING 期全部段先走系统）；失败槽进程内粘滞（speak 直接抛）；路由器 ZH_CN 分支与 EN 同构（非 READY / 非取消异常 → 当场回退系统 + **每语言族**进程内粘滞，单模型故障不牵连另一族）；取消重抛红线不变
- **采样率按模型**：piper 22050 / kokoro 24000——采样率取自 OfflineTts 实例并随 WAV 缓存头往返，AudioTrack 按段构建（缓存与播放管线本就参数化，零结构改动）
- **体积**：assets `tts/kokoro/` 208.9MB（jieba 树排除脚本 / 文档），APK ~324MB → ~500MB（SCR 预估 465MB 按 tar 压缩体积；实际按解压后 assets 计，用户已批准档位）

**测试**：jvmTest `LangRoutedSpeechSynthesizerTest` 13（TC-AE-30 EN 回归 8 + TC-AE-31 ZH 族 5；实测 13/13 绿 2026-09-20 晚）；门禁：jvmTest / testDebugUnitTest / detekt×2 / checkPlatformBoundaries / assembleDebug。kokoro actual（sid / jieba / 24kHz / 双预热 / PSS）= vivo 装机走查

**附录：kokoro 人耳否决 → melo 备胎切换（2026-09-20 晚）**：

- **kokoro 批装机实证完成度**：门禁全绿（jvmTest 41 类 315/0，ImportPerfTest 按机器级故障先例排除——证据「bug list.md」第五/六签名）；装机坑 #5 修复后 kokoro 正常出声（diag `kokoro loaded speakers=103 rate=24000` / `prewarm en=true zh=true result=READY ms=2349` / PSS 488MB 双模型驻留稳定）。装机坑 #5：vendored `OfflineTts(assetManager≠null)` 时 kokoro 的 lexicon 逗号列表读取器一律走 assets 分支（「absolute path + assetManager NOT set to null」告警），绝对路径读失败 → native `exit(-1)` 拖崩全进程（EXIT_SELF status=255）→ **ZH 槽整槽 newFromFile**（全输入解包 filesDir）；piper 的 espeak 读取器无此问题，EN 槽保持 newFromAsset。**v1.20 的「kokoro 三大件 assets 直读」布局被证伪废止**
- **人耳判定否决**：用户判定中文听感「还不如上一版」（系统 TTS）——质量目标未达，触发本 ADR「听感 / RTF 不达标 → 换 melo 复验」预授权条款，无需新 SCR
- **melo 切换**：ZH 槽 = vits-melo-tts-zh_en（MyShell MeloTTS 官方转换，MIT；fp32 model 170.4MB——tarball 内 model.int8.onnx 为 git-lfs 指针桩不可用；44.1kHz；zh+en 混合 lexicon；jieba dict + date/number/phone ruleFsts + new_heteronym ruleFars 多音字）。数据布局沿袭装机坑 #5 教训**整槽 newFromFile** 解包 filesDir `tts/melo-data/`；中文音色 4 sid → **单音色**（模型单说话人平台限制；FR-19 键复用 / 未知值回缺省不变；voice id 进缓存键，换模型键空间天然隔离）；设备遗留 `tts/kokoro-data`（~168MB）启动后台回收
- **melo 批实测（门禁）**：assets `tts/melo` 191.2MB / APK 464.7MB / jvmTest 41 类 315/0 + testDebugUnitTest + detekt×2 + checkPlatformBoundaries + assembleDebug 全绿（路由组 13 例零改动——引擎无关）；44.1kHz 与预热 RTF 以装机 diag 实测为准
- **若 melo 人耳仍不达**：端内开源可选已尽第一梯队（kokoro 否决 / melo 待验 / matcha·fanchen 质量冲突 / zipvoice-distill-int8 109MB 新架构未验证）——届时向用户呈三选项（zipvoice 试装 / 云 TTS 立项 / 维持系统 TTS 撤 FR-24 神经部分），不再自行迭代

**状态**：kokoro 批装机人耳否决；已按预授权备胎切换 melo（见附录）并全门禁绿；vivo 装机二轮人耳验收待执行（中文自然度 / 英文零变化 / 首播延迟与缓存 / 降级模拟 / 双预热 / PSS）

---

### SCR-AUDIOTRIM: 视频导入例句音频 BGM 前奏裁剪（2026-09-25）

**问题**：巫师三批次例句音频读句前纯 BGM 前奏过长（clutch 实测 22.4s 片段语音 11.2s 才开始）。
**根因**：word_matcher 直接用 Whisper segment 边界剪切；持续 BGM 下 segment start 远早于首词。
**决策**（用户 2026-09-25 批准，方案见 SPEC_CHANGE_REQUEST_AUDIO_TRIM.md）：
- 工具修复：speech_recognizer 开启 word_timestamps=True 并在 JSON 输出 words；word_matcher 剪切边界改为词级（首词−0.25s → 末词+0.5s，speech_bounds_from_words），无词级回退 segment 边界（SRT 路径不变）
- 既有批次：一次性脚本 workspace/retrim_audio.py（whisper 重跑 + difflib 对齐 data.json 句子，相似度 0.98–1.00）只重裁 6 条音频，data.json 逐字节不变；APK 内 asset:// 直读，装新包即生效无需重导入
**验收**：新 clutch 10.3s / precipice 10.2s 等，whisper 复核语音起点 0.0s；APK 重建（648MB）并归档 workspace/output/VocabularyBooster_2026-09-25_175500_001.apk。
**附带**：shared/build 与 app/build 各出现一处「not a regular file」损坏产物（Synology 同步目录已知故障模式），停 daemon 后整目录删除重建即恢复。

### SCR-ZHNUM: ZH 段 TTS 数字读音修复（2026-09-25）

**问题**：巫师三批次 legion 中文释义段「…通常由3000至6000名步兵和100至200名骑兵组成。」数字被读成英语（"three thousand"），其余中文正常。
**根因**（三层取证）：① 视频导入 meaningCN 含半角数字；② sherpa-onnx zipvoice 前端 = `MatchaTtsLexicon`，查词路径 lexicon 词条 → 单 token → CJK 逐字，**其余非 CJK 硬编码 `voice="en-us"` 走 espeak-ng**；③ 随包 lexicon.txt 68,037 行全为单汉字拼音、无数字词条 → "3000" 落 espeak 英读（上游 ZipVoice Python 版自带数字正则化，移植未含；tokens.txt 单数字 token 仅单字符命中）；WAV 缓存按原文键放大复发。
**决策**（用户 2026-09-25 批准，方案见 SPEC_CHANGE_REQUEST_ZH_NUMBER_TTS.md）：
- commonMain 新增 `ZhTtsTextNormalizer` 纯函数（speech 包）：自由数字串 → 中文读法（整数位权 十/百/千/万/亿、组内零填充、最高位一十省一；小数点后逐位；前导零串逐位；紧邻 ASCII 字母不转——MP3/3D/1990s 保持 espeak 英读；**整数 >12 位防御性不转——较 SCR 预告的 16 位收紧：万/亿两级大单位以上（万亿级）未实现，防乱读**）；转换用字（零~十/百千万亿/点）已验证全在 lexicon 内
- androidMain `SherpaOnnxSpeechSynthesizer`：ZH_CN 段合成前归一化，归一化文本**同时进 WAV 缓存键与 generateWithConfig**——旧错误读音缓存天然失键不复用（LRU 自然淘汰）；EN 段 / 系统 TTS 降级路径 / 模型资产 / 路由器零变化
**测试**：jvmTest `ZhTtsTextNormalizerTest`（**TC-AE-32**）边界组 7 全绿；门禁 jvmTest / testDebugUnitTest / detekt×2 / checkPlatformBoundaries / assembleDebug。装机验收：vivo 重播 legion 中文段数字中读（三千/六千/一百/二百）+ 旧缓存不复发。
**门禁附记（2026-09-25）**：① 全量 jvmTest 两次机器级故障（executor 4s 崩 + 复跑挂死 20 分钟，ImportPerfTest 首类受害）→ 按 ADR-007 先例排除 ImportPerfTest 跑门禁（43 类 324/0 绿；证据「bug list.md」第七签名）；同日 shared/build 再现「not a regular file」损坏产物（Synology 同步故障模式，rm -rf 重建）。② 视频导入批（未提交、零测试覆盖）VideoImportEngine.kt 10 项既有 detekt 违规挡门禁——用户批准顺手修复：词性排序 when 表 9 魔法数 → PART_OF_SPESS_ORDER 查表（noun=1…phrase=10、未知=99 数值不变）+ 102 行 import 拆 findOrCreateWord / importEntry / nextEntryOrder / isEntryInWordBook（查询序与字段逐一等价）；app VocabularyBoosterApp.kt 宽泛 catch 按仓库 @Suppress 先例标注。detekt×2 / testDebugUnitTest / checkPlatformBoundaries / assembleDebug（618MB，含视频导入资产）全绿。

### SCR-SPELLPAUSE: 拼写段逐字母精确停顿 + 停顿时长设置（2026-09-26）

**问题**：用户报告拼写段字母连读（"l-e-g-i-o-n" 六个字母名连成一段话），要求「一个字母一顿的清晰拼读」，且停顿时长需在设置里可调。
**根因**（sherpa-onnx 源码取证）：piper 前端 `phonemize_eSpeak` 按标点切句且标点作为音素保留进 VITS token 流（模型渲染停顿帧）；**连字符只是普通词内分隔符，不产生停顿 token → 必连读**；逗号停顿时长是模型训练韵律（~0.2–0.4s 随上下文浮动），**不能精确控制**——可调停顿只能逐字母合成 + 显式静音拼接。
**决策**（用户 2026-09-26 批准，方案见 SPEC_CHANGE_REQUEST_SPELLING_PAUSE.md）：
- 拼写段文本格式 `-` → `", "`（`booster` → `b, o, o, s, t, e, r`）——未实现逐字母拼接的渲染路径靠逗号获得自然停顿（格式即优雅降级，iOS 初版直接受益）
- `SpeakRequest` 加法扩展 `letterPauseMs`；编排器仅 SPELLING 段下发 `settings.spellingPauseMs`（缺省 400ms、写校验 100–2000ms、设置页滑杆 0.1–1.0s 即时持久化），游标懒读——变更自下一拼写段生效（L4 对齐）
- 神经 actual：按 `", "` 拆字母逐个 `generate` + commonMain 纯函数 `SpellingAudioAssembler.joinWithSilence` 字母间静音拼接（毫秒精确、无首尾静音）；整段进 WAV 缓存，键增 `sp{pauseMs}` 维度（旧连读缓存因文本格式 + 停顿维度双重失键）
- **系统降级路径同步实现精确停顿（用户裁决，否决「仅逗号自然停顿」的推荐项）**：多 utterance 队列逐字母 `speak(QUEUE_ADD)` + 字母间 `playSilentUtterance`，监听器按段 id 集合匹配（末字母 onDone / 集合内任一 onError 整段失败 / onStop → completed=false；普通段单元素集合语义零变化）
**测试与门禁（2026-09-26）**：jvmTest 332/332（TC-AE-33 拼读停顿组 + `SpellingAudioAssemblerTest`；ImportPerfTest 按 ADR-007 机器级故障先例继续排除）+ :shared:testDebugUnitTest + :app:testDebugUnitTest + detekt×2（修复 SettingsScreen TooManyFunctions / SpellingAudioAssembler MagicNumber / 测试行超长三项）+ checkPlatformBoundaries + assembleDebug（648MB）全绿；门禁期间 shared/build 与 app/build 各现一次「not a regular file」Synology 同步损坏（停 daemon 整删重建恢复）、一次 executor 机器级崩溃复跑即绿。测试实现修正两处：`lettersOf` 过滤条件由非空白收紧为「含字母/数字」（撇号/连字符片段不保留）；编排器测试的拼写段期望文本同步逗号格式并补 `advanceTimeBy` 起播推进。
**装机验收（vivo，2026-09-26）**：tts_diag.log 取证逐字母链路 `speak lang=EN_US engine=vits len=22 speed=0.8 pause=400` + `generated samples=156658 rate=22050 ms=7104`（ravenous 逗号文本 22 字符；8 字母音频 + 7×400ms 拼接静音）；用户人耳验收「一字母一顿，清晰 ✓」通过。停顿滑杆变更加权未单独人耳走查——机器侧由 jvmTest TC-AE-33（变更自下一拼写段生效）与 WAV 缓存键 `sp{pauseMs}` 维度覆盖。

### 巫师三释义机翻质量修复：data.json 五条定稿 + 设备 DB 一次性手术（2026-09-26）

**问题**：用户报告 clutch 中文释义「(传递性)紧紧抓住或抓住」语义重复、未体现英文原义。审计全批 6 条：5 条机翻质量问题（clutch 同义重复；legion 标签直译且定义欠完整；siege / rabid / ravenous 欠精确，其中 ravenous 英文释义本身以 "s extremely…" 截断开头）；precipice 无问题。
**根因**：`tools/video_importer/translator.py` 用 MyMemory 免费 MT（en→zh-CN）机翻——无括号标签（transitive/by extension 等）与术语处理，机翻文本未人工复核直接进 data.json。
**决策**（用户 2026-09-26 批准「改全部五条 + 设备 DB 修复」）：
- data.json 五条人工定稿（version 1→2）：clutch「（及物）紧紧抓住；攥住。」；legion 军团定义整句（3000–6000 步兵 / 100–200 骑兵）；siege「围攻；围困（包围要塞、孤立守军并持续攻击）。」；rabid「（引申）狂怒的；狂暴的；极其激烈的。」；ravenous 英文补全 +「极其饥饿的；狼吞虎咽的。」；precipice 用户明示不动。APK 重打包随资产分发（对已导入设备惰性，仅新装/重导生效）
- **设备 DB 走一次性手术而非删本重导**：VideoImportEngine 跳过已在词条目且 Word 行复用——修 data.json 不自愈，删本重导会产生重复 DefinitionEntry；force-stop → run-as 拉库 → sqlite 定点 UPDATE（wordId + 旧释义精确匹配；5 条 CN + 1 条 EN，各 rowcount=1；掌握 / 会话 / 勾选行零触碰）→ push 回写 → 重启。UI 逐条核验五条全部生效（clutch/legion/siege/rabid/ravenous 编辑弹层文案即新释义）
- TTS WAV 缓存按原文键 → 新释义自动失键重新合成；legion 数字段经 SCR-ZHNUM 归一化读「三千 / 六千 / 一百 / 二百」
**遗留缺口（未来 SCR 候选，未立项）**：已导入批次无数据刷新路径——导出侧修正后无增量更新既有行的机制，本次以一次性 DB 手术兜底；根治需导入更新语义（按 entry 版本 diff 或强制刷新入口）。

### SCR-SENTMERGE: 视频导入跨 segment 句子合并 + legion/siege 例句截断修复（2026-09-26）

**问题**：用户报告 legion 例句读完 "…laid siege to every fortress" 即截断（视频原句后面还有内容）；siege 词条共用同句同样截断。
**根因**：Whisper VAD 在 28.62s 把一句旁白劈成两个紧邻 segment（seg1 无句末标点 + seg2 紧邻续接 "from here to the Blue Mountains…"）；`extract_sentences_for_words` 以 segment 为不可分句子单元，无跨段合并 → 文本与音频都断在 fortress。SCR-AUDIOTRIM 只修了段内 BGM 边界，未覆盖段间断裂。
**决策**（用户 2026-09-26 批准，例句边界用户裁决「整句到 away.」）：
- 工具：`word_matcher.merge_continuation_segments`——提取前预合并（上一段无句末标点 `.?!` 且当前段 start ≤ 上段 end + 1.0s → 合并文本/词表/时间跨度；浅拷贝不改调用方）；冒烟验证 6 词全部正确命中、precipice 边界无回归、`rabbit→rabid` 修正链路不变
- data.json v2→v3：legion + siege 例句整句（"…every fortress from here to the Blue Mountains, rabid and ravenous he bites and bites away."）+ 译文定稿（顺带修复「皇帝皇帝」重复机翻）；rabid/ravenous 词条例句保持现状（用户批准不动项）
- 音频重裁：legion_0 / siege_0.mp3 按词级边界 21.87–35.14（whisper 缓存复用，未重跑识别）7.2s → 13.3s；`asset://` 路径不变，重打包即生效
- 设备 DB 手术：Example 表 2 行（exampleId=2/3，sentence 精确匹配 + 同 UPDATE 改 chineseTranslation，rowcount=2 验证）
**验收**：门禁全绿（jvmTest 对 commonMain 无变化 up-to-date 合法；SENTMERGE 零 Kotlin 改动）；装机 UI 例句整句上屏 ✓；例句音频与暂停响应人耳验收交接用户。

### SCR-BUTTONLAG: 学习会话按钮反馈卡顿——神经 TTS 写入循环取消不感知（2026-09-26）

**问题**：用户报告学习会话继续/暂停、退出等按键反馈卡顿不丝滑。
**根因**（两层）：① 主因——`SherpaOnnxSpeechSynthesizer.speak()` 的 PCM 写入循环以 1× 实时速率逐 16KB 块喂 AudioTrack 流缓冲，循环只认 `stopRequested`（仅 `stop()` 置位）不感知协程取消，`AudioTrack.write(BLOCKING)` 不可打断；而编排器五控制（pause/next/会了/exit）均为「先 `cancelAndJoin` 再 `synthesizer.stop()`」（防 stop 后 speak 正常返回继续推进下一词的竞态，不可反转）→ TTS 段播放中点暂停要等写入喂完整段剩余 PCM（长中文段 5–15s），期间声音照播、状态不切。例句文件段（Media3 CE 处理立即 pause）与系统 TTS（CE → tts.stop()）路径无此问题。② 次因——编排器 scope = Main.immediate，speak() 主体在主线程做 readCache（~1MB 文件读）与 toPcm16Le（~50 万样本循环）→ 逐词多段推进主线程反复磁盘 IO 丢帧。
**决策**（用户 2026-09-26 批准，纯 androidMain actual 内部改动，端口/编排器/语义零变化）：
- 写入循环与补静音条件追加 `isActive`（withContext 块内 CoroutineScope.isActive）；取消后 CE 于 withContext 出口抛出 → 既有 finally（track pause/flush/release）即刻停声；`cancelAndJoin` 最坏等一个 16KB 块（≤0.4s）
- readCache 与 toPcm16Le 包 withContext(Dispatchers.IO) 挪出主线程；diag 同步小追加保留（取证优先）
- 已知边界（记录不修）：cache-miss 首播的生成期（native generate 不可中断）点暂停仍受 RTF 限制（秒级、仅首播窗口，缓存后不复现）
**验收**：门禁全绿（shared jvmTest/testDebugUnitTest + app testDebugUnitTest + detekt×2 + checkPlatformBoundaries + assembleDebug 648MB；门禁期 shared/build 与 app/build 各现一次「not a regular file」Synology 同步损坏，停 daemon 整删重建）；vivo 装机取证（2026-09-26）：拼读段 7.1s 音频写入进行到 ~4.5s 处点暂停 → **575ms** 内 stop invoked、无 write/drain 收尾行（写入中途被掐、立即静音）——修复前同场景需等剩余音频全部喂完。人耳验收（连续暂停/继续/退出跟手）交接用户。

### SCR-PREVWORD: 学习会话「上一个」控制（2026-09-26）

**问题**：学习会话只有前向导航（「下一个」），错过刚播的词只能等组内循环绕回或「重播」当前词，无法立即回听上一个词。
**根因**：控制集自 Phase 4 定型为 Play/Pause/Resume/Next/Replay/Exit（FR-11），「上一个」在代码与全部规格中不存在——全新控制而非缺陷，走 SCR 流程。引擎位置 = 持久化单一 `PLAYING` 行，previous 可从 (groupIndex, orderInGroup, status) 纯推导，**无 DB 迁移、无新设置**。
**决策**（用户 2026-09-26 批准，方案见 SPEC_CHANGE_REQUEST_PREV_WORD.md，8 条冻结语义）：
- 引擎 `previous(sessionId)` = advance 的镜像：当前组内 `orderInGroup` 降序取最近**未掌握**前驱（跳 MASTERED）；组首回绕组内最后一个未掌握词（镜像正向组内循环，**按钮永远有效无禁用态**）；恒组内（会话只停在最小未掌握组、更早组必然全掌握的不变量 ⇒ `completedGroupIndex` 恒 null）；纯导航零掌握写入；唯一未掌握词回绕自身（播放层等效重播）；无 PLAYING 位取组内最大；终态幂等只读；复用 `AdvanceResult` 不新建密封类型
- 编排器 `previous()` 镜像 `next()`（`cancelStep()` → `stopPorts()` 顺序保持 SCR-BUTTONLAG 裁决不可反转）；`advanceAndAdopt` 参数化为 `jumpAndAdopt`（advance/previous 双变体共用）；窗口作废/词卡刷新/VM 转发全走既有路径
- UI 底栏双行（用户裁决）：行 1「上一个/下一个/重播」、行 2「暂停(继续)+退出」，两行 SpaceEvenly；既有 testTag 全保留
- v1 仅按钮，不加语音命令（预留命令表不动）
**测试**（TC-AE-34，三段）：commonTest `LearningEnginePreviousTest` 11 用例（镜像裁决全表）+ jvmTest StateTest 5 用例（纯跳转/跳过已掌握/窗口作废/Paused 起跳/单词自回绕）+ RestartTest previous 镜像（L3 双匹配钉死）+ app VM J2 转发用例；终态重入风暴补 `previous()`；接口加方法致两处手写 Fake（RecordingEngine / FakeLearningEngine）同批 override。
**验收**：门禁 jvmTest 348/349 + testDebugUnitTest ×2 + detekt ×2 + checkPlatformBoundaries + assembleDebug（648MB）全绿——唯一失败 ImportPerfTest 机器级故障窗口再现（同日两跑两种形态：JDBC NPE → SQLITE 语法错误，非确定性且本批零 import 代码，按 ADR-007 先例排除）；门禁修复三项 detekt（DefaultLearningEngine TooManyFunctions 12>11 加 @Suppress 标注 + 测试两行超长缩注）；assembleDebug 首跑 mergeDebugResources 再现「not a regular file」Synology 同步损坏（停 daemon + rm -rf app/build 重建即恢复，先例路径）。测试实现修正两处场景构造：previousSelectsNearestUnmastered / newEngineInstanceContinuesPrevious 原构造掌握全部前驱（实际是组首回绕场景，实现行为正确）——改为纯跳转留未掌握前驱形态。装机走查（vivo，2026-09-26）：双行控制条布局上屏 ✓、组首回绕 clutch→precipice 自 seg0 起播 ✓、词卡随换词刷新 ✓（均截图取证）；窗口/暂停态「上一个」行为与回绕体感人耳验收交接用户。
