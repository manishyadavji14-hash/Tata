// PDF → Practice Set pipeline.
//  1. PDF.js text pre-pass for every page: column-aware reading order, watermark removal
//     (rotated text, lines repeated across pages, user keywords), local page classification
//     (advertisement / answer key / questions / image-only).
//  2. AI pass (optional): page image and/or cleaned text → structured JSON per page; figures are
//     cropped from the rendered canvas; progress is saved to IndexedDB so an import can resume.
//  3. Answer-key mapping, solution mapping, AI enrichment (solutions + topic tagging) in batches.
//  4. Offline fallback: heuristic regex parser when no API key is configured.
import { db } from './db.js';
import { chat } from './ai.js';
import { PAGE_SYSTEM_PROMPT, pageUserPrompt, ENRICH_SYSTEM_PROMPT } from './prompts.js';
import { normalizeSet, normalizeOptionId, DEFAULT_SECTIONS } from './schema.js';
import { extractJson, slug } from './util.js';

let pdfjsPromise = null;
async function pdfjs() {
  if (!pdfjsPromise) {
    pdfjsPromise = import('../vendor/pdfjs/pdf.min.mjs').then((lib) => {
      lib.GlobalWorkerOptions.workerSrc = new URL('../vendor/pdfjs/pdf.worker.min.mjs', import.meta.url).href;
      return lib;
    });
  }
  return pdfjsPromise;
}

export async function openPdf(buffer) {
  const lib = await pdfjs();
  const base = new URL('../vendor/pdfjs/', import.meta.url).href;
  return lib.getDocument({
    data: new Uint8Array(buffer),
    cMapUrl: `${base}cmaps/`,
    cMapPacked: true,
    standardFontDataUrl: `${base}standard_fonts/`,
    isEvalSupported: false,
    enableXfa: false,
  }).promise;
}

// ---------------------------------------------------------------------------
// Text layer → ordered lines
// ---------------------------------------------------------------------------
const AD_WORDS = ['batch', 'admission', 'fee', 'fees', 'offer', 'discount', 'enroll', 'enrol', 'join', 'whatsapp', 'telegram', 'youtube', 'download', 'app', 'call', 'contact', 'helpline', 'course', 'coaching', 'classes', 'demo class', 'live class', 'seats', 'scholarship', 'register',
  'बैच', 'फीस', 'शुल्क', 'प्रवेश', 'एडमिशन', 'छूट', 'ऑफर', 'संपर्क', 'कॉल', 'जुड़ें', 'डाउनलोड', 'कोर्स', 'कक्षाएं', 'क्लास', 'सीटें', 'रजिस्टर', 'ऑनलाइन', 'ऑफलाइन', '₹', 'rs.', '/-'];

function itemsToLines(items) {
  // items: { x, y, w, h, str }
  const sorted = items.slice().sort((a, b) => a.y - b.y || a.x - b.x);
  const lines = [];
  for (const it of sorted) {
    const line = lines.length ? lines[lines.length - 1] : null;
    if (line && Math.abs(line.y - it.y) <= Math.max(2, Math.min(line.h, it.h) * 0.55)) {
      line.items.push(it);
      line.h = Math.max(line.h, it.h);
    } else lines.push({ y: it.y, h: it.h, items: [it] });
  }
  return lines.map((l) => {
    l.items.sort((a, b) => a.x - b.x);
    let text = '';
    let lastEnd = null;
    for (const it of l.items) {
      const gap = lastEnd === null ? 0 : it.x - lastEnd;
      if (gap > it.h * 2.5) text = `${text.trimEnd()}\u2003`; // wide gap: separate cells of an option grid
      else if (gap > it.h * 0.22 && !/\s$/.test(text)) text += ' ';
      text += it.str;
      lastEnd = it.x + it.w;
    }
    return { y: l.y, x: l.items[0].x, text: text.replace(/[ \t\r\n\f\v\u00a0]+/g, ' ').trim() };
  }).filter((l) => l.text);
}

// Find a vertical gutter between two columns. Returns gutter x (in page units) or null.
function findGutter(items, width) {
  const bins = 80;
  const cov = new Array(bins).fill(0);
  let counted = 0;
  for (const it of items) {
    if (it.w > width * 0.6) continue; // full-width lines don't tell us about columns
    const a = Math.max(0, Math.floor((it.x / width) * bins));
    const b = Math.min(bins - 1, Math.floor(((it.x + it.w) / width) * bins));
    for (let i = a; i <= b; i++) cov[i] += 1;
    counted++;
  }
  if (counted < 12) return null;
  const lo = Math.floor(bins * 0.3), hi = Math.ceil(bins * 0.7);
  let best = -1, bestVal = Infinity;
  for (let i = lo; i <= hi; i++) if (cov[i] < bestVal) { bestVal = cov[i]; best = i; }
  const max = Math.max(...cov);
  const gx = ((best + 0.5) / bins) * width;
  const left = items.filter((it) => it.x + it.w <= gx).length;
  const right = items.filter((it) => it.x >= gx).length;
  if (bestVal <= max * 0.06 && left >= 5 && right >= 5) return gx;
  return null;
}

