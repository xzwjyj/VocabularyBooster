#!/usr/bin/env python3
"""
语音识别模块 - 使用 Whisper 进行语音识别
"""

import sys
from pathlib import Path


def load_whisper():
    """动态加载 whisper 模块"""
    try:
        import whisper
        return whisper
    except ImportError:
        print("错误: whisper 模块未安装", file=sys.stderr)
        print("请运行: pip install openai-whisper", file=sys.stderr)
        print("或: pip install -r tools/video_importer/requirements.txt", file=sys.stderr)
        sys.exit(1)


def recognize_speech(
    audio_path: str,
    model: str = "base",
    language: str = "en",
    verbose: bool = False
) -> str:
    """
    使用 Whisper 进行语音识别

    Args:
        audio_path: 音频文件路径
        model: Whisper 模型大小 (tiny, base, small, medium, large)
        language: 语音语言代码
        verbose: 是否输出详细日志

    Returns:
        识别出的文本（包含时间戳信息）
    """
    whisper = load_whisper()

    print(f"  加载 Whisper {model} 模型...")
    whisper_model = whisper.load_model(model)

    print(f"  开始识别: {Path(audio_path).name}")
    result = whisper_model.transcribe(
        audio_path,
        language=language,
        verbose=verbose,
        # 词级时间戳：segment 级 start 在 BGM 下常远早于首个发声词，
        # 剪切边界由 word_matcher 改用词级（SCR-AUDIOTRIM）
        word_timestamps=True
    )

    # 构建带时间戳的转写结果
    transcript_with_timestamps = []
    for segment in result["segments"]:
        entry = {
            "start": segment["start"],
            "end": segment["end"],
            "text": segment["text"].strip()
        }
        # 保留词级时间供 word_matcher 精确剪切（无词级时缺省，下游回退 segment 边界）
        if segment.get("words"):
            entry["words"] = [
                {"word": w["word"], "start": w["start"], "end": w["end"]}
                for w in segment["words"]
            ]
        transcript_with_timestamps.append(entry)

    # 输出纯文本用于调试
    full_text = " ".join(seg["text"] for seg in transcript_with_timestamps)
    print(f"  识别完成: {len(full_text)} 字符")

    # 返回带时间戳的 JSON 格式
    import json
    return json.dumps(transcript_with_timestamps, ensure_ascii=False, indent=2)


def parse_srt(srt_content: str) -> list[dict]:
    """
    解析 SRT 字幕文件

    Args:
        srt_content: SRT 文件内容

    Returns:
        时间戳段列表
    """
    import re

    segments = []
    blocks = re.split(r'\n\s*\n', srt_content.strip())

    for block in blocks:
        lines = block.strip().split('\n')
        if len(lines) < 3:
            continue

        # 解析时间戳
        time_line = lines[1]
        time_match = re.match(
            r'(\d{2}):(\d{2}):(\d{2}),(\d{3})\s*-->\s*(\d{2}):(\d{2}):(\d{2}),(\d{3})',
            time_line
        )

        if not time_match:
            continue

        start_time = (
            int(time_match.group(1)) * 3600 +
            int(time_match.group(2)) * 60 +
            int(time_match.group(3)) +
            int(time_match.group(4)) / 1000
        )
        end_time = (
            int(time_match.group(5)) * 3600 +
            int(time_match.group(6)) * 60 +
            int(time_match.group(7)) +
            int(time_match.group(8)) / 1000
        )

        # 文本（可能有多行）
        text = ' '.join(lines[2:])

        segments.append({
            "start": start_time,
            "end": end_time,
            "text": text
        })

    return segments


def load_subtitles(srt_path: str) -> list[dict]:
    """
    加载字幕文件 (SRT/VTT)

    Args:
        srt_path: 字幕文件路径

    Returns:
        时间戳段列表
    """
    path = Path(srt_path)

    if not path.exists():
        raise FileNotFoundError(f"字幕文件不存在: {srt_path}")

    with open(path, "r", encoding="utf-8") as f:
        content = f.read()

    if path.suffix.lower() == ".srt":
        return parse_srt(content)
    elif path.suffix.lower() == ".vtt":
        # 简单 VTT 解析（类似 SRT）
        content = content.replace("WEBVTT\n\n", "")
        return parse_srt(content)
    else:
        raise ValueError(f"不支持的字幕格式: {path.suffix}")


if __name__ == "__main__":
    # 测试
    import sys
    if len(sys.argv) > 1:
        result = recognize_speech(sys.argv[1])
        print(result)
    else:
        print("用法: python speech_recognizer.py <audio_file>")
