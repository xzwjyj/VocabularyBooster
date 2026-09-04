# PHASE 3 验收报告 — 学习引擎（headless）

> 日期：2026-09-04 ｜ 状态：**最终验收 PASS（用户已批准）**
> 上游：ROADMAP Phase 3 ｜ 前置基线：commit `47b3f80`（Phase 2）
> 结论：TC-LE-01…11 全绿（TEST_PLAN §4.3 批准全集，覆盖 LEARNING_ENGINE_SPEC §10 十一条边界）；Phase 2 遗留 TC-DB-07 DB 级断言补齐（3 用例）；100 词 × 10 组全流程（掌握 → 组推进 → 派生 → 完成）真实 SQLite 集成通过。测试门禁：jvmTest 154 / commonTest(Android) 98 / app 单测 1，0 失败；双 detekt、平台边界门禁、assembleDebug、`git diff --check` 全绿。**schema 恒 v2：零 DDL、零迁移**；全部业务规则位于 shared/commonMain（铁律 1/2 门禁绿）。

---

## 1. 本阶段范围（严格对齐批准书）

**做了**：学习引擎全部纯逻辑（LEARNING_ENGINE_SPEC v1.3）——队列构建、分组固化、掌握标记、完成判定、§5 nextWord 推进、§9 崩溃恢复、§8 退出三分支（A/B/C）、派生生词本（含 2026-09-04 交集语义裁决）；会话持久化（LearningSession / SessionWord / 播放位 PLAYING 持久化）；学习设置端口（groupSize + 六播放开关）；四层测试（纯函数 / 引擎 Fake / 真实 SQLite 集成 / 约束级）。

**未做（按批准书/规格排除）**：Dictionary API、Ktor、TTS、SpeechRecognizer、Media3、正式学习 UI（Phase 4/5）、EventBus、引擎对外 StateFlow、勋章授予逻辑（Phase 6）、iOS。

**偏差（如实记录）**：ROADMAP Phase 3 原文提及「临时调试界面驱动引擎（仅验证用）」——**未交付**。本阶段以 headless 方式实施，引擎行为全部由 commonTest（Fake 端口）+ jvmTest（真实 SQLite，含 close/reopen 崩溃模拟）验证，未新增任何 app 层代码（app 层零改动，见 §7 scope audit）。正式学习 UI 在 Phase 4。

## 2. 规格演进（编码前先行，含版本记录）

| 文档 | 版本 | 变更 |
|---|---|---|
| LEARNING_ENGINE_SPEC | v1.2 → **v1.3** | §8 步骤 3 派生复制集合 = **effectiveRemaining**（当前母本 WordBookEntry ∩ SessionWord(status != MASTERED)）；mid-session 母本编辑三边界裁决（Case 1/2/3） |
| DOMAIN_MODEL | v1.3 → **v1.4** | §2.4 会话快照以母本现存 entry 为界；§10 派生语义同步 |
| DATABASE_SCHEMA | v1.4 → **v1.5** | Q5 注释 + 新查询 Q5d `countEffectiveRemaining`；§4 事务行。**schema 保持 v2、无 DDL、无迁移** |
| TEST_PLAN | v1.3 → **v1.4** | TC-LE-07 补三边界（Case 3 交集空 → 不建空 DERIVED 本）；命名断言升级为注入时区精确格式 + 重名 -2/-3 两级 |

**2026-09-04 交集语义裁决（Step 5D，冻结）**：
- Case 1：会话开始后母本新增未掌握词（不在 SessionWord）→ 不复制，留母本待下次会话（Q3 计入）；
- Case 2：会话中词被移出母本（SessionWord 行尚在）→ 不复制（entryOrder/pendingTranslation 恒取自母本行，禁用 SessionWord 组序）；
- Case 3：交集为空（MASTERED>0 且 REMAINING>0 的字面分支 B）→ **不创建空 DERIVED 本**：`deriveWordBook` 事务首步 Q5d 守卫返回 null，会话仍 ABANDONED、endedAt 正常写入、derivedWordBookId=null。

## 3. 架构与交付（全部位于 shared，平台无关）

