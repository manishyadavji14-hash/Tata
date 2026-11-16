// Custom AI question generation: any book / notes / document → new practice questions of the
// requested types (text, tables, SVG diagrams, figures cropped from the source pages).
import { chat } from './ai.js';
import { GEN_SYSTEM_PROMPT, genUserPrompt, QTYPES } from './prompts.js';
import { normalizeSet } from './schema.js';
import { extractJson, readFile, imageFileToDataUrl, slug } from './util.js';

export { QTYPES };

const TEXT_EXT = /\.(txt|md|csv|tsv|html?|json|xml|tex|srt)$/i;

/** Parse "1-5, 9, 12-14" → [1..5, 9, 12, 13, 14] (capped). */
export function parsePages(spec, max, cap = 60) {
  const out = new Set();
  const s = String(spec || '').trim();
  if (!s) { for (let i = 1; i <= Math.min(max, 20); i++) out.add(i); return [...out]; }
  for (const part of s.split(/[,\s]+/).filter(Boolean)) {
    const m = /^(\d+)(?:-(\d+))?$/.exec(part);
    if (!m) continue;
    const a = Number(m[1]), b = Number(m[2] || m[1]);
    for (let i = Math.min(a, b); i <= Math.max(a, b) && out.size < cap; i++) if (i >= 1 && i <= max) out.add(i);
  }
  return [...out].sort((x, y) => x - y);
}

/**
 * Build source units from the chosen files and pasted text.
 * files: [{ file, pages? }]  → units: [{ label, page?, text, image }]
 */
export async function buildSources({ files, pasted, withImages, onLog = () => {}, signal }) {
  const units = [];
  let pageNo = 0; // global page labels so the AI can refer to "page N"
  for (const { file, pages } of files) {
    if (/\.pdf$/i.test(file.name) || file.type === 'application/pdf') {
      const { pdfPageCount, pdfSourcePages } = await import('./pdf-import.js');
      const buf = await readFile(file, 'buffer');
      const total = await pdfPageCount(buf);
      const list = parsePages(pages, total);
      onLog(`📑 ${file.name}: ${total} पेज, चुने गए ${list.length} (${list.slice(0, 12).join(', ')}${list.length > 12 ? '…' : ''})`);
      const got = await pdfSourcePages(buf, list, { images: withImages, signal, onPage: (i, n) => { if (i % 5 === 0 || i === n) onLog(`  पेज पढ़े: ${i}/${n}`); } });
      for (const g of got) units.push({ label: `${file.name} — पेज ${g.page}`, page: ++pageNo, text: g.text, image: g.image });
    } else if (/^image\//.test(file.type) || /\.(png|jpe?g|webp|gif|bmp|heic)$/i.test(file.name)) {
      const image = await imageFileToDataUrl(file, 1600, 0.85);
      units.push({ label: file.name, page: ++pageNo, text: '', image });
      onLog(`🖼️ ${file.name}: चित्र जोड़ा`);
    } else if (TEXT_EXT.test(file.name) || /^text\//.test(file.type)) {
      let text = await readFile(file);
      if (/\.html?$/i.test(file.name)) text = new DOMParser().parseFromString(text, 'text/html').body.innerText;
      for (const [i, c] of chunkText(text, 12000).entries()) units.push({ label: `${file.name}${i ? ` (भाग ${i + 1})` : ''}`, text: c, image: null });
      onLog(`📄 ${file.name}: ${text.length} अक्षर`);
    } else {
      onLog(`⚠️ ${file.name}: यह फ़ाइल प्रकार नहीं पढ़ सकते — PDF, फ़ोटो या .txt दें (Word फ़ाइल को PDF में सेव करें)`, 'warn');
    }
  }
  const p = String(pasted || '').trim();
  if (p) for (const [i, c] of chunkText(p, 12000).entries()) units.push({ label: `पेस्ट किया टेक्स्ट${i ? ` (भाग ${i + 1})` : ''}`, text: c, image: null });
  return units;
}

function chunkText(text, size) {
  const t = String(text || '');
  if (t.length <= size) return t.trim() ? [t] : [];
  const out = [];
  let i = 0;
  while (i < t.length) {
    let j = Math.min(t.length, i + size);
    if (j < t.length) { const k = t.lastIndexOf('\n', j); if (k > i + size * 0.6) j = k; }
    out.push(t.slice(i, j));
    i = j;
  }
  return out;
}

// Groups of units that fit one request (text budget + image budget).
function groupUnits(units, { maxChars = 24000, maxImages = 6 } = {}) {
  const groups = [];
  let cur = [], chars = 0, imgs = 0;
  for (const u of units) {
    const c = u.text.length, im = u.image ? 1 : 0;
    if (cur.length && (chars + c > maxChars || imgs + im > maxImages)) { groups.push(cur); cur = []; chars = 0; imgs = 0; }
    cur.push(u); chars += c; imgs += im;
  }
  if (cur.length) groups.push(cur);
  return groups;
}

