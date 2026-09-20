#!/usr/bin/env node
/**
 * Tatoeba enrichment stages in Node (V8) — this machine's CPython segfaults
 * deterministically on parts of the corpus scan, so the heavy passes run here.
 *
 *   node enrich_node.js scan   → %TEMP%/enrich_work/entries.json + eng_ids.json
 *   node enrich_node.js cmn    → %TEMP%/enrich_work/cmn.json        (merge of cmn chunks)
 *   node enrich_node.js links  → %TEMP%/enrich_work/id_to_zh.json
 *
 * Matching/blocklist/caps mirror tools/dict/enrich_with_examples.py (v3).
 * Build step (sqlite rebuild) stays in Python: `python enrich_with_examples.py build`.
 */
"use strict";
const fs = require("fs");
const path = require("path");
const readline = require("readline");

const TEMP = process.env.TEMP || "/tmp";
const WORK = path.join(TEMP, "enrich_work");
const SENTENCES = path.join(TEMP, "sentences.csv");
const LINKS = path.join(TEMP, "links.csv");

const FINAL_EXAMPLES = 4;
const WORD_CANDIDATES = 12;
const PHRASE_CANDIDATES = 6;
const MAX_NGRAM = 5;
const MAX_SENTENCE_LEN = 500;

// 词边界屏蔽表：与 Python 版 BLOCK_RE 等价（粗口/成人内容剔除，宁缺毋滥）
const BLOCK_RE = /\b(fu+ck\w*|shit\w*|bitch\w*|ass(es|hole\w*)?|bastard\w*|cunt\w*|dicks?\b|piss\w*|slut\w*|whore\w*|retard\w*|nigg\w*|fag\w*|rap(e[sd]?|ist|ing)|porn\w*|nudes?|penis|vagina|boobs?\b|horny|orgasm\w*|blowjob\w*|dildo\w*|masturbat\w*|genital\w*|cum(med|ming)?)\b/;

const WORD_RE = /[a-z]+/g;

const entryCap = (entry) => (entry.includes(" ") || entry.includes("-")) ? PHRASE_CANDIDATES : WORD_CANDIDATES;

function sentenceEntries(tokens, ecWords) {
  const seen = new Set();
  const nTok = tokens.length;
  for (let n = 1; n <= Math.min(MAX_NGRAM, nTok); n++) {
    for (let i = 0; i + n <= nTok; i++) {
      const gram = tokens.slice(i, i + n);
      const joined = gram.join(" ");
      const variants = n === 1 ? [joined] : [joined, gram.join("-"), gram.join("'")];
      for (const v of variants) {
        if (!seen.has(v) && ecWords.has(v)) seen.add(v);
      }
    }
  }
  return seen;
}

async function scan() {
  const ecWords = new Set(JSON.parse(fs.readFileSync(path.join(WORK, "ec_words.json"), "utf8")));
  const entries = new Map(); // entry -> [[id, sentence], ...] capped
  const ids = new Map();     // eng sentence id -> any matching entry
  let processed = 0;
  const rl = readline.createInterface({ input: fs.createReadStream(SENTENCES, "utf8"), crlfDelay: Infinity });
  for await (const line of rl) {
    const tab1 = line.indexOf("\t");
    if (tab1 < 0) continue;
    const tab2 = line.indexOf("\t", tab1 + 1);
    if (tab2 < 0 || line.slice(tab1 + 1, tab2) !== "eng") continue;
    const sentence = line.slice(tab2 + 1);
    if (sentence.length > MAX_SENTENCE_LEN) continue;
    const lower = sentence.toLowerCase();
    if (BLOCK_RE.test(lower)) continue;
    const tokens = lower.match(WORD_RE);
    if (!tokens) continue;
    processed++;
    const sentId = line.slice(0, tab1);
    for (const entry of sentenceEntries(tokens, ecWords)) {
      let lst = entries.get(entry);
      if (lst === undefined) {
        lst = [];
        entries.set(entry, lst);
      }
      if (lst.length < entryCap(entry)) {
        lst.push([sentId, sentence]);
        if (!ids.has(sentId)) ids.set(sentId, entry);
      }
    }
    if (processed % 200000 === 0) console.log(`  eng ${processed}`);
  }
  console.log(`eng sentences used: ${processed}`);
  const entriesObj = {};
  for (const [k, v] of entries) entriesObj[k] = v;
  const idsObj = {};
  for (const [k, v] of ids) idsObj[k] = v;
  fs.writeFileSync(path.join(WORK, "entries.json"), JSON.stringify(entriesObj));
  fs.writeFileSync(path.join(WORK, "eng_ids.json"), JSON.stringify(idsObj));
  console.log(`SCAN entries=${entries.size} ids=${ids.size}`);
}

async function cmn() {
  const cmnMap = {};
  const files = fs.readdirSync(WORK).filter((f) => /^cmn_\d+\.json$/.test(f)).sort();
  for (const f of files) {
    Object.assign(cmnMap, JSON.parse(fs.readFileSync(path.join(WORK, f), "utf8")));
    console.log(`merged ${f}`);
  }
  fs.writeFileSync(path.join(WORK, "cmn.json"), JSON.stringify(cmnMap));
  console.log(`CMN ${Object.keys(cmnMap).length}`);
}

async function links() {
  const ids = new Set(Object.keys(JSON.parse(fs.readFileSync(path.join(WORK, "eng_ids.json"), "utf8"))));
  const cmnMap = JSON.parse(fs.readFileSync(path.join(WORK, "cmn.json"), "utf8"));
  const zh = {};
  let processed = 0;
  const rl = readline.createInterface({ input: fs.createReadStream(LINKS, "utf8"), crlfDelay: Infinity });
  for await (const line of rl) {
    processed++;
    const tab = line.indexOf("\t");
    if (tab < 0) continue;
    const a = line.slice(0, tab);
    if (!ids.has(a)) continue;
    const b = line.slice(tab + 1);
    const zhText = cmnMap[b];
    if (zhText !== undefined && zh[a] === undefined) zh[a] = zhText;
    if (processed % 5000000 === 0) console.log(`  links ${processed}`);
  }
  fs.writeFileSync(path.join(WORK, "id_to_zh.json"), JSON.stringify(zh));
  console.log(`LINKS ${Object.keys(zh).length}`);
}

(async () => {
  const cmd = process.argv[2];
  try {
    if (cmd === "scan") await scan();
    else if (cmd === "cmn") await cmn();
    else if (cmd === "links") await links();
    else { console.error(`unknown subcommand: ${cmd}`); process.exit(1); }
  } catch (e) {
    console.error(e);
    process.exit(1);
  }
})();
