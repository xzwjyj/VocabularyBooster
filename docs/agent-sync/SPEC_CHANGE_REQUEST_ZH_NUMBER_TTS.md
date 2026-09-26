# SPEC CHANGE REQUEST — 中文段 TTS 数字读音修复（半角数字被英读）

- **编号**：SCR-ZHNUM
- **日期**：2026-09-25
- **影响范围**：`shared/src/commonMain/kotlin/com/vocabularybooster/speech/`（新增归一化纯函数）+ `shared/src/androidMain/.../platform/SherpaOnnxSpeechSynthesizer.kt`（ZH 段合成前调用）+ commonTest；无 SQL / Gradle / 模型资产改动
- **关联需求**：FR-24（中文段端内神经 TTS）缺陷修复；PROJECT_SPEC 无需求变更

## 1. 问题（装机实测）

巫师三批次视频导入后，legion 的中文释义段「（军事，古罗马）罗马军队的主要单位或师，通常由3000至6000名步兵和100至200名骑兵组成。」中的数字 **3000 / 6000 / 100 / 200 被读成英语**（"three thousand"…），其余中文部分读音正常。

## 2. 根因（三层取证）

1. **数据源**：视频导入 `meaningCN` 含半角数字（`app/src/main/assets/video_import/data.json` legion 条目；随包 ECDICT 释义无数字，正常查词段不受影响）。
2. **sherpa-onnx zipvoice 前端**：`offline-tts-zipvoice-impl.h` 的 frontend = `MatchaTtsLexicon`；其 `ConvertWordToIds`（matcha-tts-lexicon.cc）查词路径：lexicon 词条 → 单 token → CJK 逐字拆分回 lexicon → **其余（拉丁字母、数字等非 CJK）硬编码 `config.voice = "en-us"` 走 espeak-ng**。
3. **词典无数字词条**：随包 `tts/zipvoice/lexicon.txt` 共 68037 行、全部为单汉字→拼音（军 j0 un1 / 三 s0 an1 / 千 q0 ian3 …），无任何纯数字词条 → "3000" 落入 espeak en-us 分支 → 英语读音。上游 ZipVoice Python 版自带中文文本正则化（数字→汉字），sherpa-onnx 移植未包含；tokens.txt 虽有单数字 token（130–139），但仅对**单个**数字字符生效，多位数字串不命中。
4. **缓存放大**：WAV 磁盘缓存按原文键，错误读音的缓存会持续复用。

## 3. 修复方案

1. **shared commonMain 新增 `ZhTtsTextNormalizer`（纯函数，`speech` 包）**：把中文文本中的自由数字串转中文读法——
   - 规则：`[0-9]+(\.[0-9]+)?` 匹配；整数按位权转换（十/百/千/万/亿、零填充、"一十"→"十"），小数为整数部分 +「点」+ 逐位（3.14→三点一四）；前导零串逐位读（007→零零七，0→零）；**紧邻 ASCII 字母的数字不转**（MP3 / 3D / 1990s 保持 espeak 英读）；>16 位防御性不转；
   - 归一化产物用字（零一二三四五六七八九十百千万亿点）均已验证在 zipvoice lexicon 内 → 正常拼音合成。
2. **androidMain `SherpaOnnxSpeechSynthesizer`**：`speak()` 中 ZH_CN 段合成前归一化，归一化后文本**同时用于缓存键与 `generateWithConfig`** —— 旧错误读音缓存因键变天然不再命中（LRU 自然淘汰），无需清理。
3. **零变化面**：EN 段（piper espeak 本就英读数字，正确）；系统 TTS 降级路径（系统 zh 引擎原生读中文数字，FR-23 纪律：系统路径零改动）；`ZIPVOICE_PROMPT_TEXT`、路由器、音色、速度管线。

## 4. 不改的东西

- PROJECT_SPEC / DOMAIN_MODEL / DATABASE_SCHEMA：无需求、行为模型、表结构变化。
- 模型资产 / lexicon / sherpa-onnx native：不动（不可枚举词条，归一化在调用侧是上游 Python 版同构做法）。
- 百分号（%）、全角数字、千分位逗号、负号不在本批范围（释义数据未见，遇到再立项）。

## 5. 测试与文档

- commonTest 新增 `ZhTtsTextNormalizerTest`（**TC-AE-32**）：0 / 10 / 100 / 101 / 110 / 1000 / 1001 / 1010 / 1100 / 3000 / 6000 / 10000 / 100000000 / 小数 / 前导零 / MP3·3D 字母守卫 / 混合中文句（legion 原文）。
- 门禁：jvmTest / testDebugUnitTest / detekt×2 / checkPlatformBoundaries / assembleDebug。
- 文档：TEST_PLAN +TC-AE-32（版本记录 + FR 矩阵 FR-24 行注记）；AUDIO_ENGINE_SPEC §8 归一化契约 + 版本记录；DECISION_LOG 新条目。
- 装机验收：vivo 重播 legion 中文段，数字读「三千」「六千」「一百」「二百」；旧缓存不复发。

## 6. 风险

- 数字读法歧义（年份「1990年」位权读「一千九百九十年」vs 逐位）：释义语境默认位权读法，本批数据无年份用例；如后续遇到再扩规则。
- 缓存空间：旧错误 WAV 成为死条目占 LRU 配额，100MB 上限内自然淘汰，无需处理。
