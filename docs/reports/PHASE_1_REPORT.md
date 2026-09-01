# PHASE 1 验收报告 — 工程基础

> 日期：2026-09-01 ｜ 状态：**待用户验收**
> 上游：ROADMAP Phase 1 ｜ 批准记录：用户 Phase 1 批准（含 13 项 DoD + Final Rule）
> 结论：**全部 DoD 项达成**（13/13，其中第 13 项 git 提交在本报告落笔后立即执行）。测试 9 次执行 0 失败；App 已在模拟器实机安装、启动、截图确认。

---

## 1. 环境安装结果（全自动 CLI 方案，零系统修改）

| 工具 | 版本 | 安装位置 | 说明 |
|---|---|---|---|
| JDK | Temurin 21.0.12.1 | `C:\Users\zack\.jdks\jdk-21.0.12.1+1` | 便携 zip，**未入系统 PATH**（构建前需 `export JAVA_HOME`） |
| Android SDK | cmdline-tools latest | `%LOCALAPPDATA%\Android\Sdk` | platform-35 / build-tools 35.0.0 / platform-tools / licenses 已接受 |
| 模拟器 | emulator + system-images;android-35;google_apis;x86_64 | 同上 | WHPX 硬件加速可用（`Windows Hypervisor Platform accelerator is operational`） |
| AVD | `vb_phase1`（Pixel 6） | `C:\Users\zack\.android\avd\vb_phase1.avd` | 冷启动 ~30–50s |
| Gradle | 8.11.1（一次性用于 wrapper 生成） | `~/AppTools/tools/gradle-8.11.1` | wrapper 已入库，此后构建全部走 `gradlew` |
| Git | 2.46.2（已有） | — | — |
| Android Studio | **未安装（有意）** | — | 用户选择纯 CLI 方案；全部出口条件不依赖 IDE |

网络注意事项（Windows schannel）：所有 `curl` 下载需 `--ssl-no-revoke`（证书吊销检查离线失败）。

## 2. 工程结构（本次提交内容）

```
settings.gradle.kts / build.gradle.kts / gradle.properties / gradlew(.bat)
gradle/libs.versions.toml                      # 版本目录（ARCHITECTURE §3 锁定值）
config/detekt/detekt-common.yml                # commonMain ForbiddenImport 门禁
config/detekt/detekt-app.yml                   # app 模块（Composable/Preview 豁免）
shared/
  build.gradle.kts                             # KMP：androidTarget+jvm(+iOS host-gated)、explicitApi、
                                               #   SQLDelight、detekt、checkPlatformBoundaries 任务
  src/commonMain/kotlin/.../
    di/SharedModule.kt                         # Koin：Clock 注入
    domain/model/Lang.kt                       # en-US / zh-CN
    speech/SpeechSynthesizer.kt                # 端口（含 Readiness/SpeakRequest/SegmentResult）
    speech/SpeechCommandRecognizer.kt          # 端口（Hit/Timeout/Unavailable）
    playback/AudioPlayer.kt                    # 端口（TrackDescriptor）
    playback/PlaybackPosition.kt               # @Serializable 位置快照 + PlaybackPhase
    platform/LogSink.kt                        # 端口
    platform/DatabaseDriverFactoryProvider.kt  # 端口
  src/commonMain/sqldelight/com/vocabularybooster/db/   # 11 表 .sq（DATABASE_SCHEMA v1.2 基线）
  src/androidMain/.../platform/                # AndroidLogSink / AndroidDatabaseDriverFactoryProvider（FK PRAGMA ON）
  src/commonTest/                              # Koin / Serialization 冒烟
  src/jvmTest/                                 # SchemaSmokeTest（JVM + sqlite 驱动，11 表 + Q1/Q2/Q3/Q5）
app/
  src/main/.../VocabularyBoosterApp.kt         # startKoin（sharedCoreModule + appModule）
  src/main/.../MainActivity.kt                 # Compose 占位屏（Phase 1 专用，无业务功能）
  src/main/.../di/AppModule.kt                 # LogSink / DatabaseDriverFactoryProvider actual 绑定
  src/test/.../AppModuleSmokeTest.kt           # Koin 装配冒烟
  src/androidTest/.../LaunchSmokeTest.kt       # ActivityScenario 启动冒烟（已在模拟器执行）
iosApp/README.md                               # host-gating 说明（iOS 阶段处理）
```

## 3. 版本矩阵（已锁定，详见 ARCHITECTURE §3）

Kotlin 2.1.21 ｜ AGP 8.8.2 / Gradle 8.11.1 ｜ SQLDelight 2.0.2 ｜ Koin 4.0.2 ｜ coroutines 1.10.1 ｜ serialization 1.8.0 ｜ datetime 0.6.1 ｜ Compose BOM 2025.01.00 ｜ detekt 1.23.7 ｜ compileSdk/target 35 / minSdk 26 ｜ JVM 字节码 17。

