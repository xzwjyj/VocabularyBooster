# SPEC_CHANGE_REQUEST — SCR-SENSEATTR

> 提出：2026-09-26（用户装机走查 gloss 后指示；同日两轮裁决定稿）
> 主题：Tatoeba 例句逐释义归属 + 全量例句重译 + **缺口释义生成兜底（完整覆盖）**
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
