// Shared helpers: escaping, sanitising, KaTeX rich-text rendering, formatting, files and clipboard.

export const $ = (sel, root = document) => root.querySelector(sel);
export const $$ = (sel, root = document) => Array.from(root.querySelectorAll(sel));

export function uid(prefix = 'id') {
  const rnd = crypto.getRandomValues(new Uint32Array(2));
  return `${prefix}_${Date.now().toString(36)}${rnd[0].toString(36)}${rnd[1].toString(36).slice(0, 4)}`;
}

export function esc(s) {
  return String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

export function slug(s) {
  return String(s || '').toLowerCase().normalize('NFKD').replace(/[^\p{L}\p{N}]+/gu, '_').replace(/^_+|_+$/g, '').slice(0, 60) || 'set';
}

export function fmtClock(ms) {
  const t = Math.max(0, Math.floor(ms / 1000));
  const h = Math.floor(t / 3600), m = Math.floor((t % 3600) / 60), s = t % 60;
  const p = (n) => String(n).padStart(2, '0');
  return h > 0 ? `${p(h)}:${p(m)}:${p(s)}` : `${p(m)}:${p(s)}`;
}

export function fmtDur(ms) {
  const t = Math.round((ms || 0) / 1000);
  if (t < 60) return `${t}s`;
  const h = Math.floor(t / 3600), m = Math.floor((t % 3600) / 60), s = t % 60;
  return h ? `${h}h ${m}m` : `${m}m ${s}s`;
}

export function fmtDate(ts) {
  if (!ts) return '';
  return new Date(ts).toLocaleString('en-IN', { day: '2-digit', month: 'short', year: 'numeric', hour: '2-digit', minute: '2-digit' });
}

export const pct = (a, b) => (b > 0 ? Math.round((a / b) * 1000) / 10 : 0);
export const round2 = (n) => Math.round(n * 100) / 100;
export const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

export function debounce(fn, ms) {
  let t;
  return (...a) => { clearTimeout(t); t = setTimeout(() => fn(...a), ms); };
}

// ---------- HTML sanitiser (whitelist) ----------
const ALLOWED_TAGS = new Set(['B', 'STRONG', 'I', 'EM', 'U', 'S', 'SUP', 'SUB', 'BR', 'P', 'DIV', 'SPAN', 'SMALL', 'MARK',
  'TABLE', 'THEAD', 'TBODY', 'TFOOT', 'TR', 'TD', 'TH', 'CAPTION', 'UL', 'OL', 'LI', 'HR', 'IMG', 'H3', 'H4', 'H5',
  'CODE', 'PRE', 'BLOCKQUOTE', 'CENTER']);
const DROP_WITH_CONTENT = new Set(['SCRIPT', 'STYLE', 'IFRAME', 'OBJECT', 'EMBED', 'NOSCRIPT', 'TEMPLATE', 'SVG', 'MATH', 'FORM', 'INPUT', 'BUTTON', 'TEXTAREA', 'SELECT', 'LINK', 'META', 'BASE', 'TITLE', 'HEAD']);

function cleanNode(node) {
  for (const child of Array.from(node.childNodes)) {
    if (child.nodeType === Node.TEXT_NODE) continue;
    if (child.nodeType !== Node.ELEMENT_NODE) { child.remove(); continue; }
    const tag = child.tagName.toUpperCase();
    if (DROP_WITH_CONTENT.has(tag)) { child.remove(); continue; }
    cleanNode(child);
    if (!ALLOWED_TAGS.has(tag)) { child.replaceWith(...Array.from(child.childNodes)); continue; }
    for (const attr of Array.from(child.attributes)) {
      const n = attr.name.toLowerCase();
      const v = attr.value;
      let keep = false;
      if ((tag === 'TD' || tag === 'TH') && (n === 'colspan' || n === 'rowspan')) keep = /^\d{1,2}$/.test(v);
      if (tag === 'IMG' && n === 'src') keep = /^data:image\/(png|jpe?g|gif|webp);base64,[a-z0-9+/=\s]+$/i.test(v);
      if (tag === 'IMG' && (n === 'width' || n === 'height')) keep = /^\d{1,4}$/.test(v);
      if (tag === 'IMG' && n === 'alt') keep = true;
      if (!keep) child.removeAttribute(attr.name);
    }
    if (tag === 'IMG' && !child.getAttribute('src')) child.remove();
  }
}

export function sanitizeHtml(html) {
  const doc = new DOMParser().parseFromString(`<body>${html}</body>`, 'text/html');
  cleanNode(doc.body);
  return doc.body;
}

// ---------- Math extraction ----------
const MATH_RE = /\$\$([\s\S]+?)\$\$|\\\[([\s\S]+?)\\\]|\\\(([\s\S]+?)\\\)|(?<![\\$])\$(?!\s)([^$\n]+?)(?<!\s)\$(?!\d)/g;
const PH = '\uE000';

function protectMath(text) {
  const math = [];
  const out = text.replace(MATH_RE, (m, d1, d2, i1, i2) => {
    const display = d1 != null || d2 != null;
    math.push({ tex: (d1 ?? d2 ?? i1 ?? i2).trim(), display });
    return `${PH}${math.length - 1}${PH}`;
  });
  return { out: out.replace(/\\\$/g, '$'), math };
}

function renderTex(tex, display) {
  const k = window.katex;
  if (!k) return esc(tex);
  try {
    return k.renderToString(tex, { displayMode: display, throwOnError: false, strict: 'ignore', trust: false, output: 'html' });
  } catch {
    return `<code>${esc(tex)}</code>`;
  }
}

function restoreMath(root, math) {
  if (!math.length) return;
  const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
  const targets = [];
  while (walker.nextNode()) if (walker.currentNode.nodeValue.includes(PH)) targets.push(walker.currentNode);
  const re = new RegExp(`${PH}(\\d+)${PH}`, 'g');
  for (const node of targets) {
    const frag = document.createDocumentFragment();
    let last = 0;
    const s = node.nodeValue;
    for (const m of s.matchAll(re)) {
      if (m.index > last) frag.append(s.slice(last, m.index));
      const item = math[Number(m[1])];
      const span = document.createElement('span');
      span.innerHTML = item ? renderTex(item.tex, item.display) : ''; // KaTeX output (trusted, trust:false)
      frag.append(span);
      last = m.index + m[0].length;
    }
    if (last < s.length) frag.append(s.slice(last));
    node.replaceWith(frag);
  }
}

// ---------- Minimal Markdown (for AI chat answers) ----------
function inlineMd(s) {
  return s
    .replace(/`([^`]+)`/g, '<code>$1</code>')
    .replace(/\*\*([^*]+)\*\*/g, '<b>$1</b>')
    .replace(/__([^_]+)__/g, '<b>$1</b>')
    .replace(/(^|[\s(])\*([^*\n]+)\*(?=[\s).,!?:;]|$)/g, '$1<i>$2</i>');
}

function markdownToHtml(src) {
  const lines = esc(src).split('\n');
  const out = [];
  let list = null; // 'ul' | 'ol'
  let inCode = false;
  let table = null;
  const closeList = () => { if (list) { out.push(`</${list}>`); list = null; } };
  const flushTable = () => {
    if (!table) return;
    const rows = table.filter((r) => !/^\s*\|?\s*:?-{2,}/.test(r));
    const cells = (r) => r.replace(/^\s*\|/, '').replace(/\|\s*$/, '').split('|').map((c) => inlineMd(c.trim()));
    let html = '<table>';
    rows.forEach((r, i) => {
      const tag = i === 0 ? 'th' : 'td';
      html += `<tr>${cells(r).map((c) => `<${tag}>${c}</${tag}>`).join('')}</tr>`;
    });
    out.push(`${html}</table>`);
    table = null;
  };
  for (const raw of lines) {
    const line = raw.replace(/\s+$/, '');
    if (/^```/.test(line)) { closeList(); flushTable(); out.push(inCode ? '</code></pre>' : '<pre><code>'); inCode = !inCode; continue; }
    if (inCode) { out.push(`${line}\n`); continue; }
    if (/^\s*\|.*\|\s*$/.test(line)) { closeList(); (table ||= []).push(line); continue; }
    flushTable();
    let m;
    if ((m = line.match(/^\s*#{1,6}\s+(.*)$/))) { closeList(); out.push(`<h4>${inlineMd(m[1])}</h4>`); continue; }
    if ((m = line.match(/^\s*[-*•]\s+(.*)$/))) { if (list !== 'ul') { closeList(); out.push('<ul>'); list = 'ul'; } out.push(`<li>${inlineMd(m[1])}</li>`); continue; }
    if ((m = line.match(/^\s*\d+[.)]\s+(.*)$/))) { if (list !== 'ol') { closeList(); out.push('<ol>'); list = 'ol'; } out.push(`<li>${inlineMd(m[1])}</li>`); continue; }
    closeList();
    if (!line.trim()) { out.push('<br>'); continue; }
    out.push(`${inlineMd(line)}<br>`);
  }
  closeList(); flushTable();
  if (inCode) out.push('</code></pre>');
  return out.join('');
}

