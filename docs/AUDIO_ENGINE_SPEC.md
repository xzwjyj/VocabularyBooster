# AUDIO_ENGINE_SPEC — 播放引擎规格

> 状态：Phase 0 定稿 ｜ 版本 1.0 ｜ 日期：2026-09-01
> 上游：PROJECT_SPEC FR-10 ~ FR-12 ｜ 分工：LEARNING_ENGINE_SPEC §1 ｜ 端口：ARCHITECTURE §5
> 位置：编排器 `shared/playback/`（纯 Kotlin）+ 命令解析 `shared/speech/`（纯 Kotlin）；平台 actual 在 `shared/androidMain`（Android，app 模块仅 Koin 装配）/ `iosApp`（iOS）。

---

## 1. Segment（播放最小单元）

```kotlin
data class Segment(
    val index: Int,              // 词内序（0 起）
    val type: SegmentType,       // 见下表
    val text: String?,           // TTS 文本（TTS 段必有）
    val track: TrackDescriptor?, // 文件音频（EXAMPLE_AUDIO 且有 audioUri 时）
    val lang: Lang,              // EN_US / ZH_CN —— TTS 每段必须正确切换语言
    val rateScale: Float,        // 语速倍率（SPELLING 段 0.8，其余 1.0 × 用户语速设置）
    val owner: SegmentOwner      // WORD / DefinitionEntry(id) / Example(id) —— UI 高亮联动
)

enum class SegmentType { PRONUNCIATION, SPELLING, MEANING_EN, MEANING_CN, EXAMPLE_AUDIO, EXAMPLE_CN }
```

| SegmentType | 来源 | 语言 | 播放方式 |
|---|---|---|---|
| PRONUNCIATION | `Word.text`（或 `pronunciationAudioUri`，预留） | en-US | TTS |
| SPELLING | 逐字母：`b-o-o-s-t-e-r` | en-US | TTS（0.8× 语速） |
| MEANING_EN | `DefinitionEntry.meaningEN` | en-US | TTS |
| MEANING_CN | `DefinitionEntry.meaningCN` | zh-CN | TTS |
| EXAMPLE_AUDIO | `Example.sentence` / `audioUri` | en-US | 有 track 用 AudioPlayer；无则 TTS 兜底 |
| EXAMPLE_CN | `Example.chineseTranslation` | zh-CN | TTS |

## 2. SegmentBuilder（分段序列构建，纯函数）

```
buildSegments(word, selectedDefinitions, toggles) -> List<Segment>:
  seg = []
  if toggles.PRONUNCIATION: seg += PRONUNCIATION(word)
  if toggles.SPELLING:     seg += SPELLING(word)
  for d in selectedDefinitions.sortedBy(partOfSpeechOrder, definitionOrder):   # I-4 排序规则
    if toggles.MEANING_EN: seg += MEANING_EN(d.meaningEN)
    if toggles.MEANING_CN: seg += MEANING_CN(d.meaningCN)
    if d.includeExamples:
      for ex in examples(d).sortedBy(exampleOrder):
        if toggles.EXAMPLE:     seg += EXAMPLE_AUDIO(ex)          # track ?? TTS(sentence)
        if toggles.EXAMPLE_CN:  seg += EXAMPLE_CN(ex.chineseTranslation)
  return seg     # 结果为空（六开关全关）→ 学习引擎拒绝开始会话
```

- 播放的释义集合 = `WordBookEntryDefinition` 选中项（DATABASE_SCHEMA Q4）；非生词本播放（如词条详情试听）用全部释义。
- 词内释义顺序严格 `(partOfSpeechOrder, definitionOrder)`，例句顺序严格 `exampleOrder`（同组连续展示，I-4/I-8）。
- **逐词空分段（Phase 4 Step 0 裁决 L2，2026-09-05）**：某词经 buildSegments 结果为空（如导入词无选中释义且词级开关全关）→ 该词**不产生可播放 Segment，但绝不因此 MASTERED**：不调用 `markMastered()`、不伪造播放完成、SessionWord 状态与当前位置保持不变；编排器为该词**直接进入 CommandWindow**（§3），窗口结束后调用 `advance()`，由学习引擎裁决 NextWord / BookComplete。即 `empty segments → CommandWindow → advance()`，而非 `empty segments → MASTERED`。（「六项开关全关 → 学习引擎拒绝开始会话」为**会话级**前置拒绝，语义不变，见 §2 伪码注。）
- **开关生效粒度（Phase 4 Step 0 裁决 L4，2026-09-05）**：Toggle 变化**从下一个 Segment 起生效**——当前已开始播放的 Segment 不重新解释、不重建、不打断、不取消、不重播；当前 Segment 完成后**重新读取当前有效 toggles** 再决定下一 Segment（FR-10「立即生效」的落地语义）。不要求因 Toggle 变化重建整词 Segment 列表（增量评估或按剩余位置重建均可，可观测语义以本条为准）。Phase 4 无设置页写入方，测试经可注入设置源/Fake 驱动；Phase 8 设置页接入后同语义。

## 3. 编排器状态机（PlaybackOrchestrator）

```
状态：Idle │ Playing(pos) │ Paused(pos) │ CommandWindow(wordRef, remainingMs) │ Completed │ Stopped
位置 pos = (wordRef, segmentIndex, offsetMs)      # offsetMs 仅文件音频段有意义
```

