# 随包全量词典（FR-18，Phase 8.6）

查词兜底数据源：ECDICT（MIT License，<https://github.com/skywind3000/ECDICT>）1.0.28
子集 → 只读 SQLite 随 APK 打包（`app/src/main/assets/dict/ecdict.sqlite`，v9 起 ~411MB
含 Tatoeba 例句与全量双音标，**不入 git**——本目录可再生，见下）。

## 子集口径（用户裁决"全量 ~77 万词"）

- 基础集：`bnc IS NOT NULL`（进入词频表的 846,281 条；未入表的长尾为拼写变体/噪声，剔除）。
- 字符集过滤：`^[A-Za-z][A-Za-z \-']*$`，长度 1–40。
- 产出 **836,398 条**（单词 449,387 + 短语 387,011；含音标 217,591）。
- 每词条义项上限 8（首义项覆盖核心语义）。
- 已知取舍：`take off` 等 ECDICT 原始行无词性前缀的短语 → `other`（partOfSpeechOrder=90，
  DOMAIN_MODEL §3.1 逃逸口）；ECDICT 音标为类 DJ 记法（非严格 IPA）存 `ipaAm`。

## 产物 schema

```sql
CREATE TABLE DictEntry(
  normalized   TEXT PRIMARY KEY,  -- = word.strip().lower()，与 shared toNormalizedWordText() 一致
  text         TEXT NOT NULL,     -- 原始大小写
  ipa          TEXT NOT NULL,     -- ECDICT phonetic（可空串；ipa-dict en_US 补过空缺，见 merge_ipa.py）
  definitions  TEXT NOT NULL,     -- 紧凑 JSON [["partOfSpeech","meaningEN","meaningCN"],…] 已按词性序排
  examples     TEXT NOT NULL,     -- 紧凑 JSON [["英文原句","中文译文",defIdx(,"g")],…]（v6 起逐释义归属；"g"=AI 生成兜底）
  ipaBr        TEXT               -- 英音音标（ipa-dict en_UK；可空——短语/长尾无英音属预期）
) WITHOUT ROWID;
-- PRAGMA user_version = 9：资产版本（v1 无例句列 / v2 例句对格式 / v3 粗口过滤 / v4 全量中文翻译 /
--   v5 ipa-dict 英音+美音补缺 / v6 例句逐释义归属 + 全量重译 + 生成兜底；v7 = 第二批零例句词条按词频递补 +
--   释义中文补译；v8 = -illion 词族上标记号伪译文修复（3 释义，含 blank-EN 手工补齐）；v9 = 单词+短语双音标
--   espeak-ng 全量补齐，既有值零触碰（SCR-IPACOVERAGE））。
-- 版本 = 修订号：数据重建也必须递增，否则设备端同 user_version 的旧缓存不会重拷（BundledDictionaryProvider）
```

App 侧消费：`shared/src/androidMain/.../platform/BundledDictionaryProvider.kt`
（首次 assets → filesDir 复制后只读打开；v6 起 defIdx = definitions 数组下标逐释义挂载，"g" →
AI_GENERATED 如实标注）；接线 `app/di/AppModule.kt`
（种子优先 → 全量兜底的复合 DictionaryProvider，DB miss 按需导入走 SeedImporter；
已导入词条 lookup 时幂等回填 + 例句 diff 重归位——移动保 exampleId、译文覆盖限词典来源行、删除经勾选守卫）。

## 再生步骤（Windows，Python 3.10+）

