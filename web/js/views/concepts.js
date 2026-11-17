// "कॉन्सेप्ट" tab: subject → topic → concept cards, search, import, AI concept notes, focus mode entry.
import { db } from '../db.js';
import {
  QSRC, sourceSets, buildTree, listConceptSets, saveConceptSet, replaceConceptSet, deleteConceptSet, progressMap, setProgress,
  isConceptData, expandConcepts, conceptPlainText, conceptBundle, countConcepts, externalConceptPrompt, conceptSubjectKey, subjectIcon,
} from '../concepts.js';
import { isConfigured } from '../ai.js';
import { icon, toast, modal, confirmDialog, emptyState } from '../ui.js';
import { esc, renderRich, imgTag, readFile, extractJson, copyText, downloadText, debounce } from '../util.js';
import { diagramHtml } from '../diagrams.js';
import { DIAGRAM_TYPES } from '../diagram-spec.js';

// ---------- card rendering (shared with the focus view) ----------
function tableHtml(rows) {
  if (!rows?.length) return '';
  const [head, ...body] = rows;
  return `<div class="my-2 overflow-x-auto"><table class="ctable"><thead><tr>${head.map((c) => `<th>${renderRich(c)}</th>`).join('')}</tr></thead><tbody>${body.map((r) => `<tr>${r.map((c) => `<td>${renderRich(c)}</td>`).join('')}</tr>`).join('')}</tbody></table></div>`;
}

export function conceptBody(c) {
  let html = '';
  let list = [];
  const flush = () => { if (list.length) { html += `<ul class="cpoints">${list.join('')}</ul>`; list = []; } };
  for (const it of c.items || []) {
    if (it.type === 'point') { list.push(`<li>${renderRich(it.text)}</li>`); continue; }
    flush();
    if (it.type === 'tip') html += `<div class="cbox cbox-tip"><b>⚠️ परीक्षा टिप:</b> ${renderRich(it.text)}</div>`;
    else if (it.type === 'trick') html += `<div class="cbox cbox-trick"><b>💡 ट्रिक:</b> ${renderRich(it.text)}</div>`;
    else if (it.type === 'table') html += tableHtml(it.rows);
    else if (it.type === 'q') html += `<div class="cbox cbox-q"><b>❓ प्रश्न:</b> <div class="rich">${renderRich(it.text)}</div>${it.table ? `<div class="rich mt-1 overflow-x-auto">${renderRich(it.table)}</div>` : ''}${imgTag(it.image)}</div>`;
    else if (it.type === 'ans') html += `<div class="cbox cbox-ans"><b>✅ उत्तर:</b> ${renderRich(it.text)}${imgTag(it.image)}</div>`;
    else if (it.type === 'html') html += `<div class="rich mt-2"><b class="text-brand-700 dark:text-brand-300">📝 हल / व्याख्या:</b><br>${renderRich(it.text)}</div>`;
    else if (DIAGRAM_TYPES.includes(it.type)) html += diagramHtml(it);
  }
  flush();
  return html;
}

export function conceptCard(c, prog, { open = false, crumb = '' } = {}) {
  const p = prog?.get(c.id) || {};
  return `<details class="ccard ${p.learned ? 'ccard-done' : ''}" data-cid="${esc(c.id)}" ${open ? 'open' : ''}>
    <summary class="flex cursor-pointer items-start gap-2">
      <span class="mt-0.5 shrink-0 text-sm">${p.learned ? '✅' : '🔹'}</span>
      <span class="min-w-0 flex-1"><span class="block font-semibold leading-snug">${esc(c.title)}</span>${crumb ? `<span class="block text-[11px] text-slate-500">${esc(crumb)}</span>` : ''}</span>
      ${p.star ? '<span class="shrink-0">⭐</span>' : ''}
    </summary>
    <div class="cbody mt-2" data-body></div>
    <div class="mt-2 flex flex-wrap items-center gap-2 border-t border-slate-100 pt-2 text-xs dark:border-slate-800">
      <button class="btn-outline btn-sm" data-learn="${esc(c.id)}">${p.learned ? '↩ फिर से पढ़ें' : '✓ याद हो गया'}</button>
      <button class="btn-ghost btn-sm" data-star="${esc(c.id)}">${p.star ? '★ स्टार हटाएँ' : '☆ स्टार'}</button>
      ${c.ref ? `<a class="btn-ghost btn-sm" href="#/solutions/study?set=${encodeURIComponent(c.ref.setId)}&q=${c.ref.q_no}">प्रश्न देखें ↗</a>` : ''}
      <span class="ml-auto truncate text-[10px] text-slate-400">${esc(c.ref ? `${c.ref.setTitle} • Q${c.ref.q_no}` : c.setTitle || '')}</span>
    </div>
  </details>`;
}