| 当前态 | 事件 | 次态 | 动作 |
|---|---|---|---|
| Idle | play | Playing(首词, seg 0) | 读队列首词 → buildSegments → 起播 |
| Playing | 本段完成（非末段） | Playing(段+1) | — |
| Playing | 本段完成（末段） | CommandWindow | 停 300ms（guardDelay）→ `recognizer.listenOnce(commandWindowMs)` |
| Playing | pause | Paused | 文件段：`pause()` 记 offsetMs；TTS 段：`stop()`（恢复重读本段） |
| Paused | resume | Playing(原段) | 文件段 `playAt(offsetMs)`；TTS 段重读本段（**绝不重播整词**，FR-11/ADR-09） |
| CommandWindow | 命令 `MASTERED` | Playing(下一词, seg 0) | 关识别 → `markMastered()` → `advance()` |
| CommandWindow | 手动「会了」按钮（FR-7/NFR-8） | Playing(下一词, seg 0) | 关识别 → `markMastered(source=BUTTON)` → `advance()`——与语音命令**同一执行路径**（§11） |
| CommandWindow | 超时 / `UNKNOWN` | Playing(下一词, seg 0) | 关识别 → `advance()`（组内循环由学习引擎裁决） |
| CommandWindow | pause | Paused(窗口位) | 关识别 |
| Paused(窗口位) | resume | CommandWindow(重开整窗) | — |
| Playing / Paused / CommandWindow | next | Playing(下一词, seg 0) | **不**标记掌握（FR-11） |
| 同上 | replay | Playing(当前词, seg 0) | 词级重置 |
| 同上 | exit | Stopped | `exitSession()` → 派生 / 完成（LEARNING §8/§7） |
| CommandWindow（该词触发了书完成） | — | Completed | **先停播放**再展示勋章（FR-8） |

- 全部状态变化经 `StateFlow<PlaybackState>` 暴露（含当前段、进度、命令窗口倒计时、降级标志）。
- **识别器只允许在 CommandWindow 态存在**——架构级杜绝 TTS 被自识别（FR-12）。
- **P4/P5 阶段边界（Phase 4 Step 0 裁决 L1，2026-09-05）**：本表为 v1 目标终态。**Phase 4 不接入 SpeechCommandRecognizer**——CommandWindow 以**纯倒计时**模式运行（等同 §9「识别引擎不可用」降级形态）：不调用 `listenOnce()`、不实现 CommandParser、不申请 `RECORD_AUDIO`、无语音"会了"；窗口超时即 `advance()`。TC-AE-03/08 只验证 CommandWindow 状态与倒计时。识别器接线、命令解析、屏幕"会了"按钮、权限流全部归 Phase 5（ROADMAP）。
- **窗口/语音参数来源（Phase 4 Step 0 裁决 L6，2026-09-05）**：`commandWindowMs / ttsRate / ttsPitch` 经 `LearningSettingsRepository` **加法扩展**读取（原端口两方法语义不变；缺键默认 4000 / 1.0 / 1.0，DATABASE_SCHEMA §2.11 内置键）。`guardDelay` = 300ms 为 v1 常量（FR-12 默认值；FR-15 / §2.11 未设键，如需可配置须走需求变更流程）。
- **CommandWindow 双形态（Phase 5 Step 1 裁决 D2，2026-09-12）**：窗口开启时读取 `SpeechCommandRecognizer.isAvailable`——可用 → **监听模式**（`listenOnce(剩余窗口)` 循环 + 并行倒计时广播）；不可用 → **纯倒计时**（即 L1 的 P4 形态 = §9 降级形态）。状态 `CommandWindow.listening` 如实区分两形态（UI 据此显示「请说：会了」或降级提示）；窗口中途识别器转为不可用 → 整窗降级纯倒计时，「会了」按钮不受影响。

## 4. 命令窗口时序（一个词的完整周期）

```
│ seg1 ─── seg2 ─── … ─── segK │≤300ms guard│ recognizer ON ≤ 4000ms │
│◀────────── TTS/音频（识别关闭）─────────▶│              │
                                                          ├─ "会了" → MASTERED → 下一词
                                                          └─ 超时/未知 → 下一词
```

## 5. 位置持久化（每次段切换/暂停写入，NFR-3）

`AppSetting["playback.position"] = { sessionId, wordId, segmentIndex, offsetMs, phase: PLAYING|WINDOW }`
崩溃恢复由学习引擎（LEARNING §9）读取并回放给编排器。会话结束（COMPLETED/ABANDONED）清除该键。

**恢复双源优先级（Phase 4 Step 0 裁决 L3，2026-09-05）**：`SessionWord.PLAYING` 是**词级唯一真相源**；`playback.position` 仅是**段级恢复信息**，**永远不能改变 SessionWord.PLAYING**：

1. 从 LearningEngine / SessionSnapshot 取当前 PLAYING word；
2. 读取 playback.position；
3. `playback.position.wordId == 当前 PLAYING wordId` → 采用其 segmentIndex + offsetMs 恢复；
4. wordId 不匹配（含 position 指向已不存在的词）→ 忽略 segmentIndex / offsetMs，从当前词 seg 0 开始；
5. 不存在 PLAYING word → 不使用 playback.position 创造播放位（起始词由引擎 `advance()` 裁决）。

## 6. 控制语义（FR-11 对照）

| 控制 | 幂等性 | 语义 |
|---|---|---|
| Play | ✅ | Idle→播；已有 ACTIVE 会话按 LEARNING §9 恢复 |
| Pause / Resume | ✅ | 成对；Resume 永不整词重播 |
| Next | ✅ | 纯跳转，不改掌握状态；若当前在命令窗口则跳过该词 |
| Replay | ✅ | 当前词从 seg0 重播；命令窗口若开着则作废 |
| Exit | 需确认弹窗 | 二次确认后执行（防误触中断整场学习） |

## 7. CommandParser（纯 Kotlin，`shared/speech/`）

```kotlin
fun parse(rawText: String, aliases: Set<String>, enabled: Set<VoiceCommand>): VoiceCommand
```

