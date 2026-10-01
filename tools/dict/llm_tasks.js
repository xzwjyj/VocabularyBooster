#!/usr/bin/env node
/**
 * SCR-SENSEATTR stage 2 — local LLM tasks against llama.cpp llama-server
 * (Qwen 7B-class 4-bit; offline tooling only, nothing enters the app).
 *
 *   node llm_tasks.js sample [n]   → first n translate + n generate tasks → llm_sample.json (stop-loss review)
 *   node llm_tasks.js translate    → retranslate every corpus example with empty zh (human zh kept untouched)
 *   node llm_tasks.js generate     → one example per sense still uncovered (complete-coverage guarantee)
 *   node llm_tasks.js status       → progress counts against attributed.json
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
const BLOCK_RE = /\b(fu+ck\w*|shit\w*|bitch\w*|ass(es|hole\w*)?|bastard\w*|cunt\w*|dicks?\b|piss\w*|slut\w*|whore\w*|retard\w*|nigg\w*|fag\w*|rap(e[sd]?|ist|ing)|porn\w*|nudes?|penis|vagina|boobs?\b|horny|orgasm\w*|blowjob\w*|dildo\w*|masturbat\w*|genital\w*|cum(med|ming)?)\b/;

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

function generatePrompt(task) {
  const sense = task.en + (task.cn ? ` (${task.cn})` : "");
  return [
    { role: "system", content: "You write example sentences for an English learner's dictionary. Output EXACTLY one line in the format: English sentence | 中文翻译 — nothing else." },
    { role: "user", content:
      `Word: "${task.word}" (${task.pos})\n` +
      `Target sense: ${sense || "(see word above)"}\n` +
      `Write ONE natural everyday English sentence (10-20 words) that uses "${task.word}" in exactly this sense, not any other sense. Then "|" and the Chinese translation of your sentence.\n/no_think` },
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

function wordPresent(sentence, word, lemmaCache) {
  const wt = word.toLowerCase().match(WORD_RE);
  if (!wt) return true; // no letter tokens — accept
  const n = wt.length;
  if (n === 1) {
    // single word: surface match, regular inflection prefix (word→words, automatic→
    // automatically), the headword's own lemma family (bothers→bother), suffix-stem
    // fallback (mixing→mix→mixed when wink reads the headword as an adjective),
    // or wink lemma (went→go)
    const w = word.toLowerCase();
    const wl = headwordLemma(w);
    const st = wl == null ? suffixStem(w) : null;
    const tokens = sentence.toLowerCase().match(WORD_RE) || [];
    const formOk = (t) => t === w ||
      (t.startsWith(w) && t.length <= w.length + 3) || // inflection bound (+s/+ed/+ing/+est ≤ 3)
      (w.length >= 8 && t.startsWith(w) && t.length <= w.length + 5) || // long-word derivation (automatic→automatically); short words get none — be→because collisions
      (wl != null && t.startsWith(wl) && t.length <= wl.length + 3) ||
      (st != null && t.startsWith(st) && t.length <= st.length + 4); // stem may regain final e/doubling
    if (tokens.some(formOk)) return true;
    if (w.length <= 3 && tokens.some((t) => t.startsWith(w))) return true; // abbreviations (th→Thorium)
    const lemmas = sentenceLemmas(sentence, lemmaCache);
    return lemmas.includes(w) || (wl != null && lemmas.includes(wl));
  }
  // phrase: consecutive token match with space/hyphen/apostrophe joins …
  const tokens = sentence.toLowerCase().match(WORD_RE) || [];
  for (let i = 0; i + n <= tokens.length; i++) {
    const joined = tokens.slice(i, i + n).join(" ");
    if (joined === wt.join(" ") || joined === wt.join("-") || joined === wt.join("'")) return true;
  }
  // … or the same join with inflection normalized away (take off / took off)
  const lemmas = sentenceLemmas(sentence, lemmaCache);
  for (let i = 0; i + n <= lemmas.length; i++) {
    if (lemmas.slice(i, i + n).join(" ") === wt.join(" ")) return true;
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
            ? `Your previous answer was rejected. Reply with ONE line "English sentence | 中文翻译" using "${task.word}" (exact form or a standard inflection such as plural/past/-ing) in the target sense. No other text.`
            : "Your previous answer violated the required format. Output ONLY the Chinese translation — no explanations, no quotes, no pinyin.";
          const retry = await chat(buildPrompt(task).concat([{ role: "user", content: fix }]), 200, 0.2);
          parsed = validate(retry, task, lemmaCache);
        }
        if (parsed) { out.write({ k: task.k, type: label === "generate" ? "g" : "t", ...parsed }); ok++; }
        else { failed.write({ k: task.k, type: label === "generate" ? "g" : "t", raw: text.slice(0, 300) }); parked++; }
      } catch (e) {
        failed.write({ k: task.k, type: label === "generate" ? "g" : "t", error: String(e).slice(0, 200) });
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
  console.error(`unknown subcommand: ${cmd}`);
  process.exit(1);
}

main().catch((e) => { console.error(e); process.exit(1); });
