#!/usr/bin/env node
/**
 * SCR-EXREVIEW — review batch for term-level mistranslations in AI-generated
 * examples ("g" flag) against the bundled dictionary asset (offline tooling
 * only, nothing enters the app).
 *
 * Input queue (union, see SPEC_CHANGE_REQUEST_EXAMPLE_REVIEW.md):
 *   - tier-1 flags: %TEMP%/enrich_work/term_mismatch_tier1.jsonl — examples
 *     whose EN contains the headword while the CN shares zero CJK chars with
 *     the definition CN (the "nautiloid shell -> 珍珠层" class);
 *   - template hallucination sentences: deterministic regex over the asset's
 *     "g" examples ("The form X appears in historical dictionaries…" etc.).
 *
 * Each task is judged by a local LLM (qwen3:14b default):
 *   OK                      translation is faithful (kept-English acronyms/
 *                           species names/technical terms are acceptable)
 *   FIX <zh>                sentence fine, translation wrong — retranslate
 *   REGEN <en> | <zh>       sentence itself broken/hallucinated/template —
 *                           regenerate sentence + translation
 *
 *   node review_tasks.js sample [n]  → first n tasks to review_sample.json + run them
 *   node review_tasks.js run         → full queue (skips keys parked >= 2, gen2 discipline)
 *   node review_tasks.js sweep       → full queue WITHOUT the parked skip (batch-end pass)
 *   node review_tasks.js repair      → post-sweep FIX-only fallback for parked term tasks
 *                                      (keeps the original EN; parked template tasks stay)
 *   node review_tasks.js status      → progress counts
 *
 * Output: append-only %TEMP%/enrich_work/review_out.jsonl
 *   {"k":key,"v":"ok"} / {"k":key,"v":"fix","zh":...} / {"k":key,"v":"regen","en":...,"zh":...}
 * Resume: keys already in review_out.jsonl are skipped (machine-crash discipline).
 * Failures: retried once with a corrective prompt, then parked in
 *   review_failed.jsonl — never silently dropped; apply treats missing as keep.
 */
"use strict";
const fs = require("fs");
const path = require("path");
const { wordPresent, BLOCK_RE, validZh } = require("./llm_tasks.js");

const TEMP = process.env.TEMP || "/tmp";
const WORK = path.join(TEMP, "enrich_work");
const ASSET = path.join(__dirname, "..", "..", "app", "src", "main", "assets", "dict", "ecdict.sqlite");
const BASE_URL = process.env.LLAMA_URL || "http://127.0.0.1:11434";
const MODEL = process.env.LLAMA_MODEL || "qwen3:14b";
const CONCURRENCY = Number(process.env.LLAMA_CONCURRENCY || 8);

const TIER1 = path.join(WORK, "term_mismatch_tier1.jsonl");
const CJK_RE = /[㐀-鿿]/g;
const TPL_RE = /(appears in historical dictionaries|recorded as a variant|specialised abbreviation|Lexicographers list|In reference works|the form "|The expression ")/i;
// queue/r: key uses the FULL example text — template sentences share 60-char
// prefixes and slice() keys collapsed distinct examples (94 lost, measured)

// user-reported hand fix (SCR-EXREVIEW): approved translation, bypasses the LLM
// so the shipped wording is exactly the signed-off one. Queue-conditional —
// nautiloid itself escaped tier-1 (defCn「…动物」shares 物 with the bad「海洋生物」
// translation), so its verdict was hand-appended to review_out.jsonl directly.
const HAND_FIXES = new Map([
  ["nautiloid", "鹦鹉螺壳是古代海洋生物以化石形式保存下来的一个迷人例子。"],
]);

function readTier1() {
  const out = new Map(); // key: word#di#full-en — one task per flagged example
  if (fs.existsSync(TIER1)) {
    for (const line of fs.readFileSync(TIER1, "utf8").split("\n")) {
      if (!line.trim()) continue;
      try {
        const o = JSON.parse(line);
        out.set(o.word + "#" + o.di + "#" + o.en, { word: o.word, di: o.di, en: o.en, cn: o.cn, why: "term" });
      } catch { /* torn tail — ignore */ }
    }
  }
  return out;
}

