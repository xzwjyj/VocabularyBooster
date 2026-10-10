#!/usr/bin/env node
/**
 * SCR-SENSEATTR stage 3 — rebuild the dictionary asset with per-definition
 * example attribution (format v2: [["en","zh",defIdx(,"g")], ...]).
 * v7 (SCR §7): + definition meaningCN fill ("d:" keys) for blank-CN senses,
 * + generated examples for zero-example words ("g:" second batch) → user_version=7.
 * v8: ECDICT superscript-notation garbage CN (10#[57] = 10^57, -illion family,
 * 3 senses) is treated like blank — hand-written "d:" fills replace it.
 *
 *   node build_v6.js           → ../../app/src/main/assets/dict/ecdict.sqlite (user_version=8)
 *   node build_v6.js check     → dry-run stats + coverage audit, no write
 *
 * Merges: v6 asset rows × attributed.json (defIdx + human zh) × llm_out.jsonl
 * (retranslations "t:" + gap-sense generations "g:" + v7 definition fills "d:").
 * FATAL on any gap sense without a generation, any corpus example without zh,
 * or any definition sense with blank CN and no "d:" fill (v7 no-def-cn gate) —
 * the complete-coverage bar is enforced here, not hoped for downstream.
 */
"use strict";
const fs = require("fs");
const path = require("path");
const { DatabaseSync } = require("node:sqlite");

const TEMP = process.env.TEMP || "/tmp";
const WORK = path.join(TEMP, "enrich_work");
const ASSET = path.join(__dirname, "..", "..", "app", "src", "main", "assets", "dict", "ecdict.sqlite");
const VERSION = 8;

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

// v7 (SCR §7): fill blank meaningCN positions from "d:" llm outputs.
// Key formula must stay identical to llm_tasks.js collectBatch2.
// v8: ECDICT superscript notation ("10#[57]" / "10#1#0#8" = 10^57 / 10^108,
// the -illion family) is undecipherable pseudo-CN — treat like blank and let
// a "d:" fill replace it. Garbage WITHOUT a fill keeps the original text
// (counted in stats.defCnGarbageKept) instead of failing the build: the gate
// must only enforce senses the pipeline actually promised to cover.
const CN_NOTATION_GARBAGE = /#\[\d+\]|#\d+#\d+/;
function fillDefinitionCn(word, defsJson, llm, fail, stats) {
  const defs = JSON.parse(defsJson);
  const out = defs.map((d, di) => {
    const cn = String(d[2] || "").trim();
    if (cn && !CN_NOTATION_GARBAGE.test(cn)) return d;
    const key = "d:" + word + "#" + di + "#" + String(d[1] || "").slice(0, 60);
    const hit = llm.get(key);
    if (hit && hit.zh) {
      stats.defCnFilled++;
      // hit.en (hand entries only) fills a blank meaningEN — machine "d:" outputs carry zh alone
      return [d[0], d[1] || hit.en || "", hit.zh];
    }
    if (cn) { stats.defCnGarbageKept++; return d; }
    fail({ kind: "no-def-cn", word, defIdx: di });
    return d;
  });
  return JSON.stringify(out);
}

// v7 second batch: zero-example word — every sense needs a generated example.
function buildGeneratedExamples(word, defsJson, llm, fail, stats) {
  const defs = JSON.parse(defsJson);
  const out = [];
  for (let di = 0; di < defs.length; di++) {
    const key = "g:" + word + "#" + di;
    const gen = llm.get(key);
    if (!gen || !gen.en || !gen.zh) { fail({ kind: "no-generation", word, defIdx: di }); continue; }
    out.push([gen.en, gen.zh, di, "g"]);
  }
  stats.zeroWords++;
  return out;
}

function buildExamples(word, info, llm, fail) {
  const out = [];
  for (const [en, humanZh, di] of info.e) {
    const key = "t:" + word + "#" + di + "#" + en.slice(0, 60);
    const zh = humanZh || (llm.has(key) ? llm.get(key).zh : "");
    if (!zh) fail({ kind: "no-translation", word, en: en.slice(0, 60) });
    out.push([en, zh, di]);
  }
  const covered = new Set(info.e.map((e) => e[2]));
  for (let di = 0; di < info.d; di++) {
    if (covered.has(di)) continue;
    const key = "g:" + word + "#" + di;
    const gen = llm.get(key);
    if (!gen || !gen.en || !gen.zh) { fail({ kind: "no-generation", word, defIdx: di }); continue; }
    out.push([gen.en, gen.zh, di, "g"]);
  }
  out.sort((a, b) => a[2] - b[2]); // sense order, stable within sense
  return out;
}

