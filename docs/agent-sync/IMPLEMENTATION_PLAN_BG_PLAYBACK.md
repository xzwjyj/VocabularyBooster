# 实现计划 — 熄屏/后台续播（前台媒体服务）

> 状态：待批准 | 日期：2026-10-12 | 前置：SCR-BGPLAYBACK 已定稿，用户裁决「续播」方案

## 交付物

FR-25：熄屏/后台音频续播（mediaPlayback 前台服务 + WakeLock + MediaSession/通知）；FR-12 后台语音门禁（`isAvailable` 后台置 false，CommandWindow 复用 D2 纯倒计时）；`dispose()` 增设 Stopped 内存信号；通知栏五控制。

## Step 0 规格同步（批准后立即，先于代码）

PROJECT_SPEC v1.31（FR-25 + FR-11 通知栏注 + FR-12 后台门禁注 + 撤销铁律 8「切后台自动暂停」→「续播 + 后台门禁」+ 版本记录）→ AUDIO_ENGINE_SPEC v2.13（§8 +actual 契约行 + §3 CommandWindow 双形态来源补 + §9 +降级行）→ ARCHITECTURE §1 L40「潜力保留」→「已实现」+ 版本记录 → TEST_PLAN v2.34（TC-BG-01..05 + §7 熄屏/后台行重写 + 铁律 8 修订）。

## Step 1 依赖与清单

1. `libs.versions.toml`：
   - `[libraries]` + `androidx-media3-session = { module = "androidx.media3:media3-session", version.ref = "media3" }`（1.5.1，shared/androidMain 已用同版本 exoplayer/common）；
   - + `androidx-lifecycle-process = { module = "androidx.lifecycle:lifecycle-process", version.ref = "lifecycleRuntimeKtx" }`（2.8.7，与既有 lifecycle 同组）。
2. `app/build.gradle.kts` `dependencies`：
   - + `implementation(libs.androidx.media3.session)`
   - + `implementation(libs.androidx.lifecycle.process)`
3. `app/src/main/AndroidManifest.xml`：
   - +4 `<uses-permission>`：`FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_MEDIA_PLAYBACK`、`WAKE_LOCK`、`POST_NOTIFICATIONS`（minSdk 26，<33 忽略；附 K2fsa/jieba 注记同区）；
   - `<application>` 内 + `<service android:name=".playback.PlaybackForegroundService" android:foregroundServiceType="mediaPlayback" android:exported="false" />`。

## Step 2 shared：`dispose()` 增设 Stopped 信号

`shared/commonMain/PlaybackOrchestrator.kt:284` `dispose()`：
```kotlin
public fun dispose() {
    stepJob?.cancel()
    synthesizer.stop()
    audioPlayer.stop()
    _state.value = PlaybackState.Stopped   // 内存态信号（DB 会话不动）；驱动前台控制器停服务
}
```
- KDoc 补澄清：「`_state=Stopped` 仅内存态——DB `LearningSession` 仍 ACTIVE（resumable），与既有崩溃恢复/位置持久化语义零变化；`sessionOwned` 守卫防新屏误消费」。
- jvmTest `PlaybackOrchestratorRestartTest` / `PlaybackOrchestratorStateTest` 增 TC-BG-03：`disposeSetsStoppedPreservesDbSession`（Fake 引擎断言会话未 finalize、`_state.value==Stopped`）；既有 dispose/重启用例全回归（取消顺序不变）。
- commonMain 改动 → `checkPlatformBoundaries` 须仍绿（无平台 import 引入）。

## Step 3 shared/androidMain：ExoPlayer setWakeMode

`Media3AudioPlayer.kt:62-65` `ExoPlayer.Builder` 链：
```kotlin
private val player: ExoPlayer = ExoPlayer.Builder(appContext)
    .setAudioAttributes(EXO_AUDIO_ATTRIBUTES, /* handleAudioFocus = */ false)
    .setHandleAudioBecomingNoisy(true)
    .setWakeMode(C.WAKE_MODE_LOCAL)   // 双重保险：服务级 wakelock 为主，ExoPlayer 自管例句原声段
    .build()
```
- 注：`C.WAKE_MODE_LOCAL` = PARTIAL_WAKE_LOCK；与服务级 wakelock 并存不冲突（引用计数）。
- 零契约变化（TC-AE-16 焦点 / onPlayerError 路径不动）。