async function extractPage(page, userMarks) {
  const vp = page.getViewport({ scale: 1 });
  const tc = await page.getTextContent({ includeMarkedContent: false });
  const items = [];
  let rotated = 0;
  for (const it of tc.items) {
    if (!it.str || !it.str.trim()) continue;
    const [a, b, c, d, e, f] = it.transform;
    if (Math.abs(b) > 0.05 * Math.abs(a || 1) || Math.abs(c) > 0.05 * Math.abs(d || 1)) { rotated++; continue; } // diagonal watermark
    const h = Math.hypot(c, d) || it.height || 10;
    items.push({ x: e, y: vp.height - f - h, w: it.width || it.str.length * h * 0.5, h, str: it.str });
  }
  // Very large-font text (> 2.6× median) is almost always a watermark or poster headline.
  const hs = items.map((i) => i.h).sort((p, q) => p - q);
  const med = hs.length ? hs[Math.floor(hs.length / 2)] : 10;
  const kept = items.filter((i) => i.h <= med * 2.6);
  const gutter = findGutter(kept, vp.width);
  let lines;
  if (gutter) {
    // Full-width items split the page into bands; inside a band read left column then right column.
    const full = kept.filter((i) => i.x < gutter - 2 && i.x + i.w > gutter + 2);
    const fullLines = itemsToLines(full);
    const cuts = [-Infinity, ...fullLines.map((l) => l.y), Infinity];
    lines = [];
    for (let k = 0; k < cuts.length - 1; k++) {
      const inBand = (i) => i.y > cuts[k] + 0.5 && i.y < cuts[k + 1] - 0.5 && !(i.x < gutter - 2 && i.x + i.w > gutter + 2);
      const band = kept.filter(inBand);
      lines.push(...itemsToLines(band.filter((i) => i.x + i.w / 2 < gutter)));
      lines.push(...itemsToLines(band.filter((i) => i.x + i.w / 2 >= gutter)));
      if (k < fullLines.length) lines.push(fullLines[k]);
    }
  } else lines = itemsToLines(kept);

  let texts = lines.map((l) => l.text);
  if (userMarks.length) {
    texts = texts.map((t) => {
      let s = t;
      for (const w of userMarks) s = s.split(w).join(' ');
      return s.replace(/[ \t]+/g, ' ').trim();
    }).filter(Boolean);
  }
  return { lines: texts, gutterFrac: gutter ? gutter / vp.width : null, rotated, width: vp.width, height: vp.height, chars: texts.join('').length };
}

const normLine = (t) => t.toLowerCase().replace(/[\d\s.,:|\-–—_/\\()[\]]+/g, '');

function removeRepeatedLines(pages) {
  if (pages.length < 3) return new Set();
  const counts = new Map();
  for (const p of pages) {
    const seen = new Set();
    for (const t of p.lines) {
      const k = normLine(t);
      if (k.length < 4 || seen.has(k)) continue;
      seen.add(k);
      counts.set(k, (counts.get(k) || 0) + 1);
    }
  }
  const limit = Math.max(3, Math.ceil(pages.length * 0.4));
  const rep = new Set([...counts].filter(([, c]) => c >= limit).map(([k]) => k));
  for (const p of pages) p.lines = p.lines.filter((t) => !rep.has(normLine(t)));
  return rep;
}

// ---------------------------------------------------------------------------
// Answer key + page classification
// ---------------------------------------------------------------------------
const KEY_TOKEN = '([A-Da-d1-4]|[अबसद])';

export function parseAnswerKey(lines) {
  const text = lines.join('\n');
  const found = new Map();
  let covered = 0;
  // Layout 1: "1. D  2. (B)  3-A" or table cells "1 | D"
  const re = new RegExp(`(?:^|[\\s|,;])(?:Q\\.?\\s*)?(\\d{1,3})\\s*[.):\\-–=]?\\s*\\(?${KEY_TOKEN}\\)?(?=[\\s|,;.]|$)`, 'gm');
  for (const m of text.matchAll(re)) {
    const q = Number(m[1]);
    const a = normalizeOptionId(m[2]);
    if (q >= 1 && q <= 300 && a && !found.has(q)) { found.set(q, a); covered += m[0].replace(/\s/g, '').length; }
  }
  // A real key page is mostly "number + letter" pairs. Question pages such as "(A) 3 (B) 4 (C) 5"
  // also produce pairs, but they cover only a small share of the page text.
  const letters = text.replace(/\s/g, '').length || 1;
  const headerless = text.replace(/answer|key|ans\.?|q\.?\s*no\.?|उत्तर|कुंजी|माला|तालिका|प्रश्न|सं\.?/gi, '').replace(/[\s|]/g, '').length || 1;
  if (covered / Math.min(letters, headerless) < 0.6) found.clear();
  // Layout 2: a row of increasing question numbers followed by a row of answers.
  const rowPairs = new Map();
  for (let i = 0; i < lines.length - 1; i++) {
    const nums = lines[i].match(/\b\d{1,3}\b/g);
    if (!nums || nums.length < 4) continue;
    const n = nums.map(Number);
    const inc = n.every((v, k) => k === 0 || v === n[k - 1] + 1);
    if (!inc) continue;
    const ans = (lines[i + 1].match(new RegExp(`\\(?${KEY_TOKEN}\\)?(?=\\s|$)`, 'g')) || []).map((x) => normalizeOptionId(x.replace(/[()]/g, '')));
    if (ans.length === n.length && ans.every(Boolean)) n.forEach((q, k) => rowPairs.set(q, ans[k]));
  }
  const best = rowPairs.size > found.size ? rowPairs : found;
  // Sanity: question numbers must be mostly unique and increasing-ish; require ≥ 8 pairs.
  return best.size >= 8 ? best : new Map();
}

function classifyLocal(p) {
  const text = p.lines.join('\n');
  const lower = text.toLowerCase();
  const qSignals = (text.match(/(^|\n)\s*(?:Q\.?\s*|प्रश्न\s*)?\d{1,3}\s*[.)।]\s*\S/g) || []).length;
  const optSignals = (text.match(/\(\s*(?:[A-Da-d]|[1-4]|[अबसद])\s*\)/g) || []).length;
  const adScore = AD_WORDS.reduce((n, w) => n + (lower.includes(w) ? 1 : 0), 0) + ((text.match(/\b[6-9]\d{9}\b/g) || []).length ? 2 : 0);
  const keyHint = /answer\s*key|उत्तर\s*(?:कुंजी|माला|तालिका)|ans\.?\s*key|q\.?\s*no\.?\s*ans/i.test(text);
  const key = parseAnswerKey(p.lines);
  const looksLikeQuestions = qSignals >= 3 && optSignals >= 8;
  if (!looksLikeQuestions && (key.size >= 15 || (keyHint && key.size >= 8))) return { type: 'answer_key', key, qSignals, optSignals, adScore };
  if (p.chars < 40) return { type: 'image_only', key, qSignals, optSignals, adScore };
  if (qSignals < 2 && optSignals < 4 && adScore >= 3) return { type: 'advertisement', key, qSignals, optSignals, adScore };
  return { type: qSignals >= 1 || optSignals >= 4 ? 'questions' : 'other', key, qSignals, optSignals, adScore };
}

