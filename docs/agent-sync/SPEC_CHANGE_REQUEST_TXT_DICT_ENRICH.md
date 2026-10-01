# SPEC_CHANGE_REQUEST — TXT 导入词典富化（SCR-TXTDICTENRICH）

> 提出日期：2026-10-01 ｜ 状态：已批准并落地（2026-10-01；D1=①全量兜底、D2 v1 不做）；**v2 词性标记过滤已落地（2026-10-01，§7；同日追补 prep. §7.5 + 短语修正 §7.6）**
> 上游需求：PROJECT_SPEC FR-14 ｜ 影响：IMPORT_SPEC / ImportEngine / ImportSession ｜ 无 DB 迁移

## 1. 问题

TXT 导入（FR-14，IMPORT_SPEC v1.1）目前只落 `Word` + `WordBookEntry.pendingTranslation`，**不建 DefinitionEntry / Example / 勾选行**。导入 hamper 后：学习会话只播拼读（LEARNING 边界 #3）、详情页只有临时译文。随包词典（FR-18，v6 资产逐释义例句全覆盖）已具备富化条件，但只在查词 miss 时按需导入——导入的词不查词就永远没有释义例句。

## 2. 现状（调研结论）

- `ImportEngine`（shared 纯 Kotlin）逐行解析 → `ImportRepository.withImportTransaction` 单一大事务内经 `ImportSession` 原子操作面写库（ImportModels.kt:51-87）；引擎层做四层去重。
- `DictionaryProvider.lookup(text): DictionaryWord?` 端口已存在（ARCHITECTURE §5；androidMain = BundledDictionaryProvider，v6 例句已逐释义归属）。
- `SeedImporter.import`：新建词时连释义+例句一起落库（复用词跳过）；`VideoImportEngine.importEntry`：词→释义→例句→本关联 + `WordBookEntryDefinition`/`WordBookEntryExampleSelection` 全选——**勾选行写法有现成先例**。
- 查询侧 diff（SCR-SENSEATTR）：二次 lookup 幂等、勾选行不删——富化后再次查词安全。

## 3. 方案（冻结候选）

导入执行期，对**每条通过去重、将新建本条目**的行，查词典富化；词典未收录 → 保持现状（仅 pendingTranslation）。

### 3.1 两级富化规则

| 行形态 | 规则 |
|---|---|
| 裸词行（无译文） | 词典命中 → 该词**全部**中英文释义 + 全部例句入本（全部勾选）；`pendingTranslation = null` |
| 带译文行 | 词典命中 → 译文与词典释义**匹配**（§3.2）→ 仅匹配释义 + 其全部例句入本（勾选）；`pendingTranslation = null` |

### 3.2 释义匹配（SenseMatcher，shared 纯函数，确定性）

- 双方归一：trim + lowercase；分隔符切分 `，, 、;；/ 空格`——用户译文串、词典 `meaningCN` 与 `meaningEN` 都切 token；
- 命中 = 任一用户 token 与任一词典 token **相等或互含**（"妨碍" ⊆ "妨碍, 阻碍"）；中英两侧都参与匹配；
- ≥1 释义命中 → 取命中集合；**0 命中 → 兜底全量**（与裸词同，见 §5 决策点 D1）。

### 3.3 词已存在时的复用（不重复建数据，镜像 FR-5 复用原则）

- 词已存在**且有释义**（查词/视频/种子导入过）：不建新释义例句，在**既有行**上按 §3.1/§3.2 勾选；
- 词已存在**且无释义**（TXT 时代旧行）：先按词典补齐释义+例句（同时只补空缺 IPA，同 backfill 规则），再勾选；
- 本内已存在（`duplicatesInBook`）：**不动勾选**，仅维持 v1.1 补译文行为（宁不动）。

### 3.4 落点与边界

- 富化发生在 `ImportEngine` 行循环内：provider lookup（只读词典资产库，与 vocabulary.db 事务无锁交互）→ 新增 `ImportSession` 操作 `importDictionaryDefinitions` + `insertEntryDefinitionSelection` / `insertExampleSelection`；
- provider 为 null（CI/未装配）或 lookup miss → 精确退化为现行为，**既有 ImportEngine 测试零改动**；
- 勾选行 = `WordBookEntryDefinition` + `WordBookEntryExampleSelection`，富化词条学习/播放/掌握全链路即刻可用（同视频导入词条）。

## 4. 影响面

| 项 | 变更 |
|---|---|
| DB schema | **无迁移**（全部复用既有表） |
| `ImportReport` | +`enriched`（富化成功行数，⊆ imported）、+`enrichedMatched`（其中按匹配子集的行数）——守恒式不变 |
| NFR-2 性能 | 每行 +1 次词典索引查询（~0.5–2ms）；10 万行预算由 ≤60s 放宽为 **≤120s**（ImportPerfTest 基准改判据，机器级故障窗口先例仍适用） |
| LEARNING 边界 #3 | 仅对「词典也未收录」的导入词保留原边界 |
| 文档 | PROJECT_SPEC FR-14（v1.26）/ IMPORT_SPEC v1.2（§3 行形态注记 + §8 重写为词典富化 + §6 报告字段）/ TEST_PLAN 新 TC-IMP-09…14 / DECISION_LOG |

## 5. 待用户裁决的决策点

