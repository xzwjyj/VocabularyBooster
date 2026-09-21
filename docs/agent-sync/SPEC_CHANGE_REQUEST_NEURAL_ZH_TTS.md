# SPEC_CHANGE_REQUEST_NEURAL_ZH_TTS — 中文段端内实时神经 TTS（复用 FR-23 管线）

状态：已批准（2026-09-20，选型 kokoro int8-multi-lang-v1_1）；**v2 修订（同日晚）：装机人耳否决 → 预授权备胎切换 vits-melo-tts-zh_en（见文末修订记录）**　|　日期：2026-09-20　|　上游需求：用户「继续改善中文声音机械问题」　|　前置：FR-23 已交付（`e38c2a2`，EN 段神经化，vivo 验收通过）

## 1. 用户需求（原始）

中文段（释义中文、例句中文译文）目前走系统 TTS——vivo 系统中文语音机械感明显。要求改善为接近自然人声，与英文段（FR-23 Piper 神经音）体验对齐。

## 2. 现状审计（2026-09-20，全部本机核实）

- **路由现状**：`LangRoutedSpeechSynthesizer` ZH_CN 分支无条件转发系统 actual；EN_* 走 SherpaOnnx 神经引擎 + 进程内粘滞降级。
- **基建可复用面（零新增抽象）**：
  - vendored sherpa-onnx v1.13.8 Kotlin API 已含全部所需 config：`OfflineTtsVitsModelConfig(lexicon/dictDir/dataDir)` 与 `OfflineTtsKokoroModelConfig(model/voices/tokens/dataDir/lexicon/lang/dictDir)`；JNI 整对象传 config，**vendored 文件零改动**。
  - `SherpaOnnxSpeechSynthesizer` 已解决四类装机坑（espeak 解包 / 整段 generate / 16KB 缓冲+补静音 / IO 线程阻塞写），ZH 模型同管线直接受益；WAV 磁盘缓存、ADR-09 段语义、音频焦点全部语言无关。
  - 音色管道现成：设置键 `settings.ttsVoiceZh`（FR-19）+ actual speak 前读取应用 + `availableVoices(ZH_CN)` 驱动设置页——设置页 UI 零改动。
- **模型候选（GitHub tts-models release 实测体积，2026-09-20）**：

| 模型 | tar.bz2 | 质量口碑 | 音色 | 备注 |
|---|---|---|---|---|
| **kokoro-int8-multi-lang-v1_1** | **140.2MB** | 2025 StyleTTS2 系，社区公认开源端内 zh 第一梯队 | 多说话人（zh 男/女多枚，sid 选择） | 需要 espeak-ng-data + jieba dict（均随包）；推理略慢于 VITS |
| vits-melo-tts-zh_en | 159.3MB | 良好，略逊 kokoro | 单说话人 | VITS 路径（与 EN 同 config 形态）；最快 |
| vits-zh-hf-fanchen-C 等 | 113.8MB | fairseq 老模型，仍偏机械 | 多说话人 | 与「去机械感」目标冲突，排除 |
| matcha-icefall-zh-en | 75.4MB | prosody 平，机械感明显 | 单（baker 系） | 同上排除 |
| kokoro fp32 multi-lang v1_1 | 347.9MB | 同 int8 略优 | 同 int8 | 体积不可接受，排除 |

## 3. 方案

### 3.1 选型（推荐 A，B 为备胎）

- **A（推荐）：kokoro-int8-multi-lang-v1_1**——质量优先目标下的最优体积（140MB < melo 159MB）；多说话人 = 未来可暴露中文音色选择；int8 量化质量损失轻微。
- **B（备胎）：vits-melo-tts-zh_en**——若 A 装机听感/速度不达标，VITS 路径一行 config 差异即可替换（fetch_deps 层面切换，代码零改动）。
- **装机实证纪律（v1 预录教训）**：模型质量以装机人耳为准；A 不达标换 B 复验，管线不动。

### 3.2 架构（SpeechSynthesizer 端口与编排器零改动）

- `SherpaOnnxSpeechSynthesizer` 扩展为**双模型驻留**（EN 族一个 + ZH 一个；`modelFor` 从单槽改双槽 map，换载语义只在同族内保留）。当前「单模型驻留（双模型 RAM 不可接受）」裁决按族细化：跨族不再互斥。
- 受理范围扩为 EN_US / EN_GB / ZH_CN；ZH 模型 = kokoro int8（assets `tts/kokoro/`：model.int8.onnx + voices.bin + tokens.txt 直读；espeak-ng-data 与 jieba dict 目录树解包至 filesDir，复用 FR-23 解包模式，与 piper 的 espeak 各自独立目录）。
- **预热**：EN 与 ZH 并行预热（nativeDispatcher 扩为按模型独立串行队列；实测 piper 单模型 ~1s，预期双模型 ~1.5-2s）；readiness 语义不变（INITIALIZING/READY/UNAVAILABLE 整体口径，任一模型失败仅该语言降级）。
- **路由（LangRoutedSpeechSynthesizer 唯一改动点）**：ZH_CN 从无条件系统改为与 EN 同构的「READY 走神经，非 READY / speak 非取消异常 → 当场回退系统 + 进程内粘滞；取消重抛」。EN 路径零变化。
- **中文音色（sid）**：speak 前读 `settings.ttsVoiceZh` 映射 kokoro sid，缺省固定一枚女声（如 zf_xiaobei）；`availableVoices(ZH_CN)` 神经 READY 时返回精选 2-4 枚（男/女），降级后回系统列表——FR-19 设置页 UI 自动适配零改动。
- **rate**：kokoro `lengthScale` 生效；pitch 依旧不生效（无参数，与 EN 口径一致）。

