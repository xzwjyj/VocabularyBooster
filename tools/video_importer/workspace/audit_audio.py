#!/usr/bin/env python3
"""取证：whisper 转写 assets 例句音频，比对 data.json 句子（词级末端对齐）。"""
import json, re, sys
from pathlib import Path

ROOT = Path(__file__).parents[3]
ASSETS = ROOT / "app/src/main/assets/video_import/audio"
DATA = ROOT / "app/src/main/assets/video_import/data.json"

def norm(t):
    return re.sub(r"\s+", " ", re.sub(r"[^a-z0-9 ]", " ", t.lower())).strip()

data = json.loads(DATA.read_text(encoding="utf-8"))
import whisper
model = whisper.load_model("base")

for e in data["entries"]:
    for ex in e["examples"]:
        af = ex.get("audioFile")
        if not af:
            continue
        p = ASSETS / af
        r = model.transcribe(str(p), language="en", word_timestamps=True)
        heard = " ".join(s["text"].strip() for s in r["segments"])
        words = [w for s in r["segments"] for w in (s.get("words") or [])]
        last_end = words[-1]["end"] if words else 0.0
        want, got = norm(ex["sentence"]), norm(heard)
        # 句尾覆盖判定：句子末 3 词是否都出现在转写里
        tail = want.split()[-3:]
        tail_ok = all(w in got.split() for w in tail)
        print(f"[{'OK ' if tail_ok else 'BAD'}] {af} dur={r['segments'][-1]['end'] if r['segments'] else 0:.2f} lastword_end={last_end:.2f} tail={' '.join(tail)}")
        if not tail_ok:
            print(f"      want: {want}")
            print(f"      got : {got}")
