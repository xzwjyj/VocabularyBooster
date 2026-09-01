# LEARNING_ENGINE_SPEC — 学习引擎规格

> 状态：Phase 0 定稿 ｜ 版本 1.0 ｜ 日期：2026-09-01
> 上游：PROJECT_SPEC FR-6 ~ FR-9 ｜ 模型：DOMAIN_MODEL §8 状态机、§10 服务 ｜ 存储：DATABASE_SCHEMA §2.8/2.9
> 位置：`shared/learning/`，**纯 Kotlin，JVM 可全量单测**。

---

## 1. 职责边界

| 归学习引擎 | 归播放引擎（AUDIO_ENGINE_SPEC） |
|---|---|
| 队列构建、分组固化、组内循环规则 | 一个 Word 内的分段（Segment）播放 |
| "会了"落库（掌握 + 队列移除） | 分段时序、暂停/恢复位置 |
| 组推进 / 整本完成检测 | 命令窗口时序、命令文本解析 |
| 退出时的派生生词本 | 调用 `markMastered()` / `advance()` |

两引擎通过 `LearningEngine` 接口协作：播放引擎是**驱动者**（时序），学习引擎是**规则裁决者**（状态变更）。

## 2. 会话生命周期总览

```
选生词本
   │
   ▼
[开始] ──▶ StudyQueueBuilder ──▶ GroupSplitter ──▶ 会话物化(事务)
   │             （未掌握队列）     （groupSize 固化）      │
   │                                                   ACTIVE
   ▼
播放循环（AUDIO_ENGINE 驱动，每词：播放 → 命令窗口）
   │  "会了" ──▶ MasteryMarker（事务）──▶ CompletionDetector
   │                                        │
   │              组完成 ──────────────────┤ 进入下一组
   │              书完成 ──────────────────┤ 停止播放 → WordBookCompleted 事件
   ▼                                        ▼
[退出 Exit] ──▶ 三分支裁决（§8，D1）         [整本完成 COMPLETED] → 勋章
   │
   └─▶ ABANDONED
```

## 3. 队列构建（StudyQueueBuilder）

- 输入：`wordBookId`。
- 队列 = 该本所有**未 MASTERED** 的 `WordBookEntry`，按 `entryOrder ASC`（DATABASE_SCHEMA Q2）。
- **开始前校验**（拒绝并给出明确原因）：
  - 本无词条 → `StartResult.Rejected(EMPTY_BOOK)`；
  - 队列为空（全部已掌握）→ `Rejected(ALL_MASTERED)`（引导查看勋章）；
  - 六项播放开关全关 → `Rejected(PLAYBACK_DISABLED)`（FR-10 验收）；
  - 已存在 ACTIVE 会话 → `Rejected(ACTIVE_SESSION_EXISTS(sessionId))`，UI 引导「恢复」或「放弃旧的」（全局同一时刻最多一个 ACTIVE 会话）。
- **物化**（单事务）：`INSERT LearningSession(status=ACTIVE, groupSize=设置值)` + 为队列每词 `INSERT SessionWord(status=PENDING, groupIndex, orderInGroup)`。

## 4. 分组（GroupSplitter）

- `groupIndex = floor(队列位置 / groupSize)`（0 起，入库固化）；用户可见组号 = `groupIndex + 1`。
- 100 词、groupSize=10 → 10 组；37 词 → 4 组（末组 7 词）。
- **固化**：groupSize 之后改设置只影响**新会话**（FR-6 第 4 条）。

## 5. 组内循环规则（引擎对"下一个未掌握词"的唯一裁决）

```
nextWord(session):
  words = SessionWord WHERE sessionId AND status != MASTERED
  if words.isEmpty(): return BookComplete            # → §7
  g = min(groupIndex of words)                        # 当前组 = 最小还有未掌握词的组
  cur = 当前播放中的 orderInGroup（若有，否则 g 组内 min）
  next = words in group g with orderInGroup > cur   # 顺序推进
  if next.isEmpty(): next = words in group g with orderInGroup >= 0   # 回绕循环
  return next.first
```

