#!/usr/bin/env python3
"""
Enrich ECDICT with Tatoeba examples + Chinese translations.

Input:
  - app/src/main/assets/dict/ecdict.sqlite (produced by convert.py; any examples
    column already present is ignored -- text/ipa/definitions are the only source cols)
  - %TEMP%/sentences.csv  (Tatoeba: id \t lang \t text)
  - %TEMP%/links.csv      (Tatoeba: sentenceId \t translationId, bidirectional)

Output:
  - Rebuilds ecdict.sqlite with examples column = JSON [["en","zh"], ...]
  - PRAGMA user_version = 3 (device-side cache staleness marker; bump on every data revision)

Matching: sentence word n-grams (n=1..5, plus '-'/"'"-joined variants for n>=2)
looked up in the ECDICT normalized-entry set -- covers single words AND phrases.
Profanity-filtered; candidates over-collected then best-picked at build time
(zh-bearing first, then shortest).

Design note: this machine's Python intermittently segfaults on long scans, so every
stage runs as short-lived child processes over line slices (subcommand mode), writing
atomic chunk files into %TEMP%/enrich_work/. The orchestrator skips existing chunks
and retries crashed children -- fully restartable. `python enrich_with_examples.py`
runs the whole pipeline; `python enrich_with_examples.py <subcmd> ...` runs one child.
"""
import json
import os
import re
import sqlite3
import subprocess
import sys
from pathlib import Path

SCRIPT_DIR = Path(__file__).parent
DICT_PATH = SCRIPT_DIR.parent.parent / "app/src/main/assets/dict/ecdict.sqlite"
OUT_TMP = SCRIPT_DIR.parent.parent / "app/src/main/assets/dict/ecdict_new.sqlite"
TEMP = Path(os.environ.get("TEMP", "/tmp"))
SENTENCES = TEMP / "sentences.csv"
LINKS = TEMP / "links.csv"
WORK = TEMP / "enrich_work"  # 本地盘：避开 Synology Drive 同步对分片文件读写的锁干扰

FINAL_EXAMPLES = 4     # 每词条最终例句数
WORD_CANDIDATES = 12   # 单词候选上限（构建时 zh 优先精选，多留候选提高译文命中）
PHRASE_CANDIDATES = 6  # 短语候选上限
MAX_NGRAM = 5
MAX_SENTENCE_LEN = 500
SCAN_SLICE = 200_000   # 小分片：本机 Python 长扫描概率段错误，分片 + 重试兜底
LINK_SLICE = 1_000_000
WORD_RE = re.compile(r"[a-z]+")
# 词边界屏蔽表：Tatoeba 原生语料含粗口/成人内容，学习场景剔除（宁缺毋滥）
BLOCK_RE = re.compile(
    r"\b(fu+ck\w*|shit\w*|bitch\w*|ass(es|hole\w*)?|bastard\w*|cunt\w*|dicks?\b|"
    r"piss\w*|slut\w*|whore\w*|retard\w*|nigg\w*|fag\w*|rap(e[sd]?|ist|ing)|"
    r"porn\w*|nudes?|penis|vagina|boobs?\b|horny|orgasm\w*|blowjob\w*|dildo\w*|"
    r"masturbat\w*|genital\w*|cum(med|ming)?)\b"
)


def entry_cap(entry: str) -> int:
    return WORD_CANDIDATES if " " not in entry and "-" not in entry else PHRASE_CANDIDATES


def sentence_entries(tokens, ec_words):
    """Return ECDICT entries (words + phrases) contained in the token list, in order.

    直接返回列表（不用 generator/全局态——本机解释器对挂起帧有随机损坏）。
    """
    seen = set()
    n_tokens = len(tokens)
    for n in range(1, min(MAX_NGRAM, n_tokens) + 1):
        for gram in zip(*(tokens[j:] for j in range(n))):
            joined = " ".join(gram)
            variants = (joined,) if n == 1 else (joined, "-".join(gram), "'".join(gram))
            for v in variants:
                if v not in seen and v in ec_words:
                    seen.add(v)
    return seen


def write_atomic(path: Path, obj) -> None:
    tmp = path.with_suffix(".tmp")
    tmp.write_text(json.dumps(obj, ensure_ascii=False), encoding="utf-8")
    os.replace(tmp, path)  # Windows: Path.rename fails when target exists


def load_json(path: Path):
    with path.open("r", encoding="utf-8") as f:
        return json.load(f)


# ---------------------------------------------------------------- children

def child_ec_words():
    """Dump ECDICT normalized-entry set once (children read it instead of the DB)."""
    src = sqlite3.connect(str(DICT_PATH))
    words = sorted(r[0] for r in src.execute("SELECT normalized FROM DictEntry"))
    src.close()
    write_atomic(WORK / "ec_words.json", words)
    print(f"ECWORDS {len(words)}")