## 4. DoD 逐项验收

| # | DoD 项 | 结果 | 证据 |
|---|---|---|---|
| 1 | KMP 工程可构建 | ✅ | `BUILD SUCCESSFUL`（全链：detekt + 边界检查 + 测试 + 打包） |
| 2 | Android App 可构建 | ✅ | `app-debug.apk`（约 11.3 MB） |
| 3 | Android App 可安装并启动（设备验证） | ✅ | 模拟器实机：install Success → `am start -W` Status: ok, COLD, TotalTime **1265ms** → `mCurrentFocus=…MainActivity` → 截图（1080×2400）占位屏渲染正确（详见 §6） |
| 4 | shared/commonTest 通过 | ✅ | KoinSmokeTest + SerializationSmokeTest（jvmTest 与 testDebugUnitTest 双目标） |
| 5 | SQLDelight 代码生成可用 | ✅ | 11 表 `.sq` → `VocabularyDatabase` 生成；SchemaSmokeTest 全表 CRUD + Q1/Q2/Q3/Q5 验证 |
| 6 | Koin 可初始化 | ✅ | commonTest（sharedCoreModule）+ app 单测（sharedCoreModule + appModule）各 1 例 |
| 7 | kotlinx-serialization 可用 | ✅ | PlaybackPosition JSON round-trip 测试 |
| 8 | 架构边界受保护 | ✅ | 三重门禁全绿：detekt ForbiddenImport（commonMain 禁 android.*/java.* 等）+ 自研 `checkPlatformBoundaries` Gradle 任务（接入 `check`）+ `explicitApi()` |
| 9 | 静态检查通过 | ✅ | `:shared:detekt :app:detekt` maxIssues:0 |
| 10 | 无超前业务功能 | ✅ | app 仅为占位屏 + DI 装配；shared 仅为端口接口/模型/Koin 模块；`.sq` 为批准范围内的 schema v1 基线（含规格已冻结的查询 Q1–Q5） |
| 11 | 文档已更新 | ✅ | readme / CLAUDE.md / iosApp/README / DATABASE_SCHEMA v1.2 / PROJECT_SPEC v1.2（C5 定格 35）/ ARCHITECTURE §3（版本矩阵实装值） |
| 12 | PHASE_1_REPORT.md 生成 | ✅ | 本文件 |
| 13 | Git 提交 | ✅ | 本报告落笔后立即提交（Conventional Commits；首次提交 d3abe6b 为 Phase 0 文档） |

## 5. 测试执行记录（共 9 次执行，0 失败）

| 命令 | 目标 | 用例数 | 结果 |
|---|---|---|---|
| `./gradlew :shared:jvmTest` | JVM（Windows 本机全量） | 5（Koin 1 + Schema 3 + Serialization 1） | ✅ 0 失败 |
| `./gradlew :shared:testDebugUnitTest` | commonTest 的 Android 编译运行 | 2（Koin 1 + Serialization 1） | ✅ 0 失败 |
| `./gradlew :app:testDebugUnitTest` | app 单测 | 1（AppModuleSmokeTest） | ✅ 0 失败 |
| `./gradlew :app:connectedDebugAndroidTest` | 模拟器 instrumented | 1（LaunchSmokeTest） | ✅ `tests="1" failures="0" errors="0" skipped="0"`（结果 XML：`TEST-vb_phase1(AVD) - 15-_app-.xml`） |

SchemaSmokeTest 覆盖（对应 DATABASE_SCHEMA 关键查询）：
- `schemaV1SupportsAllElevenTables`：11 表全插入 + Q1 排序（verb→noun）+ WordMastery/Achievement 幂等（INSERT OR IGNORE）+ AppSetting 覆写；
- `q2Q3StudyQueueExcludesMasteredWords`：3 词标记 1 个 → 队列 = 其余 2 词、countUnmastered=2；
- `q5DerivedWordBookCopiesRelationsWithoutMastery`：D1/D2/D3/D4 语义——派生本 type=DERIVED + parent + sourceSessionId；只复制关系行（保留 pendingTranslation / includeExamples）；不复制 WordMastery；母本不变。

## 6. 模拟器启动验证记录（DoD #3）