- 语义保证：**组内循环**（一轮播完回到组内第一个未掌握词）；组空则自然推进到下一组（§7）；`orderInGroup` 顺序严格保持。

## 6. "会了"落库（MasteryMarker）

- 入口唯一：`markMastered(sessionId, wordId, source: VOICE | BUTTON)`——语音与按钮完全等价（NFR-8）。
- **单事务**（DATABASE_SCHEMA §4）：
  1. `UPDATE SessionWord SET status='MASTERED', masteredAt=? WHERE sessionId=? AND wordId=? AND status!='MASTERED'`（0 行受影响 → 幂等返回 `AlreadyMastered`）；
  2. `INSERT OR IGNORE WordMastery(wordBookId, wordId, masteredAt)`（复合主键天然幂等）。
- 成功后发 `WordMasterConfirmed` 事件；队列移除是**状态推导**（下次 advance 自动跳过），不做删除。
- 播放引擎在调用后立即 `advance()`（FR-7：立即继续下一个未掌握词）。

## 7. 完成检测（CompletionDetector）

- 每次 `WordMasterConfirmed` 后评估：
  - 当前组在 SessionWord 中无未掌握词 → 发 `GroupCompleted(sessionId, groupIndex)`（UI 可用；推进本身由 §5 自动完成）；
  - 书级：Q3 `countUnmastered(wordBookId) == 0` → 发 `WordBookCompleted`。
- `WordBookCompleted` 时引擎动作序列（顺序不可变，FR-8）：
  1. 通知播放引擎**停止播放**；
  2. `UPDATE LearningSession SET status='COMPLETED', endedAt=?`；
  3. 勋章授予交给 AchievementEngine 订阅事件完成（ACHIEVEMENT_SPEC）。

## 8. 退出与派生（WordBookDeriver，决策 D1/D2/D3/D4 已冻结）

- 触发：`exitSession(sessionId)`，会话 ACTIVE。按会话掌握状态**三分支**裁决（`MASTERED` = 本会话已掌握数，`REMAINING` = 母本当前未掌握数，**一律以数据库实时状态为准，不信任缓存计数**）：

| 分支 | 条件 | 行为 |
|---|---|---|
| **A 零掌握** | MASTERED = 0 | 会话 `ABANDONED`（endedAt）——**保存会话状态与学习历史**；**不创建**派生本（不产生空本）；母本完全不变 |
| **B 部分掌握** | MASTERED > 0 且 REMAINING > 0 | 会话 `ABANDONED` + **单事务**派生 DERIVED 本（步骤见下）；母本不变 |
| **C 全部掌握** | REMAINING = 0 | 会话 `COMPLETED`（endedAt）+ 停止播放 + 解锁勋章（§7 / ACHIEVEMENT_SPEC）；**不创建**派生本 |

> 分支 C 通常已由正常流程在最后一词 master 时触发（§7）；用户在"最后一词已 master、完成检测尚未执行"的瞬间点退出同样归入分支 C——裁决只看数据库。

**分支 B 派生步骤（单事务，DATABASE_SCHEMA §4）**：
1. 命名：`"{母本.name} yyyy-MM-dd HH:mm"`（本地时区，注入 Clock）；重名追加 `-2`、`-3`…；
2. `insertDerivedWordBook`：`INSERT WordBook(type='DERIVED', name, parentWordBookId=母本, sourceSessionId=本会话, createdAt)`；
3. 复制 `WordBookEntry`：母本当前未掌握词（Q2 队列 / Q5 复制），**保留 entryOrder 与 pendingTranslation**；
4. 复制对应 `WordBookEntryDefinition`（**保留 includeExamples**）；
5. **不复制** WordMastery（D4：掌握属于「生词本+词」，派生本从"未掌握"全新开始）；
6. **不触碰** Word / DefinitionEntry / Example 任何行（复用不复制）；**母本（ORIGINAL）保持原样**——永久母本原则（D2）。

