# -*- coding: utf-8 -*-
"""SCR-EXREVIEW：例句译文术语审校批写库（资产 v9 -> v10）。

分工（本机 CPython 全表扫描确定性段错误——重活在 review_plan.js / Node：
  node review_plan.js   # 全表扫描 -> %TEMP%/enrich_work/review_plan.json
                        # {updates:{word:examplesJson}, stats, llmOverrides, missed}
本脚本只做小循环 + SQLite C 侧扫描：
  apply   = 备份 v9 -> 单事务 UPDATE（仅计划行）-> user_version=10 -> 校验 ->
            llm_out.jsonl 追加覆盖条目（校验全过后才追加）
  --check = 独立复核（对备份 + 计划文件，不写）
"""
import json
import os
import shutil
import sqlite3
import sys

sys.stdout.reconfigure(encoding="utf-8", errors="replace", line_buffering=True)

HERE = os.path.dirname(__file__)
ASSET = os.path.normpath(os.path.join(HERE, "..", "..", "app", "src", "main", "assets", "dict", "ecdict.sqlite"))
WORK = os.path.join(os.environ["TEMP"], "enrich_work")
PLAN = os.path.join(WORK, "review_plan.json")
BACKUP = os.path.join(WORK, "ecdict.sqlite.v9.bak")
LLM_OUT = os.path.join(WORK, "llm_out.jsonl")
SRC_VERSION, DST_VERSION = 9, 10
NAUTILOID_ZH = "鹦鹉螺壳是古代海洋生物以化石形式保存下来的一个迷人例子。"


def load_plan():
    if not os.path.exists(PLAN):
        sys.exit(f"FATAL: 缺 {PLAN}（先跑 node review_plan.js）")
    p = json.load(open(PLAN, encoding="utf-8"))
    if p.get("missed"):
        sys.exit(f"FATAL: 计划含 {len(p['missed'])} 个未命中键（重跑 review_plan.js 看明细）")
    return p


def verify(con, updates, stats, sample_pre):
    ver = con.execute("PRAGMA user_version").fetchone()[0]
    if ver != DST_VERSION:
        sys.exit(f"FATAL: user_version={ver} != {DST_VERSION}")
    total = con.execute("SELECT COUNT(*) FROM DictEntry").fetchone()[0]
    if total != stats["total"]:
        sys.exit(f"FATAL: 行数 {total} != 计划前 {stats['total']}")
    # 1) nautiloid probe（手工签收译文必须在位）
    na = json.loads(con.execute("SELECT examples FROM DictEntry WHERE normalized='nautiloid'").fetchone()[0])
    if NAUTILOID_ZH not in [e[1] for e in na]:
        sys.exit(f"FATAL: nautiloid 译文未生效 {[e[1] for e in na]}")
    # 2) 全库珍珠层归零（SQLite C 侧扫描）
    m = con.execute("SELECT COUNT(*) FROM DictEntry WHERE examples LIKE '%珍珠层%'").fetchone()[0]
    if m:
        sys.exit(f"FATAL: 仍有 {m} 行含珍珠层")
    # 3) 计划行逐一复核（小循环：仅 ~6k 行）
    n_ok = 0
    for w, want in updates.items():
        row = con.execute("SELECT examples FROM DictEntry WHERE normalized=?", (w,)).fetchone()
        if not row or row[0] != want:
            sys.exit(f"FATAL: 计划行不符 {w}")
        n_ok += 1
    if n_ok != stats["rows"]:
        sys.exit(f"FATAL: 实改行数 {n_ok} != 计划 {stats['rows']}")
    # 4) 未计划行零改动（更新前抽 2000 行原文，更新后比对）
    same = sum(1 for w, ex in sample_pre if
               con.execute("SELECT examples FROM DictEntry WHERE normalized=?", (w,)).fetchone()[0] == ex)
    if same != len(sample_pre):
        sys.exit(f"FATAL: 抽样未计划行被改动 {len(sample_pre) - same} 处")
    print(f"校验通过：实改 {n_ok} 行（fix {stats['fix']} + regen {stats['regen']}，ok {stats['ok']} 保留）；"
          f"nautiloid probe ✓；珍珠层=0 ✓；抽样 {same} 行零改动 ✓")


def main():
    arg = sys.argv[1] if len(sys.argv) > 1 else ""
    if arg not in ("apply", "--check"):
        sys.exit(__doc__)
    plan = load_plan()
    updates, stats = plan["updates"], plan["stats"]
    sample_pre = [tuple(x) for x in plan["sample"]]
    print(f"计划：fix {stats['fix']} / regen {stats['regen']} / ok {stats['ok']}；实改行 {stats['rows']}；抽样 {len(sample_pre)}")

    if arg == "--check":
        con = sqlite3.connect(ASSET)
        verify(con, updates, stats, sample_pre)
        return

    con = sqlite3.connect(ASSET)
    ver = con.execute("PRAGMA user_version").fetchone()[0]
    if ver != SRC_VERSION:
        sys.exit(f"FATAL: 资产 user_version={ver}，期望 {SRC_VERSION}")
    con.close()

    print(f"备份 v9 -> {BACKUP} ...")
    if os.path.exists(BACKUP):
        os.remove(BACKUP)
    shutil.copy2(ASSET, BACKUP)

    con = sqlite3.connect(ASSET)
    con.execute("BEGIN IMMEDIATE")
    con.executemany("UPDATE DictEntry SET examples=? WHERE normalized=?",
                    [(v, k) for k, v in updates.items()])
    con.execute(f"PRAGMA user_version={DST_VERSION}")
    con.commit()
    con.close()

    con = sqlite3.connect(ASSET)
    verify(con, updates, stats, sample_pre)
    con.close()

    # 校验全过后才追加重建覆盖（build_v6 Map.set 后写覆盖语义）。
    # 幂等：只扫尾部 30MB（追加总在文件末尾；全量载入 set 会踩 CPython 大字典段错误）
    existing = set()
    if os.path.exists(LLM_OUT):
        with open(LLM_OUT, "rb") as f:
            f.seek(0, os.SEEK_END)
            f.seek(max(0, f.tell() - 30 * 1024 * 1024))
            for raw in f.read().decode("utf-8", "replace").split("\n")[1:]:
                if raw.strip():
                    existing.add(raw)
    n_append = 0
    with open(LLM_OUT, "a", encoding="utf-8", newline="\n") as f:
        for o in plan["llmOverrides"]:
            line = json.dumps(o, ensure_ascii=False, separators=(",", ":"))
            if line in existing:
                continue
            f.write(line + "\n")
            n_append += 1
    print(f"llm_out.jsonl 追加 {n_append} 条 g: 覆盖（重建安全）")
    print(f"资产 {os.path.getsize(ASSET):,} 字节（备份 {os.path.getsize(BACKUP):,}）")


if __name__ == "__main__":
    main()