// Fill card bodies only when opened (thousands of cards stay light).
export function wireCards(root, findConcept, prog, onChange = () => {}) {
  root.addEventListener('toggle', (e) => {
    const d = e.target;
    if (!(d instanceof HTMLDetailsElement) || !d.dataset.cid || !d.open) return;
    const b = d.querySelector('[data-body]');
    if (b && !b.dataset.filled) { const c = findConcept(d.dataset.cid); b.innerHTML = c ? conceptBody(c) : ''; b.dataset.filled = '1'; }
  }, true);
  root.querySelectorAll('details[data-cid][open]').forEach((d) => d.dispatchEvent(new Event('toggle')));
  root.addEventListener('click', async (e) => {
    const l = e.target.closest('[data-learn]');
    const s = e.target.closest('[data-star]');
    if (!l && !s) return;
    e.preventDefault();
    const id = (l || s).dataset.learn || (l || s).dataset.star;
    const cur = prog.get(id) || {};
    const row = await setProgress(id, l ? { learned: !cur.learned } : { star: !cur.star });
    prog.set(id, row);
    const card = root.querySelector(`details[data-cid="${CSS.escape(id)}"]`);
    if (card) {
      const c = findConcept(id);
      const open = card.open;
      const crumb = card.querySelector('summary .text-\\[11px\\]')?.textContent || '';
      const tmp = document.createElement('div');
      tmp.innerHTML = conceptCard(c, prog, { open, crumb });
      const fresh = tmp.firstElementChild;
      card.replaceWith(fresh);
      if (open) { const b = fresh.querySelector('[data-body]'); b.innerHTML = conceptBody(c); b.dataset.filled = '1'; }
    }
    if (l) toast(row.learned ? '✓ याद हो गया — आगे बढ़ें' : 'फिर से पढ़ने की सूची में', 'success', 1400);
    onChange();
  });
}

const learnedOf = (concepts, prog) => concepts.filter((c) => prog.get(c.id)?.learned).length;
const bar = (done, total) => `<div class="bar mt-1"><span class="bg-emerald-500" style="width:${total ? Math.round((done / total) * 100) : 0}%"></span></div>`;

// ---------- import helpers ----------
async function importTexts(items) {
  let sets = 0, concepts = 0;
  for (const it of items) {
    let data;
    try { data = JSON.parse(it.text); } catch {
      try { data = extractJson(it.text); } catch (e) { throw new Error(`${it.name}: मान्य JSON नहीं है — ${e.message}`); }
    }
    if (!isConceptData(data)) throw new Error(`${it.name}: यह कॉन्सेप्ट फ़ाइल नहीं है (प्रश्न-पत्र हो तो इम्पोर्ट टैब में खोलें)`);
    for (const { set, count } of expandConcepts(data)) { await saveConceptSet(set); sets++; concepts += count; }
  }
  return { sets, concepts };
}

export async function importConceptTexts(items) { return importTexts(items); }

async function addBuiltIn(file) {
  const res = await fetch(`./concepts/${file}`);
  if (!res.ok) throw new Error(`फ़ाइल नहीं मिली (${res.status})`);
  const data = await res.json();
  let n = 0;
  for (const { set, count } of expandConcepts(data)) { await replaceConceptSet({ ...set, origin: 'builtin' }); n += count; }
  return n;
}

