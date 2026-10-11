# 审计报告：SCR-IPACOVERAGE（单词双音标覆盖）

> 日期：2026-10-10 | 对应 SCR：SPEC_CHANGE_REQUEST_IPA_COVERAGE.md

## 1. 触发案例取证

`flayer`（用户装机报告「没有音标」）：

| 层 | 值 |
|---|---|
| 资产 v8 词条 | 定义/中文/例句完整（v7 gen2 生成例句） |
| 资产 v8 `ipa` / `ipaBr` | `''` / `NULL` |
| ECDICT 1.0.28 源库 | `phonetic=''`、bnc=0、frq=0（长尾，源即无音标） |
| ipa-dict（v5 已并） | 未收 flayer（同族 slayer 收了） |

对照样本（资产 v8 实查）：slayer `'ˈsɫeɪɝ'`（ipa-dict 记法）、maker `"'meikә"`（ECDICT 类 DJ）——
**两种记法已在库中并存**，flayer 型（两源皆空）是缺口主体。

## 2. 覆盖面（资产 v8 全量统计）

| 类别 | 总数 | 有美音 | 有英音 | 双全 |
|---|---|---|---|---|
| 单词（无空格） | 449,387 | 232,663（51.8%） | 64,307（14.3%） | 59,387 |
| 短语（含空格） | 387,011 | 17,953 | 0 | 0 |

单词缺口：美音 216,724 / 英音 385,080；`PRAGMA user_version=8`。

## 3. 可修面量化（缺美音单词 216,724 的结构）

- 屈折/派生后缀结构（-s/-es/-er/-ers/-ed/-ing/-ly/-est）：105,368（48.6%），
  其中**基词在库且有音标、规则可推导**约 39,990（18.5%）——但推导规则需自建音系规则
  （-ed/-s 清浊、正字法双写/去 e、ECDICT `ә` 与标准 `ə` 两字符集）+ 应拒反例测试面；
  espeak-ng 内建英文形态学与词典，同等覆盖且免自研——**不单设派生层**，统一走引擎。
- 其余 111,336 为根词长尾：只能 G2P 引擎或新数据源。

## 4. 引擎选型实测（2026-10-10，本机）

| 引擎 | 实测结果 | 结论 |
|---|---|---|
| **espeak-ng 1.51**（GPL，构建期工具） | **已装机验证**（2026-10-10）：`msiexec /a` 便携镜像 + UCRT app-local 拷贝（`api-ms-win-crt-*.dll` Win10 19045 必需；7-Zip 直解包扁平化不可用）。双口音样本见 SCR D1（rhotic/trap-bath/lot/闪音全判据通过）；stdin 行模式 10k 词 3.9s、行数守恒零空行 → 全量双列 ~4 分钟 | **采用** |
| gruut + gruut-lang-en（pip） | flayer en-us 与 en-gb **输出同形** `flˈeɪɚ`（无口音区分）；重音符位非标准；octodecillion `ˈɑktˈɑdɪsˈɪljən` 多重重音 | **否决** |
| piper-phonemize / espeakng-loader（pip 宿主） | 本机 PyPI 索引无可装发行版（versions: none，官方源同） | 不可用 |
| CMUdict + g2p_en | 仅美音；g2p_en 引 torch 重依赖 | 否决 |
| ollama qwen3:8b 生成 | 音标错误无法确定性审计、无应拒反例可设 | 否决（与 wordPresent 纪律冲突） |

espeak pilot 全表（500 词×双口音，分层抽样）落 `%TEMP%/enrich_work/ipa_pilot_table.txt`；
样本质量：preconditioning/pakora/distributist/revalidation/above-noted（US 闪音 ɾ vs UK t）等重音与口音结构正确，
病理词（drf）按字母朗读——词条本身异常，可接受。美英同形 155/500（无口音差异词属正常）。
github.com 下载窗口 3 次重试后恢复（本机已知模式）；msys2/部分 PyPI 包同期不可达。

## 5. 运行时影响面核查

- `BundledDictionaryProvider` 读 `ipa`/`ipaBr` 列 → `DictionaryWord` → 展示「有则双显、无则单显、
  缺失不渲染空行」（FR-22）：**零代码变更即生效**。
- 已导入词回填：`needsEnhancementBackfill`（美音缺失 ∨ 英音缺失 ∨ 零例句 ∨ 译文空）+ 英音回填
  只补空缺——已建，零变更；本批数据落地后自动收敛。
- 唯一 Kotlin 改动：`DICT_ASSET_VERSION` 8→9（设备缓存重拷门禁）。
- TTS 朗读（vits）按词文本合成，**不消费音标字符串**——音标纯展示，错值不致发音错误。

## 6. 结论

缺口为数据源覆盖问题（ECDICT 长尾无 phonetic + ipa-dict 不收），可由 espeak-ng 确定性补齐至单词双列全覆盖；
运行时链路（模型/端口/回填/UI）全部现成。建议按 SCR D1–D4 执行。
