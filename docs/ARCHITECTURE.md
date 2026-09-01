# ARCHITECTURE — 跨平台架构

> 状态：Phase 0 定稿 ｜ 版本 1.0 ｜ 日期：2026-09-01
> 上游：PROJECT_SPEC（FR / NFR-6）。本文回答：**未来 iOS 为什么不需要重写核心逻辑，以及如何被强制保证。**

---

## 1. 架构总览

```
┌─ app（Android，Phase 1+） ────────────────────────────────────────────────┐
│  Jetpack Compose UI  ▲StateFlow   ViewModel(AndroidX) ──┐                 │
│                      │                                    │ 委托            │
│  platform actuals ───┼────────────────────────────────────┘                │
│   • TtsSpeechSynthesizer（android.speech.tts）                            │
│   • AndroidSpeechCommandRecognizer（SpeechRecognizer）                    │
│   • Media3AudioPlayer（Media3 ExoPlayer）                                 │
│   • AndroidDatabaseDriverFactory（SQLDelight）                            │
│  Koin：androidModule（提供上述 actuals）                                    │
└──────────────────────────────────────────────────────────────────────────┘
                            │ 仅依赖接口（Koin 注入）
┌─ shared（KMP，全部核心逻辑） ──────────────────────────────────────────────┐
│  commonMain（100% 纯 Kotlin，禁止平台 import）                             │
│  ├─ presentation：LearningEngine / PlaybackOrchestrator / ImportEngine /  │
│  │                AchievementEngine（暴露 StateFlow，UI 无关）             │
│  ├─ domain：模型（DOMAIN_MODEL）+ Repository 端口 + 领域服务                 │
│  ├─ data：SQLDelight 实现（DATABASE_SCHEMA）+ Repository 实现               │
│  └─ speech：CommandParser（纯 Kotlin）                                      │
│  androidMain / iosMain：平台实现（每源集一个 Koin module）                  │
│  commonTest：全部引擎单元测试（JVM 可跑，无需模拟器）                         │
└──────────────────────────────────────────────────────────────────────────┘
                            │ 未来
┌─ iosApp（SwiftUI，后续阶段） ───────────────────────────────────────────────┐
│  依赖 shared 产出的 XCFramework（Swift Package 集成）                       │
│  actuals：AVSpeechSynthesizer / SFSpeechRecognizer / AVPlayer / NSqliteDriver│
└───────────────────────────────────────────────────────────────────────────┘
```

**数据流**：UI 事件 → ViewModel → 引擎方法 → 引擎驱动（注入的）平台端口 → 状态经 StateFlow 回 UI。引擎自驱循环播放，不依赖 UI 存活（后台/熄屏仍可续播的潜力保留）。

## 2. 技术选型决策（ADR 摘要）

| ADR | 决策 | 理由 | 代价 |
|---|---|---|---|
| ADR-01 | **KMP**（非 Flutter/RN） | 核心逻辑 Kotlin 单语言共享；UI 按用户要求 Compose + SwiftUI 原生体验 | 需要 Gradle KMP 构建配置 |
| ADR-02 | **SQLDelight**（非 Room） | 一套 `.sq` schema 同时生成 Android/iOS 代码；KMP 成熟度高 | SQL 手写（换来全平台一致） |
| ADR-03 | 平台能力**接口注入**（非到处 expect/actual） | commonMain 定义端口接口；androidMain/iosMain 提供实现 + 各自 Koin module。expect/actual 仅限真正编译期差异（v1 几乎为零） | iOS 需实现同一批接口（即平台本分） |
| ADR-04 | 引擎纯 Kotlin：**学习引擎/播放编排/导入/勋章/命令解析** 全在 commonMain | 全部可在 Windows 上 JVM 单测；iOS 零重写 | 平台差异必须显式收敛为端口 |
| ADR-05 | **Koin** DI | KMP 全支持；Ktor 生态标准 | 非 Android 官方（可接受） |
| ADR-06 | 状态：引擎暴露 **StateFlow**；AndroidX **ViewModel 只放 app 层** | iOS 无 ViewModel 概念，核心状态不绑定 Android 生命周期 | app 层做一层薄委托 |
| ADR-07 | **Long 自增 ID** | 无同步需求；简单高效 | 引入云同步需迁移 UUID（单列 RFC） |
| ADR-08 | 语音**命令窗口**式识别（非连续监听） | PROJECT_SPEC FR-12：防 TTS 自识别 + 省电 + 隐私 | 无法在 TTS 播放中打断（v1 明确不做） |
| ADR-09 | TTS 无 seek → **Segment 级恢复** | Android TTS 无暂停位移 API；满足"不从头重播整词"（FR-11） | TTS 段恢复会重读当前段（已接受并写入规格） |
| ADR-10 | iOS 用 **SwiftUI**（非 Compose Multiplatform） | 用户明确要求；原生体验 | 两套 UI，核心仍共享 |

