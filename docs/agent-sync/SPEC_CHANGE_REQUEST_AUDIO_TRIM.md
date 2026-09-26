# SPEC CHANGE REQUEST — 视频导入例句音频 BGM 前奏裁剪

- **编号**：SCR-AUDIOTRIM
- **日期**：2026-09-25
- **影响范围**：`tools/video_importer/`（Python 工具，仅此）+ `app/src/main/assets/video_import/audio/` 再生（data.json 不变，无 Kotlin/SQL/Gradle 改动）
- **关联需求**：无正式 FR 编号（视频导入为工具链辅助功能，未入 PROJECT_SPEC）；本 SCR 仅为工具缺陷修复

## 1. 问题（审计结论）

巫师三批次例句音频在读句前有很长纯 BGM 前奏。实测取证：

- `clutch_0.mp3` 总长 22.4s，但句子语音（"I see you gather before me…"）**11.2s 才开始**——前 11.2s 为纯 BGM（whisper base + word_timestamps 复核；尾部 20.48s 处另有 "PLAYING" 幻听词，为菜单音效）。
- 其余 5 条：legion/siege 6.32s、precipice 9.52s、rabid/ravenous 5.92s，程度不一。

## 2. 根因

`word_matcher.extract_sentences_for_words` 把 **Whisper segment 级** start/end 直接当作剪切边界（word_matcher.py:177-178）。Whisper segment 以停顿对齐、跨度最长 30s；持续 BGM 下 segment start 常远早于首个发声词 → 剪切片段带长 BGM 前导。

## 3. 修复方案

1. **`speech_recognizer.recognize_speech`**：`transcribe(..., word_timestamps=True)`，输出的 JSON segment 增加 `words` 数组（whisper ≥20230314 支持，本机 20250625 已验证可用）。
2. **`word_matcher.extract_sentences_for_words`**：segment 含 `words` 时，剪切边界改为词级：
   - `start = max(0, 首词.start − 0.25s)`（呼吸前导）
   - `end = 末词.end + 0.5s`（尾音自然衰减）
   - 无 `words`（SRT 字幕路径 / 旧 JSON）回退 segment 边界，行为不变。
3. **既有巫师三批次再裁剪**（一次性脚本 `workspace/retrim_audio.py`，不重跑词典/翻译，data.json 原样保留）：
   - 对 `input/巫师三/part1.mp4` 重提音频 → whisper base + word_timestamps；
   - 按 difflib 文本相似度把 data.json 各句对齐回 whisper segment，用词级边界从原视频重新剪切，覆盖 assets 下同名 mp3；
   - 验收：新 clutch 片段时长 ≈ 9–10s 且 whisper 复核语音起点 < 0.5s；全部 6 条无长 BGM 前奏。
4. **重建 APK**：DB 侧 `asset://video_import/audio/<file>` 直读 APK assets（VideoImportEngine.kt:112，无拷贝），已导入数据自动生效，无需重导入/版本变更。

## 4. 不改的东西

- PROJECT_SPEC / DOMAIN_MODEL / DATABASE_SCHEMA：不涉及（无 App 行为、表结构变化）。
- data.json（释义/翻译/文件名）保持逐字节不变。
- 不处理 whisper 幻听词（如 "PLAYING"）——词级裁剪后最多带 0.5s 尾巴，可忽略。

## 5. 风险

- BGM 下词级时间戳仍可能小幅漂移 → 以装机人耳验收为准（与既有 FR-23 纪律一致）。
- 再裁剪脚本用 difflib 对齐句子，理论错配 → 逐条打印新旧时间轴供人工核对。
