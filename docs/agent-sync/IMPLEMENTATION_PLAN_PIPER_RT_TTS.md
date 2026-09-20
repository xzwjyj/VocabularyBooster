# IMPLEMENTATION_PLAN_PIPER_RT_TTS — 端内实时神经 TTS 实现计划

**日期**：2026-09-20　**上游**：SPEC_CHANGE_REQUEST_PIPER_TTS.md（v2，待批准）
**范围**：预录链路全回退 + sherpa-onnx 实时合成落地 + 文档/测试同步。**零 schema 迁移**（不动 DB）。

---

## Step 0　回退预录链路（撤掉，全部未提交改动）

| # | 动作 |
|---|---|
| 0.1 | `git checkout -- shared/src/commonMain/kotlin/com/vocabularybooster/playback/SegmentBuilder.kt shared/src/commonMain/kotlin/com/vocabularybooster/data/SqlDelightPlaybackContentRepository.kt shared/src/androidMain/kotlin/com/vocabularybooster/platform/Media3AudioPlayer.kt shared/src/commonMain/kotlin/com/vocabularybooster/di/DataModule.kt app/src/main/kotlin/com/vocabularybooster/app/di/AppModule.kt shared/src/jvmTest/kotlin/com/vocabularybooster/PlaybackContentRepositoryTest.kt shared/src/jvmTest/kotlin/com/vocabularybooster/playback/SegmentBuilderTest.kt` |
| 0.2 | 删除未跟踪：`shared/src/commonMain/kotlin/com/vocabularybooster/playback/PronunciationAudioCatalog.kt`、`shared/src/androidMain/kotlin/com/vocabularybooster/platform/AssetsPronunciationAudioCatalog.kt` |
| 0.3 | 删除资产目录 `app/src/main/assets/words/audio/`（4999 ogg + index.txt，42MB；`words/` 若空一并删） |
| 0.4 | `.gitignore`：移除预录条目，新增神经 TTS 条目（见 Step 4） |
| 0.5 | 跑 `:shared:jvmTest` 确认回退后基线绿（SegmentBuilder/Repository 回到 HEAD 语义） |

## Step 1　依赖落地（tools/tts-neural/fetch_deps.py，均不入 git）

下载源（官方 GitHub release，curl `--ssl-no-revoke`）：
- `sherpa-onnx-v1.13.8-android.tar.bz2`（release tag `v1.13.8`）→ 解出 `jniLibs/arm64-v8a/*.so` 复制到 `shared/src/androidMain/jniLibs/arm64-v8a/`（`libsherpa-onnx-jni.so`、`libonnxruntime.so` 及同包其余依赖 .so）
- `vits-piper-en_US-lessac-medium.tar.bz2`、`vits-piper-en_GB-alan-medium.tar.bz2`（release tag `tts-models`）→ 解出到 `app/src/main/assets/tts/piper/`：
  - `en_US/en_US-lessac-medium.onnx` + `en_US/tokens.txt`
  - `en_GB/en_GB-alan-medium.onnx` + `en_GB/tokens.txt`
  - `espeak-ng-data/`：两包 `diff -r` 一致 → 共享一份于 `assets/tts/piper/espeak-ng-data`；不一致 → 各自 `en_XX/espeak-ng-data`（脚本自动判定并打印结果）
- 脚本幂等（存在即跳过）+ 打印各步落盘体积；附 `tools/tts-neural/README.md`（来源 URL、许可、再生步骤、换 voice 常量说明）
- `tools/tts/`（预录管线）保留归档：README 头部加「已被实时合成取代（FR-23 v2），不再随包」

## Step 2　vendored Kotlin API + 新 actual

| # | 文件 | 内容 |
|---|---|---|
| 2.1 | `shared/src/androidMain/kotlin/com/k2fsa/sherpa/onnx/Tts.kt` | 上游 v1.13.8 `sherpa-onnx/kotlin-api/Tts.kt` **原样复制**（Apache-2.0 头保留；不裁剪，便于升级 diff） |
| 2.2 | `shared/src/androidMain/kotlin/com/vocabularybooster/platform/SherpaOnnxSpeechSynthesizer.kt` | 新 actual，实现 `SpeechSynthesizer`（仅 EN_US/EN_GB；构造参数 `context: Context`）：<br>· **模型管理**：按段 lang 懒加载对应 `OfflineTts`（assetManager 直读，`numThreads=2`，vits 配置 model/tokens/dataDir 指向 `tts/piper/…`）；**同时最多一个英文模型**：换口音 = 先载新（后台）成功后 `release()` 旧；构造即后台预热美音模型（不阻塞主线程）<br>· **readiness**：预热成功 READY / 失败 UNAVAILABLE（加载失败进程内粘滞，交路由器降级）<br>· **speak()**：`Mutex` 串行（native 单实例非并发安全）→ 查缓存 → miss 则 `Dispatchers.IO` 单线程 `generateWithCallback`：PCM 块流式写 `AudioTrack`（22050Hz mono float→16bit），**回调内 stopRequested 返回 1 中止生成**；播放排空经协程 await；成功后异步写缓存；rate→speed clamp [0.5, 2.0]；pitch 忽略（文档注明）<br>· **stop()**：置 stopRequested + `AudioTrack.pause()/flush()` → 在途 speak 以 `completed=false` 收场（ADR-09：恢复重读整段，语义同系统 TTS 段）<br>· **音频焦点**：播放前 `AUDIOFOCUS_GAIN`，瞬时丢失 → stop（编排器 Paused，同 Media3 行为）；结束 abandon<br>· **缓存**：`filesDir/tts-cache/{sha256(accent\|text\|speed@0.05)}.wav`（16-bit PCM WAV 自写头，无编码器依赖）；LRU（mtime）上限 100MB，启动后台清点<br>· **availableVoices**：EN_US → `[TtsVoice("piper-en_US-lessac-medium","lessac·神经")]`、EN_GB → `[alan 同式]`；`release()` 释放模型与 AudioTrack |
| 2.3 | `shared/src/commonMain/kotlin/com/vocabularybooster/speech/LangRoutedSpeechSynthesizer.kt` | commonMain 纯 Kotlin 路由器（端口接口 + Lang 组合，无平台 import，JVM 可单测）：构造 `(neural: SpeechSynthesizer, system: SpeechSynthesizer)`<br>· **speak**：ZH_CN → system；EN_* → neural READY 则 neural，否则 system（降级记 `LogSink`，进程内粘滞标记）<br>· **stop**：双转发（幂等）<br>· **readiness**：system 的 readiness 原样投影（UI 行为不变；neural 状态内部持有）<br>· **availableVoices**：EN → neural READY 时神经 voice，否则 system；ZH → system |

