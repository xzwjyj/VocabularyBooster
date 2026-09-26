# SPEC CHANGE REQUEST — SPELLING 段逐字母停顿 + 停顿时长设置

- **编号**：SCR-SPELLPAUSE（v2，并入用户增补需求：设置页可调每字母停顿时长）
- **日期**：2026-09-26
- **影响范围**：`SegmentBuilder`（格式）+ `SpeakRequest` 端口加法扩展 + `PlaybackOrchestrator`（设置读取）+ `LearningSettingsRepository`（新键）+ `SqlDelightLearningSettingsRepository` + `SherpaOnnxSpeechSynthesizer`（逐字母合成拼接）+ commonMain 新纯函数 `SpellingAudioAssembler` + 设置页 UI（AppViewModels / SettingsScreen）+ commonTest/jvmTest/app test；无 SQL schema 版本变更（AppSetting KV 加法键，ttsAccent 先例）/ Gradle / 模型资产改动
- **关联需求**：FR-10（Word Spelling）缺陷修复 + FR-15（设置）加法扩展；PROJECT_SPEC 版本记录

## 1. 问题（装机实测）

用户报告：单词拼读段字母**连读**（"l-e-g-i-o-n" 六个字母名连成一段话），要求「一个字母一顿的清晰拼读」，且**停顿时长要在设置里可调**。tts_diag 佐证：拼写段 `speak lang=EN_US engine=vits len=9 speed=0.8`——走 piper 神经引擎。

## 2. 根因（sherpa-onnx 源码取证）

1. **连字符不产生停顿**：piper 前端 `piper-phonemize-lexicon.cc` → `phonemize_eSpeak` 按 CLAUSE_BREAKERS（`,` `;` `:` `.` `!` `?`）切句，标点作为音素保留进 VITS token 流（模型渲染出停顿帧）；连字符只是普通词内分隔符，无停顿 token → 连读。
2. **逗号停顿不可调**：逗号停顿时长是模型训练韵律（约 0.2–0.4s，随上下文浮动），**不能精确控制** → 用户可调的停顿时长必须逐字母合成 + 显式静音拼接（毫秒级精确）。

## 3. 方案

### 3.1 文本格式（三层一致）

`spellingText` 分隔符 `-` → `", "`：`booster` → `b, o, o, s, t, e, r`。
- 未实现逐字母拼接的渲染路径（系统 TTS 降级 / 未来 iOS 初版）靠逗号获得**自然停顿**（`TextToSpeech` / `AVSpeechSynthesizer` 均原生按逗号停顿）——格式即优雅降级；
- 末字母不加句号（段尾已有编排器 SEGMENT_GAP）；0.8× 语速不变。

### 3.2 端口与编排器（停顿参数下发）

- `SpeakRequest` 加法扩展 `letterPauseMs: Int? = null`（缺省 null = 普通段，既有构造零破坏）；
- `PlaybackOrchestrator.speakRequestFor`：`spec.type == SPELLING` → `letterPauseMs = settingsRepository.getSpellingPauseMs()`（游标逐段懒读，生效粒度 = 下一 SPELLING 段，对齐 L4/L6 先例）；
- `LangRoutedSpeechSynthesizer` 原样转发，无逻辑变化。

### 3.3 神经 actual：逐字母合成 + 静音拼接（精确停顿）

`SherpaOnnxSpeechSynthesizer.speak()`：`letterPauseMs != null` 时——
- 文本按 `", "` 拆字母，逐字母 `generate`（单字母 espeak 读字母名，天然正确）；
- 空结果字母跳过（撇号/空格等非字母字符，防御短语与带连字符词）；
- commonMain 新纯函数 `SpellingAudioAssembler.joinWithSilence(parts, pauseMs, sampleRate)`：字母 PCM 之间插入 `sampleRate × pauseMs / 1000` 静音（**仅在字母间**，无尾停顿）——纯 PCM 数学下沉 commonMain 拿 commonTest 覆盖；
- 拼接结果整段进 WAV 缓存，缓存键增加 `sp{pauseMs}` 维度（null 段键格式不变，旧缓存不受影响；拼写旧缓存因文本格式变更天然失键）；
- 逐字母多次合成的首播延迟 ≈ 原整段合成（字母子段极短），命中缓存后零合成。