// ---------- Rich text renderer (HTML + KaTeX + images + tables) ----------
const richCache = new Map();

export function renderRich(text, { markdown = false } = {}) {
  if (text == null || text === '') return '';
  const key = (markdown ? 'm:' : 'h:') + text;
  const hit = richCache.get(key);
  if (hit !== undefined) return hit;
  const { out, math } = protectMath(String(text));
  const html = markdown ? markdownToHtml(out) : out.replace(/\r?\n/g, '<br>');
  const body = sanitizeHtml(html);
  restoreMath(body, math);
  const result = body.innerHTML;
  if (richCache.size > 800) richCache.delete(richCache.keys().next().value);
  richCache.set(key, result);
  return result;
}

export function imgTag(b64, alt = 'figure') {
  if (!b64) return '';
  const src = /^data:image\//.test(b64) ? b64 : `data:image/png;base64,${b64}`;
  if (!/^data:image\/(png|jpe?g|gif|webp);base64,[a-z0-9+/=\s]+$/i.test(src)) return '';
  return `<img src="${src}" alt="${esc(alt)}" loading="lazy" class="max-w-full rounded-lg bg-white">`;
}

// Bilingual picker. lang = 'hi' | 'en' | 'both'
export function pickLang(hi, en, lang) {
  hi = (hi || '').trim(); en = (en || '').trim();
  if (lang === 'hi') return hi || en;
  if (lang === 'en') return en || hi;
  return hi && en && hi !== en ? { hi, en } : hi || en;
}