## 3. 版本矩阵（Phase 1 锁定具体小版本）

| 组件 | 版本基线 | 备注 |
|---|---|---|
| Kotlin | 2.x | KMP 稳定 |
| AGP / Gradle | 8.x / 8.x（wrapper） | Phase 1 按当时稳定版锁定 |
| Compose BOM | 最新稳定 | Material 3 |
| SQLDelight | 2.x | schema 见 DATABASE_SCHEMA |
| Koin | 4.x | |
| kotlinx-coroutines / serialization / datetime | 最新稳定 | |
| Media3 | 1.x | 仅 app 模块 |
| detekt + 自定义 import 规则 | 1.23+ | 边界守护（§6） |
| kotlin.test / Turbine / coroutines-test | 最新稳定 | commonTest |

## 4. 模块边界铁律（跨平台边界）

1. `shared/commonMain` **禁止** import：`android.*`、`java.*`、`com.apple.*`、任何 `expect` 泄漏到 public API。
   - 允许依赖：kotlin stdlib、kotlinx-*、SQLDelight runtime、Koin core。
2. 平台能力**唯一入口**是 commonMain 中定义的端口接口（§5）。引擎只见接口。
3. `app` 模块（及未来 `iosApp`）**禁止**出现：业务规则、领域模型逻辑、状态机。UI 层只做「渲染状态 + 转发意图」。
4. 依赖方向单向：`UI → presentation → domain ← data`。domain 不依赖任何上层。
5. 时间（`Instant`）经由注入的 `Clock`（kotlinx-datetime）获取，引擎不直接取系统时间——保证可测性。
6. 所有随机/时序注入抽象化（引擎测试需要确定性行为）。

**执行机制**（Phase 1 起）：detekt `ForbiddenImport` 规则对 `shared/src/commonMain` 强制 fail 构建；shared 模块开启 `explicitApiMode`；TEST_PLAN TC-ARCH-01/02 架构守护测试。

## 5. 平台端口接口（commonMain 定义，平台实现注入）

```kotlin
// speech synthesis —— TTS
interface SpeechSynthesizer {
    val readiness: StateFlow<Readiness>                       // Ready / Initializing / Unavailable
    suspend fun speak(request: SpeakRequest): SegmentResult  // 播完返回（request 含 utteranceId/text/lang/rate/pitch）
    fun stop()                                                // 立即停止（Pause/Exit 用）
}

// file audio —— 例句原声
interface AudioPlayer {
    suspend fun prepare(track: TrackDescriptor)               // audioUri 等
    suspend fun playAt(offsetMs: Long = 0): SegmentResult     // 支持 seek（Resume 精确恢复）
    fun pause(): Long                                         // 返回当前 offsetMs
    fun stop()
    val progressMs: StateFlow<Long?>
}

// 语音命令识别 —— 只在命令窗口内被调用
interface SpeechCommandRecognizer {
    suspend fun listenOnce(windowMs: Long): RecognitionResult // Hit(text)/Timeout/Unavailable
    val isAvailable: StateFlow<Boolean>
}

// 存储驱动
interface DatabaseDriverFactoryProvider { fun create(): SqlDriver }

// 日志
interface LogSink { fun log(level: LogLevel, tag: String, message: String, error: Throwable? = null) }
```