### 3.4 设置（FR-15 加法扩展，L6 先例）

- 新键 `settings.spellingPauseMs`（AppSetting KV，Int，JSON 数字）；
- `getSpellingPauseMs()` / `setSpellingPauseMs(value)`；缺省 **400ms**；写校验 100–2000ms 越界拒绝（RepositoryValidationException）；损坏值按各键统一语义抛异常；
- 设置页「朗读」区新增「拼读字母停顿」滑杆（0.1–1.0s，步进 0.05s，即时持久化，显示「0.40 秒」）。

### 3.5 系统降级路径：同步实现精确停顿（用户决策 2026-09-26）

`TtsSpeechSynthesizer`（神经不可用时降级）同样生效：`letterPauseMs != null` 时改为
**多 utterance 队列**——逐字母 `speak(QUEUE_ADD)` + 字母间 `playSilentUtterance(pauseMs, QUEUE_ADD)`
（平台原生静音 utterance API，onDone 按时长触发），等待**末字母** utteranceId 收尾。
监听器从单 id 匹配扩展为「段 id 集合」匹配：集合内任一 onError → 整段异常、任一 onStop → 整段
completed=false、末 id onDone → 完成（中间字母 onDone 自然忽略）。普通段（letterPauseMs=null）
路径语义零变化（单元素集合）。

## 4. 不改的东西

- 段结构 / 开关门控 / SEGMENT_GAP / FR-22 口音游标 / FR-23 路由与降级管线 / ZH 管线（ZhTtsTextNormalizer 等）；
- DOMAIN_MODEL / DATABASE_SCHEMA 表结构（KV 加法键无迁移）/ 模型资产 / sherpa-onnx native；
- 无数据库 user_version 递增（无数据重建，资产版本铁律不触发）。

## 5. 测试与文档

- commonTest 新增 `SpellingAudioAssemblerTest`（**TC-AE-33**）：字母拆分 / 静音样本数（400ms@22050=8820）/ 仅字母间无尾停顿 / 单字母无静音 / 空列表兜底；
- jvmTest：`LearningSettingsRepositoryTest` 新键 round-trip + 越界拒绝；`PlaybackOrchestratorStateTest` 增 SPELLING 段请求携带 letterPauseMs、非拼写段 null、设置变更下一拼写段生效（L4）；`SegmentBuilderTest` TC-AE-01 期望 `"b, o, o, s, t, e, r"`；`PlaybackOrchestratorRestartTest` `"a, l, p, h, a"`；
- app test：SettingsViewModel 新设置读写 + 缺省；SettingsScreen 滑杆渲染（仿 FR-22 冒烟粒度）；
- jvmTest `PlaybackOrchestratorStateTest` 补：降级路径多 utterance 拼写段（letterPauseMs 携带）经 FakeTts 转发不变（平台队列行为由装机验收覆盖）；
- 门禁：jvmTest / testDebugUnitTest / detekt×2 / checkPlatformBoundaries / assembleDebug；
- 文档：PROJECT_SPEC（FR-10 示例 + FR-15 设置清单 + 版本记录）；AUDIO_ENGINE_SPEC（§1 表 SPELLING 行 + §8 停顿契约 + 版本记录）；DATABASE_SCHEMA §2.11 键清单 + 版本记录；TEST_PLAN（TC-AE-33 + 版本记录）；DECISION_LOG 新条目；
- 装机验收：vivo 播拼写段，人耳确认字母间停顿清晰；设置改 0.2s / 1.0s 重播可感变化；旧缓存不复发。

## 6. 风险

- 首播延迟：未命中缓存的拼写段 = N 次字母合成（每字母子段 RTF 极短，总时延 ≈ 原整段合成）；命中整段缓存后归零。
- 降级路径多 utterance：跨引擎 `playSilentUtterance` / onStop 派发行为有厂商差异——监听器按段 id 集合匹配 + stop() 即时补全兜底（沿用既有防御），装机验收覆盖。
- 缓存空间：旧拼写 WAV 成死条目占 LRU 配额，100MB 上限内自然淘汰，无需处理。
