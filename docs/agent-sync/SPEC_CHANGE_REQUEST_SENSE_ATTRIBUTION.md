# SPEC_CHANGE_REQUEST — SCR-SENSEATTR

> 提出：2026-09-26（用户装机走查 gloss 后指示；同日两轮裁决定稿）；**v2 增补 2026-10-02（§7：第二批 v7 并入释义中文补齐，用户方案 A 裁决）**
> 主题：Tatoeba 例句逐释义归属 + 全量例句重译 + **缺口释义生成兜底（完整覆盖）**；v2：+ 释义 meaningCN 补齐（74,873 条空缺）
> 影响范围：tools/dict 资产管线（+本地 LLM 推理工具）、shared（BundledDictionaryProvider / SeedImporter / Example.sq / Example sourceType）、资产 user_version 5→**6（第一批）**→**7（第二批）**；**app DB schema 恒 v2 零迁移**

## 1. 问题

1. **例句与释义不对应（用户报告）**：查 gloss 显示 4 条例句全部挂在首释义 verb「上光」下，其中 3 条是名词短语 **lip gloss（唇膏）**用法、1 条是 verb「作注释」用法——没有一条真正属于首释义。根因：Tatoeba 例句是句子级语料无词义归属，构建期一律挂首释义（PROJECT_SPEC v1.15 注记的已知限制），10.7 万带例句词条通病。
2. **例句译文机翻质量差**：opus-mt-en-zh 产出存在漏词（"Can you please gloss this sentence?"→「你能把这句话加上吗?」丢了 gloss 语义）、重复（"Mary applied some lip gloss."→「玛丽用唇膏涂了点唇膏」）等。
3. **零例句释义（第一版 SCR 的「诚实边界」被用户否决）**：语料匹配天然铺不满全部释义（缺口实测量化见 §2），「找不到就零例句」不可接受——**必须技术兜底保证例句对释义的完整覆盖**。

## 2. 用户裁决（2026-09-26，两轮）

1. 修复方向：**把所有释义逐条找到对应的例句并依序加进去**（词义归属，不是仅 UI 注记）。
2. 译文质量：**换更强翻译模型整批重译**。
3. **「找不到对应例句的释义不能零例句，找其它技术方案，一定要保证例句对释义的完整覆盖」**——语料铺不满的部分由**本地 LLM 生成例句**兜底（唯一可达完整覆盖的路径）。
4. 范围：**分两批达全量**——第一批（本 SCR 执行体）：归属重排 + 全量重译 + 带例词缺口生成；第二批（独立批次）：72.9 万零例词按词频从高到低生成，终态 = 931,417 释义全覆盖。
5. 引擎：**本地 Qwen 7B 级 4-bit**（本机 RTX 4060 Ti 16GB，12.9GB 空闲）——生成与重译同栈、免费、纯离线工具链，不碰云 API（app 保持纯离线，NFR-1 无损）。

**缺口量化（资产 v5 实测，2026-09-26）**：

| 口径 | 词条 | 释义数 | 语料可覆盖 | 缺口（需生成） |
|---|---|---|---|---|
| 带例句词 | 107,094 | 161,941 | 归属重排后大部分 | ~2–6 万句（第一批） |
| 零例句词 | 729,304 | 769,476 | 0 | 全部（第二批） |
| 合计 | 836,398 | 931,417 | — | ~79 万句 |

释义分布：93.5% 词条单释义；带例词中 83.5% 例句数 ≥ 释义数。

## 3. 方案（第一批执行体）

### 3.1 管线侧（tools/dict；重活与驱动一律 Node/V8——本机 CPython 稳定性先例）

**a. 词义归属三级匹配（确定性管线，不引入 LLM）**：

1. **最长短语改挂**：例句若包含**更长的已收录词典短语**（词边界匹配，如 lip gloss / take off），从单词的例句表剔除——该句在扫描期本就同时索引在短语词条下，短语词条查词自然获得完全对应的例句；
2. **POS 限定**：用 wink-nlp（Apache-2.0，纯 JS，npm 本地依赖不进 app）标注例句中该词的词性，候选释义限定同词性；词性不可判 → 全词首释义（现状行为）；
3. **同词性内义群评分**：例句 EN 与各候选释义 meaningEN 词重叠 + 重译后例句 CN 与 meaningCN 字符 bigram 重叠，取最优释义；低于置信阈值 → 该词性首释义。