- 完成后发 `SessionExited(sessionId, derivedWordBookId?)`（分支 A/C 中 derivedWordBookId = null）。
- 派生本与母本词条选择**逐字一致**（验收：比对两组关系行，仅 bookId 不同）。

## 9. 会话恢复（崩溃 / 进程死亡，NFR-3）

- App 启动：查询 ACTIVE 会话 → 有则 UI 询问「恢复上次学习？」。
- 恢复 = 从 `SessionWord` 重建队列/分组 + 播放引擎从 `AppSetting["playback.position"]`（AUDIO_ENGINE_SPEC §5）恢复词/段位置。
- 完整性检查：会话所属书仍存在；`SessionWord` 每词仍存在于 `WordBookEntry`。不满足 → 会话 ABANDONED + 提示（不静默丢数据）。

## 10. 边界情形表（全部须有测试用例，TEST_PLAN TC-LE-xx）

| # | 情形 | 规定行为 |
|---|---|---|
| 1 | 空生词本 / 全部已掌握 | 拒绝开始（§3），明确原因 |
| 2 | 词数 < groupSize | 正常单组 |
| 3 | 导入词（无释义） | 播放仅 PRONUNCIATION + SPELLING 段；"会了"照常 |
| 4 | "会了"出现在词刚开始播放时 | 立即落库并 advance（当前段音频立即停） |
| 5 | 对已 MASTERED 词再次"会了" | 幂等：`AlreadyMastered`，无重复计数 |
| 6 | 会话 ACTIVE 时杀进程 | 恢复流程（§9） |
| 7 | 退出时掌握数为 0 | 分支 A（D1）：不派生、不建空本；保存会话状态与学习历史 |
| 8 | 恢复时书已删除 | ABANDONED + 提示 |
| 9 | 恢复时某词已从本中移除 | 该词从会话剔除，其余照常 |
| 10 | 派生时母本正被编辑 | 派生读当下 DB 快照（事务隔离保证一致） |
| 11 | 退出瞬间恰为最后一词 master（MASTERED=ALL） | 分支 C（D1）：COMPLETED + 勋章，不派生（以 DB 实时状态裁决） |

## 11. 引擎公共 API（接口草图，Phase 3+ 实现）

```kotlin
interface LearningEngine {
    val state: StateFlow<LearningState>
    suspend fun startSession(wordBookId: Long): StartResult
    suspend fun resumeSession(sessionId: Long): ResumeResult
    suspend fun markMastered(sessionId: Long, wordId: Long, source: MasterySource): MasteryResult
    suspend fun advance(sessionId: Long): AdvanceResult        // WordRef | BookComplete
    suspend fun exitSession(sessionId: Long): ExitResult      // ExitResult(derivedWordBookId: Long?)
    fun abandon(reason: AbandonReason)
}
```

- `WordRef` 只带 `sessionId/wordId/groupIndex/orderInGroup`；分段内容查询属播放引擎（缓存策略见 AUDIO_ENGINE_SPEC §3）。

## 12. 确定性与可测性

- `Clock` / `CoroutineScope` / 派发器全部注入；引擎不取系统时间、不碰随机源；
- §5 / §7 / §8 的规则全部实现为纯函数（输入 SessionWord 快照 → 输出决定），状态变更薄封装在其上；
- 测试要求：§10 十条边界 + 100 词 ×10 组循环推进 + 派生内容一致性断言，全部 JVM commonTest。

---

| 版本 | 日期 | 变更 |
|---|---|---|
| 1.0 | 2026-09-01 | Phase 0 初版 |
| 1.1 | 2026-09-01 | 冻结 D1–D4：§8 重写为退出三分支；边界情形 #7 更新、新增 #11；"父本"统一改称"母本" |
