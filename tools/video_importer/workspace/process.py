#!/usr/bin/env python3
"""
视频单词导入自动化处理脚本

功能：
1. 扫描 input/ 目录中的视频和单词文件
2. 自动匹配视频和对应的单词列表
3. 运行提取工具生成数据包
4. 替换 APK assets 并构建
5. 生成的 APK 放在 output/ 目录（带时间戳，不覆盖）

用法：
    python process.py [--apk-output PATH] [--skip-build]
"""

import os
import sys
import json
import shutil
import subprocess
import argparse
from pathlib import Path
from datetime import datetime
from typing import Optional

# 项目根目录
PROJECT_ROOT = Path(__file__).parent.parent.parent.parent.resolve()
WORKSPACE_DIR = Path(__file__).parent.resolve()
INPUT_DIR = WORKSPACE_DIR / "input"
OUTPUT_DIR = WORKSPACE_DIR / "output"
PROCESSED_FILE = WORKSPACE_DIR / "processed.json"

# APK assets 目标目录
ASSETS_DIR = PROJECT_ROOT / "app/src/main/assets/video_import"


def load_processed_files() -> set:
    """加载已处理过的文件记录"""
    if PROCESSED_FILE.exists():
        with open(PROCESSED_FILE, "r", encoding="utf-8") as f:
            data = json.load(f)
            return set(data.get("processed", []))
    return set()


def save_processed_files(processed: set):
    """保存已处理过的文件记录"""
    with open(PROCESSED_FILE, "w", encoding="utf-8") as f:
        json.dump({"processed": list(processed)}, f, ensure_ascii=False, indent=2)


def find_video_and_word_pairs(input_dir: Path) -> list[tuple[Path, Path, str]]:
    """
    扫描输入目录，找到视频文件和对应的单词列表文件

    支持两种结构：
    1. 文件夹结构：input/文件夹名/video.mp4, input/文件夹名/words.txt
       文件夹名即为生词本名
    2. 平面结构：input/video.mp4, input/video_words.txt

    Returns:
        (video_path, word_file_path, wordbook_name) 元组列表
    """
    pairs = []
    processed = load_processed_files()
    video_extensions = {".mp4", ".avi", ".mkv", ".mov", ".wmv"}

    # 首先检查是否有子文件夹结构
    subdirs = [d for d in input_dir.iterdir() if d.is_dir()]

    if subdirs:
        # 文件夹结构：每个子文件夹是一个生词本
        for subdir in subdirs:
            wordbook_name = subdir.name  # 文件夹名即为生词本名

            # 在子文件夹中找视频和单词文件
            video_files = []
            for f in subdir.iterdir():
                if f.is_file() and f.suffix.lower() in video_extensions:
                    video_files.append(f)

            for video in video_files:
                # 跳过已处理的
                pair_key = f"{subdir.name}/{video.name}"
                if pair_key in processed:
                    continue

                # 找单词文件
                base = video.stem
                possible_word_files = [
                    subdir / f"{base}_words.txt",
                    subdir / f"{base}.txt",
                    subdir / "words.txt",
                    subdir / "word.txt",
                ]

                word_file = None
                for wf in possible_word_files:
                    if wf.exists():
                        word_file = wf
                        break

                if word_file:
                    pairs.append((video, word_file, wordbook_name))
                    print(f"  [{wordbook_name}] {video.name}")
                else:
                    print(f"  警告: [{wordbook_name}] 找不到 {video.name} 对应的单词文件")

    else:
        # 平面结构：直接在 input 目录下找
        video_files = []
        for f in input_dir.iterdir():
            if f.is_file() and f.suffix.lower() in video_extensions:
                if f.name not in processed:
                    video_files.append(f)

        for video in video_files:
            base = video.stem
            possible_word_files = [
                input_dir / f"{base}_words.txt",
                input_dir / f"{base}.txt",
                input_dir / f"{base.lower()}_words.txt",
                input_dir / f"{base.lower()}.txt",
            ]

            word_file = None
            for wf in possible_word_files:
                if wf.exists():
                    word_file = wf
                    break

            if word_file:
                # 使用视频名作为默认生词本名（稍后会让用户确认）
                pairs.append((video, word_file, video.stem))
                print(f"  找到配对: {video.name} <-> {word_file.name}")
            else:
                print(f"  警告: 找不到 {video.name} 对应的单词文件")

    return pairs


