#!/usr/bin/env python3
"""
数据导出模块 - 生成 VocabularyBooster APK 数据包
"""

import json
import shutil
from pathlib import Path
from typing import Optional


def export_package(
    package_data: dict,
    audio_dir: str,
    output_dir: str,
    audio_copy: bool = True
) -> str:
    """
    导出 APK 数据包

    Args:
        package_data: 包数据字典
        audio_dir: 音频文件目录
        output_dir: 输出目录
        audio_copy: 是否复制音频文件到输出目录

    Returns:
        生成的 JSON 文件路径
    """
    output_dir = Path(output_dir)
    audio_dir = Path(audio_dir) if audio_dir else None

    # 创建输出目录结构
    output_audio_dir = output_dir / "audio"
    output_audio_dir.mkdir(parents=True, exist_ok=True)

    # 复制音频文件
    if audio_copy and audio_dir and audio_dir.exists():
        print(f"  复制音频文件...")
        for audio_file in audio_dir.glob("*.mp3"):
            shutil.copy2(audio_file, output_audio_dir / audio_file.name)
        print(f"  已复制 {len(list(output_audio_dir.glob('*.mp3')))} 个音频文件")

    # 写入 JSON 文件
    json_path = output_dir / "data.json"
    with open(json_path, "w", encoding="utf-8") as f:
        json.dump(package_data, f, ensure_ascii=False, indent=2)

    print(f"  JSON 已生成: {json_path.name}")

    return str(json_path)


def validate_package_data(data: dict) -> tuple[bool, list[str]]:
    """
    验证包数据格式

    Args:
        data: 包数据字典

    Returns:
        (是否有效, 错误消息列表)
    """
    errors = []

    # 检查必需字段
    required_fields = ["version", "wordBookName", "entries"]
    for field in required_fields:
        if field not in data:
            errors.append(f"缺少必需字段: {field}")

    # 检查版本
    if "version" in data and data["version"] != 1:
        errors.append(f"不支持的版本: {data['version']}")

    # 检查 entries
    if "entries" in data:
        if not isinstance(data["entries"], list):
            errors.append("'entries' 必须是数组")
        else:
            for i, entry in enumerate(data["entries"]):
                entry_errors = validate_entry(entry, i)
                errors.extend(entry_errors)

    return len(errors) == 0, errors


def validate_entry(entry: dict, index: int) -> list[str]:
    """
    验证单个词条

    Args:
        entry: 词条字典
        index: 词条索引

    Returns:
        错误消息列表
    """
    errors = []
    prefix = f"entries[{index}]"

    # 检查必需字段
    required_fields = ["word", "meaningEN", "partOfSpeech"]
    for field in required_fields:
        if field not in entry:
            errors.append(f"{prefix}: 缺少字段 '{field}'")

    # 检查 examples
    if "examples" in entry:
        if not isinstance(entry["examples"], list):
            errors.append(f"{prefix}: 'examples' 必须是数组")
        else:
            for j, example in enumerate(entry["examples"]):
                if "sentence" not in example:
                    errors.append(f"{prefix}.examples[{j}]: 缺少 'sentence' 字段")

    return errors


def generate_sample_package() -> dict:
    """生成示例数据包"""
    return {
        "version": 1,
        "sourceVideo": "sample.mp4",
        "wordBookName": "示例-老友记",
        "exportedAt": "2026-09-21T00:00:00Z",
        "entries": [
            {
                "word": "abandon",
                "meaningEN": "to leave somebody/something behind",
                "meaningCN": "遗弃；离弃；放弃",
                "partOfSpeech": "verb",
                "examples": [
                    {
                        "sentence": "They had to abandon their car in the snow.",
                        "chineseTranslation": "他们不得不把汽车遗弃在雪地里。",
                        "audioFile": "abandon_0.mp3"
                    }
                ]
            },
            {
                "word": "practice",
                "meaningEN": "the act of doing something repeatedly",
                "meaningCN": "练习；实践",
                "partOfSpeech": "noun",
                "examples": [
                    {
                        "sentence": "Practice makes perfect.",
                        "chineseTranslation": "熟能生巧。",
                        "audioFile": "practice_0.mp3"
                    }
                ]
            }
        ]
    }


def export_with_audio(
    package_data: dict,
    video_path: str,
    output_dir: str,
    matched_sentences: dict
) -> str:
    """
    导出数据包并自动剪切音频

    Args:
        package_data: 包数据字典
        video_path: 视频文件路径
        output_dir: 输出目录
        matched_sentences: 单词到句子（带时间戳）的映射

    Returns:
        生成的 JSON 文件路径
    """
    from audio_clipper import clip_audio

    output_dir = Path(output_dir)
    audio_dir = output_dir / "audio"
    audio_dir.mkdir(parents=True, exist_ok=True)

    # 处理每个词条
    for entry in package_data.get("entries", []):
        word = entry.get("word", "")
        if word not in matched_sentences:
            continue

        sentences_data = matched_sentences[word]

        for i, example in enumerate(entry.get("examples", [])):
            # 查找对应的时间戳
            sentence_text = example.get("sentence", "")
            timestamp = None

            for sent_data in sentences_data:
                if sent_data.get("sentence") == sentence_text:
                    timestamp = sent_data
                    break

            if timestamp:
                # 剪切音频
                audio_filename = f"{word}_{i}.mp3"
                audio_path = audio_dir / audio_filename

                try:
                    clip_audio(
                        video_path,
                        str(audio_path),
                        timestamp.get("start", 0),
                        timestamp.get("end", 0)
                    )
                    example["audioFile"] = audio_filename
                except Exception as e:
                    print(f"  警告: 剪切音频失败 ({word}_{i}): {e}")
                    example["audioFile"] = None
            else:
                example["audioFile"] = None

    # 导出
    return export_package(package_data, str(audio_dir), output_dir, audio_copy=False)


def create_readme(output_dir: str, book_name: str, word_count: int, example_count: int) -> str:
    """
    生成说明文件

    Args:
        output_dir: 输出目录
        book_name: 生词本名称
        word_count: 单词数
        example_count: 例句数

    Returns:
        生成的 README 文件路径
    """
    readme_content = f"""# {book_name}

这是一个从视频中提取的 VocabularyBooster 生词本数据包。

## 数据摘要

- 单词数: {word_count}
- 例句数: {example_count}

## 使用方法

1. 将整个 `{Path(output_dir).name}` 目录复制到 APK 的 `assets/video_import/` 目录
2. 重新构建 APK
3. 安装 APK 后，数据将在首次启动时自动导入

## 源视频

数据提取自视频文件。
"""

    readme_path = Path(output_dir) / "README.md"
    with open(readme_path, "w", encoding="utf-8") as f:
        f.write(readme_content)

    return str(readme_path)


if __name__ == "__main__":
    # 测试
    sample = generate_sample_package()
    valid, errors = validate_package_data(sample)

    print("验证示例数据包:")
    if valid:
        print("  ✓ 数据格式有效")
    else:
        print("  ✗ 错误:")
        for error in errors:
            print(f"    - {error}")

    # 导出测试
    import tempfile
    with tempfile.TemporaryDirectory() as tmpdir:
        path = export_package(sample, "", tmpdir)
        print(f"\n导出到: {path}")