```
shared/src/commonMain/kotlin/com/vocabularybooster/
  learning/                      # 引擎纯逻辑（编排 + 纯函数组件）
    LearningEngine.kt            # 端口 + 结果类型（StartResult/ResumeResult/AdvanceResult/ExitResult）
    DefaultLearningEngine.kt     # 编排：sessionStateMutex 串行化；建队→分组→物化→推进→终态
    StudyQueueBuilder.kt         # Q2 队列（EMPTY_BOOK/ALL_MASTERED 拒绝原因；防御性拷贝）
    GroupSplitter.kt             # groupIndex/orderInGroup 固化（require groupSize>=1）
    MasteryMarker.kt             # "会了"唯一入口（VOICE/BUTTON 等价；铁律 5）
    CompletionDetector.kt        # 三级完成判定（词/组/书；书完成=Q3==0）
    WordBookDeriver.kt           # 派生裁决 + 命名（注入 Clock/TimeZone；-2/-3 重名）
  domain/model/
    LearningSession.kt           # LearningSession/SessionWord/SessionStatus/SessionWordStatus/SessionSnapshot
    StudyQueue.kt                # StudyQueueSnapshot/SessionWordPlacement
    PlaybackToggles.kt           # 六播放开关（默认全开）
  domain/repository/
    LearningSessionRepository.kt # 会话端口（15 方法：物化/播放位/掌握/终态/恢复完整性）
    LearningSettingsRepository.kt# 设置端口（groupSize + toggles；DEFAULT_GROUP_SIZE=10）
  data/
    SqlDelightLearningSessionRepository.kt  # ACTIVE 唯一性事务检查；markMastered 条件 UPDATE
                                             #   + SELECT changes() 幂等；setPlayingWord 单事务位迁移
    SqlDelightLearningSettingsRepository.kt # AppSetting KV 读
  di/LearningModule.kt           # sharedLearningModule（Koin）
```

修改的既有文件：`SqlDelightWordBookRepository.kt`（+`deriveWordBook` 单事务四步：母本守卫→Q5d 守卫→插入 DERIVED→复制关系行，永不复制 Word、不继承掌握）、`WordBookRepository.kt`（+派生三方法）、`RepositoryExceptions.kt`、`Mappers.kt`、`DataModule.kt`（种子导入衔接）。

`.sq` 变更（**全部 query-only**，零 DDL/索引/FK 变更）：
- `LearningSession.sq`：+`selectActiveSession`
- `SessionWord.sq`：+`markMastered`（条件 UPDATE）、`selectChanges`、`clearPlaying`/`setPlaying`、`deleteSessionWordsNotInBook`
- `Queries.sq`：+`countEffectiveRemaining`（Q5d）
- `WordBook.sq`：+`countBooksNamed`

要点：
- **播放位 = SessionWord 持久化 PLAYING 状态推导**（零引擎内存位）：advance 从持久化 PLAYING 起算，重启/换引擎实例续推（测试锁定）。
- **terminate 与 derive 保持两个独立事务**（DATABASE_SCHEMA §4；退出终态不受派生守卫影响——Case 3 下 endedAt 照写）。
- ACTIVE 会话唯一性 = 引擎/仓储不变量（`createSession` 事务内检查），未加 DB partial unique index。
- `AdvanceResult.NextWord.completedGroupIndex` 为稳定推导的位置信息（前一组全掌握即携带、重复 advance 重复携带），非一次性事件。
- 引擎时间全部走注入 `Clock`（铁律 10）；协程 suspend 止于 Repository。

## 4. 分步实施记录（每步验收后推进）

| Step | 内容 | 验收要点 |
|---|---|---|
| 1 | LearningSessionRepository（会话持久化地基） | 物化原子性、ACTIVE 唯一、close/reopen 持久 |
| 2 | StudyQueueBuilder + GroupSplitter 纯函数 | Q2 序、拒绝原因、边界规模、确定性 |
| 3 | MasteryMarker + CompletionDetector | `markSessionWordMastered` 单事务（SessionWord+WordMastery）、changes() 幂等 |
| 4 | DefaultLearningEngine 编排（start/resume/markMastered）+ 设置端口 | 四拒绝原因有序、resume 精确还原不重算、零 .sq 变更 |
| 5A | advance() §5 nextWord + PLAYING 播放位 | 跳过已掌握回绕、组推进边界、终态幂等只读 |
| 5B | TC-LE-10 恢复完整性 `recoverSessionIntegrity` | 书删→安全 ABANDON+BOOK_DELETED；词移除→剔除悬挂行不重编号、不触碰 WordMastery |
| 5C | exitSession §8 退出三分支终态 | endedAt 不可变、重复退出幂等、终态会话不可复活 |
| 5D | WordBookDeriver + 交集语义裁决 | 见 §2；37/100→63 词派生主干 + 三边界 + 命名精确断言 |

## 5. 验收矩阵（TC → 生产实现 → 测试；全部 PASS）