### 3.3 依赖与体积

- `tools/tts-neural/fetch_deps.py` 扩展：+kokoro-int8-multi-lang-v1_1（模型/.so 均不入 git，再生原则不变）。
- **APK 324MB → ~465MB**（+140MB 模型资产）。
- **RAM**：双模型驻留，预期 app PSS ~243MB → ~400-450MB（装机实测确认；vivo 16GB 无压力，低端机由缓存回收兜底）。

## 4. 影响范围

| 层 | 变更 |
|---|---|
| shared/commonMain | `LangRoutedSpeechSynthesizer`（ZH 分支降级化）+ 其测试（+ZH 路由/降级/取消用例） |
| shared/androidMain | `SherpaOnnxSpeechSynthesizer`（双模型驻留/预热/kokoro config/zh 音色映射/独立解包目录）；vendored Tts.kt **零改动** |
| app | 零代码改动（DI 已传 settings；设置页零改动） |
| assets | +tts/kokoro/（fetch_deps 再生，不入 git） |
| 文档 | PROJECT_SPEC（FR-23 扩 ZH 或新 FR-24 待批裁定）+ AUDIO_ENGINE_SPEC §8 + ARCHITECTURE §5 + TEST_PLAN + DECISION_LOG（ADR-012） |
| schema | 零变更（音色键 FR-19 已存在） |

## 5. 验收标准（装机）

1. 中文段（释义/例句译文）神经自然语音；英文段音色零变化（piper 路不动）。
2. ZH 段首播延迟可接受（kokoro int8 4 线程 RTF 装机实测；同段二播命中缓存 ~0 间隙）。
3. 神经 ZH 失败（模型缺席模拟）→ 中文段当场回退系统 TTS，会话不中断，进程内粘滞。
4. 设置页中文音色出现神经音色枚举（精选 sid），切换后下一 ZH 段生效（L4 口径）。
5. 杀进程重进：双模型并行预热正常，首个 ZH 段不超时。
6. EN↔ZH 交替播放（词循环常态）模型驻留稳定无换载抖动；PSS 记录在案。

## 6. 风险与对策

| 风险 | 对策 |
|---|---|
| kokoro 装机听感不达预期 | 备胎 melo 一键替换（fetch_deps 层）；管线/代码零改动 |
| kokoro int8 中端机推理慢（首播 >2s） | WAV 缓存摊销（每段仅首次合成）；NUM_THREADS 装机调优；仍慢则换 melo |
| 双模型 RAM 峰值 | 装机 PSS 实测记录；超预期则 ZH 改懒加载（首段 +1-2s）或接受换载 |
| kokoro espeak/dict 与 piper espeak 版本不一致 | 各自独立解包目录，不共享不假设 |
| APK 465MB 分发体积 | 用户已知并接受（324MB 先例）；未来可选 Play Asset Delivery 拆分（另行立项） |

---

## 修订记录

### v2（2026-09-20 晚）：ZH 模型备胎切换 kokoro → melo

- **触发**：kokoro int8 装机人耳否决——修复装机坑 #5 后 kokoro 正常出声（diag：speakers=103 rate=24000 / 双预热 READY 2349ms / PSS 488MB），但用户判定中文听感「**还不如上一版**」（系统 TTS）。§7 风险表第 1 行预案 + ADR-012「听感不达标 → 换 melo 复验」预授权条款生效。
- **变更**：ZH 槽 = vits-melo-tts-zh_en（MyShell MeloTTS 官方转换，MIT；fp32 model 170.4MB——tarball 内 model.int8.onnx 为 git-lfs 指针桩不可用；44.1kHz；zh+en 混合 lexicon；jieba dict + date/number/phone ruleFsts + new_heteronym ruleFars）。
- **数据布局修正**：**整槽 newFromFile 全输入解包 filesDir `tts/melo-data/`**——原 §3「模型 / voices / tokens 单文件直读」被装机坑 #5 证伪（kokoro lexicon 逗号列表读取器在 assetManager 非空时一律走 assets 分支，绝对路径读失败 → native exit(-1) 拖崩全进程；该教训对 melo 的 lexicon 同样适用），ZH 槽统一不混用 assets 路径。
- **中文音色枚举收敛**：kokoro 4 sid → **单音色**（melo 单说话人，模型平台限制）；FR-19 键复用 / 未知值回缺省不变；§5 验收项「中文音色枚举切换」自然失效。
- **体积**：assets tts/kokoro 208.9MB → tts/melo 191.2MB；APK 469.6MB → 464.7MB。
- **零变化**：路由 / 每语言族降级 / 双槽驻留与并行预热 / WAV 缓存 / PSS 口径 / 端口契约。
- **melo 批门禁（2026-09-20 晚）**：jvmTest 41 类 315/0（ImportPerfTest 按机器级故障先例排除）+ testDebugUnitTest + detekt×2 + checkPlatformBoundaries + assembleDebug 全绿。
