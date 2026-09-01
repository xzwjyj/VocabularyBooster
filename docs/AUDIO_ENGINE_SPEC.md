# AUDIO_ENGINE_SPEC — 播放引擎规格

> 状态：Phase 0 定稿 ｜ 版本 1.0 ｜ 日期：2026-09-01
> 上游：PROJECT_SPEC FR-10 ~ FR-12 ｜ 分工：LEARNING_ENGINE_SPEC §1 ｜ 端口：ARCHITECTURE §5
> 位置：编排器 `shared/playback/`（纯 Kotlin）+ 命令解析 `shared/speech/`（纯 Kotlin）；平台 actual 在 `app`（Android）/ `iosApp`（iOS）。

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
| CommandWindow | 超时 / `UNKNOWN` | Playing(下一词, seg 0) | 关识别 → `advance()`（组内循环由学习引擎裁决） |
| CommandWindow | pause | Paused(窗口位) | 关识别 |
| Paused(窗口位) | resume | CommandWindow(重开整窗) | — |
| Playing / Paused / CommandWindow | next | Playing(下一词, seg 0) | **不**标记掌握（FR-11） |
| 同上 | replay | Playing(当前词, seg 0) | 词级重置 |
| 同上 | exit | Stopped | `exitSession()` → 派生 / 完成（LEARNING §8/§7） |
| CommandWindow（该词触发了书完成） | — | Completed | **先停播放**再展示勋章（FR-8） |

- 全部状态变化经 `StateFlow<PlaybackState>` 暴露（含当前段、进度、命令窗口倒计时、降级标志）。
- **识别器只允许在 CommandWindow 态存在**——架构级杜绝 TTS 被自识别（FR-12）。

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

## 8. 平台 actual 契约

### Android（`app/platform/`）

| 端口 | 契约要点 |
|---|---|
| `TtsSpeechSynthesizer` | `TextToSpeech` 单例；每段 `setLanguage(en-US/zh-CN)`（**双语段切换是硬性要求**）；`SpeakRequest(utteranceId=segmentId, rate, pitch)`；完成回调 `UtteranceProgressListener` → `CompletableDeferred`；`stop()` 立停；readiness 经 `OnInitListener` 暴露 |
| `Media3AudioPlayer` | ExoPlayer；`playAt(offsetMs)` = `seekTo + play`；`pause()` 返回 `currentPosition`；单 track 顺序播放；音频焦点丢失（transient）→ 自动 Pause 并广播状态 |
| `AndroidSpeechCommandRecognizer` | `SpeechRecognizer`；`listenOnce` 用 `suspendCancellableCoroutine` 包 `RecognitionListener`（只收 final `RESULTS_RECOGNITION`）；`EXTRA_PREFER_OFFLINE=true`（NFR-4）；每窗口用完即 `destroy()`；`RECORD_AUDIO` 权限拒绝 → `Unavailable`（降级：手动"会了"按钮） |

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
| 命令窗口识别到噪音文本 | `UNKNOWN` → 等待至窗口超时（不误杀词） |

## 10. 可测性

- `FakeSpeechSynthesizer / FakeAudioPlayer / FakeRecognizer` 注入 commonTest：段完成、命令命中、超时全部可编程模拟；
- 时间控制：TestDispatcher + 虚拟 `Clock`；guardDelay / 窗口超时用虚拟时间推进断言；
- §3 状态表每行 = 至少一条转换测试（TEST_PLAN TC-AE-xx）。

---

| 版本 | 日期 | 变更 |
|---|---|---|
| 1.0 | 2026-09-01 | Phase 0 初版 |