def ask_wordbook_name(video_name: str) -> str:
    """
    交互式询问生词本名称

    Args:
        video_name: 视频文件名（不含扩展名），作为默认名称

    Returns:
        用户输入的生词本名称
    """
    print()
    print("=" * 50)
    print(f"Video: {video_name}")
    print("=" * 50)

    # 读取已有的生词本列表（如果有的话，可以从 app 数据中获取，这里简化处理）
    default_name = video_name.replace("_", " ").replace("-", " ").title()

    while True:
        print()
        user_input = input(f"Please enter wordbook name (press Enter for default: {default_name}): ").strip()

        if not user_input:
            return default_name

        # 简单的名称验证
        if len(user_input) > 50:
            print("  Name too long (max 50 characters)")
            continue

        # 检查是否包含非法字符
        invalid_chars = ['<', '>', ':', '"', '/', '\\', '|', '?', '*']
        if any(c in user_input for c in invalid_chars):
            print(f"  Invalid characters: {invalid_chars}")
            continue

        return user_input


def run_extraction(video_path: Path, word_file: Path, output_dir: Path, book_name: str) -> bool:
    """运行视频提取工具"""
    tool_dir = PROJECT_ROOT / "tools" / "video_importer"

    cmd = [
        sys.executable,
        str(tool_dir / "main.py"),
        "--video", str(video_path),
        "--words", str(word_file),
        "--output", str(output_dir),
        "--book-name", book_name,
    ]

    print(f"  运行提取工具: {' '.join([str(c) for c in cmd[:4]])}...")

    try:
        result = subprocess.run(
            cmd,
            cwd=str(tool_dir),
            capture_output=True,
            text=True,
            timeout=600,  # 10 分钟超时
        )

        if result.returncode != 0:
            print(f"  错误: 提取失败")
            print(f"  stderr: {result.stderr}")
            return False

        print(f"  提取完成")
        return True

    except subprocess.TimeoutExpired:
        print(f"  错误: 提取超时")
        return False
    except Exception as e:
        print(f"  错误: {e}")
        return False


def copy_to_assets(data_dir: Path) -> bool:
    """复制生成的数据到 APK assets 目录"""
    json_file = data_dir / "data.json"
    audio_dir = data_dir / "audio"

    if not json_file.exists():
        print(f"  错误: data.json 不存在")
        return False

    # 复制 JSON
    ASSETS_DIR.mkdir(parents=True, exist_ok=True)
    shutil.copy2(json_file, ASSETS_DIR / "data.json")
    print(f"  已复制: data.json")

    # 复制音频
    if audio_dir.exists():
        audio_target = ASSETS_DIR / "audio"
        if audio_target.exists():
            shutil.rmtree(audio_target)
        shutil.copytree(audio_dir, audio_target)
        print(f"  已复制: audio/")

    return True


def build_apk(output_apk_path: Path) -> bool:
    """构建 APK"""
    print(f"  开始构建 APK...")

    # 设置 JAVA_HOME
    env = os.environ.copy()
    java_home = r"C:\Users\zack\.jdks\jdk-21.0.12.1+1"
    if Path(java_home).exists():
        env["JAVA_HOME"] = java_home

    cmd = [
        str(PROJECT_ROOT / "gradlew.bat"),
        ":app:assembleDebug",
        "--no-daemon",
    ]

    try:
        result = subprocess.run(
            cmd,
            cwd=str(PROJECT_ROOT),
            env=env,
            capture_output=True,
            text=True,
            timeout=600,  # 10 分钟超时
        )

        if result.returncode != 0:
            print(f"  错误: 构建失败")
            print(f"  stderr: {result.stderr[-500:]}")
            return False

        # 找到生成的 APK
        apk_dir = PROJECT_ROOT / "app/build/outputs/apk/debug"
        apk_files = list(apk_dir.glob("app-debug.apk"))

        if not apk_files:
            print(f"  错误: 未找到生成的 APK")
            return False

        # 复制到输出目录
        OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
        shutil.copy2(apk_files[0], output_apk_path)

        print(f"  APK 已生成: {output_apk_path.name}")
        return True

    except subprocess.TimeoutExpired:
        print(f"  错误: 构建超时")
        return False
    except Exception as e:
        print(f"  错误: {e}")
        return False


