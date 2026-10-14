# SPEC CHANGE REQUEST — 熄屏/后台续播（前台媒体服务）

- **编号**：SCR-BGPLAYBACK
- **日期**：2026-10-12
- **影响范围**：`app` 新增前台服务 + 控制器 + 识别器后台门禁 wrapper；`app/AndroidManifest.xml`（+4 权限 +`<service>`）；`app/build.gradle.kts` + `libs.versions.toml`（+`media3-session` +`lifecycle-process`）；`shared/androidMain/Media3AudioPlayer.kt`（+`setWakeMode`，双重保险）；`shared/commonMain/PlaybackOrchestrator.kt`（`dispose()` 增设 Stopped 信号，1 行语义澄清）；**无 SQL schema 变更 / 无模型资产 / 无 commonMain 端口新增**（FR-12 既有规则不动）
- **关联需求**：新增 FR-25（熄屏/后台续播）；FR-11（控制）通知栏扩展；FR-12（语音协议）后台门禁；PROJECT_SPEC 版本记录
- **前置裁决（用户 2026-10-12）**：熄屏后播放继续（前台媒体服务方案），非「保持自动暂停 + 防熄屏」轻路径

## 1. 问题（用户需求）

学习会话播放中熄屏 → 播放停止。期望：屏幕关闭后音频继续循环播放（手机揣兜听词），语音命令「会了」在熄屏时不可用即退化为纯倒计时/仅按钮（符合 FR-12「绝无后台监听」）。

## 2. 背景 / 审计（源码事实）

### 2.1 根因：三处平台缺口（commonMain 零问题）

1. **无前台媒体服务**：`app/AndroidManifest.xml` 仅声明 `MainActivity`，无任何 `<service>`。targetSdk 35 下 App 进后台/熄屏 → Android 自 8.0 起强制要求 `mediaPlayback` 类型前台服务才允许后台播音频，14+ 更严——系统直接掐后台音频。**直接主因**。
2. **无 WakeLock**：`Media3AudioPlayer.kt:62-65` ExoPlayer 未 `setWakeMode`；`SherpaOnnxSpeechSynthesizer` AudioTrack STREAM 阻塞写在 `Dispatchers.IO.limitedParallelism(1)` 线程无锁。熄屏后 Doze 让 CPU 睡眠，阻塞写停滞。`setHandleAudioBecomingNoisy(true)` 只管拔耳机。
3. **编排器 scope 非元凶**：`PlaybackOrchestrator`（`AppModule.kt:127-140`）是 Koin `single`，自带应用级 `SupervisorJob + Dispatchers.Main.immediate`，Activity `onStop` 不取消它——熄屏停播不是协程被砍，是系统/Doze 掐的。

### 2.2 与现有规格的张力（须裁决化解）

- **ARCHITECTURE §1 L40**：「引擎自驱循环播放，不依赖 UI 存活（后台/熄屏仍可续播的**潜力保留**）」——只保留潜力，平台 actual 未接线。本 SCR 落地该潜力。
- **TEST_PLAN 铁律 8**（v1.8，2026-09-17）：「切后台自动暂停 = **设计行为**」——当时为语音命令 M2 统计口径（后台麦克风不可用）。**本 SCR 撤销该铁律**：熄屏/后台不再自动暂停，改为「音频续播 + 语音后台门禁」。
- **FR-12 / ADR-08**：「识别只存在于 CommandWindow 态，绝无后台监听、绝无常驻麦克风」。本 SCR 不放宽——熄屏时识别器 `isAvailable` 置 false → CommandWindow 复用既有 D2 双形态自然退化为纯倒计时（`listening=false`），按钮仍可用。

### 2.3 关键复用点（已有设计可直接接驳）

- **CommandWindow 双形态（§3 / 裁决 D2）**：`PlaybackOrchestrator.kt:422` `listeningEnabled = recognizer.isAvailable.value`；isAvailable=false → 整窗纯倒计时。**后台门禁只需让 isAvailable 在后台变 false**，编排器/解析器/掌握语义零改动。
- **`FallbackSpeechCommandRecognizer.isAvailable`（app/speech/，L39-41）**：已是 app 层 `combine(...)` wrapper——天然挂载点。但 `EMBEDDED_VOSK` 装配路径（`AppModule.kt:105`）直连 `vosk` 不经 wrapper → 后台门禁 wrapper 必须包住**两条**装配路径（系统服务在场 / 缺席）。
- **`PlaybackOrchestrator.state`（StateFlow）**：唯一状态源，app 层控制器可 collect 驱动服务启停；Playing/Paused/CommandWindow = 传输活跃（服务保持），Idle/Stopped/Completed = 终态（服务停）。

