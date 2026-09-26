# 视频单词导入工作目录

## 快速开始

1. 将视频文件（MP4/AVI/MKV）放入 `input/`
2. 将对应的单词列表（TXT）放入 `input/`
   - 命名规则：`视频名_words.txt` 或 `视频名.txt`
3. 双击运行 `process.bat`
4. 在 `output/` 文件夹中找到新生成的 APK

## 文件命名约定

```
input/
├── friends_s01e01.mp4           # 视频文件
├── friends_s01e01_words.txt     # 对应的单词列表
├── friends_s01e02.mp4
└── friends_s01e02_words.txt

output/
├── VocabularyBooster_2026-09-21_093000_001.apk  # 第一次运行
├── VocabularyBooster_2026-09-21_110500_002.apk  # 第二次运行
└── VocabularyBooster_2026-09-22_080000_001.apk  # 第二天运行
```

## 工作流程

```
process.bat
    │
    ├─► 1. 扫描 input/ 目录
    │       - 查找视频文件
    │       - 匹配同名单词列表 (_words.txt 或 .txt)
    │       - 自动跳过已处理过的文件
    │
    ├─► 2. 运行视频提取工具
    │       - 提取音频
    │       - Whisper 语音识别
    │       - 匹配单词与句子
    │       - 查询释义
    │       - 剪切音频片段
    │
    ├─► 3. 复制到 APK assets
    │       - data.json
    │       - audio/
    │
    ├─► 4. 构建 APK
    │       - 自动设置 JAVA_HOME
    │       - 生成带时间戳的 APK
    │
    └─► 5. 输出到 output/
```

## 高级选项

```bash
# 仅提取数据，不构建 APK
python process.py --skip-build

# 指定输出文件名
python process.py --apk-output "MyBook.apk"
```

## 依赖要求

- Python 3.10+
- ffmpeg（添加到 PATH）
- JDK 21（自动检测）
