#!/usr/bin/env python3
"""
端内实时神经 TTS 依赖落地（构建机一次性，FR-23 v2，SPEC_CHANGE_REQUEST_PIPER_TTS §3.1）。

下载（官方 GitHub release，均不入 git；cache/ 幂等跳过）：
  1. sherpa-onnx v1.13.8 Android 原生库
     → shared/src/androidMain/jniLibs/arm64-v8a/*.so（libsherpa-onnx-jni.so / libonnxruntime.so 等）
  2. vits-piper-en_US-lessac-medium + vits-piper-en_GB-alan-medium 模型
     → app/src/main/assets/tts/piper/{en_US,en_GB}/{*.onnx, tokens.txt}
     espeak-ng-data：两包逐文件 sha256 比对——一致 → 共享 assets/tts/piper/espeak-ng-data；
     不一致 → 各自 en_XX/espeak-ng-data（打印结果，SherpaOnnxSpeechSynthesizer 的 dataDir
     常量须与判定一致；当前代码按「共享」编写，若脚本判定不一致需同步改常量再打包）。

换 voice：改下方 EN_GB_VOICE（如 alba/cori/semaine，见 tts-models release 列表）重跑本脚本。

用法：python tools/tts-neural/fetch_deps.py
"""

import hashlib
import shutil
import subprocess
import sys
import tarfile
from pathlib import Path

sys.stdout.reconfigure(encoding="utf-8")
sys.stderr.reconfigure(encoding="utf-8")

TOOLS = Path(__file__).resolve().parent
CACHE = TOOLS / "cache"
ROOT = TOOLS.parents[1]
JNI_LIBS_DST = ROOT / "shared" / "src" / "androidMain" / "jniLibs" / "arm64-v8a"
ASSETS_PIPER = ROOT / "app" / "src" / "main" / "assets" / "tts" / "piper"

SHERPA_VERSION = "1.13.8"
EN_GB_VOICE = "alan"  # 换英音 voice 改这里（release tag tts-models 下 vits-piper-en_GB-*）

SHERPA_URL = (
    f"https://github.com/k2-fsa/sherpa-onnx/releases/download/v{SHERPA_VERSION}/"
    f"sherpa-onnx-v{SHERPA_VERSION}-android.tar.bz2"
)
MODELS = [
    ("en_US", "lessac"),
    ("en_GB", EN_GB_VOICE),
]


def download(url: str, dst: Path) -> None:
    if dst.exists() and dst.stat().st_size > 0:
        print(f"skip (cached): {dst.name}")
        return
    dst.parent.mkdir(parents=True, exist_ok=True)
    print(f"downloading: {url}")
    # --ssl-no-revoke：本机网络对 github 证书吊销查询失败的既成配置（tools/tts 同款）
    subprocess.run(
        ["curl", "-sSL", "--ssl-no-revoke", "-o", str(dst.with_suffix(".part")), url],
        check=True,
    )
    dst.with_suffix(".part").rename(dst)
    print(f"  -> {dst.stat().st_size / 1e6:.1f} MB")


def extract(tar_path: Path, target_dir: Path) -> Path:
    """解包到 target_dir/<tar 主名>/（幂等：已存在即跳过），返回该目录。"""
    out = target_dir / tar_path.name[: -len(".tar.bz2")]
    if out.is_dir() and any(out.iterdir()):
        print(f"skip (extracted): {out.name}/")
        return out
    out.mkdir(parents=True, exist_ok=True)
    print(f"extracting: {tar_path.name}")
    with tarfile.open(tar_path, "r:bz2") as tf:
        tf.extractall(out)
    return out


def tree_digest(root: Path) -> list[tuple[str, str, str]]:
    """(relpath, size, sha256) 全树摘要，用于 espeak-ng-data 一致性判定。"""
    items = []
    for f in sorted(root.rglob("*")):
        if f.is_file():
            items.append(
                (
                    f.relative_to(root).as_posix(),
                    str(f.stat().st_size),
                    hashlib.sha256(f.read_bytes()).hexdigest(),
                )
            )
    return items


def main() -> None:
    # ---- 1. sherpa-onnx jniLibs（arm64-v8a）----
    sherpa_tar = CACHE / f"sherpa-onnx-v{SHERPA_VERSION}-android.tar.bz2"
    download(SHERPA_URL, sherpa_tar)
    sherpa_dir = extract(sherpa_tar, CACHE)
    so_src = sherpa_dir / "jniLibs" / "arm64-v8a"
    sos = sorted(so_src.glob("*.so"))
    if not sos:
        sys.exit(f"jniLibs/arm64-v8a 下无 .so：{so_src}（检查解包结构）")
    JNI_LIBS_DST.mkdir(parents=True, exist_ok=True)
    for so in sos:
        dst = JNI_LIBS_DST / so.name
        if not dst.exists():
            shutil.copy2(so, dst)
        print(f"jniLibs: {so.name:28s} {dst.stat().st_size / 1e6:7.1f} MB")

    # ---- 2. 双 Piper 模型 → assets/tts/piper ----
    espeak_digests: dict[str, list] = {}
    for lang, voice in MODELS:
        name = f"vits-piper-{lang}-{voice}-medium"
        url = f"https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/{name}.tar.bz2"
        tar_path = CACHE / f"{name}.tar.bz2"
        download(url, tar_path)
        model_dir = extract(tar_path, CACHE)
        inner = model_dir / name
        onnx = inner / f"{lang}-{voice}-medium.onnx"
        if not onnx.exists():
            sys.exit(f"模型 onnx 缺失：{onnx}")
        dst_dir = ASSETS_PIPER / lang
        dst_dir.mkdir(parents=True, exist_ok=True)
        shutil.copy2(onnx, dst_dir / onnx.name)
        shutil.copy2(inner / "tokens.txt", dst_dir / "tokens.txt")
        espeak_digests[lang] = tree_digest(inner / "espeak-ng-data")
        print(f"model: {lang} onnx {onnx.stat().st_size / 1e6:.1f} MB + tokens.txt")

    # ---- 3. espeak-ng-data：一致则共享，否则各自随包 ----
    if espeak_digests["en_US"] == espeak_digests["en_GB"]:
        shared = ASSETS_PIPER / "espeak-ng-data"
        if not shared.exists():
            lang, voice = MODELS[0]
            shutil.copytree(
                extract(CACHE / f"vits-piper-{lang}-{voice}-medium.tar.bz2", CACHE)
                / f"vits-piper-{lang}-{voice}-medium"
                / "espeak-ng-data",
                shared,
            )
        n = sum(1 for f in shared.rglob("*") if f.is_file())
        print(f"espeak-ng-data: 两包一致 -> 共享 {shared.relative_to(ROOT)}（{n} 文件）")
    else:
        for lang, voice in MODELS:
            src = (
                extract(CACHE / f"vits-piper-{lang}-{voice}-medium.tar.bz2", CACHE)
                / f"vits-piper-{lang}-{voice}-medium"
                / "espeak-ng-data"
            )
            shutil.copytree(src, ASSETS_PIPER / lang / "espeak-ng-data", dirs_exist_ok=True)
        print("espeak-ng-data: 两包不一致 -> 各自随包（需同步 SherpaOnnxSpeechSynthesizer dataDir 常量！）")

    total = sum(f.stat().st_size for f in ASSETS_PIPER.rglob("*") if f.is_file())
    print(f"assets/tts/piper 合计 {total / 1e6:.1f} MB")


if __name__ == "__main__":
    main()