- 归一化：全角→半角 + 小写折叠 + **去除全部空白**（含内部——内部空白是语音引擎分词伪影：Vosk 中文模型按字分词输出「会 了」，2026-09-14 vivo 实测）；忽略末尾标点。别名与识别文本走同一归一化函数（拉丁多词别名如 "Got it" 双侧去空白后仍精确等值）。
- 命中"会了"或配置别名（默认：记住了 / 掌握了）→ `MASTERED`；其余 → `UNKNOWN`（**不误触发**）。
- v1 `enabled = {MASTERED}`；`PAUSE/RESUME/NEXT/REPLAY/EXIT` 枚举与关键词表已定义（DOMAIN_MODEL §3.3），启用=设置开关，无需改引擎。
- 识别结果只有最终文本参与解析；partial 结果一律忽略（防抖）。
- **v1 别名为代码常量（Phase 5 Step 1 裁决 D4，2026-09-12）**：`DEFAULT_MASTERED_ALIASES = {会了, 记住了, 掌握了}`、`V1_ENABLED = {MASTERED}` 为 CommandParser 伴生常量，编排器直接引用；别名设置化（用户可编辑）归 Phase 8，届时经设置端口加法扩展，不改引擎。
- 匹配 = **归一化后精确等值**，或命中该别名的**近音兜底白名单**（`NEAR_HOMOPHONES_BY_ALIAS`，v1.7/v1.9：Vosk 中文小模型近音误听——2026-09-16 vivo 实测「会了」→「坏了」/「回来」/断字截断「会」、2026-09-17 实测「会了」→「了了」（h 声母丢失）导致说完命令倒计时走满零掌握；白名单按别名归集、随真机日志证据增补，Phase 8 随别名设置化一并扩展），或命中**首/末字近音结构规则**（`STRUCTURAL_NEAR_CHARS_BY_ALIAS`，v2.0 用户裁决 2026-09-17：**「会了」——识别文本首字 ∈ 会近音字集（会/坏/换/回/惠/汇）或末字 ∈ 了近音字集（了/啦/咯/来/呀——「呀」v2.3 增补：2026-09-18 vivo 轻声实证「会了」→「呀」元音 a/e 混淆）即命中**；对连续误听形态结构级兜底——截断单字「会」「坏」、h 声母丢失「了了」、任意 X了/会X 组合与「…会了」句式全覆盖，替代逐形白名单增补）。规则外无任意模糊匹配、无 NLP 容错；不误杀红线**收窄为近音集守卫**——首尾字均不在近音集（「你好」「hello」）→ `UNKNOWN`；「会了吗」「我觉得这个词已经会了」及繁体「會了」（末字=了）等含近音首尾的文本随本裁决**转为命中**；自定义别名集无默认别名的近音兜底与结构规则。

## 8. 平台 actual 契约

### Android（`shared/androidMain/platform/`，app 模块 Koin 装配）

