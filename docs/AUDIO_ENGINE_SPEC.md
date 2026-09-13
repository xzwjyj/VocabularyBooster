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

- 归一化：trim + 全角→半角 + 小写折叠；忽略末尾标点。
- 命中"会了"或配置别名（默认：记住了 / 掌握了）→ `MASTERED`；其余 → `UNKNOWN`（**不误触发**）。
- v1 `enabled = {MASTERED}`；`PAUSE/RESUME/NEXT/REPLAY/EXIT` 枚举与关键词表已定义（DOMAIN_MODEL §3.3），启用=设置开关，无需改引擎。
- 识别结果只有最终文本参与解析；partial 结果一律忽略（防抖）。
- **v1 别名为代码常量（Phase 5 Step 1 裁决 D4，2026-09-12）**：`DEFAULT_MASTERED_ALIASES = {会了, 记住了, 掌握了}`、`V1_ENABLED = {MASTERED}` 为 CommandParser 伴生常量，编排器直接引用；别名设置化（用户可编辑）归 Phase 8，届时经设置端口加法扩展，不改引擎。
- 匹配为**归一化后精确等值**（无模糊匹配、无繁简转换、无 NLP 容错）——「會了」≠「会了」→ `UNKNOWN`。

## 8. 平台 actual 契约

### Android（`shared/androidMain/platform/`，app 模块 Koin 装配）

| 端口 | 契约要点 |
|---|---|
| `TtsSpeechSynthesizer` | `TextToSpeech` 单例；每段 `setLanguage(en-US/zh-CN)`（**双语段切换是硬性要求**）；`SpeakRequest(utteranceId=segmentId, rate, pitch)`；完成回调 `UtteranceProgressListener` → `CompletableDeferred`；`stop()` 立停；readiness 经 `OnInitListener` 暴露 |
| `Media3AudioPlayer` | ExoPlayer；`playAt(offsetMs)` = `seekTo + play`；`pause()` 返回 `currentPosition`；单 track 顺序播放；音频焦点丢失（transient）→ 自动 Pause 并广播状态 |
| `AndroidSpeechCommandRecognizer` | `SpeechRecognizer`；`listenOnce` 用 `suspendCancellableCoroutine` 包 `RecognitionListener`（只收 final `RESULTS_RECOGNITION`，partial 一律忽略）；语言**固定 `zh-CN`**（`EXTRA_LANGUAGE` + `EXTRA_PREFER_OFFLINE=true`（NFR-4）+ `EXTRA_MAX_RESULTS=1`，Phase 5 Step 1 裁决 D3）；`Main.immediate` 线程约定；**同一时刻至多一个识别会话**（并发第二调用者立即 `Unavailable`）；deadline 制窗口预算（软错误重挂延迟计入剩余预算，不越过窗口）；每次 `listenOnce` 自建实例、finally `stopListening + setRecognitionListener(null) + destroy`（取消同样触发，绝无泄漏会话）；错误映射（裁决 D5）：瞬时错误（NO_MATCH/SPEECH_TIMEOUT/RECOGNIZER_BUSY/网络类/空 results）→ 软失败重挂；可用性级错误（INSUFFICIENT_PERMISSIONS/ERROR_CLIENT/create 失败/startListening 同步异常）→ `Unavailable` 且拉低 `isAvailable`；**端口绝不抛异常**（§8 契约，编排器不为其 try/catch） |

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
| 例句音频加载失败 | 该段 TTS 朗读 sentence 兜底，记日志，不中断会话 |
| 识别引擎不可用 | 会话照常，命令窗口退化为倒计时（无识别）；手动"会了"按钮保持可用 |
| 识别瞬时错误（NO_MATCH / SPEECH_TIMEOUT / RECOGNIZER_BUSY / 网络类 / 空 results，裁决 D5） | 「本次尝试无有效命令」：窗口保持，actual 内延迟重挂（计入窗口预算）；**绝不 advance、绝不掌握**；窗口耗尽走既有超时语义 |
| `RECORD_AUDIO` 权限拒绝 / 服务缺席（可用性级，裁决 D5） | `listenOnce` → `Unavailable`：当前窗口整窗降级纯倒计时（`listening=false` 如实广播），手动"会了"按钮保持可用；硬失败拉低 `isAvailable` → 本进程后续窗口前置门直接降级（不自动恢复，重新授权后重启进程恢复） |
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
