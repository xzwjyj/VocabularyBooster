#!/usr/bin/env node
/**
 * SCR-SENSEATTR stage 2 — local LLM tasks against llama.cpp llama-server
 * (Qwen 7B-class 4-bit; offline tooling only, nothing enters the app).
 *
 *   node llm_tasks.js sample [n]   → first n translate + n generate tasks → llm_sample.json (stop-loss review)
 *   node llm_tasks.js translate    → retranslate every corpus example with empty zh (human zh kept untouched)
 *   node llm_tasks.js generate     → one example per sense still uncovered (complete-coverage guarantee)
 *   node llm_tasks.js defs         → v7: translate every definition with blank meaningCN ("d:" keys)
 *   node llm_tasks.js gen2         → v7: one generated example per sense of every zero-example word
 *                                     (bnc/frq rank order — common words first, long tail last;
 *                                     keys parked ≥2 times are skipped: hard holes re-grind for
 *                                     hours on every restart and near-never recover)
 *   node llm_tasks.js gen2sweep    → same queue WITHOUT the parked≥2 skip — batch-end final pass
 *   node llm_tasks.js sample2 [n]  → first n defs + n gen2 tasks → llm_sample2.json (stop-loss review)
 *   node llm_tasks.js status       → progress counts against attributed.json + v7 queues
 *
 * Output: append-only %TEMP%/enrich_work/llm_out.jsonl  {"k":key,"type":"t|g","en":...,"zh":...}
 * Resume: keys already in llm_out.jsonl are skipped (machine-crash discipline).
 * Failures: retried once with a corrective prompt, then parked in llm_failed.jsonl —
 * never silently dropped; build_v6.js treats a missing gap sense as fatal.
 *
 * Server: local ollama service (OpenAI-compatible :11434/v1; qwen3:8b pre-pulled).
 * Qwen3 thinking is suppressed via the /no_think soft switch in prompts plus a
 * <think>-strip safety net in the parser (ollama ignores chat_template_kwargs).
 */
"use strict";
const fs = require("fs");
const path = require("path");

const TEMP = process.env.TEMP || "/tmp";
const WORK = path.join(TEMP, "enrich_work");
const ASSET = path.join(__dirname, "..", "..", "app", "src", "main", "assets", "dict", "ecdict.sqlite");
const BASE_URL = process.env.LLAMA_URL || "http://127.0.0.1:11434";
const MODEL = process.env.LLAMA_MODEL || "qwen3:8b";
const CONCURRENCY = Number(process.env.LLAMA_CONCURRENCY || 8);

const WORD_RE = /[a-z]+/g;

// lazy wink-nlp instance — only the generate path needs lemmatization (went→go, is→be)
let nlpInstance = null;
function getNlp() {
  if (!nlpInstance) {
    const winkNLP = require("wink-nlp");
    nlpInstance = winkNLP(require("wink-eng-lite-web-model"));
  }
  return nlpInstance;
}
const BLOCK_RE = /\b(fu+ck\w*|shit\w*|bitch\w*|ass(es|hole\w*)?|bastard\w*|cunt\w*|dicks?\b|piss\w*|slut\w*|whore\w*|retard\w*|nigg\w*|fag\w*|rap(e[sd]?|ist|ing)|porn\w*|nudes?|penis|vagina|boobs?\b|horny|orgasm\w*|blowjob\w*|dildo\w*|masturbat\w*|cum(med|ming)?)\b/;
// ^ "genital" un-blocked (round 6): clinical anatomy headwords ("genital
// cord/crest/fold…") force the word; model output is medical, not sexual.

function readJson(name) { return JSON.parse(fs.readFileSync(path.join(WORK, name), "utf8")); }

function loadDone() {
  const done = new Set();
  const p = path.join(WORK, "llm_out.jsonl");
  if (fs.existsSync(p)) {
    for (const line of fs.readFileSync(p, "utf8").split("\n")) {
      if (!line.trim()) continue;
      try { done.add(JSON.parse(line).k); } catch { /* torn tail write — ignore */ }
    }
  }
  return done;
}

