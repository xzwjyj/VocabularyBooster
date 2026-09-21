# IMPLEMENTATION_PLAN_NEURAL_ZH_TTS — 中文段端内实时神经 TTS（kokoro int8）

上游：SPEC_CHANGE_REQUEST_NEURAL_ZH_TTS.md（用户已批准：模型选型 kokoro-int8-multi-lang-v1_1）　|　日期：2026-09-20

前置事实（已核实）：vendored Tts.kt v1.13.8 含 `OfflineTtsKokoroModelConfig` 零改动可用；音色键 `settings.ttsVoiceZh`（FR-19）现成；WAV 缓存 / ADR-09 / 16KB 缓冲+补静音 / IO 线程 / 降级骨架全部语言无关复用。

## Step 1：fetch_deps.py 扩展 + 模型入库

- 下载 `kokoro-int8-multi-lang-v1_1.tar.bz2`（140.2MB）解包至缓存；核对文件清单（预期 model.int8.onnx / voices.bin / tokens.txt / espeak-ng-data/ / dict/）。
- 产出入 `app/src/main/assets/tts/kokoro/`：单文件（model/voices/tokens）直接放；espeak-ng-data 与 dict 目录树放 assets（运行时解包 filesDir）。
- .gitignore 已覆盖 `app/src/main/assets/tts/`（整目录），零改动。
- 出口：assets 就位，`assembleDebug` 产出 APK ~465MB。

## Step 2：SherpaOnnxSpeechSynthesizer 双模型驻留 + kokoro

- 受理范围 +ZH_CN；`modelFor` 单槽 → 双槽（EN 族槽 + ZH 槽，各自换载语义）。
- nativeDispatcher 按模型独立串行（或双 dispatcher）——EN 与 ZH 合成互不阻塞（同会话交替段）；预热并行，readiness 整体口径不变（任一失败仅该语言降级 → readiness 投影逻辑：路由器按语言查引擎状态，见 Step 3）。
- kokoro config：`OfflineTtsKokoroModelConfig(model/voices/tokens assets 直读, dataDir=解包 espeak, dictDir=解包 dict)`；`generate(text, sid, speed)` 走 sid。
- ZH 音色：`availableVoices(ZH_CN)` 返回精选 sid 枚举（拟 zf_xiaobei 女 / zm_yunjian 男 等 2-4 枚，名称以 voices 实测为准）；speak 前读 `settings.ttsVoiceZh` 映射 sid，缺省第一枚。
- 解包目录：`filesDir/tts/kokoro-espeak-ng-data` 与 `filesDir/tts/kokoro-dict`（与 piper espeak 不共享，完成标记复用 `.complete` 模式）。
- diag 扩展：speak 行带 engine=vits|kokoro。

## Step 3：LangRoutedSpeechSynthesizer ZH 降级化

- ZH_CN 分支从无条件系统 → 与 EN 同构：READY 走神经，非 READY / speak 非取消异常 → 当场回退系统 + 进程内粘滞；取消重抛。
- 神经 readiness 需按语言粒度：`SherpaOnnxSpeechSynthesizer.readiness` 整体口径保留（两模型任一 READY 即 READY；两全败才 UNAVAILABLE），路由器 ZH 分支尝试神经后按异常降级（非 READY 时 ZH 也先走系统，同 EN INITIALIZING 行为）。
- commonTest +ZH 路由组：ZH 神经成功 / ZH 非降级异常回退粘滞 / ZH 取消重抛 / EN 行为零回归。

## Step 4：文档五件套

- PROJECT_SPEC：新 **FR-24 中文段端内实时神经 TTS**（FR-23 已验收封板不扩 scope）+ 版本行；AUDIO_ENGINE_SPEC §8 双模型行 + 路由段更新 + 版本行；ARCHITECTURE §5 矩阵 ZH 路径更新；TEST_PLAN +TC-AE-31 组；DECISION_LOG **ADR-012**（kokoro int8 选型 / 双模型驻留裁决细化 / melo 备胎 / zh 音色 sid 口径）。

## Step 5：门禁 + vivo 装机验收

- 门禁：`:shared:jvmTest`（含新路由组）/ `:shared:testDebugUnitTest` / 双 detekt / checkPlatformBoundaries / assembleDebug。
- vivo 验收（SCR §5 六项）：中文段自然语音、英文零变化、首播延迟实测（kokoro RTF）、降级模拟、中文音色枚举切换、杀进程双预热、PSS 记录。
- 用户人耳确认后 commit `feat(tts): FR-24 neural Chinese TTS`。

## 风险闸门（实现中遇阻即停上报）

- kokoro tarball 文件清单与预期不符（无 dict / voices 命名不同）→ 按实际调整 Step 1/2，不影响架构。
- 装机 kokoro 首播 >2s 或听感不达标 → 触发 SCR 备胎 melo（fetch_deps 切换，代码零改动）复验。

---

## 执行修订（2026-09-20 晚）：kokoro 方案全程走完后人耳否决 → melo 备胎切换

- kokoro 方案 Step 1–5 已全部执行：门禁全绿、装机坑 #5 修复（ZH 槽整槽 newFromFile——kokoro lexicon 逗号列表读取器 assetManager 非空一律走 assets 分支，绝对路径读失败 → native exit(-1) 拖崩全进程）、kokoro 正常出声（speakers=103 / 双预热 READY 2349ms / PSS 488MB）。
- **用户人耳判定否决**（「还不如上一版」）→ 触发上文风险闸门第 2 条 + ADR-012 备胎条款，ZH 模型切换 **vits-melo-tts-zh_en** 复验（细节见 SCR v2 修订记录）。
- melo 批实测：assets `tts/melo` 191.2MB、APK 464.7MB、jvmTest 41 类 315/0（ImportPerfTest 按机器级故障先例排除，证据在「bug list.md」）+ testDebugUnitTest + detekt×2 + checkPlatformBoundaries + assembleDebug 全绿；中文音色枚举收敛为单音色（模型单说话人）。
- 本文其余内容为 kokoro 方案历史记录（含数据布局描述，已被 melo 批整槽 newFromFile 取代）。
