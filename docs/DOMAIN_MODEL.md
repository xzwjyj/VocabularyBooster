# DOMAIN_MODEL — 领域模型

> 状态：Phase 0 定稿 ｜ 版本 1.0 ｜ 日期：2026-09-01
> 上游：PROJECT_SPEC（FR-1 ~ FR-16）。本模型是所有引擎、数据库（DATABASE_SCHEMA）与 UI 的唯一领域依据。
> 约定：本文只定义**领域概念**（与存储无关）；表结构映射见 DATABASE_SCHEMA，二者字段一一对应。

---

## 1. 聚合总览

```
┌──────────────── 聚合 1：Word（全局共享，只读复用） ─────────────────┐
│                                                                    │
│  Word ──1:N── DefinitionEntry ──1:N── Example                      │
│              (POS / EN / CN)          (Sentence / Translation /    │
│                                       Audio / 来源元数据)           │
└────────────────────────────────────────────────────────────────────┘
                              ▲ 复用（不复制）
┌──────────────── 聚合 2：WordBook（生词本） ─────────────────────────┐
│                                                                    │
│  WordBook ──1:N── WordBookEntry ──1:N── WordBookEntryDefinition     │
│     ▲              (引用 Word)          (引用 DefinitionEntry,     │
│     │                                        includeExamples 开关)  │
│  WordBook ──1:N── WordMastery (以 生词本+词 为单位)                  │
│  WordBook(type) ──0..1── parent → 母本（DERIVED 血缘, D2/D3）        │
└────────────────────────────────────────────────────────────────────┘
┌──────────────── 聚合 3：LearningSession（学习会话） ────────────────┐
│  LearningSession ──1:N── SessionWord（队列物化：分组+顺序+状态）      │
│  LearningSession ──N:1── WordBook                                  │
└────────────────────────────────────────────────────────────────────┘
┌──────────────── 聚合 4：Achievement（勋章，永久） ──────────────────┐
│  Achievement ──0..1── WordBook（type=BOOK_COMPLETED 时必填）         │
└────────────────────────────────────────────────────────────────────┘
```

## 2. 实体定义

### 2.1 Word（单词，聚合根）

| 字段 | 类型 | 说明 |
|---|---|---|
| `wordId` | Long | 主键，自增 |
| `text` | String | 单词原文（保留用户/数据源原始大小写） |
| `normalizedText` | String | 归一化：小写 + 去首尾空白；**全局唯一**，用于查询与去重 |
| `ipaAm` / `ipaBr` | String? | 美音 / 英音 IPA 音标（可空） |
| `pronunciationAudioUri` | String? | 授权发音音频（预留；v1 用 TTS） |
| `createdAt` / `updatedAt` | Instant | 创建 / 最后更新时间 |

**不变量**：`normalizedText` 全局唯一 → 同一单词全库只有一行；任何生词本"保存"都是**引用**此行（FR-5 复用）。

### 2.2 DefinitionEntry（释义条目，Word 聚合内）

| 字段 | 类型 | 说明 |
|---|---|---|
| `definitionEntryId` | Long | 主键 |
| `wordId` | Long | 所属 Word |
| `partOfSpeech` | String | 词性（小写规范名，见 §3.1） |
| `partOfSpeechOrder` | Int | 词性组排序键（§4） |
| `definitionOrder` | Int | 同词性组内排序键 |
| `meaningEN` | String | 英文释义（必填） |
| `meaningCN` | String | 中文释义（必填） |

**不变量**：`(wordId, partOfSpeech, definitionOrder)` 唯一；`MeaningEN / MeaningCN` 属于同一条目，任何层都不允许拆分为独立单元（PROJECT_SPEC FR-2）。

### 2.3 Example（例句单元，DefinitionEntry 聚合内）

| 字段 | 类型 | 说明 |
|---|---|---|
| `exampleId` | Long | 主键 |
| `definitionEntryId` | Long | 所属释义 |
| `sentence` | String | 例句原文（EN） |
| `chineseTranslation` | String | 例句中文译文 |
| `sourceType` | SourceType | 来源类型（枚举，§3.2） |
| `sourceRef` | String? | 出处（影视名 / 演讲标题 / 书名…） |
| `licenseNote` | String? | 授权说明（合规留存） |
| `audioUri` | String? | 原声音频地址（无则 TTS 朗读 `sentence`） |
| `audioDurationMs` | Long? | 音频时长（播放器预加载/进度条用） |
| `exampleOrder` | Int | 同一释义内多条例句的顺序 |

