# SPEC_CHANGE_REQUEST_PIPER_TTS — 端内实时神经 TTS（sherpa-onnx + Piper VITS）

**日期**：2026-09-20（v2 重写：方案由「构建期预录」转向「端内实时合成」，用户当日裁决）
**状态**：待批准（v1 预录方案已实现未提交，本请求取代之）

## 1. 用户需求（原始）

- 「播放语音太机械，且不支持英音」——自然度不足 + 设备（vivo）无英音语音包。
- 装机验证 v1 预录方案后反馈「听起来和之前完全没有区别」。

## 2. v1 预录方案复盘（为什么作废）

v1（构建期 Piper 预录前 5000 高频词 OGG 随包）已实现但**未提交**，装机无感知差异，根因经数据核实：

1. **覆盖错位**：预录词表 = 语料频次 top-5000（the/and/is 级基础词），而种子词 59 个中频学术词仅命中 10 个（abandon/apply/brief/capture/genuine/obvious/remarkable/significant/subject/vital）——绝大多数词照旧走系统 TTS；
2. **覆盖面结构性不足**：即使词表对齐，也只有 PRONUNCIATION 短段用预录音；占聆听时长大头的 EN 释义段与例句段（机械感主要来源）永远走系统 TTS。预录**无法覆盖任意例句**，该路线对「自然度」问题的天花板过低。

**用户裁决（2026-09-20，AskUserQuestion）**：
1. **批准实时合成**（取代预录成为主线）：sherpa-onnx + en_US/en_GB 双 Piper 模型端内实时合成，任意词 + EN 释义 + 例句全自然音，英音 = 第二模型即答；合成结果磁盘缓存；中文段暂留系统 TTS；APK 196→约 300MB。
2. **撤掉**已生成的 4999 词预录资产（42MB）：全部走实时合成 + 缓存，逻辑更简单。

## 3. 方案（v2：端内实时合成）

### 3.1 技术载体（已核实，k2-fsa/sherpa-onnx v1.13.8，Apache-2.0）

- **Kotlin API**（`sherpa-onnx/kotlin-api/Tts.kt`，包 `com.k2fsa.sherpa.onnx`）：
  `OfflineTts(assetManager, OfflineTtsConfig)` —— **从 assets 直读模型（不解包）**；
  `generateWithCallback(text, sid, speed) { chunk -> Int }` —— 分句流式产出 PCM 块（边合成边播），
  **回调返回非零即中止生成**（天然的 stop 语义）；`GeneratedAudio(samples, sampleRate)`；
  `OfflineTtsVitsModelConfig(model, lexicon, tokens, dataDir)`；`release()`。
- **集成方式**：sherpa-onnx **无官方 Maven Central 工件**（已核实，仅第三方 wrapper）→ 仿官方 demo 与本项目 Vosk 先例：**Kotlin API 源文件按原样收入 `shared/androidMain`**（保留 Apache-2.0 头）+ **jniLibs `.so` 收入 `shared/src/androidMain/jniLibs/arm64-v8a/`**（取自官方 release `sherpa-onnx-v1.13.8-android.tar.bz2`，含 `libsherpa-onnx-jni.so`/`libonnxruntime.so`）。两者均 gitignore，由 `tools/tts-neural/fetch_deps.py` 下载（与 Vosk 模型、ECDICT 资产同策略：大文件不入 git、脚本可再生）。
- **模型**（官方 tts-models release，已核实存在与体积）：
  - 美音：`vits-piper-en_US-lessac-medium`（onnx 61MB）
  - 英音：`vits-piper-en_GB-alan-medium`（onnx ~61MB；不满意可换 alba/cori/semaine 等，改常量重跑 fetch 即可）
  - 每包含 `tokens.txt` + `espeak-ng-data`（~14MB，3.8K 文件；两包内容若 diff 一致则共享一份）
- **实时性**：官方 RTF 基准 = 树莓派4 @4线程 0.357 → 现代手机（vivo）显著低于 0.2，短段合成延迟亚秒级，流式回调进一步压首响时间。

### 3.2 架构（SpeechSynthesizer 端口零改动）

```
SpeechSynthesizer (commonMain 端口，不变)
    ↑
LangRoutedSpeechSynthesizer (commonMain 新增，纯 Kotlin 可 JVM 单测；先例：端口路由属共享逻辑)
    ├── EN_US / EN_GB → SherpaOnnxSpeechSynthesizer (androidMain 新 actual)
    │     ├── OfflineTts(assetManager) —— 模型按口音懒加载单实例，换口音 = 换模型（先载新后释放旧）
    │     ├── generateWithCallback → AudioTrack 流式播放（回调返回非零 = stop 中止）
    │     ├── WAV 磁盘缓存（filesDir/tts-cache，key=口音|文本|语速，LRU 上限 100MB）
    │     └── 音频焦点：播放前 gain，焦点丢失 → stop（编排器 Paused 语义同现状）
    ├── ZH_CN → TtsSpeechSynthesizer（既有系统 actual，不动）
    └── 降级：neural 初始化失败/加载超时 → EN 段当场回退系统 TTS（记日志，进程内粘滞）
```

