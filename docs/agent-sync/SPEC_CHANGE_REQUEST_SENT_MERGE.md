# SPEC CHANGE REQUEST — 视频导入跨 segment 句子合并 + legion/siege 例句截断修复

- **编号**：SCR-SENTMERGE
- **日期**：2026-09-26
- **影响范围**：`tools/video_importer/word_matcher.py`（工具，未来批次）+ 本批一次性修复：`data.json` v2→v3（legion/siege 例句文本 + 译文）+ 音频重裁（legion_0 / siege_0.mp3）+ APK 重打包 + 设备 DB 手术（Example 表 2 行）；**无 Kotlin/SQL/Gradle 改动**
- **关联需求**：视频导入批次（巫师三）数据质量缺陷；IMPORT_SPEC 语义不变

## 1. 问题（用户报告）

legion 例句读完 "…laid siege to every fortress" 即截断——视频原句后面还有内容。siege 词条共用同一句，同样截断。

## 2. 根因（whisper 缓存取证，temp/retrim/segments.json）

Whisper VAD 在 28.62s 把一句话劈成两个 segment（边界严丝合缝 22.40–28.62 / 28.62–34.64）：

- seg1：`Emperor Emre has marched his legions into our lands, laid siege to every fortress`（**无句末标点**）
- seg2：`from here to the Blue Mountains, rabbit and ravenous he bites and bites away.`

`extract_sentences_for_words` 以 segment 为不可分句子单元（文本 + 词级剪切边界都取自单 segment），无跨段合并 → 文本与音频都在 fortress 处截断。SCR-AUDIOTRIM 只修了段内 BGM 边界，未覆盖段间句子断裂。

## 3. 方案

### 3.1 工具修复（未来批次生效）

`word_matcher.py` 提取前**预合并连续 segment**：当前 segment 文本不以 `.`/`?`/`!` 结尾，且下一 segment `start ≤ 当前 end + 1.0s` → 合并（text 拼接、words 列表拼接、时间跨度取并集）；句末标点即停。合并后句子提取与词级剪切边界（SCR-AUDIOTRIM）沿用既有逻辑，天然获得整句边界。

### 3.2 本批一次性修复（工具不重跑管线）

- **data.json v2→v3**：legion + siege 例句改为整句（边界待用户裁决：到 `away.` 整句 / 到 `Blue Mountains` 半句）；译文同步定稿（顺带修复现译文「皇帝皇帝」重复的机翻瑕疵）；
- **音频重裁**：`legion_0.mp3` / `siege_0.mp3` 用既有词级时间戳重剪（whisper 缓存在位，无需重跑识别）：start 22.15（首词 22.40−0.25）→ end 按裁决（`away.` 末词 34.64+0.5 = 35.14；`Mountains,` 30.52+0.5 = 31.02）；输出至 assets 后 APK 重打包，`asset://` 路径不变 → 装新包即生效，无需重导入；
- **设备 DB 手术**（先例：2026-09-26 释义修复）：Example 表 2 行（legion/siege 例句）`sentence` + `chineseTranslation` 定点 UPDATE（wordId + 旧句精确匹配）；掌握/会话/勾选行零触碰。

## 4. 不动项

- rabid / ravenous 例句（同一句后半段 "from here to the Blue Mountains, …"）保持现状：独立可懂、无投诉；若整句归 legion/siege，两词条例句是同一句的片段重叠，属正常的跨词条共享例句。

## 5. 验收

1. 工具冒烟：合并逻辑跑 `temp/retrim/segments.json`，legion 命中整句（到裁决边界）；
2. UI：编辑弹层例句显示整句；
3. 装机：例句音频播到整句末尾（无 fortress 突断）；
4. DB：两行更新后核对（rowcount=1 × 2）。