export function bilingualHtml(hi, en, lang, cls = '') {
  const v = pickLang(hi, en, lang);
  if (typeof v === 'string') return `<div class="rich ${cls}">${renderRich(v)}</div>`;
  return `<div class="rich ${cls}">${renderRich(v.hi)}</div><div class="rich ${cls} mt-2 border-t border-dashed border-slate-300 pt-2 text-slate-600 dark:border-slate-700 dark:text-slate-300">${renderRich(v.en)}</div>`;
}

export function plainText(s, max = 140) {
  const t = String(s || '').replace(/<[^>]+>/g, ' ').replace(/\$+/g, '').replace(/\s+/g, ' ').trim();
  return t.length > max ? `${t.slice(0, max)}…` : t;
}

// ---------- Native Android bridge helpers ----------
export const native = () => (window.AndroidBridge && typeof window.AndroidBridge.saveFile === 'function' ? window.AndroidBridge : null);

export async function copyText(text) {
  const nb = native();
  if (nb && typeof nb.copyText === 'function') { nb.copyText(text); return true; }
  try {
    if (navigator.clipboard && window.isSecureContext) { await navigator.clipboard.writeText(text); return true; }
  } catch { /* fall through */ }
  const ta = document.createElement('textarea');
  ta.value = text;
  ta.setAttribute('readonly', '');
  ta.style.cssText = 'position:fixed;top:0;left:0;opacity:0;';
  document.body.appendChild(ta);
  ta.select();
  ta.setSelectionRange(0, text.length);
  let ok = false;
  try { ok = document.execCommand('copy'); } catch { ok = false; }
  ta.remove();
  return ok;
}

export function downloadText(filename, text, mime = 'application/json') {
  const nb = native();
  if (nb) {
    const res = nb.saveFile(filename, text, mime);
    return res || 'Downloads';
  }
  const blob = new Blob([text], { type: mime });
  const a = document.createElement('a');
  a.href = URL.createObjectURL(blob);
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  setTimeout(() => { URL.revokeObjectURL(a.href); a.remove(); }, 2000);
  return 'Downloads';
}

export function readFile(file, as = 'text') {
  return new Promise((resolve, reject) => {
    const r = new FileReader();
    r.onload = () => resolve(r.result);
    r.onerror = () => reject(r.error);
    if (as === 'buffer') r.readAsArrayBuffer(file);
    else if (as === 'dataurl') r.readAsDataURL(file);
    else r.readAsText(file, 'utf-8');
  });
}

// Extract the first JSON object/array from a model response (handles ```json fences and chatter).
export function extractJson(text) {
  if (text == null) throw new Error('Empty response');
  let s = String(text).trim();
  const fence = s.match(/```(?:json)?\s*([\s\S]*?)```/i);
  if (fence) s = fence[1].trim();
  try { return JSON.parse(s); } catch { /* try to locate */ }
  const start = s.search(/[{[]/);
  if (start < 0) throw new Error('No JSON found in response');
  const open = s[start];
  const close = open === '{' ? '}' : ']';
  let depth = 0, inStr = false, escNext = false;
  for (let i = start; i < s.length; i++) {
    const c = s[i];
    if (inStr) {
      if (escNext) escNext = false;
      else if (c === '\\') escNext = true;
      else if (c === '"') inStr = false;
      continue;
    }
    if (c === '"') inStr = true;
    else if (c === open) depth++;
    else if (c === close && --depth === 0) {
      const chunk = s.slice(start, i + 1);
      try { return JSON.parse(chunk); } catch {
        return JSON.parse(chunk.replace(/,\s*([}\]])/g, '$1')); // trailing commas
      }
    }
  }
  throw new Error('Incomplete JSON in response (response may have been cut off)');
}
