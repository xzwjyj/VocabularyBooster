# Phase 5 Step 1 报告 — Speech Command / 「会了」Interaction

日期：2026-09-12 ｜ 状态：**PASS（待验收）** ｜ HEAD：`77baaf1`（未提交）

> 阶段归属：按用户 FINAL DECISIONS（D1–D5，2026-09-12），本工作属 **Phase 5 Step 1**（原指令
> "Phase 4 Step 5" 更名）；未创建 PHASE_4_STEP_5_REPORT，未改 Phase 4 L1 规格、未改 ROADMAP。

---

## 1. Preconditions

- 开始前核验：`git log -1` = `77baaf1 feat(phase3): complete learning engine`；工作区保留 Phase 4 Step 0–4 全部改动（未 reset / checkout / stash / commit / revert）。
- 结束前复验：HEAD 仍为 `77baaf1`，零 commit；Step 0–4 代码未被重构（对 Step 3 actual 的修改 = 0；对 Step 2 已验收测试仅 **2 处装配点各 +3 行**（D2 批准的构造器增参所致，注入默认不可用的 Fake = P4 形态，断言与用例体零改动，见 §3/§12）。
- READ FIRST 完成：PROJECT_SPEC（FR-7/FR-12/NFR-4/NFR-8）、DOMAIN_MODEL、LEARNING_ENGINE_SPEC（§6 唯一掌握入口）、AUDIO_ENGINE_SPEC（§3/§7/§8/§9 + L1–L7）、TEST_PLAN、ROADMAP + Phase 1 SpeechCommandRecognizer 端口、PlaybackOrchestrator/PlaybackState、CommandParser 草案、Android DI、Step 4 VM/Screen。无未决冲突，未自行改需求。

## 2. Scope audit（指令 §3 对照）

- 允许项 A–N 全部落地：Android SpeechRecognizer actual、CommandWindow 升级（监听/超时/降级三终局）、「会了」按钮同路径、RECORD_AUDIO UI 持有授权流、VM 转发、三层测试、文档同步。
- 禁区核查（零违反）：无 AchievementEngine / EventBus / schema 变更（恒 v2）/ iOS recognizer / 后台监听 / 自动「会了」/ 自动弹权限 / 第二套识别端口 / 第二套掌握路径 / 学习引擎或 SessionWord 改动 / 新 @Suppress 滥用（仅 actual 2 组带 OEM 理由）。
- 复用核查：SpeechCommandRecognizer = Phase 1 既有端口（签名零改动，D3）；PlaybackOrchestrator 仍是唯一播放状态机；LearningEngine 仍是唯一推进/掌握权威；CommandParser 是唯一命令解释器。

## 3. Files changed（本 Step 增量）

新增：

| 文件 | 内容 |
|---|---|
| `shared/src/commonMain/kotlin/com/vocabularybooster/speech/CommandParser.kt` | 命令解析器（归一化 + 精确等值；D4 别名常量；V1_ENABLED={MASTERED}） |
| `shared/src/androidMain/kotlin/com/vocabularybooster/platform/AndroidSpeechCommandRecognizer.kt` | SpeechRecognizer actual（D3 zh-CN / D5 错误映射 / 生命周期纪律） |
| `shared/src/jvmTest/kotlin/com/vocabularybooster/speech/CommandParserTest.kt` | TC-AE-10：8 例（别名/大小写/全角/标点/繁简不匹配/空白） |
| `shared/src/jvmTest/kotlin/com/vocabularybooster/playback/PlaybackOrchestratorSpeechTest.kt` | TC-AE-04/05/06 + §11：13 例（真实 CommandParser + Fake 识别器） |
| `app/src/androidTest/kotlin/com/vocabularybooster/app/audio/AndroidSpeechCommandRecognizerInstrumentedTest.kt` | actual 平台契约 4 例（探测/静默 Timeout/取消释放/并发守卫） |
| `app/src/androidTest/kotlin/com/vocabularybooster/app/audio/PermissionDeniedSpeechInstrumentedTest.kt` | 权限拒绝映射 1 例（单类隔离运行 = 新装拒绝态，见 §13） |

修改：

