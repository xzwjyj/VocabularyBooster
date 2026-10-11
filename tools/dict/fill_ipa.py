# -*- coding: utf-8 -*-
"""SCR-IPACOVERAGE：单词+短语双音标全量补齐（资产 v8 -> v9）。

espeak-ng stdin 行模式批量音标化：ipa 空缺 <- en-us，ipaBr 空缺 <- en-gb；
既有非空值零触碰；行数守恒断言违例即 FATAL 不写库；写后 PRAGMA user_version=9。

两阶段（本机 CPython 后台 spawn subprocess 段错误——实测定案必须前台跑，见 DECISION_LOG）：
  PYTHONIOENCODING=utf-8 python fill_ipa.py compute   # 阶段A：espeak 全量 -> %TEMP% 缓存（断点可续）
  PYTHONIOENCODING=utf-8 python fill_ipa.py apply     # 阶段B：备份+写库+校验（user_version=9）
  PYTHONIOENCODING=utf-8 python fill_ipa.py --check   # 只跑校验（不写）
引擎就位见 README 第 9 步（msiexec /a 镜像 + UCRT app-local）。
"""
import os
import shutil
import sqlite3
import subprocess
import sys
import time

sys.stdout.reconfigure(encoding="utf-8", errors="replace", line_buffering=True)

HERE = os.path.dirname(__file__)
ASSET = os.path.normpath(os.path.join(HERE, "..", "..", "app", "src", "main", "assets", "dict", "ecdict.sqlite"))
EXE = os.path.join(HERE, "espeak-ng-a", "eSpeak NG", "espeak-ng.exe")
DATA = os.path.join(HERE, "espeak-ng-a", "eSpeak NG", "espeak-ng-data")
WORK = os.path.join(os.environ["TEMP"], "enrich_work")
BACKUP = os.path.join(WORK, "ecdict.sqlite.v8-pre-ipa.bak")
CACHE = {"en-us": os.path.join(WORK, "ipa_fill_us.tsv"), "en-gb": os.path.join(WORK, "ipa_fill_uk.tsv")}
CHUNK = 50_000
SRC_VERSION, DST_VERSION = 8, 9
PROBES = ["flayer", "slayer", "maker", "zebra", "octodecillion"]  # 既有值原样断言（flayer 应双空->双填）


def load_rows():
    con = sqlite3.connect(ASSET)
    ver = con.execute("PRAGMA user_version").fetchone()[0]
    if ver != SRC_VERSION:
        sys.exit(f"FATAL: 资产 user_version={ver}，期望 {SRC_VERSION}（源不对/已填过）")
    rows = con.execute("SELECT normalized, text, ipa, ipaBr FROM DictEntry").fetchall()
    con.close()
    return rows


def espeak_chunk(items, voice):
    """items: list[str]（原样 text，ASCII）；返回等长音标列表。"""
    r = subprocess.run(
        [EXE, "-q", "--ipa", "-v", voice],
        input=("\n".join(items) + "\n").encode("ascii"),
        capture_output=True,
        env=dict(os.environ, ESPEAK_DATA_PATH=DATA),
    )
    if r.returncode != 0:
        sys.exit(f"FATAL: espeak rc={r.returncode} voice={voice}: {r.stderr[:200]!r}")
    lines = r.stdout.decode("utf-8").splitlines()
    if len(lines) != len(items):
        sys.exit(f"FATAL: 行数不守恒 voice={voice}: {len(items)} -> {len(lines)}")
    cleaned = [l.strip().strip('"') for l in lines]
    if any(not c for c in cleaned):
        sys.exit(f"FATAL: 空音标输出 voice={voice}")
    return cleaned


def compute():
    """阶段A：全量 espeak -> 缓存 TSV（done 标记行支持断点续传：重跑跳过已完成 voice）。"""
    t0 = time.time()
    rows = load_rows()
    jobs = {
        "en-us": [t for _, t, ip, _ in rows if ip == ""],
        "en-gb": [t for _, t, _, br in rows if (br or "") == ""],
    }
    print(f"总行 {len(rows)}；缺美音 {len(jobs['en-us'])}；缺英音 {len(jobs['en-gb'])}")
    os.makedirs(WORK, exist_ok=True)
    for voice, items in jobs.items():
        cache = CACHE[voice]
        if os.path.exists(cache):
            print(f"{voice}: 缓存已存在，跳过（{cache}）")
            continue
        tmp = cache + ".part"
        done = sum(1 for _ in open(tmp, encoding="utf-8")) if os.path.exists(tmp) else 0
        if done:
            print(f"{voice}: 断点续传，已有 {done}/{len(items)} 行")
            if done > len(items):
                sys.exit(f"FATAL: .part 行数超需求（{done} > {len(items)}），请删 {tmp} 重来")
        with open(tmp, "a" if done else "w", encoding="utf-8", newline="\n") as f:
            for i in range(done, len(items), CHUNK):
                part = items[i : i + CHUNK]
                for out in espeak_chunk(part, voice):
                    f.write(out.replace("\t", " ").replace("\n", " ") + "\n")
                f.flush()
                print(f"  {voice} chunk {i // CHUNK + 1}: +{len(part)}（累计 {min(i + CHUNK, len(items))}/{len(items)}，{time.time()-t0:.0f}s）")
        if sum(1 for _ in open(tmp, encoding="utf-8")) != len(items):
            sys.exit(f"FATAL: {voice} 缓存行数与需求不符")
        os.replace(tmp, cache)
        print(f"{voice}: 缓存完成 -> {cache}")
    print(f"阶段A 完成，耗时 {time.time()-t0:.0f}s")


