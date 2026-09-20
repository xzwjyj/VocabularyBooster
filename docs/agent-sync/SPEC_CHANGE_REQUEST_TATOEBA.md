# 规格变更请求：FR-18 词典例句增强

> 日期：2026-09-19

## 变更概述

为 ECDICT 全量词典（83.6万词条）补充英文例句，数据源为 Tatoeba。

## 背景

- FR-18 当前实现：ECDICT 836,398 条，仅含释义/音标，无例句
- 例句仅存在于精选种子词（~59词），符合 FR-3 设计约定
- 用户需求：为全部词条补充中英文例句

## 技术方案

### 数据源
- **Tatoeba** (https://tatoeba.org) —— CC-BY 2.0 许可
- 英文句子库：~290万条
- 下载：https://tatoeba.org/en/downloads（"Sentences in English"）

### 对齐策略
1. 下载 Tatoeba English 句子库
2. 提取 ECDICT 词条中的英文词作为 key
3. 模糊匹配：将包含该词的英文句子作为例句
4. 清洗：去重、截断超长句子（>500字符）
5. 每词限制：最多 4 条例句

### 数据模型变更
```kotlin
// DictionaryWord 新增字段
data class DictionaryWord(
    val text: String,
    val ipaAm: String?,
    val definitions: List<DictionaryDefinitionEntry>,
    val examples: List<DictionaryExample>, // 新增
)

data class DictionaryExample(
    val sentenceEn: String,
    val sentenceZh: String?, // Tatoeba 无中文翻译，暂空
)
```

### 资产预估
- 原词典：85.8 MB
- 新增例句：约 2-4 条/词 × 83万词 ≈ 500MB 原始
- 压缩后：预计 +150~250 MB APK

### 实现步骤
1. 下载 Tatoeba English sentences
2. 编写 `tools/dict/enrich_with_examples.py`：读取 ECDICT + Tatoeba → 新 SQLite
3. 更新 convert.py 输出格式（definitions + examples JSON）
4. 重新生成 `assets/dict/ecdict.sqlite`
5. 验证：查词显示例句

## 风险
- 对齐覆盖率不保证 100%（模糊匹配）
- APK 体积显著增加
- 构建时间变长

## 预计工作量
- 脚本开发：~2 小时
- 数据处理：~10 分钟（83万词）
- 测试验证：~1 小时

## 验收标准
1. 随机抽查 10 个常见词，例句显示正确
2. APK 大小增幅记录
3. 查词功能无回归

---

**请批准后执行**

（用户已批准，2026-09-19。以下为实现记录。）

## 实现记录（2026-09-19，与原方案的偏差均已按实际落地修正）

1. **中文译文按原方案为空 → 实际已配上**：下载 Tatoeba 全量 `links.csv`（句对映射，435MB），
   英文句 id ↔ 中文（cmn）句 id 配对，`examples` 列落为 `[["英文原句","中文译文"],…]`；
   无配对句的译文为空串（UI 只渲染英文原句）。
2. **数据模型**：未在 `DictionaryWord` 加 `examples` 字段（原方案草案）——例句经既有
   `DictionaryDefinitionEntry.examples` 通道挂**首释义**（句子级数据无词性归属），
   SeedImporter / 领域模型 / 下游全链路对**新导入**零改动。
3. **`ExampleSourceType` 新增 `TATOEBA`**（Word.kt + WordDetailScreen 标签"阅历来源"Tatoeba）；
   DB sourceType 字符串 `TATOEBA`。
4. **资产版本化**：资产 `PRAGMA user_version`；设备端 filesDir 旧缓存
   由 BundledDictionaryProvider 判定过期自动重拷。装机验证发现中间版与终版同为 v2
   导致旧缓存未被重拷（粗口例句漏网）→ 修正为 **v3**，且版本号此后按**数据修订**递增
   （不只格式变更），见 DECISION_LOG ADR-008。
5. **例句回填**（增强前已导入词条，如 karma）：`SeedImporter.importExamplesOnly()`——
   单事务、同句去重、只补不删、译文空→补译文不重复建行；`WordRepository.lookup`
   命中本地库但零例句/Tatoeba 译文缺失时触发，补齐后重读。
   完整词条（含例句）不触发——"DB 命中不打扰词典源"不变量保留（测试已更新口径）。
6. **管线工程约束**：本机 CPython 在语料扫描上确定性段错误（机器级故障，同日 JVM/V8 亦中招）
   → 重扫描与 links join 改用 **Node/V8（tools/dict/enrich_node.js）**单进程完成，
   Python 仅保留 sqlite 构建阶段。见 tools/dict/README.md 再生步骤。
   **实绩数字**：836,398 行重建；107,094 词条（词+短语，n-gram 匹配）带例句；
   30,516 例句带中文译文（zh 优先选择）；资产 123MB、APK 143.9MB（较前 +13.6MB）；
   粗口/成人内容词边界过滤已生效（karma 例句由粗口替换为干净句）。
7. **规格同步**：PROJECT_SPEC v1.15（FR-3 来源类型 +TATOEBA、FR-18 例句增强/回填条款）、
   TEST_PLAN v2.17（§4.8 词典组例句回填用例）、DECISION_LOG（Tatoeba 挂载与回填决策）。