## Step 3　DI 装配（AppModule.kt）

- `single { TtsSpeechSynthesizer(androidContext(), get()) }`（具名单例，供路由/降级）
- `single { SherpaOnnxSpeechSynthesizer(androidContext()) }`
- `single<SpeechSynthesizer> { LangRoutedSpeechSynthesizer(neural = get(), system = get()) }`
- DataModule（commonMain 手工图）不动：无 Context，天然只含系统无关逻辑——确认无 `SpeechSynthesizer` 装配点（回退后本就如此）

## Step 4　.gitignore / Gradle

- `.gitignore`：`shared/src/androidMain/jniLibs/`、`app/src/main/assets/tts/`、`tools/tts-neural/cache/`
- Gradle 零改动预期：vendored 源码随 androidMain 编译；jniLibs 走 `shared/src/androidMain/jniLibs` 源集（KMP androidTarget 映射 main→androidMain）。**若 AGP 未拾取**（Step 6 打包验证 `.so` 未入 APK）：在 `shared/build.gradle.kts` `android{ sourceSets }` 显式指 `jniLibs.srcDir("src/androidMain/jniLibs")`——唯一允许的 Gradle 触点

## Step 5　测试（commonTest 新增；预录用例已在 Step 0 回退）

- `LangRoutedSpeechSynthesizerTest`（手写 Fake 双引擎，commonTest → Windows JVM 全量 + Android 编译双跑）：
  1. EN_US/EN_GB → neural、ZH_CN → system（路由表）；
  2. neural UNAVAILABLE → EN 段落 system（降级一次、后续粘滞直连）、ZH 不受影响；
  3. neural READY → system 对 EN 零调用；
  4. stop() 双转发；
  5. availableVoices：neural READY 时 EN 取 neural、降级后取 system、ZH 恒 system；
  6. readiness 投影 = system。
- `SherpaOnnxSpeechSynthesizer` 本体 = 平台胶水（native + AudioTrack），不设 JVM 单测——vivo 装机走查覆盖（先例：Vosk actual 契约靠 androidTest/手动矩阵）。
- TEST_PLAN：TC-AE-30 重写为「路由与降级（commonTest）」；FR-23 验收行更新。

## Step 6　文档同步

| 文档 | 变更 |
|---|---|
| PROJECT_SPEC | FR-23 章重写（实时合成：架构/口音=模型/缓存/降级/pitch 限制/体积），版本记录 v1.19 行重写（未发布，原地改） |
| AUDIO_ENGINE_SPEC | §8 新增 SherpaOnnxSpeechSynthesizer 行 + 路由器行；§9 增「neural 不可用 → EN 段回退系统 TTS（会话不中断）」；移除 v1 预录条款（§1/§2/§8 words://、catalog 行）；版本记录 2.4 行重写 |
| ARCHITECTURE | §5 端口矩阵 SpeechSynthesizer Android actual 改「LangRouted（SherpaOnnx 神经 + 系统 TTS）」；移除 PronunciationAudioCatalog 端口行 |
| TEST_PLAN | TC-AE-30 重写 + FR 矩阵 FR-23 行 + 版本记录 2.20 行重写 |
| DECISION_LOG | ADR-011：实时神经合成取代预录（覆盖面根因）、单模型驻留、pitch 限制、缓存 100MB LRU、降级语义 |
| bug list.md | FR-23 状态行更新（不提交） |

## Step 7　门禁 + 装机验证

1. `export JAVA_HOME="C:/Users/zack/.jdks/jdk-21.0.12.1+1"`；
2. `./gradlew :shared:jvmTest :shared:testDebugUnitTest :shared:detekt :shared:checkPlatformBoundaries :app:assembleDebug` 全绿；
3. APK 体积核账（预期 ~290–310MB；aapt 确认 `.so` 与模型资产入包）；
4. vivo（serial 10AF712A80003U2）安装 + `pm grant com.vocabularybooster android.permission.RECORD_AUDIO`；
5. 用户装机验收：任意词（含非 top-5000，如 booster）发音/拼写/EN 释义/EN 例句均为神经声且段间隙可接受；切英音 → 下一英文段英音；中文段照旧；二播同段走缓存（间隙显著缩短）；杀进程重进预热正常；
6. 通过后 commit（feat(tts): FR-23 real-time neural TTS）+ 更新 bug list.md + 记忆文件。

## 回滚

全部新增均为未提交工作树内容：`git checkout` 5 个源文件 + 删未跟踪文件/目录即可回 HEAD；无 DB/规格发布风险（v1.19/2.20/ADR-011 均未提交）。
