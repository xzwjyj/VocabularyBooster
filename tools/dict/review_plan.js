#!/usr/bin/env node
/**
 * SCR-EXREVIEW — build the v10 write plan (Node side of apply_review.py).
 *
 * The plan needs one full pass over the 836k-row asset (match verdicts to
 * examples by word#di#en, compute per-row examples JSON). This machine's
 * CPython deterministically segfaults on that scan shape (DECISION_LOG,
 * AOR-007 class) — heavy scans run on Node/V8, Python only does the write.
 *
 *   node review_plan.js            # -> %TEMP%/enrich_work/review_plan.json
 *                                    {updates: {word: examplesJson}, stats,
 *                                     llmOverrides: [{k,type,en,zh}], missed: []}
 *   node review_plan.js --summary   # print stats only (plan file must exist)
 */
"use strict";
const fs = require("fs");
const path = require("path");

const WORK = path.join(process.env.TEMP || "/tmp", "enrich_work");
const ASSET = path.join(__dirname, "..", "..", "app", "src", "main", "assets", "dict", "ecdict.sqlite");
const OUT = path.join(WORK, "review_plan.json");
const NAUTILOID_ZH = "鹦鹉螺壳是古代海洋生物以化石形式保存下来的一个迷人例子。";
// leading gloss-prefix echo ("尾随 — 狗在森林里…"); unspaced "——" is normal prose
const GLOSS_PREFIX = /^[㐀-鿿]{1,4}\s*[—–]\s+/;
const cleanZh = (zh) => String(zh || "").replace(GLOSS_PREFIX, "");

function loadVerdicts() {
  const verdicts = new Map(); // "word#di#en" -> last verdict line (Map.set = last wins)
  for (const line of fs.readFileSync(path.join(WORK, "review_out.jsonl"), "utf8").split("\n")) {
    if (!line.trim()) continue;
    try { const o = JSON.parse(line); if (o.k && o.k.startsWith("r:")) verdicts.set(o.k.slice(2), o); } catch { /* torn tail */ }
  }
  return verdicts;
}

function main() {
  const summary = process.argv[2] === "--summary";
  if (summary && fs.existsSync(OUT)) {
    const p = JSON.parse(fs.readFileSync(OUT, "utf8"));
    console.log(JSON.stringify(p.stats, null, 1), "missed:", p.missed.length);
    return;
  }
  const { DatabaseSync } = require("node:sqlite");
  const db = new DatabaseSync(ASSET, { readOnly: true });
  const verdicts = loadVerdicts();
  const updates = {};           // word -> new examples JSON string
  const llmOverrides = [];      // {k:"g:word#di", type:"g", en, zh} — rebuild-safe
  const missed = [];            // fix/regen keys with no matching asset example
  const stats = { fix: 0, regen: 0, ok: 0, rows: 0, verdicts: verdicts.size, total: 0 };
  // pre-state sample of UNPLANNED rows (deterministic stride) — apply_review.py
  // verifies they stay byte-identical; kept here so Python never scans the table
  const SAMPLE_N = 2000;
  const sample = [];
  const total = db.prepare("SELECT COUNT(*) c FROM DictEntry").get().c;
  stats.total = total;
  const skip = Math.max(1, Math.floor(total / SAMPLE_N));
  let unchanged = 0;
  const used = new Set();
  for (const row of db.prepare("SELECT normalized, examples FROM DictEntry").iterate()) {
    let exs;
    try { exs = JSON.parse(row.examples); } catch { continue; }
    let changed = false;
    for (const e of exs) {
      if (!Array.isArray(e) || e.length < 3 || typeof e[0] !== "string") continue;
      const key = `${row.normalized}#${e[2]}#${e[0]}`;
      const v = verdicts.get(key);
      if (!v) continue;
      used.add(key);
      if (v.v === "ok") { stats.ok++; continue; }
      const zh = cleanZh(v.zh);
      if (v.v === "fix" && e[1] !== zh) {
        e[1] = zh; changed = true; stats.fix++;
        llmOverrides.push({ k: `g:${row.normalized}#${e[2]}`, type: "g", en: e[0], zh });
      } else if (v.v === "regen" && (e[0] !== v.en || e[1] !== zh)) {
        e[0] = v.en; e[1] = zh; changed = true; stats.regen++;
        llmOverrides.push({ k: `g:${row.normalized}#${e[2]}`, type: "g", en: v.en, zh });
      }
    }
    if (changed) { updates[row.normalized] = JSON.stringify(exs); stats.rows++; }
    else if (unchanged++ % skip === 0 && sample.length < SAMPLE_N) sample.push([row.normalized, row.examples]);
  }
  for (const [k, v] of verdicts) {
    if ((v.v === "fix" || v.v === "regen") && !used.has(k)) missed.push(k);
  }
  db.close();
  // gates before writing the plan file
  const na = updates["nautiloid"];
  if (!na || !na.includes(NAUTILOID_ZH)) { console.error("FATAL: nautiloid hand fix not in plan"); process.exit(1); }
  if (missed.length) { console.error(`FATAL: ${missed.length} fix/regen keys unmatched (e.g. ${missed.slice(0, 3).join(" | ")})`); process.exit(1); }
  fs.writeFileSync(OUT, JSON.stringify({ updates, stats, llmOverrides, missed, sample }));
  console.log(JSON.stringify(stats), "missed:", missed.length);
  console.log("plan ok ->", OUT);
}

main();