def child_scan(start: int, end: int):
    """Slice of sentences.csv: candidate example sentences (eng, profanity-filtered) per entry."""
    ec_words = set(load_json(WORK / "ec_words.json"))
    entries = {}  # entry -> [[id, sentence], ...] capped
    ids = {}      # eng sentence id -> any matching entry (links membership filter)
    processed = 0
    with SENTENCES.open("r", encoding="utf-8", errors="ignore") as f:
        for i, line in enumerate(f):
            if i < start:
                continue
            if i >= end:
                break
            processed += 1
            parts = line.rstrip("\n").split("\t", 2)
            if len(parts) < 3 or parts[1] != "eng":
                continue
            sentence = parts[2]
            if len(sentence) > MAX_SENTENCE_LEN or BLOCK_RE.search(sentence.lower()):
                continue
            tokens = WORD_RE.findall(sentence.lower())
            if not tokens:
                continue
            sent_id = parts[0]
            for entry in sentence_entries(tokens, ec_words):
                lst = entries.setdefault(entry, [])
                if len(lst) < entry_cap(entry):
                    lst.append([sent_id, sentence])
                    ids[sent_id] = entry
    write_atomic(WORK / f"p1_{start:09d}.json", {"entries": entries, "ids": ids})
    print(f"PROCESSED {processed}")


def child_cmn(start: int, end: int):
    """Slice of sentences.csv: Mandarin id->text map."""
    cmn = {}
    processed = 0
    with SENTENCES.open("r", encoding="utf-8", errors="ignore") as f:
        for i, line in enumerate(f):
            if i < start:
                continue
            if i >= end:
                break
            processed += 1
            parts = line.rstrip("\n").split("\t", 2)
            if len(parts) >= 3 and parts[1] == "cmn":
                cmn[parts[0]] = parts[2]
    write_atomic(WORK / f"cmn_{start:09d}.json", cmn)
    print(f"PROCESSED {processed}")


def merge_scan():
    """Merge p1 chunks -> entries.json (capped) + eng_ids.json; merge cmn chunks -> cmn.json."""
    entries = {}
    ids = {}
    for f in sorted(WORK.glob("p1_*.json")):
        d = load_json(f)
        for entry, lst in d["entries"].items():
            merged = entries.setdefault(entry, [])
            cap = entry_cap(entry)
            for item in lst:
                if len(merged) < cap:
                    merged.append(item)
        for sid, entry in d["ids"].items():
            ids.setdefault(sid, entry)
        print(f"merged {f.name}")
    write_atomic(WORK / "entries.json", entries)
    write_atomic(WORK / "eng_ids.json", ids)
    cmn = {}
    for f in sorted(WORK.glob("cmn_*.json")):
        cmn.update(load_json(f))
    write_atomic(WORK / "cmn.json", cmn)
    print(f"MERGED entries={len(entries)} eng_ids={len(ids)} cmn={len(cmn)}")


def child_links(start: int, end: int):
    """Slice of links.csv: eng_id -> Chinese translation for kept eng sentences."""
    ids = load_json(WORK / "eng_ids.json")
    cmn = load_json(WORK / "cmn.json")
    zh = {}
    processed = 0
    with LINKS.open("r", encoding="utf-8", errors="ignore") as f:
        for i, line in enumerate(f):
            if i < start:
                continue
            if i >= end:
                break
            processed += 1
            a, _, b = line.rstrip("\n").partition("\t")
            if a in ids and b in cmn and a not in zh:
                zh[a] = cmn[b]
    write_atomic(WORK / f"zh_{start:09d}.json", zh)
    print(f"PROCESSED {processed}")


def merge_zh():
    zh = {}
    for f in sorted(WORK.glob("zh_*.json")):
        zh.update(load_json(f))
    write_atomic(WORK / "id_to_zh.json", zh)
    print(f"MERGED_ZH {len(zh)}")


def pick_final(candidates, id_to_zh):
    """zh-bearing first, then shortest; stable within equal keys. Cap FINAL_EXAMPLES."""
    scored = sorted(
        candidates,
        key=lambda c: (0 if id_to_zh.get(c[0]) else 1, len(c[1])),
    )
    return [[sentence, id_to_zh.get(sent_id, "")] for sent_id, sentence in scored[:FINAL_EXAMPLES]]


