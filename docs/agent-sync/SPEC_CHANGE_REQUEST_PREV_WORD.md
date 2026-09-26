# SPEC CHANGE REQUEST — 学习会话「上一个」控制

- **编号**：SCR-PREVWORD
- **日期**：2026-09-26
- **影响范围**：`LearningEngine` 接口 + `DefaultLearningEngine`（previous 裁决）+ `PlaybackOrchestrator`（previous 控制与 adopt 参数化）+ `LearningSessionViewModel` + `LearningSessionScreen`（双行控制条）+ 两处手写引擎 Fake（jvmTest `RecordingEngine` / app `FakeLearningEngine`）+ commonTest/jvmTest/app test；**无 SQL schema 变更（previous 纯推导，零迁移零新键）/ 无 Gradle / 无语音命令改动**
- **关联需求**：FR-11（播放控制）加法扩展——新增 Previous 控制；PROJECT_SPEC 版本记录

## 1. 问题（用户需求）

学习会话目前只有前向导航：用户错过刚播的词时，只能等组内循环绕回来（最多一整轮），或用「重播」重听当前词——**无法立即回听上一个词**。要求增加「上一个」按钮与功能。

## 2. 背景（源码事实）

1. 现控制集 = Play/Pause/Resume/Next/Replay/Exit（FR-11）；`advanceLocked`（DefaultLearningEngine）为**纯前向**推导：组内 `orderInGroup` 正向取下一未掌握词 + 组尾回绕，代码与全部规格中无任何后退语义。
2. 播放位 = 持久化单一 `SessionWord.PLAYING` 行（`setPlayingWord` 单事务 clearPlaying→setPlaying，且拒绝 MASTERED 目标）——**反向推导无需任何新状态**，与 advance 同一数据面。
3. 关键不变量：会话只停在「最小还有未掌握词的组」，更早的组必然全掌握（组推进仅在当前组全掌握时发生）⇒ **previous 恒组内取词**，跨组回退天然不可达；`setPlayingWord` 拒绝 MASTERED 目标 ⇒ 已掌握词天然被跳过（与 Next 对称）。
4. 编排器 `next()` 既有模式（cancelStep → stopPorts → NonCancellable adopt）与 SCR-BUTTONLAG 顺序裁决可直接复用。

## 3. 方案（用户裁决 2026-09-26，八条冻结语义）

1. **取词规则**：Previous = 当前组内 `orderInGroup` 更小的最近一个**未掌握**词；跳过 MASTERED（对称 Next）。
2. **组首回绕**：当前词是组内第一个未掌握词时 → 回绕到组内**最后一个**未掌握词（镜像 §5 前进方向的组内循环回绕）；按钮永远有效，无禁用态。
3. **纯导航**：不改变任何词的掌握状态（Next/Replay 同款保证）；当前词回 PENDING 留在组内循环（`setPlayingWord` 既有事务语义）。
4. **单词自回绕**：组内仅剩当前词一个未掌握 → 回绕自身 = 从 seg0 重载（等效重播）。
5. **窗口作废**：CommandWindow 开着按「上一个」→ 作废窗口、跳上一词、当前词留 PENDING（镜像 Next 的窗口行为）。
6. **暂停态可用**：Paused（含 Error-paused）→ 跳上一词并从 seg0 播放（镜像 Next from Paused；TTS 失败态下的逃生口与 Next 一致）。
7. **UI 双行控制条**：行 1 =「上一个/下一个/重播」，行 2 =「暂停(继续)+退出」，两行 SpaceEvenly；既有 testTag 全保留，新按钮 `btn_prev`。
8. **仅按钮入口**：v1 不加语音命令（FR-12 预留命令表不动；NFR-8 语音↔手动等价性不涉及——Previous 本身就是手动控制）。

**引擎细节**：`previous(sessionId): AdvanceResult` 复用既有结果类型（`NextWord.completedGroupIndex` 恒 null——previous 永不离当前组，无组完成语义）；无 PLAYING 位（开场/掌握后顺延）→ 取组内**最大** `orderInGroup`（advance 对称取最小的镜像）；终态会话重复 previous = 幂等只读（镜像 advance）；会话不存在 → 抛 `RepositoryValidationException`。**编排器细节**：`previous()` 镜像 `next()`（isTransportActive 门 + cancelStep → stopPorts + NonCancellable adopt）；`advanceAndAdopt` 参数化为 `jumpAndAdopt(jump)` 共享体，前进/回退两变体，装载语义完全一致（loadWord 重置 specCursor=0 等）。