// ---------- figures ----------
function sanitizeSvg(svg) {
  let s = String(svg || '').trim();
  const a = s.indexOf('<svg');
  const b = s.lastIndexOf('</svg>');
  if (a < 0 || b < 0) return null;
  s = s.slice(a, b + 6)
    .replace(/<script[\s\S]*?<\/script>/gi, '')
    .replace(/<foreignObject[\s\S]*?<\/foreignObject>/gi, '')
    .replace(/\son\w+\s*=\s*("[^"]*"|'[^']*')/gi, '')
    .replace(/(href\s*=\s*["'])\s*(?!#)[^"']*/gi, '$1#');
  if (!/xmlns=/.test(s)) s = s.replace('<svg', '<svg xmlns="http://www.w3.org/2000/svg"');
  return s;
}

export async function svgToPng(svg, maxW = 900) {
  const clean = sanitizeSvg(svg);
  if (!clean) return null;
  const url = `data:image/svg+xml;charset=utf-8,${encodeURIComponent(clean)}`;
  const img = new Image();
  await new Promise((res, rej) => { img.onload = res; img.onerror = () => rej(new Error('SVG')); img.src = url; });
  let w = img.naturalWidth || 600, h = img.naturalHeight || 400;
  const vb = /viewBox\s*=\s*["']\s*[-\d.]+[\s,]+[-\d.]+[\s,]+([\d.]+)[\s,]+([\d.]+)/i.exec(clean);
  if (vb && (!img.naturalWidth || img.naturalWidth === 150)) { w = Number(vb[1]); h = Number(vb[2]); }
  const k = Math.min(2, maxW / w);
  const c = document.createElement('canvas');
  c.width = Math.max(1, Math.round(w * k)); c.height = Math.max(1, Math.round(h * k));
  const ctx = c.getContext('2d');
  ctx.fillStyle = '#fff'; ctx.fillRect(0, 0, c.width, c.height);
  ctx.drawImage(img, 0, 0, c.width, c.height);
  const out = c.toDataURL('image/png');
  c.width = 0; c.height = 0;
  return out;
}

async function cropDataUrl(dataUrl, bbox, maxW = 900) {
  if (!dataUrl || !Array.isArray(bbox) || bbox.length !== 4) return null;
  const img = new Image();
  await new Promise((res, rej) => { img.onload = res; img.onerror = rej; img.src = dataUrl; });
  const cl = (v) => Math.min(1, Math.max(0, Number(v) || 0));
  let [a, b, c, d] = bbox.map(cl);
  if (c < a) [a, c] = [c, a];
  if (d < b) [b, d] = [d, b];
  const W = img.naturalWidth, H = img.naturalHeight;
  const sw = (c - a) * W, sh = (d - b) * H;
  if (sw < 8 || sh < 8) return null;
  const k = Math.min(1, maxW / sw);
  const cv = document.createElement('canvas');
  cv.width = Math.round(sw * k); cv.height = Math.round(sh * k);
  const ctx = cv.getContext('2d');
  ctx.fillStyle = '#fff'; ctx.fillRect(0, 0, cv.width, cv.height);
  ctx.drawImage(img, a * W, b * H, sw, sh, 0, 0, cv.width, cv.height);
  const out = cv.toDataURL('image/jpeg', 0.88);
  cv.width = 0; cv.height = 0;
  return out;
}

async function figureFrom(obj, pageImages) {
  if (!obj || typeof obj !== 'object') return null;
  if (obj.svg) { try { const p = await svgToPng(obj.svg); if (p) return p; } catch { /* fall through */ } }
  const sf = obj.source_figure;
  if (sf && pageImages.has(Number(sf.page))) { try { return await cropDataUrl(pageImages.get(Number(sf.page)), sf.bbox); } catch { return null; } }
  return null;
}

async function resolveFigures(q, pageImages) {
  q.question_media = q.question_media || {};
  const qi = await figureFrom(q.question_media, pageImages);
  if (qi) q.question_media.image_base64 = qi;
  for (const o of q.options || []) {
    if (!o || typeof o !== 'object') continue;
    const oi = await figureFrom(o, pageImages);
    if (oi) o.image_base64 = oi;
  }
  if (q.solution_svg) {
    try {
      const p = await svgToPng(q.solution_svg);
      if (p) {
        const tag = `<img src="${p}" alt="हल का चित्र">`;
        if (q.solution_hi || !q.solution_en) q.solution_hi = `${q.solution_hi || ''}\n${tag}`; else q.solution_en = `${q.solution_en}\n${tag}`;
      }
    } catch { /* ignore broken svg */ }
  }
  return q;
}

// ---------- planning ----------
function typePlan(total, types) {
  const list = types.length ? types : [];
  const seq = [];
  for (let i = 0; i < total; i++) seq.push(list.length ? list[i % list.length] : null);
  return seq;
}

const stemOf = (q) => String(q.question_hi || q.question_en || '').replace(/<[^>]+>/g, ' ').replace(/\s+/g, ' ').trim();

/**
 * Resumable generation used by background jobs. job.params = { count, perCall, types, lang, difficulty,
 * section, fidelity, custom, title, pos, neg, minutes, withImages }. Progress lives in job.state and is
 * saved after every batch, so a paused / interrupted job continues where it stopped.
 * ctx: { files:[{file,pages}], signal, log(msg, level), progress({pct,msg}), saveState() }
 */
export async function runGeneration(job, { files, signal, log = () => {}, progress = () => {}, saveState = async () => {} }) {
  const p = job.params;
  const count = Math.max(1, Number(p.count) || 20);
  const perCall = Math.max(3, Math.min(20, Number(p.perCall) || 8));
  const types = p.types || [];
  progress({ pct: 1, msg: 'स्रोत पढ़ रहे हैं…' });
  const units = await buildSources({ files, pasted: job.pasted, withImages: !!p.withImages, onLog: log, signal });
  if (!units.length && !p.custom) throw new Error('कोई स्रोत नहीं — फ़ाइल, टेक्स्ट या निर्देश दें');
  const groups = units.length ? groupUnits(units) : [[]];
  const nBatches = Math.max(Math.ceil(count / perCall), Math.min(groups.length, count));
  const plan = typePlan(count, types.filter((t) => t !== 'five'));
  const pageImages = new Map(units.filter((u) => u.page && u.image).map((u) => [u.page, u.image]));
  const st = job.state || (job.state = { batchesDone: 0, extra: 0, questions: [], passages: {} });
  const seen = new Set([...(job.avoid || []), ...st.questions.map(stemOf)].map((x) => String(x).toLowerCase()));
  if (st.batchesDone) log(`पिछली प्रगति: ${st.questions.length} प्रश्न तैयार — आगे जारी`, 'success');
  for (let b = st.batchesDone; ; b++) {
    const planned = b < nBatches;
    if (!planned && (st.questions.length >= count || st.extra >= 3)) break;
    if (signal?.aborted) throw new DOMException('Aborted', 'AbortError');
    const from = Math.round((b * count) / nBatches);
    const want = planned ? Math.round(((b + 1) * count) / nBatches) - from : Math.min(perCall, count - st.questions.length);
    if (want <= 0) { st.batchesDone = b + 1; continue; }
    const slice = planned ? plan.slice(from, from + want) : plan.slice(0, want);
    const typeCounts = {};
    for (const t of slice) if (t) typeCounts[t] = (typeCounts[t] || 0) + 1;
    const group = groups[b % groups.length];
    const textPart = group.filter((u) => u.text.trim()).map((u) => `[${u.page ? `पेज ${u.page}` : u.label}] ${u.label}\n"""${u.text.trim()}"""`).join('\n\n');
    const imgs = group.filter((u) => u.image);
    const sourceNote = [
      group.length ? `SOURCE (part ${(b % groups.length) + 1} of ${groups.length}):` : 'No source document — follow the custom instructions only.',
      imgs.length ? `Attached page images in order: ${imgs.map((u) => `page ${u.page}`).join(', ')}. Use "source_figure" with these page numbers to reuse their figures.` : '',
      textPart,
    ].filter(Boolean).join('\n');
    const avoid = [...(job.avoid || []).slice(-25), ...st.questions.slice(-30).map(stemOf)].map((x) => String(x).slice(0, 90));
    const user = genUserPrompt({
      count: want, typeCounts, lang: p.lang, difficulty: p.difficulty, section: p.section, fidelity: p.fidelity,
      five: types.includes('five'), custom: p.custom, avoid, sourceNote,
    });
    const label = planned ? `बैच ${b + 1}/${nBatches}` : `अतिरिक्त बैच ${st.extra + 1}`;
    progress({ pct: Math.min(97, Math.round((st.questions.length / count) * 100)), msg: `${label}: ${want} प्रश्न बन रहे हैं… (अब तक ${st.questions.length}/${count})` });
    let data = null;
    try {
      const r = await chat({ system: GEN_SYSTEM_PROMPT, messages: [{ role: 'user', text: user, images: imgs.map((u) => u.image) }], json: true, maxTokens: 16000, temperature: 0.6, signal });
      data = extractJson(r.text);
      if (r.truncated) log(`${label}: जवाब लंबा होकर कट गया — जितने पूरे प्रश्न मिले वे रखे`, 'warn');
    } catch (e) {
      if (e.name === 'AbortError') throw e;
      log(`${label}: ${e.message}`, 'error');
      if (e.fatal) throw e;
    }
    let got = 0;
    if (data) {
      const qs = Array.isArray(data.questions) ? data.questions : Array.isArray(data) ? data : [];
      const pmap = {};
      for (const [k, v] of Object.entries(data.passages || {})) { const id = `g${b + 1}_${st.extra}_${k}`; pmap[k] = id; st.passages[id] = v; }
      for (const q of qs) {
        if (!q || typeof q !== 'object' || st.questions.length >= count) continue;
        const sm = stemOf(q).toLowerCase();
        if (!sm && !q.question_media) continue;
        if (sm && seen.has(sm)) continue;
        seen.add(sm);
        if (q.passage_id) q.passage_id = pmap[q.passage_id] || null;
        await resolveFigures(q, pageImages);
        st.questions.push(q);
        got++;
      }
    }
    log(`${label}: ${got} प्रश्न ✓ (कुल ${st.questions.length}/${count})`, got ? 'success' : 'warn');
    st.batchesDone = b + 1;
    if (!planned) st.extra++;
    await saveState();
  }
  if (!st.questions.length) throw new Error('AI ने कोई प्रश्न नहीं बनाया — निर्देश/स्रोत जाँचें या दूसरा मॉडल आज़माएँ');
  progress({ pct: 100, msg: `पूरा हुआ ✓ ${st.questions.length} प्रश्न` });
}

/** Normalised practice set from a generation job's (possibly partial) state.
 *  Returns { set, warnings, rawIndex } where rawIndex[i] = index in job.state.questions. */
export function buildGenSet(job) {
  const p = job.params;
  const raw = (job.state?.questions || []).map((q, i) => ({ ...q, q_no: i + 1 }));
  if (!raw.length) return null;
  const pos = Number(p.pos) || 1;
  const title = job.title;
  const { set, warnings } = normalizeSet({
    exam_meta: {
      exam_id: `ai_${slug(title).slice(0, 40) || 'gen'}_${job.id.slice(-6)}`, title,
      duration_minutes: Number(p.minutes) || Math.max(5, Math.round(raw.length * 1.2)),
      max_marks: raw.length * pos, positive_marks: pos, negative_marks: Number(p.neg) || 0,
      instructions_hi: `ये प्रश्न AI द्वारा आपके दिए स्रोत से बनाए गए हैं। उत्तर और व्याख्या एक बार जाँच लें।${p.custom ? `\nनिर्देश: ${String(p.custom).slice(0, 300)}` : ''}`,
      instructions_en: '',
    },
    passages: job.state.passages || {}, questions: raw,
  }, { source: 'ai-generated' });
  const rawIndex = set.questions.map((q) => q.q_no - 1);
  set.questions.forEach((q, i) => { q.q_no = i + 1; q.answer_source = 'ai'; });
  return { set, warnings, rawIndex };
}

export { stemOf };

/** Prompt to paste into ChatGPT/Gemini/Claude together with the document (no API key needed). */
export function externalGenPrompt(o) {
  const typeCounts = {};
  const plan = typePlan(o.count, o.types.filter((t) => t !== 'five'));
  for (const t of plan) if (t) typeCounts[t] = (typeCounts[t] || 0) + 1;
  return `${GEN_SYSTEM_PROMPT}

The source document is attached to this message.
${genUserPrompt({ count: o.count, typeCounts, lang: o.lang, difficulty: o.difficulty, section: o.section, fidelity: o.fidelity, five: o.types.includes('five'), custom: o.custom })}

APP NOTE: My app CAN draw "svg" fields (question_media.svg, options[i].svg, solution_svg) — use them for diagrams. It cannot use "source_figure" from an external chat, so for a printed picture you cannot redraw, describe it in brackets, e.g. "[चित्र: ...]". Add at the top level: "exam_meta": { "exam_id": "my_custom_test_1", "title": "${String(o.title || 'AI Custom Test').replace(/"/g, "'")}", "duration_minutes": ${Math.max(5, Math.round(o.count * 1.2))}, "positive_marks": 1, "negative_marks": 0 }. If the reply would be too long, give questions 1-25 first as a complete JSON object and continue with the next part (same exam_id) when I say "continue".`;
}

/** Pasted / received JSON may contain AI "svg" figures → convert them to images before normalising. */
export async function resolveRawFigures(data) {
  const qsets = data && Array.isArray(data.sets) ? data.sets : [data];
  let n = 0;
  for (const s of qsets) {
    const qs = Array.isArray(s?.questions) ? s.questions : Array.isArray(s) ? s : [];
    for (const q of qs) {
      if (!q || typeof q !== 'object') continue;
      const has = q.solution_svg || q.question_media?.svg || (Array.isArray(q.options) && q.options.some((o) => o && o.svg));
      if (has) { await resolveFigures(q, new Map()); n++; }
    }
  }
  return n;
}