| 端口 | 契约要点 |
|---|---|
| `TtsSpeechSynthesizer` | `TextToSpeech` 单例；每段 `setLanguage(en-US/zh-CN)`（**双语段切换是硬性要求**）；`SpeakRequest(utteranceId=segmentId, rate, pitch)`；完成回调 `UtteranceProgressListener` → `CompletableDeferred`；`stop()` 立停；readiness 经 `OnInitListener` 暴露；FR-23 起 = ZH_CN 主路 + EN 降级兜底（FR-19 音色应用仅此路） |
| `SherpaOnnxSpeechSynthesizer`（神经 TTS actual，**FR-23，2026-09-20**） | sherpa-onnx v1.13.8（vendored Kotlin API `shared/androidMain/kotlin/com/k2fsa/` + jniLibs arm64-v8a，`tools/tts-neural/fetch_deps.py` 产出入库）+ Piper VITS medium 模型（en_US lessac / en_GB alan 各 ~63MB，app assets `tts/piper/` **模型 / tokens 单文件直读；espeak-ng-data 目录树一次性解包至 filesDir**（native 只认真实路径——传 assets 路径 phonemize 失败并 native exit 拖崩全进程，vivo 装机实证；双模型字节相同共享一份，完成标记增量复用））；**仅受理 EN_US / EN_GB**（ZH_CN 抛异常，路由器保证不达）；**口音 = 模型**（段 lang=EN_GB → 英音模型——无厂商英音包设备也生效）；**单模型驻留**（双模型 RAM 峰值不可接受）：换口音先载新成功后 release 旧（加载失败旧模型保持），构造即后台预热美音、`readiness` 反映结果（失败进程内粘滞 → 路由器降级）；合成 = 整段 `generate` 一次产出 PCM **分块直写 AudioTrack**（STREAM 模式、阻塞写即背压、float→16-bit LE 单声道统一管线；**缓冲 16KB + 不足一缓冲的微型段补静音至超缓冲**——vivo mixer 对未填满缓冲 / 无阻塞写的轨道不启动消费（<缓冲段全静音、audio_flinger `Flushed=全部帧` 实证）；**阻塞写在 IO 线程**——编排器经 Main 调 speak；流式回调因 JNI 精确签名在本工具链不可达而弃用，stop 期合成后台自然完成仅浪费少量 CPU）；完成判定 = 播放头追平已写样本（20ms 轮询、30s 兜底、stopRequested 优先判）；**暂停/恢复 ADR-09 同系统段**（stop = 标记 + pause/flush，在途段 completed=false，恢复重读整段，不保留播放位置）；**磁盘缓存** `filesDir/tts-cache/{sha256(lang\|speed@2位\|text)}.wav`（自写 44 字节头；命中免合成整段直播；写后与构造时 LRU mtime 清理上限 100MB；缓存写失败仅记忽略）；rate → speed clamp 0.5–2.0、**pitch 不生效**（VITS 无基频参数）；音频焦点 AUDIOFOCUS_GAIN 播放前请求、任何丢失 → stop（编排器 Paused，同 Media3）；native 合成与模型换载串行于单线程 dispatcher、speak 互斥（OfflineTts 单实例非并发安全）；`release()` 后 speak 抛异常、readiness=UNAVAILABLE |
| `Media3AudioPlayer` | ExoPlayer；`playAt(offsetMs)` = `seekTo + play`；`pause()` 返回 `currentPosition`；单 track 顺序播放；音频焦点丢失（transient）→ 自动 Pause 并广播状态 |
| `AndroidSpeechCommandRecognizer` | `SpeechRecognizer`；`listenOnce` 用 `suspendCancellableCoroutine` 包 `RecognitionListener`（只收 final `RESULTS_RECOGNITION`，partial 一律忽略）；语言**固定 `zh-CN`**（`EXTRA_LANGUAGE` + `EXTRA_PREFER_OFFLINE=true`（NFR-4）+ `EXTRA_MAX_RESULTS=1`，Phase 5 Step 1 裁决 D3）；`Main.immediate` 线程约定；**同一时刻至多一个识别会话**（并发第二调用者立即 `Unavailable`）；deadline 制窗口预算（软错误重挂延迟计入剩余预算，不越过窗口）；每次 `listenOnce` 自建实例、finally `stopListening + setRecognitionListener(null) + destroy`（取消同样触发，绝无泄漏会话）；错误映射（裁决 D5）：瞬时错误（NO_MATCH/SPEECH_TIMEOUT/RECOGNIZER_BUSY/网络类/空 results）→ 软失败重挂；可用性级错误（INSUFFICIENT_PERMISSIONS/ERROR_CLIENT/create 失败/startListening 同步异常）→ `Unavailable` 且拉低 `isAvailable`；**响应看门狗（E4，2026-09-14）**：`startListening` 后 1500ms 内**零回调**（健康服务安静时也持续回调 `onReadyForSpeech`/`onRmsChanged`——零回调 ≠ 用户没说话）→ 判服务僵尸（实测 vivo 假 RecognitionService 的静默死法：零回调零错误挂至窗口超时，端口层与安静窗口不可区分）→ 按可用性级失败上报（HardFailure → 拉低 `isAvailable` → E3 同窗回退），判死不算"识别了什么"；GMS 健康服务回调即时到达，看门狗零误触（模拟器静默窗口 androidTest 锁定）；**端口绝不抛异常**（§8 契约，编排器不为其 try/catch） |
| `VoskSpeechCommandRecognizer`（内置离线兜底，**裁决 E1/E2，2026-09-14**） | Vosk（`com.alphacephei:vosk-android:0.3.70`）+ 中文小模型 `vosk-model-small-cn-0.22`（~42MB，打包 app assets，首次使用经 `StorageService.sync` 解包至应用外部文件目录（`getExternalFilesDir`，按模型 `uuid` 文件增量同步、无重复解包）后加载，进程内复用；解包与模型加载在 IO 线程、懒加载于首个窗口；E3 后另提供启动阶段 `prewarmModel()` 预解包/预加载（仅文件/CPU，**不开麦克风**，失败照粘滞——装配时后台触发，首个真窗口免加载等待）；**自管录音管线（轻声/气音支持，用户裁决 2026-09-18）**：弃用库 `SpeechService`（其 VOICE_RECOGNITION 源走 OEM 语音处理链，vivo 04:41 实证把轻声/气音整窗抹成纯静默——流正常开启活跃、识别器零回调零 partial），自建 `AudioRecord`（**MIC 原始源**）50ms 块（16kHz 16-bit 单声道）读入，**轻声三件套**：①块 RMS 自适应预增益（归一目标电平、上限 10×、削波保护）②`setEndpointerMode(SHORT)` 模型 VAD 灵敏度最高档 ③自端点强制终局（**双触发**）——(a) **持续**语音能量（连续 ≥3 块 50ms 越过 RMS 门限才武装——单块环境噪声尖峰不算：2026-09-18 插桩静默窗实证底噪尖峰 150–204 越过固定门限 120）后 500ms 尾静默；(b) **假设稳定**——模型已解出非空 partial 且 700ms 无改进即冲刷（**不依赖声学门限**：同日窗口诊断文件实证环境底噪 min/avg 117–142 **持续**贴着门限——能量尾静默永不积累，轻声冲刷失效 = 用户首词零识别候选根因；partial 仅作冲刷裁决信号，命令语义仍只认 final 文本）；任一触发即 `getFinalResult()` 冲刷假设并重置（不依赖模型 VAD 自行决断），冲刷后识别器重置、**同窗可继续听第二句**；**只取 final 文本**（endpoint `getResult()` / 冲刷 `getFinalResult()` 的 JSON `text`，partial 一律忽略——与系统 actual 端口契约一致；能量门限按原始值判定、增益只喂模型，门限偏低误冲刷不产生命令语义）；窗口预算 = 读入样本数计窗 + 外层 deadline 兜底；读错误/零样本 → 按窗口耗尽 `Timeout`（零命令语义）；`isAvailable` = 模型资产在场 + `RECORD_AUDIO` 已授予 + 模型未发生加载失败（构造时与每窗口前刷新；加载失败进程内粘滞）；错误映射沿用 D5 精神：模型加载失败/录音器创建或启动失败/权限缺失 → `Unavailable`（拉低 `isAvailable`）；窗口静默耗尽 → `Timeout`；终局 `stop + release + close` 释放录音器与识别器（绝无泄漏，取消同样触发）；**取消 = 端口契约内正常生命周期，`CancellationException` 照常重抛、不按故障处理、不拉低 `isAvailable`**（2026-09-17 vivo 回归修正：取消曾被兜底 catch 吞成 `Unavailable`+拉低 → E3 代理误判主引擎硬失败 → 粘滞降级到坏服务系统引擎，后续窗口全降级零识别）；**并发 ≤1 会话**（守卫同系统 actual）；**端口绝不抛异常**；**纯本地识别、无云端链路**（NFR-1/NFR-4）；不做语法约束（自由识别 + §7 解析，维持不误杀语义）；**窗口事件诊断文件**（`filesDir/vosk_diag.log`：窗口起止/RMS 摘要/能量武装/终局文本/终态，超 512KB 截断——vivo logd 间歇性**整进程吞应用日志**（2026-09-18 用户测试进程零日志实证）的取证对冲，debug 构建 `run-as` 可导出）；**轻声识别为尽力而为**（小模型对无浊音语音的解码上限，验收口径 M2 仍为正常音量） |