### 2.4 依赖现状

- `libs.versions.toml`：`media3 = "1.5.1"`（shared/androidMain 已用 exoplayer+common）；`lifecycleRuntimeKtx = "2.8.7"`。**缺**：`media3-session`（MediaSession）、`lifecycle-process`（ProcessLifecycleOwner）——两项新增 app 依赖，沿用同版本。
- `app/build.gradle.kts` 无 Media3 直接依赖（Media3 在 shared/androidMain）；app 新增 `media3-session` + `lifecycle-process`。

## 3. 方案（裁决 2026-10-12，七条冻结语义）

1. **前台媒体服务 `PlaybackForegroundService`**（app 模块，新文件）：`foregroundServiceType="mediaPlayback"`；`startForeground` 持媒体通知（MediaStyle）；持有进程级 `PARTIAL_WAKE_LOCK`（覆盖 ExoPlayer + SherpaOnnx AudioTrack IO 阻塞写——单锁兜底两 actual，SherpaOnnx 零改动）；承载 Media3 `MediaSession` + `ForwardingPlayer` 桩镜像编排器状态（Android 14 mediaPlayback FGS 合规：系统期望活跃 MediaSession，否则宽限后杀）。
2. **服务生命周期由 app 层 `PlaybackForegroundController` 驱动**（新文件，DI single）：collect `orchestrator.state`——传输活跃（Playing/Paused/CommandWindow）→ `startForegroundService` + `startForeground`；终态（Idle/Stopped/Completed）→ `stopForeground` + `stopSelf`。状态变化只更新 MediaSession/通知，不重启服务。
3. **`dispose()` 增设 Stopped 信号**（`PlaybackOrchestrator.kt:284`）：宿主 `onCleared` 时除现有 cancelStep + stopPorts 外，置 `_state.value = PlaybackState.Stopped`（**仅内存态；DB 会话不动**——`LearningSession` 仍 ACTIVE 可 resume，既有崩溃恢复语义零变化）。目的：控制器能观测到「播放已停」可靠停服务；`sessionOwned` 守卫（ViewModel L74）已防 Stopped 被新屏误消费。注：`onCleared` 仅在 Activity 真正 finishing 触发，旋转/熄屏不清 VM（dispose 不在熄屏调用）。
4. **熄屏/后台语音门禁（FR-12 严守）**：新建 app 层薄 wrapper `BackgroundGatedSpeechCommandRecognizer`（包住两条装配路径产出），`isAvailable = underlying.isAvailable && appInForeground`，`listenOnce` 在后台短路返回 `Unavailable`（绝不后台持麦、不试探）。前台标志 = `ProcessLifecycleOwner`（`lifecycle-process`）ON_START→true / ON_STOP→false。编排器 §3 D2 自然复用，零 commonMain 改动。
5. **熄屏竞态（窗口中熄屏）严守**：ON_STOP 时若 `state == CommandWindow && listening==true` → 控制器 `orchestrator.pause()`（取消窗口 Job → listenOnce 协程取消 → actual destroy 关麦；状态转 Paused(窗口位)）。**音频随后由用户 resume 或保持暂停**——不一刀切续播越过窗口。理由：绝不让麦进入后台，哪怕一瞬；窗口恢复由用户主导（与既有 pause/resume 同路径）。
6. **`Media3AudioPlayer` +`setWakeMode(C.WAKE_MODE_LOCAL)`**（`L62-65` Builder 链）：双重保险——服务级 wakelock 为主，ExoPlayer 自管为辅（例句原声段独立保险）。
7. **通知栏控制（FR-11 扩展）**：Pause/Resume/Next/Previous/Exit 五控制经通知 action → 控制器 → 编排器，与屏幕按钮同一执行路径（D2 精神延伸，零第二套语义）。Exit 经通知栏需二次确认的 UI 不便——**裁决：通知栏 Exit 直接执行（无二次确认）**，因通知栏交互本身即明确意图（防误触的二次确认针对屏内按钮，通知栏不在其列）。

## 4. 不改的东西

