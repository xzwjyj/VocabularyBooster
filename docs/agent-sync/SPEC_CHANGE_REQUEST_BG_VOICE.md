# SPEC CHANGE REQUEST — 熄屏语音「会了」（mic 型前台服务）

- **编号**：SCR-BGVOICE
- **日期**：2026-10-14
- **影响范围**：`app/speech/BackgroundGatedSpeechCommandRecognizer.kt`（**删除**）；`app/playback/PlaybackForegroundController.kt`（移除熄屏竞态 `pause()` 守护）；`app/playback/AppForegroundState.kt`（KDoc 改用途：诊断标记源）；`app/playback/PlaybackForegroundService.kt`（`startForeground` 双类型 + 降级守卫）；`app/di/AppModule.kt`（装配直连）；`shared/androidMain/platform/VoskSpeechCommandRecognizer.kt`（构造参 `appForeground` + 诊断 `fg=` 标记）；`app/AndroidManifest.xml`（+`FOREGROUND_SERVICE_MICROPHONE`、service type `mediaPlayback|microphone`）；**无 SQL schema 变更 / 无模型资产 / 无 commonMain 端口改动**
- **关联需求**：FR-12（语音协议）v1.31 后台门禁**废止** → 熄屏/后台语音可用；FR-25（熄屏/后台续播）扩展；PROJECT_SPEC v1.33
- **前置裁决（用户 2026-10-14）**：熄屏学习时「会了」必须可用——推翻本人 2026-10-12 批准的「后台禁麦」红线（当时走查按纯倒计时验收通过，实际使用后判定口袋场景语音不可用不可接受）

## 1. 问题（用户需求）

v1.6.0 装机实际使用报告：熄屏后播放学习会话，说「会了」无反应，播放一直继续（词循环不掌握）。根因 = FR-25 v1.31 设计行为：`BackgroundGatedSpeechCommandRecognizer` 后台 `isAvailable=false` → CommandWindow 纯倒计时 → 超时 advance 不掌握。**非实现 bug，是红线本身与核心场景冲突**：FR-25 的目的就是手机揣兜听词，兜里无法点屏内按钮，语音是唯一掌握入口——纯倒计时 = 熄屏模式永远无法掌握任何词。

## 2. 平台事实（Android while-in-use 限制）

- **Android 11+（API 30）**：App 在后台（无可见 Activity）时麦克风访问被系统静默/拒绝，**除非**持有 `foregroundServiceType="microphone"` 的前台服务（while-in-use 豁免）。当前服务仅 `mediaPlayback` 型——后台开麦在系统层就拿不到声音，这是 v1.31 选择禁麦的客观约束。
- **Android 14+（API 34）**：mic 型 FGS 必须在 **App 前台时启动**方可访问麦克风（罕见豁免除外）；且要求 `FOREGROUND_SERVICE_MICROPHONE` 权限（per-type FGS 权限族）。本 App 会话播放恒由屏内操作发起（点开始/继续），服务启动时刻 App 必在前台 → 天然满足；通知栏恢复路径服务未停（Paused 仍是传输活跃态，服务保持运行），无二次启动问题。
- **Android 10 及以下（minSdk 26）**：无 FGS 类型概念（`ServiceCompat` 门控），mic 型位自然忽略——前台服务本身已允许后台持麦。
- **残余风险（不可系统层消除）**：OEM 后台管控（vivo i管家 / MIUI 等）可能对后台麦克风独立静默，即便 FGS 类型合规。已知该类风险（先例：vivo OEM 语音链路把轻声整窗抹静默，AUDIO_ENGINE_SPEC v2.1）。**取证口径**：Vosk 诊断文件窗口行新增 `fg=` 前后台标记——装机若见 `fg=false` 窗口 RMS 恒零/无环境底噪 → OEM 静默实证；届时行为自然退化为纯倒计时（与 v1.31 等价，无功能损害——纯倒计时与「开了麦但听不到」用户可感知行为一致），机型处置另立项。

## 3. 方案（裁决 2026-10-14，五条冻结语义）

1. **FR-12 红线重述（放宽一条，其余不变）**：「识别只存在于 CommandWindow 态」「TTS 播放期间识别器关闭」「绝无常驻监听」全部不变；**废止 v1.31 的「绝不在后台持麦」**——CommandWindow 在熄屏/后台照常开麦（`listening=true` 监听模式），麦克风合法性由 mic 型 FGS 承载。开麦仍只在 4 秒命令窗口内，非连续监听。
2. **前台服务双类型**：`PlaybackForegroundService` manifest `foregroundServiceType="mediaPlayback|microphone"` + `FOREGROUND_SERVICE_MICROPHONE` 权限；`ServiceCompat.startForeground` 传双类型位。**降级守卫**：双类型启动异常（系统限 background-start mic 型等罕见路径）→ 捕获降级重试 `mediaPlayback` 单类型 + 记日志（熄屏语音该进程内不可用 = 窗口纯倒计时，续播不中断——续播优先级高于熄屏语音）。
3. **wrapper 撤除 + 守护移除**：删除 `BackgroundGatedSpeechCommandRecognizer`（两条装配路径产出直连 `SpeechCommandRecognizer` 绑定出口，恢复 v1.31 前形态）；`PlaybackForegroundController` 的 ON_STOP 熄屏竞态 `pause()` 守护移除（窗口中熄屏不再关麦转 Paused——窗口照常后台听音走完，超时 advance 语义不变）。`AppForegroundState` 保留，注入 Vosk actual 作诊断标记源（不再作门禁）。
4. **Vosk actual 诊断扩展**：构造参 `appForeground: StateFlow<Boolean>`（默认 true，app 装配注入 `AppForegroundState.flow`），`listenOnce` 窗口诊断行增 `fg=` 标记——OEM 后台静默取证通道（§2 残余风险）。系统 actual 不动（GMS 机后台语音经 while-in-use 豁免；失败走既有 E3/E4 回退 Vosk）。
5. **编排器/引擎零改动**：CommandWindow 双形态（D2）由 `recognizer.isAvailable` 驱动的既有语义不变——后台不再有「置 false 来源」，窗口恒监听模式（除非既有可用性降级来源触发）。掌握/推进/完成语义零改动。

## 4. 不改的东西

- commonMain 端口矩阵 / 编排器状态机 / CommandParser / LearningEngine——零改动；
- 通知形态（三大传输键、无 Exit）、`POST_NOTIFICATIONS` 请求流、`dispose()` Stopped 信号、WakeLock 策略——v2.14 收口维持；
- 系统 actual（`AndroidSpeechCommandRecognizer`）、`FallbackSpeechCommandRecognizer` E2/E3/E4 选择与回退——零改动；
- SQL schema / 模型资产——零改动。

## 5. 验收

装机走查 TC-BG-08（TEST_PLAN v2.37）：熄屏播放中，每个词播完后的命令窗口照常听音，说「会了」→ 立即掌握换下一词（跨 3 词以上）；`vosk_diag.log` 窗口行 `fg=false` 且 RMS 有环境底噪（非恒零）——OEM 未静默取证；亮屏对照（fg=true 窗口）行为一致。若装机证实 OEM 后台静默（fg=false 窗口 RMS 恒零）→ 该机型熄屏语音不可用登记 + 处置另立项（行为退化纯倒计时，不阻断续播）。
