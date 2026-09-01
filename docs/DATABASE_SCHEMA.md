# DATABASE_SCHEMA — 数据库规格（SQLDelight）

> 状态：Phase 0 定稿 ｜ 版本 1.0 ｜ 日期：2026-09-01
> 上游：DOMAIN_MODEL §2（实体字段一一对应）。
> 选型：**SQLDelight 2.x**（KMP 原生支持 Android/iOS 同一套 `.sq` schema；生成类型安全 Kotlin API；支持 Flow 查询）。iOS 无需任何 schema 重写。

---

## 1. 存储约定

| 项 | 约定 |
|---|---|
| 数据库名 | `vocabulary.db`（SQLDelight database name: `VocabularyDatabase`） |
| 主键 | `INTEGER PRIMARY KEY AUTOINCREMENT`（Long） |
| 时间 | `INTEGER` epoch **毫秒**（kotlinx-datetime `Instant.toEpochMilliseconds()`） |
| 布尔 | `INTEGER` 0/1（SQLDelight 映射 Boolean） |
| 枚举 | `TEXT`（领域枚举名，应用层映射） |
| 外键 | **强制开启**：Android 驱动必须 `PRAGMA foreign_keys = ON`（SQLite 默认关闭，Phase 1 在 Driver 工厂中配置；iOS 驱动同样） |
| schema 文件位置 | `shared/src/commonMain/sqldelight/com/vocabularybooster/db/*.sq`（Phase 1 引入） |
| 文件组织 | 每表一个 `.sq` 文件（建表 + 本表查询）；跨表查询放 `Queries.sq` |

## 2. 表结构 DDL

### 2.1 Word

```sql
CREATE TABLE Word (
  wordId                 INTEGER PRIMARY KEY AUTOINCREMENT,
  text                   TEXT NOT NULL,
  normalizedText         TEXT NOT NULL,
  ipaAm                  TEXT,
  ipaBr                  TEXT,
  pronunciationAudioUri  TEXT,          -- 授权发音音频（预留，v1 用 TTS）
  createdAt              INTEGER NOT NULL,
  updatedAt              INTEGER NOT NULL
);
CREATE UNIQUE INDEX Word_normalizedText ON Word(normalizedText);
```

### 2.2 DefinitionEntry

```sql
CREATE TABLE DefinitionEntry (
  definitionEntryId  INTEGER PRIMARY KEY AUTOINCREMENT,
  wordId             INTEGER NOT NULL REFERENCES Word(wordId) ON DELETE CASCADE,
  partOfSpeech       TEXT NOT NULL,      -- 规范名，DOMAIN_MODEL §3.1
  partOfSpeechOrder  INTEGER NOT NULL,   -- 词性组序
  definitionOrder    INTEGER NOT NULL,   -- 组内序
  meaningEN          TEXT NOT NULL,
  meaningCN          TEXT NOT NULL
);
CREATE UNIQUE INDEX DefinitionEntry_unique
  ON DefinitionEntry(wordId, partOfSpeech, definitionOrder);
CREATE INDEX DefinitionEntry_displayOrder
  ON DefinitionEntry(wordId, partOfSpeechOrder, definitionOrder);   -- 核心排序查询
```

### 2.3 Example

```sql
CREATE TABLE Example (
  exampleId          INTEGER PRIMARY KEY AUTOINCREMENT,
  definitionEntryId  INTEGER NOT NULL REFERENCES DefinitionEntry(definitionEntryId) ON DELETE CASCADE,
  sentence           TEXT NOT NULL,
  chineseTranslation TEXT NOT NULL,
  sourceType         TEXT NOT NULL,      -- REAL_MOVIE_TV|CELEBRITY_SPEECH|TED|AUDIOBOOK|LICENSED_OTHER|TTS
  sourceRef          TEXT,
  licenseNote        TEXT,                -- 合规留存（NFR-5）
  audioUri           TEXT,                -- 原声；空则 TTS 朗读 sentence
  audioDurationMs    INTEGER,
  exampleOrder       INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX Example_byEntry ON Example(definitionEntryId, exampleOrder);
```

