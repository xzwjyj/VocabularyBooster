# 规格变更请求：FR-22 双音标显示与发音口音设置

> 日期：2026-09-20
> 需求来源：用户（学习会话界面需同时显示美音音标和英音音标，可在设置界面选择读英音还是美音）

## 变更概述

1. **双音标显示**：学习会话面板与查词界面显示 `US /…/`、`UK /…/` 双音标——**有则双显、无则单显**（数据源覆盖所限，短语无英音属预期）。
2. **发音口音设置**：设置页新增「发音口音：美音（默认）/ 英音」——控制英文段（单词发音/拼写/英文释义/例句原文）TTS 朗读口音 en-US / en-GB；中文段不受影响。

## 背景与现状审计（2026-09-20 实测）

### 链路：ipaBr 全链路已预留，零 schema 迁移

- `Word` 表 `ipaBr` 列建表即在（schema v1 起）；`Word` 领域模型、`DictionaryWord` 端口字段、
  `SeedImporter` 新词导入路径均已带 ipaBr——**DB/端口/模型三层零改动**（缺的只是数据与展示）。
- 种子 59 词部分已带 ipaBr（abandon/absorb/acknowledge…），缺的顺带补全。

### 数据：ECDICT 无英音（硬约束），源裁决 = ipa-dict

- ECDICT `detail` 列实测全空（子集 836,398 行仅 1 行非空且为字面 `""`）——**无英音音标**；
  现有 217,591 条单一 `phonetic`（类 DJ 记法，口音归属含糊）维持现状存 `ipaAm`，不重新标注。
- **用户裁决（2026-09-20）：数据源 = ipa-dict（open-dict-data，MIT License）**：
  `en_UK.txt` 65,119 条 / `en_US.txt` 125,927 条，`word\t/IPA/` 格式（一行可多候选，取首个）。
- 与子集实测交集：**UK 命中 64,307 / US 命中 83,394；可双音标同显 53,078 词**
  （常用单词覆盖良好；短语源不收、恒无英音）；副产品 **en_US 可补 33,025 条 ECDICT 缺失的美音**
  （音标覆盖率 217,591 → 250,616，+15%）。

### TTS：口音切换点

- `Lang` 枚举现仅 `EN_US`/`ZH_CN`；SegmentBuilder 六处硬编码 `Lang.EN_US`；
  `TtsLocales.localeFor` 映射 `Locale.US`。
- FR-19 音色（`ttsVoiceEn` 为 en-US 音色 id）与口音正交：音色仅在其 locale 与段语言一致时应用，
  不一致走 `setLanguage`（既有失效回退语义自然覆盖）。

## 技术方案

### 1. 资产 v5（tools/dict）

- `DictEntry` 表 +`ipaBr` 列；构建管线并入 ipa-dict：en_UK → `ipaBr`，en_US → **补 `ipa` 空缺**
  （只补空缺、绝不覆盖，与运行时回填同口径）；`PRAGMA user_version=5`（数据重建递增铁律）。
- 产物增量预估 +2~3MB（19 万行音标文本）；`stardict.db`/下载件不入 git，README 再生步骤 +ipa-dict 下载。

### 2. 运行时链路

- `BundledDictionaryProvider`：读 `ipaBr` 列 → `DictionaryWord.ipaBr`（字段已在）。
- **回填扩展（backfillEnhancements 四合一）**：英音回填 = `ipaBr` NULL 且源有值才写（绝不覆盖）；
  美音回填闸门不变。`Word.sq` +`updateWordIpaBr`（query-only，**schema 恒 v2 零迁移**）；
  `needsEnhancementBackfill` 闸门 = 美音缺失 ∨ **英音缺失** ∨ 零例句 ∨ TATOEBA 译文空。
- UI（有则双显、无则单显，缺失不渲染空行）：
  - 学习会话面板（FR-21）音标行：`US /…/  UK /…/`；`LearningWordDetail` +`ipaBr`；
  - 查词详情 / 查词结果头同规则；种子 59 词缺失 ipaBr 的顺带补全。

### 3. 发音口音设置

- `settings.ttsAccent`（`"EN_US"|"EN_GB"`，**缺省 EN_US**；L6 加法扩展——缺键默认、损坏抛
  `RepositoryValidationException`，与既有设置键同语义）。
- `Lang` +`EN_GB("en-GB")`；`TtsLocales.localeFor` +`Locale.UK`；SegmentBuilder 英文段语言
  由注入参数决定（编排器游标懒读设置，**生效粒度 = 下一 Segment，对齐 L4**）；SPELLING 0.8× 不变。
- **en-GB 语音缺失回退（本请求裁决点）**：口音是用户偏好而非双语硬要求——设备无 en-GB 语音时
  **回退 en-US 继续播，不进 Paused(error)**（zh-CN 缺失仍按既有硬失败，TC-AE-21 语义不变）；
  设置页显示「设备未安装英音语音，将回退美音」提示（探测复用 FR-19 availableVoices）。
- 设置页「发音口音」二选一，即时持久化（FR-15 无保存按钮惯例）。

### 4. 规格与测试同步

- PROJECT_SPEC v1.18：新增 FR-22；FR-18 增补 ipa-dict 音标条款；FR-15 设置表 +口音行；
  TEST_PLAN v2.19：回填组 +英音用例、SegmentBuilder 口音用例、设置键往返、口音 VM/冒烟、FR 矩阵 +FR-22；
  DECISION_LOG ADR-010（数据源裁决 + 记法取舍 + 回退裁决）。
- 测试：SeedImporterTest（英音补空缺不覆盖/端到端/闸门）；SegmentBuilderTest（accent 参数段语言）；
  LearningSettingsRepositoryTest（accent 往返）；TtsLocalesTest（EN_GB 映射）；SettingsViewModelTest（口音持久化）；
  androidTest SettingsUiSmoke（口音切换 KV 落库）。

## 风险与取舍

- **ECDICT 音标口音归属含糊**：维持现状存 ipaAm、不重标注（诚实记录；如未来换源再议）。
- **两种记法并存**：ipaAm 多为类 DJ 记法、ipaBr 为真 IPA（含 ɹ 等）——同屏并列视觉可接受，记录取舍。
- **短语无英音**（源只收单词）；ipa-dict 多候选取首个。
- 机器故障窗口：本机 CPython 间歇段错误——资产构建小数据量（19 万行）Python 直跑，失败重试/分批。

## 预计工作量

- 资产管线 + 重建：~1 小时（含 v5 验证）
- shared 代码 + 测试：~2 小时；app UI/VM + 测试：~1 小时
- vivo 装机验证：查词/面板双显、口音切换朗读、回填幂等、资产重拷

## 验收标准

1. 常见单词（如 dance/beautiful）查词与学习面板双音标同显；无英音的词（短语/长尾）只显美音不渲染空行。
2. 设置切换英音后英文段朗读口音变化（装机实测）；设备无英音语音时回退美音且会话不中断。
3. 口音设置重启保持；美音/英音回填幂等（只补空缺）；资产 v5 设备旧缓存自动重拷。

---

**请批准后执行**
