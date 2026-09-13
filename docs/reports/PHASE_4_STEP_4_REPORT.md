# Phase 4 Step 4 报告 — 正式学习会话 UI 与播放接入

日期：2026-09-11 ｜ 状态：**PASS（待验收）** ｜ HEAD：`77baaf1`（未提交）

---

## 1. Preconditions

- 开始前核验：`git log -1` = `77baaf1 feat(phase3): complete learning engine`；工作区保留 Phase 4 Step 0–3 全部改动（未 reset / checkout / stash / commit / revert）。
- 结束前复验：HEAD 仍为 `77baaf1`，无任何新 commit；Step 0–3 文件未被重构（本 Step 触碰的已验收模块 = 0）。
- READ FIRST 完成：PROJECT_SPEC / DOMAIN_MODEL / DATABASE_SCHEMA / LEARNING_ENGINE_SPEC / AUDIO_ENGINE_SPEC / TEST_PLAN / ROADMAP + DefaultLearningEngine、四个仓储、PlaybackOrchestrator/PlaybackState、两端口契约、Android DI、既有导航与 Phase 2 UI flow。代码与文档无未决冲突，未自行改规格。

## 2. Scope audit

- 实现 = 指令 §3 允许项 A–Q 全部落在 app 层 + 两个 androidTest/uiTest 新文件；shared 零改动（本 Step 对 shared 目录的既有改动全部属于 Step 0–3，保持原样）。
- 禁区核查：未实现 SpeechRecognizer / CommandParser / RECORD_AUDIO / AchievementEngine / EventBus / TXT 导入 / 词典 API / 设置 UI / iOS；未改编排器状态机、SegmentBuilder、Media3AudioPlayer、TtsSpeechSynthesizer、AudioPlayer/SpeechSynthesizer 契约；未向 commonMain domain model 加字段。
- 过程中发现并修复 1 个**本 Step 自有代码的 bug**（见 §21），未触碰任何已验收模块。

## 3. Files changed（本 Step 增量）

新增：

| 文件 | 内容 |
|---|---|
| `app/src/main/kotlin/com/vocabularybooster/app/ui/LearningSessionScreen.kt` | 学习屏（纯渲染 + 意图转发；五控制 / 窗口倒计时 / Completed / 冲突与提示对话框 / BackHandler） |
| `app/src/main/kotlin/com/vocabularybooster/app/ui/LearningSessionViewModel.kt` | 会话接入 + PlaybackState→UI 投影 + 命令转发 + sessionOwned 门 |
| `app/src/test/kotlin/com/vocabularybooster/app/ui/LearningSessionViewModelTest.kt` | TC-AE-23：VM 单测 13 例 |
| `app/src/test/kotlin/com/vocabularybooster/app/ui/LearningSessionTestSupport.kt` | 手写 Fake 端口/引擎/设置（无 mock 框架） |
| `app/src/androidTest/kotlin/com/vocabularybooster/app/ui/LearningSessionUiSmokeTest.kt` | TC-AE-24：Smoke A–H（真实 App 全链路） |
| `app/src/androidTest/kotlin/com/vocabularybooster/app/ui/BookDeletedRecoveryUiSmokeTest.kt` | TC-AE-24：Smoke I（BOOK_DELETED 恢复流程） |

修改：

| 文件 | 内容 |
|---|---|
| `app/src/main/kotlin/com/vocabularybooster/app/MainActivity.kt` | 学习屏全屏覆盖层接线（`learningBookId` rememberSaveable；学习期间隐藏底部导航） |
| `app/src/main/kotlin/com/vocabularybooster/app/ui/WordBooksScreen.kt` | 本详情「开始学习」入口（`start_learning_button`） |
| `docs/TEST_PLAN.md` | v1.7：TC-AE-23 / TC-AE-24 |
| `docs/ROADMAP.md` | Step 4 状态 |

（`git status` 中其余改动均属 Step 0–3，本 Step 未触碰。）

## 4. UI architecture

```
LearningSessionScreen（Compose，零播放逻辑/零计时器）
  ↓ 意图（pause/resume/next/replay/exit/冲突裁决）
LearningSessionViewModel（StateFlow 投影 + 命令转发）
  ↓ 公开 API
PlaybackOrchestrator（唯一状态源；既有，未改）
  ↓ 既有端口
AudioPlayer（Media3）/ SpeechSynthesizer（TTS）——既有 actual，未改
```