```bash
cd tools/dict
# 1. 下载 ECDICT 1.0.28 sqlite 包（~207MB，zip 内 stardict.db ~812MB）
curl -sL -o ecdict-sqlite-28.zip \
  https://github.com/skywind3000/ECDICT/releases/download/1.0.28/ecdict-sqlite-28.zip
# 2. 解出 stardict.db（python 一行即可，无需 7z）
python -c "import zipfile; zipfile.ZipFile('ecdict-sqlite-28.zip').extract('stardict.db')"
# 3. 转换（约 10 秒，产出 ../../app/src/main/assets/dict/ecdict.sqlite）
PYTHONIOENCODING=utf-8 python convert.py
# 4. Tatoeba 例句增强（PROJECT_SPEC v1.15 FR-18）：
#    下载全量 sentences.tar.bz2 + links.tar.bz2 到 %TEMP% 并解压
#    （https://tatoeba.org/en/downloads → "Sentences (CC-BY 2.0 FR)" / "Links"）
#    产出 examples 列（词/短语 n-gram 匹配 ≤4 条/词，zh 优先、粗口过滤）+ user_version=3
node enrich_node.js scan     # 全量扫描 → %TEMP%/enrich_work/entries.json + eng_ids.json
node enrich_node.js cmn      # 合并 cmn 分片 → cmn.json
node enrich_node.js links    # links join → id_to_zh.json
PYTHONIOENCODING=utf-8 python enrich_with_examples.py build   # 重建 sqlite（含 user_version=3）
# 5. ipa-dict 音标增强（FR-22）：英音 + 美音补缺 → user_version=5
#    下载 open-dict-data/ipa-dict（MIT）data/en_UK.txt + data/en_US.txt 到 %TEMP%
#    （https://github.com/open-dict-data/ipa-dict）
#    en_UK → ipaBr（取首个候选、剥斜杠）；en_US 只补 ipa 空缺（绝不覆盖）
#    实绩（2026-09-20）：ipaBr 64,307 / 美音 217,591→250,616 / 双音标同显 59,387 / 资产 159MB
PYTHONIOENCODING=utf-8 python merge_ipa.py
# 6. 例句逐释义归属 + 完整覆盖（SCR-SENSEATTR，PROJECT_SPEC FR-3 v1.25）→ user_version=6
#    三级归属（最长短语改挂 / wink-nlp 词性限定 / EN token + CN bigram 重叠打分）
#    + 缺口释义离线生成兜底 + 无译文句全量重译（本地 ollama qwen3:8b，think:false）
#    实绩（2026-09-27）：归属 267,071 词条 / 短语改挂 35,420 / 缺口 48,767 生成 / 重译 238,382
#    （NLLB-200-distilled-1.3B ct2 抽样止损停批：~10% 截断 + 全量半角标点——nllb_translate.py 留档不投产）
node attribute_senses.js prep        # 资产 → %TEMP%/enrich_work/ec_words.json + defs.json
node attribute_senses.js cmn         # sentences.csv 单遍 → cmn.json（喂 enrich_node.js links 复用）
node attribute_senses.js attribute   # 三级归属 → attributed.json（含缺口清单）
# ollama serve（OLLAMA_NUM_PARALLEL=16）后双队列（resume-safe，键去重 llm_out.jsonl）：
node llm_tasks.js generate           # 缺口释义生成（"g:" 键）
node llm_tasks.js translate          # 无译文句重译（"t:" 键；失败重试一次后 parked llm_failed.jsonl，
                                     #  收尾重跑同命令即自动重试 parked）
node build_v6.js                     # 合并 v5×attributed×llm_out → v6 资产（缺译/缺生成 FATAL；崩溃安全换盘）
# 7. 第二批零例句词条 + 释义中文补译（SCR-SENSEATTR §7 v2）→ user_version=7
#    d:：blank meaningCN 释义补译；gen2:：零例句词条逐释义生成（bnc/frq 词频序，常用词先行）
#    校验器 wordPresent 经六轮「词典记号等价」放宽（sth/sb 占位、be 形式、词头冠词可选、
#    one's/oneself↔物主/反身类、a/b 占位通配（≥3 实词锚）、连字符断行合并、OSA 换位模糊、
#    tail 屈折后缀白名单——"take off" 不吞 "take office"）；每轮放行必带应拒反例
#    （test_wordpresent_v4.js，39 用例）。实绩终数见 DECISION_LOG（d: 74,873 已全收口含 56 人工）
node llm_tasks.js defs               # 释义补译（"d:" 键；键公式 = d:word#defIdx#en.slice(0,60)，
                                     #  与 build_v6.js fillDefinitionCn 两处逐字一致）
node llm_tasks.js gen2               # 零例句词条生成（"g:" 键；自动跳过 parked≥2 硬洞防重启重磨）
node llm_tasks.js gen2sweep          # 批尾终扫：无视跳过全量重试一次
node %TEMP%/enrich_work/replay_promote.js --write   # 校验器放宽后重放历史拒稿，零 GPU 回收晋级
node build_v6.js check               # 覆盖审计（缺译/缺生成/blank-CN 全 FATAL）
node build_v6.js                     # 写 v7 资产（user_version=7；备份落 %TEMP% 不入 assets）
# 8. -illion 词族记号伪译文修复（SCR-SENSEATTR §7 v3，用户报告 2026-10-10）→ user_version=8
#    ECDICT translation 的上标转义记号（"10#[57]" = 10^57）是不可读伪中文；fillDefinitionCn
#    将其视同空白，由手写 "d:" 条目替换（hand_d3.js，3 释义；hit.en 可选补齐 blank meaningEN）。
#    实绩：defCnFilled 74,873→74,876，defCnGarbageKept=0；垃圾无条目时保留原文不 FATAL。
node %TEMP%/enrich_work/hand_d3.js --write
node build_v6.js check               # 期望 problems=0 + defCnFilled=74,876 + 三词 probe 正常中文
node build_v6.js                     # 写 v8 资产（user_version=8）
# 9. 单词+短语双音标全量补齐（SCR-IPACOVERAGE，2026-10-10）→ user_version=9
#    espeak-ng 就位（一次性；本目录 espeak-ng-a/ 已 gitignore）：
#      curl -sL -o %TEMP%/espeak-ng-x64.msi \
#        https://github.com/espeak-ng/espeak-ng/releases/download/1.51/espeak-ng-x64.msi
#      msiexec /a %TEMP%\espeak-ng-x64.msi /qn TARGETDIR=<本目录>\espeak-ng-a
#      cp /c/Windows/System32/downlevel/api-ms-win-crt-*.dll "<本目录>/espeak-ng-a/eSpeak NG/"
#      cp /c/Windows/System32/ucrtbase.dll               "<本目录>/espeak-ng-a/eSpeak NG/"
#    ⚠ 7-Zip 直解包 MSI 会扁平化目录（voice 全失联）——必须 msiexec /a；UCRT 桩 Win10 19045 必需。
#    ⚠ 本机 CPython 后台任务里 spawn subprocess 必段错误（两次实测）——fill_ipa.py 必须前台跑；
#      compute 断点可续（.part 续行），超 10min 分多次前台调用即可。
#    实绩（2026-10-10）：美音 585,782 + 英音 772,091 填充，既有值零改动；compute 613s + apply 36s；
#    资产 344,498,176 → 410,787,840 字节。
PYTHONIOENCODING=utf-8 python fill_ipa.py compute   # 阶段A：espeak 全量 -> %TEMP% 缓存（可续）
PYTHONIOENCODING=utf-8 python fill_ipa.py apply     # 阶段B：备份+写库+校验（user_version=9）
PYTHONIOENCODING=utf-8 python fill_ipa.py --check   # 独立复核（覆盖率/既有值零改动）
# ⚠ 管线单向性：build_v6.js 以「当前资产」为源重建——v7/v8 产物的零例句词已带生成例句，
#   再跑会被当 stale-exampled-word FATAL。**重建必须先把阶段备份回置为源**：
#   cp %TEMP%/enrich_work/ecdict.sqlite.v6.bak ../../app/src/main/assets/dict/ecdict.sqlite
#   （llm_out.jsonl 追加不改旧键 → 同输入确定性重建；v7/v8 现网产物各自有 .bak 在 %TEMP%）
# ⚠ Kotlin 门禁：DICT_ASSET_VERSION（BundledDictionaryProvider）必须与 user_version 同步递增，
#   忘改 = 设备静默不更新（v7 装机实录教训）。
```

运行时分工注记：本机 CPython 在语料扫描上确定性段错误（机器级故障）——
**重活（scan/cmn/links）走 Node/V8（enrich_node.js），Python 只做 sqlite 构建阶段**。
`enrich_with_examples.py` 保留 Python 子进程分片 + 重试 + 断点续传实现（历史路径，
机器修复后可用 `python enrich_with_examples.py` 一键全流程）；分片落 `%TEMP%/enrich_work/`。
Tatoeba 数据（sentences/links，~1.2GB 解压）不入 git、不随包，仅离线工具用。

`stardict.db`、`*.zip`、`app/src/main/assets/dict/` 均已 gitignore；
`convert.py`、`enrich_node.js`、`attribute_senses.js`、`llm_tasks.js`、`build_v6.js`、
`nllb_translate.py`（留档）与本 README 入 git；`node_modules/`（wink-nlp）与
`%TEMP%/enrich_work/` 中间产物不入 git。
