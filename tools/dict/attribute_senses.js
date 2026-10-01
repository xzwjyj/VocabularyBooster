#!/usr/bin/env node
/**
 * SCR-SENSEATTR stage 1 — per-definition example attribution (deterministic).
 *
 *   node attribute_senses.js prep       → %TEMP%/enrich_work/ec_words.json + defs.json
 *   node enrich_node.js scan            (unchanged, reuses ec_words.json)
 *   node attribute_senses.js cmn        → %TEMP%/enrich_work/cmn.json (single pass, feeds links)
 *   node enrich_node.js links           (unchanged → id_to_zh.json)
 *   node attribute_senses.js attribute  → %TEMP%/enrich_work/attributed.json
 *
 * Three-tier matching (words with >1 definitions; single-def words take the
 * fast path = tier 1 + current selection rules):
 *   1. longest-phrase redirect — sentence containing a longer dictionary phrase
 *      that includes the word (lip gloss under gloss) is dropped from the word;
 *   2. POS limiting via wink-nlp — candidate senses restricted to same POS
 *      ('other' senses always stay in; untaggable → no constraint);
 *   3. within-POS EN token overlap + CN char-bigram overlap → best sense;
 *      zero signal → first candidate sense (mirrors legacy first-def behavior).
 *
 * Selection per sense: human-zh first, then shortest; cap 4 for single-def
 * words, 2 per sense otherwise; round-robin across senses in definition order.
 * Senses left empty are the generation gap (filled by llm_tasks.js, src 'g').
 */
"use strict";
const fs = require("fs");
const path = require("path");

const TEMP = process.env.TEMP || "/tmp";
const WORK = path.join(TEMP, "enrich_work");
const ASSET = path.join(__dirname, "..", "..", "app", "src", "main", "assets", "dict", "ecdict.sqlite");
const SENTENCES = path.join(TEMP, "sentences.csv");

const WORD_RE = /[a-z]+/g;
const SINGLE_CAP = 4;   // single-def words: keep legacy cap
const PER_SENSE_CAP = 2; // multi-def words: per-sense cap

// UD tags (wink-nlp) → asset definition pos vocabulary
const UD_TO_POS = {
  NOUN: "noun", PROPN: "noun",
  VERB: "verb", AUX: "verb",
  ADJ: "adjective",
  ADV: "adverb",
  ADP: "preposition",
  PRON: "pronoun",
  INTJ: "interjection",
  NUM: "numeral",
  CCONJ: "conjunction", SCONJ: "conjunction",
  DET: "determiner",
};

const STOP = new Set(("the a an and or but if of to in on at for with by from as is are was were be been being " +
  "it its this that these those he she they them his her their you your i my we our not no do does did have has had " +
  "will would can could should may might must about into over under out up down off than then so such very").split(" "));

function readJson(name) {
  return JSON.parse(fs.readFileSync(path.join(WORK, name), "utf8"));
}
function writeJson(name, obj) {
  fs.writeFileSync(path.join(WORK, name), JSON.stringify(obj));
}

// ———— prep: dictionary word list + definitions of pool-bearing words ————

function prep() {
  const { DatabaseSync } = require("node:sqlite");
  const db = new DatabaseSync(ASSET, { readOnly: true });
  const words = [];
  const defs = {};
  for (const row of db.prepare("SELECT normalized, definitions, examples FROM DictEntry").iterate()) {
    words.push(row.normalized);
    if (row.examples !== "[]") defs[row.normalized] = JSON.parse(row.definitions);
  }
  db.close();
  writeJson("ec_words.json", words);
  writeJson("defs.json", defs);
  console.log(`PREP words=${words.length} withDefs=${Object.keys(defs).length}`);
}

// ———— cmn: single pass over sentences.csv → id → zh text (feeds enrich_node.js links) ————

async function cmn() {
  const readline = require("readline");
  const map = {};
  let processed = 0;
  const rl = readline.createInterface({ input: fs.createReadStream(SENTENCES, "utf8"), crlfDelay: Infinity });
  for await (const line of rl) {
    const tab1 = line.indexOf("\t");
    if (tab1 < 0) continue;
    const tab2 = line.indexOf("\t", tab1 + 1);
    if (tab2 < 0 || line.slice(tab1 + 1, tab2) !== "cmn") continue;
    map[line.slice(0, tab1)] = line.slice(tab2 + 1);
    processed++;
    if (processed % 100000 === 0) console.log(`  cmn ${processed}`);
  }
  writeJson("cmn.json", map);
  console.log(`CMN ${processed}`);
}

