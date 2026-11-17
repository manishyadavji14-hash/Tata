import { recordMistakes } from '../practice.js';
import { recordQStats } from '../qstats.js';
// Testbook-style CBT exam player.
import { db } from '../db.js';
import { getSet, bookmarkSet, toggleBookmark } from '../store.js';
import { evaluate, recordTopicStats } from '../analytics.js';
import { passageHtml, questionHtml, optionInner } from '../components.js';
import { icon, modal, confirmDialog, toast } from '../ui.js';
import { esc, fmtClock } from '../util.js';

const LANG_NEXT = { hi: 'en', en: 'both', both: 'hi' };
const LANG_LABEL = { hi: 'हिंदी', en: 'English', both: 'हि + En' };

export function statusOf(r) {
  if (!r || !r.visited) return 'notvisited';
  if (r.marked && r.sel) return 'ansmarked';
  if (r.marked) return 'marked';
  if (r.sel) return 'answered';
  return 'notanswered';
}

export async function render({ root, params, navigate, setLeaveGuard }) {
  const attempt = await db.get('attempts', params[0]);
  if (!attempt) { root.innerHTML = '<div class="card m-3">टेस्ट नहीं मिला</div>'; return; }
  if (attempt.state === 'submitted') { navigate(`result/${attempt.id}`, { replace: true }); return; }
  const set = await getSet(attempt.setId);
  if (!set) { root.innerHTML = '<div class="card m-3">प्रैक्टिस सेट डिलीट हो चुका है</div>'; return; }

  const qmap = new Map(set.questions.map((q) => [q.q_no, q]));
  attempt.qNos = attempt.qNos.filter((n) => qmap.has(n));
  const qs = attempt.qNos.map((n) => qmap.get(n));
  const sections = set.sections.filter((s) => qs.some((q) => q.section === s));
  for (const q of qs) if (!sections.includes(q.section)) sections.push(q.section);
  const bookmarks = await bookmarkSet(set.id);
  let idx = Math.min(attempt.current || 0, qs.length - 1);
  let lang = attempt.lang || 'both';
  let paused = true; // starts paused until first frame; set false below
  let paletteOpen = false;
  let submitting = false;
  let lastTick = performance.now();
  let lastSave = Date.now();
  const resp = (q) => (attempt.responses[q.q_no] ||= { sel: null, visited: false, marked: false, time: 0 });

  root.innerHTML = `
  <div class="flex h-[100dvh] flex-col bg-slate-50 dark:bg-slate-950">
    <div class="safe-top bg-brand-700 text-white dark:bg-slate-900">
      <div class="flex h-14 items-center gap-1 px-2">
        <button class="btn-ghost btn-sm !text-white hover:!bg-white/10" data-act="exit" aria-label="Exit">${icon.back()}</button>
        <div class="min-w-0 flex-1">
          <div class="truncate text-[13px] font-semibold opacity-90">${esc(attempt.title)}</div>
          <div class="flex items-center gap-1 whitespace-nowrap text-[11px] opacity-80" title="इस प्रश्न पर लगा समय / Time on this question">${icon.clock('h-3 w-3')} Q: <span data-qtime class="font-mono">00:00</span></div>
        </div>
        <button class="rounded-lg bg-white/15 px-2 py-1 text-[11px] font-bold" data-act="lang" aria-label="Language">${icon.lang('inline h-4 w-4')} <span data-langlbl>${LANG_LABEL[lang]}</span></button>
        <div class="rounded-lg bg-white px-2 py-1 font-mono text-sm font-bold text-brand-700 dark:bg-slate-800 dark:text-white" data-timer>--:--</div>
        <button class="btn-ghost btn-sm !px-2 !text-white hover:!bg-white/10" data-act="pause" aria-label="Pause">${icon.pause()}</button>
        <button class="btn-ghost btn-sm !px-2 !text-white hover:!bg-white/10" data-act="palette" aria-label="Question palette">${icon.grid()}</button>
      </div>
      <div class="no-scrollbar flex gap-2 overflow-x-auto bg-brand-800/40 px-2 py-2 dark:bg-slate-950/40" data-tabs></div>
    </div>

    <div class="flex items-center gap-2 border-b border-slate-200 bg-white px-3 py-2 text-sm dark:border-slate-800 dark:bg-slate-900">
      <span class="font-bold" data-qno></span>
      <span class="chip bg-emerald-100 text-emerald-700 dark:bg-emerald-900/40 dark:text-emerald-300">+${attempt.config.pos}</span>
      <span class="chip bg-rose-100 text-rose-700 dark:bg-rose-900/40 dark:text-rose-300">−${attempt.config.neg}</span>
      <span class="ml-auto text-xs text-slate-500" data-qpos></span>
      <button class="btn-ghost btn-sm !px-2" data-act="bookmark" aria-label="Bookmark"></button>
    </div>

    <div class="flex-1 overflow-y-auto px-3 py-3" data-scroll>
      <div class="mx-auto max-w-3xl" data-question></div>
    </div>

    <div class="safe-bottom border-t border-slate-200 bg-white px-2 pt-2 dark:border-slate-800 dark:bg-slate-900">
      <div class="mx-auto grid max-w-3xl grid-cols-2 gap-2">
        <button class="btn-purple !px-2 !text-xs" data-act="marknext">Mark for Review &amp; Next</button>
        <button class="btn-outline !px-2 !text-xs" data-act="clear">Clear Response</button>
      </div>
      <div class="mx-auto mt-2 grid max-w-3xl grid-cols-[auto_1fr_auto] gap-2 pb-1">
        <button class="btn-outline !px-3" data-act="prev" aria-label="Previous">${icon.back('h-4 w-4')}</button>
        <button class="btn-success" data-act="savenext">Save &amp; Next</button>
        <button class="btn-danger !px-3 !text-xs" data-act="submit">Submit Test</button>
      </div>
    </div>
  </div>

  <div class="fixed inset-0 z-40 hidden bg-black/40" data-backdrop data-drawer-open></div>
  <aside class="drawer fixed inset-y-0 right-0 z-50 flex w-[86vw] max-w-sm translate-x-full flex-col bg-white shadow-2xl dark:bg-slate-900" data-drawer>
    <div class="safe-top flex items-center gap-2 border-b border-slate-200 px-4 py-3 dark:border-slate-800">
      <div class="flex-1 font-bold">प्रश्न पैलेट / Question Palette</div>
      <button class="btn-ghost btn-sm" data-act="palette">${icon.close()}</button>
    </div>
    <div class="grid grid-cols-2 gap-2 border-b border-slate-200 p-3 text-[11px] dark:border-slate-800" data-legend></div>
    <div class="flex-1 overflow-y-auto p-3" data-palette></div>
    <div class="safe-bottom border-t border-slate-200 p-3 dark:border-slate-800"><button class="btn-danger w-full" data-act="submit">Submit Test</button></div>
  </aside>

  <div class="fixed inset-0 z-[55] hidden flex-col items-center justify-center gap-4 bg-slate-900/95 p-6 text-center text-white" data-pause>
    <div class="text-5xl">⏸️</div>
    <div class="text-xl font-bold">टेस्ट रुका हुआ है / Test Paused</div>
    <div class="text-sm opacity-80">बचा समय: <span class="font-mono" data-pause-time></span></div>
    <button class="btn-primary !px-8 !py-3 text-base" data-act="resume">${icon.play('h-5 w-5')} Resume</button>
    <button class="text-sm underline opacity-80" data-act="exit">सेव करके बाहर जाएँ / Save &amp; Exit</button>
  </div>`;

  const $ = (s) => root.querySelector(s);
  const qBox = $('[data-question]');
  const scroller = $('[data-scroll]');

  // ---------- persistence ----------
  const save = async () => {
    attempt.current = idx;
    attempt.lang = lang;
    attempt.updatedAt = Date.now();
    lastSave = Date.now();
    try { await db.put('attempts', attempt); } catch (e) { console.error(e); }
  };

  // ---------- rendering ----------
  const counts = () => {
    const c = { answered: 0, notanswered: 0, notvisited: 0, marked: 0, ansmarked: 0 };
    for (const q of qs) c[statusOf(attempt.responses[q.q_no])]++;
    return c;
  };

  const drawTabs = () => {
    const cur = qs[idx].section;
    $('[data-tabs]').innerHTML = sections.map((s) => {
      const n = qs.filter((q) => q.section === s);
      const done = n.filter((q) => attempt.responses[q.q_no]?.sel).length;
      return `<button class="tab ${s === cur ? 'border-white bg-white text-brand-700' : 'border-white/30 bg-transparent text-white'}" data-sec="${esc(s)}">${esc(s)} <span class="opacity-70">${done}/${n.length}</span></button>`;
    }).join('');
    const active = $('[data-tabs] .bg-white');
    if (active) active.scrollIntoView({ inline: 'center', block: 'nearest', behavior: 'smooth' });
  };

  const drawQuestion = () => {
    const q = qs[idx];
    const r = resp(q);
    if (!r.visited) r.visited = true;
    $('[data-qno]').textContent = `Q.${q.q_no}`;
    $('[data-qpos]').textContent = `${idx + 1} / ${qs.length}`;
    const bm = bookmarks.has(q.q_no);
    const bb = $('[data-act=bookmark]');
    bb.innerHTML = bm ? icon.bookmarkFill('h-5 w-5') : icon.bookmark('h-5 w-5');
    bb.classList.toggle('text-amber-500', bm);
    qBox.innerHTML = `
      ${passageHtml(set, q, lang)}
      <div class="mb-1 text-[11px] font-semibold uppercase tracking-wide text-slate-400">${esc(q.section)}</div>
      <div class="mb-4">${questionHtml(q, lang)}</div>
      <div class="space-y-2.5" role="radiogroup">
        ${q.options.map((o) => `
          <button class="opt ${r.sel === o.id ? 'opt-sel' : ''}" data-opt="${o.id}" role="radio" aria-checked="${r.sel === o.id}">
            <span class="opt-badge ${r.sel === o.id ? 'border-brand-600 bg-brand-600 text-white' : ''}">${o.id}</span>
            <div class="min-w-0 flex-1">${optionInner(o, lang)}</div>
          </button>`).join('')}
      </div>
      ${r.marked ? '<div class="mt-3 text-xs font-semibold text-violet-600">🔖 समीक्षा के लिए चिह्नित / Marked for review</div>' : ''}
      <div class="h-6"></div>`;
    $('[data-act=prev]').disabled = idx === 0;
    drawTabs();
    if (paletteOpen) drawPalette();
  };

  const drawPalette = () => {
    const c = counts();
    $('[data-legend]').innerHTML = `
      <div class="flex items-center gap-2"><span class="pal pal-answered !h-7 !w-7 text-xs">${c.answered}</span>Answered</div>
      <div class="flex items-center gap-2"><span class="pal pal-notanswered !h-7 !w-7 text-xs">${c.notanswered}</span>Not Answered</div>
      <div class="flex items-center gap-2"><span class="pal pal-notvisited !h-7 !w-7 text-xs">${c.notvisited}</span>Not Visited</div>
      <div class="flex items-center gap-2"><span class="pal pal-marked !h-7 !w-7 text-xs">${c.marked}</span>Marked</div>
      <div class="col-span-2 flex items-center gap-2"><span class="pal pal-ansmarked tick !h-7 !w-7 text-xs">${c.ansmarked}</span>Answered &amp; Marked for Review</div>`;
    $('[data-palette]').innerHTML = sections.map((s) => `
      <div class="mb-4">
        <div class="mb-2 text-xs font-bold uppercase tracking-wide text-slate-500">${esc(s)}</div>
        <div class="grid grid-cols-5 gap-2">${qs.map((q, i) => (q.section !== s ? '' : (() => {
          const st = statusOf(attempt.responses[q.q_no]);
          return `<button class="pal pal-${st} ${st === 'ansmarked' ? 'tick' : ''} ${i === idx ? 'pal-current' : ''}" data-go="${i}">${q.q_no}</button>`;
        })())).join('')}</div>
      </div>`).join('');
  };

  const setPalette = (open) => {
    paletteOpen = open;
    $('[data-drawer]').classList.toggle('translate-x-full', !open);
    $('[data-backdrop]').classList.toggle('hidden', !open);
    if (open) drawPalette();
  };

  const updateTimers = () => {
    const t = $('[data-timer]');
    t.textContent = fmtClock(attempt.remainingMs);
    const low = attempt.remainingMs < 5 * 60000;
    t.classList.toggle('!bg-rose-500', low);
    t.classList.toggle('!text-white', low);
    $('[data-qtime]').textContent = fmtClock(resp(qs[idx]).time);
    $('[data-pause-time]').textContent = fmtClock(attempt.remainingMs);
  };

  const setPaused = (p) => {
    paused = p;
    $('[data-pause]').classList.toggle('hidden', !p);
    $('[data-pause]').classList.toggle('flex', p);
    lastTick = performance.now();
    if (p) save();
  };

  const go = (i) => {
    if (i < 0 || i >= qs.length) return;
    idx = i;
    drawQuestion();
    scroller.scrollTop = 0;
    save();
  };

  // ---------- submit ----------
  const finish = async (auto = false) => {
    if (submitting) return;
    submitting = true;
    clearInterval(timer);
    attempt.state = 'submitted';
    attempt.submittedAt = Date.now();
    attempt.remainingMs = Math.max(0, attempt.remainingMs);
    attempt.result = evaluate(set, attempt);
    await save();
    try { await recordTopicStats(attempt.result); } catch (e) { console.error(e); }
    try { await recordMistakes(set, attempt.result); } catch (e) { console.error(e); }
    try { await recordQStats(set, attempt.result); } catch (e) { console.error(e); }
    setLeaveGuard(null);
    if (auto) toast('⏰ समय समाप्त! टेस्ट सबमिट हो गया', 'warn', 4000);
    navigate(`result/${attempt.id}`, { replace: true });
  };

  const askSubmit = async () => {
    const c = counts();
    const md = modal({
      title: 'टेस्ट सबमिट करें? / Submit test?', size: 'sm',
      body: `<div class="grid grid-cols-2 gap-2 text-sm">
        <div class="stat"><div class="stat-v text-emerald-600">${c.answered + c.ansmarked}</div><div class="stat-l">Answered</div></div>
        <div class="stat"><div class="stat-v text-rose-600">${c.notanswered}</div><div class="stat-l">Not Answered</div></div>
        <div class="stat"><div class="stat-v">${c.notvisited}</div><div class="stat-l">Not Visited</div></div>
        <div class="stat"><div class="stat-v text-violet-600">${c.marked + c.ansmarked}</div><div class="stat-l">Marked</div></div>
      </div>
      <div class="mt-3 text-center text-sm text-slate-500">बचा समय: <b class="font-mono">${fmtClock(attempt.remainingMs)}</b></div>`,
      footer: '<button class="btn-outline" data-close>वापस / Back</button><button class="btn-danger" data-yes>Submit</button>',
    });
    md.el.querySelector('[data-yes]').onclick = () => { md.close(); finish(false); };
  };

  // ---------- events ----------
  root.addEventListener('click', async (e) => {
    const opt = e.target.closest('[data-opt]');
    if (opt) {
      resp(qs[idx]).sel = opt.dataset.opt;
      drawQuestion();
      save();
      return;
    }
    const goBtn = e.target.closest('[data-go]');
    if (goBtn) { setPalette(false); go(Number(goBtn.dataset.go)); return; }
    const sec = e.target.closest('[data-sec]');
    if (sec) { const i = qs.findIndex((q) => q.section === sec.dataset.sec); if (i >= 0) go(i); return; }
    if (e.target.closest('[data-backdrop]')) { setPalette(false); return; }
    const act = e.target.closest('[data-act]')?.dataset.act;
    if (!act) return;
    const q = qs[idx];
    switch (act) {
      case 'savenext':
        if (idx < qs.length - 1) go(idx + 1);
        else { save(); setPalette(true); toast('यह अंतिम प्रश्न है — पैलेट देखें या Submit करें', 'info'); }
        break;
      case 'marknext':
        resp(q).marked = true;
        if (idx < qs.length - 1) go(idx + 1); else { drawQuestion(); save(); setPalette(true); }
        break;
      case 'clear':
        resp(q).sel = null;
        resp(q).marked = false;
        drawQuestion(); save();
        break;
      case 'prev': go(idx - 1); break;
      case 'palette': setPalette(!paletteOpen); break;
      case 'pause': setPaused(true); break;
      case 'resume': setPaused(false); break;
      case 'lang':
        lang = LANG_NEXT[lang];
        $('[data-langlbl]').textContent = LANG_LABEL[lang];
        drawQuestion(); save();
        break;
      case 'bookmark': {
        const on = await toggleBookmark(set.id, q.q_no);
        if (on) bookmarks.add(q.q_no); else bookmarks.delete(q.q_no);
        drawQuestion();
        toast(on ? 'बुकमार्क किया' : 'बुकमार्क हटाया', 'info', 1200);
        break;
      }
      case 'submit': setPalette(false); askSubmit(); break;
      case 'exit':
        if (await confirmDialog('टेस्ट रोककर बाहर जाएँ? आपकी प्रगति सेव है — होम स्क्रीन से <b>Resume</b> कर सकते हैं।', { ok: 'सेव करके बाहर', cancel: 'जारी रखें' })) {
          paused = true;
          await save();
          setLeaveGuard(null);
          navigate('home');
        }
        break;
      default:
    }
  });

  // Swipe left/right to change question (ignored inside horizontally scrollable tables/formulas).
  let sx = 0, sy = 0, st = 0, swipeOk = true;
  scroller.addEventListener('touchstart', (e) => {
    const t = e.touches[0];
    sx = t.clientX; sy = t.clientY; st = Date.now();
    swipeOk = !e.target.closest('table, .katex-display, details > div');
  }, { passive: true });
  scroller.addEventListener('touchend', (e) => {
    if (!swipeOk) return;
    const t = e.changedTouches[0];
    const dx = t.clientX - sx, dy = t.clientY - sy;
    if (Math.abs(dx) > 70 && Math.abs(dy) < 60 && Date.now() - st < 600) go(idx + (dx < 0 ? 1 : -1));
  }, { passive: true });

  const onKey = (e) => {
    if (paused || e.target.closest('input, textarea') || document.querySelector('#modal-root > div')) return;
    const k = e.key.toUpperCase();
    const map = { 1: 'A', 2: 'B', 3: 'C', 4: 'D', 5: 'E' };
    const id = /^[A-E]$/.test(k) ? k : map[k];
    if (id && qs[idx].options.some((o) => o.id === id)) { resp(qs[idx]).sel = id; drawQuestion(); save(); }
    else if (e.key === 'ArrowRight') go(idx + 1);
    else if (e.key === 'ArrowLeft') go(idx - 1);
  };
  document.addEventListener('keydown', onKey);

  const onVis = () => { if (document.hidden && !paused && !submitting) setPaused(true); };
  document.addEventListener('visibilitychange', onVis);
  const onUnload = () => { attempt.current = idx; db.put('attempts', attempt); };
  window.addEventListener('pagehide', onUnload);

  // ---------- timer ----------
  const timer = setInterval(() => {
    const now = performance.now();
    const dt = Math.min(now - lastTick, 2000); // clamp: throttled timers must not eat minutes
    lastTick = now;
    if (paused || submitting) return;
    attempt.remainingMs -= dt;
    attempt.elapsedMs += dt;
    resp(qs[idx]).time += dt;
    updateTimers();
    if (attempt.remainingMs <= 0) { attempt.remainingMs = 0; finish(true); return; }
    if (Date.now() - lastSave > 5000) save();
  }, 250);

  setLeaveGuard(async () => {
    if (submitting) return true;
    const wasPaused = paused;
    paused = true;
    const ok = await confirmDialog('टेस्ट से बाहर जाएँ? प्रगति सेव रहेगी और बाद में Resume कर सकते हैं।', { ok: 'बाहर जाएँ', cancel: 'जारी रखें' });
    if (ok) { await save(); return true; }
    paused = wasPaused;
    lastTick = performance.now();
    return false;
  });

  drawQuestion();
  updateTimers();
  const resumed = attempt.elapsedMs > 0;
  setPaused(false);
  if (resumed) toast(`टेस्ट वहीं से शुरू — बचा समय ${fmtClock(attempt.remainingMs)}`, 'info');
  save();

  return () => {
    clearInterval(timer);
    document.removeEventListener('keydown', onKey);
    document.removeEventListener('visibilitychange', onVis);
    window.removeEventListener('pagehide', onUnload);
    if (!submitting) { attempt.current = idx; db.put('attempts', attempt); }
  };
}
