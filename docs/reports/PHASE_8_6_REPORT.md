# PHASE 8_6 REPORT — 打磨批（bug list 四项）

> 日期：2026-09-19 ｜ 协议文档：docs/agent-sync/`*_BUGLIST.md` ｜ 决策：ADR-007
> 范围：用户 bug list（2026-09-19）四项——词典收录不全 / 语音机械不自然 / 勋章页统计缺失 / 默认图标。
> 用户裁决：词典 = **ECDICT 全量**（随包只读 SQLite）；TTS = **本地音色枚举+选择**（离线，不引入云端）。

## 1. 交付摘要（四 commit，实现顺序：图标 → TTS → 统计 → 词典）

| 项 | commit | 交付 |
|---|---|---|
| 图标 | `8d6c71f` | 自适应矢量图标（书本+声波，含 Android 13+ 主题图标）——manifest 原无 icon |
| FR-19 TTS 音色 | `c991efb` | 设置页英/中音色枚举选择 + 系统语音设置直达；自然度上限 = 设备已装音色 |
| FR-20 学习统计 | `bd46150` | 勋章页统计卡（今日学习/复习、累计学习、今日/累计时长）+ 日/月/年柱状图详情页 |
| FR-18 全量词典 | `dd17820` | 随包 ECDICT 词典 836,398 条（449,387 词 + 387,011 短语，含音标 217,591）；查词 DB 未命中自动按需导入（幂等单事务），精选种子词（含例句）优先 |
| 收尾修复 | `fix(dictionary)` + `test(stats)` + `test(dictionary)` | ① Koin 自引用修复（见 §4）② 统计冒烟清表/幂等/unmerged 树断言（见 §4）③ 词典冒烟头词大小写断言 |

## 2. 口径与架构要点

### FR-20 统计（PROJECT_SPEC v1.14）
- **学习** = 当日首次掌握去重词数（跨本，按 wordId 最早 masteredAt 的本地日）；**复习** = 事件本地日**严格晚于**首掌日（同日重复既非学亦非复）；**时长** = endedAt−startedAt 按结束日归集（ACTIVE endedAt NULL 不计）。
- **分桶在 Kotlin**（注入 Clock/TimeZone）——SQL `strftime('unixepoch'` 为 UTC 会错本地日界（铁律 10）；Q8/Q9 只取原始事件流，聚合纯 Kotlin。
- 窗口：DAY = 近 30 天含今日 / MONTH = 近 12 个月 / YEAR = 首事件年至今年，零值填充。

### FR-18 词典（ARCHITECTURE §5 端口零变更）
- 数据源：ECDICT 1.0.28 sqlite 发行包（MIT）→ 子集 `bnc IS NOT NULL` + charset `[A-Za-z][A-Za-z \-']{0,39}` = **836,398 条**；`DictEntry(normalized PRIMARY KEY, text, ipa, definitions) WITHOUT ROWID`；normalized ≡ `toNormalizedWordText()`。
- definitions = **紧凑数组 JSON** `[["pos","en","cn"],…]`（对象 JSON 键开销会膨胀至 ~200MB；数组编码资产 85.8MB，APK 压缩后全包 **130.3 MB**）；义项上限 8；EN↔CN 仅双方同位都有才配对，剩余单侧独立成条；例句恒空（FR-3 例句契约仅精选种子）。
- 按需导入 = DB miss → `FallbackDictionaryProvider`（种子优先 → 随包兜底）→ `SeedImporter.import(listOf(word))` 幂等单事务 → 重读 DB。
- Koin 4.0.2 无 override → `DictionaryProvider` 全库唯一绑定在 appModule（DataModule 撤绑定）；仓储两新依赖可空（`getOrNull()` 装配，手工 Koin 图纯 DB 行为不变）；资产缺席/损坏 → 永久 null 降级（查词增强非硬依赖）。
- 词典资产 >100MB 规则：原始 db 不入 git（`.gitignore`），`tools/dict/convert.py` + README 三步再生（curl 216MB zip → extract → convert ~10s）。

## 3. 质量门禁

| 门禁 | 结果 |
|---|---|
| :shared:jvmTest | **28/29 测试类分批全绿**（+7 统计组：首掌去重/跨日复习/时长按结束日/ACTIVE 不计/空库零值/月上卷/注入时区日界；+3 按需导入组：命中导入并二次走 DB / miss 零增长 / DB 命中不打扰）。`ImportPerfTest` 当日下午起被机器级原生故障阻塞（见 §4 环境事件——当日上午 dd17820 门禁全量 290/0 含它通过，当日 import 路径零代码改动） |
| :shared:testDebugUnitTest | 绿（commonTest Android 编译运行） |
| :app:testDebugUnitTest | **51/0**（强制重跑新鲜执行；+3 StatsViewModel：加载汇总/粒度切换/失败文案） |
| detekt ×2 + checkPlatformBoundaries | 绿 |
| :app:assembleDebug | 绿（APK **130.3 MB**；词典资产 85.8 MB，Vosk 模型为 Phase 5 既有） |
| :app:connectedDebugAndroidTest（模拟器） | **54 执行 / 49 通过 / 5 skip / 0 失败**（+1 统计卡冒烟 +1 词典冒烟类；skip 5 = 权限态×2、TTS 初始化失败注入×1、smokeJ 权限态×1、音色持久化×1——干净模拟器无已下载音色，vivo 走查覆盖） |