function run() {
  const attributed = readJson("attributed.json");
  const llm = loadLlmOut();
  const src = new DatabaseSync(ASSET, { readOnly: true });

  // bounded problem ledger: count every failure, keep only first samples per kind
  // (a mid-batch check on 769k unfinished generations must not grow an 80MB array)
  const problems = [];
  const problemKinds = {};
  let problemCount = 0;
  function fail(obj) {
    problemCount++;
    problemKinds[obj.kind] = (problemKinds[obj.kind] || 0) + 1;
    if (problems.length < 50) problems.push(obj);
  }

  let rows = 0, replaced = 0, totalEx = 0, totalGen = 0, noZhWords = 0;
  const stats = { defCnFilled: 0, defCnGarbageKept: 0, zeroWords: 0, zeroCovered: 0 };
  const check = process.argv[2] === "check";
  const batch = [];
  for (const row of src.prepare("SELECT normalized, text, ipa, definitions, examples, ipaBr FROM DictEntry").iterate()) {
    rows++;
    const info = attributed[row.normalized];
    if (row.examples !== "[]") {
      if (!info) { fail({ kind: "stale-exampled-word", word: row.normalized }); continue; }
      const ex = buildExamples(row.normalized, info, llm, fail);
      row.examples = JSON.stringify(ex);
      replaced++;
      totalEx += ex.length;
      totalGen += ex.filter((e) => e[3] === "g").length;
      if (ex.some((e) => !e[1])) noZhWords++;
    } else if (info) {
      // scan found candidates but v5 selected none — impossible normally; rebuild anyway
      const ex = buildExamples(row.normalized, info, llm, fail);
      row.examples = JSON.stringify(ex);
      totalEx += ex.length;
      totalGen += ex.filter((e) => e[3] === "g").length;
    } else {
      // v7 second batch: zero-example word — generated example per sense
      const ex = buildGeneratedExamples(row.normalized, row.definitions, llm, fail, stats);
      if (ex.length === JSON.parse(row.definitions).length) stats.zeroCovered++;
      row.examples = JSON.stringify(ex);
      totalEx += ex.length;
      totalGen += ex.length;
    }
    // v7: blank-CN definition senses must be filled ("d:") — no-def-cn gate
    row.definitions = fillDefinitionCn(row.normalized, row.definitions, llm, fail, stats);
    batch.push(row);
    if (!check && problemCount > 50) break; // check keeps scanning for a full audit
  }
  src.close();

  console.log(`rows=${rows} replaced=${replaced} examples=${totalEx} generated=${totalGen} noZhWords=${noZhWords}`);
  console.log(`v7/v8: defCnFilled=${stats.defCnFilled} defCnGarbageKept=${stats.defCnGarbageKept} zeroWords=${stats.zeroWords} zeroCovered=${stats.zeroCovered}`);
  console.log(`problems=${problemCount} kinds=${JSON.stringify(problemKinds)}` + (problems.length ? ` first=${JSON.stringify(problems.slice(0, 5))}` : ""));
  if (problemCount) {
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
  // v7 probe: metrics definitions must show CN on every sense
  const m = batch.find((b) => b.normalized === "metrics");
  console.log(`probe metrics defs: ${m ? m.definitions.slice(0, 400) : "MISSING"}`);
  // v8 probe: -illion family notation garbage must be replaced with real Chinese
  for (const probe8 of ["octodecillion", "septendecillion", "undecillion"]) {
    const w = batch.find((b) => b.normalized === probe8);
    console.log(`probe ${probe8} defs: ${w ? w.definitions : "MISSING"}`);
  }
  if (check) { console.log("check mode — no write"); return; }

  const tmp = ASSET + ".v8.tmp";
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
  const bak = path.join(WORK, "ecdict.sqlite.v7.bak");
  if (fs.existsSync(bak)) fs.rmSync(bak);
  fs.copyFileSync(ASSET, bak); // WORK is on another drive — renameSync would EXDEV
  fs.rmSync(ASSET);
  fs.renameSync(tmp, ASSET);
  console.log(`WROTE ${cnt} rows user_version=${VERSION} (backup at ${bak})`);
}

run();