### 2.4 WordBook

```sql
CREATE TABLE WordBook (
  wordBookId            INTEGER PRIMARY KEY AUTOINCREMENT,
  type                  TEXT NOT NULL DEFAULT 'ORIGINAL',   -- ORIGINAL|DERIVED（决策 D3）
  name                  TEXT NOT NULL,                      -- 可重名
  description           TEXT,
  parentWordBookId      INTEGER REFERENCES WordBook(wordBookId) ON DELETE RESTRICT,  -- 母本；仅 DERIVED 必填
  sourceSessionId       INTEGER REFERENCES LearningSession(sessionId),               -- 来源会话；仅 DERIVED 必填
  createdAt             INTEGER NOT NULL,
  updatedAt             INTEGER NOT NULL,
  CHECK (type IN ('ORIGINAL','DERIVED'))
);
CREATE INDEX WordBook_parent ON WordBook(parentWordBookId);   -- 血缘查询
```

### 2.5 WordBookEntry

```sql
CREATE TABLE WordBookEntry (
  wordBookEntryId  INTEGER PRIMARY KEY AUTOINCREMENT,
  wordBookId       INTEGER NOT NULL REFERENCES WordBook(wordBookId) ON DELETE CASCADE,
  wordId           INTEGER NOT NULL REFERENCES Word(wordId),        -- 删本不删词
  entryOrder       INTEGER NOT NULL,          -- 学习队列排序键
  pendingTranslation  TEXT,               -- TXT 导入的临时译文（IMPORT_SPEC；正式释义回填后清除）
  addedAt          INTEGER NOT NULL
);
CREATE UNIQUE INDEX WordBookEntry_unique ON WordBookEntry(wordBookId, wordId);
CREATE INDEX WordBookEntry_order ON WordBookEntry(wordBookId, entryOrder);
```

### 2.6 WordBookEntryDefinition（收藏时的释义选择）

```sql
CREATE TABLE WordBookEntryDefinition (
  wordBookEntryDefinitionId  INTEGER PRIMARY KEY AUTOINCREMENT,
  wordBookEntryId            INTEGER NOT NULL REFERENCES WordBookEntry(wordBookEntryId) ON DELETE CASCADE,
  definitionEntryId          INTEGER NOT NULL REFERENCES DefinitionEntry(definitionEntryId),
  includeExamples            INTEGER NOT NULL DEFAULT 1
);
CREATE UNIQUE INDEX WordBookEntryDefinition_unique
  ON WordBookEntryDefinition(wordBookEntryId, definitionEntryId);
```

### 2.7 WordMastery（行存在 = MASTERED）

```sql
CREATE TABLE WordMastery (
  wordBookId  INTEGER NOT NULL REFERENCES WordBook(wordBookId) ON DELETE CASCADE,
  wordId      INTEGER NOT NULL REFERENCES Word(wordId),
  masteredAt  INTEGER NOT NULL,
  PRIMARY KEY (wordBookId, wordId)
);
```

### 2.8 LearningSession

```sql
CREATE TABLE LearningSession (
  sessionId   INTEGER PRIMARY KEY AUTOINCREMENT,
  wordBookId  INTEGER NOT NULL REFERENCES WordBook(wordBookId) ON DELETE RESTRICT, -- 先 ABANDONED 再删本
  status      TEXT NOT NULL,      -- ACTIVE|COMPLETED|ABANDONED
  groupSize   INTEGER NOT NULL,   -- 会话固化值
  startedAt   INTEGER NOT NULL,
  endedAt     INTEGER
);
```

### 2.9 SessionWord（队列物化）

```sql
CREATE TABLE SessionWord (
  sessionId     INTEGER NOT NULL REFERENCES LearningSession(sessionId) ON DELETE CASCADE,
  wordId        INTEGER NOT NULL REFERENCES Word(wordId),
  groupIndex    INTEGER NOT NULL,
  orderInGroup  INTEGER NOT NULL,
  status        TEXT NOT NULL,    -- PENDING|PLAYING|MASTERED|SKIPPED
  masteredAt    INTEGER,
  PRIMARY KEY (sessionId, wordId)
);
CREATE INDEX SessionWord_queue ON SessionWord(sessionId, groupIndex, orderInGroup);
```