平台实现矩阵：

| 端口 | Android actual | iOS actual |
|---|---|---|
| `SpeechSynthesizer` | `android.speech.tts.TextToSpeech` + `UtteranceProgressListener` | `AVSpeechSynthesizer` |
| `AudioPlayer` | Media3 `ExoPlayer` | `AVPlayer` |
| `SpeechCommandRecognizer` | `SpeechRecognizer`（`EXTRA_PREFER_OFFLINE`，NFR-4） | `SFSpeechRecognizer` |
| `DatabaseDriverFactoryProvider` | `AndroidSqliteDriver` | `NSqliteDriver` |
| `LogSink` | `android.util.Log` | `os_log` |

## 6. 分层职责与降级策略（错误处理）

| 故障 | 策略（降级矩阵） |
|---|---|
| TTS 不可用（引擎缺失/初始化失败） | 阻止开始会话 + 引导安装语音数据；已有会话安全终止 |
| 例句无 `audioUri` / 音频加载失败 | **TTS 朗读 sentence 兜底**（FR-3），记日志 |
| SpeechRecognizer 不可用（权限拒绝/无引擎） | 会话继续，命令窗口退化为"手动会了按钮"（NFR-8） |
| 识别到未知文本 | 忽略并保持监听直到窗口超时（不误杀） |
| 数据库迁移失败 | 拒绝启动 + 上报日志（不静默清库） |
| 导入取消/失败 | 事务回滚（IMPORT_SPEC §6），无半成品 |

错误传播：data 层异常包装为 `RepositoryException`；端口层异常包装为领域错误（`AudioPortError`）；引擎将错误归入 `EngineState.Error(recoverable: Boolean)` 并继续可恢复运行——**引擎永不裸抛平台异常给 UI**。

## 7. 并发模型

- 引擎持有注入的 `CoroutineScope`（SupervisorJob + 派发器注入，测试用 TestDispatcher）；
- 播放循环 = 顺序协程（`for` 驱动 segment 序列），暂停 = `Mutex/Channel` 控制，禁止线程 sleep 轮询；
- SQLDelight 查询经 `.asFlow()` 暴露响应式数据（列表页自动刷新）；
- UI 层（Compose）只 collect `StateFlow`，不在 UI 线程做任何引擎逻辑。

## 8. iOS 复用策略（验证"零核心重写"）

1. **产物**：`shared` 编译 `iosArm64/iosSimulatorArm64` → XCFramework → 本地 Swift Package；`iosApp` 以 SPM 依赖引入。
2. **Flow 桥接**：kotlinx StateFlow → SwiftUI：KMP-NativeCoroutines（或自写 `AsyncStream` 包装器，二选一，iOS 阶段定夺）。
3. **iOS 要写的全部内容**（= 平台本分，非"重写"）：
   - 5 个 actual 类（§5 矩阵）；
   - SwiftUI 各界面（与 Android Compose 平级的工作量）；
   - iOS Koin module。
4. **零重写保证**：commonTest 同一套测试在 `iosSimulatorArm64` 全量运行（macOS CI）——引擎行为在 iOS 二进制上被同一断言验证。
5. **当前缺口**：开发机为 Windows——iOS 构建依赖 macOS（实体 Mac 或 CI macOS runner），见 ROADMAP Phase-ios 与 PHASE_0_REPORT 风险 R2。

## 9. 架构守护清单（每 Phase 验收必查）

- [ ] commonMain 无 `android.*` / `java.*` import（detekt 绿）
- [ ] 引擎无平台类型泄漏（public API 全显式声明）
- [ ] 新功能先落 domain/端口，再落 data/actual
- [ ] commonTest 覆盖新增引擎行为（无需模拟器即可在 Windows CI 跑）
- [ ] app 模块 diff 不含状态机/业务规则
- [ ] DB 变更走迁移文件 + 迁移测试

---

| 版本 | 日期 | 变更 |
|---|---|---|
| 1.0 | 2026-09-01 | Phase 0 初版 |
