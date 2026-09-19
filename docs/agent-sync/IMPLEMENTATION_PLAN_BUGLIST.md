# 实现计划 — Phase 8.6 打磨批（bug list 四项）

> 状态：已批准（用户裁决：词典全量 77 万 / TTS 本地音色；实现顺序如下） | 日期：2026-09-19 | 前置：SCR + AUDIT 定稿

## Step 0 规格同步（先于代码）

PROJECT_SPEC v1.14（FR-18/19/20 + FR-3 注记 + FR-16 v1.1 注记 + 版本记录）→ ARCHITECTURE §5（BundledDictionaryProvider actual / SpeechSynthesizer.availableVoices / LearningStatsRepository 端口）→ DATABASE_SCHEMA v1.10（统计查询 query-only）→ TEST_PLAN v2.16（TC-DICT/TC-VOICE/TC-STAT）→ ROADMAP v1.11（Phase 8.6）。

## Step 1 应用图标（bug#4）

1. `res/drawable/ic_launcher_foreground.xml`（矢量：书本 + V 字 + 声波意象，白前景）；`values/ic_launcher_background.xml`（品牌色）
2. `res/mipmap-anydpi-v26/ic_launcher.xml` / `ic_launcher_round.xml`（adaptive + monochrome）
3. Manifest `application` + `android:icon` / `android:roundIcon`
4. 验证：assembleDebug + 模拟器安装目视；门禁 detekt（XML 不涉）+ 全量回归无影响

## Step 2 TTS 音色（bug#2，FR-19）

1. shared：`SpeechSynthesizer` +`availableVoices(lang): List<TtsVoice>`（`TtsVoice(id, displayName, quality?)`，explicitApi）+ 同步全部 Fake（commonTest/app）
2. shared：`LearningSettingsRepository` +2（`ttsVoiceEn/ttsVoiceZh` getter/setter，L6 先例）
3. androidMain：`TtsSpeechSynthesizer` 实现 `availableVoices`（`tts.voices` 按 locale 前缀过滤、剔 `network` 特征）；`speak()` 读设置 → `setVoice` 命中才用，否则 `setLanguage` 兜底（音色名缓存）
4. app：设置页「语音音色」区——英语/中文两个选择器（当前选中显示 + 系统默认项）+「打开系统语音设置」按钮（TTS_SETTINGS intent，try-catch）
5. 测试：jvmTest Fake +availableVoices（编译级）；app 单测 SettingsViewModel 音色读写投影；连接测试 SettingsUiSmokeTest + 音色选择落库冒烟
6. 门禁：jvmTest / app unit / detekt×2 / 边界 / assembleDebug / connected

## Step 3 学习统计（bug#3，FR-20）

1. shared：`LearningStatsRepository` 端口 + `LearningStatEntry`（日聚合：学习词数/复习词数/时长）+ `LearningStatsSummary`（今日/累计四数）
2. .sq（query-only，零迁移）：SessionWord.sq +`selectMasteredEvents`（wordId, masteredAt 全量事件）、LearningSession.sq +`selectEndedSessions`（startedAt, endedAt）
3. `SqlDelightLearningStatsRepository`：Kotlin 侧分桶（注入 `TimeZone`，默认 currentSystemDefault）——首词首次掌握 = 学习；当日事件中今日前已掌握 = 复习；时长 = endedAt−startedAt 按结束日归集；`dailyBuckets(range)` / `summary()`
4. app：`StatsViewModel` + AchievementsScreen 顶部统计卡（四数 + 可点击）→ `StatsDetailScreen`（日[近30]/月[近12]/年[全部] segmented + Canvas 柱状图 ×2：学习词数、时长）
5. 测试：jvmTest 统计聚合（首词去重/复习判定/跨午夜时区注入/ACTIVE 无 endedAt 排除/空库零值）；连接测试统计卡冒烟（学一词后今日+1）
6. 门禁同上

## Step 4 全量词典（bug#1，FR-18）

1. 离线工具（本机一次性，不入 App 构建）：下载 ECDICT full CSV → 转换只读 SQLite（`DictEntry(normalized PK, text, ipa, definitionsJson)`；POS 映射/EN 对齐/`other`@90/短语原样收录；报告词条数与短语数）→ 产物 `app/src/main/assets/dict/ecdict.sqlite`
2. shared：`WordRepository` 实现注入 `DictionaryProvider`（Koin 复合装配：种子优先 → 全量兜底）；DB miss → provider 命中 → `SeedImporter.import(listOf(word))` 按需导入 → 重读 DB 返回
3. androidMain：`BundledDictionaryProvider`（首次使用 assets→filesDir 复制，只读打开；`DictionaryWord` 反序列化 `definitionsJson`；索引查找规范化键）
4. app：词详情 meaningEN 空串不渲染空行（EN 缺失只显 CN，I-6 不变量不受影响——EN 存在才显示）
5. 测试：jvmTest 按需导入（Fake provider：miss→导入→命中；DB 已有优先不覆盖）；SeedDictionaryCoverageTest 不变（契约分域注记）；连接测试查生僻词冒烟（真实 asset）
6. 门禁同上 + APK 体积核对
7. `assets/dict/ecdict.sqlite` 入库方式：>100MB 超 GitHub 限制 → 仓库不提交二进制，`.gitignore` 排除 + 构建缺失时 `assembleDebug` 报错提示（工具脚本 `tools/dict/README` 说明再生步骤）——**裁决：产物可再生成，不入 git**

## Step 5 收尾（每项完成后即注释 bug list.md 对应行）

每步：门禁绿 → commit（`feat(icon)` / `feat(tts)` / `feat(stats)` / `feat(dictionary)`）→ bug list.md 注释对应项；全部完成 → ADR-007 → PHASE_8_6_REPORT.md + ROADMAP 状态 + vivo 走查清单。

## 边界与不做

- 不做 TTS 引擎级切换 / 云端语音；音色自然度上限 = 设备已装音色（设置页有直达下载入口）
- 统计不做跨设备同步/导出；不做词频/复习间隔分析
- 词典不改精选种子与例句契约；不引入在线 API；APK 内词典不加密
- 图标不提供 PNG 密度桶（minSdk 26）
