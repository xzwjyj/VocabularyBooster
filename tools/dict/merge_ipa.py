#!/usr/bin/env python3
"""
Merge ipa-dict (open-dict-data/ipa-dict, MIT License) into ecdict.sqlite → v5.

- ADD COLUMN DictEntry.ipaBr; en_UK.txt → ipaBr (first candidate, slashes stripped)
- Fill EMPTY DictEntry.ipa from en_US.txt (fill-only, never overwrite)
- PRAGMA user_version = 5 (data revision; devices with older cache recopy automatically)

Input files (tab-separated "word /ipa/ [/ipa2/ ...]", downloaded from
https://github.com/open-dict-data/ipa-dict → data/en_UK.txt + data/en_US.txt):
  %TEMP%/ipadict_en_UK.txt
  %TEMP%/ipadict_en_US.txt
"""
import os
import sqlite3
import sys
from pathlib import Path

SCRIPT_DIR = Path(__file__).parent
DICT_PATH = SCRIPT_DIR.parent.parent / "app/src/main/assets/dict/ecdict.sqlite"
TEMP = Path(os.environ.get("TEMP", "/tmp"))
UK = TEMP / "ipadict_en_UK.txt"
US = TEMP / "ipadict_en_US.txt"
ASSET_VERSION = 5


def load_first_ipa(path: Path):
    """word(lower) -> first IPA candidate without surrounding slashes."""
    m = {}
    with path.open("r", encoding="utf-8") as f:
        for line in f:
            head, sep, rest = line.rstrip("\n").partition("\t")
            if not sep:
                continue
            first = rest.strip().split(" ")[0].strip()
            first = first.strip("/")
            if first:
                m.setdefault(head.strip().lower(), first)
    return m


def main():
    for p in (DICT_PATH, UK, US):
        if not p.exists():
            print(f"ERROR: missing {p}")
            sys.exit(1)

    uk = load_first_ipa(UK)
    us = load_first_ipa(US)
    print(f"loaded en_UK={len(uk)} en_US={len(us)}")

    db = sqlite3.connect(str(DICT_PATH))
    cur = db.cursor()

    cols = [r[1] for r in cur.execute("PRAGMA table_info(DictEntry)")]
    if "ipaBr" not in cols:
        cur.execute("ALTER TABLE DictEntry ADD COLUMN ipaBr TEXT")
        db.commit()
        print("added column ipaBr")

    cur.execute("SELECT COUNT(*) FROM DictEntry")
    total = cur.fetchone()[0]
    cur.execute("SELECT COUNT(*) FROM DictEntry WHERE ipa IS NULL OR ipa = ''")
    empty_ipa_before = cur.fetchone()[0]

    uk_rows = [(ipa, w) for w, ipa in uk.items()]
    us_rows = [(ipa, w) for w, ipa in us.items()]

    cur.executemany(
        "UPDATE DictEntry SET ipaBr = ? WHERE normalized = ? AND (ipaBr IS NULL OR ipaBr = '')",
        uk_rows,
    )
    cur.executemany(
        "UPDATE DictEntry SET ipa = ? WHERE normalized = ? AND (ipa IS NULL OR ipa = '')",
        us_rows,
    )
    db.commit()

    cur.execute("SELECT COUNT(*) FROM DictEntry WHERE ipaBr IS NOT NULL AND ipaBr != ''")
    br = cur.fetchone()[0]
    cur.execute("SELECT COUNT(*) FROM DictEntry WHERE ipa IS NOT NULL AND ipa != ''")
    am = cur.fetchone()[0]
    cur.execute("SELECT COUNT(*) FROM DictEntry WHERE (ipa IS NOT NULL AND ipa != '') AND (ipaBr IS NOT NULL AND ipaBr != '')")
    both = cur.fetchone()[0]

    cur.execute(f"PRAGMA user_version = {ASSET_VERSION}")
    db.commit()
    db.close()

    print(f"rows={total} ipaBr={br} ipaAm={am} (was {total - empty_ipa_before}) both={both}")
    print(f"user_version={ASSET_VERSION} size={DICT_PATH.stat().st_size // 1024 // 1024}MB")


if __name__ == "__main__":
    main()