function collectTasks() {
  const { DatabaseSync } = require("node:sqlite");
  const asset = new DatabaseSync(ASSET, { readOnly: true });
  const queue = readTier1();
  // template sentences straight from the asset (tier-1 overlap is absorbed by
  // the full-text key — same example, same task; measured union = 14,351)
  for (const row of asset.prepare("SELECT normalized, examples FROM DictEntry").iterate()) {
    let exs;
    try { exs = JSON.parse(row.examples); } catch { continue; }
    for (const e of exs) {
      if (len4(e) && e[3] === "g" && TPL_RE.test(e[0])) {
        queue.set(row.normalized + "#" + e[2] + "#" + e[0], { word: row.normalized, di: e[2], en: e[0], cn: e[1], why: "template" });
      }
    }
  }
  // attach sense info (pos + def EN/CN) from the asset
  const tasks = [];
  const defsQ = asset.prepare("SELECT definitions FROM DictEntry WHERE normalized = ?");
  for (const [qk, t] of queue.entries()) {
    const d = defsQ.get(t.word);
    let defs = null;
    try { defs = d && JSON.parse(d.definitions); } catch { /* keep null */ }
    const def = (defs && (defs[t.di] || defs[defs.length - 1])) || ["other", "", ""];
    tasks.push({
      k: "r:" + qk,
      word: t.word, di: t.di, en: t.en, cn: t.cn, why: t.why,
      pos: def[0] || "other", defEn: String(def[1] || ""), defCn: String(def[2] || ""),
    });
  }
  asset.close();
  tasks.sort((a, b) => (a.why === b.why ? a.word < b.word ? -1 : 1 : a.why === "term" ? -1 : 1));
  return tasks;
}

const len4 = (e) => Array.isArray(e) && e.length > 3 && typeof e[3] === "string";

class AppendOut {
  constructor(name) { this.fd = fs.openSync(path.join(WORK, name), "a"); }
  write(obj) { fs.writeSync(this.fd, JSON.stringify(obj) + "\n"); }
}

function loadDone() {
  const done = new Set();
  const p = path.join(WORK, "review_out.jsonl");
  if (fs.existsSync(p)) {
    for (const line of fs.readFileSync(p, "utf8").split("\n")) {
      if (!line.trim()) continue;
      try { done.add(JSON.parse(line).k); } catch { /* torn tail write — ignore */ }
    }
  }
  return done;
}

function parkCounts() {
  const parks = new Map();
  const p = path.join(WORK, "review_failed.jsonl");
  if (fs.existsSync(p)) {
    for (const line of fs.readFileSync(p, "utf8").split("\n")) {
      if (!line.trim()) continue;
      try { const k = JSON.parse(line).k; parks.set(k, (parks.get(k) || 0) + 1); } catch { /* torn tail */ }
    }
  }
  return parks;
}

async function chat(messages, maxTokens, temperature) {
  const res = await fetch(BASE_URL + "/api/chat", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      model: MODEL,
      messages,
      stream: false,
      think: false,
      options: { num_predict: maxTokens, temperature, top_p: 0.9 },
    }),
    signal: AbortSignal.timeout(180000),
  });
  if (!res.ok) throw new Error(`HTTP ${res.status}: ${(await res.text()).slice(0, 200)}`);
  const j = await res.json();
  return (j.message?.content || "").replace(/<think>[\s\S]*?<\/think>/g, "").trim();
}

function reviewPrompt(task) {
  const why = task.why === "template"
    ? "\nNote: the sentence looks like boilerplate dictionary prose rather than a real example."
    : "";
  return [
    { role: "system", content:
      "You are a bilingual (English-Chinese) dictionary QA editor. Given a headword, its target sense, and one AI-generated example sentence with its Chinese translation, judge whether the translation faithfully renders the sentence — especially the headword term. Reply with EXACTLY one line and nothing else:\n" +
      "OK — the translation is faithful (keeping the headword untranslated in the Chinese is acceptable for acronyms, species names, or technical terms)\n" +
      "FIX 新译文 — the English sentence is fine but the translation is wrong or drops the headword term; FIX followed by your corrected Chinese translation only\n" +
      "REGEN English sentence | 中文翻译 — the English sentence itself is broken, hallucinated, or template-like; REGEN followed by one new natural everyday English sentence (10-20 words) that uses the headword in exactly the target sense, then \"|\" and its Chinese translation" },
    { role: "user", content:
      `Word: "${task.word}" (${task.pos})\n` +
      `Target sense: ${task.defEn}${task.defCn ? ` (${task.defCn})` : ""}\n` +
      `Current sentence: ${task.en}\n` +
      `Current translation: ${task.cn}${why}\n/no_think` },
  ];
}

