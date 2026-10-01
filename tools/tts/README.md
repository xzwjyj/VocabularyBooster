# 预录单词发音（Piper 管线，FR-23 批次）

自然朗读的单词发音：构建期用 [Piper](https://github.com/rhasspy/piper)（MIT）离线合成
高频词音频，OGG Vorbis 随 APK 打包（`app/src/main/assets/words/audio/{word}.ogg` +
索引 `index.txt`，**不入 git**——本目录可再生，见下）。

## 口径（SPEC_CHANGE_REQUEST_PIPER_TTS，已批准）

- 词源：[FrequencyWords](https://github.com/hermitdave/FrequencyWords)（MIT）
  `content/2018/en/en_full.txt` 语料频次前 **5000** 个纯 ASCII 字母单词。
  **Windows 保留名取舍**：`con` 等保留设备名（CON/PRN/AUX/NUL/COM1-9/LPT1-9）在 NTFS
  无法落盘 → 不选（实绩：前 5000 词命中 `con` 一个），该词运行时照常走 TTS；
  `index.txt` 恒按**实际落盘文件**生成，索引与资产不可能错位。
- 声音：`en_US-lessac-medium`（onnx 63MB，一次性构建产物，不随包）。
- 覆盖词 PRONUNCIATION 段走文件音频（Media3 经 `words://` scheme 流式播 assets，
  不解包）；未覆盖词照旧系统 TTS——**预期分支，不触发降级横幅**。
- 音量/口音注记：预录音频不受「朗读口音」设置影响（FR-22 口音只作用于 TTS 段）；
  实时 Piper 合成（英音 voice、全词覆盖）待真机质量验证后再立项。

## 产物约定

- `assets/words/audio/{normalizedText}.ogg`——与 `Word.normalizedText` 一致（小写）。
- `assets/words/audio/index.txt`——每行一个 normalizedText
  （`AssetsPronunciationAudioCatalog` 懒加载为 Set；资产缺席 → 永远 miss，全走 TTS）。
- URI scheme：`words://audio/{word}.ogg` → Media3 actual 解析为
  `file:///android_asset/words/audio/{word}.ogg`（AssetDataSource）。
- DB 优先级：`Word.pronunciationAudioUri`（授权音频预留列）非空时 catalog 不补位
  （只补空缺，绝不覆盖——`SqlDelightPlaybackContentRepository` 装载层实现）。

## 再生步骤（Windows，Python 3.10+，需 ffmpeg 在 PATH）

```bash
cd tools/tts
# 1. 前置下载到 cache/（均一次性，已 gitignore）：
#    piper 引擎（github release 2023.11.14-2，zip 解压出 piper/piper.exe + dll）
curl -sSL --ssl-no-revoke -o cache/piper_windows_amd64.zip \
  https://github.com/rhasspy/piper/releases/download/2023.11.14-2/piper_windows_amd64.zip
cd cache && unzip -o -q piper_windows_amd64.zip && cd ..
#    声音模型（huggingface.co 本网络不可达时走 hf-mirror.com 镜像，两文件同目录）
curl -sSL --ssl-no-revoke -o cache/en_US-lessac-medium.onnx \
  "https://hf-mirror.com/rhasspy/piper-voices/resolve/main/en/en_US/lessac/medium/en_US-lessac-medium.onnx"
curl -sSL --ssl-no-revoke -o cache/en_US-lessac-medium.onnx.json \
  "https://hf-mirror.com/rhasspy/piper-voices/resolve/main/en/en_US/lessac/medium/en_US-lessac-medium.onnx.json"
#    频次词表（每行 "word count"）
curl -sSL --ssl-no-revoke -o cache/full.txt \
  https://raw.githubusercontent.com/hermitdave/FrequencyWords/master/content/2018/en/en_full.txt
# 2. 生成 + 转码 + 索引（单次 piper 进程批量合成，约 10 分钟；重跑增量续传）
python generate_word_audio.py            # 前 5000 词
python generate_word_audio.py --limit 200  # 小批试验
```

产出直写 `../../app/src/main/assets/words/audio/`（~30MB / 5000 词，OGG -q:a 4），
随后 `./gradlew :app:assembleDebug` 即随包。

## 运行时消费链（代码侧）

- 端口：`shared/.../playback/PronunciationAudioCatalog.kt`（commonMain fun interface）
- actual：`shared/src/androidMain/.../platform/AssetsPronunciationAudioCatalog.kt`
- 装载补位：`SqlDelightPlaybackContentRepository`（DB 值优先，catalog 只补空缺）
- 段构建：`SegmentBuilder.wordSpecs`——有 URI → PRONUNCIATION 文件段；无 → TTS 段
- scheme 解析：`Media3AudioPlayer.resolveUri`（`words://` → android_asset）

`cache/`（piper/模型/词表/wav 中间产物，~260MB）与 `app/src/main/assets/words/`
均已 gitignore；`generate_word_audio.py` 与本 README 入 git。