## Step 4 app：后台语音门禁 wrapper

新文件 `app/src/main/kotlin/com/vocabularybooster/app/speech/BackgroundGatedSpeechCommandRecognizer.kt`：
```kotlin
public class BackgroundGatedSpeechCommandRecognizer(
    private val delegate: SpeechCommandRecognizer,
    private val appForeground: StateFlow<Boolean>,
) : SpeechCommandRecognizer {
    override val isAvailable: StateFlow<Boolean> =
        combine(delegate.isAvailable, appForeground) { d, fg -> d && fg }
            .stateIn(scope, SharingStarted.Eagerly, delegate.isAvailable.value && appForeground.value)
    override suspend fun listenOnce(windowMs: Long): RecognitionResult =
        if (!appForeground.value) RecognitionResult.Unavailable  // 后台短路（FR-12：绝不后台持麦/试探）
        else delegate.listenOnce(windowMs)
}
```
- 单测 TC-BG-01：前台 true → isAvailable 随 delegate / listenOnce 透传；false → isAvailable 恒 false / listenOnce 返 Unavailable 零调用 delegate（Fake 端口计数）。
- 装配（Step 6）：包住 `FallbackSpeechCommandRecognizer` 与直连 `vosk` 两条路径的产出（统一出口 = 本 wrapper）。

## Step 5 app：前台服务 + 控制器 + MediaSession + 通知

### 5.1 `PlaybackForegroundService`（新文件 `app/.../playback/PlaybackForegroundService.kt`）
- `Service` 子类；`onCreate` 建 `PowerManager.WakeLock`（PARTIAL_WAKE_LOCK，`VocabularyBooster::wake_lock` tag，`setReferenceCounted(false)`）+ Media3 `MediaSession`（`ForwardingPlayer` 桩镜像状态）；
- `onStartCommand`：`startForeground(NOTIF_ID, buildNotification(state))` + `wakeLock.acquire()`；
- `onDestroy` / 终态：`wakeLock.release()` + `mediaSession.release()` + `stopForeground(STOP_FOREGROUND_REMOVE)`；
- 状态更新入口（`updateState(PlaybackState)`，由控制器 RemoteAction 调）：刷 MediaSession `playWhenReady`/`PlaybackStateCompat` + 通知 `NotificationManager.notify`（同 NOTIF_ID，不重启服务）；
- 通知 action Intent（Pause/Resume/Next/Previous/Exit）→ 控制器 `onNotificationAction` → 编排器对应方法。

### 5.2 `ForwardingPlayer` 桩（或自实现 `Player`，新文件 `app/.../playback/OrchestratorMirroringPlayer.kt`）
- 继承 `androidx.media3.common.ForwardingPlayer`（包一个基 `Player`）或直接实现 `Player` 接口：机械 no-op + `getPlayWhenReady()/getPlaybackState()` 镜像编排器 Playing/Paused/CommandWindow→STATE_READY+playWhenReady / Stopped→STATE_IDLE；
- 通知 action 回调经 `Player.Listener`（`MediaSession` 转发播放控制到 Player）→ 控制器 → 编排器。
- 若桩过重（~30 方法），降级：`ExoPlayer` 空实例作基 + ForwardingPlayer 包裹（接受其状态不精确，由 MediaSession 状态覆盖）。

### 5.3 `PlaybackForegroundController`（新文件 `app/.../playback/PlaybackForegroundController.kt`）
- DI `single`；构造注入 `PlaybackOrchestrator` + `Context` + `ProcessLifecycleOwner` + `CoroutineScope(Main.immediate)`；
- `init` collect `orchestrator.state`：
  - `isTransportActive(state)` → `ensureServiceStarted()`（幂等：已启则只 `updateState`）；
  - 终态（Idle/Stopped/Completed）→ `ensureServiceStopped()`（幂等）；