- commonMain 端口矩阵（`SpeechSynthesizer / AudioPlayer / SpeechCommandRecognizer` 接口零改动——后台门禁是 app 层 wrapper，非端口概念泄漏）；
- 学习/掌握/推进/完成/退出三分支语义、CommandWindow 时序、位置持久化双源优先级（L3）、ADR-09 段语义、Pause/Resume 不整词重播；
- FR-12 识别只存在于 CommandWindow（后台门禁是收紧非放宽）、别名代码常量（D4）、近音结构规则（v2.0）；
- `SherpaOnnxSpeechSynthesizer` actual（服务级 wakelock 覆盖其 AudioTrack IO，零触碰）；
- SQL schema（零迁移零 user_version）、模型资产、Gradle wrapper；
- 既有屏幕五控制 +「会了」按钮 + Exit 二次确认（通知栏 Exit 是独立入口，不改屏内确认流）。

## 5. 测试与文档

### 测试（新编号 TC-BG 系列，沿 TC-AE 先例；执行时取下一空闲号）

- **TC-BG-01**（app 单测，StandardTestDispatcher）：`BackgroundGatedSpeechCommandRecognizer`——前台标志 true 时 `isAvailable` 随 underlying；false 时恒 false；`listenOnce` 后台短路返回 `Unavailable`、零调用 underlying（Fake 端口断言）。
- **TC-BG-02**（app 单测）：`PlaybackForegroundController` 状态映射——Playing/Paused/CommandWindow → `foregroundDesired=true`；Idle/Stopped/Completed → false；Stable tick 无抖动（同状态重复发射不重启服务，Fake ServiceGateway 计数）。
- **TC-BG-03**（app 单测）：`dispose()` 置 Stopped——`PlaybackOrchestrator` jvmTest 增 `disposeSetsStoppedPreservesDbSession`（Fake 引擎断言 LearningSession 仍 ACTIVE、`_state.value==Stopped`）；既有 dispose 用例回归（取消 stepJob + stopPorts 不变）。
- **TC-BG-04**（app 单测）：熄屏竞态——ON_STOP 时 state==CommandWindow.listening → 控制器调 `orchestrator.pause()`；state==Playing → 不 pause（保持续播）；ON_START 不触发任何控制。
- **TC-BG-05**（androidTest 模拟器冒烟，可选）：startForeground 通知出现 + MediaSession active；退出 → 通知消失、服务 stopForeground。模拟器无 Doze，Doze 行为归真机走查。
- **装机走查（vivo，用户执行）**：① 熄屏续播（屏幕关闭后音频继续，验证 1 分钟以上跨多个词）；② 熄屏时 CommandWindow 退化纯倒计时（无麦指示，倒计时走完 advance）；③ 通知栏 Pause/Next/Exit 可用；④ 拔耳机（becomingNoisy）仍自动暂停；⑤ 屏内 Exit 二次确认流不变。
- **门禁**：`jvmTest` / `testDebugUnitTest` / app:testDebugUnitTest / detekt×2 / `checkPlatformBoundaries`（commonMain 零平台 import，须仍绿——本批不动 commonMain 端口）/ `assembleDebug`。

### 文档（执行时同步，先于代码）

- **PROJECT_SPEC v1.31**（FR-25 全文 + 验收行 + FR-11 通知栏扩展注记 + FR-12 后台门禁注记 + §6 追溯 + 版本记录；**撤销铁律 8 的「切后台自动暂停」**→改为「熄屏/后台续播 + 语音后台门禁」）。
- **AUDIO_ENGINE_SPEC v2.13**（§8 +`PlaybackForegroundService` actual 契约行 +`BackgroundGatedSpeechCommandRecognizer` 后台门禁行 +`setWakeMode` 注记 + MediaSession/通知契约；§3 CommandWindow 双形态行补「后台 isAvailable=false 触发」来源；§9 +「熄屏/后台续播」降级行；铁律 8 修订或迁移注记）。
- **ARCHITECTURE §1 L40**「潜力保留」→「已实现」；§5 端口矩阵不新增（app 层组件，非 commonMain 端口）；版本记录。
- **TEST_PLAN v2.34**（TC-BG-01..05 + §7 熄屏/后台行重写 + 铁律 8 修订）。
- **DECISION_LOG** ADR-013（前台媒体服务 + 后台语音门禁决策）。
- **ROADMAP**：本 SCR 属 Phase 9（稳定化/Alpha）范围或独立小批，执行时定夺登记。

