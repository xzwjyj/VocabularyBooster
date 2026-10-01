#!/usr/bin/env node
/**
 * SCR-SENSEATTR stage 3 — rebuild the dictionary asset with per-definition
 * example attribution (format v2: [["en","zh",defIdx(,"g")], ...]).
 *
 *   node build_v6.js           → ../../app/src/main/assets/dict/ecdict.sqlite (user_version=6)
 *   node build_v6.js check     → dry-run stats + coverage audit, no write
 *
 * Merges: v5 asset rows × attributed.json (defIdx + human zh) × llm_out.jsonl
 * (retranslations "t:" + gap-sense generations "g:"). FATAL on any gap sense
 * without a generation or any corpus example without zh — the complete-coverage
 * bar is enforced here, not hoped for downstream.
 */
"use strict";
const fs = require("fs");
const path = require("path");
const { DatabaseSync } = require("node:sqlite");

const TEMP = process.env.TEMP || "/tmp";
const WORK = path.join(TEMP, "enrich_work");
const ASSET = path.join(__dirname, "..", "..", "app", "src", "main", "assets", "dict", "ecdict.sqlite");
const VERSION = 6;

function readJson(name) { return JSON.parse(fs.readFileSync(path.join(WORK, name), "utf8")); }

function loadLlmOut() {
  const map = new Map();
  for (const name of ["llm_out.jsonl"]) {
    const p = path.join(WORK, name);
    if (!fs.existsSync(p)) continue;
    for (const line of fs.readFileSync(p, "utf8").split("\n")) {
      if (!line.trim()) continue;
      try {
        const o = JSON.parse(line);
        map.set(o.k, o);
      } catch { /* torn tail write */ }
    }
  }
  return map;
}

function buildExamples(word, info, llm, problems) {
  const out = [];
  for (const [en, humanZh, di] of info.e) {
    const key = "t:" + word + "#" + di + "#" + en.slice(0, 60);
    const zh = humanZh || (llm.has(key) ? llm.get(key).zh : "");
    if (!zh) problems.push({ kind: "no-translation", word, en: en.slice(0, 60) });
    out.push([en, zh, di]);
  }
  const covered = new Set(info.e.map((e) => e[2]));
  for (let di = 0; di < info.d; di++) {
    if (covered.has(di)) continue;
    const key = "g:" + word + "#" + di;
    const gen = llm.get(key);
    if (!gen || !gen.en || !gen.zh) { problems.push({ kind: "no-generation", word, defIdx: di }); continue; }
    out.push([gen.en, gen.zh, di, "g"]);
  }
  out.sort((a, b) => a[2] - b[2]); // sense order, stable within sense
  return out;
}

function run() {
  const attributed = readJson("attributed.json");
  const llm = loadLlmOut();
  const src = new DatabaseSync(ASSET, { readOnly: true });

  const problems = [];
  let rows = 0, replaced = 0, totalEx = 0, totalGen = 0, noZhWords = 0;
  const batch = [];
  for (const row of src.prepare("SELECT normalized, text, ipa, definitions, examples, ipaBr FROM DictEntry").iterate()) {
    rows++;
    const info = attributed[row.normalized];
    if (row.examples !== "[]") {
      if (!info) { problems.push({ kind: "stale-exampled-word", word: row.normalized }); continue; }
      const ex = buildExamples(row.normalized, info, llm, problems);
      row.examples = JSON.stringify(ex);
      replaced++;
      totalEx += ex.length;
      totalGen += ex.filter((e) => e[3] === "g").length;
      if (ex.some((e) => !e[1])) noZhWords++;
    } else if (info) {
      // scan found candidates but v5 selected none — impossible normally; rebuild anyway
      const ex = buildExamples(row.normalized, info, llm, problems);
      row.examples = JSON.stringify(ex);
      totalEx += ex.length;
      totalGen += ex.filter((e) => e[3] === "g").length;
    }
    batch.push(row);
    if (problems.length > 50) break;
  }
  src.close();

  console.log(`rows=${rows} replaced=${replaced} examples=${totalEx} generated=${totalGen} noZhWords=${noZhWords}`);
  console.log(`problems=${problems.length}` + (problems.length ? ` first=${JSON.stringify(problems.slice(0, 5))}` : ""));
  if (problems.length) {
    console.error("FATAL: coverage problems — fix parked llm_failed.jsonl tasks before building.");
    process.exit(1);
  }
  // spot check
  for (const probe of ["gloss", "take", "run"]) {
    if (attributed[probe]) {
      const p = batch.find((b) => b.normalized === probe);
      console.log(`probe ${probe}: ${p ? p.examples.slice(0, 300) : "MISSING"}`);
    }
  }
  if (process.argv[2] === "check") { console.log("check mode — no write"); return; }

  const tmp = ASSET + ".v6.tmp";
  if (fs.existsSync(tmp)) fs.rmSync(tmp);
  const dst = new DatabaseSync(tmp);
  dst.exec(`CREATE TABLE DictEntry (
            normalized TEXT PRIMARY KEY,
            text TEXT NOT NULL,
            ipa TEXT,
            definitions TEXT NOT NULL,
            examples TEXT NOT NULL
        , ipaBr TEXT) WITHOUT ROWID`);
  const ins = dst.prepare("INSERT INTO DictEntry VALUES (?,?,?,?,?,?)");
  dst.exec("BEGIN");
  for (let i = 0; i < batch.length; i += 5000) {
    for (const r of batch.slice(i, i + 5000)) ins.run(r.normalized, r.text, r.ipa, r.definitions, r.examples, r.ipaBr);
  }
  dst.exec("COMMIT");
  dst.exec(`PRAGMA user_version = ${VERSION}`);
  const cnt = dst.prepare("SELECT COUNT(*) c FROM DictEntry").get().c;
  dst.close();
  // crash-safe swap: only touch the live asset after the new file is fully written.
  // backup goes to WORK — a .bak inside assets/ would be packaged into the APK (+123MB)
  const bak = path.join(WORK, "ecdict.sqlite.v5.bak");
  if (fs.existsSync(bak)) fs.rmSync(bak);
  fs.copyFileSync(ASSET, bak); // WORK is on another drive — renameSync would EXDEV
  fs.rmSync(ASSET);
  fs.renameSync(tmp, ASSET);
  console.log(`WROTE ${cnt} rows user_version=${VERSION} (backup at ${bak})`);
}

run();