- Screen 不 import 任何 playback 端口类型；`ui`/`conflictSessionId`/`notice`/`exitResult` 是仅有的四个渲染输入。
- 无第二套编排器 / 引擎 / 导航框架；学习屏为 MainActivity 既有覆盖层式导航的扩展。

## 5. ViewModel architecture

职责（指令 §6 白名单）：start/resume 接入、状态收集与 immutable 投影、UI action→编排器命令、生命周期（`onCleared → orchestrator.dispose()`）、冲突/BOOK_DELETED/一次性结果呈现。

关键机制：

- **幂等 start 守卫**：编排器传输态活跃（Playing/Paused/CommandWindow）时跳过——覆盖重组重放。
- **sessionOwned 门**（本 Step 修复，见 §21）：只有观察到过传输态之后才开始把编排器状态映射为 ui——应用级单例上一屏遗留的 Stopped/Completed 终态不被新屏误读为"本次退出"。
- **wordTextCache**：CommandWindow 态不携带词文本（PlaybackState 契约），显示延续用最近携带态的缓存；非第二状态源。
- **冲突三分支**：恢复（resumeSession）/ 放弃旧的并开始（resume→exit→start，全程编排器公开 API，期间 UI 抑制为 Loading）/ 取消。
- VM 不碰 AudioPlayer/SpeechSynthesizer；不裁决推进/掌握/完成。

## 6. PlaybackState → UI mapping

| PlaybackState | LearningUiState | 屏幕呈现 |
|---|---|---|
| Idle | Loading | 「正在准备学习…」 |
| Playing | Playing（词/组号/段标签/段号/offset/degraded） | 词头 + 「正在播放 · 段」+ 降级横幅（degraded） |
| Paused(error=null) | Paused | 「已暂停（第 N 段）」 |
| Paused(error=TTS_FAILED) | Error | 「语音合成失败，播放已暂停」（重试=继续） |
| CommandWindow | CommandWindow（remainingMs/totalMs） | 「命令窗口」+ 进度条 + 剩余秒 |
| Completed | Completed | 🎉 学习完成（权威=编排器，不重判） |
| Stopped | Stopped | 「已退出学习」→ LaunchedEffect onExit |

组号显示为 `groupIndex+1`（用户可见 1 起，LE spec §4）。

## 7. Command forwarding

五控制全部 `launchCommand { orchestrator.xxx() }` 转发（VM 单测 G–K 断言 Fake 端口可观测副作用）；Exit 经二次确认弹窗后调用，结果存 `exitResult`，UI 等 Stopped 后导航离开。Next 走 UI→VM→编排器，绝不直连引擎。

## 8. CommandWindow

直接消费编排器既有倒计时状态（100ms tick 的 remainingMs/totalMs），渲染进度条 + 剩余秒文本。VM 无自建计时器；UI 不裁决 advance、不调 `engine.advance()`；窗口结束后推进完全由编排器驱动循环完成（L1 纯倒计时，P4 无识别器）。

## 9. BOOK_DELETED

`resumeSession` 被拒（引擎 TC-LE-10 完整性检查）→ `LearningNotice.BookDeleted` → AlertDialog「生词本已删除」→ 确定 → consumeNotice + onExit 返回上一层。底层 ABANDON/删除语义零改动、零新增 DB 行为。

Smoke I（真实链路验证）：悬挂 ACTIVE 会话（书 X 已删，经 PRAGMA foreign_keys=OFF 直构——生产 UI 不可达态）+ 全新编排器栈（等价进程重建，后端全为 actual：真实引擎/文件库/Media3/TTS）→ 学书 Y → 冲突弹窗 →「恢复」→ BOOK_DELETED 提示 → 确定 → 返回上一层。✅ PASS

## 10. Completed

编排器 `PlaybackState.Completed` 是唯一完成信号；屏渲染完成页 +「返回生词本」。Smoke H 用确定性 fixture（真实仓储 `markSessionWordMastered` 预置掌握 → 窗口超时 → 引擎判 BookComplete）验证，UI 不重判"是否全掌握"。

## 11. Navigation

沿用 MainActivity 既有覆盖层导航：生词本 Tab → 本详情「开始学习」→ `learningBookId` 置位 → 学习屏全屏覆盖（底部导航隐藏）；Exit（二次确认）/ Completed 返回 / 冲突取消 / BOOK_DELETED 确定 → 清位回本详情。状态 `rememberSaveable`，进程重建后重进走冲突-恢复既有语义。未引入新导航框架。

## 12. Unit tests

