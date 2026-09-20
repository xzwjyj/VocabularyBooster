# 端内实时神经 TTS 依赖（sherpa-onnx + Piper VITS，FR-23 v2）

`fetch_deps.py` 一次性下载（官方 GitHub release，`cache/` 幂等跳过；**产物均不入 git**）：

| 产物 | 去向 | 来源 |
|---|---|---|
| `libsherpa-onnx-jni.so` 等 4 个 `.so`（arm64-v8a，~32MB） | `shared/src/androidMain/jniLibs/arm64-v8a/` | release tag `v1.13.8` 的 `sherpa-onnx-v1.13.8-android.tar.bz2` |
| `en_US-lessac-medium.onnx`（63MB）+ `tokens.txt` | `app/src/main/assets/tts/piper/en_US/` | release tag `tts-models` |
| `en_GB-alan-medium.onnx`（63MB）+ `tokens.txt` | `app/src/main/assets/tts/piper/en_GB/` | 同上 |
| `espeak-ng-data/`（355 文件） | `app/src/main/assets/tts/piper/espeak-ng-data/`（**共享一份**） | 两模型包逐文件 sha256 比对一致（2026-09-20 实测） |

## 约定

- **换英音 voice**：改 `fetch_deps.py` 的 `EN_GB_VOICE`（alan → alba/cori/semaine 等，见 tts-models release 列表）重跑，并同步 `SherpaOnnxSpeechSynthesizer` 的模型文件名常量。
- **espeak-ng-data 布局**：当前共享一份于 `assets/tts/piper/espeak-ng-data`；脚本若日后判定两包不一致会自动改为各自随包并打印告警——届时须同步 `SherpaOnnxSpeechSynthesizer.dataDir` 常量再打包。
- **升级 sherpa-onnx**：改 `SHERPA_VERSION` 重跑，并把上游 `sherpa-onnx/kotlin-api/Tts.kt`（vendored 于 `shared/androidMain/kotlin/com/k2fsa/sherpa/onnx/Tts.kt`）替换为对应版本后全门禁回归。
- 许可：sherpa-onnx（Apache-2.0）、piper-voices 声音模型（各包 LICENSE/MODEL_CARD，随 tarball 在 `cache/` 可查）。
- 模型经 `OfflineTts(assetManager, config)` 从 assets 直读（不解包落盘）；合成结果磁盘缓存在应用 `filesDir/tts-cache`（运行时生成，非本目录职责）。

## 预录管线（历史）

`tools/tts/`（前 5000 高频词 Piper 构建期预录 OGG）已被本方案取代（FR-23 v2 用户裁决 2026-09-20：
预录覆盖错位且无法覆盖任意例句）——管线保留归档，**不再随包**；其 `cache/` 可清理（约 260MB）。

```bash
python tools/tts-neural/fetch_deps.py   # 前置：curl 可达 github release（--ssl-no-revoke）
```