**b. 例句终选改为按释义分摊 + 生成兜底（完整覆盖）**：候选池（词 12 / 短语 6）先按归属释义分组，逐释义轮转选优，每词仍 ≤4 条；**归属后仍无例句的释义 → 本地 Qwen 生成 1 条例句（EN 句 + zh 译文一并产出）**——每释义至少 1 条例句，无一例外。

**c. 全量重译（引擎升级为 Qwen）**：原计划 NLLB-1.3B 的前提（无 GPU）不成立——本机 4060 Ti 16GB 直接跑 Qwen 重译全部机翻例句（~26.9 万句；Tatoeba links 人译文 3.05 万条**保留不动**——人译优先于任何机翻）。**执行前先抽样 50 句对比 opus-mt，生成句另抽 50 句，质量无可见提升则停批上报**（止损点；备选升 14B 复验）。

**d. 本地推理环境（工具链，不入 app）**：llama.cpp 官方 CUDA 预编译版（cublas x64 zip）+ Qwen 7B 级 4-bit 量化 GGUF（hf-mirror.com 镜像下载）→ `llama-server --parallel N` OpenAI 兼容接口连续批处理；Node 驱动脚本（tools/dict/），**断点续传 + 勤落盘**（本机机器级故障窗口纪律）。

**e. 资产格式 v2**：`examples` 列改 `[["en","zh",defIdx],…]`；生成句第 4 元素标 `"g"`（`["en","zh",defIdx,"g"]`）——defIdx = definitions 数组下标（definitions 已按 (posOrder, defOrder) 序生成，与导入插入序天然对齐）。`PRAGMA user_version=6`（数据重建递增铁律，设备旧缓存自动重拷）。**第二批完成后 user_version=7。**

### 3.2 App 侧（shared）

- **BundledDictionaryProvider**：解析新格式，例句按 defIdx 挂对应释义（替换现有 senseIndex==0 逻辑）；`"g"` 标记的例句 sourceType = **AI_GENERATED（新枚举值，如实标注，FR-3）**——DB 值即标注，v1 不加 UI 徽标。
- **SeedImporter.backfillEnhancements 升级为 diff 驱动重归位**（已导入词条装机自愈，无需 DB 手术）：
  - 缺失句 → 按 defIdx 挂**对应释义**（不再是首释义）；
  - 已有句但词典 defIdx 不同 → `updateExampleDefinitionEntryId` **移动**（exampleId 不变，用户勾选/选择行天然保留）；
  - TATOEBA 例句译文 ≠ 词典译文 → 覆盖更新（**只限 sourceType=TATOEBA**，绝不碰视频/种子例句）；
  - 词典已剔除的 Tatoeba 例句（短语改挂类，如 gloss 下的 3 条唇膏句）→ **无选择行引用才删**（`countExampleSelections` 守卫 + 按 exampleId 删；被用户收藏的保留，宁留不错删）；
  - 回填闸门扩为「词带任一 TATOEBA 例句 ∨ 音标缺失 ∨ 零例句 ∨ 译文空」，diff 幂等零写。
- **Example.sq +3 查询**（move / 选择行计数 / 按 id 删）——query-only，**schema 恒 v2 零迁移**。

## 4. 不改的东西

- app DB schema、FR-5 选择粒度、FR-10 播放分段序（例句仍随所属释义播放，只是归属修正后「所听即所属」）；
- ECDICT 释义中英按位配对的跨义混杂（上游数据问题，另案）；
- 候选句来源与粗口过滤（沿用 v3 口径）；
- app 运行时形态：LLM 只存在于离线资产构建管线，app 内零模型零 API 调用（NFR-1 无损）。

## 5. 测试与验收