### 2.10 Achievement

```sql
CREATE TABLE Achievement (
  achievementId  INTEGER PRIMARY KEY AUTOINCREMENT,
  type            TEXT NOT NULL,   -- v1: BOOK_COMPLETED；预留 STREAK_DAYS / TOTAL_WORDS_MASTERED / GROUPS_COMPLETED / BOOKS_COMPLETED
  wordBookId      INTEGER REFERENCES WordBook(wordBookId),   -- NULL=非书本类勋章
  payloadJson     TEXT NOT NULL,   -- {bookName, wordCount, …} 快照
  earnedAt        INTEGER NOT NULL
);
CREATE UNIQUE INDEX Achievement_unique ON Achievement(type, wordBookId);
-- SQLite UNIQUE 中 NULL 互不相等：未来全局类勋章（wordBookId=NULL）可多次授予
```

### 2.11 AppSetting

```sql
CREATE TABLE AppSetting (
  key        TEXT PRIMARY KEY,
  valueJson  TEXT NOT NULL          -- kotlinx-serialization 序列化的设置值
);
```

内置键（与 PROJECT_SPEC FR-15 对应）：`settings.groupSize`(10)、`settings.commandWindowMs`(4000)、`settings.playbackToggles`（六项默认全开）、`settings.ttsRate`(1.0)、`settings.ttsPitch`(1.0)、`settings.masteredAliases`（["会了","记住了","掌握了"]）。

## 3. 关键查询（负载承载）

```sql
-- Q1 词条展示排序（PROJECT_SPEC FR-2 排序规则，唯一入口）
selectDefinitionsForWord:
SELECT * FROM DefinitionEntry WHERE wordId = ? ORDER BY partOfSpeechOrder ASC, definitionOrder ASC;

-- Q2 学习队列：生词本当前未掌握的词
selectStudyQueue:
SELECT wbe.* FROM WordBookEntry wbe
LEFT JOIN WordMastery wm ON wm.wordBookId = wbe.wordBookId AND wm.wordId = wbe.wordId
WHERE wbe.wordBookId = ? AND wm.wordId IS NULL
ORDER BY wbe.entryOrder ASC;

-- Q3 完成度检测（=0 → BOOK_COMPLETED 事件）
countUnmastered:
SELECT COUNT(*) FROM WordBookEntry wbe
LEFT JOIN WordMastery wm ON wm.wordBookId = wbe.wordBookId AND wm.wordId = wbe.wordId
WHERE wbe.wordBookId = ? AND wm.wordId IS NULL;

-- Q4 播放用的词条选择（只播被选中的释义）
selectSelectedDefinitions:
SELECT de.* FROM WordBookEntryDefinition wed
JOIN DefinitionEntry de ON de.definitionEntryId = wed.definitionEntryId
WHERE wed.wordBookEntryId = ?
ORDER BY de.partOfSpeechOrder ASC, de.definitionOrder ASC;

-- Q5 派生 DERIVED WordBook（D1 分支 B）：先建本（type=DERIVED + 血缘），再复制关系（不复制 Word / DefinitionEntry / Example）
insertDerivedWordBook:
INSERT INTO WordBook(type, name, description, parentWordBookId, sourceSessionId, createdAt, updatedAt)
VALUES ('DERIVED', ?, ?, ?, ?, ?, ?);

copyEntryRelations:
INSERT INTO WordBookEntry (wordBookId, wordId, entryOrder, addedAt)
SELECT :newBookId, wordId, entryOrder, :now FROM WordBookEntry WHERE wordBookId = :sourceBookId
  AND wordId IN (SELECT wordId FROM SessionWord WHERE sessionId = :sessionId AND status != 'MASTERED');
```

## 4. 事务规则（必须原子，违反即 bug）