// park counts per key — normal gen2 runs skip keys already parked ≥2 times
// (each restart otherwise re-grinds the same hard holes: ~2 calls × hours);
// `gen2sweep` ignores the skip for the final batch-end pass.
function parkCounts() {
  const parks = new Map();
  const p = path.join(WORK, "llm_failed.jsonl");
  if (fs.existsSync(p)) {
    for (const line of fs.readFileSync(p, "utf8").split("\n")) {
      if (!line.trim()) continue;
      try { const k = JSON.parse(line).k; parks.set(k, (parks.get(k) || 0) + 1); } catch { /* torn tail */ }
    }
  }
  return parks;
}

class AppendOut {
  constructor(name) { this.fd = fs.openSync(path.join(WORK, name), "a"); }
  write(obj) { fs.writeSync(this.fd, JSON.stringify(obj) + "\n"); }
}

async function chat(messages, maxTokens, temperature) {
  const res = await fetch(BASE_URL + "/api/chat", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      model: MODEL,
      messages,
      stream: false,
      think: false, // deterministic thinking kill (OpenAI-compat endpoint leaks reasoning into an empty content)
      options: { num_predict: maxTokens, temperature, top_p: 0.9 },
    }),
    signal: AbortSignal.timeout(180000),
  });
  if (!res.ok) throw new Error(`HTTP ${res.status}: ${(await res.text()).slice(0, 200)}`);
  const j = await res.json();
  let text = j.message?.content || "";
  text = text.replace(/<think>[\s\S]*?<\/think>/g, "").trim(); // safety net
  return text;
}

// ———— task builders ————

function collectTasks() {
  const attributed = readJson("attributed.json");
  const defsMap = readJson("defs.json");
  const translate = [], generate = [];
  for (const [word, info] of Object.entries(attributed)) {
    const covered = new Set(info.e.map((e) => e[2]));
    for (const [en, zh, di] of info.e) {
      if (!zh) translate.push({ k: "t:" + word + "#" + di + "#" + en.slice(0, 60), en, word });
    }
    const defs = defsMap[word] || [];
    for (let di = 0; di < info.d; di++) {
      if (!covered.has(di)) {
        const def = defs[di] || ["other", "", ""];
        generate.push({ k: "g:" + word + "#" + di, word, di, pos: def[0], en: def[1] || "", cn: def[2] || "" });
      }
    }
  }
  return { translate, generate };
}

function translatePrompt(sentence) {
  return [
    { role: "system", content: "You are a professional English→Chinese translator. Output ONLY the Chinese translation — no explanations, no quotes, no pinyin." },
    { role: "user", content: sentence + "\n/no_think" },
  ];
}

// v7 (SCR-SENSEATTR §7): tasks collected straight from the v6 asset + ECDICT
// stardict.db (bnc/frq rank) — attributed.json/defs.json stay batch-1 scoped.
//   defs ("d:"): every definition sense whose meaningCN is blank (metrics-class)
//   gen2 ("g:"): every sense of every zero-example word (second-batch coverage)
// Key formula for "d:" must stay identical to build_v6.js fillDefinitionCn.
function collectBatch2() {
  const { DatabaseSync } = require("node:sqlite");
  const asset = new DatabaseSync(ASSET, { readOnly: true });
  const star = new DatabaseSync(path.join(__dirname, "stardict.db"), { readOnly: true });
  const bncQ = star.prepare("SELECT bnc, frq FROM stardict WHERE word = ?");
  const defs = [], gen2 = [];
  for (const row of asset.prepare("SELECT normalized, definitions, examples FROM DictEntry").iterate()) {
    let d;
    try { d = JSON.parse(row.definitions); } catch { continue; }
    if (!Array.isArray(d) || d.length === 0) continue;
    const r = bncQ.get(row.normalized) || {};
    const rank = r.bnc ?? r.frq ?? Number.MAX_SAFE_INTEGER; // rank miss → long tail last
    const zeroExample = row.examples === "[]";
    d.forEach((def, di) => {
      const pos = def[0] || "other";
      const en = String(def[1] || "");
      const cn = String(def[2] || "").trim();
      if (!cn) defs.push({ k: "d:" + row.normalized + "#" + di + "#" + en.slice(0, 60), word: row.normalized, pos, en, rank });
      if (zeroExample) gen2.push({ k: "g:" + row.normalized + "#" + di, word: row.normalized, di, pos, en, cn, rank });
    });
  }
  asset.close();
  star.close();
  defs.sort((a, b) => a.rank - b.rank);
  gen2.sort((a, b) => a.rank - b.rank);
  return { defs, gen2 };
}