def generate_output_filename() -> str:
    """生成带时间戳的 APK 文件名"""
    timestamp = datetime.now().strftime("%Y-%m-%d_%H%M%S")

    # 查找今天已经生成的数量
    existing = list(OUTPUT_DIR.glob(f"VocabularyBooster_*.apk"))
    today_count = sum(1 for f in existing if f.stem.startswith(f"VocabularyBooster_{timestamp[:10]}"))

    suffix = f"{today_count + 1:03d}" if today_count > 0 else "001"
    return f"VocabularyBooster_{timestamp}_{suffix}.apk"


def main():
    parser = argparse.ArgumentParser(description="视频单词导入自动化处理")
    parser.add_argument(
        "--apk-output",
        default=None,
        help="APK 输出路径（默认: output/VocabularyBooster_*.apk）"
    )
    parser.add_argument(
        "--skip-build",
        action="store_true",
        help="仅运行提取，不构建 APK"
    )
    args = parser.parse_args()

    print("=" * 50)
    print("视频单词导入自动化处理")
    print("=" * 50)
    print(f"项目目录: {PROJECT_ROOT}")
    print(f"输入目录: {INPUT_DIR}")
    print(f"输出目录: {OUTPUT_DIR}")
    print()

    # 检查输入目录
    if not INPUT_DIR.exists():
        print(f"错误: 输入目录不存在: {INPUT_DIR}")
        sys.exit(1)

    # 查找待处理的文件
    print("[1/4] 扫描输入文件...")
    pairs = find_video_and_word_pairs(INPUT_DIR)
    print(f"  找到 {len(pairs)} 个待处理的文件对")

    if not pairs:
        print("没有需要处理的文件")
        return

    processed = load_processed_files()

    # 处理每个文件对
    for video_path, word_path, default_book_name in pairs:
        print()
        print(f"处理: {video_path.name}")

        # 交互式询问生词本名称（如果是文件夹结构，已有名称为 default_book_name）
        if default_book_name != video_path.stem:
            # 文件夹结构：直接使用文件夹名作为生词本名
            book_name = default_book_name
        else:
            # 平面结构：询问用户
            book_name = ask_wordbook_name(video_path.stem)
        print(f"  Wordbook: {book_name}")

        # 创建临时输出目录
        temp_output = WORKSPACE_DIR / "temp" / video_path.stem
        temp_output.mkdir(parents=True, exist_ok=True)

        # 运行提取
        if not run_extraction(video_path, word_path, temp_output, book_name):
            print(f"  跳过: {video_path.name}")
            continue

        # 复制到 assets
        if not copy_to_assets(temp_output):
            print(f"  跳过: {video_path.name}")
            continue

        # 构建 APK（如果不跳过）
        if not args.skip_build:
            output_filename = args.apk_output or generate_output_filename()
            output_path = OUTPUT_DIR / output_filename

            if not build_apk(output_path):
                print(f"  构建失败: {video_path.name}")
                continue

        # 标记为已处理（使用 folder/video.mp4 格式）
        if video_path.parent.name != "input":
            pair_key = f"{video_path.parent.name}/{video_path.name}"
        else:
            pair_key = video_path.name
        processed.add(pair_key)
        save_processed_files(processed)

        # 清理临时目录
        shutil.rmtree(temp_output, ignore_errors=True)

    print()
    print("=" * 50)
    print("处理完成!")
    if not args.skip_build:
        print(f"APK 文件在: {OUTPUT_DIR}")
    print("=" * 50)


if __name__ == "__main__":
    main()