// ---------------------------------------------------------------------------
// Rendering + cropping
// ---------------------------------------------------------------------------
async function renderPage(page, scale) {
  const vp = page.getViewport({ scale });
  const canvas = document.createElement('canvas');
  canvas.width = Math.floor(vp.width);
  canvas.height = Math.floor(vp.height);
  const ctx = canvas.getContext('2d', { willReadFrequently: false });
  ctx.fillStyle = '#fff';
  ctx.fillRect(0, 0, canvas.width, canvas.height);
  await page.render({ canvasContext: ctx, viewport: vp }).promise;
  return canvas;
}

function cropCanvas(canvas, [x0, y0, x1, y1], { maxW = 900, type = 'image/jpeg', q = 0.85 } = {}) {
  const clamp = (v) => Math.min(1, Math.max(0, Number(v) || 0));
  let a = clamp(x0), b = clamp(y0), c = clamp(x1), d = clamp(y1);
  if (c < a) [a, c] = [c, a];
  if (d < b) [b, d] = [d, b];
  const pad = 0.006;
  a = Math.max(0, a - pad); b = Math.max(0, b - pad); c = Math.min(1, c + pad); d = Math.min(1, d + pad);
  const sw = (c - a) * canvas.width, sh = (d - b) * canvas.height;
  if (sw < 8 || sh < 8) return null;
  const k = Math.min(1, maxW / sw);
  const out = document.createElement('canvas');
  out.width = Math.round(sw * k);
  out.height = Math.round(sh * k);
  const ctx = out.getContext('2d');
  ctx.fillStyle = '#fff';
  ctx.fillRect(0, 0, out.width, out.height);
  ctx.drawImage(canvas, a * canvas.width, b * canvas.height, sw, sh, 0, 0, out.width, out.height);
  const url = out.toDataURL(type, q);
  out.width = 0; out.height = 0;
  return url;
}

function canvasToJpeg(canvas, maxSide = 2000) {
  const k = Math.min(1, maxSide / Math.max(canvas.width, canvas.height));
  if (k === 1) return canvas.toDataURL('image/jpeg', 0.82);
  const c = document.createElement('canvas');
  c.width = Math.round(canvas.width * k);
  c.height = Math.round(canvas.height * k);
  c.getContext('2d').drawImage(canvas, 0, 0, c.width, c.height);
  const url = c.toDataURL('image/jpeg', 0.82);
  c.width = 0; c.height = 0;
  return url;
}

// ---------------------------------------------------------------------------
// Merging helpers
// ---------------------------------------------------------------------------
const longer = (a, b) => ((b || '').length > (a || '').length ? b : a);

function mergeQuestion(store, q, fallbackSection) {
  const qn = Number(q.q_no);
  if (!Number.isFinite(qn) || qn <= 0) return;
  const prev = store[qn];
  const sec = q.section || fallbackSection || '';
  if (!prev) { store[qn] = { ...q, q_no: qn, section: sec }; return; }
  for (const f of ['question_hi', 'question_en', 'solution_hi', 'solution_en', 'key_concept', 'topic', 'syllabus_tag']) prev[f] = longer(prev[f], q[f]);
  prev.section = prev.section || sec;
  prev.passage_id = prev.passage_id || q.passage_id || null;
  prev.correct_option = prev.correct_option || q.correct_option || null;
  prev.difficulty = prev.difficulty || q.difficulty;
  if (q.question_media) {
    prev.question_media = prev.question_media || {};
    prev.question_media.table_html = prev.question_media.table_html || q.question_media.table_html || null;
    prev.question_media.image_base64 = prev.question_media.image_base64 || q.question_media.image_base64 || null;
  }
  if (Array.isArray(q.options) && q.options.length) {
    if (!Array.isArray(prev.options) || prev.options.length < q.options.length) prev.options = q.options;
    else prev.options = prev.options.map((o, i) => ({ ...o, text_hi: longer(o.text_hi, q.options[i]?.text_hi), text_en: longer(o.text_en, q.options[i]?.text_en) }));
  }
}

function attachFigure(store, fig, dataUrl) {
  const q = store[Number(fig.q_no)];
  if (!q || !dataUrl) return;
  if (fig.target === 'option' && fig.option_id && Array.isArray(q.options)) {
    const o = q.options.find((x) => String(x.id).toUpperCase() === String(fig.option_id).toUpperCase());
    if (o && !o.image_base64) { o.image_base64 = dataUrl; return; }
  }
  q.question_media = q.question_media || {};
  if (!q.question_media.image_base64) q.question_media.image_base64 = dataUrl;
}