function defsPrompt(task) {
  return [
    { role: "system", content: "You are a professional English→Chinese dictionary editor. Output ONLY the concise Chinese definition (释义词组) for the given English sense — no explanations, no quotes, no pinyin, no example sentences." },
    { role: "user", content: `Word: "${task.word}" (${task.pos})\nSense: ${task.en}\n/no_think` },
  ];
}

function generatePrompt(task) {
  const sense = task.en + (task.cn ? ` (${task.cn})` : "");
  const ph = /\b(sth|sb)\b/i.test(task.word)
    ? " If the entry contains \"sth\" or \"sb\", replace it with a concrete noun or pronoun in your sentence."
    : "";
  const beHint = /^be\b/i.test(task.word)
    ? " Use a natural form of the verb \"be\" (am/is/are/was/were) — never the bare word \"be\"."
    : "";
  return [
    { role: "system", content: "You write example sentences for an English learner's dictionary. Output EXACTLY one line in the format: English sentence | 中文翻译 — nothing else." },
    { role: "user", content:
      `Word: "${task.word}" (${task.pos})\n` +
      `Target sense: ${sense || "(see word above)"}\n` +
      `Write ONE natural everyday English sentence (10-20 words) that uses "${task.word}" in exactly this sense, not any other sense.${ph}${beHint} Then "|" and the Chinese translation of your sentence.\n/no_think` },
  ];
}

// ———— validation ————

function validZh(zh) {
  return zh && zh.length >= 2 && /[一-鿿]/.test(zh) && zh.length <= 200 && !BLOCK_RE.test(zh);
}