| 文件 | 本 Step 增量 |
|---|---|
| `shared/src/commonMain/kotlin/com/vocabularybooster/playback/PlaybackOrchestrator.kt` | +recognizer/commandParser 依赖；CommandWindow 双形态（监听循环 + `listenForMasteredCommand`）；`masterCurrentWord()` 按钮入口（仅窗口态）；`windowCommandConsumed` 重复防护 |
| `shared/src/commonMain/kotlin/com/vocabularybooster/playback/PlaybackState.kt` | `CommandWindow.listening` 字段（双形态如实广播） |
| `shared/src/jvmTest/kotlin/com/vocabularybooster/playback/PlaybackFakes.kt` | +FakeSpeechCommandRecognizer（脚本化 + 调用记录） |
| `shared/src/jvmTest/.../PlaybackOrchestratorStateTest.kt` / `PlaybackOrchestratorRestartTest.kt` | 仅装配点 +3 行/文件（新构造参数注入默认不可用 Fake = P4 形态；**断言与用例体零改动**） |
| `app/src/main/kotlin/com/vocabularybooster/app/di/AppModule.kt` | +`AndroidSpeechCommandRecognizer` 单例 + `CommandParser` 单例；编排器装配增参 |
| `app/src/main/AndroidManifest.xml` | +`RECORD_AUDIO` 声明（带「仅 CommandWindow 内识别、绝无后台监听」注记） |
| `app/src/main/kotlin/com/vocabularybooster/app/ui/LearningSessionViewModel.kt` | +`masterCurrentWord()` 转发；`LearningUiState.CommandWindow.listening` 透传 |
| `app/src/main/kotlin/com/vocabularybooster/app/ui/LearningSessionScreen.kt` | 窗口语音形态指示（listening 双文案，不伪造录音态）；「会了」按钮（仅窗口分支渲染）；麦克风非阻断授权条（用户点击才发起） |
| `app/src/test/kotlin/com/vocabularybooster/app/ui/LearningSessionTestSupport.kt` | FakeLearningEngine +`markMasteredCalls` 记录；+app 侧 FakeSpeechCommandRecognizer |
| `app/src/test/kotlin/com/vocabularybooster/app/ui/LearningSessionViewModelTest.kt` | +5 例语音路径 VM 测试（15→20） |
| `app/src/androidTest/.../PlaybackSmokeInstrumentedTest.kt` | setUp pm grant + 编排器装配增参（真实 listen 路径回归） |
| `app/src/androidTest/.../BookDeletedRecoveryUiSmokeTest.kt` | 编排器装配增参（未授权 = 窗口降级，语义不受影响） |
| `app/src/androidTest/.../LearningSessionUiSmokeTest.kt` | +Smoke I（会了按钮推进）/ J（拒绝态降级 + 横幅）；矩阵注记更新 |
| `docs/AUDIO_ENGINE_SPEC.md` | v1.2（§3 按钮行 + 双形态；§7 D4/精确等值；§8 actual 契约细化 + 位置澄清；§9 两行 D5；新增 §11；版本记录） |
| `docs/TEST_PLAN.md` | v1.8（TC-AE-04/05/06/10 交付标注；TC-AE-12 闭合；+TC-AE-25/26；TC-AE-24 字母表修正；FR-7/FR-12 映射） |

（`git status` 其余改动均属 Step 0–4，未触碰。）

## 4. CommandParser（shared/speech，纯 Kotlin）

- 归一化：trim → 全角→半角（U+FF01–FF5E −0xFEE0、U+3000→空格）→ 小写折叠 → 去末尾标点（中英文标点集合）；**归一化后精确等值匹配**（无模糊/繁简/NLP 容错——「會了」≠「会了」→ UNKNOWN）。
- `DEFAULT_MASTERED_ALIASES = {会了, 记住了, 掌握了}`、`V1_ENABLED = {MASTERED}` 为伴生常量（D4）；PAUSE/RESUME/NEXT/REPLAY/EXIT 枚举保留（DOMAIN_MODEL §3.3），v1 不启用。
- 8 例 JVM 单测覆盖归一化各维 + 不误触发（TC-AE-10 ✅）。

## 5. 编排器接入（D2 最小接口变化，未重写状态机）