## 4. 偏差与备注

- **Koin 自引用崩溃（生产阻断，connected 首跑捕获）**：appModule 的 `single<DictionaryProvider>` 内 `primary = get()` 按构造参数类型解析回**本绑定自身** → 真实图首次启动 StackOverflowError（进程崩溃）。此前词典批 connected 因统计冒烟编译错未跑成，该缺陷漏网——修复 = `get<SeedDictionaryProvider>()` 按具体类型解析（种子绑定注册类型即具体类）。教训：**Koin 复合绑定的同接口注入必须显式具体类型；绑定变更必须过一次真实图启动（connected 首例即 launch 冒烟）**。
- **脏快照伪回归（环境）**：一次 connected 全量因主库页损坏中途崩（`(11) database corruption` → androidx 损坏处置**删除 vocabulary.db** → 11 用例级联"already-closed"）。根因 = 模拟器进程随会话退出被强杀、快照带未 checkpoint 的 WAL，复启未加 `-wipe-data` 从脏快照恢复。`-wipe-data` 重启后同套件全绿。**经验：模拟器非正常终止后必须 -wipe-data 复启再跑 connected**。
- **统计卡冒烟三修**：① 生成的 `VocabularyDatabase` 只暴露 queries（无 public driver）→ 清表改走测试支援 query `deleteAllSessionWords`（清 LearningSession 会被 DERIVED 本 `sourceSessionId` 无级联外键拦，且会话残留不影响断言口径）；② 种子改幂等（词先查后插，am instrument 重跑不卸载不清库）；③ **clickable 卡片 mergeDescendants 吞内层 tag 节点 → 值断言必须 `useUnmergedTree = true`**（merged 树上唯一命中节点是整卡、Text 为全卡拼接串）。
- **词典冒烟断言**：`lookup("  Serendipity ")` 的 `text` = ECDICT 头词（惯例小写"serendipity"），非查询原文——大小写归一只用于命中，断言按头词形态。
- PLAN 两处偏差已记 ADR-007：definitions 紧凑数组 JSON（对象 JSON ~200MB 不可行）；词典产物不入 git 由工具再生。
- **ImportPerfTest 环境阻塞（非回归，未解决）**：当日下午起该基准单类必原生崩溃（`0xC0000005` @ sqlitejdbc.dll `column_name_utf8`，hs_err×9 于 shared/）或执行器挂死（CPU 归零）；当日上午 dd17820 门禁全量含它 290/0 通过、import 路径当日零改动、其余 28 类分批全绿 → 判定机器级（8.5 报告 §4 同款前科加重；`--stop` 释放内存与清 Temp DLL 缓存均无效）。**建议重启机器后 `./gradlew :shared:jvmTest --tests "com.vocabularybooster.ImportPerfTest"` 复核**；不以此阻塞 Phase 8.6 收尾（该基准当日有通过记录）。
- `WordDetailScreen` 空侧释义不渲染（FR-18 词典词常缺 CN 侧，空行渲染观感差）。
- 环境：jvmTest native 崩溃阈值经验复用（空闲内存 < ~3G 先 `--stop`）；强杀执行器会留毒 test-results 目录（`binaryResultsDirectory` 不可读 → 删除该目录重跑即可）。

## 5. 走查清单（vivo，待用户执行）

1. 装机目视：桌面/启动图标为书本+声波矢量图标；深色壁纸主题图标单查
2. 查词：输一个种子外生僻词（如 serendipity）与一个短语（如 look forward to）→ 详情有释义；音标有则显示；再次查同一词瞬时返回（已落库）
3. 设置 → 英/中音色切换 → 播放会话听音色变化；「系统语音设置」直达
4. 勋章页顶部统计卡有数（今日学习/复习、累计、时长）→ 点入 → 日/月/年切换柱状图有形
5. 播放中观感：无 CN 侧释义的词不出现空行

## 6. 文档同步

PROJECT_SPEC v1.14（FR-18/19/20 + 验收行）/ ARCHITECTURE（随包词典资产注记）/ TEST_PLAN（§统计组 + 词典按需导入组）/ DATABASE_SCHEMA（Q8/Q9 + §6 映射）/ ROADMAP v1.12 / agent-sync `*_BUGLIST.md` + ADR-007。
