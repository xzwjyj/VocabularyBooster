# PHASE 2 验收报告 — 数据层 + 生词本基础

> 日期：2026-09-03 ｜ 状态：**待用户验收**
> 上游：ROADMAP Phase 2 ｜ 批准记录：用户 Phase 2 批准（Objective + 不可协商领域模型 + 乱序排序必测 + 24 项 DoD + 阶段边界）
> 前置基线：commit `28cc991`（Phase 1）
> 结论：**全部 DoD 项达成（24/24）**。测试 34 次执行 0 失败（jvmTest 24 + commonTest/Android 6 + app 单测 1 + 仪器测试 3）；Android 构建、双 detekt、平台边界门禁全绿；数据链 Word → DefinitionEntry → Example → WordBook 全程贯通并持久化。

---

## 1. 本阶段范围（严格对齐批准书）

**做了**：SQLDelight schema v2（FR-5 粒度细化迁移）、领域模型 + 映射、WordRepository / WordBookRepository、SaveRequestValidator、种子词库（JSON，59 词）与幂等导入器、查词 UI（搜索 → 词条详情 POS 分组）、生词本 CRUD UI、保存流（选本 + 勾释义 + 逐例句勾选）、四层测试（Domain / Repository / Integration / UI）。

**未做（按批准书排除）**：Dictionary API、AI API、TTS、SpeechRecognizer、Media3 播放、真实版权音频、SRS、勋章引擎。本地种子是唯一词典数据源。

## 2. 需求变更先行（Phase 2 批准前完成）

FR-5 收藏粒度由「DefinitionEntry + includeExamples 开关」细化为「DefinitionEntry + 逐 Example 勾选」（PROJECT_SPEC v1.3 / DOMAIN_MODEL v1.2 / DATABASE_SCHEMA v1.3 / TEST_PLAN v1.2 同步更新），落库为 schema v2 新表 `WordBookEntryExampleSelection`（`wordBookEntryId, exampleId` 复合主键），`WordBookEntryDefinition` 重建去掉 `includeExamples` 列。v1→v2 迁移 `1.sqm` + 专项迁移测试（TC-DB-06）。v1 从未发布，`includeExamples=0` 语义在 v2 无对应表达，迁移不伪造（详见 DATABASE_SCHEMA §5）。

## 3. 数据层（shared，平台无关）

```
shared/src/commonMain/kotlin/com/vocabularybooster/
  domain/model/Word.kt          # Word / DefinitionEntry / Example / ExampleSourceType
                                 #   / WordDetail / PartOfSpeechGroup / groupedByPartOfSpeech()
  domain/model/WordBook.kt      # WordBookType / WordBook / WordBookSummary / WordBookWord
                                 #   / DefinitionSelection / SaveWordRequest
  domain/model/WordText.kt      # toNormalizedWordText()（trim + lowercase）
  domain/dictionary/            # DictionaryProvider 端口 + 种子 DTO（@Serializable）
  domain/repository/            # WordRepository / WordBookRepository 接口 + 仓储异常
  data/Mappers.kt               # 行 → 领域对象（internal）
  data/SqlDelightWordRepository.kt      # lookup（Q1 全联查）/ search
  data/SqlDelightWordBookRepository.kt  # CRUD / saveWordToBooks / 删除守卫 / observe Flow
  data/SaveRequestValidator.kt  # 事务内校验：词存在、本存在、释义∈词、例句∈所选释义
  data/seed/                    # SeedDictionaryProvider（JSON 解析）/ SeedImporter（幂等单事务）
                                 #   / SeedData.kt（59 词种子）
  di/DataModule.kt              # sharedDataModule（Koin）
```

要点：
- Repository API 由 Domain 需要决定，UI 不触 SQLDelight（铁律 2）；全部接口 platform-independent（commonMain，铁律 1 门禁绿）。
- `lookup` 用 Q1 单查询全联查（Word + 全部 DefinitionEntry + 全部 Example），映射层组装 `WordDetail.examplesByEntryId`；`groupedByPartOfSpeech()` 按 `(partOfSpeechOrder, definitionOrder)` 排序并保证同 POS 连续分组——**展示顺序永不依赖插入顺序**。
- `saveWordToBooks`：一次请求可将词加入多个本；重复保存走「删旧关系行重插」以替换选择集（级联清旧例句选择），`entryOrder` 保持不变；整个保存 + 校验在单事务内，失败原子回滚（无半保存状态，测试锁定）。
- 删除守卫：有 BOOK_COMPLETED 勋章或派生子本的 ORIGINAL 本不可删（`WordBookDeletionException`）；普通删除只删本及关系行（级联），**Word 永不级联删除**（测试锁定）。
- 种子导入 `ensureSeeded()`：库空才导，单事务，按 normalizedText 复用已存在词（幂等，测试锁定）；App 启动后台执行（runCatching 兜底）。

## 4. 种子词库（59 词）