// gate: corrected zh must carry the headword term — a shared CJK char with the
// definition CN, or the headword itself kept in English (acronym/species/tech).
// Inflection-note defs ("bedizen的变形", "accelerate的变形") carry no usable CN
// anchor (1,083/13,120 tier-1 words) — treat like blank and trust validZh.
const META_CHARS = "的变形词语体尾缀";
function termOk(zh, task) {
  if (zh.toLowerCase().includes(task.word.toLowerCase())) return true;
  const defCjk = task.defCn.match(CJK_RE) || [];
  const content = defCjk.filter((c) => !META_CHARS.includes(c));
  if (content.length < 2) return true; // no CN anchor — validZh is all we have
  const defChars = new Set(defCjk);
  return (zh.match(CJK_RE) || []).some((c) => defChars.has(c));
}

const SEPARATORS = ["|", "｜", "—", "——"];

// the model sometimes echoes the format label ("新译文 — ", "English sentence:")
// into its payload — strip those prefixes before validation (pilot finding)
const LABEL_ZH = /^(新译文|译文|翻译)\s*[:：—\-]*\s*/;
const LABEL_EN = /^(english sentence|new sentence|new english sentence|sentence)\s*[:：—\-]*\s*/i;

function parseVerdict(text, task, lemmaCache) {
  const line = text.split("\n").map((l) => l.trim()).filter(Boolean)[0] || "";
  if (/^ok\b/i.test(line)) return { v: "ok" };
  let m = line.match(/^fix\b[:：]?\s*(.+)$/i);
  if (m) {
    const zh = m[1].trim().replace(LABEL_ZH, "").replace(/^["“”']+|["“”']+$/g, "");
    return validZh(zh) && termOk(zh, task) ? { v: "fix", zh } : null;
  }
  m = line.match(/^regen\b[:：]?\s*(.+)$/i);
  if (m) {
    for (const sep of SEPARATORS) {
      const at = m[1].lastIndexOf(sep);
      if (at <= 0) continue;
      const en = m[1].slice(0, at).trim().replace(LABEL_EN, "").replace(/^["']|["']$/g, "");
      const zh = m[1].slice(at + sep.length).trim().replace(/^["“”']+|["“”']+$/g, "");
      if (en.length < 15 || en.length > 150) continue;
      if (!/[.!?]$/.test(en)) continue;
      if (BLOCK_RE.test(en.toLowerCase())) continue;
      if (!validZh(zh) || !termOk(zh, task)) continue;
      if (!wordPresent(en, task.word, lemmaCache)) continue;
      return { v: "regen", en, zh };
    }
  }
  return null;
}

const RETRY_MSG =
  'Your previous answer was rejected by the format/term gate. Reply with EXACTLY one line: either "OK", or "FIX 新译文" (Chinese only, must render the headword term — or keep the headword itself for acronyms/species/technical terms), or "REGEN New english sentence | 中文翻译" (sentence 10-20 words, natural, uses the headword in the target sense). No other text.';

// repair pass (post-sweep): parked TERM tasks get a FIX-only fallback — the
// original EN sentence is kept (it already lives in the asset) and only the
// translation is redone. This bypasses the REGEN gates that parked most of
// them (BLOCK_RE substring collisions like "pissarroes", typo headwords like
// "schrdinger", possessive-marker lemma gaps like "take one's place").
// Parked TEMPLATE tasks are NOT repaired — retranslating boilerplate prose
// would keep the useless sentence; they stay parked and keep their original.
function repairPrompt(task) {
  return [
    { role: "system", content:
      "You are a professional English-Chinese translator. Translate the given sentence into natural Chinese. The translation must render the headword term correctly — or keep the headword itself untranslated for acronyms, species names, or technical terms. Output ONLY the Chinese translation — no explanations, no quotes, no pinyin." },
    { role: "user", content:
      `Word: "${task.word}" (${task.pos})\n` +
      `Target sense: ${task.defEn}${task.defCn ? ` (${task.defCn})` : ""}\n` +
      `Sentence: ${task.en}\n/no_think` },
  ];
}

// tighten pass (user-approved option B, 2026-10-11): re-examine fixes whose
// first definition-term char only appears in the LATE half of the zh — the
// headword itself is likely transliterated ("haycocks" -> 海科克斯) or dropped
// ("adam fig" -> 香蕉). Re-translate with a semantic-term-forcing prompt and a
// hard position gate: the term must surface early (or the headword stays in
// English). Known detector escapes are seeded explicitly.
const TIGHTEN_SEEDS = new Set(["haycocks"]);
function loadVerdicts() {
  const out = new Map(); // k -> last verdict line (Map.set semantics = last wins)
  const p = path.join(WORK, "review_out.jsonl");
  if (fs.existsSync(p)) {
    for (const line of fs.readFileSync(p, "utf8").split("\n")) {
      if (!line.trim()) continue;
      try { const o = JSON.parse(line); if (o.k) out.set(o.k, o); } catch { /* torn tail */ }
    }
  }
  return out;
}
function lateAnchor(task, zh) {
  // mirrors the audit probe: first shared def char (content chars only)
  // appearing past 45% of the sentence = term likely missing at the head
  if (!zh || zh.toLowerCase().includes(task.word.toLowerCase())) return false;
  const meta = META_CHARS + "复数";
  const defCjk = (task.defCn.match(CJK_RE) || []).filter((c) => !meta.includes(c));
  const defChars = new Set(defCjk);
  if (defChars.size < 2) return false;
  for (let i = 0; i < zh.length; i++) {
    if (defChars.has(zh[i])) return i > zh.length * 0.45;
  }
  return true; // gate should have caught a zero-share; treat as suspect
}
function tightenPrompt(task) {
  return [
    { role: "system", content:
      "You are a professional English-Chinese translator. Translate the given sentence into natural Chinese. The headword MUST be translated with its Chinese meaning from the target sense — transliterating it phonetically or dropping it entirely is FORBIDDEN. Only keep the headword in English if it is an acronym or a Latin/scientific name. Output ONLY the Chinese translation — no explanations, no quotes, no pinyin." },
    { role: "user", content:
      `Word: "${task.word}" (${task.pos})\n` +
      `Target sense: ${task.defEn}${task.defCn ? ` (${task.defCn})` : ""}\n` +
      `Sentence: ${task.en}\n/no_think` },
  ];
}

// detaint pass: fixes sitting on person-name-polluted defCn while the defEN is
// a real gloss ("haycocks" — EN "cone-shaped pile of hay" / CN "[人名] 海科克斯").
// Re-translate trusting ONLY the English sense; the corrected zh must not carry
// the surname transliteration characters from the polluted CN.
const EN_PERSON_RE = /\b(surname|name|first name|given name)\b/i;
function nameTokens(defCn) {
  // CJK runs (>=2 chars) inside the polluted defCn = transliteration tokens
  // ("[人名] 巴尔特" -> ["人名","巴尔特"] -> drop the literal label)
  return (defCn.match(/[㐀-鿿]{2,}/g) || []).filter((t) => t !== "人名");
}
function detaintPrompt(task) {
  return [
    { role: "system", content:
      "You are a professional English-Chinese translator. The Chinese definition shown for this word is WRONG (a mis-tagged surname) — ignore it and translate strictly according to the English sense. Translate the sentence into natural Chinese, rendering the headword with its true meaning. Output ONLY the Chinese translation — no explanations, no quotes, no pinyin." },
    { role: "user", content:
      `Word: "${task.word}" (${task.pos})\n` +
      `Correct sense (EN): ${task.defEn}\n` +
      `Polluted sense (ignore): ${task.defCn}\n` +
      `Sentence: ${task.en}\n/no_think` },
  ];
};

async function runQueue(tasks, out) {
  let done = 0, ok = 0, parked = 0;
  const failed = new AppendOut("review_failed.jsonl");
  const lemmaCache = new Map();
  let idx = 0;
  async function worker() {
    while (idx < tasks.length) {
      const task = tasks[idx++];
      const hand = HAND_FIXES.get(task.word);
      if (hand) { out.write({ k: task.k, v: "fix", zh: hand }); ok++; done++; continue; }
      try {
        const text = await chat(reviewPrompt(task), 220, 0.2);
        let parsed = parseVerdict(text, task, lemmaCache);
        if (!parsed) {
          const retry = await chat(reviewPrompt(task).concat([{ role: "user", content: RETRY_MSG }]), 220, 0.2);
          parsed = parseVerdict(retry, task, lemmaCache);
        }
        if (parsed) { out.write({ k: task.k, ...parsed }); ok++; }
        else { failed.write({ k: task.k, raw: text.slice(0, 300) }); parked++; }
      } catch (e) {
        failed.write({ k: task.k, error: String(e).slice(0, 200) });
        parked++;
        if (String(e).includes("ECONNREFUSED") || String(e).includes("fetch failed")) {
          console.error("ollama unreachable — start it first (see header comment). Aborting.");
          process.exit(2);
        }
      }
      done++;
      if (done % 200 === 0) console.log(`  review ${done}/${tasks.length} ok=${ok} parked=${parked}`);
    }
  }
  await Promise.all(Array.from({ length: Math.min(CONCURRENCY, tasks.length) }, worker));
  console.log(`REVIEW done=${done} ok=${ok} parked=${parked}`);
}

async function main() {
  const cmd = process.argv[2];
  const tasks = collectTasks();
  const terms = tasks.filter((t) => t.why === "term").length;
  console.log(`tasks: ${tasks.length} (term=${terms} template=${tasks.length - terms})`);
  const done = loadDone();
  if (cmd === "status") {
    console.log(`review_out.jsonl keys=${done.size} pending=${tasks.filter((t) => !done.has(t.k)).length}`);
    return;
  }
  const out = new AppendOut("review_out.jsonl");
  if (cmd === "sample") {
    const n = Number(process.argv[3] || 50);
    const t = tasks.filter((x) => !done.has(x.k)).slice(0, n);
    fs.writeFileSync(path.join(WORK, "review_sample.json"), JSON.stringify(t, null, 1));
    await runQueue(t, out);
    return;
  }
  if (cmd === "repair") {
    const tasks = collectTasks();
    const done = loadDone();
    const parks = parkCounts();
    const byKey = new Map(tasks.map((t) => [t.k, t]));
    const queue = [...new Set([...parks.keys()])]
      .filter((k) => !done.has(k) && byKey.has(k) && byKey.get(k).why === "term")
      .map((k) => byKey.get(k));
    console.log(`repair queue (parked term tasks, FIX-only): ${queue.length}`);
    let doneR = 0, okR = 0;
    let idx = 0;
    async function worker() {
      while (idx < queue.length) {
        const task = queue[idx++];
        try {
          const text = await chat(repairPrompt(task), 220, 0.2);
          let zh = (text || "").split("\n").map((l) => l.trim().replace(LABEL_ZH, "").replace(/^["“”']+|["“”']+$/g, "")).filter(Boolean)[0] || "";
          if (!(validZh(zh) && termOk(zh, task))) {
            const retry = await chat(repairPrompt(task).concat([{ role: "user", content: "Your previous answer failed the term gate. The translation must render the headword term (or keep the headword itself for acronyms/species/technical terms). Output ONLY the Chinese translation." }]), 220, 0.2);
            zh = (retry || "").split("\n").map((l) => l.trim().replace(LABEL_ZH, "").replace(/^["“”']+|["“”']+$/g, "")).filter(Boolean)[0] || "";
          }
          if (validZh(zh) && termOk(zh, task)) { out.write({ k: task.k, v: "fix", zh }); okR++; }
        } catch (e) {
          if (String(e).includes("ECONNREFUSED") || String(e).includes("fetch failed")) {
            console.error("ollama unreachable — Aborting."); process.exit(2);
          }
        }
        doneR++;
        if (doneR % 200 === 0) console.log(`  repair ${doneR}/${queue.length} ok=${okR}`);
      }
    }
    await Promise.all(Array.from({ length: Math.min(CONCURRENCY, queue.length) }, worker));
    console.log(`REPAIR done=${doneR} ok=${okR}`);
    return;
  }
  if (cmd === "tighten") {
    const tasks = collectTasks();
    const verdicts = loadVerdicts();
    const byKey = new Map(tasks.map((t) => [t.k, t]));
    const queue = [];
    for (const [k, o] of verdicts) {
      if (o.v !== "fix") continue; // regen verdicts carry a new validated EN — a fix override here would discard it
      const t = byKey.get(k);
      if (!t) continue;
      if (TIGHTEN_SEEDS.has(t.word) || lateAnchor(t, o.zh)) queue.push(t);
    }
    console.log(`tighten queue (late-anchor suspects + seeds): ${queue.length}`);
    let doneT = 0, okT = 0;
    let idx = 0;
    async function worker() {
      while (idx < queue.length) {
        const task = queue[idx++];
        try {
          const text = await chat(tightenPrompt(task), 220, 0.2);
          const strip = (s) => (s || "").split("\n").map((l) => l.trim().replace(LABEL_ZH, "").replace(/^["“”']+|["“”']+$/g, "")).filter(Boolean)[0] || "";
          let zh = strip(text);
          if (!(validZh(zh) && termOk(zh, task) && !lateAnchor(task, zh))) {
            const retry = await chat(tightenPrompt(task).concat([{ role: "user", content: "The headword term must appear as its Chinese meaning EARLY in the sentence (or stay in English for acronyms/Latin names). Output ONLY the corrected Chinese translation." }]), 220, 0.2);
            zh = strip(retry);
          }
          if (validZh(zh) && termOk(zh, task) && !lateAnchor(task, zh)) { out.write({ k: task.k, v: "fix", zh }); okT++; }
        } catch (e) {
          if (String(e).includes("ECONNREFUSED") || String(e).includes("fetch failed")) {
            console.error("ollama unreachable — Aborting."); process.exit(2);
          }
        }
        doneT++;
        if (doneT % 100 === 0) console.log(`  tighten ${doneT}/${queue.length} ok=${okT}`);
      }
    }
    await Promise.all(Array.from({ length: Math.min(CONCURRENCY, queue.length) }, worker));
    console.log(`TIGHTEN done=${doneT} ok=${okT}`);
    return;
  }
  if (cmd === "detaint") {
    const tasks = collectTasks();
    const verdicts = loadVerdicts();
    const byKey = new Map(tasks.map((t) => [t.k, t]));
    const queue = [];
    for (const [k, o] of verdicts) {
      if (o.v !== "fix" && o.v !== "regen") continue;
      const t = byKey.get(k);
      if (!t || !t.defCn.includes("人名")) continue;
      if (EN_PERSON_RE.test(t.defEn) || !t.defEn.trim()) continue; // genuine surname entry — consistent, leave
      queue.push(t);
    }
    console.log(`detaint queue (person-name-polluted defCn, real defEN): ${queue.length}`);
    let doneD = 0, okD = 0;
    let idx = 0;
    async function worker() {
      while (idx < queue.length) {
        const task = queue[idx++];
        const banned = nameTokens(task.defCn);
        const clean = (s) => (s || "").split("\n").map((l) => l.trim().replace(LABEL_ZH, "").replace(/^["“”']+|["“”']+$/g, "")).filter(Boolean)[0] || "";
        const okZh = (zh) => validZh(zh) && !banned.some((b) => zh.includes(b));
        try {
          const text = await chat(detaintPrompt(task), 220, 0.2);
          let zh = clean(text);
          if (!okZh(zh)) {
            const retry = await chat(detaintPrompt(task).concat([{ role: "user", content: "Your answer still contains the mis-tagged surname characters. Translate the headword with its TRUE meaning from the English sense. Output ONLY the Chinese translation." }]), 220, 0.2);
            zh = clean(retry);
          }
          if (okZh(zh)) { out.write({ k: task.k, v: "fix", zh }); okD++; }
        } catch (e) {
          if (String(e).includes("ECONNREFUSED") || String(e).includes("fetch failed")) {
            console.error("ollama unreachable — Aborting."); process.exit(2);
          }
        }
        doneD++;
      }
    }
    await Promise.all(Array.from({ length: Math.min(CONCURRENCY, queue.length) }, worker));
    console.log(`DETAINT done=${doneD} ok=${okD}`);
    return;
  }
  if (cmd === "run" || cmd === "sweep") {
    let queue = tasks.filter((x) => !done.has(x.k));
    if (cmd === "run") {
      const parks = parkCounts();
      const before = queue.length;
      queue = queue.filter((x) => (parks.get(x.k) || 0) < 2);
      console.log(`parked>=2 skipped: ${before - queue.length} (final pass: node review_tasks.js sweep)`);
    }
    await runQueue(queue, out);
    return;
  }
  console.error(`unknown subcommand: ${cmd}`);
  process.exit(1);
}

if (require.main === module) {
  main().catch((e) => { console.error(e); process.exit(1); });
}
module.exports = { parseVerdict, termOk };