// ---------- AI dialog ----------
async function aiDialog({ settings, navigate }) {
  const cfgOk = isConfigured(settings);
  const papers = (await db.all('sets')).filter((s) => s.kind !== 'practice').sort((a, b) => (a.meta.title || '').localeCompare(b.meta.title || '', 'hi', { numeric: true }));
  const files = [];
  let src = 'doc';
  const md = modal({
    title: '🤖 AI से कॉन्सेप्ट नोट्स बनवाएँ', size: 'lg',
    body: `<div class="space-y-3 text-sm">
      <div class="text-xs text-slate-500">किसी किताब/नोट्स/PDF या आपके प्रश्न-पत्रों के हल से AI विषयवार → टॉपिकवार → कॉन्सेप्टवार नोट्स बनाएगा। काम बैकग्राउंड में चलेगा — ऐप में कहीं भी जाएँ।</div>
      ${cfgOk ? '' : '<div class="rounded-xl border border-amber-300 bg-amber-50 p-2 text-xs text-amber-900 dark:border-amber-800 dark:bg-amber-950/30 dark:text-amber-200">AI सेटिंग नहीं है। <a class="font-semibold underline" href="#/settings">सेटिंग्स</a> में API key डालें — या नीचे "बाहरी AI प्रॉम्प्ट" कॉपी करके ChatGPT/Gemini में PDF के साथ भेजें और उसका जवाब यहाँ इम्पोर्ट करें।</div>'}
      <div class="grid grid-cols-2 gap-2"><button class="tab tab-on" data-src="doc">📄 डॉक्यूमेंट से</button><button class="tab tab-off" data-src="qs">📚 मेरे प्रश्न-पत्रों से</button></div>
      <div data-doc>
        <label class="btn-outline w-full cursor-pointer">📎 फ़ाइल चुनें (PDF, फ़ोटो, .txt) — एक या अधिक<input type="file" multiple accept="application/pdf,.pdf,image/*,.txt,.md,.html,.htm,text/plain" class="hidden" data-files></label>
        <div class="mt-1 space-y-1" data-flist></div>
        <label class="label mt-2">या टेक्स्ट पेस्ट करें</label><textarea class="input h-24 text-sm" data-paste placeholder="नोट्स / अध्याय का टेक्स्ट…"></textarea>
        <label class="mt-2 flex items-center gap-2 text-xs"><input type="checkbox" class="h-4 w-4" data-img checked> PDF पेज की फ़ोटो भी भेजें (स्कैन/पुराने फ़ॉन्ट वाली PDF के लिए ज़रूरी)</label>
      </div>
      <div data-qs class="hidden">
        <div class="mb-1 flex gap-2"><button class="btn-outline btn-sm" data-qall>सभी</button><button class="btn-outline btn-sm" data-qnone>कोई नहीं</button></div>
        <div class="max-h-48 space-y-1 overflow-y-auto rounded-xl border border-slate-200 p-2 dark:border-slate-700">${papers.map((s) => `<label class="flex items-center gap-2 text-sm"><input type="checkbox" class="h-4 w-4" data-paper="${esc(s.id)}"><span class="min-w-0 flex-1 truncate">${esc(s.meta.title)}</span><span class="text-xs text-slate-400">${s.questions.length}</span></label>`).join('') || '<div class="text-xs text-slate-500">कोई प्रश्न-पत्र नहीं</div>'}</div>
        <div class="mt-1 text-[11px] text-slate-500">ऑफ़लाइन "प्रश्न-पत्रों के हल से" कॉन्सेप्ट पहले से कॉन्सेप्ट टैब में हैं; AI उन्हीं हलों से साफ़-सुथरे, विषयवार नोट्स बनाएगा।</div>
      </div>
      <div class="grid grid-cols-2 gap-2">
        <div class="col-span-2"><label class="label">नाम</label><input class="input" data-title placeholder="जैसे: बाल विकास नोट्स"></div>
        <div><label class="label">विषय (वैकल्पिक)</label><input class="input" data-subject placeholder="जैसे: हिन्दी"></div>
        <div><label class="label">भाषा</label><select class="input" data-lang><option value="hi">हिन्दी</option><option value="both">हिन्दी + English शब्द</option><option value="en">English</option></select></div>
        <div class="col-span-2"><label class="label">विस्तार</label><select class="input" data-detail><option value="full">विस्तृत — हर परीक्षा-उपयोगी तथ्य</option><option value="short">संक्षिप्त — केवल सबसे ज़रूरी (वन-लाइनर)</option></select></div>
        <div class="col-span-2"><label class="label">अपने निर्देश (वैकल्पिक)</label><textarea class="input h-16 text-sm" data-custom placeholder="जैसे: हर कॉन्सेप्ट में एक याद रखने की ट्रिक दें, तालिकाएँ ज़्यादा बनाएँ"></textarea></div>
      </div>
    </div>`,
    footer: '<button class="btn-outline" data-ext>📋 बाहरी AI प्रॉम्प्ट</button><button class="btn-success" data-go>🤖 बनवाना शुरू करें</button>',
  });
  const el = md.el;
  const drawFiles = () => {
    el.querySelector('[data-flist]').innerHTML = files.map((f, i) => `<div class="flex items-center gap-2 rounded-lg bg-slate-100 p-2 text-xs dark:bg-slate-800">
      <span class="min-w-0 flex-1 truncate">📄 ${esc(f.file.name)}</span>
      ${/pdf/i.test(f.file.type) || /\.pdf$/i.test(f.file.name) ? `<input class="input !w-28 !py-1 text-xs" data-pg="${i}" value="${esc(f.pages)}" placeholder="पेज: 1-20">` : ''}
      <button class="text-rose-600" data-frm="${i}">✕</button></div>`).join('');
  };
  el.querySelector('[data-files]').onchange = (e) => {
    for (const f of e.target.files || []) files.push({ file: f, pages: '' });
    e.target.value = '';
    drawFiles();
    const t = el.querySelector('[data-title]');
    if (!t.value && files[0]) t.value = files[0].file.name.replace(/\.[^.]+$/, '');
  };
  el.addEventListener('input', (e) => { if (e.target.dataset.pg) files[Number(e.target.dataset.pg)].pages = e.target.value; });
  const opts = () => ({
    title: el.querySelector('[data-title]').value.trim(), subject: el.querySelector('[data-subject]').value.trim(),
    lang: el.querySelector('[data-lang]').value, detail: el.querySelector('[data-detail]').value, custom: el.querySelector('[data-custom]').value.trim(),
  });
  el.addEventListener('click', async (e) => {
    const t = (s) => e.target.closest(s);
    let b;
    if ((b = t('[data-src]'))) {
      src = b.dataset.src;
      el.querySelectorAll('[data-src]').forEach((x) => { x.classList.toggle('tab-on', x.dataset.src === src); x.classList.toggle('tab-off', x.dataset.src !== src); });
      el.querySelector('[data-doc]').classList.toggle('hidden', src !== 'doc');
      el.querySelector('[data-qs]').classList.toggle('hidden', src !== 'qs');
      return;
    }
    if ((b = t('[data-frm]'))) { files.splice(Number(b.dataset.frm), 1); drawFiles(); return; }
    if (t('[data-qall]') || t('[data-qnone]')) { const on = !!t('[data-qall]'); el.querySelectorAll('[data-paper]').forEach((c) => { c.checked = on; }); return; }
    if (t('[data-ext]')) {
      const ok = await copyText(externalConceptPrompt(opts()));
      toast(ok ? 'प्रॉम्प्ट कॉपी हुआ — ChatGPT/Gemini में PDF के साथ पेस्ट करें, जवाब यहाँ इम्पोर्ट करें' : 'कॉपी नहीं हो सका', ok ? 'success' : 'error', 5000);
      return;
    }
    if (!t('[data-go]')) return;
    if (!cfgOk) { toast('पहले सेटिंग्स में AI (API key) सेट करें — या बाहरी AI प्रॉम्प्ट इस्तेमाल करें', 'warn', 4500); return; }
    const o = opts();
    const pasted = el.querySelector('[data-paste]').value;
    const fromSets = src === 'qs' ? [...el.querySelectorAll('[data-paper]:checked')].map((c) => c.dataset.paper) : [];
    if (src === 'doc' && !files.length && !pasted.trim()) { toast('कोई फ़ाइल या टेक्स्ट दें', 'warn'); return; }
    if (src === 'qs' && !fromSets.length) { toast('कम से कम एक प्रश्न-पत्र चुनें', 'warn'); return; }
    const { createJob } = await import('../jobs.js');
    const job = await createJob({
      kind: 'concepts', title: o.title || (src === 'qs' ? 'प्रश्न-पत्रों से कॉन्सेप्ट' : 'AI कॉन्सेप्ट नोट्स'),
      sources: src === 'doc' ? files : [], pasted: src === 'doc' ? pasted : '',
      params: { ...o, fromSets, withImages: src === 'doc' && el.querySelector('[data-img]').checked },
    });
    md.close();
    toast('काम शुरू — बैकग्राउंड में चलेगा। पूरा होने पर सेव करें।', 'success', 3500);
    navigate(`job/${job.id}`);
  });
}