def read_cache(path, expect):
    vals = [l.rstrip("\n") for l in open(path, encoding="utf-8")]
    if len(vals) != expect:
        sys.exit(f"FATAL: 缓存 {os.path.basename(path)} 行数 {len(vals)} != 需求 {expect}")
    return vals


def apply():
    """阶段B：备份 -> 读缓存 -> 单事务写库 -> user_version=9 -> 全量校验。"""
    t0 = time.time()
    rows = load_rows()
    n_us = sum(1 for _, _, ip, _ in rows if ip == "")
    n_uk = sum(1 for _, _, _, br in rows if (br or "") == "")
    print(f"总行 {len(rows)}；待填美音 {n_us} / 英音 {n_uk}")
    us = read_cache(CACHE["en-us"], n_us)
    uk = read_cache(CACHE["en-gb"], n_uk)

    print(f"备份 v8 -> {BACKUP} ...")
    if os.path.exists(BACKUP):
        os.remove(BACKUP)
    shutil.copy2(ASSET, BACKUP)

    fills_us, fills_uk = {}, {}
    i_us = i_uk = 0
    for n, _, ip, br in rows:
        if ip == "":
            fills_us[n] = us[i_us]
            i_us += 1
        if (br or "") == "":
            fills_uk[n] = uk[i_uk]
            i_uk += 1
    assert i_us == n_us and i_uk == n_uk

    con = sqlite3.connect(ASSET)
    con.execute("BEGIN IMMEDIATE")
    con.executemany("UPDATE DictEntry SET ipa=? WHERE normalized=?", [(v, k) for k, v in fills_us.items()])
    con.executemany("UPDATE DictEntry SET ipaBr=? WHERE normalized=?", [(v, k) for k, v in fills_uk.items()])
    con.execute(f"PRAGMA user_version={DST_VERSION}")
    con.commit()
    con.close()
    print(f"写库完成：美音 {len(fills_us)} + 英音 {len(fills_uk)}，user_version={DST_VERSION}")

    con = sqlite3.connect(ASSET)
    verify(con, len(rows), {n: ip for n, _, ip, _ in rows}, {n: (br or "") for n, _, _, br in rows})
    print(f"阶段B 完成，耗时 {time.time()-t0:.0f}s；资产 {os.path.getsize(ASSET):,} 字节（备份 {os.path.getsize(BACKUP):,}）")


def check():
    con = sqlite3.connect(ASSET)
    ver = con.execute("PRAGMA user_version").fetchone()[0]
    if ver != DST_VERSION:
        sys.exit(f"FATAL: user_version={ver} != {DST_VERSION}")
    rows = con.execute("SELECT normalized, ipa, ipaBr FROM DictEntry").fetchall()
    verify(con, len(rows), {n: ip for n, ip, _ in rows}, {n: (br or "") for n, _, br in rows}, filled_only=True)


def verify(con, total, pre_ipa, pre_br, filled_only=False):
    n, has_ip, has_br = con.execute(
        "SELECT COUNT(*), SUM(ipa<>''), SUM(ipaBr IS NOT NULL AND ipaBr<>'') FROM DictEntry"
    ).fetchone()
    if (n, has_ip, has_br) != (total, total, total):
        sys.exit(f"FATAL: 覆盖率 rows={n} ipa={has_ip} ipaBr={has_br}，期望全 {total}")
    changed_bad = 0
    for n, ip, br in con.execute("SELECT normalized, ipa, ipaBr FROM DictEntry"):
        p_ip, p_br = pre_ipa.get(n), pre_br.get(n)
        if p_ip and ip != p_ip:
            changed_bad += 1
        if p_br and (br or "") != p_br:
            changed_bad += 1
    if changed_bad:
        sys.exit(f"FATAL: 既有非空值被改动 {changed_bad} 处")
    if not filled_only:
        ip, br = con.execute("SELECT ipa, ipaBr FROM DictEntry WHERE normalized='flayer'").fetchone()
        if not ip or not br:
            sys.exit(f"FATAL: flayer 未补齐 ({ip!r},{br!r})")
        print(f"flayer: US {ip} / UK {br}")
    print("校验通过：全量双列覆盖 + 既有值零改动")


def main():
    arg = sys.argv[1] if len(sys.argv) > 1 else ""
    if arg == "compute":
        compute()
    elif arg == "apply":
        apply()
    elif arg == "--check":
        check()
    else:
        sys.exit(__doc__)


if __name__ == "__main__":
    main()
