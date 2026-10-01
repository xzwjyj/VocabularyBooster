#!/usr/bin/env python3
"""
Piper 预录单词发音生成管线（构建时，SPEC_CHANGE_REQUEST_PIPER_TTS §3.5）。

词源：FrequencyWords en_full.txt（语料频次降序）→ 前 N 个纯字母单词
合成：Piper en_US-lessac-medium（单次批量进程，模型只加载一遍）
转换：ffmpeg WAV → OGG Vorbis（-q:a 4，语音码率足够）
输出：
  app/src/main/assets/words/audio/{word}.ogg   预录音频本体
  app/src/main/assets/words/audio/index.txt    每行一个 normalizedText
    （AssetsPronunciationAudioCatalog 索引；Media3 经 words:// scheme 流式播放）

前置（cache/ 内，均不入 git）：
  piper/piper.exe                      piper_windows_amd64.zip 解压
  en_US-lessac-medium.onnx(.json)      huggingface rhasspy/piper-voices
  full.txt                             https://github.com/hermitdave/FrequencyWords
                                      content/2018/en/en_full.txt（每行 "word count"）

用法：
  python tools/tts/generate_word_audio.py                 # 默认前 5000 词
  python tools/tts/generate_word_audio.py --limit 200     # 小批试验

重跑安全：WAV 数与词数一致则跳过合成；已存在的 .ogg 跳过转换（增量续跑）。
"""

import argparse
import subprocess
import sys
from pathlib import Path

sys.stdout.reconfigure(encoding="utf-8")
sys.stderr.reconfigure(encoding="utf-8")

TOOLS_TTS = Path(__file__).resolve().parent
CACHE = TOOLS_TTS / "cache"
ASSETS_AUDIO = TOOLS_TTS.parents[1] / "app" / "src" / "main" / "assets" / "words" / "audio"


# Windows 保留设备名（带扩展名同样保留）：NTFS 无法落盘 con.ogg 等文件。
# 这些词不可随包（构建机为 Windows）→ 直接不选，运行时安全走 TTS（索引按实际文件生成）。
WINDOWS_RESERVED = {"con", "prn", "aux", "nul"} | {
    f"{p}{n}" for p in ("com", "lpt") for n in range(1, 10)
}


def load_words(freq_path: Path, limit: int) -> list[str]:
    """频次表前 N 个纯 ASCII 字母单词（去重；跳过缩写/含撇号/非字母 token 与 Windows 保留名）。"""
    words: list[str] = []
    seen: set[str] = set()
    for line in freq_path.read_text(encoding="utf-8").splitlines():
        token = line.split()[0].lower() if line.split() else ""
        if token.isascii() and token.isalpha() and token not in seen and token not in WINDOWS_RESERVED:
            seen.add(token)
            words.append(token)
            if len(words) >= limit:
                break
    return words


def synthesize(words: list[str], piper: Path, model: Path, wav_dir: Path) -> None:
    """批量合成：一次 piper 进程读 stdin 全部词 → wav_dir 下纳秒时间戳命名（升序 = 行序）。"""
    wavs = sorted(wav_dir.glob("*.wav"))
    if len(wavs) == len(words):
        print(f"synthesize: {len(wavs)} 个 WAV 已在位，跳过")
        return
    if wavs:
        sys.exit(f"wav 目录非空但数量不符（{len(wavs)} != {len(words)}）：删除 {wav_dir} 后重跑")
    wav_dir.mkdir(parents=True, exist_ok=True)
    text = ("\n".join(words) + "\n").encode("utf-8")
    # piper 从 stdin 逐行合成，输出命名 = 纳秒时间戳（单调递增 → 排序即输入序）
    subprocess.run(
        [str(piper), "--model", str(model), "--output_dir", str(wav_dir)],
        input=text,
        check=True,
        cwd=str(piper.parent),  # 同目录 dll（espeak-ng/onnxruntime）依赖
    )
    produced = sorted(wav_dir.glob("*.wav"))
    if len(produced) != len(words):
        sys.exit(f"piper 输出 {len(produced)} 个 WAV，期望 {len(words)}：检查日志")


def convert(words: list[str], wav_dir: Path, ffmpeg: str) -> int:
    """WAV → OGG Vorbis；已存在且非空的 .ogg 跳过（增量续跑）。返回成功数。"""
    ASSETS_AUDIO.mkdir(parents=True, exist_ok=True)
    wavs = sorted(wav_dir.glob("*.wav"))
    done = skipped = 0
    for word, wav in zip(words, wavs):
        ogg = ASSETS_AUDIO / f"{word}.ogg"
        if ogg.exists() and ogg.stat().st_size > 0:
            skipped += 1
            continue
        subprocess.run(
            [ffmpeg, "-y", "-loglevel", "error", "-i", str(wav),
             "-c:a", "libvorbis", "-q:a", "4", str(ogg)],
            check=True,
        )
        done += 1
        if (done + skipped) % 500 == 0:
            print(f"convert: {done + skipped}/{len(words)}")
    return done + skipped


def main() -> None:
    ap = argparse.ArgumentParser(description="Piper 预录单词发音生成")
    ap.add_argument("--limit", type=int, default=5000, help="取频次表前 N 词")
    ap.add_argument("--piper", type=Path, default=CACHE / "piper" / "piper.exe")
    ap.add_argument("--model", type=Path, default=CACHE / "en_US-lessac-medium.onnx")
    ap.add_argument("--freq", type=Path, default=CACHE / "full.txt")
    ap.add_argument("--ffmpeg", default="ffmpeg")
    args = ap.parse_args()

    for path in (args.piper, args.model, Path(str(args.model) + ".json"), args.freq):
        if not path.exists():
            sys.exit(f"缺少前置文件: {path}（见脚本头部下载说明）")

    words = load_words(args.freq, args.limit)
    print(f"words: {len(words)}（{words[0]}…{words[-1]}）")

    synthesize(words, args.piper, args.model, CACHE / "wavs")
    covered = convert(words, CACHE / "wavs", args.ffmpeg)

    # index.txt = 实际落盘的词（与 catalog 的行级 normalizedText 约定一致）
    covered_words = sorted(p.stem for p in ASSETS_AUDIO.glob("*.ogg"))
    (ASSETS_AUDIO / "index.txt").write_text("\n".join(covered_words) + "\n", encoding="utf-8")

    total_mb = sum(p.stat().st_size for p in ASSETS_AUDIO.glob("*.ogg")) / 1e6
    print(f"done: {covered} 词转换（共 {len(covered_words)} ogg，{total_mb:.1f} MB）+ index.txt")


if __name__ == "__main__":
    main()
