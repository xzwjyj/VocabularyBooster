#!/usr/bin/env python3
"""
一次性重裁剪脚本（SCR-AUDIOTRIM）：修复巫师三批次例句音频的长 BGM 前奏。

背景：word_matcher 曾直接用 Whisper segment 边界剪切，持续 BGM 下 segment
start 远早于首个发声词（clutch 实测 22.4s 片段语音 11.2s 才开始）。

做法：对原视频重跑 Whisper（word_timestamps=True），把 app assets
data.json 中的每个例句用 difflib 模糊对齐回 whisper segment，按词级边界
（首词−0.25s → 末词+0.5s，与 word_matcher.speech_bounds_from_words 同口径）
从原视频重剪，覆盖 assets audio/ 同名 mp3。data.json 不做任何改动。

用法（须用装有 openai-whisper 的解释器，本机为系统 Python 3.10）：
    python retrim_audio.py            # dry-run：只打印新旧时间轴，不写文件
    python retrim_audio.py --apply    # 重剪并覆盖 app assets audio/
"""

import argparse
import difflib
import json
import re
import subprocess
import sys
from pathlib import Path

WORKSPACE_DIR = Path(__file__).parent.resolve()
PROJECT_ROOT = WORKSPACE_DIR.parent.parent.parent
TOOL_DIR = WORKSPACE_DIR.parent

sys.path.insert(0, str(TOOL_DIR))
from audio_clipper import clip_audio, get_audio_duration  # noqa: E402
from video_processor import extract_audio  # noqa: E402
from word_matcher import speech_bounds_from_words  # noqa: E402

VIDEO_PATH = WORKSPACE_DIR / "input" / "巫师三" / "part1.mp4"
ASSETS_AUDIO_DIR = PROJECT_ROOT / "app/src/main/assets/video_import/audio"
DATA_JSON = PROJECT_ROOT / "app/src/main/assets/video_import/data.json"
TEMP_DIR = WORKSPACE_DIR / "temp" / "retrim"

# 例句 ↔ whisper segment 文本对齐的最低相似度，低于则判为错配并人工复核
MATCH_THRESHOLD = 0.55


def normalize_text(text: str) -> str:
    """小写化并去掉非字母数字，供模糊对齐"""
    return re.sub(r"\s+", " ", re.sub(r"[^a-z0-9 ]", " ", text.lower())).strip()


def load_whisper_segments(audio_path: Path, model_name: str = "base") -> list[dict]:
    """重跑 Whisper（词级时间戳），返回带 words 的 segment 列表。

    结果缓存到 temp/retrim/segments.json——dry-run 与 --apply 复用同一份
    对齐依据，避免二次转写且保证两次结果一致。
    """
    cache_path = TEMP_DIR / "segments.json"
    if cache_path.exists():
        with open(cache_path, "r", encoding="utf-8") as f:
            segments = json.load(f)
        print(f"  复用缓存 segment: {cache_path.name} ({len(segments)} 条)")
        return segments

    import whisper

    print(f"  加载 Whisper {model_name} 模型...")
    model = whisper.load_model(model_name)
    print(f"  识别: {audio_path.name}（26 分钟视频，CPU 需数分钟）...")
    result = model.transcribe(str(audio_path), language="en", word_timestamps=True)

    segments = []
    for seg in result["segments"]:
        entry = {
            "start": seg["start"],
            "end": seg["end"],
            "text": seg["text"].strip(),
            "norm": normalize_text(seg["text"]),
        }
        if seg.get("words"):
            entry["words"] = [
                {"word": w["word"], "start": w["start"], "end": w["end"]}
                for w in seg["words"]
            ]
        segments.append(entry)
    print(f"  识别完成: {len(segments)} 个 segment")
    with open(cache_path, "w", encoding="utf-8") as f:
        json.dump(segments, f, ensure_ascii=False)
    return segments


