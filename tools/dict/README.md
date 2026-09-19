# 随包全量词典（FR-18，Phase 8.6）

查词兜底数据源：ECDICT（MIT License，<https://github.com/skywind3000/ECDICT>）1.0.28
子集 → 只读 SQLite 随 APK 打包（`app/src/main/assets/dict/ecdict.sqlite`，约 90MB，
**不入 git**——本目录可再生，见下）。

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
  ipa          TEXT NOT NULL,     -- ECDICT phonetic（可空串）
  definitions  TEXT NOT NULL      -- 紧凑 JSON [["partOfSpeech","meaningEN","meaningCN"],…] 已按词性序排
) WITHOUT ROWID;
```

App 侧消费：`shared/src/androidMain/.../platform/BundledDictionaryProvider.kt`
（首次 assets → filesDir 复制后只读打开）；接线 `app/di/AppModule.kt`
（种子优先 → 全量兜底的复合 DictionaryProvider，DB miss 按需导入走 SeedImporter）。

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
```

`stardict.db`、`*.zip`、`app/src/main/assets/dict/` 均已 gitignore；
`convert.py` 与本 README 入 git。
