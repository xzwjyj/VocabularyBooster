# TEST_PLAN — 测试策略

> 状态：Phase 0 定稿 ｜ 版本 1.0 ｜ 日期：2026-09-01
> 原则：**核心逻辑在 JVM 上测试**（不依赖模拟器/真机）；设备层只测"胶水"。

---

## 1. 测试金字塔

```
        ┌──────────────┐
        │ 手动矩阵 §7   │  真机语音/扬声器/TTS 质量（少量）
        ├──────────────┤
        │ androidTest  │  Compose UI 测试、真机 DB/驱动冒烟（少量）
        ├──────────────┤
        │ commonTest   │  领域/学习引擎/播放编排/命令解析/导入/勋章
        │ （JVM 全量） │  + SQLDelight 查询测试（JVM sqlite driver）
        └──────────────┘
```

**关键约束**：所有引擎测试只用 Fake 端口（`FakeSpeechSynthesizer / FakeAudioPlayer / FakeRecognizer / 内存 SQLDelight driver`），虚拟时间（`TestDispatcher`）——**可在 Windows CI 无模拟器运行**（NFR-6/NFR-9）。

## 2. 工具矩阵

| 用途 | 工具 |
|---|---|
| 单测框架 | kotlin.test（commonTest，JVM/iOS 共用同一套断言） |
| 协程/Flow | kotlinx-coroutines-test + Turbine |
| DB 测试 | SQLDelight JDBC sqlite driver（JVM）；真机 AndroidSqliteDriver（androidTest） |
| UI 测试 | Compose UI Test（`createAndroidComposeRule`） |
| 架构守护 | detekt `ForbiddenImport`（commonMain 禁 `android.*`/`java.*`）+ 模块边界断言（§6） |
| 覆盖率 | Kover（shared 模块） |

## 3. 用例命名与组织

- ID 规范：`TC-<模块>-<序号>`；模块代号：`DM` 领域模型、`DB` 数据库、`LE` 学习引擎、`AE` 播放引擎、`IMP` 导入、`AC` 勋章、`ARCH` 架构守护、`UI` Android 界面；
- 测试类与被测类同包（`com.vocabularybooster.<pkg>.<SubjectTest>`）；
- 每个边界情形表条目（各规格 §10）必须一一对应至少一个用例。

## 4. 核心用例矩阵

### 4.1 TC-DM 领域与排序不变量

| ID | 用例 | 断言 |
|---|---|---|
| TC-DM-01 | 多词性词条排序 | 同 POS 全部连续；(POSOrder, defOrder) 全序正确 |
| TC-DM-02 | 乱序入库数据 | 渲染排序仍满足连续性（排序与入库顺序无关） |
| TC-DM-03 | EN/CN 顺序 | MeaningEN 恒先于 MeaningCN；Example 句先于译文（I-6/I-7） |
| TC-DM-04 | 例句原子性 | 保存/取消 Example 为整体，无半选状态 |
| TC-DM-05 | 例句选择粒度（v1.2） | 每条选中释义可勾选任意 Example 子集（含空集=仅释义）；选择的只落 `WordBookEntryExampleSelection`，底层 Example 不复制 |

### 4.2 TC-DB 数据库

| ID | 用例 |
|---|---|
| TC-DB-01 | 唯一约束生效：normalizedText / (wordBookId,wordId) / (wordBookEntryId,definitionEntryId) / (wordBookEntryId,exampleId) / (type,wordBookId) 重复插入被拒 |
| TC-DB-02 | FK 级联：删 WordBook → entries/selections/mastery 级联，Word 不动 |
| TC-DB-03 | 关键查询正确：Q1 排序 / Q2 队列 / Q3 计数 / Q4 选中释义 / Q5 派生复制 |
| TC-DB-04 | 事务原子性：MasteryMarker 中途失败 → 两个写全回滚 |
| TC-DB-05 | 派生一致性：派生本关系行与母本（除 bookId）逐行相等 |
| TC-DB-06 | 迁移链：schema v1→vN 全版本 MigrationTest（Phase 1 起基线，每版迁移必加；首个迁移 v1→v2 于 Phase 2 落地：includeExamples 移除 + WordBookEntryExampleSelection 新建 + 数据保留断言） |
| TC-DB-07 | WordBook 类型约束：`type` CHECK 生效；删除有派生子本的母本被拒（RESTRICT）；DERIVED 本 parentWordBookId/sourceSessionId 必填（**Phase 2 遗留**：仓库层删除守卫已测并绿；DB 级 CHECK/RESTRICT 断言随 Phase 3 测试电池顺手补齐，不改 Phase 2 行为） |

### 4.3 TC-LE 学习引擎（覆盖 LEARNING_ENGINE_SPEC §10 十一条边界，Phase 3 批准全集）

| ID | 用例 |
|---|---|
| TC-LE-01 | 队列构建：100 词 → 未掌握全量，entryOrder 升序；空本 / 全掌握 → 拒绝并给出明确原因（§10-1） |
| TC-LE-02 | 分组固化：groupSize=10 → 组号 0..9；词数 < groupSize → 单组/末组不足（§10-2）；中途改设置不影响现有会话 |
| TC-LE-03 | 组内循环：随机顺序 master 部分 → 推进严格跳过已掌握并回绕 |
| TC-LE-04 | "会了"语义：同词重复调用 → AlreadyMastered 幂等无重复计数（§10-5）；词刚开始播放即"会了" → 立即落库并 advance（§10-4）；导入词（无释义）"会了"照常（§10-3 掌握侧；播放分段侧由 Phase 4 TC-AE-01/02 覆盖） |
| TC-LE-05 | 组推进：组清空 → 下一组首词 |
| TC-LE-06 | 书完成：最后一词 master → COMPLETED + 事件次序（停止→状态→勋章） |
| TC-LE-07 | 退出分支 B：掌握 37/100 → 派生本恰 63 词、`type=DERIVED`、parentWordBookId/sourceSessionId 记录正确、名称格式 `yyyy-MM-dd HH:mm`（注入时区精确断言）、重名 -2、再重名 -3；释义 + 例句选择关系逐 ID 一致；派生读事务内 DB 快照，母本并发编辑不影响一致性（§10-10）。**mid-session 母本编辑三边界（交集语义，2026-09-04 裁决，真实 JDBC 集成）**：Case 1 会话外新增未掌握词 → 不进派生本（留母本，Q3 计入）；Case 2 会话中词被移出母本（SessionWord 尚在）→ 不进派生本（entryOrder/pendingTranslation 恒取自母本行）；Case 3 交集为空（MASTERED>0 且 REMAINING>0）→ **不创建空 DERIVED 本**：derivedWordBookId=null、会话 ABANDONED、endedAt 正常写入 |
| TC-LE-08 | 退出分支 A：0 掌握退出 → 无新本（不产生空本）；会话状态与学习历史保留 |
| TC-LE-09 | 崩溃恢复：杀进程模拟 → 队列/分组/掌握状态完整还原 |
| TC-LE-10 | 恢复异常两分支：书已删 → 安全 ABANDON + 提示（§10-8）；某词已从本移除 → 该词从会话剔除，其余照常（§10-9，**不**整会话 ABANDON） |
| TC-LE-11 | 退出分支 C：退出瞬间 REMAINING=0 → COMPLETED + 勋章 + 不派生（以 DB 实时状态裁决） |

> TC-LE-01…11 即 Phase 3 批准测试全集（ROADMAP Phase 3 出口条件）；与 LEARNING_ENGINE_SPEC §10 #1–#11 逐条映射如上括注。另：ACTIVE 会话唯一性不变量（LE spec §3）断言并入 TC-LE-01（存在 ACTIVE 时第二次 startSession → Rejected）。