- 构造器 +`recognizer: SpeechCommandRecognizer`、`commandParser: CommandParser`（默认无参重载保留既有测试形态——既有 204 例零改动通过）。
- **CommandWindow 双形态**：窗口开启读 `recognizer.isAvailable`——可用 → 监听模式（`listenOnce(剩余窗口)` 循环 + 100ms ticker 并行广播）；不可用 → P4 纯倒计时（= §9 降级形态，零 listen 调用）。`CommandWindow.listening` 如实区分。
- **监听循环三终局**（`listenForMasteredCommand`）：Hit → parser → MASTERED=关窗掌握推进 / UNKNOWN=`yield()` 保持监听（不误杀）；Timeout → ticker 跑满既有超时 advance；Unavailable → 整窗降级（`degraded` 后续 tick 广播 `listening=false`）。**任何识别错误不产生命令语义、绝不 advance/掌握**（D5 红线，逐条有测试）。
- **`masterCurrentWord()`**：仅 CommandWindow 态（否则幂等 no-op）→ `windowCommandConsumed` 检查/置位 → cancelStep（= 关识别）→ 与语音汇合于 `executeMasteredCommand`（`markMastered(BUTTON)` → 既有 `advanceAndAdopt`，NonCancellable）。**语音与按钮同一执行路径、同源幂等**（LE §6 入口唯一）。
- **窗口生命周期（FR-12）**：ENTER（guard 300ms 后）开识别；命中恰一次消费；Timeout 关识别走既有语义；pause/exit/dispose → listenOnce 协程取消（actual finally destroy）；resume(窗口位) → 重开整窗（消费位重置）。识别器只在 CommandWindow 态被调用（TC-AE-06 间谍断言：Playing 全程零调用）。
- **重复防护（AUDIO §11 双层）**：编排器 `windowCommandConsumed`（同窗语音+按钮合计至多一次）+ actual 单次 resume 守卫（回调不重复触达）。

## 6. Android actual（AndroidSpeechCommandRecognizer）

- **线程/生命周期**：`Main.immediate`；每次 `listenOnce` = create →（软错误限速重挂）→ finally `stopListening + setRecognitionListener(null) + destroy`——取消/终局/异常路径统一收尾，迟到回调解绑后不触达业务层。**同一时刻 ≤1 并发会话**（active 守卫，重入 → Unavailable）。
- **窗口预算**：deadline 制（elapsedRealtime 基准，重挂 150ms 间隔计入预算）——软错误循环不越过窗口；预算静默耗尽 → Timeout。
- **D3**：`EXTRA_LANGUAGE=zh-CN` + `EXTRA_PREFER_OFFLINE=true` + `EXTRA_PARTIAL_RESULTS=false` + `EXTRA_MAX_RESULTS=1`；common 端口签名零改动。
- **D5 错误映射**：软（NO_MATCH/SPEECH_TIMEOUT/RECOGNIZER_BUSY/网络/服务瞬时/空 results/未知码保守）→ 重挂；硬（INSUFFICIENT_PERMISSIONS/ERROR_CLIENT/create 失败/startListening 同步 SecurityException）→ `Unavailable` + 拉低 `isAvailable`（后续窗口前置门降级，进程内不自动恢复）。
- **绝不抛异常**（§8 契约）：OEM 同步异常在 actual 内消化为结果；@Suppress 2 组（TooGenericExceptionCaught/SwallowedException）理由 = OEM 异常种类不可穷举。

## 7. DI / Manifest / ViewModel / Screen

- Koin：`AndroidSpeechCommandRecognizer(applicationContext)` 单例（不持 Activity）、`CommandParser()` 单例；编排器装配增参——VM 仍只依赖编排器，**不创建识别器**。
- Manifest：`RECORD_AUDIO` 声明（注释言明仅窗口内识别、无后台监听）。
- VM：`masterCurrentWord()` = `launchCommand { orchestrator.masterCurrentWord() }`（纯转发）；`listening` 透传投影。
- Screen：窗口分支「🎤 请说『会了』」/「语音命令不可用，可点击『会了』按钮」双文案（诚实呈现，无伪造录音动画）；「会了」按钮仅窗口分支渲染（testTag `btn_mastered`）；会话中未授权 → 非阻断授权条（`mic_banner` + `btn_grant_mic`，**用户点击才 launch 系统弹窗，绝不自动请求、绝不阻断**，D5）。

## 8. 窗口语义总表（实现 ↔ 规格）

| 场景 | 行为 | 验证 |
|---|---|---|
| 窗口开启 + 识别器可用 | 监听模式 + 倒计时广播 | TC-AE-04/25 |
| 说「会了」/别名 | 恰一次 markMastered(VOICE) → advance → 下一词起播 | TC-AE-04/25 + VM 测试 |
| 说「你好」（UNKNOWN） | 忽略、保持监听、零掌握，窗口跑满走既有超时 advance | TC-AE-05/25 + VM 噪音测试 |
| 瞬时识别错误（软） | 「本次无有效命令」重挂，预算内；零掌握零推进 | TC-AE-25 + actual 静默契约测试 |
| Unavailable / 权限拒绝 / 服务缺席 | 整窗降级纯倒计时（listening=false），按钮仍可用，零掌握 | TC-AE-25/26 + 隔离插桩 |
| 「会了」按钮（窗口内） | markMastered(BUTTON) 同一路径推进 | Smoke I + VM 测试 |
| 按钮窗口外 | 幂等 no-op | TC-AE-26 VM 测试 |
| 双击 / 语音后补按 | 同窗至多一次掌握 | TC-AE-25/26 |
| TTS 播放期间 | recognizer 零调用 | TC-AE-06 间谍断言 |
| pause/exit/dispose | 关识别（取消 → destroy）；resume 重开整窗 | TC-AE-25 + actual 取消契约测试 |