**不变量**：`(Sentence, ChineseTranslation, Audio)` 是**原子单元**——收藏选择、播放开关、删除均以整个 Example 为粒度，不可只保留译文不保留原句（FR-3）。

### 2.4 WordBook（生词本，聚合根）

| 字段 | 类型 | 说明 |
|---|---|---|
| `wordBookId` | Long | 主键 |
| `type` | WordBookType | `ORIGINAL`（母本，用户创建）/ `DERIVED`（派生快照，会话退出时系统创建）——决策 D3 |
| `name` | String | 显示名（可重名；用户可重命名） |
| `description` | String? | 备注（可空） |
| `parentWordBookId` | Long? | 母本 ID（仅 DERIVED 本必填；ORIGINAL 为 null） |
| `sourceSessionId` | Long? | 来源会话 ID（仅 DERIVED 本必填） |
| `createdAt` / `updatedAt` | Instant | |

**不变量（D2）**：ORIGINAL 本是**永久母本**——学习会话退出永不修改其内容；DERIVED 本是某次会话退出时刻的**未掌握词快照**，只新增关系行，复用 Word / DefinitionEntry / Example，不继承母本掌握状态（D4）。

### 2.5 WordBookEntry（生词本词条）

| 字段 | 类型 | 说明 |
|---|---|---|
| `wordBookEntryId` | Long | 主键 |
| `wordBookId` / `wordId` | Long | 所属本 / 引用的词 |
| `entryOrder` | Int | 本内顺序（入本先后；学习队列排序键） |
| `pendingTranslation` | String? | TXT 导入携带的临时译文（IMPORT_SPEC；DictionaryProvider 回填正式释义后清除） |
| `addedAt` | Instant | 加入时间 |

**不变量**：`(wordBookId, wordId)` 唯一——一个词在一本内只有一条 entry（可进多个本）。

### 2.6 WordBookEntryDefinition（收藏时的释义选择）

| 字段 | 类型 | 说明 |
|---|---|---|
| `wordBookEntryDefinitionId` | Long | 主键 |
| `wordBookEntryId` | Long | 所属生词本词条 |
| `definitionEntryId` | Long | 选中的释义 |
| `includeExamples` | Boolean | 是否携带该释义的 Example（默认 true） |

**不变量**：`(wordBookEntryId, definitionEntryId)` 唯一；`definitionEntryId` 必须属于 `wordBookEntryId` 指向的 Word（应用层校验）；学习播放只播放被选中的释义（FR-10）。

### 2.7 WordMastery（掌握记录）

| 字段 | 类型 | 说明 |
|---|---|---|
| `wordBookId` + `wordId` | 复合主键 | 掌握以（生词本, 词）为单位 |
| `masteredAt` | Instant | 掌握确认时刻 |

**语义**：**行存在 = 已 MASTERED**；无行 = 未掌握。不可逆（v1 无删除入口）。

### 2.8 LearningSession（学习会话，聚合根）

| 字段 | 类型 | 说明 |
|---|---|---|
| `sessionId` | Long | 主键 |
| `wordBookId` | Long | 学习的生词本 |
| `status` | SessionStatus | `ACTIVE / COMPLETED / ABANDONED` |
| `groupSize` | Int | 会话固化的分组大小（FR-6） |
| `startedAt` | Instant | |
| `endedAt` | Instant? | |

### 2.9 SessionWord（队列物化）

| 字段 | 类型 | 说明 |
|---|---|---|
| `sessionId` + `wordId` | 复合主键 | |
| `groupIndex` | Int | 组号（0 起） |
| `orderInGroup` | Int | 组内顺序（0 起） |
| `status` | SessionWordStatus | `PENDING / PLAYING / MASTERED / SKIPPED` |
| `masteredAt` | Instant? | |

**语义**：会话开始时把"未掌握队列"固化成本表（每词一行，含组号）；会话恢复/崩溃恢复都以此为准（NFR-3）。

### 2.10 Achievement（勋章）

| 字段 | 类型 | 说明 |
|---|---|---|
| `achievementId` | Long | 主键 |
| `type` | AchievementType | v1 仅 `BOOK_COMPLETED`；预留 `STREAK_DAYS / TOTAL_WORDS_MASTERED / GROUPS_COMPLETED / BOOKS_COMPLETED` |
| `wordBookId` | Long? | BOOK_COMPLETED 时必填且**唯一**（幂等，FR-13） |
| `payloadJson` | String | 快照：`{bookName, wordCount, …}`（防改名/删除影响勋章真实性） |
| `earnedAt` | Instant | 授予时刻（永久，不可删除） |