// ———— attribution core ————

function enTokens(s) {
  const t = s.toLowerCase().match(WORD_RE);
  return t ? t.filter((w) => w.length > 2 && !STOP.has(w)) : [];
}

function cnBigrams(s) {
  const c = s.replace(/[^一-鿿]/g, "");
  const set = new Set();
  for (let i = 0; i + 1 < c.length; i++) set.add(c.slice(i, i + 2));
  return set;
}

function overlap(a, b) { // |a ∩ b|, a iterable, b Set
  let n = 0;
  for (const x of a) if (b.has(x)) n++;
  return n;
}

function scoreSense(def, sentTokenSet, humanZh) {
  let s = 0;
  if (def[1]) {
    const dt = enTokens(def[1]);
    if (dt.length) s += overlap(dt, sentTokenSet) / Math.min(8, dt.length);
  }
  if (def[2] && humanZh) {
    const db = cnBigrams(def[2]);
    if (db.size) s += 0.5 * overlap(db, cnBigrams(humanZh)) / Math.min(30, db.size);
  }
  return s;
}

async function attribute() {
  const winkNLP = require("wink-nlp");
  const model = require("wink-eng-lite-web-model");
  const nlp = winkNLP(model);

  const entries = readJson("entries.json");   // word → [[sentId, sentence], ...]
  const defsMap = readJson("defs.json");      // exampled words → [[pos,en,cn],...]
  const idToZh = readJson("id_to_zh.json");   // human translations

  // phrase index for tier-1 redirect: token → phrases containing it
  const phraseByToken = new Map();
  for (const w of readJson("ec_words.json")) {
    if (!w.includes(" ") && !w.includes("-") && !w.includes("'")) continue;
    for (const t of w.toLowerCase().match(WORD_RE) || []) {
      let s = phraseByToken.get(t);
      if (!s) { s = new Set(); phraseByToken.set(t, s); }
      s.add(w);
    }
  }

  // does sentence contain phrase p (space/hyphen/apostrophe variants, word boundary)?
  function containsPhrase(tokens, p) {
    const pt = p.toLowerCase().match(WORD_RE);
    if (!pt || pt.length < 2) return false; // 1-token "phrases" can't be longer than the word itself
    const n = pt.length;
    const variants = [pt.join(" "), pt.join("-"), pt.join("'")];
    for (let i = 0; i + n <= tokens.length; i++) {
      const joined = tokens.slice(i, i + n).join(" ");
      if (joined === variants[0] || joined === variants[1] || joined === variants[2]) return true;
    }
    return false;
  }

  const out = {};
  let wordsMulti = 0, wordsSingle = 0, droppedPhrase = 0, posLimited = 0, scored = 0, gapSenses = 0;
  const gapDefsSample = [];

  for (const word of Object.keys(entries)) {
    const defs = defsMap[word];
    if (!defs) continue; // word lost examples since v5 build (none expected; guard anyway)
    const pool = entries[word];
    const isSingleWord = !word.includes(" ") && !word.includes("-") && !word.includes("'");

    // tier 1 — longest-phrase redirect (single words only)
    let cands = pool;
    if (isSingleWord) {
      const redirects = phraseByToken.get(word) || emptySet;
      if (redirects.size) {
        cands = [];
        for (const [sid, sentence] of pool) {
          const tokens = sentence.toLowerCase().match(WORD_RE) || [];
          let hit = false;
          for (const p of redirects) {
            // redirect phrases must contain the word as a token (index built that way)
            if (containsPhrase(tokens, p)) { hit = true; break; }
          }
          if (hit) droppedPhrase++; else cands.push([sid, sentence]);
        }
      }
    }
    // attribution to defIdx
    const bySense = new Map(); // defIdx → [[sid, sentence, zh], ...]
    if (defs.length === 1) {
      wordsSingle++;
      bySense.set(0, cands.map(([sid, s]) => [sid, s, idToZh[sid] || ""]));
    } else {
      wordsMulti++;
      for (const [sid, sentence] of cands) {
        let defIdx = 0;
        if (isSingleWord) {
          // tier 2 — POS limiting
          const doc = nlp.readDoc(sentence);
          const toks = doc.tokens().out();
          const tags = doc.tokens().out(nlp.its.pos);
          const lemmas = doc.tokens().out(nlp.its.lemma);
          const posSet = new Set(defs.map((d) => d[0]));
          let mapped = null;
          for (let i = 0; i < toks.length; i++) {
            const form = toks[i].toLowerCase();
            if (form !== word && (lemmas[i] || "").toLowerCase() !== word) continue;
            const m = UD_TO_POS[tags[i]] || null;
            if (m && posSet.has(m)) { mapped = m; break; } // first occurrence matching a known def pos
          }
          let candIdx;
          if (mapped) {
            candIdx = [];
            defs.forEach((d, i) => { if (d[0] === mapped || d[0] === "other") candIdx.push(i); });
            posLimited++;
          } else {
            candIdx = defs.map((_, i) => i);
          }
          // tier 3 — within-candidates overlap scoring
          const zh = idToZh[sid] || "";
          const sentTokenSet = new Set(enTokens(sentence));
          let best = candIdx[0], bestScore = -1;
          for (const i of candIdx) {
            const sc = scoreSense(defs[i], sentTokenSet, zh);
            if (sc > bestScore) { bestScore = sc; best = i; }
          }
          if (bestScore <= 0) best = candIdx[0]; // zero signal → first candidate (legacy mirror)
          else scored++;
          defIdx = best;
        } else {
          // phrase entries: no POS tier, score across all defs
          const zh = idToZh[sid] || "";
          const sentTokenSet = new Set(enTokens(sentence));
          let best = 0, bestScore = -1;
          defs.forEach((d, i) => {
            const sc = scoreSense(d, sentTokenSet, zh);
            if (sc > bestScore) { bestScore = sc; best = i; }
          });
          if (bestScore <= 0) best = 0; else scored++;
          defIdx = best;
        }
        let lst = bySense.get(defIdx);
        if (!lst) { lst = []; bySense.set(defIdx, lst); }
        lst.push([sid, sentence, idToZh[sid] || ""]);
      }
    }

    // selection: human-zh first, then shortest; cap + round-robin
    for (const lst of bySense.values()) {
      lst.sort((a, b) => (b[2] ? 1 : 0) - (a[2] ? 1 : 0) || a[1].length - b[1].length);
    }
    const picked = []; // [defIdx, sid, sentence, zh]
    const maxPerSense = defs.length === 1 ? SINGLE_CAP : PER_SENSE_CAP;
    for (let round = 0; round < maxPerSense; round++) {
      for (let di = 0; di < defs.length; di++) {
        const lst = bySense.get(di);
        if (!lst) continue;
        const slot = lst[round];
        if (slot && picked.filter((p) => p[0] === di).length < maxPerSense) picked.push([di, slot[0], slot[1], slot[2]]);
      }
    }

    // gaps
    const covered = new Set(picked.map((p) => p[0]));
    const gaps = [];
    for (let di = 0; di < defs.length; di++) if (!covered.has(di)) gaps.push(di);
    gapSenses += gaps.length;
    if (gaps.length && gapDefsSample.length < 30) gapDefsSample.push({ word, gaps, defs });

    out[word] = {
      d: defs.length,
      e: picked.map(([di, , sentence, zh]) => [sentence, zh, di]),
    };
  }

  writeJson("attributed.json", out);
  const totalEx = Object.values(out).reduce((n, w) => n + w.e.length, 0);
  console.log(`ATTRIBUTE words=${Object.keys(out).length} (single=${wordsSingle} multi=${wordsMulti})`);
  console.log(`  examples=${totalEx} droppedByPhraseRedirect=${droppedPhrase} posLimited=${posLimited} scored=${scored}`);
  console.log(`  gapSenses(needs generation)=${gapSenses}`);
  console.log(`  gap sample: ${JSON.stringify(gapDefsSample.slice(0, 8))}`);
}

const emptySet = new Set();

(async () => {
  const cmd = process.argv[2];
  try {
    if (cmd === "prep") prep();
    else if (cmd === "cmn") await cmn();
    else if (cmd === "attribute") await attribute();
    else { console.error(`unknown subcommand: ${cmd}`); process.exit(1); }
  } catch (e) {
    console.error(e);
    process.exit(1);
  }
})();