## 4. 不改的东西

- SQL schema（SessionWord 的 groupIndex/orderInGroup/status 已足够，零迁移零 user_version）、AppSetting 键、Gradle、模型资产；
- `advance` / `markMastered` / 窗口语义（FR-12）/ Pause/Resume 段级语义（ADR-09）/ 位置持久化双源优先级（L3：SessionWord.PLAYING 词级真相源不变）；
- 预留语音命令枚举（PAUSE/RESUME/NEXT/REPLAY/EXIT，DOMAIN_MODEL §3.3）不加 PREVIOUS；
- `SKIPPED` 状态仍不产生（previous 与 next 一样不改 SessionWord 状态）；
- `beginFromSnapshot` 起始词仍由 `engine.advance` 裁决（回退不参与开场）。

## 5. 测试与文档

- **TC-AE-34**（单编号，沿 TC-AE-33 先例）：
  - commonTest 新建 `LearningEnginePreviousTest`：组内降序取前一词并迁移 PLAYING / 跳过 MASTERED / 组首回绕组内最后未掌握 / 不回退进已掌握前组（20 词 2 组）/ 组内唯一未掌握词回绕自身 / 纯导航零掌握写入 / 无 PLAYING 位取组内最大 / 终态幂等只读 / 非 ACTIVE 零写入 / 会话不存在抛契约异常 / 新引擎实例零内存位续推；
  - jvmTest `PlaybackOrchestratorStateTest` 增 TC-AE-34 组：Playing 中纯跳转（PENDING 回循环 + PLAYING 迁移 + 未掌握数不减 + position 跟随新词）/ 跳过 MASTERED 到最近前驱 / CommandWindow 中作废窗口立即 Playing(上一词, seg0) / Paused 中跳上一词 seg0 / 单未掌握词等效重播；`exitStopsPortsClearsPositionAndIsIdempotent` 终态重入风暴补 previous；`PlaybackOrchestratorRestartTest` 增 previous 换词后陈旧 position 不误用（L3 双匹配镜像）；
  - app `LearningSessionViewModelTest` 增 J2：previous 转发恰一次 `engine.previous`、零 `markMastered`、新词 Playing seg0；
  - 装机走查：双行控制条布局 / 组首回绕体感（按钮全程有效）/ 窗口与暂停态下「上一个」行为 / 词卡面板随换词刷新。
- 文档：PROJECT_SPEC（FR-11 表 + 验收行 + §6 追溯 + 版本记录 1.23）；AUDIO_ENGINE_SPEC（§3 状态机行 + §6 控制表 + 版本记录 2.9）；LEARNING_ENGINE_SPEC（§5 previousWord 镜像伪码 + §11 接口草图 + 版本记录 1.6）；DOMAIN_MODEL（§8.3 + §10 + 版本记录 1.7）；TEST_PLAN（TC-AE-34 + FR-11 追溯 + 版本记录 2.25）；DECISION_LOG 新条目；
- 门禁：jvmTest / testDebugUnitTest / app:testDebugUnitTest / detekt×2 / checkPlatformBoundaries / assembleDebug。

## 6. 风险

- `LearningEngine` 接口加方法 = 编译破坏：jvmTest `RecordingEngine` 与 app `FakeLearningEngine` 两处手写 Fake 必须同批 override，否则测试编译失败。
- SCR-BUTTONLAG 顺序红线：previous 必须保持先 `cancelStep()` 再 `stopPorts()`，反转会复活神经 TTS「stop 后 speak 返回 completed」竞态。
- 换词到新词首个 savePosition 之间存在陈旧 KV 窗口——与 advance 路径完全相同（L3 双匹配 + TC-AE-18 已锁），RestartTest 补 previous 镜像用例钉死。
- 版本号占用：1.23/2.9/1.6/1.7/2.25 当前空闲（SCR-BUTTONLAG/SCR-SENTMERGE 未占号）；执行时如有他批先落需重查版本表。