- jvmTest SeedImporterTest +N：defIdx 挂载 / 已有句移动 / 译文覆盖限 TATOEBA / 删除守卫（有选择行不删）/ 生成句 sourceType / 幂等零写；
- androidTest 词典资产冒烟更新（新格式解析）；
- 门禁全套（jvmTest / testDebugUnitTest×2 / detekt×2 / checkPlatformBoundaries / assembleDebug）；
- **装机验收 = gloss 重查**：唇膏 3 句消失（查 lip gloss 可见）、"Can you please gloss this sentence?" 挂到「作注释」动词义、译文语义完整、**每个释义都有例句（含生成句）**；抽样若干词目检归属合理性。

## 6. 风险与许可

- **生成质量边界**：高频词好，长尾生僻词/短语自然度下降（7B 模型对罕用语有限）——50 句抽样止损纪律；生成句 `"g"` 标记不冒充语料；第二批长尾占比高，质量风险集中在该批（届时按频次优先、逐段抽检）；
- 本机 16GB RAM / 4060 Ti 16GB VRAM：4-bit 7B 级 + 连续批处理显存充裕；翻译/生成期间停 Gradle 守护进程；断点 + 勤落盘；
- Qwen 系 Apache-2.0（工具侧使用，产物入资产无许可障碍）；
- wink 词性标注词级准确率 ~95%：存在少量错配，但相对「全挂首义」错配率低一个量级；
- 资产重建必须递增 user_version（第一批 6 / 第二批 7，铁律）。

---

## 7. v2 增补（2026-10-02）：第二批 v7 并入「释义中文补齐」

> 提出：2026-10-02（用户实录：查 *metrics* 后几条释义只有英文无中文）；方案 A 用户裁决
> （与原第二批 72.9 万零例句生成**合并为一次 v7 重建**）。
> 影响范围：tools/dict 管线（llm_tasks.js 新任务型 / build_v6.js 新门 + 版本 7）、
> shared（SeedImporter 释义 CN 自愈 + DefinitionEntry.sq 一条 UPDATE，**schema 恒 v2 零迁移**）。

### 7.1 问题与根因（取证三方对齐）

- **资产 v6 实测**：`metrics` 4 条释义，第 2–4 条 meaningCN = 空串（App 端 `meaningCN.isNotBlank()`
  才渲染中文行——渲染正确，数据缺失）。
- **根因（ECDICT 源库）**：`definition`（英文，WordNet 来源）是**逐义**的；`translation`（中文）
  是**整词级**的——`metrics` 有 4 条英文释义但中文只有 1 行「韵律学…」。构建期拆分逐释义行时，
  第 2 条起分不到中文。
- **门禁盲区**：`build_v6.js check` 的 FATAL 门只覆盖**例句译文**（`no-translation`）与
  **缺口释义例句生成**（`no-generation`）；**释义本体 meaningCN 的覆盖从未设门**——v6 批的
  注意力全在例句归属，此缺口从 v1 资产起就存在。
- **规模（v6 全量扫描，836,395 词 / 931,414 释义）**：

| 口径 | 数量 |
|---|---|
| 部分缺中文词（≥1 释义空 CN 且 ≥1 有 CN；metrics 属此类） | **40,441 词** |
| 全部释义空 CN 词 | 2,198 词 |
| **空缺 CN 释义总数（本批补译对象）** | **74,873 条** |
| 释义空 EN（另一类：ECDICT 无 definition 行、只有整词中文；与空 CN **从不同时出现**） | 688,272 条 / 683,151 词 |

### 7.2 方案

**a. 管线（tools/dict，原 SCR §3.1 纪律全部沿用）**

1. `llm_tasks.js` 新任务型 **`d:`（释义补译）**：key = `d:word#defIdx`，输入
   （word, partOfSpeech, meaningEN）→ 输出纯中文释义词组；校验复用 `validZh`/`cleanZh`
   （首行、剥引号、含汉字、粗口过滤）。任务收集自 `defs.json`（`[pos,en,cn]` 三元组已带 CN，
   空 CN 即入队）——**含零例句词条**（`attribute_senses.js prep` 需覆盖全量释义，不再只扫带例句词）。
