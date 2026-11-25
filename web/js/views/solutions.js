import { db, saveSettings } from '../db.js';
import { getSet, bookmarkSet, toggleBookmark } from '../store.js';
import { evaluate } from '../analytics.js';
import { solutionCard } from '../components.js';
import { openDoubtChat } from '../chat.js';
import { icon, toast, modal, pickFile } from '../ui.js';
import { esc, imageFileToDataUrl } from '../util.js';
import { displayQuestion, dispLetter } from '../optorder.js';

const FILTERS = [['all', 'All'], ['correct', 'Correct'], ['wrong', 'Incorrect'], ['skipped', 'Unattempted'], ['bookmarked', 'Bookmarked']];
const CHUNK = 12;

export async function render({ root, params, query, settings, setHeader }) {
  const study = params[0] === 'study';
  let attempt = null, set;
  if (study) set = await getSet(query.set);
  else {
    attempt = await db.get('attempts', params[0]);
    if (attempt) set = await getSet(attempt.setId);
  }
  if (!set) { root.innerHTML = '<div class="card">डेटा नहीं मिला</div>'; return; }
  const result = attempt ? (attempt.result || evaluate(set, attempt)) : null;
  const items = new Map((result?.items || []).map((i) => [i.q_no, i]));
  const qNos = attempt ? attempt.qNos : set.questions.map((q) => q.q_no);
  const qmap = new Map(set.questions.map((q) => [q.q_no, q]));
  const qs = qNos.map((n) => qmap.get(n)).filter(Boolean);
  const bookmarks = await bookmarkSet(set.id);
  // Show every question with the same (shuffled) option letters the student saw in this attempt.
  const order = (q) => attempt?.optOrder?.[q.q_no] || null;
  const card = (q) => {
    const it = items.get(q.q_no) || null;
    return solutionCard(set, displayQuestion(q, order(q)), lang, it && { ...it, sel: dispLetter(q, order(q), it.sel) }, { bookmarked: bookmarks.has(q.q_no), editable: true });
  };
  let lang = settings.lang || 'both';
  let filter = 'all';
  let section = '';
  setHeader({
    title: study ? 'प्रश्न + हल / Study' : 'सॉल्यूशन / Solutions', sub: attempt ? attempt.title : set.meta.title,
    back: study ? `set/${set.id}` : `result/${attempt.id}`,
    actions: `<button class="btn-ghost btn-sm" id="sol-lang">${icon.lang('h-4 w-4')}<span>${{ hi: 'हि', en: 'En', both: 'हि+En' }[lang]}</span></button>`,
  });

  const count = (f) => qs.filter((q) => match(q, f)).length;
  function match(q, f) {
    if (f === 'all') return true;
    if (f === 'bookmarked') return bookmarks.has(q.q_no);
    return items.get(q.q_no)?.status === f;
  }
  const filters = study ? FILTERS.filter(([f]) => f === 'all' || f === 'bookmarked') : FILTERS;
  const sections = set.sections.filter((s) => qs.some((q) => q.section === s));

  root.innerHTML = `
    <div class="sticky top-14 z-20 -mx-3 mb-3 border-b border-slate-200 bg-slate-50/95 px-3 py-2 backdrop-blur dark:border-slate-800 dark:bg-slate-950/95">
      <div class="no-scrollbar flex gap-2 overflow-x-auto" data-filters></div>
      <div class="mt-2 flex gap-2">
        <select class="input !py-1.5 text-sm" data-section>
          <option value="">सभी विषय / All sections</option>
          ${sections.map((s) => `<option value="${esc(s)}">${esc(s)}</option>`).join('')}
        </select>
        <input class="input !w-24 !py-1.5 text-sm" type="number" min="1" placeholder="Q.no" data-jump>
      </div>
    </div>
    <div class="space-y-3" data-list></div>
    <div class="h-10" data-sentinel></div>`;

  const list = root.querySelector('[data-list]');
  let filtered = [];
  let shown = 0;

  const drawFilters = () => {
    root.querySelector('[data-filters]').innerHTML = filters.map(([f, l]) => `<button class="tab ${f === filter ? 'tab-on' : 'tab-off'}" data-f="${f}">${l} <span class="opacity-70">${count(f)}</span></button>`).join('');
  };
  const more = () => {
    const next = filtered.slice(shown, shown + CHUNK);
    list.insertAdjacentHTML('beforeend', next.map(card).join(''));
    shown += next.length;
  };
  const redraw = () => {
    filtered = qs.filter((q) => match(q, filter) && (!section || q.section === section));
    shown = 0;
    list.innerHTML = filtered.length ? '' : '<div class="card py-8 text-center text-sm text-slate-500">इस फ़िल्टर में कोई प्रश्न नहीं।</div>';
    more();
    drawFilters();
  };

  const jumpTo = (qn) => {
    const pos = filtered.findIndex((q) => q.q_no === qn);
    if (pos < 0) { filter = 'all'; section = ''; root.querySelector('[data-section]').value = ''; redraw(); }
    const p2 = filtered.findIndex((q) => q.q_no === qn);
    if (p2 < 0) { toast(`Q.${qn} नहीं मिला`, 'warn'); return; }
    while (shown <= p2) more();
    const el = list.querySelector(`[data-q="${qn}"]`);
    if (el) { el.scrollIntoView({ behavior: 'smooth', block: 'start' }); el.classList.add('ring-2', 'ring-brand-500'); setTimeout(() => el.classList.remove('ring-2', 'ring-brand-500'), 1800); }
  };

  redraw();
  const io = new IntersectionObserver((ents) => { if (ents.some((e) => e.isIntersecting) && shown < filtered.length) more(); }, { rootMargin: '600px' });
  io.observe(root.querySelector('[data-sentinel]'));
  if (query.q) setTimeout(() => jumpTo(Number(query.q)), 50);

  // Attach / replace / remove the figure of a question (stored inside the set, works offline).
  async function editFigure(qn) {
    const q = qmap.get(qn);
    const has = !!q.question_media?.image_base64;
    const choice = await new Promise((resolve) => {
      const md = modal({
        title: `🖼️ Q.${qn} का चित्र`, size: 'sm',
        body: `<div class="text-sm text-slate-600 dark:text-slate-300">मूल PDF में उस प्रश्न के चित्र का स्क्रीनशॉट लें, गैलरी में उसे काट (crop) लें, फिर यहाँ चुनें। चित्र इसी सेट में सेव रहेगा और टेस्ट में भी दिखेगा।</div>`,
        footer: `${has ? '<button class="btn-outline text-rose-600" data-c="remove">हटाएँ</button>' : ''}<button class="btn-primary" data-c="pick">गैलरी से चुनें</button>`,
        onClose: (v) => resolve(v || null),
      });
      md.el.querySelectorAll('[data-c]').forEach((b) => { b.onclick = () => md.close(b.dataset.c); });
    });
    if (!choice) return;
    let data = null;
    if (choice === 'pick') {
      const file = await pickFile('image/*');
      if (!file) return;
      try { data = await imageFileToDataUrl(file); } catch (err) { toast(`चित्र नहीं खुला: ${err.message}`, 'error', 4000); return; }
    }
    q.question_media = { ...(q.question_media || {}), image_base64: data };
    await db.put('sets', set);
    const el = list.querySelector(`[data-q="${qn}"]`);
    if (el) el.outerHTML = card(q);
    toast(data ? 'चित्र सेव हुआ' : 'चित्र हटाया', 'success', 1500);
  }

  root.addEventListener('click', async (e) => {
    const f = e.target.closest('[data-f]');
    if (f) { filter = f.dataset.f; redraw(); return; }
    const bm = e.target.closest('[data-bm]');
    if (bm) {
      const qn = Number(bm.dataset.bm);
      const on = await toggleBookmark(set.id, qn);
      if (on) bookmarks.add(qn); else bookmarks.delete(qn);
      bm.classList.toggle('text-amber-500', on);
      bm.innerHTML = on ? icon.bookmarkFill('h-5 w-5') : icon.bookmark('h-5 w-5');
      drawFilters();
      toast(on ? 'बुकमार्क किया' : 'बुकमार्क हटाया', 'info', 1200);
      return;
    }
    const fig = e.target.closest('[data-fig]');
    if (fig) { editFigure(Number(fig.dataset.fig)); return; }
    const ask = e.target.closest('[data-ask]');
    if (ask) {
      const qn = Number(ask.dataset.ask);
      const q = qmap.get(qn);
      openDoubtChat(set, displayQuestion(q, order(q)), { userAnswer: dispLetter(q, order(q), items.get(qn)?.sel) || null });
    }
  });
  root.querySelector('[data-section]').onchange = (e) => { section = e.target.value; redraw(); };
  root.querySelector('[data-jump]').onchange = (e) => { const n = Number(e.target.value); if (n) jumpTo(n); };
  document.getElementById('sol-lang').onclick = async () => {
    lang = { hi: 'en', en: 'both', both: 'hi' }[lang];
    document.querySelector('#sol-lang span').textContent = { hi: 'हि', en: 'En', both: 'हि+En' }[lang];
    await saveSettings({ lang });
    redraw();
  };
  return () => io.disconnect();
}
