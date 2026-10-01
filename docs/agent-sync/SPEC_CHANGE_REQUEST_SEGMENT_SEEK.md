# SPEC_CHANGE_REQUEST — 学习会话点卡跳段（SCR-SEGMENTSEEK）

> 提出日期：2026-10-01 ｜ 状态：已批准并实施（2026-10-01 用户裁决：D1 = A 只跳位置不自动恢复 / D2 = A 词头可点；TC-AE-35 全绿）
> 上游需求：FR-21（词内容面板）/ FR-11（播放控制）｜ 影响：AUDIO_ENGINE_SPEC §3/§6、LearningSessionScreen/ViewModel、PlaybackOrchestrator ｜ 无 DB 迁移、无 LearningEngine 变更、无新设置

## 1. 问题

学习会话词内容面板（FR-21）目前纯只读展示：想重听某条释义/例句，只能等组内循环绕回，或「重播」整词（从头发音 + 拼写开始）——无法直接跳到想听的段落。

## 2. 现状（调研结论，2026-10-01）

- 卡片身份 = `SegmentOwner`（Word / Definition(definitionEntryId) / Example(exampleId)）——FR-21 高亮联动已用该映射（`LearningSessionScreen.kt` 459–471），点卡跳段天然有稳定的卡 → 段归属键；
- 编排器已有词级导航（`next`/`previous`/`replay`：cancelStep → stopPorts → 改游标 → launchDriveLoop），**段级 seek 不存在**；
- 游标三元组 `specCursor`/`playedCount`/`resumeOffsetMs` 为编排器私有；`PlaybackPosition.segmentIndex` = 启用段序 rank（`applyPlayingRestore` 同口径，seek 复用同一换算）；
- LearningEngine 不参与段级位置——seek 为纯编排器控制，零引擎调用、零掌握写入（与 replay 同级纯导航）；
- 面板卡片数据（`getCurrentContent()`）只含选中释义/例句，但段开关（FR-10）可在运行期禁用某类段——seek 解析须过滤被禁段。

## 3. 冻结候选方案

### 3.1 编排器新控制 `seekTo(owner: SegmentOwner)`

- **作用域**：Playing / Paused / CommandWindow（`isTransportActive`）；Idle / Completed / Stopped 幂等 no-op；
- **解析**：当前词 specs 全范围（允许向后跳）找**首个** owner 匹配且当前开关启用的 spec；无命中（该卡片全部段类型被开关禁用，如释义卡在 MeaningEN+CN 全关时）→ no-op；
- **Playing**：cancelStep → stopPorts → 游标迁移（specCursor=目标 spec 下标、playedCount=目标在启用段中的 rank、resumeOffsetMs=0、currentSpec=null）→ launchDriveLoop（从该段头起播）；
- **CommandWindow**：作废窗口（cancelStep 关识别 + 停倒计时——镜像 previous/replay 的「窗口开着则作废」语义，非命令消费）→ 同 Playing 路径重播；当前词保持 PLAYING 不动；
- **Paused**：游标迁移到目标段、**保持 Paused**（新暂停位 = 该段起点，offsetMs=0，atCommandWindow 转 false）；此后 Resume 从该段起播（既有 resume 语义零改动）；
- **纯导航**：SessionWord / WordMastery / 勋章零接触；`PlaybackPosition` 随段起播自然写入（NFR-3 既有路径）。

### 3.2 UI / ViewModel

- 释义卡、例句卡加 `clickable` → `onSeek(SegmentOwner.Definition/Example(id))`；VM 新增 `seekToSegment(owner)` 经既有命令转发路径到编排器（镜像 previous）；
- testTag 沿用卡片既有 `learning_def_{id}` / `learning_example_{id}`；
- 点当前正在播的卡 = 该卡首段重起（卡级重播，合法）。

## 4. 待用户裁决的决策点

- **D1 Paused 态点卡**：A) 只移动暂停位、不自动恢复播放（推荐——符合 FR-11「冻结位置」语义，防误触后突然出声）；B) 直接从该段恢复播放。
- **D2 词头（大字词文本）是否同样可点**（= 跳发音段，实质等效「重播」）：A) 可点，交互一致；B) v1 仅释义/例句卡。
- **裁决（2026-10-01）**：D1 = A（只跳位置，不自动播）；D2 = A（词头也可点）；批准按 §3 冻结方案实施。附加实施细节：error 暂停（TTS 失败 Paused(error)）随跳转清除 error 位（错误属于旧段，Resume 从目标段重试）；`PlaybackPosition` 在 Paused 分支即写入迁移后的段位（NFR-3）。

## 5. 测试计划（TC-AE-35，jvmTest + app 单测）

`PlaybackOrchestratorStateTest` seek 组：Playing 中向前/向后跳（游标 + Playing 状态 + position 落点）/ CommandWindow 中 seek 作废窗口立即 Playing(该段, 头) / Paused 中 seek 暂停位迁移且仍 Paused（resume 自该段起播）/ 卡片段全被开关禁用 no-op / 空词（零 specs）no-op / seek 零掌握零推进（引擎零调用）/ 终态与 Idle 幂等。app `LearningSessionViewModelTest`：点卡转发恰一次 `seekTo`。装机走查：点卡跳段体感、快速连点、窗口倒计时中点卡、暂停态点卡后 Resume。

## 6. 文档同步

PROJECT_SPEC FR-11 控制表 +Seek 行（点卡跳段）、FR-21 注记卡片可点 / AUDIO_ENGINE_SPEC §6 控制表 +seek、§3 Playing 行注记 / TEST_PLAN TC-AE-35 / DOMAIN_MODEL §10 控制清单。零 schema 迁移。

## 7. 风险

- 与「重播」边界：seekTo(Word) ≈ replay——若 D2 选 A 允许点词头，两者并存不冲突（replay 无视开关从 spec0 起跳、seek 按开关解析）；
- 高亮与滚动：跳段后高亮随既有 owner 联动自然切换，卡片滚动位置不自动跟随（v1 不做 smoothScrollTo，走查确认是否需要）；
- 窗口作废重播 = 该词再走一轮完整段序 → 再开新窗口（时长不累计，语义同 replay）。