// ---------- views ----------
export async function render({ root, params, query, settings, navigate, setHeader }) {
  const lang = settings.lang || 'both';
  const src = query.src || 'all';
  if (params[0] === 's' && params[1]) return renderSubject({ root, key: params[1], src, lang, navigate, setHeader });

  setHeader({ title: 'कॉन्सेप्ट / Concepts', sub: 'विषयवार → टॉपिकवार → कॉन्सेप्टवार रिवीज़न' });
  const [files, prog] = await Promise.all([listConceptSets(), progressMap()]);
  const sets = await sourceSets(src, lang);
  const tree = buildTree(sets);
  const all = tree.flatMap((s) => s.topics.flatMap((t) => t.concepts.map((c) => ({ c, s, t }))));
  const byId = new Map(all.map((x) => [x.c.id, x.c]));
  const total = all.length, done = all.filter((x) => prog.get(x.c.id)?.learned).length;
  const stars = all.filter((x) => prog.get(x.c.id)?.star).length;
  let builtIn = [];
  try { builtIn = await (await fetch('./concepts/index.json')).json(); } catch { /* offline file missing */ }
  const have = new Set(files.map((f) => f.id));
  const stored = new Map(files.map((f) => [f.id, f]));
  const outdated = (b) => have.has(b.id) && b.version && stored.get(b.id)?.version !== b.version;
  const needs = (b) => !have.has(b.id) || outdated(b);
  const qset = sets.find((s) => s.id === QSRC);
  const qCount = qset ? countConcepts(qset) : 0;

  const srcChip = (id, label, n) => `<a href="#/concepts${id === 'all' ? '' : `?src=${encodeURIComponent(id)}`}" class="tab !px-3 !py-1.5 text-xs ${src === id ? 'tab-on' : 'tab-off'}">${esc(label)}${n != null ? ` <span class="opacity-70">${n}</span>` : ''}</a>`;
  root.innerHTML = `
    <div class="mb-3 flex gap-2 overflow-x-auto pb-1 no-scrollbar">${srcChip('all', '🗂 सभी')}${files.map((f) => srcChip(f.id, f.title.replace(/^UP SUPER TET\s*/i, '').slice(0, 28), countConcepts(f))).join('')}${srcChip(QSRC, '📝 प्रश्न-पत्रों के हल से', src === QSRC || src === 'all' ? qCount : null)}</div>

    <div class="card mb-3 !p-3">
      <div class="relative"><input class="input !pl-9" placeholder="कॉन्सेप्ट खोजें — जैसे: पियाजे, RTE धारा 17, संधि…" data-search><span class="pointer-events-none absolute left-3 top-2.5 text-slate-400">🔍</span></div>
      <div class="mt-3 grid grid-cols-3 gap-2 text-center">
        <div class="stat"><div class="stat-v">${total}</div><div class="stat-l">कॉन्सेप्ट</div></div>
        <div class="stat"><div class="stat-v text-emerald-600">${done}</div><div class="stat-l">याद हुए</div></div>
        <div class="stat"><div class="stat-v text-amber-500">${stars}</div><div class="stat-l">⭐ स्टार</div></div>
      </div>
      ${bar(done, total)}
      ${total ? `<div class="mt-3 grid grid-cols-2 gap-2">
        <a class="btn-primary" href="#/focus?src=${encodeURIComponent(src)}&mode=unlearned">🎯 फोकस मोड</a>
        <a class="btn-outline" href="#/focus?src=${encodeURIComponent(src)}&mode=star">⭐ स्टार वाले</a></div>` : ''}
    </div>
    <div data-results></div>

    <div data-subjects>
      ${tree.length ? `<h2 class="mb-2 text-xs font-bold uppercase tracking-wide text-slate-500">विषय चुनें</h2>
      <div class="mb-3 grid grid-cols-2 gap-2">${tree.map((s) => {
        const cs = s.topics.flatMap((t) => t.concepts);
        const d = learnedOf(cs, prog);
        return `<a href="#/concepts/s/${encodeURIComponent(s.key)}${src === 'all' ? '' : `?src=${encodeURIComponent(src)}`}" class="card !p-3">
          <div class="flex items-center gap-2"><span class="text-2xl">${s.icon}</span><span class="min-w-0 flex-1 text-sm font-bold leading-tight">${esc(s.label)}</span></div>
          <div class="mt-1 text-[11px] text-slate-500">${s.topics.length} टॉपिक • ${cs.length} कॉन्सेप्ट • ✅ ${d}</div>${bar(d, cs.length)}</a>`;
      }).join('')}</div>` : emptyState('अभी कोई कॉन्सेप्ट नहीं', 'नीचे से Selection Book के कॉन्सेप्ट जोड़ें, कोई कॉन्सेप्ट फ़ाइल इम्पोर्ट करें या AI से बनवाएँ। प्रश्न-पत्र इम्पोर्ट करने पर उनके हल से कॉन्सेप्ट अपने-आप यहाँ आ जाते हैं।')}
    </div>

    <section class="card mb-3">
      <div class="mb-2 font-bold">📥 कॉन्सेप्ट जोड़ें</div>
      ${builtIn.length ? `<div class="mb-2 text-xs text-slate-500">ऐप के साथ आए नोट्स (एक टैप में जोड़ें, ऑफ़लाइन):</div>
      <div class="mb-3 space-y-2">${builtIn.map((b) => `<div class="flex items-center gap-2 rounded-xl border border-slate-200 p-2 dark:border-slate-700">
        <span class="text-xl">📗</span><span class="min-w-0 flex-1"><span class="block text-sm font-semibold leading-tight">${esc(b.title)}</span><span class="block text-[11px] text-slate-500">${b.topics} टॉपिक • ${b.concepts} कॉन्सेप्ट</span></span>
        ${outdated(b) ? `<button class="btn-success btn-sm" data-builtin="${esc(b.file)}">🔄 नया संस्करण</button>` : have.has(b.id) ? '<span class="chip bg-emerald-100 text-emerald-700">✓ जुड़ा</span>' : `<button class="btn-primary btn-sm" data-builtin="${esc(b.file)}">जोड़ें</button>`}</div>`).join('')}
        ${builtIn.some(needs) ? `<button class="btn-outline btn-sm w-full" data-builtin-all>${builtIn.some(outdated) ? 'सभी जोड़ें / अपडेट करें' : 'सभी जोड़ें'}</button>` : ''}</div>` : ''}
      <div class="grid grid-cols-2 gap-2">
        <label class="btn-outline cursor-pointer">${icon.upload('h-4 w-4')} फ़ाइल इम्पोर्ट<input type="file" accept=".json,application/json,text/plain" multiple class="hidden" data-cfiles></label>
        <button class="btn-outline" data-cpaste>📋 JSON पेस्ट</button>
        <button class="btn-success col-span-2" data-ai>🤖 AI से कॉन्सेप्ट बनवाएँ (PDF / नोट्स / प्रश्न-पत्र)</button>
      </div>
    </section>

    ${files.length ? `<section class="card mb-3"><div class="mb-2 font-bold">📚 मेरे कॉन्सेप्ट सेट</div><div class="space-y-2">${files.map((f) => `<div class="rounded-xl border border-slate-200 p-2 dark:border-slate-700">
      <div class="text-sm font-semibold leading-tight">${esc(f.title)}</div>
      <div class="text-[11px] text-slate-500">${countConcepts(f)} कॉन्सेप्ट • ${f.subjects.map((s) => esc(s.subject)).join(', ')} • ${f.origin === 'ai' ? '🤖 AI' : f.origin === 'builtin' ? '📗 बिल्ट-इन' : '📥 इम्पोर्ट'}</div>
      <div class="mt-2 flex flex-wrap gap-2"><a class="btn-outline btn-sm" href="#/concepts?src=${encodeURIComponent(f.id)}">पढ़ें</a><button class="btn-outline btn-sm" data-cshare="${esc(f.id)}">📤 शेयर</button><button class="btn-outline btn-sm" data-cdl="${esc(f.id)}">${icon.download('h-4 w-4')} JSON</button><button class="btn-ghost btn-sm text-rose-600" data-cdel="${esc(f.id)}">🗑 हटाएँ</button></div></div>`).join('')}</div></section>` : ''}`;

  // search
  const results = root.querySelector('[data-results]');
  const subjBox = root.querySelector('[data-subjects]');
  const index = all.map((x) => ({ ...x, hay: conceptPlainText(x.c).toLowerCase() }));
  const doSearch = debounce((q) => {
    q = q.trim().toLowerCase();
    if (q.length < 2) { results.innerHTML = ''; subjBox.classList.remove('hidden'); return; }
    const words = q.split(/\s+/);
    const hits = index.filter((x) => words.every((w) => x.hay.includes(w)));
    subjBox.classList.add('hidden');
    results.innerHTML = `<div class="mb-2 flex items-center justify-between"><h2 class="text-xs font-bold uppercase tracking-wide text-slate-500">${hits.length} परिणाम</h2>${hits.length ? `<a class="btn-primary btn-sm" href="#/focus?src=${encodeURIComponent(src)}&q=${encodeURIComponent(q)}">🎯 इनका फोकस मोड</a>` : ''}</div>
      <div class="space-y-2">${hits.slice(0, 60).map((x) => conceptCard(x.c, prog, { crumb: `${x.s.label} › ${x.t.name}` })).join('') || '<div class="card text-sm text-slate-500">कुछ नहीं मिला</div>'}</div>
      ${hits.length > 60 ? `<div class="mt-2 text-center text-xs text-slate-500">पहले 60 दिखाए — और सटीक खोजें</div>` : ''}`;
  }, 250);
  root.querySelector('[data-search]').addEventListener('input', (e) => doSearch(e.target.value));
  wireCards(results, (id) => byId.get(id), prog);

  const reload = () => navigate(`concepts${src === 'all' ? '' : `?src=${encodeURIComponent(src)}`}`, { replace: true });
  root.addEventListener('change', async (e) => {
    if (!e.target.matches('[data-cfiles]')) return;
    const list = Array.from(e.target.files || []);
    e.target.value = '';
    try {
      const items = [];
      for (const f of list) items.push({ name: f.name, text: await readFile(f) });
      const r = await importTexts(items);
      toast(`${r.sets} कॉन्सेप्ट सेट जुड़े (${r.concepts} कॉन्सेप्ट)`, 'success', 3000);
      reload();
    } catch (err) { toast(err.message, 'error', 5000); }
  });
  root.addEventListener('click', async (e) => {
    const t = (s) => e.target.closest(s);
    let b;
    if ((b = t('[data-builtin]'))) {
      b.disabled = true;
      try { const n = await addBuiltIn(b.dataset.builtin); toast(`${n} कॉन्सेप्ट जुड़े`, 'success'); reload(); } catch (err) { b.disabled = false; toast(err.message, 'error', 5000); }
      return;
    }
    if ((b = t('[data-builtin-all]'))) {
      b.disabled = true;
      let n = 0;
      try { for (const x of builtIn) if (needs(x)) n += await addBuiltIn(x.file); toast(`${n} कॉन्सेप्ट जुड़े`, 'success'); reload(); } catch (err) { b.disabled = false; toast(err.message, 'error', 5000); }
      return;
    }
    if (t('[data-cpaste]')) {
      const md = modal({
        title: '📋 कॉन्सेप्ट JSON पेस्ट करें', size: 'lg',
        body: '<div class="mb-2 text-xs text-slate-500">ChatGPT/Gemini का पूरा जवाब पेस्ट करें (```json वाले हिस्से भी चलेंगे)।</div><textarea class="input h-64 font-mono text-xs" data-ptext></textarea>',
        footer: '<button class="btn-outline" data-close>Cancel</button><button class="btn-primary" data-pgo>इम्पोर्ट करें</button>',
      });
      md.el.querySelector('[data-pgo]').onclick = async () => {
        try { const r = await importTexts([{ name: 'पेस्ट', text: md.el.querySelector('[data-ptext]').value }]); md.close(); toast(`${r.concepts} कॉन्सेप्ट जुड़े`, 'success'); reload(); } catch (err) { toast(err.message, 'error', 5000); }
      };
      return;
    }
    if (t('[data-ai]')) { aiDialog({ settings, navigate }); return; }
    if ((b = t('[data-cshare]')) || (b = t('[data-cdl]'))) {
      const f = files.find((x) => x.id === (b.dataset.cshare || b.dataset.cdl));
      if (!f) return;
      const text = JSON.stringify(conceptBundle([f]));
      const name = `abhyas_concepts_${f.id.slice(0, 40)}.json`;
      if (b.dataset.cdl) { downloadText(name, text); return; }
      const { shareFile } = await import('../share.js');
      const how = await shareFile(name, text, `💡 ${f.title} — ${countConcepts(f)} कॉन्सेप्ट (Abhyas CBT)। ऐप में कॉन्सेप्ट → फ़ाइल इम्पोर्ट करें।`);
      if (how === 'saved') toast('फ़ाइल डाउनलोड हुई — इसे Telegram/WhatsApp में भेजें', 'info', 4000);
      return;
    }
    if ((b = t('[data-cdel]'))) {
      const f = files.find((x) => x.id === b.dataset.cdel);
      if (!f) return;
      const { requirePin } = await import('../pin.js');
      if (!(await requirePin(`"${esc(f.title)}" हटाने के लिए`))) return;
      if (!(await confirmDialog(`"${esc(f.title)}" (${countConcepts(f)} कॉन्सेप्ट) और इसकी प्रगति हटेगी।`, { ok: 'हटाएँ', danger: true }))) return;
      await deleteConceptSet(f.id);
      toast('हटाया गया', 'success');
      navigate('concepts', { replace: true });
    }
  });
  if (query.ai) setTimeout(() => aiDialog({ settings, navigate }), 50);
}