- **FR-22 口音正交保持**：编排器消费时映射（ADR-010）不变——段 lang=EN_GB 到达路由器即选英音模型；**vivo 无厂商英音包也生效**（根需求解决）。FR-19 音色：EN 侧 `availableVoices` 返回当前口音的神经 voice 单条（id=模型名），设置页兼容不冲突；ZH 侧照旧系统音色。
- **rate/pitch**：`rate` → VITS `speed`（clamp 0.5–2.0）；`pitch` VITS 无参数，**不生效**（平台限制，文档注明；ADR 先例：ADR-09 TTS 段重读同类）。
- **Pause/Resume 语义不变**（ADR-09）：TTS 段暂停 = stop，恢复 = 重读整段——神经段沿用，不引入新语义。
- ** SPELLING/数字/缩写**：espeak-ng 音素化天然支持逐字母与常规文本。

### 3.3 预录链路回退（撤掉）

| 动作 | 对象 |
|---|---|
| git checkout 回退 | `SegmentBuilder.kt`、`SqlDelightPlaybackContentRepository.kt`、`Media3AudioPlayer.kt`、`DataModule.kt`、`AppModule.kt`（回退后再加路由装配） |
| 删除未跟踪文件 | `PronunciationAudioCatalog.kt`（commonMain）、`AssetsPronunciationAudioCatalog.kt`（androidMain） |
| 删除资产 | `app/src/main/assets/words/audio/`（4999 ogg + index.txt，42MB，本就 gitignore） |
| 回退测试 | `PlaybackContentRepositoryTest.kt`、`SegmentBuilderTest.kt` 的预录用例（TC-AE-30 重写为路由用例） |
| `.gitignore` | 预录条目改写为神经 TTS 条目（jniLibs `.so`、assets 模型、tools/tts-neural/cache） |

## 4. 影响范围

| 模块 | 变更 |
|---|---|
| shared/androidMain | +`com/k2fsa/sherpa/onnx/Tts.kt`（上游原样）；+`platform/SherpaOnnxSpeechSynthesizer.kt`；+jniLibs（gitignore） |
| shared/commonMain | +`speech/LangRoutedSpeechSynthesizer.kt`（纯 Kotlin） |
| shared commonTest | +路由用例（手写 Fake，Windows JVM 全量可跑） |
| app | DI 装配换路由；assets +模型（gitignore）；无 UI 改动 |
| tools | +`tts-neural/fetch_deps.py`；`tools/tts/` 预录管线保留归档（README 注明已被实时合成取代，不再随包） |
| 文档 | PROJECT_SPEC FR-23 重写（v1.19 未发布，原地改）；AUDIO_ENGINE_SPEC §8/§9；ARCHITECTURE §5；TEST_PLAN TC-AE-30 重写；DECISION_LOG ADR-011 |
| 体积 | APK 196.5 − 42（预录）+ ~130–145（双模型+共享 espeak-ng-data，onnx 压缩率低）+ ~13（.so arm64）≈ **290–310MB** |

## 5. 验收标准

1. **离线**：全程无网络（NFR-1）；
2. **自然度**：任意词的 PRONUNCIATION/SPELLING/EN 释义/EN 例句均为神经声（装机对比系统 TTS 可辨）；
3. **英音**：设置切英音后**下一英文段**即英音（消费时映射既有语义），vivo 无厂商英音包也生效；
4. **中文段**：照旧系统 TTS，行为不变；
5. **降级**：模型资产缺席/加载失败 → EN 段回退系统 TTS，会话不中断（无资产构建可全量系统 TTS 跑通）；
6. **性能**：模型后台预热（进程启动一次 ~1–3s，不阻塞 UI）；缓存命中间隙 < 100ms；冷合成段间隙满足 NFR-2 ≤ 500ms（目标 300ms）；
7. **缓存**：同文本+口音+语速二次播放走缓存；缓存上限 100MB LRU 淘汰；
8. **门禁**：`:shared:jvmTest` / `:shared:testDebugUnitTest` / detekt / checkPlatformBoundaries / `:app:assembleDebug` 全绿。

## 6. 风险与对策

| 风险 | 对策 |
|---|---|
| vivo 低端 SoC 合成偏慢 | 官方基准余量大（RPi4@4t RTF 0.357，手机远快）；numThreads=2 起步可调 4；流式回调压首响 |
| 双模型 RAM 峰值 | 单实例策略：同时最多加载一个英文模型，换口音先载新后释放旧 |
| espeak-ng-data 两包不一致 | fetch 脚本 diff 校验，不一致则各自随包（+14MB，APK 预算内） |
| APK 超 300MB 用户侧安装压力 | 后续可选 fp16 变体（模型减半 ~230MB，需 ARM v8.2+）或 int8（再小、音质降）——本批不做，验收后另议 |
| 上游 API 变动 | Tts.kt 原样 vendored + 版本号锁定 v1.13.8，升级=换文件重验 |
| pitch 不生效 | 文档明示（VITS 无基频参数），非回归——系统 TTS 的 pitch 本就弱感知 |

---

**请批准本规格（v2）与配套 `IMPLEMENTATION_PLAN_PIPER_RT_TTS.md` 后开始实现。**