- 覆盖契约（commonTest `SeedDictionaryCoverageTest` 自动锁定）：≥50 词、normalizedText 唯一、多词性词 ≥5（实际 8+）、同词性多释义组 ≥10、≥2 例句释义 ≥10、**每条释义 ≥1 例句**、词性白名单校验。
- 多词性样例：boost（verb×2 + noun）、brief、capture、cease、flourish、resolve、subject、deliberate…
- 例句来源：TTS（自拟）为主 + LICENSED_OTHER 公版（《傲慢与偏见》开篇、《爱玛》、KJV 林后 12:9），后者强制带 `sourceRef + licenseNote`（测试锁定），为 Phase 5 真实音频的合规位留形。

## 5. UI（app 层，Compose，刻意简朴）

- **查词页**：搜索框（前缀联想，短词优先）→ 结果行 → **词条详情**：词头 + 音标，POS 分组标题（大写）→ 释义块（**MeaningEN 在前**、MeaningCN 随后、例句 = 句子 → 中文译文 → 来源标签）。铁律 4 顺序在 UI 侧直接消费 `groupedByPartOfSpeech()`。
- **生词本页**：列表（词数 + 派生标记）、新建 / 重命名 / 删除（确认对话框，明示「词库中的单词不受影响」）、本详情（词条列表 + 移除）。
- **保存流**（FR-5 v1.3）：详情页「添加到生词本」→ 对话框：多选已有本 / 新建即选 → 勾 DefinitionEntry → 已勾释义展开逐 Example 勾选（一条不选 = 只存释义）→ 保存；校验失败显示错误信息且不落任何半状态。
- 双 Tab 导航（查词 / 生词本），详情页覆盖层进出。ViewModel 只做委托（ADR-06），业务零下沉 app 层。

## 6. 测试记录（34 执行 / 0 失败）