async function renderSubject({ root, key, src, lang, navigate, setHeader }) {
  const [sets, prog] = await Promise.all([sourceSets(src, lang), progressMap()]);
  const subj = buildTree(sets).find((s) => s.key === key);
  const back = `concepts${src === 'all' ? '' : `?src=${encodeURIComponent(src)}`}`;
  if (!subj) { setHeader({ title: 'कॉन्सेप्ट', back }); root.innerHTML = emptyState('इस विषय में कोई कॉन्सेप्ट नहीं', ''); return; }
  setHeader({ title: `${subj.icon} ${subj.label}`, sub: `${subj.topics.length} टॉपिक • ${subj.count} कॉन्सेप्ट`, back });
  const byId = new Map(subj.topics.flatMap((t) => t.concepts.map((c) => [c.id, c])));
  const cs = [...byId.values()];
  const q = (extra) => `#/focus?src=${encodeURIComponent(src)}&subj=${encodeURIComponent(key)}${extra}`;
  const draw = () => {
    const d = learnedOf(cs, prog);
    root.innerHTML = `
      <div class="card mb-3 !p-3">
        <div class="flex items-center justify-between text-sm"><b>प्रगति</b><span class="text-xs text-slate-500">✅ ${d} / ${cs.length} याद</span></div>${bar(d, cs.length)}
        <div class="mt-3 grid grid-cols-3 gap-2">
          <a class="btn-primary btn-sm" href="${q('&mode=unlearned')}">🎯 फोकस</a>
          <a class="btn-outline btn-sm" href="${q('&mode=all')}">📖 सभी</a>
          <a class="btn-outline btn-sm" href="${q('&mode=star')}">⭐ स्टार</a>
        </div>
        <label class="mt-2 flex items-center gap-2 text-xs"><input type="checkbox" class="h-4 w-4" data-openall> सभी कॉन्सेप्ट खोलकर दिखाएँ</label>
      </div>
      <div class="space-y-2" data-topics>${subj.topics.map((t, i) => {
        const td = learnedOf(t.concepts, prog);
        return `<details class="card !p-3" data-topic="${i}">
          <summary class="flex cursor-pointer items-center gap-2"><span class="min-w-0 flex-1 font-bold">${esc(t.name)}</span><span class="shrink-0 text-xs text-slate-500">✅ ${td}/${t.concepts.length}</span></summary>
          ${bar(td, t.concepts.length)}
          <div class="mt-2 flex gap-2"><a class="btn-outline btn-sm" href="${q(`&topic=${encodeURIComponent(t.name)}&mode=all`)}">🎯 इस टॉपिक का फोकस मोड</a></div>
          <div class="mt-2 space-y-2" data-tlist></div>
        </details>`;
      }).join('')}</div>`;
  };
  draw();
  const fill = (d, open = false) => {
    const box = d.querySelector('[data-tlist]');
    if (box.dataset.filled) return;
    const t = subj.topics[Number(d.dataset.topic)];
    box.innerHTML = t.concepts.map((c) => conceptCard(c, prog, { open })).join('');
    box.dataset.filled = '1';
    if (open) box.querySelectorAll('details[data-cid]').forEach((x) => { const b = x.querySelector('[data-body]'); b.innerHTML = conceptBody(byId.get(x.dataset.cid)); b.dataset.filled = '1'; });
  };
  root.addEventListener('toggle', (e) => { const d = e.target; if (d instanceof HTMLDetailsElement && d.dataset.topic && d.open) fill(d, root.querySelector('[data-openall]')?.checked); }, true);
  root.addEventListener('change', (e) => {
    if (!e.target.matches('[data-openall]')) return;
    const on = e.target.checked;
    root.querySelectorAll('details[data-topic]').forEach((d) => { const box = d.querySelector('[data-tlist]'); box.dataset.filled = ''; d.open = on; if (on) fill(d, true); });
  });
  wireCards(root, (id) => byId.get(id), prog);
}

export { conceptSubjectKey, subjectIcon };