- `ProcessLifecycleOwner.get().lifecycle.addObserver`：ON_START → `appForeground=true`；ON_STOP → `appForeground=false` + 熄屏竞态处理（见下）；
- **熄屏竞态（TC-BG-04）**：ON_STOP 时 `val s = orchestrator.state.value; if (s is CommandWindow && s.listening) orchestrator.pause()`（关麦 + 窗口转 Paused(窗口位)）；若 `s is Playing/Paused` → 不动（续播/保持）；ON_START 不触发控制。

### 5.4 通知构建（`buildNotification`）
- `NotificationCompat.Builder`，`MediaStyle`（挂 MediaSession token，Android 14 mediaPlayback FGS 合规）；
- 五 action：Pause/Resume（互斥按状态）、Previous、Next、Exit；
- `setContentTitle` = 当前词文本（`orchestrator.getCurrentContent()` 或 state 携带的 wordText）；
- `smallIcon` = `R.mipmap.ic_launcher` 或专用图标。

## Step 6 app：DI 装配 + Application 启动

`AppModule.kt`：
- `single { ProcessLifecycleOwner.get() }`（或直接 `androidContext()` 取，不入 Koin）；
- `appForeground: MutableStateFlow<Boolean>` 单例（DI `single { MutableStateFlow(true) }`）；
- `single<SpeechCommandRecognizer>` 既有绑定产出（Fallback 或直连 vosk）→ 再包 `BackgroundGatedSpeechCommandRecognizer(delegate = <既有产出>, appForeground = get())`；
- `single { PlaybackForegroundController(get(), androidContext(), get(), scope) }`；
- `single { PlaybackForegroundService.Gateway(...) }`（或控制器直接 `startForegroundService`/`stopService`，无 Gateway——取最简）。
`VocabularyBoosterApp.kt` `onCreate`：`koinApp.koin.get<PlaybackForegroundController>()` 触发构造（同 AudioPlayer 预装配先例）——启动即 collect state + 注册 ProcessLifecycle 观察。

## Step 7 测试

1. **TC-BG-01**（app unit）`BackgroundGatedSpeechCommandRecognizerTest`：Fake delegate + MutableStateFlow 前台标志；断言 isAvailable 与门、listenOnce 后台短路零调用。
2. **TC-BG-02**（app unit）`PlaybackForegroundControllerTest`：Fake ServiceGateway + Fake Orchestrator state；状态映射 desiredForeground；幂等（同状态重复不重启）。
3. **TC-BG-03**（jvmTest）`PlaybackOrchestratorStateTest` +`disposeSetsStoppedPreservesDbSession`；既有 dispose/Restart 用例全回归。
4. **TC-BG-04**（app unit）控制器熄屏竞态：ON_STOP + CommandWindow.listening → 调 `pause()`；ON_STOP + Playing → 不调；ON_START 零控制。
5. **TC-BG-05**（androidTest 模拟器，可选）冒烟：`startForegroundService` + 通知可见 + MediaSession active；退出 → 通知消失。模拟器无 Doze，Doze 归真机。
6. 全量门禁：`jvmTest` / `testDebugUnitTest` / app:testDebugUnitTest / detekt×2 / `checkPlatformBoundaries` / `assembleDebug`（+ connectedDebugAndroidTest 模拟器，vivo 拔出）。

## Step 8 收尾

装机走查清单（vivo，用户执行）→ DECISION_LOG ADR-013 → commit `feat(playback): background playback via mediaPlayback foreground service (SCR-BGPLAYBACK)`。版本号顺延（v1.5.0 → v1.6.0？执行时定夺；versionCode+1）。

## 边界与不做

- 不动 commonMain 端口接口（后台门禁 = app wrapper，非端口概念）；
- 不动 SherpaOnnx actual（服务级 wakelock 覆盖其 AudioTrack IO）；
- 不做通知栏 Exit 二次确认（屏内确认流不改，通知栏交互即明确意图）；
- 不做后台麦克风任何尝试（FR-12 严守，listenOnce 后台短路 Unavailable）；
- 不做 Media3 `MediaSessionService` 改造（非 ExoPlayer 驱动，用自管 FGS + ForwardingPlayer 桩）；
- 不改 DB schema / 模型资产。