2. `build_v6.js` 升版：`VERSION = 7`；合并逻辑——definitions 数组内空 CN 位置用 `llm_out` 的
   `d:` 输出填充；**新增 FATAL 门 `no-def-cn`**（任一释义 CN 空且无对应 `d:` 输出 → 拒绝写盘，
  「完整覆盖」门从例句扩到释义本体）；probe 词组 +`metrics`；备份落 `%TEMP%/enrich_work/ecdict.sqlite.v6.bak`。
3. 跑批顺序与配比照旧（先 `d:` 后 `g:`，translate 10 / generate 6 并发；serve `>/dev/null`、
   键去重 resume-safe、parked 收尾重跑）。**量级**：`d:` 74,873 条 × ~0.92/s/worker × 10 ≈ **2.3h**
   （先跑先得）；`g:`（原第二批）72.9 万词条 / 76.9 万释义 ≈ **~6 天**连续——质量止损纪律
   （长尾按 bnc 词频从高到低）沿原 SCR §6。

**b. App 侧（shared）——已导入词条自愈（新决策 D1）**

用户设备上的 *metrics* 已经按需导入过（DB 命中即不再咨询词典），仅修资产**不会自愈**；
且 v1 无删词入口 → 必须走既有回填通道：

1. `SqlDelightWordRepository.needsEnhancementBackfill` 门 +「**任一 DB 释义 meaningCN 空白**」
   ——与「零例句 / 音标缺失 / 词典例句存在」同级的第 5 个触发条件（幂等收敛后零写入）。
2. `SeedImporter.backfillEnhancements` 新步骤「释义 CN 填充」：**仅对齐词条**
   （DB 释义数 == 词典释义数，Q1 位序对位——同 `reattributeExamples` 的 aligned 判定），
   DB 行 meaningCN 空白且词典侧非空 → UPDATE 填充；**非空白绝不覆盖**（ECDICT 原有中文/
   视频词中文优先）；**非对齐词条（TXT/视频形态）不动**（位序映射不可靠，保持既有保守口径）。
3. `DefinitionEntry.sq` + 一条 UPDATE 查询（零迁移，schema 恒 v2）。
4. 预期连带效果（正确且即用户所求）：已收藏含空 CN 释义的词条，学习播放的 MEANING_CN 段
   自愈后从「空段」变为真实中文朗读；勾选关系零变化（填充不改 definitionEntryId）。

### 7.3 不改的东西

- app DB schema（恒 v2）、FR-5 选择粒度、播放分段结构、收藏/掌握语义；
- **释义空 EN 类（688,272 条）明确范围外**（另案——ECDICT 无 WordNet 行的整词中文词条，
  量级 9 倍于本批，且改的是「展示有 EN 行」而非用户报告的「缺中文」；本批不扩）；
- 例句管线口径（归属/重译/生成，全部沿 v6 既定行为，仅扩量到零例句词条）。

### 7.4 测试与验收

- jvmTest `SeedImporterTest` +4：对齐词条空 CN 填充（Q1 位序对位）/ 非空绝不覆盖 /
  非对齐词条零写入 / 二次 lookup 幂等零写；`WordRepositoryOnDemandImportTest` 契约注记更新
  （空 CN 触发咨询属预期）；
- `node build_v6.js check`：`no-def-cn` 计数 = 0 且 problems=0 才可写盘（门自证：先故意断供
  一条 `d:` 验证 FATAL 触发，再补齐）；
- 门禁六项（jvmTest / testDebugUnitTest×2 / detekt×2 / checkPlatformBoundaries / assembleDebug）；
- **装机走查（vivo）**：①重查 *metrics*（已导入词自愈：第 2–4 条中文出现）②新查一个部分缺词
  （如任一 topological 类术语词，验证新导入直接带 CN）③原第二批验收项（零例句词条生成例句
  抽查、bnc 高频段优先）。

### 7.5 风险

- Qwen 对术语类释义（topological space / versification）的中文准确性——50 条抽检止损（沿
  §6 纪律；释义翻译比例句生成容易，预期风险低）；