**TTS 语言路由与降级（FR-23 v2，2026-09-20）**：`LangRoutedSpeechSynthesizer`（commonMain 组合实现，app 装配为 `SpeechSynthesizer` 唯一绑定）按段语言路由——EN_US / EN_GB → 神经 actual；ZH_CN → 系统 actual。**降级规则**：神经引擎非 READY（INITIALIZING 期间 / UNAVAILABLE 粘滞）或 `speak` 抛非取消异常 → **EN 段当场回退系统 actual 同段播完**，并**进程内粘滞**降级（后续 EN 段直连系统，不反复试探；重启进程后重试神经）；模型资产缺席/加载失败下整体行为 = 全系统 TTS，会话不中断。**取消红线**：`CancellationException`（暂停/退出路径）照常重抛、绝不触发降级（先例：Vosk 取消被吞成降级的 vivo 回归）。**readiness 投影 = 系统引擎**（既有 UI 行为零变化；神经状态仅内部路由依据）。**availableVoices**：EN 由神经承接路由时取神经（每口音单条模型 voice），降级后取系统；ZH 恒系统（FR-19 语义不变）。**stop 双转发**（幂等，未知当前路由落点时两侧全停）。

**引擎选择与回退规则（裁决 E2/E3，2026-09-14）**：两段式——①**启动探测（E2）**：DI 装配时探测系统识别服务——`SpeechRecognizer.isRecognitionAvailable(context) == true` → 系统 actual 为主引擎、Vosk actual 为备引擎（经 app 层回退代理装配，两者皆实现同一端口）；`false`（国行无 GMS / 无标准 `RecognitionService` 机型）→ 直连 Vosk actual（无主引擎，行为同 E2 原文）。②**窗口内回退（E3，2026-09-14 vivo 真机诊断驱动）**：探测"服务在场" ≠ "服务可用"——厂商助手可注册不可用的 `RecognitionService`（实测 vivo V2436A 蓝心 Copilot 唤醒服务可解析、绑定后 46ms 即硬失败，RECORD_AUDIO 已授予）。故：主引擎 `listenOnce` 返回 `Unavailable` **且**拉低了自身 `isAvailable`（D5 可用性级硬失败信号）→ 代理**当场**改用备引擎完成同一窗口的**剩余预算**（窗口总时长不变），并**进程内降级**主引擎（后续窗口直连备引擎，不反复试探；重启后回到启动探测——进程内粘滞语义与既有可用性刷新一致）。系统服务健康设备（GMS 机）硬失败从不发生 → 代理全透传，行为**零变化**；并发守卫触发的 `Unavailable`（不拉低 `isAvailable`）**不**触发回退；回退仅换引擎继续监听，**不产生任何命令语义**（D2–D5 零改动，编排器无感知）。**响应看门狗（E4，2026-09-14 真机二次诊断驱动）**：E3 的触发信号原仅含显式硬失败，但坏服务存在**静默死法**（零回调零错误挂至窗口超时——Timeout 与"用户未说话"不可区分，实测 vivo 首窗吞掉用户命令词）→ 系统 actual 内置响应看门狗：`startListening` 后 1500ms 零回调 → 判死按可用性级失败上报（Unavailable + 拉低）→ 纳入 E3 同窗回退；健康服务（含安静窗口）回调持续到达，看门狗永不误触。

### iOS（未来，`iosApp/`）

| 端口 | 对应实现 |
|---|---|
| `SpeechSynthesizer` | `AVSpeechSynthesizer` + `AVSpeechUtterance`（`voice` 按语言切换），delegate 回调桥 CompletableDeferred 等价物 |
| `AudioPlayer` | `AVPlayer`（`seek(to:)` 精确恢复） |
| `SpeechCommandRecognizer` | `SFSpeechRecognizer` + buffer 识别请求 + 窗口超时；权限 `NSSpeechRecognitionUsageDescription` |

## 9. 降级与错误（对照 ARCHITECTURE §6）

| 场景 | 行为 |
|---|---|
| TTS 中途不可用 | 会话自动进入 Paused + `EngineState.Error(recoverable=true)` + 引导修复 |
| 神经 TTS 不可用 / 合成失败（FR-23，2026-09-20） | 模型缺席 / 加载失败 / 预热超时 / `speak` 非取消异常 → **EN 段当场回退系统 TTS 同段播完** + 进程内粘滞降级（后续 EN 段直连系统）；ZH 段与系统引擎路径不受影响；`CancellationException`（暂停/退出）照常重抛、不降级不兜底 |
| 例句音频加载失败 | 该段 TTS 朗读 sentence 兜底，记日志，不中断会话 |
| 识别引擎不可用 | 会话照常，命令窗口退化为倒计时（无识别）；手动"会了"按钮保持可用；**E1/E2/E3（2026-09-14）后**：系统服务缺席或不可用时由内置 Vosk 引擎兜底（E3 窗口内回退），本行仅剩「双引擎皆不可用」（主引擎硬失败 且 内置引擎模型损坏/权限拒绝）的极端情形 |
| 识别瞬时错误（NO_MATCH / SPEECH_TIMEOUT / RECOGNIZER_BUSY / 网络类 / 空 results，裁决 D5） | 「本次尝试无有效命令」：窗口保持，actual 内延迟重挂（计入窗口预算）；**绝不 advance、绝不掌握**；窗口耗尽走既有超时语义 |
| `RECORD_AUDIO` 权限拒绝 / 服务缺席或不可用（可用性级，裁决 D5/E3/E4） | 主引擎（若有）可用性级失败（显式错误 **或** E4 看门狗判死）→ **E3：当场回退内置引擎完成本窗口剩余预算**；内置引擎亦不可用（权限拒绝 / 模型损坏）→ 当前窗口整窗降级纯倒计时（`listening=false` 如实广播），手动"会了"按钮保持可用；失败在进程内粘滞（主引擎降级 / 双引擎皆拉低 `isAvailable`；重新授权后重启进程恢复） |
| 命令窗口识别到噪音文本 | `UNKNOWN` → 等待至窗口超时（不误杀词） |