def child_build():
    """Rebuild ecdict.sqlite with examples=[["en","zh"],...]; atomic replace on success.

    ipaBr column (merge_ipa.py v5+) is carried through when present, so re-running
    the example pipeline never silently drops British IPA.
    """
    entries = load_json(WORK / "entries.json")
    id_to_zh = load_json(WORK / "id_to_zh.json")
    if OUT_TMP.exists():
        OUT_TMP.unlink()
    out = sqlite3.connect(str(OUT_TMP))
    oc = out.cursor()
    oc.execute("""
        CREATE TABLE DictEntry (
            normalized TEXT PRIMARY KEY,
            text TEXT NOT NULL,
            ipa TEXT,
            definitions TEXT NOT NULL,
            examples TEXT NOT NULL,
            ipaBr TEXT
        ) WITHOUT ROWID
    """)
    src = sqlite3.connect(str(DICT_PATH))
    src.row_factory = sqlite3.Row
    src_cols = {r[1] for r in src.execute("PRAGMA table_info(DictEntry)")}
    src_select = "normalized, text, ipa, definitions" + (", ipaBr" if "ipaBr" in src_cols else ", NULL")
    src_count = src.execute("SELECT COUNT(*) FROM DictEntry").fetchone()[0]
    batch = []
    enriched = translated = written = 0
    for row in src.execute(f"SELECT {src_select} FROM DictEntry"):
        normalized = row["normalized"]
        candidates = entries.get(normalized)
        examples_json = "[]"
        if candidates:
            final = pick_final(candidates, id_to_zh)
            examples_json = json.dumps(final, ensure_ascii=False)
            enriched += 1
            translated += sum(1 for _, zh in final if zh)
        batch.append((normalized, row["text"], row["ipa"], row["definitions"], examples_json, row["ipaBr"]))
        if len(batch) >= 5000:
            oc.executemany("INSERT INTO DictEntry VALUES (?,?,?,?,?,?)", batch)
            out.commit()
            written += len(batch)
            batch = []
            print(f"ROW {written}")
    if batch:
        oc.executemany("INSERT INTO DictEntry VALUES (?,?,?,?,?,?)", batch)
        out.commit()
        written += len(batch)
    oc.execute("PRAGMA user_version = 3")
    out.commit()
    if written != src_count:
        out.close()
        src.close()
        OUT_TMP.unlink()
        print(f"ABORT count mismatch {written} != {src_count}")
        sys.exit(2)
    out.close()
    src.close()
    DICT_PATH.unlink()
    OUT_TMP.rename(DICT_PATH)
    print(f"BUILT rows={written} enriched={enriched} translated={translated} "
          f"size={DICT_PATH.stat().st_size // 1024 // 1024}MB")


# ---------------------------------------------------------------- orchestrator

def run_stage(name: str, make_args, chunk_pattern, slice_size):
    """Run child slices for a stage; skip existing chunks; retry crashed children.

    EOF detection: a slice past the end of the file writes an (almost) empty chunk;
    an existing empty chunk terminates the stage too, so re-runs stop at the same point.
    """
    start = 0
    while True:
        chunk = WORK / (chunk_pattern % start)
        if chunk.exists():
            if chunk.stat().st_size < 64:  # "{}"-class empty payload = past EOF
                break
            start += slice_size
            continue
        args = [sys.executable, "-u", str(Path(__file__).resolve())] + make_args(start, start + slice_size)
        ok = False
        for attempt in range(1, 9):
            print(f"[{name}] slice @{start} attempt {attempt}", flush=True)
            r = subprocess.run(args)
            if r.returncode == 0 and chunk.exists():
                ok = True
                break
        if not ok:
            print(f"[{name}] slice @{start} FAILED after retries")
            sys.exit(1)
        start += slice_size
        if chunk.stat().st_size < 64:  # empty payload = reached EOF
            break


def main():
    for p in (SENTENCES, LINKS, DICT_PATH):
        if not p.exists():
            print(f"ERROR: missing {p}")
            sys.exit(1)
    WORK.mkdir(exist_ok=True)

    if not (WORK / "ec_words.json").exists():
        subprocess.run([sys.executable, "-u", str(Path(__file__).resolve()), "ec"], check=True)

    run_stage("scan", lambda s, e: ["scan", str(s), str(e)], "p1_%09d.json", SCAN_SLICE)
    run_stage("cmn", lambda s, e: ["cmn", str(s), str(e)], "cmn_%09d.json", SCAN_SLICE)
    subprocess.run([sys.executable, "-u", str(Path(__file__).resolve()), "merge_scan"], check=True)
    run_stage("links", lambda s, e: ["links", str(s), str(e)], "zh_%09d.json", LINK_SLICE)
    subprocess.run([sys.executable, "-u", str(Path(__file__).resolve()), "merge_zh"], check=True)
    subprocess.run([sys.executable, "-u", str(Path(__file__).resolve()), "build"], check=True)
    print("Done!")


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else "all"
    if cmd == "all":
        main()
    elif cmd == "ec":
        child_ec_words()
    elif cmd == "scan":
        child_scan(int(sys.argv[2]), int(sys.argv[3]))
    elif cmd == "cmn":
        child_cmn(int(sys.argv[2]), int(sys.argv[3]))
    elif cmd == "merge_scan":
        merge_scan()
    elif cmd == "links":
        child_links(int(sys.argv[2]), int(sys.argv[3]))
    elif cmd == "merge_zh":
        merge_zh()
    elif cmd == "build":
        child_build()
    else:
        print(f"unknown subcommand: {cmd}")
        sys.exit(1)