- **D1 零匹配兜底**：带译文行但一个释义都匹配不上 → ①全量导入（推荐，与裸词一致，主诉是"要有释义例句"）②保持现状仅临时译文（尊重用户 specificity，但词回到"没释义"状态）。
- **D2 预览提示**：导入预览是否显示「预计富化 N 词」（预览期只查前 20 行，成本低）→ 建议 v1 不做，报告期可见即可。

## 6. 测试计划（commonTest，Fake DictionaryProvider）

裸词全量勾选 / 带译文子集勾选（中英 token 含有匹配）/ 零匹配按 D1 / 词典 miss 原行为 / 词存在有释义仅勾选不重建 / 词存在无释义先补后勾 / duplicatesInBook 不动勾选 / 守恒式仍成立 / enriched 计数 / provider=null 退化 /（perf）万行预算冒烟。

## 7. v2 词性标记过滤（2026-10-01，用户直接指令——「直接修改下一版本」）

**需求原文**：「如果单词同一行有写要导入的词性（n./v./vt./vi./adj./adv./int.），则导入单词时只导入对应词性的中英文释义和例句。在导入TXT词表的UI界面也把这一信息以及只写单词时会导入所有释义写入说明」。

### 7.1 解析层（LineParser）

- 译文段**首部**词性标记 `n./v./vt./vi./adj./adv./prep./int.`（大小写不敏感，**点号必须有**）解析期剥离，映射规范名：vt./vi./v→**对应动词** verb、n→noun、adj→adjective、adv→adverb、prep.→preposition、int.→interjection（DOMAIN_MODEL §3.1；及物/不及物已按 FR-18 合并为单一动词类——vt.、vi.、v. 都映射动词释义）；
- 正则长词形优先（`vt|vi|…|n|v`）防 v. 吞 vt.；**标记独占行**（`word n.`）→ 译文 null（等效裸词 + 词性过滤）；**未收录标记**（conj. 等）与**无点号前缀**（`adj 是`）原样保留为译文、不判词性；
- `ParsedLine.WordWithTranslation.translation` 转 nullable + 尾参 `partOfSpeech: String? = null`（既有位置构造调用源兼容）。

### 7.2 引擎层（勾选池收窄，母数据不动）

- `selectSenses` 两级过滤：**词性先收窄池**（`partOfSpeech == 规范名`），译文在**池内**再匹配；**词性零命中 → 兜底全释义**（同 D1 理由）；**池内译文零命中 → 兜底到词性池全量**（不是全释义）；
- 母数据导入不受词性影响（词缺释义仍词典全量补齐）；既有释义行上同样按词性过滤（零命中兜底全部既有行）；
- `enrichedMatched` 口径不变（只计译文匹配子集；词性独占行无译文不计）；`ImportSession.DefinitionWithExamples` +`partOfSpeech` 字段（query-only，SELECT * 已含该列，零迁移）；
- 词性独占行 translation=null → §4 规则 3 补译文分支天然不触发（null 守卫短路）。

### 7.3 UI（ImportScreen 选文件页）

说明文案三行化：每行一个词，可选词性或译文 →「只写单词：导入词典中该词的全部中英文释义和例句」+「写词性（n./v./vt./vi./adj./adv./prep./int.）：只导入该词性的释义和例句，后面还可再写译文进一步筛选」+ 既有去重说明。

### 7.4 测试与文档

TC-IMP-15（TEST_PLAN v2.29）：commonTest `LineParserTest` 词性组 3 + jvmTest `ImportEngineTest` 词性组 5。PROJECT_SPEC v1.27 / IMPORT_SPEC v1.3。零 schema 迁移。装机走查取消（用户 2026-10-01），随下一版 APK 一并人工核验。

### 7.5 追补（2026-10-01，用户指示）

「vt./vi./v 统一映射 verb」表述改为「vt. 和 vi. 映射对应动词」（功能不变：词库粒度按 FR-18 合并，三者同指动词释义类）；词性标记集**补 `prep.` → preposition**（DOMAIN_MODEL §3.1 规范位 5），UI 说明标记列表同步。测试：`LineParserTest` 补 prep 剥离用例、未收录示例改 conj.。文档：PROJECT_SPEC v1.28 / IMPORT_SPEC v1.4 / TEST_PLAN v2.30。

### 7.6 追补 2（2026-10-01，用户实录缺陷）

**问题**：用户 TXT 写短语 `roll out`，导入结果是 `roll`（+ 译文 out）。根因 = Phase 7 原口径：单空格是分隔符级且优先于整词校验，首空格必然分割——多词短语永远无法以单空格形态导入（原测试锁过 `take off`→take+off）。

**修正**：单空格级改为**逐位置扫描**，跳过「译文以英文字母开头」的位置（英文尾巴是短语成分，不是译文）：
- `roll out` → 单空格级全跳过 → 整行短语 WordOnly（词典按短语整体 lookup 富化）；
- `roll out 推出` → 在首个非英文开头的空格处分割 → 短语 + 译文；
- `hello world,你好` → 单空格级全跳过后下探逗号级 → 短语 + 译文；
- 显式分隔级（Tab / 2+ 空格 / 逗号 / 分号 / 冒号）不做扫描、右侧英文照常作译文；**译文确以英文开头时须用显式分隔符**（UI 说明已注明）。

测试：TC-IMP-16（`LineParserTest` 短语组 3 + 既有 2 用例期望反转 + `ImportEngineTest` 短语富化 1）。文档：PROJECT_SPEC v1.29 / IMPORT_SPEC v1.5（§3 + 边界 #10）。零 schema 迁移。