> **P4/P5 拆分（Phase 4 Step 0 裁决 L7，2026-09-05）**：Phase 4 只实现并测试本表两行——「例句音频加载失败 → TTS 朗读 sentence 兜底」与「识别不接入（L1）→ CommandWindow 倒计时模式」；「手动'会了'按钮保持可用」子句随 Phase 5 按钮交付。TC-AE-12 拆分见 TEST_PLAN §4.4。

## 10. 可测性

- `FakeSpeechSynthesizer / FakeAudioPlayer / FakeRecognizer` 注入 commonTest：段完成、命令命中、超时全部可编程模拟；
- 时间控制：TestDispatcher + 虚拟 `Clock`；guardDelay / 窗口超时用虚拟时间推进断言；
- Toggle 生效粒度（L4）：可注入设置源在段间变更开关，断言当前段不受影响、下一 Segment 采用新配置（虚拟时间）；
- §3 状态表每行 = 至少一条转换测试（TEST_PLAN TC-AE-xx）。

## 11. 语音命令接入（Phase 5 Step 1，裁决 D2/D5，2026-09-12）

「会了」是 v1 唯一启用的语音命令；**识别只存在于 CommandWindow 态**（§3 / FR-12），绝无后台监听、绝无常驻麦克风。

- **同一执行路径（D2）**：语音（`listenOnce` → `Hit` → `CommandParser` 判 `MASTERED`）与屏幕「会了」按钮（编排器 `masterCurrentWord()`，**仅 CommandWindow 态可用**，其余态幂等 no-op）汇合于编排器私有 `executeMasteredCommand()`：`markMastered(source=VOICE|BUTTON)`（LE spec §6 入口唯一）→ 既有 `advance()`。无第二套掌握语义；掌握/推进/完成/退出裁决全部仍归 LearningEngine。
- **窗口双形态**（§3 CommandWindow 双形态行）：`CommandWindow.listening` 如实广播监听 / 纯倒计时两形态（UI 据此呈现，不伪造录音状态）；窗口中途 `Unavailable` → 整窗降级（`listening=false`），按钮不受影响。
- **监听循环（D5）**：`listenOnce(剩余窗口)` 逐次尝试至终局——`MASTERED` → 关窗、恰一次消费、走既有掌握推进；`UNKNOWN`（噪音）→ 忽略并保持监听至窗口超时（不误杀）；`Timeout` → 窗口自然结束 → 既有超时 advance；`Unavailable` → 整窗降级纯倒计时。**任何识别错误都不产生命令语义、绝不 advance、绝不掌握**（D5 红线，TEST RULE）。
- **窗口生命周期（FR-12）**：进入窗口（guard 300ms 后）→ 开识别；命中命令 → 恰一次消费 → 关识别 → 既有推进；超时 → 关识别 → 既有超时语义；pause / exit / dispose → 关识别（listenOnce 协程取消 → actual `destroy()`）；resume(窗口位) → **重开整窗**（含消费位重置，§3）。识别回调重复到达由 **actual 单次 resume 守卫 + 编排器窗口消费位（`windowCommandConsumed`）双层吸收**——同一窗口内语音与按钮合计至多一次掌握。
- **权限（RECORD_AUDIO，运行时）**：授权流 UI 持有——非阻断授权条 + 用户点击发起系统弹窗，**绝不自动请求、绝不阻断会话**；未授权 = 识别路径降级（同可用性降级形态），会话与按钮不受影响。`commonMain` 端口不含权限概念（平台 actual 自行感知）。
- **别名与解析（D4）**：`DEFAULT_MASTERED_ALIASES = {会了, 记住了, 掌握了}`、`V1_ENABLED = {MASTERED}` 为 CommandParser 代码常量（§7）；别名设置化归 Phase 8。
- **测试锚点**：TC-AE-04/05/06/10 + 同窗双入口重复防护，编排器（commonTest）/ ViewModel（app 单测）/ 插桩（actual 契约 + UI 冒烟 I/J）三层同语义；详见 TEST_PLAN §4.4。

---