## 9. Schema audit

Schema 恒 **v2**：零 DDL / migration / 新表列索引 / SessionWord 变更。LearningSettingsRepository 未扩（D4 明示归 Phase 8）。

## 10. Architecture boundary audit

- `:shared:checkPlatformBoundaries` ✅ + detekt ✅：commonMain 无 `android.*`/`java.*`（CommandParser/编排器为纯 Kotlin）。
- Screen/VM 无 `SpeechRecognizer`/`RecognizerIntent` 符号；VM 不创建识别器（DI 单例注入编排器）；UI → VM → 编排器 → 端口唯一通路不变。
- detekt 整改：编排器抽出 `listenForMasteredCommand`/`WindowListenOutcome`（复杂度/跳转/返回数全绿，零 suppress）；Screen/VM 测试行长整改——**本 Step 新代码 0 未处理告警**。

## 11. 三层测试总览

| 层 | 数量 | 结果 |
|---|---|---|
| `:shared:jvmTest` | 225（+21：CommandParser 8 + 编排器语音 13） | ✅ 225/225，0 跳过 |
| `:shared:testDebugUnitTest` | 98 | ✅ 98/98 |
| `:app:testDebugUnitTest` | 20（+5 VM 语音路径） | ✅ 20/20 |
| androidTest 全套 | 38 | ✅ 0 failed，3 skipped（见 §13/§14） |

编排器语音 13 例要点：Hit 恰一次掌握+推进+停听；UNKNOWN 零掌握超时推进；Unavailable 整窗降级按钮仍可用；isAvailable=false 前置门零 listen 调用；TTS 期间零调用；按钮双击/语音后补按至多一次；窗口 pause/exit 关识别；D5 红线逐条。VM 5 例：语音 Hit、噪音、降级+按钮、双击至多一次、窗口外 no-op（Fake 端口记录 `markMasteredCalls`/`listenCalls`，无 mock 框架）。

## 12. Step 2/3/4 regression

- Step 2（204 基线 → 225 全量复跑）：既有状态机/仓储 204 例全绿（仅 StateTest/RestartTest 装配点 +3 行注入默认不可用 Fake，语义 = P4 纯倒计时不变，断言零改动；`--rerun-tasks` 强制复跑验证，非 up-to-date 缓存）。
- Step 3：6 个既有 androidTest 类全量复跑 ✅（Media3/TTS/焦点/冒烟无回归；唯一既有 skip = TTS 初始化失败契约，Step 3 起诚实跳过）。
- Step 4：VM 既有 13 例 + Smoke A–H + BookDeleted 全绿（Fake 识别器默认不可用 = P4 形态，既有用例时序不变）。

## 13. Instrumentation 关键发现：`pm revoke` 会杀插桩进程

- **现象**：初版 `permissionDenied_mapsToUnavailable` 在全套件内崩溃（空 failure + "Process crashed"，吞掉其后 ~32 例）。
- **根因**：Android 11+ 运行时 revoke 已授权危险权限会**杀死运行中的 app 进程**；插桩默认与 app 同进程 → revoke 即杀全套件。非被测代码缺陷。
- **处置**：全套件禁止 revoke。拒绝态验证改为**两段式确定性执行法**（TEST_PLAN TC-AE-26 注记）——单类隔离运行，Gradle 每次插桩前重装 APK = 全新拒绝态：
  1. `…-Pandroid.testInstrumentationRunnerArguments.class=…PermissionDeniedSpeechInstrumentedTest` → **1/1 PASS**（真机实证：拒绝 + 服务在场 → listenOnce=Unavailable、isAvailable 拉低、不抛异常不 Hit）。
  2. `…-Pandroid.testInstrumentationRunnerArguments.class=…LearningSessionUiSmokeTest` → **10/10 PASS**（含 smokeJ 真实渲染：窗口降级提示 + `mic_banner`；A–I 在拒绝态同绿 = 会话/按钮不受权限影响）。
  全套件内该二例 Assume 诚实跳过（前序用例已 grant；跳过原因写明）。

## 14. Instrumentation 结果与 Honest Skip

全套件（最终门禁运行）：**38 tests，0 failed，0 errors，3 skipped**——

