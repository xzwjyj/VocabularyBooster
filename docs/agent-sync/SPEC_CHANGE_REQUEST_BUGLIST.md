# 规格变更请求 — Phase 8.6 打磨批（bug list 四项）

> 状态：已批准（用户 2026-09-19 指令「实现 bug list 项目」+ 两项裁决：词典全量 / TTS 本地音色） | 作者：Claude | 日期：2026-09-19

## 变更概述

**类型**：新功能 ×3 + 打磨 ×1

| # | bug list 原文 | 变更 |
|---|---|---|
| 1 | 查词收入的词汇表不全，收入所有英语单词以及短语 | **FR-18** 全量离线词典（ECDICT 77 万词条）+ 查词按需导入 |
| 2 | 播放中英文语音机械不自然，改成接近自然人声语音播放方案，并在设置里提供不同音色支持 | **FR-19** TTS 音色枚举/选择/持久化 + 系统语音设置直达 |
| 3 | 勋章里增加“统计”显示区（今日学习&复习词数、累计学习词数、今日/累计学习时长；日/月/年曲线） | **FR-20** 学习统计（查询聚合 + 统计卡 + 图表页） |
| 4 | 给 app 提供一个好看的 UI 图标 | 自适应应用图标（矢量，无 FR，ADR-007 记录） |

## 变更内容

### FR-18 全量离线词典与按需导入（用户裁决：全量 77 万词）

- 数据源：**ECDICT（skywind3000，MIT 许可）** full 版 ~77 万词条（单词 + 数据源收录的短语/多词条目），转换为只读 SQLite 随 APK 打包（`assets/dict/`）；
- 转换映射（离线工具一次性完成，App 只消费结果）：`translation` 按行拆 `n./vt./a./ad.…` 前缀 → DefinitionEntry（vt./vi./aux.→verb，a.→adjective，ad.→adverb，无前缀行→`other`@90，§3.1 逃逸口）；`meaningEN` 取 `definition` 列对应行，缺失存空串（NOT NULL 兼容）；例句恒空（数据源无例句）；
- **查询路径**：`WordRepository.lookup` DB 未命中 → 复合 DictionaryProvider（精选种子优先 → 全量词典兜底）→ 命中则**按需导入该词**（幂等，与种子同代码路径）后返回；未命中 → “未收录”；
- 首启种子导入维持精选 50 词不变（例句契约、冒烟 fixtures 零变化）；
- FR-3 注记：**「每条释义至少 1 例句」契约仅约束精选种子**；全量词典词条例句可为空（无例句可勾选，释义照常可存/可播）。

### FR-19 TTS 音色选择（用户裁决：本地枚举）

- `SpeechSynthesizer` 端口加法扩展 `availableVoices(lang)`；Android actual 枚举引擎音色（en-US / zh-CN 分列，排除 network 音色）；
- 设置页新增「语音音色」区：英语/中文音色选择器 + 「系统语音设置」直达（下载更多高自然度音色）；
- 选择经 `LearningSettingsRepository` +2 键持久化（L6 加法先例）；`TtsSpeechSynthesizer` 逐段应用 `setVoice`（无效/未选 → `setLanguage` 兜底）；
- 口径（诚实约束）：自然度上限 = 设备已安装音色；不引入云端语音（离线 MVP）。

### FR-20 学习统计

- 勋章 Tab 顶部统计卡：今日学习词数 / 今日复习词数 / 累计学习词数（不含复习）/ 今日学习时长 / 累计学习时长；点击 → 统计详情页（日/月/年切换 + 学习词数、学习时长两组柱状图，Compose Canvas 自绘）；
- 口径：**学习** = 当日**首次掌握**的去重词数（跨本）；**复习** = 当日掌握事件中此前（今日之前）已掌握过的词；**时长** = 当日会话（含 ABANDONED/COMPLETED，endedAt 非空）时长合计；
- 实现：新端口 `LearningStatsRepository`（查询聚合，时区安全：Kotlin 侧 kotlinx-datetime 分桶）；**查询 query-only，零迁移**。

### 应用图标（无 FR）

- 自适应图标（mipmap-anydpi-v26 + 矢量前景，minSdk 26 无需 PNG 回退）+ manifest `android:icon/roundIcon`；`monochrome` 支持Android 13+ 主题图标。

## 影响分析

- PROJECT_SPEC v1.14（FR-18/19/20 + FR-3/FR-16 注记）；ARCHITECTURE §5（端口矩阵 +BundledDictionaryProvider actual +SpeechSynthesizer.availableVoices）；DATABASE_SCHEMA v1.10（统计查询 query-only）；TEST_PLAN v2.16（TC-DICT/TC-VOICE/TC-STAT 组）；ROADMAP v1.11（Phase 8.6）
- 代码：shared（WordRepository 回填、端口 +2、统计仓储、设置 +2 键）、app（设置音色区、统计 UI、图标、BundledDictionaryProvider actual）、离线转换工具
- **零 schema 迁移**（全部 query-only / 加法）

## 风险

- **中风险**（APK 体积 +~40–70MB、assets→filesDir 首次复制 ~100MB 一次性 I/O、77 万词条首查延迟可控：PK 查询）；其余低风险