## 6. 风险

- **`dispose()` 行为变更**：现有 KDoc「不动会话状态（学习历史真相在库）」——须澄清「不动 DB 会话 vs 设内存 Stopped」区别（DB `LearningSession` 仍 ACTIVE，内存 `_state=Stopped` 仅信号控制器）。既有 `sessionOwned` 守卫已防误消费，仍须 TC-BG-03 钉死 + 既有 dispose/重启用例全回归。
- **Media3 `MediaSession` 强制 `Player` 入参**：本 App 非 ExoPlayer 驱动（TTS 经 AudioTrack）→ 须实现 `ForwardingPlayer` 桩（~30 接口方法机械 no-op + 状态/`playWhenReady` 镜像编排器）。若桩过重，**降级方案**：纯 FGS + 普通通知（无 MediaSession），接受部分 OEM 宽限后杀的风险（vivo 实测定夺）。执行时先试 MediaSession 桩，装机若稳定即采用。
- **后台麦克风竞态**：窗口中熄屏（4s 窗口内）虽 §3.5 已裁决 pause，但 ON_STOP 到 pause 执行有 ~ms 级窗口——Vosk `listenOnce` 协程取消经 `CancellationException` 重抛（actual `destroy` 关麦），既有 v1.8 取消语义已验证零泄漏；竞态由 TC-BG-04 + 装机走查 ② 兜底。
- **Android 14 mediaPlayback FGS 合规**：须 `foregroundServiceType` 声明 + 通知 + 活跃 MediaSession（否则宽限后杀）；`POST_NOTIFICATIONS` 运行时授权（Android 13+）——未授权时通知不显但 FGS 仍可跑（前台状态生效）；走查须覆盖拒授权路径。
- **minSdk 26 / targetSdk 35 跨版本**：FGS 类型权限仅 API 34+ 强制（<34 忽略）；`setWakeMode` API 1 可用；`lifecycle-process` 全版本可用。版本兼容性低风险。
- **版本号占用**：FR-25 / PROJECT_SPEC v1.31 / AUDIO v2.13 / TEST_PLAN v2.34——v2.33 已被 SCR-SENSEATTR v7/v8 占用（2026-10-10），本批顺延至 v2.34；执行时重查版本表。

## 7. 追补（2026-10-14，v1.6.0 装机走查后用户裁决 3 条）

走查四项全过（① 熄屏续播跨 3 词 PLAYING 无断 / ② 后台窗口纯倒计时零听音 + 亮屏正对照 / ③ 三键路由 + 媒体键 + 退出三清零 / ④ 听音中熄屏 2s 内 PAUSED+已暂停），抓出 1 个发布阻塞 bug + 1 个设备约束：

1. **修 `POST_NOTIFICATIONS` 运行时请求**（真 bug）：v1 批次只声明未请求——Android 13+ 真实用户通知全不可见（走查靠 `adb shell pm grant` 后方可见）。修法：首次进入会话屏（`LearningSessionScreen` LaunchedEffect）`ActivityCompat.requestPermissions`；拒绝不阻断（FGS/熄屏续播不依赖通知可见）。
2. **通知形态改 MediaStyle 三大传输键**（设备约束 + 裁决）：vivo 通知栏对普通通知只渲染 3/4 action（第 4 个「退出」被裁）。裁决：接受通知栏无退出（退出仅屏内按钮），把 播放/暂停、上一词、下一词 做成**熄屏与屏上通知栏的三个大按键**——`MediaStyleNotificationHelper.MediaStyle` + 活跃 MediaSession → Android 13+ SystemUI 媒体轮播渲染系统标准大键；`OrchestratorMirroringPlayer.getAvailableCommands` 恒宣告 play-pause/prev/next（桩 delegate 空播放列表默认无 seek 命令，不覆写轮播缺键）；非轮播回退形态显示同三 action 文本键；撤 `ACTION_NOTIFY_EXIT` / `onNotificationExit`。
3. **只在本地 commit**：v1.6.0 未发布，修复并入首发构建（versionCode 7 / versionName 1.6.0 维持）。

文档同步：PROJECT_SPEC v1.32 / AUDIO_ENGINE_SPEC v2.14 / TEST_PLAN v2.35（TC-BG-06 权限请求 + TC-BG-07 媒体轮播三大键，装机走查用例）。
