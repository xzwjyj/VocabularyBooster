# 审计报告 — Phase 8.6 打磨批（bug list 四项）

> 状态：定稿（SCR 同日） | 日期：2026-09-19 | 范围：bug list.md 全部 4 项

## 1. 词典覆盖（bug#1）

**现状**：
- `SeedData.kt` = Kotlin 字符串常量 `SEED_DICTIONARY_JSON`（774 行，精选 50 词，含例句/多词性/公版来源）；
- 首启 `SeedImporter.ensureSeeded`（Application onCreate，IO scope）全量导入 DB；此后 `SqlDelightWordRepository.lookup` **只查 DB**——`DictionaryProvider.lookup` 端口在运行时查询路径上无人调用（仅测试用）；
- 词未导入 → `WordDetailViewModel` 显示“词库中找不到”。

**缺口**：50 词之外全部“查不到”。

**审计发现（关键）**：
- `SeedDictionaryCoverageTest` 契约（每释义 ≥1 例句、POS 白名单、来源标注）**只针对精选种子**——扩容数据无例句，契约须显式分域（FR-3 注记），否则测试语义被破坏；
- `DefinitionEntry.meaningEN/CN` NOT NULL——ECDICT 缺英义时须存空串；
- DOMAIN_MODEL §3.1 已有「≥90 其他」逃逸口 → 无前缀译行映射 `other`@90 **零规格新增**；
- IMPORT_SPEC §8 已预留「导入词回填任务」概念——按需导入与既有设计同向；
- 种子导入路径（`SeedImporter.import`）幂等且 generic——按需导入单词可直接复用，零新写入代码。

**架构决策**：**按需导入**（DB miss → provider 兜底 → 命中即导入该词），不整库导入——77 万词整库首启导入需数分钟且 DB 膨胀数百万行，不可接受。

**数据源**：ECDICT full（MIT；~77 万词条 CSV ~63MB；字段 word/phonetic/definition/translation/pos/collins/tag/bnc/frq/exchange）。转换工具离线跑一次，产物只读 SQLite（`normalized` PK + `text/ipa/definitionsJson`），App 侧零解析逻辑。短语 = 数据源收录的多词条目（实现后报告实际条数）。

**体积口径**：SQLite ~90–110MB → APK deflate 后 +~45MB；首次使用时 assets→filesDir 复制（一次性，后台）。Phase 9 NFR 批可再评估免复制方案。

## 2. TTS 音色（bug#2）

**现状**：`TtsSpeechSynthesizer`（shared/androidMain）单引擎实例，逐段 `setLanguage(en-US/zh-CN)` + `setSpeechRate/setPitch`；**无任何音色 API 使用**；设置页五项无语音项。

**可行路径**：`TextToSpeech.voices` / `setVoice` 平台 API 齐备（音色隐含 locale）；`Intent("com.android.settings.TTS_SETTINGS")` 可直达系统音色下载页。vivo 自带引擎通常含高自然度音色。

**审计发现**：
- 端口 `SpeechSynthesizer` 加法扩展 +1 方法 → 需同步 commonTest/app 全部 Fake（L6 先例）；
- 音色持久化走 `LearningSettingsRepository` +2 键（L6 加法先例，`upsertSetting` 在位 → 零 .sq 变更）；
- 引擎级切换（多引擎选择）不做——运行时重建 TTS 实例与 Phase 5 稳定性权衡后裁剪（边界记录）；音色失效（卸载）→ setLanguage 兜底。

## 3. 学习统计（bug#3）

**现状数据可用性**：
- `SessionWord.masteredAt`（每次会话掌握事件，幂等首值）+ `LearningSession.startedAt/endedAt`（ABANDONED/COMPLETED 落 endedAt；崩溃未恢复的 ACTIVE 行 endedAt NULL → 不计时长，可接受口径）；
- `WordMastery` 为 (本,词) 快照，不适合做“跨本去重首次掌握”事件源——**统计口径锚定 SessionWord**。

**审计结论**：全部所需数据在位；聚合查询 query-only（SELECT + 分桶），**零迁移**。分桶在 Kotlin（kotlinx-datetime 注入 TimeZone）而非 SQL strftime（UTC 陷阱）——测试确定性 + 本地时区正确。

**缺口**：无统计端口/UI；Compose 无图表依赖 → Canvas 自绘柱状图（无新三方库）。

## 4. 应用图标（bug#4）

**现状**：`AndroidManifest.xml` **无 `android:icon` 属性**；`res/` 仅 raw+values——系统默认图标属实。

**结论**：minSdk 26 → 仅需 `mipmap-anydpi-v26` 自适应图标（前景/背景矢量 + monochrome），无需 PNG 密度桶。零代码逻辑，纯资产。

## 5. 门禁与风险汇总

| 项 | 风险 | 缓解 |
|---|---|---|
| 词典 | APK +~45MB；首次复制 100MB I/O | 用户已裁决接受；复制异步后台 |
| 词典 | 77 万行转换工具跨平台跑批 | 离线一次性，产物入库（LFS/普通 blob 视大小决定） |
| TTS | 音色枚举随设备差异大 | 未选/失效 → setLanguage 兜底零回归 |
| 统计 | 时区/跨午夜口径 | Kotlin 分桶 + 注入 TimeZone 测试 |
| 图标 | 无 | — |

**结论**：四项均可安全落地；两项零迁移；建议实现顺序 图标 → TTS → 统计 → 词典（外部数据下载风险置底）。