### 4.4 TC-AE 播放引擎（AUDIO_ENGINE_SPEC §3 状态表逐行；Phase 4 批准全集 = TC-AE-01…03、07…19，TC-AE-04…06、10 由 **Phase 5 Step 1** 交付——Step 0 裁决 L1/L5 + Phase 5 裁决 D1–D5，2026-09-12）

| ID | 用例 |
|---|---|
| TC-AE-01 | 分段构建：六开关全开 → 段序 = I-8 结构（POS 排序 + 例句序）；**导入词（无选中释义）→ 仅 PRONUNCIATION + SPELLING**（LE §10-3 播放侧，Phase 3 延期项） |
| TC-AE-02 | 开关组合：随机开关 → 段序列与映射表一致；全关 → 引擎拒绝会话 |
| TC-AE-03 | 每词周期：末段完成 → 300ms guard → 窗口开启（虚拟时间断言；**P4 纯倒计时，不接识别器**，L1） |
| TC-AE-04 | 窗口命中"会了" → markMastered 被调 + 立即换词（**Phase 5 Step 1 已交付**：commonTest 语音集成 + app 单测/冒烟三层同语义） |
| TC-AE-05 | 窗口超时/噪音文本 → 换词，不误掌握（**Phase 5 Step 1 已交付**：UNKNOWN 保持监听至窗口超时；识别错误零掌握零推进——裁决 D5 红线） |
| TC-AE-06 | **TTS 期间识别关闭**：Playing 态全程 recognizer 零调用（间谍断言）（**Phase 5 Step 1 已交付**，commonTest） |
| TC-AE-07 | Pause/Resume：文件段恢复 offsetMs 精确；TTS 段重读本段（段索引不变、词索引不变） |
| TC-AE-08 | Pause/Resume 于窗口态 → 重开整窗 |
| TC-AE-09 | Next / Replay：不改掌握状态；词级重置正确 |
| TC-AE-10 | 命令解析：别名/大小写/全角/带标点/**分词空格**（Vosk 中文按字分词「会 了」，2026-09-14 vivo 实测修复）/**近音兜底白名单**（「坏了」「换了」「回来」及断字截断「会」「了了」（h 声母丢失，2026-09-17 vivo 实测）等按命中处理，2026-09-16 vivo 实测「会了」→「坏了」/「回来」/「会」零掌握修复）/**首/末字近音结构规则**（「会了」：首字∈{会,坏,换,回,惠,汇} 或 末字∈{了,啦,咯,来,呀} 即命中，v2.0 用户裁决 2026-09-17——单字残片「坏」「了」、「会了吗」「…会了」句式、h 声母丢失「了了」全覆盖；守卫翻转：「会了吗」「我觉得这个词已经会了」「，会了」及繁体「會了」（末字=了）原 UNKNOWN → 现 MASTERED；末字集 v2.3 +「呀」——2026-09-18 vivo 轻声实测「会了」→「呀」（元音 a/e 混淆，vosk_diag.log 取证），「呀」「好呀」X呀 形式随扩集命中）→ MASTERED；未知（空文本/首尾字均不在近音集「你好」「hello」/自定义别名集不适用结构规则与白名单）→ UNKNOWN（**Phase 5 Step 1 已交付**：CommandParser 纯 JVM 单测 + 编排器集成同断言） |
| TC-AE-11 | 双语切换：EN 段 en-US、CN 段 zh-CN 的 speak 请求逐段正确（rate/pitch 取设置；SPELLING 0.8×） |
| TC-AE-12 | 降级（**P4/P5 拆分，裁决 L7**）：P4 断言——例句音频加载失败 → 该段 TTS 朗读 sentence 兜底、会话不中断；识别不接入（L1）→ CommandWindow 倒计时模式；「手动'会了'按钮可用」子句已随 Phase 5 Step 1 按钮交付（TC-AE-25/26） |
| TC-AE-13 | 位置持久化生命周期（AUDIO §5 / NFR-3）：段切换/暂停即写 `playback.position`；恢复读回词/段/offsetMs；会话 COMPLETED/ABANDONED 后键清除；close/reopen 真库重启续播（JVM integration + restart） |
| TC-AE-14 | 停止播放次序（FR-8/FR-9 可观测语义，TC-LE-06/11「停止播放」延期段）：advance → BookComplete / exit 分支 C → 全部播放端口先停（零在途 speak/播放）再暴露 Completed/Stopped 状态（commonTest + JVM integration） |
| TC-AE-15 | BOOK_DELETED 恢复提示 UI 边界（TC-LE-10 UI 侧，Phase 3 延期项 3）：resume 返回 BOOK_DELETED → 提示呈现、无后续会话操作入口、返回书本列表；引擎语义不在 UI 层重测（androidTest UI 边界） |
| TC-AE-16 | 音频焦点：transient 丢失 → 自动 Pause 并广播状态；焦点回归**不**自动播放（androidTest + 手动矩阵；平台侧 transient→自动暂停/回归不续播已由 Phase 4 Step 3 androidTest 覆盖，编排器级状态广播随学习 UI Step 接入） |
| TC-AE-17 | **逐词空 Segment（裁决 L2，negative 必测）**：空段词不调用 markMastered、不产生 WordMastery 行、SessionWord.status 不变、不伪造播放完成 → 直接进入该词 CommandWindow → 窗口结束正常 advance（Engine 裁决 NextWord/BookComplete）——**空段绝不改变掌握状态**（commonTest） |
| TC-AE-18 | **恢复双源冲突（裁决 L3）**：position.wordId == PLAYING wordId → 采用 segmentIndex + offsetMs；不匹配 → 忽略 position 从 seg0；position 指向已不存在的词 → 忽略；无 PLAYING word → 不由 position 创造播放位；**advance 已切 PLAYING 词而 position 尚未写新词 → close/reopen → resume 使用新 PLAYING 词、从该词 seg0 开始**（JVM integration 真实 SQLite + restart） |
| TC-AE-19 | **Toggle 生效粒度（裁决 L4）**：Segment N 播放中 toggle 变更 → N 不受影响（不重播/不切换/不取消/不重建）；N 完成 → N+1 使用新配置；当前段绝不重建重播（commonTest，可注入设置源） |
| TC-AE-20 | **Media3 后端契约（Phase 4 Step 3，androidTest·模拟器）**：prepare 合法 res/raw 资产完成；playAt(0) 回调驱动完成（durationMs≈4000）；pause 返回真实 currentPosition；playAt(offset) 续播非重播（耗时可区分）；stop 后可重新 prepare；无效 audioUri → prepare 抛异常（→ 兜底触发器）；release 后 prepare/playAt 抛异常、stop 幂等（AUDIO §8） |
| TC-AE-21 | **TTS 后端契约（Phase 4 Step 3，androidTest·模拟器，环境自适应不伪造绿）**：初始化达终态（READY/UNAVAILABLE）；UNAVAILABLE → speak 抛异常；EN 朗读 UtteranceProgressListener 回调完成；zh-CN 语音缺席 → 抛"语言不可用"异常、**不静默改播另一语言**；rate/pitch 接受且完成；stop() 真停（isSpeaking=false + 在途 utterance completed=false）；release 后 speak 抛异常（AUDIO §8） |
| TC-AE-22 | **编排器 ↔ 真实后端冒烟 A–D（Phase 4 Step 3，androidTest·模拟器，真实种子 + in-memory SQLite + 真实引擎栈）**：A 带占位 audioUri 例句 → 文件段真实出声（progressMs 前进）→ 完成 → CommandWindow → exit；B audioUri 指向不存在资源 → Media3 真失败 → degraded 广播 → TTS 兜底 → 会话不中断；C 文件段 pause 记录真实 offsetMs → resume 续播（非整段重播）；D 无 audioUri 例句 = 纯 TTS 段（非降级）→ pause → 引擎层面真停 |
| TC-AE-23 | **学习会话 ViewModel 投影/转发（Phase 4 Step 4，app 单测·JVM，真实 PlaybackOrchestrator + 手写 Fake 端口）**：A 初始 Loading；B Playing 投影（词文本/组号/段标签/段号/降级标志）；C Paused 投影；D CommandWindow 倒计时直接消费编排器状态（VM 无自建计时器）；E Completed 投影（完成权威=引擎，UI 不重判）；F Stopped 投影；G–K Pause/Resume/Replay/Next/Exit 五控制转发（可观测端口 stop 与位置清除）；L BOOK_DELETED 呈现（不出声、不推进引擎）；M onCleared → dispose 停端口停驱动；另：TTS 失败 → Error 投影（§9 可表达子集）；冲突「放弃旧的并开始」= resume → exit → start 全编排器公开 API |
| TC-AE-24 | **学习会话 UI 冒烟 A–H（Phase 4 Step 4，androidTest·模拟器，真实 App 流程：MainActivity/真实 Koin 图/文件库/Media3/TTS）**：A 打开学习屏首词可见；B 占位音频真实播完 → 窗口开启；C Pause→已暂停→Resume 回播放；D 缺音频 → Media3 真失败 → TTS 兜底降级横幅；E 窗口倒计时可见递减；F Next 按编排器语义换第二词；G Exit 二次确认后回本详情；H Completed 确定性 fixture（repository 预置掌握 + 窗口超时）渲染。（BOOK_DELETED 恢复流程独立为 BookDeletedRecoveryUiSmokeTest，归 TC-AE-15；冒烟字母 I/J 自 Phase 5 Step 1 重指派，见 TC-AE-26） |
| TC-AE-25 | **语音命令编排器集成（Phase 5 Step 1，commonTest·JVM：真实 CommandParser + Fake 识别器 + 记录型引擎 Fake）**：Hit「会了」→ 恰一次 markMastered(VOICE) + advance、消费后停听；噪音 UNKNOWN → 零掌握、窗口跑满走既有超时 advance（不误杀）；Unavailable → 整窗降级（listening=false）零掌握、按钮仍可用；isAvailable=false → 前置门降级零 listenOnce 调用；TTS/Playing 全程 recognizer 零调用（与 TC-AE-06 同层互证）；**同窗双入口至多一次掌握**（按钮双击 / 语音命中后补按按钮 → no-op，AUDIO §11 双层防护）；窗口 pause/exit → 关识别；**识别错误绝不 advance / 绝不掌握**（裁决 D5 红线逐条） |
| TC-AE-26 | **「会了」交互端到端（Phase 5 Step 1，app 单测·JVM + androidTest·模拟器）**：VM 单测——语音 Hit → markMastered(VOICE) 恰一次推进；噪音零掌握、窗口超时推进；降级窗口按钮 markMastered(BUTTON)；双击至多一次；窗口外按钮 no-op。插桩 actual 契约——可用性探测 / 静默 Timeout（deadline 预算）/ 取消 → destroy 后再受理 / 并发第二调用者 Unavailable / 权限拒绝 → Unavailable 且拉低 isAvailable（**PermissionDeniedSpeechInstrumentedTest 单类隔离运行**：Gradle 每次插桩重装 APK = 新装拒绝态，确定性；套件内 pm revoke 会杀运行中 app 进程故禁止）。UI 冒烟 I 窗口「会了」按钮 → 同一路径推进第二词；J 权限拒绝 → 窗口降级提示 + 未授权麦克风横幅（拒绝态自适应 / 隔离运行）。真实人声「会了」识别 = 手动矩阵（不伪造） |
| TC-AE-27 | **内置离线引擎（Vosk）actual 契约 + 引擎选择（裁决 E1/E2，Phase 5 收尾范围修订，2026-09-14；app 单测·JVM 选择逻辑 + androidTest·模拟器 actual 契约）**：选择逻辑——探测为真 → 系统 actual、为假 → Vosk actual（纯函数化探测注入，Fake 探测两分支单测）；Vosk actual 契约——模型自 assets 解包加载成功；静默窗口 → Timeout（deadline 预算，零命令语义）；listenOnce 取消 → 录音/识别器全释放后再受理（无泄漏）**且取消不拉低 isAvailable（2026-09-17 vivo 回归锁定：取消曾被吞成 Unavailable+拉低 → E3 代理粘滞降级坏服务引擎 → 后续窗口全废）**；并发第二调用者 → Unavailable；RECORD_AUDIO 未授予 → Unavailable 且拉低 isAvailable；**任何错误不产生命令语义**（D5 红线同系统 actual）；**自管录音管线（轻声/气音支持，2026-09-18 用户裁决）**：MIC 源 `AudioRecord` + RMS 自适应预增益 + `EndpointerMode.SHORT` + 能量后 500ms 尾静默强制冲刷——既有四用例（静默/取消/并发/可用性）即新管线生命周期回归，静默 Timeout 语义不变（能量门限按原始值、误冲刷零命令语义）；**冲刷双触发**：能量持续判据（连续 ≥3 块越过门限才武装——单块环境噪声尖峰不武装/不重置尾静默，2026-09-18 插桩静默窗实证底噪尖峰 150–204 越过固定门限）+ **假设稳定冲刷**（非空 partial 700ms 无改进即冲刷，不依赖声学门限——诊断文件实证环境底噪 117–142 持续贴门限、能量尾静默永不积累；partial 仅作裁决信号，命令语义仍只认 final 文本）；**窗口事件诊断文件** `vosk_diag.log`（vivo logd 间歇整进程吞应用日志的取证对冲，同日实证）。真实人声命中（含轻声实测）与国行真机覆盖 = M1/M2 手动矩阵（vivo V2436A = 无系统服务代表机型） |
| TC-AE-28 | **引擎回退代理（裁决 E3，2026-09-14，app 单测·JVM，手写 Fake 双引擎）**：主引擎可用性级硬失败（返回 `Unavailable` 且拉低自身 `isAvailable`）→ **同窗内**改用备引擎、以剩余预算调用（窗口总时长不变）、返回备引擎结果；降级进程内粘滞——后续窗口主引擎**零调用**、直连备引擎；主引擎正常（Hit/Timeout）→ 备引擎零调用（GMS 机零变化）；并发守卫触发的 `Unavailable`（不拉低 `isAvailable`）→ 不回退、如实透传；剩余预算 ≤ 0 → `Timeout`（不调备引擎、零命令语义）；双引擎皆硬失败 → `Unavailable` 且代理 `isAvailable=false`（§9 双引擎皆不可用行）；`isAvailable` 投影——降级前随主引擎、降级后随备引擎；任何路径不产生命令语义（D5 红线）。真实坏服务机型（vivo V2436A 蓝心 Copilot = 注册 `RecognitionService` 但绑定即硬失败）= M1/M2 手动矩阵 |
| TC-AE-29 | **系统引擎响应看门狗（裁决 E4，2026-09-14；androidTest·模拟器 + vivo M1 手动）**：健康服务安静窗口**零误触**——静默 listenOnce → Timeout 且 `isAvailable` 保持 true（回调持续到达，看门狗不判死；既有静默用例扩展断言）；僵尸服务路径（零回调 → 1500ms 判死 → HardFailure → 拉低 `isAvailable` → E3 同窗回退 Vosk）无法自动化伪造死服务 = vivo V2436A M1 手动矩阵实证（日志链：watchdog 判死 → VB-Vosk onResult text=…）；判死不产生命令语义（D5 红线）。两 actual 全链路识别日志（onResults/onError/看门狗/onResult 文本）为诊断辅助，非断言对象 |
| TC-AE-30 | **TTS 语言路由与降级（FR-23 v2，2026-09-20；jvmTest·commonTest `LangRoutedSpeechSynthesizerTest`）**：路由表 EN_US/EN_GB → 神经引擎、ZH_CN → 系统；神经 INITIALIZING / UNAVAILABLE → EN 段走系统（预热完成自动切换语义）；神经 READY 时英文零系统调用；神经 `speak` 非取消异常 → **同段回退系统播完** + 进程内粘滞（后续 EN 段零神经调用）；`CancellationException` 重抛不降级（恢复后下一 EN 段回神经——取消红线）；`stop()` 双转发；`availableVoices` 随路由与降级联动（EN 神经承接取神经、降级取系统、ZH 恒系统）；readiness 投影系统引擎（UI 零变化）。神经 actual（native 合成 / AudioTrack 排空 / WAV 缓存 / 焦点）为平台管线不设模拟器自动化 = vivo 装机走查（任意词自然语音 / 英音切换 / 缓存命中段间隙 / 降级不中断） |

> **TC-AE 编号裁决（L5，2026-09-05）**：沿用 TEST_PLAN/ROADMAP 既有 `TC-<模块>-<序号>` 体系，新增自 TC-AE-13 顺序递增（后续新增从 TC-AE-20 起）；**不设 Phase 专属第二编号体系**。

### 4.5 TC-IMP 导入（IMPORT_SPEC §9 九条边界，Phase 7 已落地 ✅）

| ID | 用例 |
|---|---|
| TC-IMP-01 ✅ | UTF-8 / BOM / GB18030 样例逐字节断言（BOM 剥离）——commonTest `EncodingDetectorTest`（8 用例：BOM×3 / UTF-8 严格含 overlong·代理区·超平面拒绝 / GB18030 双+四字节兜底 / 零容忍非法序列 / head 末尾截断容忍 / 空输入）；BOM **剥离**在 androidMain actual（`ContentResolverTextLineSource` 首行剥离），真机走查覆盖 |
| TC-IMP-02 ✅ | 六种分隔符 + 分隔符优先级 + 译文含逗号整段保留——commonTest `LineParserTest`（11 用例：Tab/2+空格/单空格/半全角逗号/分号/冒号 + 优先级穿透与回退 + 译文整段含逗号 + 63/64 与 256/257 双边界 + 尾分隔符 trim） |
| TC-IMP-03 ✅ | 守恒断言：报告计数恒等式——jvmTest `ImportEngineTest.conservationIdentityHoldsAndEventPublishedAfterCommit`（恒等式 + 单一 `ImportFinished` + 检测/解析 wiring） |
| TC-IMP-04 ✅ | 性能：10 万行内存 Fake 源 ≤ 60s（JVM 基准，实测 ~3s）+ 取消 ≤1s 回滚——jvmTest `ImportPerfTest`（GBK 字节级解码在平台 actual 侧，JVM 基准以行源为准） |
| TC-IMP-05 ✅ | 去重三层：文件内/本内/全局复用（词行数不变）——jvmTest `ImportEngineTest.dedupLayersSplitAcrossFileGlobalAndBookScopes`（四层分桶 + entryOrder 续接 + updated 计数） |
| TC-IMP-06 ✅ | 非法行（数字/中文开头/超长）→ invalid + 样例收集——`LineParserTest` 非法分类组 + `ImportEngineTest` 守恒用例（invalid=3 计入恒等式；样例封顶 20 条由报告携带） |
| TC-IMP-07 ✅ | 事务：中途异常 → 目标本零变化——jvmTest `ImportEngineTest.midStreamExceptionRollsBackEverythingAndPublishesNothing` + `cancellationMidImportRollsBackInPlace`（取消路径 + `ImportPerfTest` ≤1s 计时断言） |
| TC-IMP-08 ✅ | pendingTranslation 落库与回填清除后的状态——jvmTest `ImportEngineTest.pendingTranslationBackfillsOnceAndNeverOverwrites`（缺位补写恰一次 / 已有译文永不覆盖 / 纯重复轮零事件）；「正式释义回填清除」= DictionaryProvider 未来 Phase，v1 以「补写后不再覆盖」等价锁定 |

### 4.6 TC-AC 勋章（ACHIEVEMENT_SPEC §6，Phase 6 已落地 ✅）

| ID | 用例 |
|---|---|
| TC-AC-01 ✅ | 幂等重放：同一 `WordBookCompleted` 重放 N 次 → 恰一枚勋章；同本重学再完成 → 仍恰一枚、`AchievementUnlocked` 恰一次（jvmTest 集成：真实 JDBC + 默认总线） |
| TC-AC-02 ✅ | 防御复核（ADR-002 会话快照口径）：ACTIVE 会话 / 伪 COMPLETED 带未掌握词 / 未知会话 → 零授予 + WARN 留痕 |
| TC-AC-03 ✅ | 快照不可变：授予后改名 / 加词 → payload 不变（书本体照常可改名） |
| TC-AC-04 ✅ | 删除拦截：真实授予落行后调用删除 → `BOOK_HAS_COMPLETION_MEDAL` 拒绝 |
| TC-AC-05 ✅ | 授予顺序锚点：编排器两条完成路径（advance 自然完成 / exit 分支 C）——事件到达时端口 stop 已发生（记录型订阅者在事件到达时采样 stopCount ≥1） |

### 4.7 TC-ARCH 架构守护

| ID | 用例 |
|---|---|
| TC-ARCH-01 | detekt：`shared/src/commonMain` 无 `android.*`、`java.*` import（fail 构建） |
| TC-ARCH-02 | app 模块无状态机/业务规则（Code owner review + 抽样 Konsist 断言） |
| TC-ARCH-03 | 共享模块 public API 显式声明（explicitApiMode 编译通过） |

### 4.8 TC-UI Android 界面（androidTest / app 单测，少量关键）

词条渲染顺序快照断言（FR-2）、播放控制条状态（六控制可用性矩阵）、生词本增删改流、保存释义选择流、勋章 ceremony 出现条件。

**设置页（FR-15，Phase 8 已落地 ✅）**：
- `SettingsViewModelTest`（app 单测·JVM，6 用例）：快照加载投影 / groupSize 合法输入即时持久化 / 非数字只更新显示不持久化 / 写失败中文提示且回滚显示持久值（Fake `failWrites` 注入）/ 开关整组即时持久化 / 窗口时长与语速·音调即时持久化；
- `SettingsUiSmokeTest`（androidTest·模拟器，1 用例）：真实 MainActivity + 真实 Koin 图——第四 Tab 进入设置页 → 切换「拼写」开关 → AppSetting KV 内 JSON 即时含 `"spelling":false`；
- 写路径权威校验（范围镜像读侧、close/reopen 往返、越界拒绝原值不变、upsert 覆盖）在 jvmTest `LearningSettingsRepositoryTest`（Phase 8 写路径组）；
- TC-LE-02「中途改设置不影响现有会话」既有断言即 groupSize 仅新会话生效的锁定（设置页 UI 不重测引擎语义）。

**词条选择编辑（FR-17，Phase 8.5 已落地 ✅）**：
- jvmTest `WordBookRepositoryTest` 编辑组（真实 JDBC，4 用例）：编辑替换往返（增删释义/例句 → 快照重读一致、未勾旧项消失）/ 词条行不重建（entryOrder·addedAt·pendingTranslation 逐字段不变）/ 校验拒绝且事务回滚（空 selections、词不在本、例句不属释义 → `RepositoryValidationException` 原选择原样）/ 掌握零接触（已掌握词编辑后 WordMastery 行仍在）；
- app 单测 `WordSelectionEditorViewModelTest`（StandardTestDispatcher）：预填投影 / 勾选增删 / 空选择拦截文案 / 保存成功态；
- androidTest 冒烟 `WordSelectionEditorUiSmokeTest`（模拟器）：本详情 → 编辑首词 → 取消一条释义 → DB 断言选择行减少；
- 生效时点（该词下一次播放取新选择）不设新用例——Q4/Q4b 按词条查选择为既有事实，粒度同 TC-AE-19（L4）。

**TTS 音色（FR-19，Phase 8.6 已登记）**：app 单测 `SettingsViewModelTest` 音色组（音色列表加载投影 / 选择即时持久化 / 写失败提示）+ androidTest `SettingsUiSmokeTest` 音色冒烟（枚举非空设备上选择 → KV 落库）；jvmTest `LearningSettingsRepositoryTest` +两键往返；失效回退（所选音色不存在 → setLanguage）在 TtsSpeechSynthesizer 逻辑注释锁定，不设真机自动化（引擎差异）。

**学习统计（FR-20，Phase 8.6 已登记）**：jvmTest `LearningStatsRepositoryTest`（真实 JDBC：首次掌握去重=学习 / 今日前已掌握再掌握=复习 / 会话时长按结束日归集 / endedAt NULL 不计 / 空库零值 / 跨午夜按本地时区注入）；androidTest `StatsCardUiSmokeTest`（学一词 → 统计卡今日学习 +1）。

**全量词典（FR-18，Phase 8.6 已登记）**：jvmTest `WordRepositoryOnDemandImportTest`（Fake provider：DB miss → 导入 → 命中且幂等 / DB 已有不覆盖 / provider null → 未收录；词条例句与音标齐备时 DB 命中不打扰词典源——v2.17 口径：例句或音标缺失时回填咨询属预期行为，回填路径由下组覆盖）；androidTest `BundledDictionarySmokeTest`（真实 asset：查种子外单词命中 + 未收录词 null）；`SeedDictionaryCoverageTest` 不变（例句契约仅精选种子——PROJECT_SPEC v1.14 FR-3 注记）。

**增强回填（FR-18 v1.15 例句/译文，v1.17 扩音标，v1.18 扩英音）**：jvmTest `SeedImporterTest` 回填组 9——`backfillEnhancements`（v1.17 自 `importExamplesOnly` 改名，例句/译文/音标四合一）零例句旧词补齐到首释义且幂等（二次调用零变更）/ Tatoeba 例句译文空 → 只补译文不重复建行 / 未导入词返回 0 绝不建词 / `lookup` 端到端：DB 旧词 + Fake 词典源 → 查看详情自动回填（含译文）重读返回 / 音标空 → 只补空缺（已有音标绝不覆盖，幂等零变更）/ 零释义 TXT 导入词（FR-14 形态）音标仍可回填 / `lookup` 端到端：例句完整但音标缺失 → 自动补音标 / **英音空 → 只补 ipaBr 不覆盖（已有英音优先，幂等，v1.18）** / **lookup 端到端：例句/美音完整但英音缺失 → 自动补英音（FR-22 闸门，v1.18）**；种子导入端到端断言 59 词 ipaBr 全带（v1.18 种子契约）。

**学习会话词内容面板（FR-21，v1.17 已登记）**：UI 纯渲染投影（PlaybackState 段信息 + 编排器 `getCurrentContent()` 读模型），无引擎行为变更不设引擎用例；装机走查 2026-09-20 vivo 实测通过（高亮随段切换释义卡/例句卡/词头、Paused 冻结段高亮保持、换词异步重载、音标回填后面板显示、卡片列表滚动）。

**双音标与发音口音（FR-22，v1.18 已登记）**：jvmTest `SegmentBuilderTest` 口音映射组 2（`withEnglishAccent`：EN_GB 只重写英文段语言、其余字段零改动；缺省 EN_US 与防御 ZH_CN 原样）+ `PlaybackOrchestratorStateTest` 口音组 1（开局英音 → 六段 speak 语言 = 英文四段 EN_GB/中文两段 ZH_CN；窗口中切回美音 → 下一词首段即 EN_US——下一 Segment 生效对齐 TC-AE-19）+ `LearningSettingsRepositoryTest` 口音键组（缺省美音 / 往返与 upsert 覆盖 / ZH_CN 拒写原值不变 / JSON 非法与非口音枚举损坏抛 / 重启持久化）；app 单测 `TtsLocalesTest` EN_GB → Locale.UK + `SettingsViewModelTest` 口音组 2（缺省美音切换即时持久化 / 英音缺失提示随口音与设备音色联动）；androidTest `SettingsUiSmokeTest` 口音冒烟（切英音 → `settings.ttsAccent` KV 含 EN_GB）。en-GB 语音缺失回退 en-US 在 TtsSpeechSynthesizer 逻辑注释锁定（同 FR-19 失效回退口径，引擎差异不设真机自动化）；双音标渲染（formatIpaLine 有则双显无则单显）随 FR-21 装机走查覆盖。

**端内实时神经 TTS（FR-23，v1.19 已登记，TC-AE-30）**：jvmTest `LangRoutedSpeechSynthesizerTest` 路由组 9（路由表 EN_*→神经 / ZH→系统 / 神经 INITIALIZING 与 UNAVAILABLE → EN 走系统 / READY 英文零系统调用 / speak 非取消异常同段回退 + 进程内粘滞 / 取消重抛不降级（恢复回神经）/ stop 双转发 / availableVoices 路由与降级联动（ZH 恒系统）/ readiness 投影系统引擎）。既有 FR-22 口音组零改动即回归（口音 = 模型，消费时映射不变）；神经 actual（native 合成、AudioTrack 排空、WAV 磁盘缓存、音频焦点）为平台管线不设引擎用例——vivo 装机走查：任意词（含长尾）发音 / 拼写 / 英文释义 / 例句神经自然语音、切英音下一英文段英音、中文段零变化、同段二次播放命中缓存段间隙缩短、杀进程重进预热正常。

**应用图标（bug#4）**：装机目视走查项（自适应图标 + 主题图标），无自动化用例。

## 5. FR 追踪矩阵（需求 → 用例）

| FR | 用例组 |
|---|---|
| FR-1/FR-16 | TC-DB-01、TC-UI（词条页） |
| FR-2 | TC-DM-01…03、TC-UI 词条渲染 |
| FR-3 | TC-DM-04、TC-AE-12 |
| FR-4/FR-5 | TC-DB-01/02、TC-DM-04/05、TC-UI 生词本流 |
| FR-6 | TC-LE-01…03、TC-DB-03 |
| FR-7 | TC-LE-04、TC-AE-04/05/10/25/26 |
| FR-8 | TC-LE-05/06、TC-AC |
| FR-9 | TC-LE-07/08/11、TC-DB-05/07 |
| FR-10 | TC-AE-01/02 |
| FR-11 | TC-AE-07/08/09 |
| FR-12 | TC-AE-03/06/25/26/27/28/29 |
| FR-13 | TC-AC 全组 |
| FR-14 | TC-IMP 全组 |
| FR-15 | TC-UI 设置、TC-LE-02 |
| FR-17 | TC-UI 词条编辑组（jvmTest 仓储 + VM 单测 + 冒烟） |
| FR-18 | TC-UI 词典组（jvmTest 按需导入 + androidTest 真实 asset 冒烟） |
| FR-19 | TC-UI 音色组（VM 单测 + 冒烟 + jvmTest 设置键往返） |
| FR-20 | TC-UI 统计组（jvmTest 聚合 + 统计卡冒烟） |
| FR-21 | 装机走查（v1.17 vivo 实测）+ 回填组音标用例（复用 FR-18 组） |
| FR-22 | TC-UI 口音组（jvmTest SegmentBuilder 映射 + 编排器口音段 + 设置键往返 + 回填组英音 / app 单测 VM 口音 + TtsLocales EN_GB / androidTest 设置冒烟；装机走查双显与口音朗读） |
| FR-23 | TC-AE-30 路由组（jvmTest LangRoutedSpeechSynthesizerTest 9：路由 / 非 READY 走系统 / 异常同段回退粘滞 / 取消红线 / stop 双转发 / 音色联动 / readiness 投影）；神经 actual 装机走查（自然度 / 英音 / 缓存命中 / 降级不中断） |

## 6. 覆盖率门槛（Kover，Phase 质量门）

| 层 | 门槛 |
|---|---|
| shared/domain + learning + playback + speech + import + achievement | **行覆盖 ≥ 85%**（引擎核心分支 ≥ 90%） |
| data/repository | ≥ 70% |
| app（UI/胶水） | 不设硬门槛，以 TC-UI 关键流为准 |

## 7. 手动测试矩阵（每 Phase 收尾 + 发版前）

| 场景 | 检查点 |
|---|---|
| 真机语音 | 命令窗口对"会了"识别率（安静/嘈杂）；TTS 播放中说话**必须无效** |
| TTS 双语质量 | en-US 与 zh-CN 发音自然度；SPELLING 0.8× 可懂度 |
| 暂停恢复实感 | 恢复后听觉上"从断点续"（TTS 段重读为已知限制，验证不回到整词开头） |
| 音频焦点 | 来电/其他 App 抢焦点 → 自动暂停，回归焦点不自动播放（安全） |
| 权限流 | 麦克风拒绝 → 降级路径可用（NFR-8） |
| 大文件真机 | 5 万行 GBK 真机导入时长 + 取消 |
| 熄屏/后台 | 会话状态与音频焦点行为符合预期 |

### 7.1 Phase 5 真机验收（M1–M4，ROADMAP Phase 5 出口，裁决 D-E）

详细步骤、口径与设备记录见 `docs/reports/PHASE_5_REAL_DEVICE_ACCEPTANCE_CHECKLIST.md`（2026-09-13，基线 `7ccb04c`）。
**必须由用户本人真机执行；AVD 无真人输入与 zh-CN 离线识别包，不能代替 M1/M2。结果由用户回填——不预填、不伪造。**
**2026-09-18 用户裁决：M1–M4 延后**——当前语音效果用户自评够用（vivo V2436A 多轮取证性实测，见 checklist 2026-09-14…18 记录），先推进 MVP 开发；下方结果框保持空置，补跑时按 checklist 原样执行、结果由用户回填。
**2026-09-14 更新（裁决 E1/E2）**：识别引擎新增内置离线兜底后，M1–M4 须在搭载新引擎的包上执行；vivo V2436A（系统无 RecognitionService）为内置引擎路径的代表验收机型，有系统服务的机型回归 M1/M4 验证系统路径行为不变。**同日更新（裁决 E3）**：vivo 实测存在"注册了 `RecognitionService` 但绑定即硬失败"的厂商服务（蓝心 Copilot）——此类机型走**窗口内回退**路径（首窗当场切内置引擎），M1–M4 口径不变（UI 与直连内置引擎路径不可区分）。

| 项 | 场景 | 门禁 | Manual Acceptance Result |
|---|---|---|---|
| M1 | 正常语音命令：授权 → 重启（进程内不自动恢复为设计语义）→ 窗口内说「会了」→ 恰一次掌握 + 立即下一词；同窗重复命令（语音后补按钮 / 按钮后补语音）不重复 advance | 全步骤符合预期 | ☐ PASS ☐ FAIL（待真机执行） |
| M2 | 识别率：安静环境、明确发音，三别名 会了/记住了/掌握了 各 ≥10 次 | 命中率 = 命中次数 / 总说命令次数 ≥ 90%（Timeout 不计分母） | ☐ PASS ☐ FAIL（待真机执行） |
| M3 | 权限拒绝：新装拒绝 → 不崩溃 + 整窗降级倒计时 + 「会了」按钮仍可用；重启后绝不自动申请权限 | 全步骤符合预期 | ☐ PASS ☐ FAIL（待真机执行） |
| M4 | 无后台监听：Playing 期间零麦克风指示；窗口内监听属正常；Pause/Exit/离开学习屏即消失 | 窗口外零麦克风活动 | ☐ PASS ☐ FAIL（待真机执行） |

## 8. CI 计划（Phase 1 落地）

- Windows runner：`:shared:jvmTest` + detekt + Kover 报告（**核心回归不等 macOS**）；
- macOS runner（iOS 阶段起）：`:shared:allTests`（iosSimulatorArm64）+ Xcode 构建；
- 每次合并门禁：JVM 测试绿 + detekt 绿 + 覆盖率不回退。

## 9. 测试数据策略

- 词库 fixtures：JSON 种子（含多词性/多例句/多来源词，与 Phase 2 种子数据同源）；
- 编码样例：字节数组常量（BOM/GBK/混错）内嵌测试，不依赖磁盘文件；
- Fake 端口：可编程段完成时序、命令命中脚本、超时注入。

---

| 版本 | 日期 | 变更 |
|---|---|---|
| 1.0 | 2026-09-01 | Phase 0 初版 |
| 1.1 | 2026-09-01 | 冻结 D1–D4：TC-LE-07/08 升级，新增 TC-LE-11（分支 C）与 TC-DB-07（type/血缘约束） |
| 1.2 | 2026-09-01 | FR-5 粒度细化（PROJECT_SPEC v1.3）：新增 TC-DM-05；TC-DB-01 增 (wordBookEntryId,exampleId)；TC-DB-06 记 v1→v2 首迁移 |
| 1.3 | 2026-09-03 | Phase 3 规格对齐：§4.3 标题改"十一条边界"（ROADMAP 出口统一为 TC-LE-01…11）；TC-LE-01/02/04/07 补 §10 边界映射（空本、<groupSize、开播即会了、导入词、事务快照）+ ACTIVE 唯一性断言并入 TC-LE-01；**修正 TC-LE-10 语义**（词已移除 → 剔除该词其余照常，非整会话 ABANDON，对齐 LE spec §10-9）；TC-DB-07 标注 Phase 2 遗留、随 Phase 3 电池补齐 |
| 1.4 | 2026-09-04 | Step 5D 验收裁决（交集语义）：TC-LE-07 补 mid-session 母本编辑三边界——Case 1 会话外新增词不进派生本 / Case 2 会话中移除词不进派生本 / Case 3 交集为空**不创建空 DERIVED 本**（derivedWordBookId=null、会话 ABANDONED、endedAt 写入）；命名断言升级为注入时区精确格式 + 重名 -2/-3 两级 |
| 1.5 | 2026-09-05 | Phase 4 Step 0 裁决落地：**L5** 编号体系冻结（沿用 TC-AE 顺序递增，不设 P4 第二编号）；**L1** §4.4 标注 P4 批准全集（01…03、07…19；04…06、10 归 P5）与 CommandWindow 纯倒计时边界；**L2** 新增 TC-AE-17 逐词空 Segment（negative：绝不 MASTERED）；**L3** 新增 TC-AE-18 恢复双源冲突四分支 + 重启场景；**L4** 新增 TC-AE-19 Toggle 下一 Segment 生效粒度；**L7** TC-AE-12 拆分 P4 子集；另新增 TC-AE-13 位置持久化生命周期 / TC-AE-14 停止播放次序（TC-LE-06/11 延期段）/ TC-AE-15 BOOK_DELETED UI 边界（Phase 3 延期项 3）/ TC-AE-16 音频焦点 |
| 1.6 | 2026-09-05 | Phase 4 Step 3（Android 音频后端）落地：新增 TC-AE-20 Media3 后端契约 / TC-AE-21 TTS 后端契约（环境自适应）/ TC-AE-22 编排器↔真实后端冒烟 A–D；TC-AE-16 注记平台侧 androidTest 已交付、编排器级广播随 UI Step |
| 1.7 | 2026-09-06 | Phase 4 Step 4（学习会话 UI）落地：新增 TC-AE-23 ViewModel 投影/转发单测（真实编排器 + Fake 端口；映射 A–F + 转发 G–K + BOOK_DELETED L + 生命周期 M + Error 投影 + 冲突「放弃旧的」公开 API 序列）/ TC-AE-24 UI 冒烟 A–I（真实 App 全链路）。TC-AE-16 的编排器级焦点状态广播因本 Step 范围约束（禁改 Media3AudioPlayer / AudioPlayer 端口契约）未接入，维持 Known Limitation |
| 1.8 | 2026-09-12 | Phase 5 Step 1（语音命令 / 「会了」交互，裁决 D1–D5）落地：TC-AE-04/05/06/10 标注已交付（commonTest + app 层三层同语义）；TC-AE-12「按钮可用」子句闭合；新增 **TC-AE-25** 语音命令编排器集成（同窗双入口至多一次掌握、D5 红线逐条、TTS 期间零识别调用互证）与 **TC-AE-26**「会了」交互端到端（VM 单测 + AndroidSpeechCommandRecognizer actual 契约 + UI 冒烟 I/J；权限拒绝 = PermissionDeniedSpeechInstrumentedTest 单类隔离运行两段式确定性执行法；运行时 pm revoke 杀进程故全套件禁止；真实人声 = 手动矩阵）；TC-AE-24 冒烟字母表修正（A–H，BOOK_DELETED 独立归 TC-AE-15）；FR-7/FR-12 验收映射更新 |
| 1.9 | 2026-09-13 | Phase 5 收尾（决策 D-A…D-E，checkpoint commit `7ccb04c`）：§7.1 新增 Phase 5 真机验收 **M1–M4 Manual Acceptance Result 占位**（详细 checklist 见 `docs/reports/PHASE_5_REAL_DEVICE_ACCEPTANCE_CHECKLIST.md`；结果等用户真机执行后回填，不预填不伪造）；自动化交付项维持 v1.8 标注不变。同日 PROJECT_SPEC v1.4 对齐 FR-12 别名验收口径（D-B ↔ 裁决 D4：别名可配置延期至 Phase 8，Phase 5 = 固定代码常量三别名全命中） |
| 2.0 | 2026-09-14 | **需求变更（用户裁决 E1/E2）：语音命令须支持中国大陆发售的所有安卓手机**（PROJECT_SPEC v1.5 / AUDIO_ENGINE_SPEC v1.3）。新增 **TC-AE-27** 内置离线引擎（Vosk）actual 契约 + 引擎选择逻辑（系统在场→系统 actual 零变化；缺席→Vosk；app 单测选择逻辑 + androidTest actual 契约；真实人声/国行机型 = M1/M2 手动矩阵）；FR-12 追踪映射 +TC-AE-27；§7.1 注记 M1–M4 须在搭载新引擎的包上执行（vivo V2436A = 内置引擎代表机型） |
| 2.1 | 2026-09-14 | **裁决 E3（引擎回退代理）**：新增 **TC-AE-28**（app 单测·JVM，Fake 双引擎——同窗回退剩余预算/进程内降级粘滞/GMS 零变化/并发守卫不回退/剩余预算 ≤0 → Timeout/双引擎皆败 → Unavailable/isAvailable 投影）；FR-12 映射 +28；§7.1 增坏服务机型（vivo 蓝心 Copilot）走窗口内回退路径注记。上游：PROJECT_SPEC v1.6 / AUDIO_ENGINE_SPEC v1.4 |
| 2.2 | 2026-09-14 | **裁决 E4（系统引擎响应看门狗）**：新增 **TC-AE-29**（androidTest 零误触断言扩展进既有静默用例 + vivo 僵尸服务路径 M1 手动实证；两 actual 全链路识别日志）；FR-12 映射 +29。上游：PROJECT_SPEC v1.7 / AUDIO_ENGINE_SPEC v1.5 |
| 2.3 | 2026-09-14 | **解析归一化缺陷修复（E4 后真机日志定位）**：TC-AE-10 增**分词空格**用例——Vosk 中文模型按字分词输出「会 了」，归一化原只 trim 首尾导致精确匹配未命中（识别成功却零掌握）；AUDIO_ENGINE_SPEC v1.6 §7 归一化改**去除全部空白**（别名/识别文本同函数，精确匹配语义不变） |
| 2.4 | 2026-09-16 | **需求变更（用户裁决）：「会了」近音误听按命中处理**（M1 真机复测驱动——Vosk 小模型把「会了」听成「坏了」，说完命令倒计时走满零掌握）。TC-AE-10 增**近音兜底白名单**用例（坏了/换了/会啦/记住啦/掌握咯 → MASTERED）+ 防误杀守卫用例（你好/坏（单字）/会了吗 → UNKNOWN；自定义别名集无近音兜底）。上游：PROJECT_SPEC v1.8（FR-12 验收口径）；下游同步：AUDIO_ENGINE_SPEC v1.7 §7（`NEAR_HOMOPHONES_BY_ALIAS`） |
| 2.5 | 2026-09-17 | **Vosk actual 取消语义回归锁定（vivo 真机定位）**：TC-AE-27 增断言——`listenOnce` 取消后 `isAvailable` 保持 true（回归：取消曾被兜底 catch 吞成 Unavailable+拉低 → E3 代理粘滞降级坏服务引擎，后续窗口全降级零识别，2026-09-17 21:56 vivo 实证）。真机验收 checklist 增铁律 8（切后台自动暂停 = 设计行为，恢复播放后语音才有效，M2 不计分母）与铁律 9（vivo `adb install -r` 重置 RECORD_AUDIO，装包后预期重走授权+重启一轮）。下游同步：AUDIO_ENGINE_SPEC v1.8 §8 |
| 2.6 | 2026-09-17 | **近音白名单证据增补**：TC-AE-10 +「了 了」用例（2026-09-17 23:26 vivo 实测「会了」→「了了」，h 声母丢失；boost 窗口首句 UNKNOWN → 同窗第二句预算不足零识别）。上游：AUDIO_ENGINE_SPEC v1.9 §7 |
| 2.7 | 2026-09-17 | **需求变更（用户裁决）：首/末字近音结构规则**：TC-AE-10 增结构规则用例组（headCharNearHuiHits / tailCharNearLeHits / structuralRuleStillRejectsUnrelated / structuralRuleIsScopedToHuiLeAlias）+ **守卫翻转**（「会了吗」「我觉得这个词已经会了」「，会了」「坏（单字）」及繁体「會了」（末字=了）原 UNKNOWN → 现 MASTERED；前导标点精确语义断言改用拉丁别名）。上游：PROJECT_SPEC v1.9（FR-12）、AUDIO_ENGINE_SPEC v2.0 §7（`STRUCTURAL_NEAR_CHARS_BY_ALIAS`） |
| 2.8 | 2026-09-18 | **需求变更（用户裁决）：轻声/气音支持（自管录音管线）**：TC-AE-27 增自管管线注记——MIC 源 `AudioRecord`（弃用库 `SpeechService` 的 OEM VOICE_RECOGNITION 链路，vivo 04:41 实证轻声被抹成纯静默）+ 预增益/SHORT 端点/500ms 强制冲刷；既有四用例（静默/取消/并发/可用性）即新管线生命周期回归；轻声真实效果 = vivo 手动实测登记（尽力而为，M2 口径仍正常音量）。上游：PROJECT_SPEC v1.10（FR-12）、AUDIO_ENGINE_SPEC v2.1 §8 |
| 2.9 | 2026-09-18 | **平台 actual 缺陷修正（非语义）**：TC-AE-27 注**冲刷双触发**——能量持续判据（连续 3 块越限才武装；单块噪声尖峰不武装/不重置尾静默——插桩静默窗实证底噪尖峰 150–204 越过固定门限 120）+ **假设稳定冲刷**（非空 partial 700ms 无改进即冲刷——诊断文件实证环境底噪 117–142 **持续**贴门限、能量尾静默永不积累 → 轻声冲刷失效 = 用户首词零识别候选根因；partial 仅作裁决信号，final-only 命令语义不变）——与**窗口事件诊断文件** `vosk_diag.log`（vivo logd 间歇整进程吞应用日志——用户首词失败测试会话零日志，取证对冲）。既有四用例语义不变。上游：AUDIO_ENGINE_SPEC v2.2 §8 |
| 2.10 | 2026-09-18 | **近音字集证据增补（用户裁决）**：TC-AE-10 末字集 +「呀」——自管管线轻声首测取证（vosk_diag.log 三窗：了[命中]/**呀[零掌握→本裁决]**/回来[命中]）：轻声「会了」（arm rms 2559）解码为单字「呀」（元音 a/e 弱音频混淆）；tailCharNearLeHits +「呀」「好呀」用例。上游：PROJECT_SPEC v1.11 / AUDIO_ENGINE_SPEC v2.3 |
| 2.11 | 2026-09-18 | **M1–M4 延后（用户裁决，非用例变更）**：§7.1 注记——当前语音效果用户自评够用（vivo 取证性实测），先交付 MVP，正式真机统计延后至后续迭代补跑；M1–M4 结果框保持空置（不预填、不伪造）。上游：ROADMAP v1.8（Phase 5 收尾）；收尾报告 `PHASE_5_REPORT.md` |
| 2.12 | 2026-09-18 | **Phase 6 勋章用例落地**：§4.6 TC-AC-01…05 全组 ✅ 标记 + 口径对齐实现——01 增同本重学再完成；02 改 ADR-002 会话快照口径三形态（ACTIVE/伪 COMPLETED 带未掌握/未知会话）；03 改名/加词；04 真实授予后守卫拒绝（引擎级闭环）；05 编排器双完成路径（advance + exit 分支 C）事件到达时端口 stop 已发生。测试落点：`AchievementEngineTest`（jvmTest 集成）+ `PlaybackOrchestratorStateTest` 两条顺序用例 + `LearningEngineExitTest`/`LearningEngineExitIntegrationTest` sessionCompleted 断言 + app 侧 `WordBooksViewModelTest` 删书文案 3 例 + `LearningSessionViewModelTest` 仪式页 E2/E3。上游：ACHIEVEMENT_SPEC v1.1、LEARNING_ENGINE_SPEC v1.4、DOMAIN_MODEL v1.5 |
| 2.13 | 2026-09-18 | **Phase 7 TXT 导入用例落地**：§4.5 TC-IMP-01…08 全组 ✅ 标记 + 测试落点——commonTest `EncodingDetectorTest`（8 用例）+ `LineParserTest`（11 用例）、jvmTest `ImportEngineTest`（6 用例：守恒+事件/四层去重/补写幂等/异常回滚/取消回滚/导入词可学 PRON+SPELL）+ `ImportPerfTest`（10 万行 ≤60s + 取消 ≤1s）；口径注记——GBK 解码性能与 BOM 剥离在平台 actual 侧（真机走查）、「回填清除」以「补写后不再覆盖」等价锁定。上游：IMPORT_SPEC v1.1、DATABASE_SCHEMA v1.7、DOMAIN_MODEL v1.6 |
| 2.14 | 2026-09-18 | **Phase 8 设置页用例落地（方案 A：别名维持常量）**：§4.8 增设置页组——`SettingsViewModelTest`（app 单测 6：加载投影/合法即时持久化/非数字不落库/写失败提示+回滚显示/开关持久化/窗口与语速·音调持久化）+ `SettingsUiSmokeTest`（androidTest 1：开关变更 → KV JSON 即时落库）；写路径权威校验组记入 jvmTest `LearningSettingsRepositoryTest`（往返/越界拒绝原值不变/覆盖）；TC-LE-02 注记 = groupSize 仅新会话既有锁定。上游：PROJECT_SPEC v1.12（FR-15 验收行 + 别名再延后）、ROADMAP v1.9（Phase 8 收敛） |
| 2.15 | 2026-09-19 | **Phase 8.5 词条选择编辑用例登记（FR-17）**：§4.8 增词条编辑组——jvmTest `WordBookRepositoryTest` 编辑组 4（替换往返/词条行不重建/校验拒绝事务回滚/掌握零接触）+ `WordSelectionEditorViewModelTest` + `WordSelectionEditorUiSmokeTest`；生效时点不设新用例（Q4/Q4b 既有事实，粒度同 TC-AE-19）。上游：PROJECT_SPEC v1.13（FR-17）、ROADMAP v1.10 |
| 2.16 | 2026-09-19 | **Phase 8.6 打磨批用例登记（bug list 四项）**：§4.8 增三组——音色（FR-19：VM 单测 + 冒烟 + jvmTest 两键往返；失效回退注释锁定）/ 统计（FR-20：jvmTest 聚合 6 边界 + 统计卡冒烟）/ 词典（FR-18：jvmTest 按需导入 + 真实 asset 冒烟；例句契约分域注记 FR-3 v1.14）；图标 = 走查项无自动化；FR 矩阵 +FR-18/19/20 行。上游：PROJECT_SPEC v1.14、ROADMAP v1.11 |
| 2.17 | 2026-09-19 | **例句回填用例登记（FR-18 v1.15 Tatoeba 例句增强）**：§4.8 词典组口径更新——完整词条（含例句）DB 命中不打扰词典源（例句缺失时回填咨询属预期）；+回填组 4（SeedImporterTest：补齐到首释义且幂等 / 译文空只补译文不重建行 / 未导入词零建词 / lookup 端到端自动回填）。上游：PROJECT_SPEC v1.15、`SPEC_CHANGE_REQUEST_TATOEBA.md` |
| 2.18 | 2026-09-20 | **增强回填扩音标 + FR-21 面板登记**：§4.8 回填组 4→7（`importExamplesOnly` 改名 `backfillEnhancements`——音标只补空缺不覆盖 / 零释义 TXT 词可补 / lookup 端到端例句完整但音标缺失自动补；词典组口径"例句或音标缺失时回填咨询属预期"）；+FR-21 学习会话词内容面板组（纯渲染投影不设引擎用例，2026-09-20 vivo 走查通过）；FR 矩阵 +FR-21 行。上游：PROJECT_SPEC v1.17 |
| 2.19 | 2026-09-20 | **双音标与发音口音用例登记（FR-22 v1.18）**：§4.8 回填组 7→9（英音只补空缺不覆盖 / lookup 端到端英音缺失自动补；种子端到端断言 59 词 ipaBr 全带）+ 新口音组（SegmentBuilder `withEnglishAccent` 映射 2 / 编排器口音段 1：英文段 EN_GB、切换下一 Segment 生效 / 设置键往返 + 损坏抛 + ZH_CN 拒写 / TtsLocales EN_GB / VM 口音与英音缺失提示 2 / 设置冒烟 KV 落库）；en-GB→en-US 回退注释锁定（同 FR-19 口径）；FR 矩阵 +FR-22 行。上游：PROJECT_SPEC v1.18、`SPEC_CHANGE_REQUEST_DUAL_IPA.md` |
| 2.20 | 2026-09-20 | **端内实时神经 TTS 用例登记（FR-23 v1.19）**：新增 **TC-AE-30**（jvmTest `LangRoutedSpeechSynthesizerTest` 路由组 9：路由表 / 非 READY 与 UNAVAILABLE 走系统 / READY 英文零系统调用 / speak 异常同段回退 + 进程内粘滞 / 取消重抛不降级 / stop 双转发 / availableVoices 联动 / readiness 投影系统）；+FR-23 测试组段（既有 FR-22 口音组零改动即回归——口音=模型消费时映射不变；神经 actual 平台管线不设引擎用例 = vivo 装机走查：自然度 / 英音切换 / 中文段零变化 / 缓存命中段间隙 / 杀进程预热 / 降级不中断）；FR 矩阵 +FR-23 行。上游：PROJECT_SPEC v1.19、`SPEC_CHANGE_REQUEST_PIPER_TTS.md`、AUDIO_ENGINE_SPEC v2.4 |
