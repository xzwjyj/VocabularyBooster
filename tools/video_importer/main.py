#!/usr/bin/env python3
"""
Video Word Extractor - 从英文视频中提取单词和例句

用法:
    python main.py --video video.mp4 --words words.txt --output ./output
    python main.py --video video.mp4 --words words.txt --output ./output --book-name "老友记第一季"
"""

import argparse
import json
import sys
from pathlib import Path
from datetime import datetime

from video_processor import extract_audio
from speech_recognizer import recognize_speech
from word_matcher import match_words, match_words_with_timestamps
from dictionary_client import fetch_definition
from audio_clipper import clip_audio
from exporter import export_package


def parse_args():
    parser = argparse.ArgumentParser(
        description="从英文视频中提取单词和例句到 VocabularyBooster 生词本"
    )
    parser.add_argument(
        "--video", "-v",
        required=True,
        help="视频文件路径 (MP4, AVI, MKV 等)"
    )
    parser.add_argument(
        "--words", "-w",
        required=True,
        help="单词列表文件路径 (TXT, 每行一个单词)"
    )
    parser.add_argument(
        "--output", "-o",
        default="./output",
        help="输出目录路径 (默认: ./output)"
    )
    parser.add_argument(
        "--book-name",
        default=None,
        help="生词本名称 (默认: 视频文件名)"
    )
    parser.add_argument(
        "--language",
        default="en",
        help="视频语言代码 (默认: en)"
    )
    parser.add_argument(
        "--model",
        default="base",
        choices=["tiny", "base", "small", "medium", "large"],
        help="Whisper 模型大小 (默认: base)"
    )
    parser.add_argument(
        "--skip-clip",
        action="store_true",
        help="跳过音频片段剪切 (仅生成 JSON)"
    )
    parser.add_argument(
        "--use-subtitles",
        action="store_true",
        help="优先使用字幕文件 (需要同目录下的 .srt 文件)"
    )
    return parser.parse_args()


def load_words(words_file: Path) -> list[str]:
    """加载单词列表"""
    words = []
    with open(words_file, "r", encoding="utf-8") as f:
        for line in f:
            word = line.strip()
            if word and not word.startswith("#"):
                words.append(word)
    return words


def main():
    args = parse_args()

    video_path = Path(args.video)
    words_file = Path(args.words)
    output_dir = Path(args.output)

    # 验证输入文件
    if not video_path.exists():
        print(f"错误: 视频文件不存在: {video_path}")
        sys.exit(1)

    if not words_file.exists():
        print(f"错误: 单词列表文件不存在: {words_file}")
        sys.exit(1)

    # 创建输出目录
    output_dir.mkdir(parents=True, exist_ok=True)
    audio_dir = output_dir / "audio"
    audio_dir.mkdir(exist_ok=True)

    # 加载单词列表
    print(f"[1/6] 加载单词列表: {len(load_words(words_file))} 个单词")
    target_words = load_words(words_file)

    # 提取音频
    print(f"[2/6] 从视频中提取音频: {video_path.name}")
    audio_path = output_dir / "extracted_audio.wav"
    extract_audio(str(video_path), str(audio_path))

    # 语音识别
    print(f"[3/6] 语音识别 (Whisper {args.model})")
    subtitle_path = video_path.with_suffix(".srt")
    if args.use_subtitles and subtitle_path.exists():
        print(f"  -> 使用字幕文件: {subtitle_path.name}")
        with open(subtitle_path, "r", encoding="utf-8") as f:
            transcript = f.read()
    else:
        print(f"  -> 使用 Whisper 识别...")
        transcript = recognize_speech(str(audio_path), model=args.model)

    # 匹配单词与句子（带时间戳）
    print(f"[4/6] 匹配目标单词与句子")
    matched_with_timestamps = match_words_with_timestamps(transcript, target_words)
    print(f"  -> 找到 {len(matched_with_timestamps)} 个目标单词的例句")

    # 查询释义并剪切音频
    print(f"[5/6] 查询单词释义并剪切音频")
    entries = []
    for word in target_words:
        if word not in matched_with_timestamps:
            print(f"  -> 跳过 '{word}': 未找到例句")
            continue

        # 获取该单词的所有例句作为上下文
        context_sentences = [seg["sentence"] for seg in matched_with_timestamps[word]]

        # 查询释义（传入上下文用于选择最佳释义）
        definition = fetch_definition(word, context_sentences=context_sentences)
        if not definition:
            print(f"  -> 跳过 '{word}': 无法获取释义")
            continue

        # 处理每个例句
        examples = []
        for i, seg in enumerate(matched_with_timestamps[word]):
            sentence = seg["sentence"]
            start = seg.get("start", 0)
            end = seg.get("end", 0)

            audio_file = None
            if not args.skip_clip and start >= 0 and end > start:
                # 从视频中剪切音频片段
                try:
                    audio_filename = f"{word}_{i}.mp3"
                    clip_audio(
                        str(video_path),
                        str(audio_dir / audio_filename),
                        start,
                        end
                    )
                    audio_file = audio_filename
                    print(f"    剪切音频: {audio_filename} ({start:.1f}s - {end:.1f}s)")
                except Exception as e:
                    print(f"    音频剪切失败: {e}")

            # 翻译例句为中文
            try:
                from translator import translate_to_chinese
                chinese_translation = translate_to_chinese(sentence)
            except Exception as e:
                print(f"    翻译失败: {e}")
                chinese_translation = ""

            examples.append({
                "sentence": sentence,
                "chineseTranslation": chinese_translation,
                "audioFile": audio_file
            })

        entries.append({
            "word": word,
            "meaningEN": definition.get("meaningEN", ""),
            "meaningCN": definition.get("meaningCN", ""),
            "partOfSpeech": definition.get("partOfSpeech", "noun"),
            "examples": examples
        })

        print(f"  -> '{word}': {len(examples)} 个例句")

    # 导出数据包
    print(f"[6/6] 导出数据包")
    book_name = args.book_name or video_path.stem
    package_data = {
        "version": 1,
        "sourceVideo": video_path.name,
        "wordBookName": book_name,
        "exportedAt": datetime.utcnow().isoformat() + "Z",
        "entries": entries
    }

    # 音频已经在 audio_dir 中，不需再复制
    json_path = export_package(package_data, "", str(output_dir), audio_copy=False)

    print(f"\n完成! 数据包已导出到: {output_dir}")
    print(f"  - JSON: {json_path}")
    print(f"  - 音频: {audio_dir}/")

    # 输出摘要
    total_examples = sum(len(e["examples"]) for e in entries)
    print(f"\n摘要:")
    print(f"  - 生词本: {book_name}")
    print(f"  - 单词数: {len(entries)}")
    print(f"  - 例句数: {total_examples}")


if __name__ == "__main__":
    main()
