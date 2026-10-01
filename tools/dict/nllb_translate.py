#!/usr/bin/env python
"""SCR-SENSEATTR stage 2b — bulk corpus retranslation via NLLB-200-distilled-1.3B
(ctranslate2 int8 GPU; runs alongside the ollama generation queue, ~1.5GB VRAM).

  python nllb_translate.py sample [n]   → first n tasks, side-by-side print, still checkpointed
  python nllb_translate.py run          → all remaining tasks (resume-safe)

Key format is byte-identical to llm_tasks.js ("t:<word>#<di>#<en[:60]>") and output
appends to the same llm_out.jsonl, so build_v6.js merges both engines seamlessly.
Batched + checkpointed every batch (machine-crash discipline, v4 precedent).
"""
import json
import os
import sys

import ctranslate2
from transformers import AutoTokenizer

WORK = os.path.join(os.environ.get("TEMP", "/tmp"), "enrich_work")
MODEL_DIR = os.environ.get("NLLB_CT2", "C:/Users/zack/vb-llm/nllb-ct2")
SRC, TGT = "eng_Latn", "zho_Hans"
BATCH = 64

out_path = os.path.join(WORK, "llm_out.jsonl")


def load_done():
    done = set()
    if os.path.exists(out_path):
        with open(out_path, encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if not line:
                    continue
                try:
                    done.add(json.loads(line)["k"])
                except Exception:
                    pass  # torn tail write
    return done


def collect_tasks():
    with open(os.path.join(WORK, "attributed.json"), encoding="utf-8") as f:
        attributed = json.load(f)
    tasks = []  # (key, sentence)
    for word, info in attributed.items():
        for en, zh, di in info["e"]:
            if not zh:
                key = "t:%s#%d#%s" % (word, di, en[:60])
                tasks.append((key, en))
    return tasks


def main():
    mode = sys.argv[1] if len(sys.argv) > 1 else "run"
    limit = int(sys.argv[2]) if len(sys.argv) > 2 else 50
    done = load_done()
    tasks = [(k, s) for k, s in collect_tasks() if k not in done]
    if mode == "sample":
        tasks = tasks[:limit]
    print("pending tasks: %d" % len(tasks), flush=True)
    if not tasks:
        return

    translator = ctranslate2.Translator(MODEL_DIR, device="cuda", compute_type="int8_float16")
    tokenizer = AutoTokenizer.from_pretrained(MODEL_DIR, src_lang=SRC, tgt_lang=TGT)

    def encode(text):
        return tokenizer.convert_ids_to_tokens(tokenizer(text).input_ids)

    def decode(tokens):
        return tokenizer.decode(
            tokenizer.convert_tokens_to_ids(tokens), skip_special_tokens=True
        ).strip()

    out = open(out_path, "a", encoding="utf-8")
    done_n = ok = 0
    for i in range(0, len(tasks), BATCH):
        chunk = tasks[i:i + BATCH]
        try:
            results = translator.translate_batch(
                [encode(s) for _, s in chunk],
                target_prefix=[[TGT]] * len(chunk),
                max_decoding_length=256,
                beam_size=4,
            )
        except RuntimeError as e:  # transient CUDA OOM while ollama spikes — halve and retry
            print("batch %d failed (%s), retrying in halves" % (i, str(e)[:120]), flush=True)
            results = []
            half = max(1, len(chunk) // 2)
            for j in range(0, len(chunk), half):
                sub = chunk[j:j + half]
                results += translator.translate_batch(
                    [encode(s) for _, s in sub],
                    target_prefix=[[TGT]] * len(sub),
                    max_decoding_length=256,
                    beam_size=4,
                )
        for (key, src), res in zip(chunk, results):
            zh = decode(res.hypotheses[0][1:])  # drop zho_Hans prefix token
            if zh and any("\u4e00" <= c <= "\u9fff" for c in zh):
                out.write(json.dumps({"k": key, "type": "t", "zh": zh}, ensure_ascii=False) + "\n")
                ok += 1
                if mode == "sample":
                    print("%s\n  => %s" % (src, zh), flush=True)
            else:
                print("BAD [%s]: %s => %r" % (key, src[:50], zh[:50]), flush=True)
        out.flush()
        done_n += len(chunk)
        if done_n % (BATCH * 20) == 0:
            print("  %d/%d ok=%d" % (done_n, len(tasks), ok), flush=True)
    out.close()
    print("DONE %d ok=%d" % (done_n, ok), flush=True)


if __name__ == "__main__":
    main()
