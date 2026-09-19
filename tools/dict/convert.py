"""ECDICT stardict.db -> bundled read-only dictionary asset (FR-18, Phase 8.6).

Input : tools/dict/stardict.db  (ECDICT 1.0.28 sqlite release, MIT license,
        https://github.com/skywind3000/ECDICT — download see README.md)
Output: app/src/main/assets/dict/ecdict.sqlite

Subset (bundle scale ~82 万, user-approved "全量 ~77 万词" scope):
  bnc IS NOT NULL (frequency-listed; unlisted tail = junk) AND word matches
  [A-Za-z][A-Za-z \-']* AND 1 <= len <= 40.

DictEntry(normalized TEXT PRIMARY KEY, text, ipa, definitions) WITHOUT ROWID;
normalized = word.strip().lower() — byte-identical to shared
String.toNormalizedWordText() (trim().lowercase()), the exact key the app
queries. definitions = compact JSON array-of-arrays
[["noun","meaningEN","meaningCN"],...] — androidMain BundledDictionaryProvider
decodes List<List<String>> and assigns partOfSpeechOrder per DOMAIN_MODEL §3.1.
Examples stay empty (sentence contract = curated seed only, FR-3 note).

Usage: PYTHONIOENCODING=utf-8 python convert.py [--probe]
"""
import json
import re
import sqlite3
import sys
import time

SRC = "stardict.db"
OUT = "../../app/src/main/assets/dict/ecdict.sqlite"

# ECDICT line prefix -> DOMAIN_MODEL §3.1 canonical name (vt./vi./aux. -> verb etc.)
PREFIX_POS = {
    "n": "noun", "v": "verb", "vt": "verb", "vi": "verb", "aux": "verb",
    "a": "adjective", "adj": "adjective", "ad": "adverb", "adv": "adverb",
    "pron": "pronoun", "prep": "preposition", "conj": "conjunction",
    "interj": "interjection", "int": "interjection",
    "art": "determiner", "det": "determiner", "num": "numeral",
    "abbr": "other",
}
POS_ORDER = {
    "verb": 0, "noun": 1, "adjective": 2, "adverb": 3, "pronoun": 4,
    "preposition": 5, "conjunction": 6, "interjection": 7, "determiner": 8,
    "numeral": 9, "phrase": 10, "other": 90,
}
PREFIX_RE = re.compile(r"^\(?([a-zA-Z]{1,6})\)?\.?\s+")
WORD_RE = re.compile(r"^[A-Za-z][A-Za-z \'-]*$")
MAX_SENSES = 8  # per-word cap; first senses carry the core meanings


def parse_lines(text):
    """-> list[(pos|None, meaning)] ; continuation lines inherit previous pos."""
    out = []
    for raw in (text or "").replace("\r\n", "\n").replace("\r", "\n").split("\n"):
        line = raw.strip(" \t;；,，")
        if not line:
            continue
        m = PREFIX_RE.match(line)
        pos = PREFIX_POS.get(m.group(1).lower()) if m else None
        if pos is not None:
            out.append([pos, line[m.end():].strip()])
        elif out:
            out[-1][1] += "；" + line  # 无前缀行 = 上一义项延续（[医]/[网络] 等标注行）
        else:
            out.append([None, line])
    return out


def build_definitions(definition, translation):
    en = parse_lines(definition)
    cn = parse_lines(translation)
    if not en and not cn:
        return None
    # EN↔CN 只在双方都有第 i 行时按位配对；剩余行单侧成条（不伪造关联，缺侧存空串）
    rows = []
    for i in range(min(len(en), len(cn))):
        pos = en[i][0] or cn[i][0] or "other"
        rows.append((POS_ORDER.get(pos, 90), pos, en[i][1], cn[i][1]))
    for e_pos, e_txt in en[len(cn):]:
        pos = e_pos or "other"
        rows.append((POS_ORDER.get(pos, 90), pos, e_txt, ""))
    for c_pos, c_txt in cn[len(en):]:
        pos = c_pos or "other"
        rows.append((POS_ORDER.get(pos, 90), pos, "", c_txt))
    # 铁律 3：(partOfSpeechOrder ASC, definitionOrder ASC)；同词性连续
    rows.sort(key=lambda r: r[0])
    trimmed = rows[:MAX_SENSES]
    packed = []
    order_by_pos = {}
    for order, pos, meaning_en, meaning_cn in trimmed:
        idx = order_by_pos.get(pos, 0)
        order_by_pos[pos] = idx + 1
        packed.append((pos, meaning_en, meaning_cn, order, idx))
    return json.dumps([(p, e, c) for p, e, c, _, _ in packed],
                      ensure_ascii=False, separators=(",", ":"))


def main():
    probe_only = "--probe" in sys.argv
    src = sqlite3.connect(SRC)
    cur = src.cursor()
    cur.execute(
        "SELECT word, phonetic, definition, translation FROM stardict "
        "WHERE bnc IS NOT NULL"
    )
    out_rows = []
    stats = {"total": 0, "skipped_charset": 0, "skipped_length": 0,
             "skipped_empty": 0, "phrases": 0, "with_ipa": 0}
    t0 = time.time()
    while True:
        batch = cur.fetchmany(50_000)
        if not batch:
            break
        for word, phonetic, definition, translation in batch:
            stats["total"] += 1
            w = word.strip()
            if not (1 <= len(w) <= 40) or not WORD_RE.match(w):
                stats["skipped_charset" if not WORD_RE.match(w) else "skipped_length"] += 1
                continue
            definitions = build_definitions(definition, translation)
            if definitions is None:
                stats["skipped_empty"] += 1
                continue
            ipa = (phonetic or "").strip()
            out_rows.append((w.lower(), w, ipa, definitions))
            if " " in w:
                stats["phrases"] += 1
            if ipa:
                stats["with_ipa"] += 1
        print(f"  scanned {stats['total']:,} kept {len(out_rows):,} ({time.time()-t0:.0f}s)", flush=True)
        if probe_only and stats["total"] >= 100_000:
            break
    src.close()

    print("stats:", stats)
    print("kept:", len(out_rows))
    payload = sum(len(r[3]) + len(r[0]) + len(r[1]) + len(r[2]) for r in out_rows)
    print(f"payload chars: {payload/1e6:.1f} MB")
    if probe_only:
        for r in out_rows[:3]:
            print(r)
        return

    import os
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    if os.path.exists(OUT):
        os.remove(OUT)
    dst = sqlite3.connect(OUT)
    dst.execute(
        "CREATE TABLE DictEntry("
        "normalized TEXT PRIMARY KEY,"
        "text TEXT NOT NULL,"
        "ipa TEXT NOT NULL,"
        "definitions TEXT NOT NULL) WITHOUT ROWID"
    )
    dst.executemany("INSERT OR IGNORE INTO DictEntry VALUES (?,?,?,?)", out_rows)
    dst.commit()
    dst.execute("VACUUM")
    n = dst.execute("SELECT COUNT(*) FROM DictEntry").fetchone()[0]
    dst.close()
    print(f"wrote {OUT}: {n:,} entries")


if __name__ == "__main__":
    main()