// ---------------------------------------------------------------------------
// Offline heuristic parser (no AI)
// ---------------------------------------------------------------------------
// Section headings, in Unicode and in the legacy Kruti Dev encoding many coaching PDFs use for headings
// (e.g. "i;kZoj.k ,oa lkkekftd v/;;u" = पर्यावरण एवं सामाजिक अध्ययन).
const SECTION_ALIASES = [
  ['HINDI', /^(?:भाग\s*[-–:]?\s*\S+\s*)?(?:hindi|हिन्दी|हिंदी|fgUnh)(?:\s*(?:भाषा|language))?$/i],
  ['ENGLISH', /^(?:english|अंग्रेजी|अंग्रेज़ी|vaxzsth)(?:\s*language)?$/i],
  ['SANSKRIT', /^(?:sanskrit|संस्कृत|laLd`r)$/i],
  ['SCIENCE', /^(?:science|विज्ञान|foKku)$/i],
  ['MATHS', /^(?:maths?|mathematics|गणित|xf.kr)$/i],
  ['पर्यावरण एवं सामाजिक अध्ययन', /^(?:evs|पर्यावरण.*|सामाजिक\s*अध्ययन|social\s*studies|i;kZoj\.k.*)$/i],
  ['बाल विकास (CDP)', /^(?:cdp|बाल\s*विकास.*|child\s*development.*|शिक्षाशास्त्र.*|ckky\s*fodkl.*|\S+\s*\S*\s*\(cdp\))$/i],
  ['सामान्य ज्ञान एवं समसायिक घटनाएं', /^(?:gk|general\s*(?:knowledge|awareness|studies).*|सामान्य\s*ज्ञान.*|समसामयिक.*|lkekU;\s*Kku.*)$/i],
  ['REASONING', /^(?:reasoning.*|तर्कशक्ति.*|तर्क\s*शक्ति.*|तार्किक\s*क्षमता|रीजनिंग)$/i],
  ['सूचना तकनीक', /^(?:सूचना\s*(?:तकनीक|प्रौद्योगिकी).*|information\s*technology|computer|कम्प्यूटर|कंप्यूटर|lwpuk\s*rduhd.*)$/i],
];

function detectSection(line) {
  const t = line.replace(/[*_:–\-|]+/g, ' ').replace(/\s+/g, ' ').trim();
  if (t.length > 45 || /^\d/.test(t)) return null;
  for (const [name, re] of SECTION_ALIASES) if (re.test(t)) return name;
  return null;
}

// Option markers: (A) (a) (1) (अ)  — or —  A. a. A) a)  (the bare forms need a space before them).
const OPT_MARK = /\(\s*([A-Ea-e]|[1-5]|[अबसद])\s*\)|(?<=^|\s)([A-Ea-e])[.)](?=\s)/g;
const DEV = /[\u0900-\u097F]/;
const GAP = '\u2003'; // inserted by itemsToLines for wide horizontal gaps (two-column option grids)

function splitBilingual(text) {
  const lines = text.split('\n').map((l) => l.replaceAll(GAP, ' ').replace(/\s+/g, ' ').trim()).filter(Boolean);
  const hi = lines.filter((l) => DEV.test(l));
  const en = lines.filter((l) => !DEV.test(l) && /[a-z]{3,}/i.test(l));
  if (hi.length && en.length && en.join(' ').split(/\s+/).length >= 4) return { hi: hi.join(' '), en: lines.filter((l) => !hi.includes(l)).join(' ') };
  const all = lines.join(' ');
  return DEV.test(all) ? { hi: all, en: '' } : { hi: '', en: all };
}

// "भूमध्य रेखा पर / At the equator" → { hi, en }
function splitOption(t) {
  const s = t.replaceAll(GAP, ' ').replace(/\s+/g, ' ').trim();
  const m = s.match(/^(.*\S)\s+\/\s+(.*)$/);
  if (m && DEV.test(m[1]) !== DEV.test(m[2])) return DEV.test(m[1]) ? { hi: m[1], en: m[2] } : { hi: m[2], en: m[1] };
  return DEV.test(s) ? { hi: s, en: '' } : { hi: '', en: s };
}

const opt = (id, t) => { const l = splitOption(t); return { id, text_hi: l.hi, text_en: l.en, image_base64: null }; };

// Turns one question's raw text into { stem, options[] }.
export function splitQuestion(raw) {
  const MD = '[\\u{1D7CE}-\\u{1D7FF}]'; // "mathematical bold" digits used by Word equations
  const body = raw
    .replace(/[АВСЕ]/g, (c) => ({ А: 'A', В: 'B', С: 'C', Е: 'E' }[c])) // Cyrillic look-alikes
    .replace(new RegExp(`(\\d+)\\s+(${MD}+)\\s+(${MD}+)`, 'gu'), '$1 $2/$3') // mixed fraction "49 𝟏 𝟏𝟏" → "49 1/11"
    .replace(new RegExp(`(${MD}+)\\s*\\n\\s*(${MD}+)`, 'gu'), '$1/$2')
    .replace(new RegExp(MD, 'gu'), (d) => String((d.codePointAt(0) - 0x1D7CE) % 10));
  const marks = [...body.matchAll(OPT_MARK)].map((m) => ({ id: normalizeOptionId(m[1] || m[2], 5), start: m.index, end: m.index + m[0].length, paren: !!m[1] }));

  // Error-spotting style: "text (a) / text (b) / text (c) / No error" — the option is the text BEFORE each marker.
  if (/\(\s*a\s*\)\s*\//i.test(body)) {
    const segs = body.split(/\(\s*[a-d]\s*\)\s*\/?/i).map((x) => x.replace(/^\s*\/\s*/, '').trim()).filter(Boolean);
    if (segs.length >= 3) {
      return { stem: 'Find the part of the sentence that has an error / वाक्य के जिस भाग में त्रुटि है उसे चुनें:\n' + segs.join(' / '), options: segs.slice(0, 4).map((s, i) => opt('ABCD'[i], s)) };
    }
  }

  const pickRun = (from) => {
    const used = new Set();
    const run = [];
    for (const m of marks.slice(from)) {
      if (!m.id || used.has(m.id)) { if (m.id === 'A' && used.size >= 2) break; continue; }
      used.add(m.id);
      run.push(m);
      if (used.size === 4 && !marks.some((x) => x.id === 'E' && x.start > m.start && x.start < (marks.find((y) => y.id === 'A' && y.start > m.start)?.start ?? Infinity))) break;
      if (used.size === 5) break;
    }
    return run;
  };
  const firstA = marks.findIndex((m) => m.id === 'A');
  if (firstA >= 0) {
    const chosen = pickRun(firstA);
    // Bilingual layout: Hindi stem, Hindi options, English stem, English options.
    const last = chosen[chosen.length - 1];
    const secondA = last ? marks.findIndex((m) => m.id === 'A' && m.start > last.start) : -1;
    if (chosen.length >= 2 && secondA >= 0) {
      const run2 = pickRun(secondA);
      if (run2.length === chosen.length) {
        const tail = body.slice(last.end, run2[0].start).split('\n');
        const lastText = tail[0];
        const enStem = tail.slice(1).join('\n');
        const textOf = (run, i, end) => body.slice(run[i].end, i + 1 < run.length ? run[i + 1].start : end);
        const opts = chosen.map((m, i) => {
          const hi = splitOption(i === chosen.length - 1 ? lastText : textOf(chosen, i, last.end));
          const en = splitOption(textOf(run2, i, body.length));
          return { id: m.id, text_hi: hi.hi || hi.en, text_en: en.en || en.hi, image_base64: null };
        }).sort((a, b) => a.id.localeCompare(b.id));
        return { stem: `${body.slice(0, chosen[0].start)}\n${enStem}`, options: opts };
      }
    }
    if (chosen.length >= 2) {
      let options = chosen.map((m, i) => opt(m.id, body.slice(m.end, i + 1 < chosen.length ? chosen[i + 1].start : body.length)));
      // 2×2 grid where only the left labels survived: "a. X <gap> Y / b. Z <gap> W" → A=X B=Z C=Y D=W
      if (options.length === 2) {
        const parts = chosen.map((m, i) => body.slice(m.end, i + 1 < chosen.length ? chosen[i + 1].start : body.length).trim().split(GAP).map((x) => x.trim()).filter(Boolean));
        if (parts.every((p) => p.length === 2)) options = [opt('A', parts[0][0]), opt('B', parts[1][0]), opt('C', parts[0][1]), opt('D', parts[1][1])];
      }
      options.sort((a, b) => a.id.localeCompare(b.id));
      return { stem: body.slice(0, chosen[0].start), options };
    }
  }

  // No labels at all: the last four lines are the options.
  const lines = body.split('\n').map((l) => l.trim()).filter(Boolean);
  if (lines.length >= 5) {
    const last = lines.slice(-4);
    if (last.every((l) => l.length <= 80)) return { stem: lines.slice(0, -4).join('\n'), options: last.map((l, i) => opt('ABCD'[i], l)) };
  }
  return { stem: body, options: [] };
}

export function heuristicParse(pages) {
  let qs = new Map();
  let cur = null;
  let section = DEFAULT_SECTIONS[0];
  let expected = 1;
  let lastValid = 0;
  let afterHeading = false; // right after a section heading a bigger jump in numbering is plausible
  let passage = null; // { id, lines, section } — an "Passage:" block applies to the following questions
  const passages = {};
  let pCount = 0;
  const startRe = /^\s*(?:Q\.?\s*|प्रश्न\s*)?(\d{1,3})\s*[.)।:]\s*(.*)$/;

  const closePassage = () => {
    if (passage && passage.lines.length) {
      const text = passage.lines.join('\n').trim();
      passages[passage.id] = DEV.test(text) ? { text_hi: text, text_en: '' } : { text_hi: '', text_en: text };
    }
  };
  const flush = () => {
    if (!cur) return;
    const { stem, options } = splitQuestion(cur.lines.join('\n'));
    const l = splitBilingual(stem);
    if (options.length >= 2 && (l.hi || l.en)) {
      qs.set(cur.n, {
        q_no: cur.n, section: cur.section, topic: '', passage_id: cur.passage || null,
        question_hi: l.hi, question_en: l.en, options: options.slice(0, 5), correct_option: null, solution_hi: '', solution_en: '',
      });
      lastValid = cur.n;
    } else {
      expected = lastValid + 1; // not a real question (e.g. numbered instructions): undo the count
    }
    cur = null;
  };

  for (const p of pages) {
    if (p.local.type !== 'questions' && p.local.type !== 'other') continue;
    for (const line of p.lines) {
      const sec = detectSection(line);
      if (sec) { flush(); closePassage(); passage = null; section = sec; afterHeading = true; continue; }
      const pm = line.match(/^\s*(?:passage|गद्यांश|अपठित\s*गद्यांश|अनुच्छेद)\s*[:\-–]?\s*(.*)$/i);
      if (pm && !startRe.test(line)) {
        flush(); closePassage();
        passage = { id: `p_${++pCount}`, lines: [], section, collecting: true };
        continue;
      }
      const m = line.match(startRe);
      if (m) {
        const n = Number(m[1]);
        // A new "1." restarts numbering when what came before was only a handful of items (instructions).
        if (n === 1 && qs.size > 0 && qs.size < 10) { qs = new Map(); lastValid = 0; expected = 1; }
        // Allow small jumps (a question whose number line was garbled) but not stray numbers inside text.
        const looksLikeQ = /[\p{L}]{2,}/u.test(m[2]) && m[2].length >= 8;
        const jumpOk = n <= expected + 3 || (looksLikeQ && n <= expected + (afterHeading ? 40 : 12));
        if (n >= expected && jumpOk) {
          flush();
          if (passage?.collecting) { passage.collecting = false; closePassage(); }
          cur = { n, section, lines: [m[2]], passage: passage && passage.section === section ? passage.id : null };
          expected = n + 1;
          afterHeading = false;
          continue;
        }
      }
      if (passage?.collecting) passage.lines.push(line);
      else if (cur) cur.lines.push(line);
    }
  }
  flush();
  closePassage();
  const list = [...qs.values()].sort((a, b) => a.q_no - b.q_no);
  for (const q of list) if (q.passage_id && !passages[q.passage_id]) q.passage_id = null;
  return { questions: list, passages };
}

// ---------------------------------------------------------------------------
// Main entry
// ---------------------------------------------------------------------------
/**
 * importPdf(file, { settings, useAI, onProgress(evt), onLog(msg, level), signal })
 * Returns { set, warnings, stats }.
 */
export async function importPdf(file, { settings, useAI, onProgress = () => {}, onLog = () => {}, signal }) {
  const mode = settings.pdfMode || 'hybrid';
  const draftKey = `${file.name}|${file.size}|${file.lastModified}|${useAI ? mode : 'local'}`;
  const throwIfAborted = () => { if (signal && signal.aborted) throw new DOMException('Import cancelled', 'AbortError'); };

  onProgress({ phase: 'open', pct: 1, msg: 'PDF खोल रहे हैं…' });
  const buffer = await file.arrayBuffer();
  const pdf = await openPdf(buffer);
  const total = pdf.numPages;
  onLog(`PDF खुली: ${total} पेज / pages`);

  // ---- Pass 1: text layer for every page
  const userMarks = String(settings.watermarks || '').split('\n').map((s) => s.trim()).filter((s) => s.length >= 2);
  const pages = [];
  for (let i = 1; i <= total; i++) {
    throwIfAborted();
    const page = await pdf.getPage(i);
    const info = await extractPage(page, userMarks);
    page.cleanup();
    pages.push({ no: i, ...info });
    onProgress({ phase: 'scan', pct: 2 + Math.round((i / total) * 10), msg: `टेक्स्ट स्कैन: पेज ${i}/${total}` });
  }
  const repeated = removeRepeatedLines(pages);
  if (repeated.size) onLog(`${repeated.size} दोहराई गई header/footer/watermark लाइनें हटाईं`);
  for (const p of pages) {
    p.local = classifyLocal(p);
    if (p.rotated > 3) onLog(`पेज ${p.no}: ${p.rotated} तिरछे watermark टुकड़े हटाए`);
  }

  const stats = { pages: total, adPages: [], keyPages: [], skipped: [], aiCalls: 0, enriched: 0, errors: [] };
  const answerKey = new Map();
  for (const p of pages) {
    if (p.local.type === 'advertisement') { stats.adPages.push(p.no); onLog(`पेज ${p.no}: विज्ञापन पेज — छोड़ा गया`, 'warn'); }
    if (p.local.type === 'answer_key') {
      stats.keyPages.push(p.no);
      for (const [q, a] of p.local.key) answerKey.set(q, a);
      onLog(`पेज ${p.no}: उत्तर कुंजी मिली (${p.local.key.size} उत्तर)`, 'success');
    }
  }

  const titleGuess = file.name.replace(/\.pdf$/i, '').replace(/[_-]+/g, ' ').trim();
  let questions = {};
  let passages = {};
  let solutions = {};
  let metaGuess = {};
  let aiDown = null;

  if (!useAI) {
    onProgress({ phase: 'parse', pct: 70, msg: 'ऑफ़लाइन पार्सर से प्रश्न निकाल रहे हैं…' });
    const local = heuristicParse(pages);
    for (const q of local.questions) questions[q.q_no] = q;
    passages = local.passages;
    onLog(`ऑफ़लाइन पार्सर: ${Object.keys(questions).length} प्रश्न मिले`);
  } else {
    // ---- Pass 2: AI per page (resumable)
    let draft = (await db.get('importDrafts', draftKey)) || { key: draftKey, done: [], questions: {}, passages: {}, solutions: {}, answerKey: {}, metaGuess: {}, currentSection: null, enriched: [], createdAt: Date.now() };
    if (draft.done.length) onLog(`पिछला अधूरा इम्पोर्ट मिला — ${draft.done.length} पेज पहले से पूरे, वहीं से जारी`, 'success');
    for (const [q, a] of Object.entries(draft.answerKey)) answerKey.set(Number(q), a);
    let prevTail = '';
    // One tiny request first: a wrong URL/key/model is reported in seconds instead of after 18 pages.
    onProgress({ phase: 'ai', pct: 12, msg: 'AI कनेक्शन जाँच रहे हैं…' });
    try {
      await chat({ messages: [{ role: 'user', text: 'Reply with exactly: OK' }], signal });
      onLog('AI कनेक्शन ठीक है ✓', 'success');
    } catch (e) {
      if (e.name === 'AbortError') throw e;
      aiDown = e;
    }
    for (const p of pages) {
      if (aiDown) break;
      throwIfAborted();
      const pctBase = 12 + Math.round(((p.no - 1) / total) * 63);
      if (draft.done.includes(p.no)) { prevTail = p.lines.slice(-8).join('\n'); continue; }
      const t = p.local.type;
      const skipAd = t === 'advertisement';
      const skipKey = t === 'answer_key' && p.local.key.size >= 15;
      const skipEmpty = t === 'image_only' && mode === 'text';
      if (skipAd || skipKey || skipEmpty) {
        draft.done.push(p.no);
        if (skipEmpty) { stats.skipped.push(p.no); onLog(`पेज ${p.no}: टेक्स्ट नहीं (स्कैन इमेज) — Text मोड में छोड़ा। Hybrid/Vision मोड इस्तेमाल करें`, 'warn'); }
        continue;
      }
      onProgress({ phase: 'ai', pct: pctBase, msg: `AI पेज ${p.no}/${total} पढ़ रहा है…`, page: p.no, total });
      const page = await pdf.getPage(p.no);
      let canvas = null;
      try {
        if (mode !== 'text') canvas = await renderPage(page, Number(settings.renderScale) || 1.6);
        const text = p.lines.join('\n').slice(0, 12000);
        const result = await extractPageWithAI({ canvas, text, p, total, prevTail, draft, mode, signal, stats, onLog });
        if (result) {
          if (result.exam_meta_guess) for (const [k, v] of Object.entries(result.exam_meta_guess)) if (v != null && v !== '' && draft.metaGuess[k] == null) draft.metaGuess[k] = v;
          if (result.page_type === 'advertisement') { stats.adPages.push(p.no); onLog(`पेज ${p.no}: AI ने विज्ञापन पेज बताया — छोड़ा`, 'warn'); }
          let sec = draft.currentSection;
          for (const q of result.questions || []) { mergeQuestion(draft.questions, q, sec); sec = q.section || sec; }
          if (result.current_section) draft.currentSection = result.current_section;
          else if (sec) draft.currentSection = sec;
          Object.assign(draft.passages, Object.fromEntries(Object.entries(result.passages || {}).filter(([id, v]) => v && !draft.passages[id])));
          for (const k of result.answer_key || []) {
            const a = normalizeOptionId(k.ans ?? k.answer ?? k.correct_option);
            if (a && Number(k.q_no) > 0) { draft.answerKey[Number(k.q_no)] = a; answerKey.set(Number(k.q_no), a); }
          }
          if ((result.answer_key || []).length >= 8 && !stats.keyPages.includes(p.no)) { stats.keyPages.push(p.no); onLog(`पेज ${p.no}: AI ने उत्तर कुंजी पढ़ी (${result.answer_key.length})`, 'success'); }
          for (const s of result.solutions || []) {
            const n = Number(s.q_no);
            if (n > 0) draft.solutions[n] = { hi: longer(draft.solutions[n]?.hi, s.solution_hi), en: longer(draft.solutions[n]?.en, s.solution_en) };
          }
          if (canvas) for (const f of result.figures || []) if (Array.isArray(f.bbox) && f.bbox.length === 4) attachFigure(draft.questions, f, cropCanvas(canvas, f.bbox));
          const n = (result.questions || []).length;
          if (n) onLog(`पेज ${p.no}: ${n} प्रश्न (${result.questions.map((q) => q.q_no).filter(Boolean).slice(0, 1)}–${result.questions.map((q) => q.q_no).filter(Boolean).slice(-1)})`);
        }
        draft.done.push(p.no);
        await db.put('importDrafts', draft);
      } catch (e) {
        if (e.name === 'AbortError') throw e;
        stats.errors.push(`पेज ${p.no}: ${e.message}`);
        onLog(`पेज ${p.no}: त्रुटि — ${e.message}`, 'error');
        // Wrong URL / key / model or no internet: every page would fail the same way, so stop now.
        if (e.fatal) { aiDown = e; break; }
      } finally {
        if (canvas) { canvas.width = 0; canvas.height = 0; }
        page.cleanup();
      }
      prevTail = p.lines.slice(-8).join('\n');
    }
    questions = draft.questions;
    passages = draft.passages;
    solutions = draft.solutions;
    metaGuess = draft.metaGuess;

    if (aiDown) {
      stats.errors.push(aiDown.message);
      onLog(`AI से कनेक्ट नहीं हो सका: ${aiDown.message}`, 'error');
      onLog('AI के बिना ऑफ़लाइन पार्सर से प्रश्न निकाल रहे हैं (सॉल्यूशन नहीं बनेंगे)। Settings ठीक करके वही PDF दोबारा चुनें।', 'warn');
    }
    // Fill every question number the AI did not return (or all of them if AI failed) from the offline parser.
    const before = Object.keys(questions).length;
    const local = heuristicParse(pages);
    for (const q of local.questions) if (!questions[q.q_no]) questions[q.q_no] = q;
    for (const [id, v] of Object.entries(local.passages)) if (!passages[id]) passages[id] = v;
    const added = Object.keys(questions).length - before;
    if (added) onLog(`ऑफ़लाइन पार्सर से ${added} प्रश्न जोड़े`, before ? 'warn' : 'info');

    // ---- Answer key + solutions
    applyKeyAndSolutions(questions, answerKey, solutions);

    // ---- Pass 3: enrichment
    if (settings.autoSolve && !aiDown) {
      const need = Object.values(questions).filter((q) => !draft.enriched.includes(q.q_no) && (!q.correct_option || !(q.solution_hi || q.solution_en) || !q.topic || !q.key_concept));
      const BATCH = 8;
      for (let i = 0; i < need.length; i += BATCH) {
        throwIfAborted();
        const batch = need.slice(i, i + BATCH);
        onProgress({ phase: 'enrich', pct: 76 + Math.round((i / Math.max(1, need.length)) * 22), msg: `AI सॉल्यूशन + टॉपिक टैगिंग: ${Math.min(i + BATCH, need.length)}/${need.length}` });
        try {
          await enrichBatch(batch, passages, signal, stats);
          draft.enriched.push(...batch.map((q) => q.q_no));
          stats.enriched += batch.length;
          draft.questions = questions;
          await db.put('importDrafts', draft);
        } catch (e) {
          if (e.name === 'AbortError') throw e;
          stats.errors.push(`Enrich Q${batch[0].q_no}-${batch[batch.length - 1].q_no}: ${e.message}`);
          onLog(`सॉल्यूशन बैच Q${batch[0].q_no}–${batch[batch.length - 1].q_no}: ${e.message}`, 'error');
          if (e.fatal) { onLog('AI उपलब्ध नहीं — बाकी सॉल्यूशन छोड़े गए (बाद में Ask AI से पूछ सकते हैं)', 'warn'); break; }
        }
      }
      if (need.length) onLog(`${stats.enriched} प्रश्नों के सॉल्यूशन/टॉपिक AI से बने`, 'success');
    }
  }

  if (!useAI) applyKeyAndSolutions(questions, answerKey, solutions);

  onProgress({ phase: 'build', pct: 99, msg: 'प्रैक्टिस सेट बना रहे हैं…' });
  const qList = Object.values(questions).sort((a, b) => a.q_no - b.q_no);
  if (!qList.length) throw new Error('इस PDF से कोई प्रश्न नहीं निकला। Hybrid/Vision मोड और API Key के साथ फिर कोशिश करें, या "Copy AI Extraction Prompt" वाला तरीका अपनाएँ।');
  const keyed = qList.filter((q) => q.correct_option).length;
  onLog(`कुल ${qList.length} प्रश्न, ${keyed} के सही उत्तर मैप हुए`, keyed === qList.length ? 'success' : 'warn');
  const sections = [];
  for (const q of qList) if (q.section && !sections.includes(q.section)) sections.push(q.section);
  const title = String(metaGuess.title || titleGuess).trim();
  const raw = {
    exam_meta: {
      exam_id: `${slug(title)}_${Date.now().toString(36)}`,
      title,
      duration_minutes: Number(metaGuess.duration_minutes) || Math.max(10, qList.length),
      max_marks: Number(metaGuess.max_marks) || qList.length,
      positive_marks: 1,
      negative_marks: Number(metaGuess.negative_marks) || 0,
      instructions_hi: 'सभी प्रश्नों के अंक समान हैं।',
      instructions_en: 'All questions carry equal marks.',
    },
    sections,
    passages,
    questions: qList,
  };
  const { set, warnings } = normalizeSet(raw, { source: useAI ? `pdf-ai:${mode}` : 'pdf-local' });
  stats.questions = set.questions.length;
  stats.keyed = keyed;
  stats.draftKey = draftKey;
  return { set, warnings, stats };
}

export async function discardDraft(key) {
  if (key) await db.delete('importDrafts', key);
}

function applyKeyAndSolutions(questions, answerKey, solutions) {
  for (const q of Object.values(questions)) {
    const k = answerKey.get(Number(q.q_no));
    if (k) { q.correct_option = k; q.answer_source = 'key'; }
    const s = solutions[q.q_no];
    if (s) { q.solution_hi = longer(q.solution_hi, s.hi); q.solution_en = longer(q.solution_en, s.en); }
  }
}

async function callPage({ images, text, p, total, prevTail, currentSection, mode, signal, stats, note }) {
  stats.aiCalls++;
  const user = pageUserPrompt({ pageNo: p.no, totalPages: total, text, prevTail, currentSection, mode }) + (note ? `\n\n${note}` : '');
  const r = await chat({ system: PAGE_SYSTEM_PROMPT, messages: [{ role: 'user', text: user, images }], json: true, maxTokens: 16000, temperature: 0, signal });
  return { ...r, json: (() => { try { return extractJson(r.text); } catch (e) { return { __error: e.message }; } })() };
}

async function extractPageWithAI({ canvas, text, p, total, prevTail, draft, mode, signal, stats, onLog }) {
  const images = canvas ? [canvasToJpeg(canvas)] : [];
  const sendText = mode === 'vision' ? '' : text;
  const first = await callPage({ images, text: sendText, p, total, prevTail, currentSection: draft.currentSection, mode, signal, stats });
  if (!first.truncated && !first.json.__error) return first.json;

  // Response cut off or invalid → split the page (columns if detected, else top/bottom halves) and retry.
  const why = first.truncated ? 'जवाब बीच में कट गया (token limit)' : `जवाब JSON नहीं था: "${String(first.text).replace(/\s+/g, ' ').slice(0, 90)}"`;
  onLog(`पेज ${p.no}: ${why} — पेज को दो हिस्सों में बाँटकर फिर पढ़ रहे हैं`, 'warn');
  if (!canvas) {
    const lines = text.split('\n');
    const half = Math.ceil(lines.length / 2);
    const a = await callPage({ images: [], text: lines.slice(0, half).join('\n'), p, total, prevTail, currentSection: draft.currentSection, mode, signal, stats, note: 'This is the FIRST HALF of the page text.' });
    const b = await callPage({ images: [], text: lines.slice(half).join('\n'), p, total, prevTail: lines.slice(Math.max(0, half - 6), half).join('\n'), currentSection: draft.currentSection, mode, signal, stats, note: 'This is the SECOND HALF of the page text.' });
    return combineParts([a.json, b.json], null);
  }
  const g = p.gutterFrac;
  const boxes = g ? [[0, 0, g, 1], [g, 0, 1, 1]] : [[0, 0, 1, 0.52], [0, 0.48, 1, 1]];
  const parts = [];
  for (const [k, box] of boxes.entries()) {
    const img = cropCanvas(canvas, box, { maxW: 1600, q: 0.85 });
    const note = `This image is only the ${g ? (k ? 'RIGHT column' : 'LEFT column') : (k ? 'BOTTOM half' : 'TOP half')} of the page. Figure bboxes must be fractions of THIS cropped image.`;
    const r = await callPage({ images: [img], text: '', p, total, prevTail: k ? '' : prevTail, currentSection: draft.currentSection, mode: 'vision', signal, stats, note });
    parts.push({ json: r.json, box });
  }
  return combineParts(parts.map((x) => x.json), parts.map((x) => x.box));
}

function combineParts(jsons, boxes) {
  const out = { page_type: 'questions', questions: [], passages: {}, answer_key: [], solutions: [], figures: [], exam_meta_guess: {} };
  jsons.forEach((j, idx) => {
    if (!j || j.__error) return;
    if (j.page_type && j.page_type !== 'questions') out.page_type = out.questions.length ? out.page_type : j.page_type;
    out.questions.push(...(j.questions || []));
    Object.assign(out.passages, j.passages || {});
    out.answer_key.push(...(j.answer_key || []));
    out.solutions.push(...(j.solutions || []));
    Object.assign(out.exam_meta_guess, j.exam_meta_guess || {});
    if (j.current_section) out.current_section = j.current_section;
    for (const f of j.figures || []) {
      if (!Array.isArray(f.bbox) || f.bbox.length !== 4) continue;
      if (!boxes) { out.figures.push(f); continue; }
      const [bx0, by0, bx1, by1] = boxes[idx];
      const w = bx1 - bx0, h = by1 - by0;
      out.figures.push({ ...f, bbox: [bx0 + f.bbox[0] * w, by0 + f.bbox[1] * h, bx0 + f.bbox[2] * w, by0 + f.bbox[3] * h] });
    }
  });
  if (out.questions.length) out.page_type = 'questions';
  return out;
}

async function enrichBatch(batch, passages, signal, stats) {
  const payload = batch.map((q) => ({
    q_no: q.q_no,
    section: q.section,
    passage: q.passage_id && passages[q.passage_id] ? String(passages[q.passage_id].text_en || passages[q.passage_id].text_hi || '').slice(0, 1500) : undefined,
    question_hi: q.question_hi, question_en: q.question_en,
    table_html: q.question_media?.table_html || undefined,
    options: (q.options || []).map((o) => ({ id: o.id, text_hi: o.text_hi, text_en: o.text_en })),
    correct_option: q.correct_option || null,
    existing_solution: q.solution_hi || q.solution_en || undefined,
  }));
  stats.aiCalls++;
  const r = await chat({ system: ENRICH_SYSTEM_PROMPT, messages: [{ role: 'user', text: JSON.stringify({ questions: payload }) }], json: true, maxTokens: 12000, temperature: 0.2, signal });
  const data = extractJson(r.text);
  const items = Array.isArray(data) ? data : data.items || data.questions || [];
  for (const it of items) {
    const q = batch.find((x) => Number(x.q_no) === Number(it.q_no));
    if (!q) continue;
    if (!q.correct_option) {
      const a = normalizeOptionId(it.correct_option);
      if (a) { q.correct_option = a; q.answer_source = 'ai'; }
    }
    if (!q.solution_hi && it.solution_hi) q.solution_hi = it.solution_hi;
    if (!q.solution_en && it.solution_en) q.solution_en = it.solution_en;
    if (!q.key_concept && it.key_concept) q.key_concept = it.key_concept;
    if ((!q.topic || /अवर्गीकृत|uncategor/i.test(q.topic)) && it.topic) q.topic = it.topic;
    if (!q.syllabus_tag && it.syllabus_tag) q.syllabus_tag = it.syllabus_tag;
    if (it.difficulty) q.difficulty = q.difficulty || it.difficulty;
  }
}
