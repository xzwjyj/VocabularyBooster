#!/usr/bin/env python3
"""
视频处理模块 - 从视频中提取音频
"""

import subprocess
import sys
from pathlib import Path


def extract_audio(video_path: str, output_path: str, sample_rate: int = 16000) -> str:
    """
    从视频文件中提取音频

    Args:
        video_path: 视频文件路径
        output_path: 输出音频文件路径
        sample_rate: 采样率 (Whisper 推荐 16kHz)

    Returns:
        输出音频文件路径
    """
    video_path = Path(video_path)
    output_path = Path(output_path)

    if not video_path.exists():
        raise FileNotFoundError(f"视频文件不存在: {video_path}")

    # 检查 ffmpeg 是否可用
    try:
        subprocess.run(
            ["ffmpeg", "-version"],
            capture_output=True,
            check=True
        )
    except (subprocess.CalledProcessError, FileNotFoundError):
        print("错误: ffmpeg 未安装或不在 PATH 中", file=sys.stderr)
        print("请从 https://ffmpeg.org 下载并安装 ffmpeg", file=sys.stderr)
        sys.exit(1)

    # 提取音频
    cmd = [
        "ffmpeg",
        "-y",  # 覆盖输出文件
        "-i", str(video_path),
        "-vn",  # 禁用视频
        "-acodec", "pcm_s16le",  # WAV 格式
        "-ar", str(sample_rate),  # 采样率
        "-ac", "1",  # 单声道
        str(output_path)
    ]

    print(f"  执行: ffmpeg {' '.join(cmd[:6])}...")

    result = subprocess.run(
        cmd,
        capture_output=True,
        text=True
    )

    if result.returncode != 0:
        print(f"ffmpeg 错误: {result.stderr}", file=sys.stderr)
        raise RuntimeError(f"音频提取失败: {result.stderr}")

    print(f"  音频已提取: {output_path.name} ({output_path.stat().st_size / 1024 / 1024:.1f} MB)")
    return str(output_path)


def get_video_info(video_path: str) -> dict:
    """
    获取视频信息

    Returns:
        包含 duration, width, height, fps 等信息的字典
    """
    cmd = [
        "ffprobe",
        "-v", "quiet",
        "-print_format", "json",
        "-show_format",
        "-show_streams",
        video_path
    ]

    result = subprocess.run(
        cmd,
        capture_output=True,
        text=True
    )

    if result.returncode != 0:
        return {}

    import json
    data = json.loads(result.stdout)

    info = {}
    for stream in data.get("streams", []):
        if stream.get("codec_type") == "video":
            info["width"] = stream.get("width")
            info["height"] = stream.get("height")
            info["fps"] = eval(stream.get("r_frame_rate", "0/1"))
        elif stream.get("codec_type") == "audio":
            info["sample_rate"] = stream.get("sample_rate")
            info["channels"] = stream.get("channels")

    format_info = data.get("format", {})
    info["duration"] = float(format_info.get("duration", 0))
    info["format"] = format_info.get("format_name")

    return info


if __name__ == "__main__":
    # 测试
    import sys
    if len(sys.argv) > 1:
        extract_audio(sys.argv[1], "test_audio.wav")
    else:
        print("用法: python video_processor.py <video_file>")