- `:shared:jvmTest` **204/204**（Step 2 基线保持）
- `:shared:testDebugUnitTest` **98/98**
- `:app:testDebugUnitTest` **15/15**：LearningSessionViewModelTest 13（指令 §16 A–M 全覆盖 + Error 投影 + 冲突「放弃旧的」公开 API 序列）+ TtsLocalesTest 1 + AppModuleSmokeTest 1
- VM 单测形态：真实 PlaybackOrchestrator + 手写 Fake 端口/引擎/设置（无 mock 框架）；未修改任何 Step 2 状态机测试。

## 13. Instrumentation tests（TC-AE-24）

- `LearningSessionUiSmokeTest`（Smoke A–H）：MainActivity 真实 Koin 图 / 文件库 / Media3 / TTS，无 fake playback bypass、无 emulator 专用逻辑、无 sleep 伪完成；等待全部 `waitUntil` 超时封顶轮询；测试间隔离 = 既有 exit（Main.immediate 上执行，两端口 stop 均主线程限定）+ 孤儿 ACTIVE 会话清扫 + 位置清空 + 种子确定性兜底。
- `BookDeletedRecoveryUiSmokeTest`（Smoke I）：见 §9。
- 失败诊断增强：关键等待点包 `catch (Throwable) { diag(...) }`（`ComposeTimeoutException` 继承 `AssertionError`，`catch (Exception)` 捕不到——本项目实测教训），diag 转储编排器状态 + 屏幕各候选内容；仅失败路径执行，不伪装结果。

## 14. Emulator environment

- 本机 Windows 10 CLI（无 Android Studio）；AVD `vb_phase1`（API 35 x86_64），本次会话模拟器曾掉线（adb daemon 重启后 No connected devices）→ `-no-snapshot-load` 冷启后恢复，`adb devices` = `emulator-5554 device`。
- 结果目录曾被 Synology 同步锁定（"另一个程序正在使用此文件"）→ 运行前清理 `androidTest-results` 内部文件（既有环境注意事项，非本 Step 代码问题）。

## 15. Smoke A–I 结果

| # | 场景 | 结果 |
|---|---|---|
| A | 书 → 开始学习 → 学习屏打开，首词可见 | ✅ Automated PASS |
| B | 占位音频真实经 Media3 播完整段 → 窗口开启（4s 音 + 300ms guard 后） | ✅ Automated PASS |
| C | Pause → 「已暂停」→ Resume → 回「正在播放」（ADR-09 段重读） | ✅ Automated PASS |
| D | 缺音频 → Media3 真实 prepare 失败（RawResourceDataSourceException）→ TTS 兜底 + 降级横幅，仍在学习流 | ✅ Automated PASS |
| E | 窗口倒计时可见递减（3s 窗口两次采样可分） | ✅ Automated PASS |
| F | Next → 编排器语义推进到第二词（boost→abandon） | ✅ Automated PASS |
| G | Exit 二次确认后回本详情 | ✅ Automated PASS |
| H | Completed 渲染（预置掌握 + 窗口超时 → 引擎 BookComplete） | ✅ Automated PASS |
| I | BOOK_DELETED 恢复流程（冲突→恢复→提示→返回） | ✅ Automated PASS |

（A–H 一类 8/8 + I 一类 1/1；detekt 整改重构 Screen 后**整体复跑 9/9 仍全绿**。）

## 16. Step 2 regression

`:shared:jvmTest` **204/204、0 失败 0 跳过** —— 204 基线保持。编排器/SegmentBuilder/仓储语义零改动（本 Step 未触碰 shared 代码）。

## 17. Step 3 regression

6 个既有 androidTest 类全量复跑：**23 tests finished，0 failed，1 skipped**——
- AudioFocusInstrumentedTest / Media3AudioPlayerInstrumentedTest / PlaybackSmokeInstrumentedTest / TtsSpeechSynthesizerInstrumentedTest / LaunchSmokeTest / Phase2UiFlowTest 全绿；
- 唯一 skipped = `TtsSpeechSynthesizerInstrumentedTest.initializationFailure_contractIsException`（**Step 3 既有诚实跳过**：装有健康 Google TTS 引擎的模拟器上无法强制初始化失败；非本 Step 引入）。
- Media3/TTS 集成未因 UI 接入回归。

## 18. Schema audit

Schema 保持 **v2**：无 DDL / migration / 新表 / 新列 / 索引 / FK / CHECK / SessionWord 变更。`Queries.sq` 等 shared 数据层改动均属 Step 0–3。Smoke D 的例句音频篡改是测试 fixture 的**数据级** UPDATE（并行连接，用后还原），非 schema 变更。