function cleanZh(text) {
  const line = (text || "").split("\n").map((l) => l.trim().replace(/^["“”']+|["“”']+$/g, "")).filter(Boolean)[0] || "";
  return validZh(line) ? line : null;
}

// headword → its own wink lemma (null when lemma === headword). Inflected
// headwords (bothers, went) must accept their whole inflection family, not
// just extensions of the surface form.
const WORD_LEMMA = new Map();
function headwordLemma(word) {
  let l = WORD_LEMMA.get(word);
  if (l === undefined) {
    const w = word.toLowerCase();
    const out = getNlp().readDoc(w).tokens().out(getNlp().its.lemma);
    l = out.length && out[0].toLowerCase() !== w ? out[0].toLowerCase() : null;
    WORD_LEMMA.set(word, l);
  }
  return l;
}

function sentenceLemmas(sentence, lemmaCache) {
  let lemmas = lemmaCache.get(sentence);
  if (lemmas === undefined) {
    lemmas = getNlp().readDoc(sentence).tokens().out(getNlp().its.lemma).map((l) => l.toLowerCase());
    lemmaCache.set(sentence, lemmas);
  }
  return lemmas;
}

// fallback stem for -ing/-ed headwords whose standalone wink lemma equals the
// surface form (readDoc sees "mixing"/"fascinating" as adjectives): mixing→mix,
// stepping→step (undoubled), falling→fall (ll/ss/zz kept). Irregulars (wound)
// deliberately NOT covered — parking those is a correct quality rejection.
function suffixStem(w) {
  for (const suf of ["ing", "ed"]) {
    if (w.endsWith(suf) && w.length - suf.length >= 3) {
      let stem = w.slice(0, -suf.length);
      const last = stem[stem.length - 1];
      if (stem.length >= 4 && last === stem[stem.length - 2] && !"lsz".includes(last)) stem = stem.slice(0, -1);
      return stem;
    }
  }
  return null;
}

// be-form heads: "be double dutch to" entries surface naturally as am/is/was/…
// ("I'm doubtful of", "was completely double dutch to me")
const BE_FORMS = new Set(["am", "is", "are", "was", "were", "been", "being", "be", "s", "re", "m"]);

// dictionary-notation possessives (round 4, measured against parked hard holes
// 2026-10-04): "a lump in one's throat" → "in her throat", "abandon sb to his
// fate" → "to its fate" — the model concrete-izes whose it is. Headword
// possessive tokens ("onez" = normalized "one's") accept any possessive.
// Round 5: "sb's"/"someone's" owners (sbz/somez markers) and the owner-side
// accept set widens to indefinite owners (everyone's head); "oneself"
// reflexives ("onezelf" marker) ↔ herself/themselves/…
const POSSESSIVE = new Set(["my", "your", "his", "her", "its", "our", "their", "hers", "ours", "onez", "somez", "sbz"]);
const OWNER = new Set([...POSSESSIVE, "someone", "everyone", "anyone", "somebody", "everybody", "anybody", "nobody"]);
const REFLEXIVE = new Set(["myself", "yourself", "himself", "herself", "itself", "ourselves", "themselves", "oneself", "onezelf"]);
const isMarker = (w) => w === "onez" || w === "somez" || w === "sbz";

// bounded edit distance for ECDICT typo headwords (dischange→discharge,
// instince→instinct, latin plurals cristae→crista): the model writes correct
// English while the headword itself is misspelled. ≥5-char tokens at OSA
// distance ≤1 (≤2 for ≥8-char) — OSA adds adjacent transposition because
// headword typos are overwhelmingly single-key slips (saliing↔sailing).
function nearToken(t, w) {
  if (t === w) return true;
  const lt = t.length, lw = w.length;
  if (lw < 5 || Math.abs(lt - lw) > 2) return false;
  const max = lw >= 8 ? 2 : 1;
  let prevPrev = null;
  let prev = Array.from({ length: lw + 1 }, (_, j) => j);
  for (let i = 1; i <= lt; i++) {
    const cur = [i];
    for (let j = 1; j <= lw; j++) {
      cur[j] = Math.min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (t[i - 1] === w[j - 1] ? 0 : 1));
      if (i > 1 && j > 1 && t[i - 1] === w[j - 2] && t[i - 2] === w[j - 1]) {
        cur[j] = Math.min(cur[j], prevPrev[j - 2] + 1);
      }
    }
    if (Math.min(...cur) > max) return false;
    prevPrev = prev;
    prev = cur;
  }
  return prev[lw] <= max;
}

// relaxed phrase containment (recovery measured against parked llm_failed
// output, 2026-10-03): consecutive tokens, head-form variants (be-forms,
// verb inflection ≤+3), ≤1 intervening token ("was completely double dutch
// to"), bounded in-order subsequence (separable usage: "consigns all its
// products to"), fuzzy tokens for typo headwords. Placeholders sth/sb are
// stripped by the caller — the model concrete-izes them ("consist of sth" →
// "consists of five members").
// headword-token stem acceptance (round 5): inflected phrase tokens
// ("accelerating time") accept their base form — mirrors the single-word
// suffixStem/lemma rules, cached via WORD_LEMMA.
function stemOk(t, w) {
  const s = headwordLemma(w) || suffixStem(w);
  return s != null && s !== w && t.startsWith(s) && t.length <= s.length + 3;
}

// suffix-whitelist inflection for phrase TAIL tokens (round 6): tails got no
// prefix rule (round 3 kept them strict — "take off" must not swallow "take
// office"), which also blocked legitimate -ing/-ed/-s ("generally speak ng" →
// "Generally speaking"). Only standard suffixes, e-drop stems (take→taking),
// and consonant doubling (stop→stopped, run→running) — "off"+"ice" stays out.
const INFLECT_REST = /^([bdfglmnprt])(ed|ing)$/;
function inflectOk(t, w) {
  let stem = w;
  if (!t.startsWith(stem)) {
    if (stem.length > 3 && stem.endsWith("e") && t.startsWith(stem.slice(0, -1))) stem = stem.slice(0, -1);
    else if (stem.length > 3 && stem.endsWith("y") && t.startsWith(stem.slice(0, -1) + "i")) stem = stem.slice(0, -1) + "i"; // city→cities, gayety→gayeties
    else return false;
  }
  const rest = t.slice(stem.length);
  return rest === "" || rest === "s" || rest === "es" || rest === "ed" || rest === "ing" || INFLECT_REST.test(rest);
}

function phraseHit(tokens, wt, wildcard) {
  const n = wt.length;
  const headOk = (t, w) => t === w ||
    (w.length === 1 && wildcard) || // a/b placeholder template ("a is used as b")
    ((isMarker(w) || POSSESSIVE.has(w)) && OWNER.has(t)) || // one's/sb's/someone's → concrete owner
    (REFLEXIVE.has(w) || w === "onezelf") && REFLEXIVE.has(t) || // oneself → herself/…
    (w !== "be" && t.startsWith(w) && t.length <= w.length + 3) ||
    (w === "be" && BE_FORMS.has(t)) ||
    stemOk(t, w) ||
    nearToken(t, w);
  const tailOk = (t, w) => t === w ||
    (w.length === 1 && wildcard) ||
    ((isMarker(w) || POSSESSIVE.has(w)) && OWNER.has(t)) ||
    (REFLEXIVE.has(w) || w === "onezelf") && REFLEXIVE.has(t) ||
    inflectOk(t, w) ||
    stemOk(t, w) ||
    nearToken(t, w);
  for (let i = 0; i + n <= tokens.length; i++) {
    for (const len of [n, n + 1]) {
      if (i + len > tokens.length) continue;
      // len===n → no skip; len===n+1 → skip exactly one position (all n
      // phrase tokens must be consumed: k===n guard, else a dropped tail
      // silently matches a shorter sub-phrase)
      const skips = len === n ? [-1] : Array.from({ length: len }, (_, x) => x);
      for (const skip of skips) {
        let ok = true, k = 0;
        for (let j = 0; j < len; j++) {
          if (j === skip) continue;
          const t = tokens[i + j], w = wt[k++];
          if (k === 1 ? headOk(t, w) : tailOk(t, w)) continue;
          ok = false; break;
        }
        if (ok && k === n) return true;
      }
    }
  }
  for (let i = 0; i < tokens.length; i++) {
    if (!headOk(tokens[i], wt[0])) continue;
    let j = i + 1, k = 1;
    while (k < n && j < tokens.length && j - i <= n + 5) {
      if (tailOk(tokens[j], wt[k])) k++;
      j++;
    }
    if (k === n) return true;
  }
  return false;
}

function wordPresent(sentence, word, lemmaCache) {
  // dictionary-notation normalization (markers survive WORD_RE): one's/sb's/
  // someone's → owner markers, oneself → reflexive marker
  let wt = word.toLowerCase()
    .replace(/\bone'?s\b/g, " onez ")
    .replace(/\bsb'?s\b/g, " sbz ")
    .replace(/\b(some|any|every)(one|body)'?s\b/g, " somez ")
    .replace(/\boneself\b/g, " onezelf ")
    .match(WORD_RE);
  if (!wt) return true; // no letter tokens — accept
  const n = wt.length;
  if (n === 1) {
    // single word: surface match, regular inflection prefix (word→words, automatic→
    // automatically), the headword's own lemma family (bothers→bother), suffix-stem
    // fallback (mixing→mix→mixed when wink reads the headword as an adjective),
    // or wink lemma (went→go). w must be the LETTER token (wt[0]) — the raw
    // headword may carry punctuation ("ac-") that no sentence token can prefix-match.
    const w = wt[0];
    const wl = headwordLemma(w);
    const st = wl == null ? suffixStem(w) : null;
    const combining = /-\s*$/.test(word); // "gastro-" — combining form marker
    const tokens = sentence.toLowerCase().match(WORD_RE) || [];
    const formOk = (t) => t === w ||
      (t.startsWith(w) && t.length <= w.length + 3) || // inflection bound (+s/+ed/+ing/+est ≤ 3)
      (combining && t.startsWith(w) && t.length <= w.length + 8) || // gastro-→gastrocolic: combining forms attach long suffixes
      (w.length >= 8 && t.startsWith(w) && t.length <= w.length + 5) || // long-word derivation (automatic→automatically); short words get none — be→because collisions
      (wl != null && t.startsWith(wl) && t.length <= wl.length + 3) ||
      (st != null && t.startsWith(st) && t.length <= st.length + 4) || // stem may regain final e/doubling
      nearToken(t, w); // typo headwords (abfurin → model writes afurin), same bounds as phrases
    if (tokens.some(formOk)) return true;
    if (w.length <= 3 && tokens.some((t) => t.startsWith(w))) return true; // abbreviations (th→Thorium)
    const lemmas = sentenceLemmas(sentence, lemmaCache);
    return lemmas.includes(w) || (wl != null && lemmas.includes(wl));
  }
  // phrase: relaxed containment (see phraseHit) over the head and its
  // sth/sb-stripped core … then the same with inflection normalized away
  // (take off / took off). Round 4 notation handling: leading dictionary
  // article is optional ("a family skeleton" ↔ "The family skeleton…");
  // single-letter a/b placeholders go wildcard only with ≥3 spelled-out
  // anchors, so "vitamin a" stays literal while "a is used as b" matches
  // its concrete instantiations.
  if (wt.length >= 2 && (wt[0] === "a" || wt[0] === "an" || wt[0] === "the")) wt = wt.slice(1);
  const anchors = wt.filter((t) => t.length > 1 && t !== "sth" && t !== "sb").length;
  const wildcard = anchors >= 3;
  const tokens = sentence.toLowerCase().match(WORD_RE) || [];
  const core = wt.filter((t) => t !== "sth" && t !== "sb");
  const targets = core.length > 0 && core.length < wt.length ? [core, wt] : [wt];
  // trailing inflection fragment split off by OCR ("generally speak ng"):
  // the model writes the joined form — dropping the fragment lets inflectOk
  // on the previous token do the rest. Extra target only; real phrases never
  // end in a bare "ng"/"ing"/"ed"/"es"/"s" token.
  if (wt.length >= 2 && /^(ng|ing|ed|es|ies|s)$/.test(wt[wt.length - 1])) targets.push(wt.slice(0, -1));
  // hyphen line-wrap artifacts ("ac-count", "absorption spectroelec-
  // trochemistry"): the merged form is the real word the model writes — try
  // the fully de-hyphenated token sequence when it differs (normal compound
  // hyphens merely add a harmless near-no-op target)
  if (word.includes("-")) {
    const mwt = word.toLowerCase().replace(/-/g, "").match(WORD_RE);
    if (mwt && mwt.join(" ") !== wt.join(" ")) targets.push(mwt);
  }
  if (targets.some((target) => phraseHit(tokens, target, wildcard))) return true;
  const lemmas = sentenceLemmas(sentence, lemmaCache);
  for (const target of targets) {
    for (let i = 0; i + target.length <= lemmas.length; i++) {
      if (lemmas.slice(i, i + target.length).join(" ") === target.join(" ")) return true;
    }
    // bounded in-order subsequence over lemmas — separable AND inflected
    // ("He took his coat off"): token space handles one or the other, not both
    for (let i = 0; i < lemmas.length; i++) {
      if (lemmas[i] !== target[0]) continue;
      let j = i + 1, k = 1;
      while (k < target.length && j < lemmas.length && j - i <= target.length + 5) {
        if (lemmas[j] === target[k]) k++;
        j++;
      }
      if (k === target.length) return true;
    }
  }
  return false;
}

const SEPARATORS = ["|", "｜", "—", "——", "：", ":"];

function parseGenerated(text, task, lemmaCache) {
  const line = text.split("\n").map((l) => l.trim()).filter(Boolean)[0] || "";
  for (const sep of SEPARATORS) {
    const at = line.lastIndexOf(sep);
    if (at <= 0) continue;
    const en = line.slice(0, at).trim().replace(/^["']|["']$/g, "");
    const zh = line.slice(at + sep.length).trim().replace(/^["']|["']$/g, "");
    if (en.length < 15 || en.length > 150) continue;
    if (!/[.!?]$/.test(en)) continue;
    if (BLOCK_RE.test(en.toLowerCase())) continue;
    if (!validZh(zh)) continue;
    if (!wordPresent(en, task.word, lemmaCache)) continue;
    return { en, zh };
  }
  return null;
}

// ———— runners ————

const TYPE_BY_LABEL = { translate: "t", generate: "g", defs: "d" };

async function runQueue(tasks, buildPrompt, validate, out, label) {
  let done = 0, ok = 0, parked = 0;
  const failed = new AppendOut("llm_failed.jsonl");
  const lemmaCache = new Map(); // per-queue lemma cache (validate uses it via 3rd arg)
  let idx = 0;
  async function worker() {
    while (idx < tasks.length) {
      const task = tasks[idx++];
      try {
        let text = await chat(buildPrompt(task), 200, label === "generate" ? 0.7 : 0.3);
        let parsed = validate(text, task, lemmaCache);
        if (!parsed) {
          const fix = label === "generate"
            ? `Your previous answer was rejected. Reply with ONE line "English sentence | 中文翻译" using "${task.word}" (exact form or a standard inflection such as plural/past/-ing${/\b(sth|sb)\b/i.test(task.word) ? "; replace sth/sb with a concrete noun or pronoun" : ""}${/^be\b/i.test(task.word) ? "; use a natural be-form (am/is/are/was/were), never a bare \"be\"" : ""}) in the target sense. No other text.`
            : label === "defs"
              ? "Your previous answer violated the required format. Output ONLY the concise Chinese definition (释义词组) — no explanations, no quotes, no pinyin, no example sentences."
              : "Your previous answer violated the required format. Output ONLY the Chinese translation — no explanations, no quotes, no pinyin.";
          const retry = await chat(buildPrompt(task).concat([{ role: "user", content: fix }]), 200, 0.2);
          parsed = validate(retry, task, lemmaCache);
        }
        if (parsed) { out.write({ k: task.k, type: TYPE_BY_LABEL[label], ...parsed }); ok++; }
        else { failed.write({ k: task.k, type: TYPE_BY_LABEL[label], raw: text.slice(0, 300) }); parked++; }
      } catch (e) {
        failed.write({ k: task.k, type: TYPE_BY_LABEL[label], error: String(e).slice(0, 200) });
        parked++;
        if (String(e).includes("ECONNREFUSED") || String(e).includes("fetch failed")) {
          console.error("llama-server unreachable — start it first (see header comment). Aborting.");
          process.exit(2);
        }
      }
      done++;
      if (done % 200 === 0) console.log(`  ${label} ${done}/${tasks.length} ok=${ok} parked=${parked}`);
    }
  }
  await Promise.all(Array.from({ length: Math.min(CONCURRENCY, tasks.length) }, worker));
  console.log(`${label.toUpperCase()} done=${done} ok=${ok} parked=${parked}`);
}

async function main() {
  const cmd = process.argv[2];
  const { translate, generate } = collectTasks();
  console.log(`tasks: translate=${translate.length} generate=${generate.length}`);
  if (cmd === "status") {
    const done = loadDone();
    console.log(`llm_out.jsonl keys=${done.size}`);
    const { defs, gen2 } = collectBatch2();
    console.log(`v7 pending: defs=${defs.filter((x) => !done.has(x.k)).length}/${defs.length} gen2=${gen2.filter((x) => !done.has(x.k)).length}/${gen2.length}`);
    return;
  }
  const done = loadDone();
  const out = new AppendOut("llm_out.jsonl");
  if (cmd === "sample") {
    const n = Number(process.argv[3] || 50);
    const t = translate.filter((x) => !done.has(x.k)).slice(0, n);
    const g = generate.filter((x) => !done.has(x.k)).slice(0, n);
    fs.writeFileSync(path.join(WORK, "llm_sample.json"), JSON.stringify({ translate: t, generate: g }, null, 1));
    await runQueue(t, (x) => translatePrompt(x.en), (text) => { const zh = cleanZh(text); return zh ? { zh } : null; }, out, "translate");
    await runQueue(g, generatePrompt, parseGenerated, out, "generate");
    return;
  }
  if (cmd === "translate") {
    await runQueue(translate.filter((x) => !done.has(x.k)), (x) => translatePrompt(x.en), (text) => { const zh = cleanZh(text); return zh ? { zh } : null; }, out, "translate");
    return;
  }
  if (cmd === "generate") {
    await runQueue(generate.filter((x) => !done.has(x.k)), generatePrompt, parseGenerated, out, "generate");
    return;
  }
  if (cmd === "gen2" || cmd === "gen2sweep") {
    const { gen2 } = collectBatch2();
    console.log(`v7 tasks: gen2=${gen2.length}`);
    let tasks = gen2.filter((x) => !done.has(x.k));
    if (cmd === "gen2") {
      const parks = parkCounts();
      const before = tasks.length;
      tasks = tasks.filter((x) => (parks.get(x.k) || 0) < 2);
      console.log(`parked>=2 skipped: ${before - tasks.length} (final pass: node llm_tasks.js gen2sweep)`);
    }
    await runQueue(tasks, generatePrompt, parseGenerated, out, "generate");
    return;
  }
  if (cmd === "defs" || cmd === "sample2") {
    const { defs, gen2 } = collectBatch2();
    console.log(`v7 tasks: defs=${defs.length} gen2=${gen2.length}`);
    if (cmd === "sample2") {
      const n = Number(process.argv[3] || 50);
      const d = defs.filter((x) => !done.has(x.k)).slice(0, n);
      const g = gen2.filter((x) => !done.has(x.k)).slice(0, n);
      fs.writeFileSync(path.join(WORK, "llm_sample2.json"), JSON.stringify({ defs: d, gen2: g }, null, 1));
      await runQueue(d, defsPrompt, (text) => { const zh = cleanZh(text); return zh ? { zh } : null; }, out, "defs");
      await runQueue(g, generatePrompt, parseGenerated, out, "generate");
      return;
    }
    // defs only (gen2/gen2sweep returned earlier; sample2 returned above)
    await runQueue(
      defs.filter((x) => !done.has(x.k)),
      defsPrompt,
      (text) => { const zh = cleanZh(text); return zh ? { zh } : null; },
      out,
      "defs",
    );
    return;
  }
  console.error(`unknown subcommand: ${cmd}`);
  process.exit(1);
}

if (require.main === module) {
  main().catch((e) => { console.error(e); process.exit(1); });
}
// SCR-EXREVIEW: validators exported for review_tasks.js — logic unchanged.
module.exports = { wordPresent, BLOCK_RE, validZh };