- `d:` 与 `g:` 同窗混跑的 GPU 竞争——按原配比分队列跑（translate 类 10 并发独占窗口期先清 `d:`）；
- 688,272 空 EN 类不动 → 查这类词仍只有中文行（现状不变，登记另案）。

## 8. v3 增补（2026-10-10）：-illion 词族记号伪译文修复 → user_version=8

### 8.1 问题与取证（用户装机报告）

- 用户报告：*octodecillion 释义是数字加特殊字符，不是正确中英文释义*。
- 资产取证：`definitions` 的 meaningCN 为 ECDICT translation 上标转义记号——
  octodecillion `& adj. （英、德）10#1#0#8（的）；（美、法）10#[57]（的）`。
- 全量审计（`/#\[\d+\]|#\d+#\d+/`）：**3 条释义**（octodecillion / septendecillion /
  undecillion，全部 -illion 大数词族、meaningEN 空、defIdx=0）。早先 `LIKE '%#[%'`
  审计漏了无括号形态 `10#1#0#2`（septendecillion）——正则双形态才收全。

### 8.2 方案（已执行）

1. `build_v6.js fillDefinitionCn`：记号伪 CN 视同空白（可被 `d:` 条目替换）；垃圾无条目
   时**保留原文不 FATAL**（门只管管线承诺覆盖的空白位，未发现垃圾不阻塞构建，
   `defCnGarbageKept` 计数可见）；`hit.en`（仅手写条目携带）可选补齐 blank meaningEN。
2. 手写 3 条 `d:` 条目（`hand_d3.js`，沿 hand.json 先例：干跑自检 + 键查重 + `--write`
   追加 llm_out.jsonl）；中文 = 记号解码（短级差 美、法 10^(3n+3) / 长级差 英、德 10^(6n)，
   与原文地理归属一致），英文 = 词典式 "the number equal to 1 followed by N zeros..."。
3. **重建自 v6 源**：管线单向（v7 产物的零例句词已带例句，再跑全量 stale-exampled-word
   FATAL）→ 先保全现网 v7 → 回置 `ecdict.sqlite.v6.bak` 为源 → check（problems=0、
   defCnFilled=74,876=74,873+3、garbageKept=0、其余计数与 v7 终态逐项一致）→ 写 v8。
4. **DICT_ASSET_VERSION 7→8 同步递增**（v7 装机实录教训：忘改 = 设备静默不更新）。

### 8.3 验收（2026-10-10，vivo）

- 资产 v8：836,398 行 / 344,498,176 字节 / user_version=8；三词 defs+examples probe 正常。
- 装机：7→8 门禁重拷触发 ✓；octodecillion（手术清除旧导入行后重导，wordId 48→49）+
  septendecillion + undecillion 首查导入 ✓；UI 三词均显示正常中英文释义 + AI 例句标注 ✓。

### 8.4 走查疑似缺陷复盘（同日撤案——非 app bug）

**疑似现象**：force-stop 后第一词 `importOnDemand` 正常，同会话第二词起查询 miss、DB 零写入，
force-stop 即恢复一次（10-08「install -r 后首会话失效」观察似为其子集）。

**取证（确定性 UI 驱动 dictdiag.py：dump 定位元素 + 每步断言，替代裸坐标 tap）**：
同进程连续导入两个全新词（quokka → narwhal）**双成功**，`importOnDemand`/`search()` 无任何
per-session 状态。

**真相 = 走查方法缺陷**：① 详情页（WordDetailScreen）**没有查词输入框**，从详情页裸
tap(586,280) 后 `input text` 丢弃或落到错误焦点；② 主页输入框带旧词时 `adb input text`
是**追加不是替换**（实证：输入框出现 `quokkanarwhalnarwhal` 拼串）——拼串查询词典 miss →
「未找到匹配的词」+ DB 零写入，**属正常 miss 而非导入失效**。此前三张「miss 截屏」的
查询词实为拼串/未送达。

**教训**：装机走查必须用确定性驱动（dump→定位→tap→断言，输入前清空输入框），
裸坐标 + 带残留输入框的 `input text` 会制造系统性假 bug。10-08 的观察同因，一并撤案。