| TC | 生产实现 | 测试（代表引用） |
|---|---|---|
| TC-LE-01 队列构建 + ACTIVE 唯一 | StudyQueueBuilder；DefaultLearningEngine.startSessionLocked；createSession 事务检查 | StudyQueueBuilderTest 7 用例；DefaultLearningEngineTest（emptyBook/allMastered/existingActiveSession/hundredWords）；LearningSessionRepositoryTest.createSessionRejectsSecondActive... |
| TC-LE-02 分组固化 + 中途改设置不影响 | GroupSplitter；groupSize 建会话时读一次并持久化 | GroupSplitterTest 10 用例；groupSizeComesFromSettingsNotHardcoded；resumeDoesNotCreateSessionOrRecomputePlacements；IntegrationTest.groupSizeSettingValueDrivesGrouping |
| TC-LE-03 组内循环跳过回绕 | DefaultLearningEngine.advanceLocked；setPlayingWord 单事务 | LearningEngineAdvanceTest（mixedStatus/wrapAround/firstAdvance） |
| TC-LE-04 "会了"幂等/开播即会了/导入词掌握侧 | MasteryMarker；markSessionWordMastered 条件 UPDATE+changes() | MasteryMarkerTest 7 用例；engineNeverCallsUnguardedStatusUpdate；RepositoryTest（幂等/回滚） |
| TC-LE-05 组推进 | advanceLocked | LearningEngineAdvanceTest（20/21/11 词边界三用例） |
| TC-LE-06 书完成（COMPLETED） | CompletionDetector（Q3==0）；completeSessionLocked | CompletionDetectorTest 9 用例；AdvanceTest 单词/十词完成；IntegrationTest.bookCompletionWritesTerminalState... |
| TC-LE-07 退出分支 B（37/100→63 + 命名 + 血缘 + 逐 ID 选择 + 三边界） | exitSessionLocked（两独立事务）；WordBookDeriver；deriveWordBook（Q5d 守卫） | LearningEngineDerivationIntegrationTest 11 用例（含 Case 1/2/3 各自真实路径用例）；WordBookDeriverTest |
| TC-LE-08 退出分支 A（零掌握无新本） | WordBookDeriver 零掌握→null | exitZeroMasteryCreatesNoDerivedBook；exitWithZeroMasteryAbandonsPreservingAllHistory；ExitIntegrationTest.exitAbandonedAcrossRestart... |
| TC-LE-09 崩溃恢复 | resumeSession 从 DB 重读；PLAYING 持久化 | crashRestartResumePreservesEntireSnapshot；advancePositionSurvivesCrashRestart...；recoverySnapshotSurvivesCloseAndReopen |
| TC-LE-10 恢复异常两分支 | recoverSessionIntegrity（单事务） | LearningEngineRecoveryTest 11 用例；RecoveryIntegrationTest 4 用例（真实移词路径） |
| TC-LE-11 退出分支 C（Q3 实时裁决） | exitSessionLocked 实时读 Q3 → COMPLETED 不派生 | exitAfterMasteringAllWordsCompletesWithoutAdvanceDetection；exitOneWordShort...（负边界）；ExitIntegrationTest.exitCompletedBranchC... |
| TC-DB-07 type CHECK/RESTRICT/血缘（DB 级，本轮补齐） | WordBook.sq v2 DDL（未改动）：CHECK(type)、FK ON DELETE RESTRICT | **WordBookTypeAndLineageConstraintTest** 3 用例（详见 §6）；血缘必填=应用层由 deriveWordBook 恒写（deriveThirtySevenOfHundred 断言）；仓储守卫 deleteGuardsRejectMedalAndDerivedParent |

100 词 × 10 组出口项：`hundredWordsFormExactlyTenFullGroups`（引擎级）+ `hundredWordsTenGroupsExactCoverage`（分组级）+ `deriveThirtySevenOfHundredMirrorsTCLE07`（100 词真库：37 掌握 → 派生 63 → 选择逐 ID 一致）。

## 6. 测试与门禁记录（2026-09-04 最终验收实测）

| Gate | 结果 |
|---|---|
| `:shared:jvmTest` | ✅ 154 tests, 0 failed |
| `:shared:testDebugUnitTest`（--rerun） | ✅ 98 tests, 0 failed |
| `:app:testDebugUnitTest`（--rerun） | ✅ 1 test（AppModuleSmokeTest），0 failed |
| `:shared:detekt`（--rerun）/ `:app:detekt` | ✅ |
| `:shared:checkPlatformBoundaries` | ✅ |
| `:app:assembleDebug` | ✅ |
| `git diff --check` | ✅ 干净（仅 autocrlf 提示） |