## 3. 值对象与枚举

### 3.1 PartOfSpeech 词性规范序（`partOfSpeechOrder` 的唯一依据）

| 序 | 词性 | 序 | 词性 |
|---|---|---|---|
| 0 | verb | 6 | conjunction |
| 1 | noun | 7 | interjection |
| 2 | adjective | 8 | determiner |
| 3 | adverb | 9 | numeral |
| 4 | pronoun | 10 | phrase |
| 5 | preposition | ≥90 | 其他（90+字典序，数据源自定义扩展） |

> 序 0–10 为平台内置规范序；与 PROJECT_SPEC FR-2 示例一致（verb → noun → adjective）。`partOfSpeechOrder` 由 **DictionaryProvider** 在写入时分配，展示/排序层只消费不计算。

### 3.2 SourceType（例句来源）

`REAL_MOVIE_TV` / `CELEBRITY_SPEECH` / `TED` / `AUDIOBOOK` / `LICENSED_OTHER` / `TTS`

### 3.3 VoiceCommand（语音命令）

| 枚举 | 触发词 | v1 状态 |
|---|---|---|
| `MASTERED` | "会了"（别名：记住了 / 掌握了） | ✅ 实现 |
| `PAUSE` | "暂停" | 预留 |
| `RESUME` | "继续" | 预留 |
| `NEXT` | "下一个" | 预留 |
| `REPLAY` | "再来一次" | 预留 |
| `EXIT` | "退出" | 预留 |

命令**解析**（文本 → 枚举）为共享纯 Kotlin（`speech` 包）；识别引擎平台注入（AUDIO_ENGINE_SPEC）。

### 3.4 PlaybackToggleKey（播放开关，六项）

`PRONUNCIATION / SPELLING / MEANING_EN / MEANING_CN / EXAMPLE / EXAMPLE_CN`

### 3.5 WordBookType（生词本类型，D3）

`ORIGINAL`（用户创建的母本） / `DERIVED`（会话退出派生的快照本；必须携带 `parentWordBookId` + `sourceSessionId`，不继承母本掌握状态）

## 4. 排序不变量（核心规则）

**词条展示与播放的 DefinitionEntry 顺序恒为：**

```
ORDER BY partOfSpeechOrder ASC, definitionOrder ASC
```

- I-1 同词性的所有 DefinitionEntry **连续**出现（先分组，再排组内）；
- I-2 一个词性的全部条目完成后再进入下一个词性；
- I-3 组内按 `definitionOrder` 升序；
- I-4 该规则同时适用于：词条详情 UI、播放序列构建（AUDIO_ENGINE_SPEC §3）、生词本释义选择界面；
- I-5 违反连续性 = 渲染/排序 bug，测试矩阵必须有专项用例（TEST_PLAN TC-DM-01…03）。

## 5. 展示不变量

- I-6 `MeaningEN` 恒在 `MeaningCN` 之前（同一 DefinitionEntry 内，不可拆分选择）；
- I-7 `Example` 内 `Sentence`（EN）恒在 `ChineseTranslation`（CN）之前，播放顺序同理；
- I-8 播放序列结构（FR-10）：`PRONUNCIATION → SPELLING → [对每个选中释义: MEANING_EN → MEANING_CN → [每个选中 Example: EXAMPLE(Sentence/原声) → EXAMPLE_CN(译文)]]`。

## 6. ID / 时间 / 命名策略

- **ID**：Long 自增（无云同步需求；若未来引入同步，迁移到 UUID 须单列 RFC——记入开放问题区）；
- **时间**：kotlinx-datetime `Instant`（UTC 存储，展示按本地时区）；
- **派生生词本命名**（FR-9）：`"{母本名称} yyyy-MM-dd HH:mm"`（24h 制，本地时区）；冲突时 `-2`、`-3`…；命名器为纯 Kotlin（`WordBookDeriver`）。

## 7. 聚合边界与所有权（删除策略）