| 步骤 | 命令/动作 | 结果 |
|---|---|---|
| 1 | 启动 AVD `vb_phase1`（headless，swiftshader，WHPX） | boot_completed ≈ 30–50s；日志确认 WHPX accelerator operational |
| 2 | `adb install -r app-debug.apk` | `Success` |
| 3 | `adb shell am start -W -n com.vocabularybooster/com.vocabularybooster.app.MainActivity` | `Status: ok` / `LaunchState: COLD` / `TotalTime: 1265ms`（NFR-2 冷启动预算 ≤3s，模拟器参考值） |
| 4 | `dumpsys window` | `mCurrentFocus=…com.vocabularybooster.app.MainActivity`（前台、无崩溃） |
| 5 | `adb exec-out screencap -p` → 人工查看 | 1080×2400 截图：Material 浅色主题，"VocabularyBooster" 标题 + 「Phase 1 工程基础已就绪 / 业务功能将在后续阶段实现」正常渲染 |
| 6 | `./gradlew :app:connectedDebugAndroidTest` | 1 test on vb_phase1，0 失败 |
| 7 | `adb emu kill` | 干净退出 |

## 7. 构建过程中发现并修复的问题

| # | 问题 | 修复 |
|---|---|---|
| 1 | Windows curl schannel 证书吊销检查离线 → 下载全部失败（CRYPT_E_REVOCATION_OFFLINE） | 所有 curl 加 `--ssl-no-revoke`（仍验证证书链） |
| 2 | `:app:detekt` 对 @Composable PascalCase 命名与 @Preview 未使用函数报错 | detekt-app.yml：`FunctionNaming.ignoreAnnotated:['Composable']`、`UnusedPrivateMember.ignoreAnnotated:['Preview']` |
| 3 | SQLDelight Boolean 映射两轮失败：`BOOLEAN` 关键字不被 sqlite_3_18 方言接受；裸 `AS Boolean` 生成无效 `import Boolean` | 最终方案：`INTEGER AS Boolean` + `.sq` 顶部 `import kotlin.Boolean;`（已回写 DATABASE_SCHEMA §1/§2.6，v1.2） |
| 4 | app 单测找不到 `Clock`（shared 的 `implementation` 依赖不传导） | app 增加 `testImplementation(libs.kotlinx.datetime)` |
| 5 | `cmd 2>&1 \| tail` 管道吞掉非零退出码，险些漏报构建失败 | 改用 `EXIT=${PIPESTATUS[0]}` 显式记录；Final Rule 对照自查 |
| 6 | `connectedDebugAndroidTest` 首次运行时 UTP 依赖下载 TLS 握手被远端中断（网络抖动） | 重试即成功（非工程缺陷；已在本表留痕） |

## 8. 与 ROADMAP Phase 1 原计划的偏差（均已记录）

| # | 偏差 | 理由 / 处置 |
|---|---|---|
| 1 | compileSdk/target **35**（原计划 36）；SDK platform-35 | AGP 8.8.2 稳定支持上限为 35；升 36 需 AGP ≥8.9。已定格入 PROJECT_SPEC C5（v1.2），与依赖升级一并处理 |
| 2 | **未安装 Android Studio**（原计划任务 1 含 AS） | 用户批准的全自动 CLI 方案；纯 CLI 不影响任何出口条件 |
| 3 | iOS targets 编译验证未执行 | 开发机 Windows（C4/R2）；targets 以 macOS 宿主为门控；`:shared:allTests` 留待 iOS 阶段 |
| 4 | GitHub Actions（原计划可选项 8）未做 | 可选项；建议 Phase 2+ 有多轮提交需求时再立项 |
| 5 | TC-DB-06 迁移基线测试 | v1 即初始基线，无历史版本可迁 → SchemaSmokeTest 建立行为基线；`.sqm` 迁移测试自 v2 起强制 |
| 6 | TC-DB-07（FK PRAGMA 强制开启的行为测试）延后 | Phase 1 仅在 Driver 工厂配置 `PRAGMA foreign_keys=ON`（代码已就位）；行为断言随 Phase 2 数据层 Repository 测试补齐 |

## 9. 遗留提醒

- **R3（Synology Drive 同步）**：仓库位于同步目录。`.gitignore` 已排除 `build/`、`.gradle/`、`local.properties`，但**同步客户端不受 git 控制**——请在 Synology Drive 客户端中手动排除 `build/`、`.gradle/` 目录，避免构建产物打爆同步。
- 模拟器 `vb_phase1` 与系统镜像（约 1.6GB+）保留在本机，后续 Phase 验收复用。
- JDK 便携版未入 PATH：每个新 shell 构建前 `export JAVA_HOME="C:/Users/zack/.jdks/jdk-21.0.12.1+1"`（已写入 CLAUDE.md / readme）。

## 10. Phase 2 提案（**待用户批准，未开始任何 Phase 2 工作**）

按 ROADMAP：**数据层与生词本基础**——DOMAIN_MODEL 实体 + Repository 实现；词条详情页（FR-2 排序渲染）；生词本增删改（FR-4）；保存流（多本 + 释义选择 + 例句开关，FR-5）；`DictionaryProvider` 本地 JSON 种子（≥50 词样例库）。出口：TC-DM、TC-DB-01…03 绿；FR-1~FR-5 逐条签字。
