#!/usr/bin/env python3
"""
音频剪切模块 - 从原视频中剪切例句音频片段
"""

import subprocess
import sys
from pathlib import Path
from typing import Optional


def clip_audio(
    video_path: str,
    output_path: str,
    start_time: float,
    end_time: float,
    volume: float = 1.0
) -> str:
    """
    从视频中剪切指定时间范围的音频

    Args:
        video_path: 视频文件路径
        output_path: 输出音频文件路径
        start_time: 开始时间（秒）
        end_time: 结束时间（秒）
        volume: 音量增益（默认 1.0）

    Returns:
        输出文件路径
    """
    video_path = Path(video_path)
    output_path = Path(output_path)

    if not video_path.exists():
        raise FileNotFoundError(f"视频文件不存在: {video_path}")

    # 确保输出目录存在
    output_path.parent.mkdir(parents=True, exist_ok=True)

    # 计算时长
    duration = end_time - start_time
    if duration <= 0:
        raise ValueError(f"无效的时间范围: {start_time} - {end_time}")

    # 使用 ffmpeg 剪切音频
    cmd = [
        "ffmpeg",
        "-y",  # 覆盖输出文件
        "-i", str(video_path),
        "-vn",  # 禁用视频
        "-ss", str(start_time),  # 开始时间
        "-t", str(duration),  # 时长
        "-acodec", "libmp3lame",  # MP3 编码
        "-ab", "128k",  # 比特率
        "-ar", "44100",  # 采样率
        "-ac", "2",  # 立体声
    ]

    if volume != 1.0:
        # 调整音量
        cmd.extend(["-af", f"volume={volume}"])

    cmd.append(str(output_path))

    result = subprocess.run(
        cmd,
        capture_output=True,
        text=True
    )

    if result.returncode != 0:
        print(f"ffmpeg 错误: {result.stderr}", file=sys.stderr)
        raise RuntimeError(f"音频剪切失败: {result.stderr}")

    return str(output_path)


def clip_audio_from_wav(
    wav_path: str,
    output_path: str,
    start_time: float,
    end_time: float
) -> str:
    """
    从 WAV 文件中剪切音频（无需重新编码）

    Args:
        wav_path: WAV 文件路径
        output_path: 输出文件路径
        start_time: 开始时间（秒）
        end_time: 结束时间（秒）

    Returns:
        输出文件路径
    """
    wav_path = Path(wav_path)
    output_path = Path(output_path)

    if not wav_path.exists():
        raise FileNotFoundError(f"WAV 文件不存在: {wav_path}")

    output_path.parent.mkdir(parents=True, exist_ok=True)

    duration = end_time - start_time
    if duration <= 0:
        raise ValueError(f"无效的时间范围: {start_time} - {end_time}")

    # 使用 ffmpeg 剪切（copy 模式，无需重编码）
    cmd = [
        "ffmpeg",
        "-y",
        "-i", str(wav_path),
        "-ss", str(start_time),
        "-t", str(duration),
        "-acodec", "copy",  # 直接复制，不重新编码
        str(output_path)
    ]

    result = subprocess.run(
        cmd,
        capture_output=True,
        text=True
    )

    if result.returncode != 0:
        raise RuntimeError(f"音频剪切失败: {result.stderr}")

    return str(output_path)


def get_audio_duration(audio_path: str) -> float:
    """
    获取音频文件时长

    Args:
        audio_path: 音频文件路径

    Returns:
        时长（秒）
    """
    cmd = [
        "ffprobe",
        "-v", "error",
        "-show_entries", "format=duration",
        "-of", "default=noprint_wrappers=1:nokey=1",
        audio_path
    ]

    result = subprocess.run(
        cmd,
        capture_output=True,
        text=True
    )

    if result.returncode != 0:
        raise RuntimeError(f"获取音频时长失败: {result.stderr}")

    try:
        return float(result.stdout.strip())
    except ValueError:
        raise RuntimeError(f"无法解析音频时长: {result.stdout}")


def normalize_audio(
    audio_path: str,
    output_path: str,
    target_level: float = -20.0
) -> str:
    """
    标准化音频音量

    Args:
        audio_path: 输入音频文件
        output_path: 输出音频文件
        target_level: 目标音量（dB）

    Returns:
        输出文件路径
    """
    # 使用 ffmpeg loudnorm 滤镜标准化音量
    # 第一步：分析音频
    cmd_analyze = [
        "ffmpeg",
        "-i", audio_path,
        "-af", "loudnorm=print_format=json",
        "-f", "null",
        "-"
    ]

    result = subprocess.run(
        cmd_analyze,
        capture_output=True,
        text=True
    )

    # 提取分析结果（简化处理）
    # 实际应该解析 JSON 输出并使用测量值

    # 第二步：应用标准化
    cmd_normalize = [
        "ffmpeg",
        "-y",
        "-i", audio_path,
        "-af", f"loudnorm=I={target_level}:TP=-1.5:LRA=11",
        output_path
    ]

    result = subprocess.run(
        cmd_normalize,
        capture_output=True,
        text=True
    )

    if result.returncode != 0:
        raise RuntimeError(f"音频标准化失败: {result.stderr}")

    return output_path


def batch_clip(
    video_path: str,
    output_dir: str,
    clips: list[dict],
    prefix: str = ""
) -> list[str]:
    """
    批量剪切音频

    Args:
        video_path: 视频文件路径
        output_dir: 输出目录
        clips: 片段列表 [{"start": 0.0, "end": 5.0, "name": "clip1"}, ...]
        prefix: 文件名前缀

    Returns:
        输出文件路径列表
    """
    output_dir = Path(output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)

    results = []

    for i, clip in enumerate(clips):
        start = clip.get("start", 0)
        end = clip.get("end", 0)
        name = clip.get("name", f"{prefix}{i}")

        if end <= start:
            print(f"  跳过无效片段: {name}")
            continue

        output_path = output_dir / f"{name}.mp3"

        try:
            result_path = clip_audio(
                video_path,
                str(output_path),
                start,
                end
            )
            results.append(result_path)
            print(f"  已剪切: {name}.mp3")
        except Exception as e:
            print(f"  剪切失败 {name}: {e}")

    return results


if __name__ == "__main__":
    # 测试
    import sys
    if len(sys.argv) >= 4:
        start = float(sys.argv[2])
        end = float(sys.argv[3])
        clip_audio(sys.argv[1], "test_clip.mp3", start, end)
    else:
        print("用法: python audio_clipper.py <video> <start_sec> <end_sec>")