| 层 | 套件 | 数量 | 覆盖 |
|---|---|---|---|
| shared jvmTest | `PartOfSpeechOrderingTest` | 1 | **批准书必测**：乱序插入 {noun#2, verb#3, noun#1, verb#1, adj#1, verb#2} → 断言全序列 verb#1,2,3 → noun#1,2 → adj#1 + 分组连续性 |
| | `WordRepositoryTest` | 3 | 大小写/空白 lookup、Q1 顺序 vs 插入顺序、来源映射、未知词 null、search 前缀/短词优先/limit/空串 |
| | `WordBookRepositoryTest` | 7 | CRUD（trim/空名拒绝/改名缺失拒绝/observe 首值）、分本独立选择集、校验失败原子性、重保存替换选择集且保 entryOrder、**多本共享 + 删 A 留 B + Word 不误删 + 移除词清掌握**、删除守卫（勋章/派生拒绝、普通删除成功）、**持久化 close/reopen（文件库）** |
| | `SchemaSmokeTest` | 3 | 12 表冒烟（含例句选择 INSERT OR IGNORE 幂等）、Q2/Q3、Q5 派生复制（选择集复制、掌握不继承、母本不动） |
| | `MigrationV1ToV2Test` | 1 | v1 手抄 DDL 建库 → 填 v1 数据 → `Schema.migrate(1,2)` → v2 重开断言数据保留 |
| | `SeedImporterTest` | 3 | 导入一次 / 幂等复用 / ensureSeeded 空库才导；经 Repository 用真种子 lookup；provider 可插拔 |
| | `WordDetailGroupingTest`* | 2 | 分组连续性 + 空释义 |
| | `SeedDictionaryCoverageTest`* | 2 | §4 覆盖契约 + provider 大小写命中 |
| | Koin/Serialization 冒烟 | 4 | 装配与序列化 |
| app 仪器 | `LaunchSmokeTest` | 1 | 冷启动不崩（Koin 全量装配） |
| | `Phase2UiFlowTest` | 2 | 搜索→详情：VERB 组先于 NOUN、EN/CN 文本呈现；新建本→勾 verb#1→只勾第二条例句→保存→DB 断言释义选择恰 1 条、例句选择恰为所勾 |
| app 单测 | `AppModuleSmokeTest` | 1 | appModule 装配 |

\* commonTest（Android 目标以 testDebugUnitTest 复跑，6 执行）。

**完整门禁链（2026-09-03 全绿）**：`:shared:jvmTest` 24/0 ｜ `:shared:testDebugUnitTest` 6/0 ｜ `:app:testDebugUnitTest` 1/0 ｜ `:app:connectedDebugAndroidTest` 3/0（AVD vb_phase1 实机） ｜ `:shared:detekt` + `:app:detekt` ｜ `:shared:checkPlatformBoundaries` ｜ `:app:assembleDebug`。

## 7. 过程问题与修复（留档）

1. **JDBC 文件库 `last_insert_rowid()` 返回 0**（仅 JVM 文件驱动）：SQLDelight `JdbcSqliteDriver` 文件 URL 走 `ThreadedConnectionManager`，每操作新连接，插入后另开连接读 rowid 必为 0（IN_MEMORY 单连接不受影响；Android 驱动单连接不受影响）。修复：插入 + rowid 读取包进同一事务（`createWordBook`、测试 `seedWord`）。根因经 javap 反查驱动源确认，已在 DATABASE_SCHEMA §4 事务说明中记录。
2. **仪器测试进程崩溃 → `NoDefinitionFoundException: LookupViewModel`**：`koinViewModel()` 需要 `viewModel { }` 定义而非 `single`。appModule 补 4 个 ViewModel 定义后通过。
3. **UI 测试选择器二义**：输入 "boost" 后 `onNodeWithText("boost")` 同时命中输入框 EditableText 与结果行 Text。修复：结果行加 `testTag("search_result_{word}")`，测试按 tag 点击。
4. **SQLDelight 2.0.2 API 陷阱**：无 `transacters` 包（`transactionWithResult` 是 `SuspendingTransacter` 接口成员）；聚合查询返回包装类需用 mapper 重载（`maxEntryOrder { it ?: -1L }`）。
5. **环境**：Gradle transform 缓存损坏（会话中断残留）→ 删除该 transform 条目即恢复；模拟器 AVD 因会话硬终止留下脏 userdata → adb 永久 offline，`-wipe-data` 后 40s 正常冷启动。两者均为本机环境问题，与工程代码无关。

## 8. DoD 逐项验收（批准书 24 项）

| # | DoD 项 | 状态 | 证据 |
|---|---|---|---|
| 1 | Seed ≥50 words | ✅ | 59 词；CoverageTest 锁定 |
| 2 | Seed contains multiple PartOfSpeech | ✅ | boost/brief/capture… 多词性；CoverageTest ≥5 |
| 3 | Seed: multiple definitions under same POS | ✅ | CoverageTest ≥10 组 |
| 4 | Seed contains multiple examples | ✅ | ≥2 例句释义 ≥10 条；每释义 ≥1 例句 |
| 5 | Word persistence works | ✅ | WordBookRepositoryTest 持久化用例（close/reopen） |
| 6 | DefinitionEntry persistence works | ✅ | 同上 + Migration 测试 |
| 7 | Example persistence works | ✅ | 同上（例句选择行级断言） |
| 8 | WordBook CRUD works | ✅ | WordBookRepositoryTest CRUD 用例 + UI 新建/改名/删除 |
| 9 | One Word belongs to multiple WordBooks | ✅ | 多本共享用例 + UI 保存流多选本 |
| 10 | WordBook deletion does not delete shared Word | ✅ | 删 A 留 B 用例（Word 行仍在） |
| 11 | DefinitionEntry selection works | ✅ | Repository 选择集用例 + Phase2UiFlowTest DB 断言 |
| 12 | Example selection works | ✅ | 同上（恰为所勾例句） |
| 13 | MeaningEN/MeaningCN inseparable | ✅ | 同一 DefinitionEntry 两字段，无独立实体；重保存整体替换 |
| 14 | PartOfSpeech ordering test passes | ✅ | PartOfSpeechOrderingTest（乱序必测） |
| 15 | Definition ordering test passes | ✅ | 同上全序列断言 + WordRepositoryTest Q1 顺序 |
| 16 | Repository tests pass | ✅ | Word/WordBook Repository 10 用例绿 |
| 17 | Integration tests pass | ✅ | 持久化 / 多本 / 删除安全 / 种子装载 |
| 18 | UI smoke tests pass | ✅ | connectedDebugAndroidTest 3/0 |
| 19 | Android build passes | ✅ | assembleDebug 绿 |
| 20 | Full test suite passes | ✅ | §6 门禁链全绿 |
| 21 | No commonMain → Android violations | ✅ | detekt ForbiddenImport + checkPlatformBoundaries + 人工 grep 三重确认 |
| 22 | Documentation updated | ✅ | PROJECT_SPEC v1.3 / DOMAIN_MODEL v1.2 / DATABASE_SCHEMA v1.3 / TEST_PLAN v1.2 / CLAUDE.md 状态行 + 本报告 + ROADMAP |
| 23 | PHASE_2_REPORT.md generated | ✅ | 本文件 |
| 24 | Git commit created | ✅ | 本报告落笔后立即提交（见 §10） |

## 9. 偏差与说明

- 无范围偏差。批准书示例 API 名（`getWord`/`searchWords`/`addWordToWordBook` 等）按「具体 API 根据实际架构决定」条款落为 `lookup`/`search`/`saveWordToBooks`（一次多本 + 选择集，语义更贴 FR-5）。
- UI 视觉按批准书刻意从简（无动画/主题打磨）。
- 删除守卫（勋章/派生本不可删）是对 D1/D2 铁律的前置防御，属 Phase 2 安全范畴，勋章引擎本身未实现。

## 10. Git

- 提交内容：本阶段全部代码 / 测试 / 文档 / 本报告（Conventional Commits，`feat(phase2)`）。
- 下一步：**等待用户验收与 Phase 3 指令**（学习引擎；不自行启动）。