## 19. Architecture boundary audit

- `:shared:checkPlatformBoundaries` ✅ + `:shared:detekt` ✅：commonMain 无 `android.*` / `java.*` import。
- Screen/VM 无 `androidx.media3` / `TextToSpeech` / `AudioManager` / `Looper` 等平台符号；不直接操作 AudioPlayer/SpeechSynthesizer（唯一例外 = Smoke I 测试栈装配 actual，属测试代码）。
- 正常路径恒为 UI → ViewModel → PlaybackOrchestrator → 既有端口；引擎仍只由编排器驱动。

## 20. All gates

| 门禁 | 结果 |
|---|---|
| `:shared:jvmTest` | ✅ 204/204 |
| `:shared:testDebugUnitTest` | ✅ 98/98 |
| `:app:testDebugUnitTest` | ✅ 15/15 |
| `:shared:detekt` | ✅ |
| `:app:detekt` | ✅（本轮新代码曾报 10 告警——VM 宽捕获×4/函数数、Screen 复杂度/魔数、TestSupport×3；已按项目惯例修复或带理由 suppress，复检 0 issue） |
| `:shared:checkPlatformBoundaries` | ✅ |
| `:app:assembleDebug` | ✅ |
| `git diff --check` | ✅ 干净（仅 CRLF 提示性 warning） |
| `adb devices` | ✅ emulator-5554 |
| Smoke A–I | ✅ 9/9 |
| Step 3 androidTest 回归 | ✅ 23/0 failed/1 既有 skip |

## 21. Known limitations & 本 Step 发现并修复的 bug

**修复的 bug（本 Step 自有 VM/Screen 代码，非已验收模块）**：应用级单例编排器上一屏退出遗留的 `Stopped` 终态，被下一次进入学习屏的新 VM collector 立即映射 → `LaunchedEffect(Stopped) → onExit()` 闪退回本详情，而 `start()` 的新会话照常在编排器自有 scope 后台播放（第二次进学习屏必现）。定位证据：超时 diag 转储（`state=Playing word=null backAtDetail=true`）+ logcat 无按键事件且会话完整播放 5 周期 + JUnit 实际执行序（B 的 UI exit 留下 Stopped，D 起逐个闪退）。修复 = `sessionOwned` 门（见 §5）。修复后 Smoke A–I 全绿。

**Known limitations**：

1. TC-AE-16 编排器级焦点丢失广播未接入（本 Step 范围禁改 Media3AudioPlayer/端口契约；焦点丢失仍只表现为 progressMs 冻结 + 静音，UI 无横幅）——TEST_PLAN v1.7 已注记，维持 Known Limitation。
2. Smoke I 以「全新编排器栈 + 真实引擎/actual 端口 + PRAGMA 直构悬挂态」等价进程重建（同进程单例无法真正重建进程）；生产不可达态的构造仅存在于测试。
3. Smoke D 依赖并行 DB 连接的 UPDATE 对 App 连接可见（实测成立；SQLite 同机文件库行为）。
4. `ComposeTimeoutException` 是 `Error` 非 `Exception`——测试诊断包裹必须 `catch (Throwable)`（已作为永久教训写入测试注释与 diag 实现）。
5. 模拟器曾掉线需冷启恢复；androidTest-results 目录可能被 Synology 同步锁定（环境项，报告 §14）。

## 22. Git status

- HEAD：`77baaf1`（未变，**零 commit**）。
- `git diff --check`：干净。
- 工作区 = Step 0–4 全部改动叠加（17 个已跟踪文件修改 + Step 0–4 新增文件），等用户验收后统一处置。

---

## 结论分类

- **Automated PASS**：§12 三层单测（204+98+15）、§15 Smoke A–I（9/9）、§17 Step 3 回归（23/0/1 既有 skip）、§20 全部门禁（detekt/边界/打包/git）。
- **Manual PASS**：无（本 Step 全部验证均自动化于真实模拟器链路）。
- **Honest Skip**：`TtsSpeechSynthesizerInstrumentedTest.initializationFailure_contractIsException`（Step 3 既有；健康 TTS 引擎上无法强制初始化失败）。
- **Known Risk**：§21 五项（焦点广播缺口 / Smoke I 等价重建 / Smoke D 并行连接可见性 / Error 型超时异常 / 模拟器环境项）。

---

Phase 4 Step 4
STATUS: **PASS**

STOP — WAITING FOR PHASE 4 STEP 4 APPROVAL