| 操作 | 允许？ | 规则 |
|---|---|---|
| 删除 WordBook | ✅（无完成勋章 **且** 无派生子本时） | 级联删除其 WordBookEntry / EntryDefinition / **本内** WordMastery / 未完成会话；**Word 与 DefinitionEntry 不动**；存在派生子本 → 拒绝（血缘完整，先处理子本） |
| 删除有勋章的 WordBook | ❌ | FR-13 永久性；UI 提示 |
| 全局删除 Word | ❌ v1 | 多本引用 + 授权例句留存，不提供；只做"从某本移除"（删 entry 关系） |
| 删除 Achievement | ❌ | 永久记录 |
| 删除 ACTIVE 会话 | ❌ | 标记 ABANDONED（保留掌握事实） |

**核心所有权原则**：Word 聚合是**全局共享只读**的；生词本只拥有"关系 + 选择 + 本内掌握状态"；任何"删除"都止步于关系层。

## 8. 状态机

### 8.1 WordMastery（按 生词本+词）
```
ABSENT（无行）──"会了"──▶ MASTERED（行存在，masteredAt 记录）
        （不可逆，v1 无反向迁移）
```

### 8.2 LearningSession（退出三分支，决策 D1）
```
ACTIVE ──整本完成（含退出瞬间发现 MASTERED=ALL）──▶ COMPLETED（勋章，不派生）
ACTIVE ──用户退出，MASTERED=0───────────────────▶ ABANDONED（无派生，保存会话状态）
ACTIVE ──用户退出，0<MASTERED 且 REMAINING>0─────▶ ABANDONED（派生 DERIVED 本）
```

### 8.3 SessionWord
```
PENDING ──开始播放──▶ PLAYING ──"会了"──▶ MASTERED
   └──────────────────Next 跳过──────────────▶（回到组内循环，仍 PENDING）
```

### 8.4 WordBook 完成度（派生状态，不落库）
```
completion = masteredEntryCount / entryCount
 = 1.0  → BOOK_COMPLETED 事件 → 勋章（幂等）
```

## 9. 领域事件（kotlinx-serialization，共享 EventBus 分发）

| 事件 | 载荷 | 消费方 |
|---|---|---|
| `WordMasterConfirmed` | sessionId, wordBookId, wordId | 学习引擎（推进）、未来统计 |
| `GroupCompleted` | sessionId, groupIndex | 学习引擎、未来统计 |
| `WordBookCompleted` | wordBookId, bookName, wordCount | 勋章引擎（FR-13）、UI |
| `SessionExited` | sessionId, derivedWordBookId? | UI、统计 |
| `AchievementUnlocked` | type, wordBookId | UI（勋章展示） |
| `ImportFinished` | 目标本、五项计数报告 | UI、统计 |
| `PlaybackConfigChanged` | 开关集 | 播放编排器 |

## 10. 领域服务（纯 Kotlin，归属 shared 包）

| 服务 | 职责 | 规格 |
|---|---|---|
| `StudyQueueBuilder` | 未掌握 entry → 队列（entryOrder） | LEARNING_ENGINE_SPEC §3 |
| `GroupSplitter` | 队列 → 组（groupSize，会话固化） | LEARNING_ENGINE_SPEC §4 |
| `MasteryMarker` | 事务化：SessionWord→MASTERED + WordMastery 落行 + 队列移除 | LEARNING_ENGINE_SPEC §6 |
| `CompletionDetector` | 组完成→推进下一组；书完成→事件 | LEARNING_ENGINE_SPEC §7 |
| `WordBookDeriver` | 退出三分支裁决（零掌握不派生 / 部分掌握派生 DERIVED 快照本 / 全部完成不派生） | LEARNING_ENGINE_SPEC §8 |
| `SegmentBuilder` | Word + 开关 → 播放分段序列（I-8） | AUDIO_ENGINE_SPEC §3 |
| `PlaybackOrchestrator` | 播放状态机（Play/Pause/Resume/Next/Replay/Exit） | AUDIO_ENGINE_SPEC §5 |
| `CommandParser` | 识别文本 → VoiceCommand | AUDIO_ENGINE_SPEC §7 |
| `ImportEngine` | 编码检测/解析/去重/流式导入 | IMPORT_SPEC |
| `AchievementEngine` | 事件 → 判定 → 幂等授予 | ACHIEVEMENT_SPEC |

---

| 版本 | 日期 | 变更 |
|---|---|---|
| 1.0 | 2026-09-01 | Phase 0 初版 |
| 1.1 | 2026-09-01 | 冻结 D1–D4：WordBook.type（ORIGINAL/DERIVED）、parentWordBookId + sourceSessionId、母本不变性、退出三分支、掌握作用域 |