| 场景 | 事务内容 |
|---|---|
| `MasteryMarker`（"会了"） | `UPDATE SessionWord → MASTERED` + `INSERT WordMastery`（同一事务，LEARNING_ENGINE_SPEC §6） |
| `WordBookDeriver`（退出分支 B 派生） | `insertDerivedWordBook`（type=DERIVED + parentWordBookId + sourceSessionId）+ Q5 复制 entries（保留 entryOrder/pendingTranslation）+ 复制 WordBookEntryDefinition（含 includeExamples）——全部或全无；**不复制** WordMastery（D4） |
| 收藏保存（FR-5） | Word upsert（首次）+ N×`WordBookEntry` + M×`WordBookEntryDefinition` |
| 导入（FR-14） | 分块事务：每块（如 500 行）成功才提交；取消/失败 → 已提交块保留**或**整体回滚（见 IMPORT_SPEC §6：v1 整体回滚） |
| 勋章授予 | `INSERT OR IGNORE` + 唯一索引兜底（幂等，FR-13） |
| 会话创建 | `INSERT LearningSession` + N×`INSERT SessionWord` |

## 5. 迁移与版本

- Schema v1 为基线；此后任何 DDL 变更 = **新 schema 版本 + `.sqm` 迁移文件 + 迁移测试**（SQLDelight `MigrationTest`，JVM 全版本链验证）；
- D1–D4 决策字段（`WordBook.type / parentWordBookId / sourceSessionId`）**直接纳入 schema v1 基线**（Phase 1 落地时一并生成，无需迁移）；
- 禁止修改历史 `.sqm`；禁止无迁移直接改 v1+ 的建表语句；
- Phase 1 生成 schema 时锁定版本 1 并在 TEST_PLAN TC-DB-06 建立迁移测试基线。

## 6. 需求映射检查表（FR → 表/列）

| 需求 | 支撑 |
|---|---|
| FR-1 大小写不敏感查词 | `Word.normalizedText` 唯一索引 |
| FR-2 POS 分组连续 + (POSOrder, defOrder) 排序 | `DefinitionEntry.partOfSpeechOrder/definitionOrder` + Q1 索引 |
| FR-2 MeaningEN/CN 不可拆分 | 同表两列（天然原子） |
| FR-3 例句单元原子（句子+译文+音频+来源） | `Example` 单表 + `sourceType/sourceRef/licenseNote` |
| FR-4 生词本增删改 | `WordBook`；删除 = 关系级联，词不动 |
| FR-5 一词多本 + 释义选择 + 例句开关 | `WordBookEntry(wordBookId,wordId)` 唯一 + `WordBookEntryDefinition.includeExamples` |
| FR-6 队列=未掌握 + entryOrder + 分组固化 | Q2 + `SessionWord.groupIndex/orderInGroup` |
| FR-7 "会了"幂等 | `WordMastery` 复合主键（重复 INSERT 冲突即忽略）；`SessionWord` PK |
| FR-8 完成检测 | Q3 |
| FR-9 派生本（复用不复制） | Q5 只插关系行；`type='DERIVED'` + `parentWordBookId` + `sourceSessionId` 血缘与来源；不复制 WordMastery（D4） |
| FR-11 崩溃恢复 | `LearningSession/SessionWord` 持久化 |
| FR-13 勋章幂等 + 快照 | `Achievement` 唯一索引 + `payloadJson` 快照 |
| FR-14 导入译文暂存 / 词复用 / 去重 | `WordBookEntry.pendingTranslation` + `Word.normalizedText` 唯一复用 |
| FR-15 设置 | `AppSetting` KV（结构化 JSON） |

---

| 版本 | 日期 | 变更 |
|---|---|---|
| 1.0 | 2026-09-01 | Phase 0 初版 |
| 1.1 | 2026-09-01 | 冻结 D1–D4：WordBook 增 type（ORIGINAL/DERIVED，CHECK 约束）、parentWordBookId（RESTRICT）、sourceSessionId；派生不复制 WordMastery |
