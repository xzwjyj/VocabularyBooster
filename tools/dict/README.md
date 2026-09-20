# 随包全量词典（FR-18，Phase 8.6）

查词兜底数据源：ECDICT（MIT License，<https://github.com/skywind3000/ECDICT>）1.0.28
子集 → 只读 SQLite 随 APK 打包（`app/src/main/assets/dict/ecdict.sqlite`，约 123MB
含 Tatoeba 例句，**不入 git**——本目录可再生，见下）。

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
  examples     TEXT NOT NULL,     -- 紧凑 JSON [["英文原句","中文译文"],…]（Tatoeba，译文可空串）
  ipaBr        TEXT               -- 英音音标（ipa-dict en_UK；可空——短语/长尾无英音属预期）
) WITHOUT ROWID;
-- PRAGMA user_version = 5：资产版本（v1 无例句列 / v2 例句对格式 / v3 粗口过滤 / v4 全量中文翻译 / v5 ipa-dict 英音+美音补缺）。
-- 版本 = 修订号：数据重建也必须递增，否则设备端同 user_version 的旧缓存不会重拷（BundledDictionaryProvider）
```

App 侧消费：`shared/src/androidMain/.../platform/BundledDictionaryProvider.kt`
（首次 assets → filesDir 复制后只读打开；例句挂首释义——句子级数据无词性归属）；接线 `app/di/AppModule.kt`
（种子优先 → 全量兜底的复合 DictionaryProvider，DB miss 按需导入走 SeedImporter；
增强前已导入词条 lookup 时幂等回填例句）。

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
```

运行时分工注记：本机 CPython 在语料扫描上确定性段错误（机器级故障）——
**重活（scan/cmn/links）走 Node/V8（enrich_node.js），Python 只做 sqlite 构建阶段**。
`enrich_with_examples.py` 保留 Python 子进程分片 + 重试 + 断点续传实现（历史路径，
机器修复后可用 `python enrich_with_examples.py` 一键全流程）；分片落 `%TEMP%/enrich_work/`。
Tatoeba 数据（sentences/links，~1.2GB 解压）不入 git、不随包，仅离线工具用。

`stardict.db`、`*.zip`、`app/src/main/assets/dict/` 均已 gitignore；
`convert.py` 与本 README 入 git。