def best_match(sentence: str, segments: list[dict]) -> tuple[dict | None, float]:
    """difflib 模糊对齐：返回 (最佳 segment, 相似度)"""
    norm = normalize_text(sentence)
    best, best_ratio = None, 0.0
    for seg in segments:
        ratio = difflib.SequenceMatcher(None, norm, seg["norm"]).ratio()
        if ratio > best_ratio:
            best, best_ratio = seg, ratio
    return best, best_ratio


def main():
    parser = argparse.ArgumentParser(description="按词级时间戳重裁剪已导入例句音频")
    parser.add_argument("--apply", action="store_true", help="实际写文件（缺省 dry-run）")
    parser.add_argument("--model", default="base", help="Whisper 模型（默认 base，与原批次一致）")
    args = parser.parse_args()

    if not VIDEO_PATH.exists():
        print(f"错误: 原视频不存在: {VIDEO_PATH}")
        sys.exit(1)
    if not DATA_JSON.exists():
        print(f"错误: data.json 不存在: {DATA_JSON}")
        sys.exit(1)

    with open(DATA_JSON, "r", encoding="utf-8") as f:
        data = json.load(f)

    TEMP_DIR.mkdir(parents=True, exist_ok=True)
    audio_path = TEMP_DIR / "part1_audio.wav"
    if not audio_path.exists():
        print("[1/3] 提取音频...")
        extract_audio(str(VIDEO_PATH), str(audio_path))
    else:
        print("[1/3] 复用已提取音频:", audio_path.name)

    print("[2/3] Whisper 识别（词级时间戳）...")
    segments = load_whisper_segments(audio_path, model_name=args.model)

    print("[3/3] 对齐例句并重裁剪...")
    failures = []
    plan = []  # (audio_file, sentence, old_dur, new_start, new_end, ratio)
    for entry in data["entries"]:
        for example in entry["examples"]:
            audio_file = example.get("audioFile")
            sentence = example["sentence"]
            if not audio_file:
                continue
            old_path = ASSETS_AUDIO_DIR / audio_file
            old_dur = get_audio_duration(str(old_path)) if old_path.exists() else 0.0

            seg, ratio = best_match(sentence, segments)
            if seg is None or ratio < MATCH_THRESHOLD or "words" not in seg:
                failures.append((audio_file, sentence, ratio,
                                 seg["start"] if seg else None))
                continue

            bounds = speech_bounds_from_words(seg)
            plan.append((audio_file, sentence, old_dur,
                         bounds["start"], bounds["end"], ratio))

    # 打印计划（dry-run 与 --apply 都打印，供人工核对时间轴）
    print()
    print(f"{'文件':<18} {'旧时长':>7} {'新起点':>9} {'新终点':>9} {'新时长':>7} {'相似度':>6}")
    for audio_file, sentence, old_dur, ns, ne, ratio in plan:
        print(f"{audio_file:<18} {old_dur:>6.1f}s {ns:>8.2f}s {ne:>8.2f}s "
              f"{ne - ns:>6.1f}s {ratio:>6.2f}  {sentence[:40]}")

    if failures:
        print("\n以下例句对齐失败（不重剪，需人工处理）:")
        for audio_file, sentence, ratio, seg_start in failures:
            print(f"  {audio_file}: ratio={ratio:.2f} seg={seg_start} {sentence[:50]}")

    if not args.apply:
        print("\n[dry-run] 未写任何文件。确认无误后加 --apply 执行。")
        return

    out_dir = TEMP_DIR / "out"
    out_dir.mkdir(parents=True, exist_ok=True)
    for audio_file, _, _, ns, ne, _ in plan:
        clip_audio(str(VIDEO_PATH), str(out_dir / audio_file), ns, ne)

    # 覆盖 assets
    for audio_file, _, _, _, _, _ in plan:
        target = ASSETS_AUDIO_DIR / audio_file
        target.write_bytes((out_dir / audio_file).read_bytes())
        new_dur = get_audio_duration(str(target))
        print(f"  已覆盖: {audio_file} -> {new_dur:.1f}s")
    print("\n完成: assets audio/ 已更新，data.json 未改动。")


if __name__ == "__main__":
    main()
