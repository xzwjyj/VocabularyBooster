#!/usr/bin/env python3
"""
端内实时神经 TTS 依赖落地（构建机一次性，FR-23 v2，SPEC_CHANGE_REQUEST_PIPER_TTS §3.1）。

下载（官方 GitHub release，均不入 git；cache/ 幂等跳过）：
  1. sherpa-onnx v1.13.8 Android 原生库
     → shared/src/androidMain/jniLibs/arm64-v8a/*.so（libsherpa-onnx-jni.so / libonnxruntime.so 等）
  2. vits-piper-en_US-lessac-medium + vits-piper-en_GB-alan-medium 模型（FR-23 EN 段）
     → app/src/main/assets/tts/piper/{en_US,en_GB}/{*.onnx, tokens.txt}
     espeak-ng-data：两包逐文件 sha256 比对——一致 → 共享 assets/tts/piper/espeak-ng-data；
     不一致 → 各自 en_XX/espeak-ng-data（打印结果，SherpaOnnxSpeechSynthesizer 的 dataDir
     常量须与判定一致；当前代码按「共享」编写，若脚本判定不一致需同步改常量再打包）。
  3. vits-melo-tts-zh_en（FR-24 中文段；zh+en 混合单说话人女声 / 44100Hz，MIT）
     → app/src/main/assets/tts/melo/：model.onnx（fp32 170MB；tarball 内 model.int8.onnx
       为 git-lfs 指针桩不可用）/ lexicon.txt（zh+en 合并词典）/ tokens.txt /
       date.fst + number.fst + phone.fst（ruleFsts）/ new_heteronym.fst（ruleFars）/
       dict/（jieba，排除 README.md）。
     kokoro int8（v1.1-zh）装机人耳否决（2026-09-20 vivo，「还不如系统 TTS」）——ADR-012
     预授权备胎切换；脚本会清掉残留的 assets/tts/kokoro/。

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

# ---- FR-24 v3: zipvoice-distill-int8-zh-en-emilia（2026-09-21）----
# 零样本中文 TTS（flow-based，encoder→decoder→vocos 管线）；需外置 vocoder。
# melo 人耳否决（「读句子节奏不自然」）→ 换 zipvoice 复验。

ZIPVOICE_NAME = "sherpa-onnx-zipvoice-distill-int8-zh-en-emilia"
ZIPVOICE_URL = (
    f"https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/{ZIPVOICE_NAME}.tar.bz2"
)
VOCOS_URL = (
    "https://github.com/k2-fsa/sherpa-onnx/releases/download/vocoder-models/vocos_24khz.onnx"
)
ASSETS_ZIPVOICE = ROOT / "app" / "src" / "main" / "assets" / "tts" / "zipvoice"
# zipvoice 模型文件清单
ZIPVOICE_FILES = [
    "encoder.int8.onnx",
    "decoder.int8.onnx",
    "tokens.txt",
    "lexicon.txt",
]
# 保留 melo 资产以备回退（用户未确认前不删）；zipvoice 成功后再删 MELO_ASSET_ROOT


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

    # ---- 4. zipvoice-distill-int8（FR-24 v3：melo 人耳否决后换 zipvoice 复验）
    #         → assets/tts/zipvoice ----
    # vocos 声码器（24kHz）：外置独立模型，非 zipvoice tarball 内
    vocos_tar = CACHE / "vocos_24khz.onnx"
    download(VOCOS_URL, vocos_tar)

    zipvoice_tar = CACHE / f"{ZIPVOICE_NAME}.tar.bz2"
    download(ZIPVOICE_URL, zipvoice_tar)
    zipvoice_dir = extract(zipvoice_tar, CACHE)
    # 兼容两种缓存布局
    zipvoice_inner = zipvoice_dir / ZIPVOICE_NAME
    if not (zipvoice_inner / "encoder.int8.onnx").exists():
        zipvoice_inner = zipvoice_dir
    if not (zipvoice_inner / "encoder.int8.onnx").exists():
        sys.exit(f"zipvoice 模型缺失：{zipvoice_dir}")

    ASSETS_ZIPVOICE.mkdir(parents=True, exist_ok=True)

    # 模型文件
    for name in ZIPVOICE_FILES:
        dst = ASSETS_ZIPVOICE / name
        if not dst.exists():
            shutil.copy2(zipvoice_inner / name, dst)
        print(f"zipvoice: {name:22s} {dst.stat().st_size / 1e6:7.1f} MB")

    # vocos 声码器
    dst = ASSETS_ZIPVOICE / "vocos_24khz.onnx"
    if not dst.exists():
        shutil.copy2(vocos_tar, dst)
    print(f"zipvoice: vocos_24khz.onnx {dst.stat().st_size / 1e6:.1f} MB")

    # 参考音色：选 news-female.wav（6.8s 新闻女声，学习场景标准音）
    prompt_wav = "test_wavs/news-female.wav"
    dst = ASSETS_ZIPVOICE / "prompt.wav"
    if not dst.exists():
        shutil.copy2(zipvoice_inner / prompt_wav, dst)
    print(f"zipvoice: prompt.wav {dst.stat().st_size / 1e6:.1f} MB")

    # espeak-ng-data（zipvoice 内置同 melo 需解包）
    src = zipvoice_inner / "espeak-ng-data"
    dst = ASSETS_ZIPVOICE / "espeak-ng-data"
    if not dst.exists():
        shutil.copytree(src, dst)
    n = sum(1 for f in dst.rglob("*") if f.is_file())
    print(f"zipvoice: espeak-ng-data {n} 文件")

    total_z = sum(f.stat().st_size for f in ASSETS_ZIPVOICE.rglob("*") if f.is_file())
    print(f"assets/tts/zipvoice 合计 {total_z / 1e6:.1f} MB")

    # melo 资产业已失效但保留（用户确认 zipvoice OK 后可删）


if __name__ == "__main__":
    main()