| 版本 | 日期 | 变更 |
|---|---|---|
| 1.0 | 2026-09-01 | Phase 0 初版 |
| 1.1 | 2026-09-05 | Phase 4 Step 0 裁决落地：**L1** P4/P5 CommandWindow 边界（P4 纯倒计时，不接入 SpeechCommandRecognizer，§3）；**L2** 逐词空分段语义（空段 → CommandWindow → advance()，绝不 MASTERED，§2）；**L3** 恢复双源优先级（SessionWord.PLAYING 词级真相源，playback.position 仅段级且不可反写，§5）；**L4** 开关生效粒度（下一 Segment 起，当前段不重解释/不重建/不打断，§2）；**L6** 窗口/语音参数经 LearningSettingsRepository 加法扩展 + guardDelay 300ms 常量澄清（§3）；**L7** §9 降级矩阵 P4 子集拆分（按钮子句归 P5） |
| 1.2 | 2026-09-12 | Phase 5 Step 1 语音命令接入（裁决 D1–D5）：§3 +手动「会了」按钮行 + CommandWindow 双形态（`listening` 广播）；§7 别名代码常量（D4）与归一化精确等值；§8 Android actual 契约细化（zh-CN 固定 D3、D5 软/硬错误映射、单会话守卫、deadline 预算、用后即毁、绝不抛异常）+ actual 位置澄清（`shared/androidMain`，Phase 4 起实况）；§9 +识别瞬时错误 / 权限拒绝两行（D5）；新增 **§11 语音命令接入**（语音与按钮同一执行路径、窗口生命周期、双层重复防护、RECORD_AUDIO UI 持有授权流） |
| 1.3 | 2026-09-14 | **需求变更（用户裁决 E1/E2）：语音命令须支持中国大陆发售的所有安卓手机**（实测 vivo V2436A / Android 16 系统无任何标准 RecognitionService）。§8 新增 `VoskSpeechCommandRecognizer` 内置离线兜底 actual 契约（模型打包 APK、16kHz、final-only、D5 精神错误映射、绝无泄漏/绝不抛异常、纯本地）+ **引擎选择规则 E2**（系统服务在场→系统 actual 零变化；缺席→Vosk；进程启动时一次性选择）；§9「识别引擎不可用」行更新为双引擎皆不可用极端情形。上游：PROJECT_SPEC v1.5（FR-12 识别引擎条款 + NFR-4）；测试：TEST_PLAN v2.0 TC-AE-27 |
| 1.4 | 2026-09-14 | **裁决 E3（窗口内引擎回退，vivo 真机诊断驱动）**：v1.3 的「vivo 无任何 RecognitionService」判断修正——App 声明 `<queries>` 包可见性后 vivo V2436A 上蓝心 Copilot 唤醒服务**可解析**，E2 探测返回 true → 装配系统 actual，但该服务绑定后 46ms 即可用性级硬失败（权限已授予）→ 首窗降级粘滞 = 真机「语音命令不可用」根因。§8 引擎选择规则升级为**选择与回退规则 E2/E3**（启动探测定主备 + 主引擎硬失败当场回退备引擎完成同窗剩余预算 + 进程内降级主引擎；健康系统服务零变化；并发守卫 Unavailable 不触发回退；回退不产生命令语义）；§9 可用性级行同步。上游：PROJECT_SPEC v1.6；测试：TEST_PLAN v2.1 TC-AE-28 |
| 1.5 | 2026-09-14 | **裁决 E4（系统引擎响应看门狗，vivo 二次真机诊断驱动）**：E3 交付后真机复测暴露坏服务第二种死法——**静默僵尸**（`startListening` 后零回调零错误挂至窗口超时；端口层 Timeout 与"用户未说话"不可区分，E3 未触发，用户命令词被吞，17:36 日志实证）。§8 系统 actual 契约增看门狗：1500ms 零回调（健康服务安静时亦持续回调 ready/RMS）→ 判死按可用性级失败上报 → 纳入 E3 同窗回退；模拟器静默窗口 androidTest 锁定零误触（TC-AE-29）；两 actual 同步增识别回调/最终文本/错误码全链路诊断日志（VB-SysRec/VB-Vosk）。上游：PROJECT_SPEC v1.7；测试：TEST_PLAN v2.2 |
| 1.6 | 2026-09-14 | **解析归一化缺陷修复（E4 后真机日志定位，非语义变更）**：E4 看门狗 + E3 回退在 vivo 上工作正常，但 Vosk 识别到的最终文本「会 了」未命中——Vosk 中文模型**按字分词**（字间插空格），而 §7 归一化原只 trim 首尾、未除内部空白 → 精确等值失败 → UNKNOWN → 窗口超时零掌握（D5 红线正确守住了不误掌握，但也吞掉了合法命中）。§7 归一化增**去除全部空白**（识别文本与别名同函数归一化，精确匹配语义不变、别名常量不变、繁体/前导标点/非精确仍 UNKNOWN）。测试：TEST_PLAN v2.3（TC-AE-10 增分词空格用例） |
| 1.7 | 2026-09-16 | **需求变更（用户裁决）：「会了」的近音误听全部按命中处理**（M1 真机复测驱动——Vosk 小模型把「会了」听成「坏了」等，说完命令倒计时走满零掌握）。§7 匹配规则升级为**精确等值 + 近音兜底白名单**（`NEAR_HOMOPHONES_BY_ALIAS`：会了→坏了/换了/会啦/惠了/汇了/回来/回了/会来/会（「回来」「会」为同日二/三次实测新增：近音替换与断字截断；回了/会来为 huì-le 同族高概率形式），记住了→记住啦/记住咯，掌握了→掌握啦/掌握咯；证据驱动增补，Phase 8 随别名设置化扩展）。白名单外仍精确语义（「你好」「會了」→ UNKNOWN，D5 不误杀红线不变；单字「坏」等误听残片不白名单）；白名单按别名归集，自定义别名集无默认别名近音兜底。上游：PROJECT_SPEC v1.8（FR-12 验收口径）；测试：TEST_PLAN v2.4（TC-AE-10 增近音白名单用例） |
| 1.8 | 2026-09-17 | **平台 actual 缺陷修复（vivo 真机回归定位，非语义变更）**：Vosk actual `listenOnce` 兜底 catch 吞掉外层取消的 `CancellationException`（关窗取消被当故障）→ 返回 `Unavailable` 且拉低 `isAvailable` → E3 代理误判主引擎硬失败 → 进程内粘滞降级到坏服务系统引擎，后续窗口全降级零识别（2026-09-17 21:56 vivo 实证）。§8 Vosk 契约行明确：**取消 = 端口契约内正常生命周期，照常重抛、不按故障处理、不拉低 `isAvailable`**。测试：TEST_PLAN v2.5（TC-AE-27 增取消不拉低断言） |
| 1.9 | 2026-09-17 | **近音白名单证据增补（FR-12 v1.8 既有裁决的既定扩展路径，非新需求）**：「会了」→「了了」（h 声母丢失，huì-le → le-le；2026-09-17 23:26 vivo V2436A 实测——boost 窗口首句被听成「了 了」→ UNKNOWN 重挂 → 第二句剩余预算不足零识别）。§7 白名单会了行 +「了了」。测试：TEST_PLAN v2.6（TC-AE-10 +「了 了」用例） |
| 2.0 | 2026-09-17 | **需求变更（用户裁决）：首/末字近音结构规则**（v1.8 白名单逐形增补仍漏新形态，用户裁决改为结构覆盖）。「会了」匹配升级：**首字 ∈ {会,坏,换,回,惠,汇} 或 末字 ∈ {了,啦,咯,来} 即命中**（`STRUCTURAL_NEAR_CHARS_BY_ALIAS`）——截断「会」「坏」、h 声母丢失「了了」、「会了吗」「…会了」句式等全部兜住。**不误杀守卫随之收窄**：「会了吗」「我觉得这个词已经会了」及繁体「會了」（末字=了；原 UNKNOWN 守卫例）转为命中；剩余守卫 = 首尾字均不在近音集（「你好」「hello」）+ 空文本 + 自定义别名集不适用。上游：PROJECT_SPEC v1.9（FR-12）；测试：TEST_PLAN v2.7（TC-AE-10 结构规则用例组 + 守卫翻转） |
| 2.1 | 2026-09-18 | **需求变更（用户裁决）：轻声/气音支持——自管录音管线**（根因 = vivo 04:41 实证：轻声说话被 OEM 语音链路整窗抹成纯静默——AudioRecord 流正常开启活跃满预算、Vosk 零回调零 partial、appops allow、无蓝牙/有线/他进程占用；库 `SpeechService` 无音频源/增益可控点）。§8 Vosk 行采集层重写：自建 `AudioRecord`（**MIC 原始源**，绕开 OEM VOICE_RECOGNITION 处理链）50ms 块读 + 轻声三件套（①RMS 自适应预增益 10× 上限 ②`EndpointerMode.SHORT` ③能量后 500ms 尾静默强制 `getFinalResult()` 冲刷、同窗续听第二句）。静默窗口 Timeout 语义不变（能量门限按原始值，门限偏低误冲刷零命令语义）；既有四用例（静默/取消/并发/可用性）即新管线生命周期回归。**轻声识别为尽力而为**（小模型无浊音上限；M2 口径仍正常音量）。上游：PROJECT_SPEC v1.10（FR-12）；测试：TEST_PLAN v2.8 |
| 2.2 | 2026-09-18 | **平台 actual 缺陷修正（非语义变更，v2.1 交付当日回归发现）**：①自端点能量判据加**持续条件**（连续 ≥3 块越过门限才武装冲刷跟踪）——插桩静默窗实证环境底噪**单块尖峰 150–204 已越过固定门限 120**（静默用例内出现 speech energy/forced-flush 事件）：旧单块判据会被噪声武装、语音尾静默计数也会被间歇尖峰重置；②自端点增**第二冲刷触发：假设稳定**（非空 partial 连续 700ms 无改进即冲刷，不依赖声学门限）——新交付的窗口诊断文件随即实证更深层问题：该环境底噪 min/avg 117–142 **持续**贴着门限（非孤立尖峰），能量尾静默**永不积累** → 轻声语音的强制冲刷永不触发（用户同日报告「只有第一个词『会了』没识别」的候选根因——首词轻声、后续正常音量靠模型端点器命中）；③Vosk 行增**窗口事件诊断文件** `vosk_diag.log`——用户测试会话进程被 vivo logd **整进程吞日志**（零取证），logcat 之外的持久取证通道（本次②即由其取证）。语义不变：partial 仅作冲刷裁决信号、命令语义仍只认 final 文本（防抖契约）；静默 Timeout / 空冲刷零命令 / 同窗第二句 / 生命周期四用例全部不受影响。测试：TEST_PLAN v2.9（TC-AE-27 注记） |
| 2.3 | 2026-09-18 | **近音字集证据增补（v2.0 结构规则既定扩展路径，用户裁决）**：「会了」末字近音集 +「呀」——自管管线轻声首测取证（vosk_diag.log 三窗：了[命中]/呀[零掌握→本裁决]/回来[命中]）：轻声「会了」（arm rms 2559）被小模型解码为单字「呀」（元音 a/e 弱音频混淆）。单字「呀」「好呀」等 X呀 形式随扩集命中（与 X了/会X 同级取舍）。上游：PROJECT_SPEC v1.11；测试：TEST_PLAN v2.10（TC-AE-10 用例） |
| 2.4 | 2026-09-20 | **新增 FR-23 端内实时神经 TTS（用户批准 SPEC_CHANGE_REQUEST_PIPER_TTS v2）**：§8 Android 表 +`SherpaOnnxSpeechSynthesizer` actual 契约行（sherpa-onnx v1.13.8 + Piper VITS medium 双口音模型，模型单文件 assets 直读 + espeak-ng-data 解包至 filesDir；口音=模型；单模型驻留换载；整段合成分块直写 AudioTrack（16KB 缓冲 + 补静音）、阻塞写在 IO 线程；ADR-09 段语义；WAV 磁盘缓存 100MB LRU；rate clamp 0.5–2.0 / pitch 不生效）+`TtsSpeechSynthesizer` 行注明 FR-23 起 ZH 主路 + EN 降级兜底；新增 **TTS 语言路由与降级规则段**（`LangRoutedSpeechSynthesizer`：EN→神经 / ZH→系统；非 READY / speak 非取消异常 → EN 当场回退 + 进程内粘滞；取消重抛红线；readiness 投影系统）；§9 +神经 TTS 不可用 / 合成失败行。上游：PROJECT_SPEC v1.19；测试：TEST_PLAN v2.20（TC-AE-30） |