本阶段新增测试：commonTest `learning/` 9 个测试类 + Fakes（92 用例，Fake 端口手写、不用 mock 框架）；jvmTest 7 个文件 32 用例（会话仓储、五组引擎集成、TC-DB-07 约束）。

**TC-DB-07 补齐说明（唯一为验收新增的测试文件）**：`WordBookTypeAndLineageConstraintTest.kt`——① type='SHARED' 被 CHECK 拒（合法值对照组先行）；② 删有派生子本的母本被 FK RESTRICT 拒（含归因：清除会话行/血缘链后仍拒 → 拒绝独立来自派生子本；先删子本后母本可删）；③ 悬挂 parentWordBookId 被 FK 拒。JDBC 侧以连接属性 `foreign_keys=true` 等价开启 FK（xerial 逐连接消费，多连接语义下贴近生产 Android 驱动 `PRAGMA foreign_keys=ON`，DATABASE_SCHEMA §1）。

## 7. Schema / 迁移 / Scope 审计

- `VocabularyDatabase.Schema.version == 2L`（MigrationV1ToV2Test + TC-DB-07 测试双重断言）；迁移目录仍仅 `1.sqm`，未新增未改动。
- 4 个 `.sq` 改动全部 query-only（§3 清单）；零 DDL、零索引、零 FK 变更。
- app 层零改动（无 UI、无 ViewModel、无 actual 变更）；shared 改动全部位于 commonMain，detekt 平台 import 禁令 + checkPlatformBoundaries 绿。
- 无 EventBus / StateFlow 暴露 / Achievement 逻辑 / Dictionary API / Ktor / TTS / 语音识别 / Phase 4 代码。

## 8. 关键问题与陷阱记录（延续 PHASE_2_REPORT §7）

1. **`last_insert_rowid()` 跨连接返回 0**（JDBC 文件驱动每语句新建连接，SQLDelight ThreadedConnectionManager）——插入+读 rowid 必须包同一事务。Phase 3 第三次踩到（TC-DB-07 测试种子），已在测试内以 `db.transaction {}` 收口。
2. **SQLDelight 2.0.2 UPDATE 生成函数返回 Unit 丢弃行数**——受影响行数须同事务内 `SELECT changes();` 读取（markMastered 幂等判定）。
3. **Kotlin 负向 `is` 检查不做 sealed 互补分支智能转换**——用穷尽 `when`。
4. **JDBC 测试驱动默认 FK 关闭**——本阶段以连接属性等价开启后补齐 DB 级约束断言（§6）。
5. **LearningSession.wordBookId FK RESTRICT**：FK 开启时书删不掉；JDBC 测试驱动 FK 关 → 书删悬挂态可达，恢复完整性集成测试即以此模拟（§10-8 分支）。

## 9. 已知延期（均为规格明示）与观察项

延期：
1. TC-LE-06/11 事件次序中的「停止播放」段 → Phase 4（AUDIO_ENGINE_SPEC）；
2. 勋章写入（BOOK_COMPLETED）→ Phase 6（Achievement 表与幂等插入已在 schema 就位，引擎未授予）；
3. TC-LE-10「提示」的 UI 呈现 → Phase 4（引擎已返回 `ResumeResult.Reason.BOOK_DELETED`）；
4. §10-3 导入词（无释义）播放分段侧 → Phase 4 TC-AE-01/02（TEST_PLAN v1.4 明示）；
5. iOS targets → 需 macOS 宿主。

观察项（非阻塞，已登记、留待独立清理）：commonTest `LearningEngineExitTest.exitWithRemainingBookAdditionsAbandons`（`LearningEngineExitTest.kt:165-181`）以未建模交集守卫的 Fake 断言「分支 B 字面 → 派生」（注释停留在 5D deferred 措辞）。引擎层将交集守卫委托给 `repository.deriveWordBook` 属 LE spec §8 设计；冻结的 Case 3 语义已由 jvmTest 两个真实交集空集成测试 + `WordBookDeriverTest.derivePropagatesEmptyIntersectionAsNull` 锁定。**不改生产语义、不改该测试**（用户裁决：留待后续独立清理）。

## 10. 结论

**PASS**。Phase 3 出口条件全部满足：TC-LE-01…11 全绿、100 词 ×10 组全流程通过、TC-DB-07 补齐；工程门禁全绿；schema 恒 v2 零迁移；业务规则全部下沉 shared。FR-6/FR-8/FR-9 逻辑层验收（播放/语音/勋章的接入段按规格归 Phase 4/5/6）。进入 Phase 4（播放引擎）需用户另行批准。