| Skip | 性质 |
|---|---|
| `TtsSpeechSynthesizerInstrumentedTest.initializationFailure_contractIsException` | Step 3 既有诚实跳过（健康 TTS 引擎无法强制初始化失败） |
| `PermissionDeniedSpeechInstrumentedTest.permissionDenied_mapsToUnavailable` | 套件内自适应跳过（已 grant）；**隔离运行实证 PASS**（§13） |
| `LearningSessionUiSmokeTest.smokeJ_…` | 同上（已 grant）；**隔离运行实证 PASS**（§13） |

Smoke I（窗口「会了」按钮 → 同一路径推进第二词）在全套件内 **PASS**（确定性）。

## 15. Manual matrix（不伪造，待人工）

| # | 场景 | 步骤 | 预期 |
|---|---|---|---|
| M1 | 真人语音「会了」 | 授权麦克风 → 开始学习 → 播完一词 → 对麦克风说「会了」（及「记住了」「掌握了」） | 立即推进下一词，恰一次 |
| M2 | 真人语音噪音 | 同上说「你好」/咳嗽 | 不推进不掌握，窗口倒计时走满后推进 |
| M3 | 权限拒绝真机复验 | 全新安装不授权 → 开始学习 | 窗口显示「语音命令不可用」+ 底部授权条；点「会了」照常推进；点「授权」→ 系统弹窗 → 授权后**重启 App** 恢复语音（进程内不自动恢复为设计语义） |
| M4 | 无后台监听体感 | 学习中/退出后观察状态栏与电量 | 任意时刻无麦克风指示常驻（识别仅在窗口内创建/销毁） |

（AVD 无 zh-CN 离线语言包 + 无人声输入，M1/M2 不在自动化内伪造；M3 的映射与 UI 已由隔离插桩在 AVD 实证，真机 OEM 差异留人工。）

## 16. All gates

| 门禁 | 结果 |
|---|---|
| `:shared:jvmTest` | ✅ 225/225（`--rerun-tasks` 强制复跑） |
| `:shared:testDebugUnitTest` | ✅ 98/98 |
| `:app:testDebugUnitTest` | ✅ 20/20 |
| `:shared:detekt` / `:app:detekt` | ✅ 0 issue |
| `:shared:checkPlatformBoundaries` | ✅ |
| `:app:assembleDebug` | ✅ |
| `:app:connectedDebugAndroidTest`（全套件） | ✅ 38 tests / 0 failed / 3 skipped（§14） |
| 隔离运行 ×2（拒绝态） | ✅ 1/1 + 10/10（§13） |
| `git diff --check` | ✅ 干净（仅 CRLF 提示 warning，同历届） |
| `adb devices` | ✅ emulator-5554 |
| HEAD | ✅ `77baaf1`（零 commit） |

## 17. Known limitations

1. **权限授权后不自动恢复语音**（进程内 `isAvailable` 已拉低，前置门持续降级）——设计裁决（诚实降级、无权限自愈轮询）；重启进程后恢复。M3 人工复核。
2. **AVD 无 zh-CN 离线识别包**（SodaSpeechRecognizer language pack error 12）：静默窗口 = 软错误限速重挂 → 预算内 Timeout（已被 actual 契约测试实证为正确行为）；真机离线包存在时同路径直接出识别结果。
3. **真人语音识别不在自动化内**（M1/M2 手动矩阵）——解析层（TC-AE-10）与路由层（TC-AE-04/05/25）已各自自动化锁死。
4. **未知错误码保守映射软错误**（重挂而非降级）——宁可多试不误判不可用；若真出现持续未知码，表现为窗口静默耗尽（仍零掌握零误推进，符合 D5）。
5. 套件内两个拒绝态用例恒 Assume 跳过（顺序依赖），拒绝态回归依赖两段式隔离运行（CI 若接入需两条命令，已写入 TC-AE-26 注记）。

## 18. Git status

- HEAD `77baaf1` 不变，**零 commit**（等验收）。
- 工作区 = Phase 4 Step 0–4 全部改动 + 本 Step 增量（§3），`git diff --check` 干净。

---

## 结论分类

- **Automated PASS**：§11 三层单测（225+98+20）、全套件 38/0/3、隔离拒绝态 1/1 + 10/10、Smoke I、全部门禁（detekt/边界/打包/git/adb）。
- **Isolated-run PASS（环境诚实）**：权限拒绝映射 + smokeJ（新装拒绝态隔离插桩，§13）。
- **Honest Skip**：TTS 初始化失败契约（Step 3 既有）；套件内两个拒绝态用例（隔离实证替代）。
- **Manual pending**：M1–M4（§15）。
- **Known Risk**：§17 五项。

---

Phase 5 Step 1
STATUS: **PASS**

STOP — WAITING FOR PHASE 5 STEP 1 APPROVAL
