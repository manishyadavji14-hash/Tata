// "तैयारी रिपोर्ट": how ready each subject → topic → concept is, coloured by the readiness score
// (questions solved ≥3 times, accuracy, time, concept notes learned). #/mastery and #/mastery/<subject>/<topic>.
import { bankItems, groupBank, statsMap, globalAvgTime, measure, LEVELS, SCORE_HELP, cylinderHtml, conceptCardsBySubject, linkedConcepts, MASTERED_AT, NO_CONCEPT } from '../qstats.js';
import { progressMap } from '../concepts.js';
import { conceptCard, wireCards } from './concepts.js';
import { buildPracticeSet, shuffle } from '../practice.js';
import { solutionCard } from '../components.js';
import { bookmarkSet, toggleBookmark } from '../store.js';
import { openDoubtChat } from '../chat.js';
import { icon, toast, modal, emptyState } from '../ui.js';
import { esc, fmtDur, plainText } from '../util.js';

let sortWeak = true;

const chip = (m) => `<span class="chip shrink-0 ${LEVELS[m.level].cls}">${LEVELS[m.level].dot} ${m.score == null ? LEVELS.new.label : `${m.score}% • ${LEVELS[m.level].label}`}</span>`;
const bar = (m) => `<div class="bar mt-1.5 !h-2"><span class="${LEVELS[m.level].bar}" style="width:${m.total ? Math.round((m.mastered / m.total) * 100) : 0}%"></span></div>`;
const line = (m) => `<div class="mt-1 flex flex-wrap gap-x-3 gap-y-0.5 text-[11px] text-slate-500">
  <span>✓${MASTERED_AT}+ : <b>${m.mastered}</b>/${m.total}</span><span>हल किए: ${m.seen}</span>
  ${m.correct + m.wrong ? `<span>Accuracy: <b>${Math.round(m.acc * 100)}%</b></span>` : ''}
  ${m.wrong ? `<span class="text-rose-600">✗ ${m.wrong} गलती</span>` : ''}
  ${m.answers ? `<span>⏱ ${fmtDur(m.time)} (औसत ${Math.round(m.avgTime / 1000)}s)</span>` : ''}
  ${m.conceptsTotal ? `<span>📘 ${m.conceptsLearned}/${m.conceptsTotal} कॉन्सेप्ट याद</span>` : ''}</div>`;
const order = (arr) => (sortWeak ? arr.sort((a, b) => (a.m.score ?? 101) - (b.m.score ?? 101) || b.m.total - a.m.total) : arr.sort((a, b) => b.m.total - a.m.total));

async function load() {
  const [items, stats, cards, prog] = await Promise.all([bankItems(), statsMap(), conceptCardsBySubject(), progressMap()]);
  const avg = globalAvgTime(stats);
  const groups = groupBank(items).map((s) => {
    const topics = s.topics.map((t) => {
      const notes = linkedConcepts(cards.get(s.key), t);
      return { ...t, notes, m: measure(t.items, stats, avg, notes, prog) };
    });
    const allNotes = [...new Map(topics.flatMap((t) => t.notes).map((c) => [c.id, c])).values()];
    return { ...s, topics, m: measure(s.items, stats, avg, allNotes, prog) };
  });
  return { items, stats, avg, groups, prog };
}

export async function render(ctx) {
  if (ctx.params[0]) return renderTopic(ctx);
  const { root, setHeader } = ctx;
  setHeader({ title: 'तैयारी रिपोर्ट / Readiness', sub: 'विषय → टॉपिक → कॉन्सेप्ट', back: 'home', actions: '<a href="#/bank" class="btn-ghost btn-sm">🗃️ बैंक</a>' });
  const { items, stats, groups } = await load();
  if (!items.length) { root.innerHTML = emptyState('कोई प्रश्न नहीं', 'पहले इम्पोर्ट टैब से प्रश्न-पत्र जोड़ें।'); return; }
  const all = { ...measure(items, stats, globalAvgTime(stats)) };

  const draw = () => {
    root.innerHTML = `
      <div class="card mb-3">
        <div class="mb-2 text-sm font-bold">🛢️ कम से कम ${MASTERED_AT} बार सही हल किए प्रश्न</div>
        ${cylinderHtml(all.mastered, all.total, { big: true })}
        <div class="mt-3 grid grid-cols-3 gap-2">
          <div class="stat !p-2"><div class="stat-v text-base">${all.seen}</div><div class="stat-l">हल किए</div></div>
          <div class="stat !p-2"><div class="stat-v text-base">${all.correct + all.wrong ? Math.round(all.acc * 100) : 0}%</div><div class="stat-l">Accuracy</div></div>
          <div class="stat !p-2"><div class="stat-v text-base text-rose-600">${all.wrong}</div><div class="stat-l">गलतियाँ</div></div>
          <div class="stat col-span-3 !p-2"><div class="stat-v text-base">${fmtDur(all.time)}</div><div class="stat-l">कुल समय • औसत ${Math.round(all.avgTime / 1000)}s / प्रश्न</div></div>
        </div>
        <details class="mt-3 text-sm"><summary class="cursor-pointer font-semibold text-brand-600">🎨 रंग कैसे तय होता है?</summary><div class="mt-2 text-xs leading-relaxed">${SCORE_HELP}</div></details>
      </div>
      <div class="mb-2 flex items-center gap-2">
        <h2 class="flex-1 text-xs font-bold uppercase tracking-wide text-slate-500">विषय (${groups.length})</h2>
        <button class="tab ${sortWeak ? 'tab-on' : 'tab-off'}" data-sort="weak">कमजोर पहले</button>
        <button class="tab ${!sortWeak ? 'tab-on' : 'tab-off'}" data-sort="big">ज़्यादा प्रश्न</button>
      </div>
      <div class="space-y-2">${order([...groups]).map((s) => `
        <details class="card border-l-4 !p-3 ${LEVELS[s.m.level].ring}">
          <summary class="nomark cursor-pointer">
            <div class="flex items-center gap-2"><span class="text-lg">${s.icon}</span><span class="min-w-0 flex-1 font-bold leading-tight">${esc(s.label)}</span>${chip(s.m)}</div>
            ${bar(s.m)}${line(s.m)}
          </summary>
          <div class="mt-3 space-y-1.5">${order([...s.topics]).map((t) => `
            <a href="#/mastery/${encodeURIComponent(s.key)}/${encodeURIComponent(t.name)}" class="block rounded-xl border border-l-4 border-slate-200 p-2 dark:border-slate-700 ${LEVELS[t.m.level].ring}">
              <div class="flex items-center gap-2 text-sm"><span class="min-w-0 flex-1 font-semibold">${esc(t.name)}</span>${chip(t.m)}<span class="text-slate-400">›</span></div>
              ${bar(t.m)}${line(t.m)}
            </a>`).join('')}</div>
        </details>`).join('')}</div>`;
  };
  draw();
  root.addEventListener('click', (e) => {
    const b = e.target.closest('[data-sort]');
    if (b) { sortWeak = b.dataset.sort === 'weak'; draw(); }
  });
}

async function renderTopic({ root, params, settings, navigate, setHeader }) {
  const [sk, topicName] = params;
  const { stats, avg, groups, prog } = await load();
  const s = groups.find((g) => g.key === sk);
  const t = s?.topics.find((x) => x.name === topicName);
  setHeader({ title: topicName || 'टॉपिक', sub: s?.label || '', back: 'mastery' });
  if (!t) { root.innerHTML = '<div class="card">यह टॉपिक नहीं मिला (शायद नाम बदल गया)। <a class="text-brand-600" href="#/mastery">वापस</a></div>'; return; }
  const lang = settings.lang || 'both';
  const qstate = (it) => measure([it], stats, avg);
  const tagged = t.concepts.filter((c) => c.name !== NO_CONCEPT).map((c) => ({ ...c, m: measure(c.items, stats, avg) }));
  const notMastered = t.items.filter((it) => (stats.get(it.key)?.correct || 0) < MASTERED_AT);
  const qRow = (it) => {
    const r = stats.get(it.key);
    const m = qstate(it);
    const dots = Array.from({ length: MASTERED_AT }, (_, i) => `<span class="inline-block h-2 w-2 rounded-full ${i < (r?.correct || 0) ? 'bg-emerald-500' : 'bg-slate-300 dark:bg-slate-600'}"></span>`).join('');
    return `<button class="block w-full rounded-xl border border-l-4 border-slate-200 p-2 text-left text-sm dark:border-slate-700 ${LEVELS[m.level].ring}" data-q="${esc(it.key)}">
      <div class="line-clamp-2">${esc(plainText(it.q.question_hi || it.q.question_en || '[चित्र वाला प्रश्न]', 160))}</div>
      <div class="mt-1 flex flex-wrap items-center gap-x-3 text-[11px] text-slate-500"><span class="truncate">${esc(it.set.meta.title)} • Q${it.q.q_no}</span>
        <span class="ml-auto inline-flex items-center gap-0.5">${dots}</span>
        ${r?.wrong ? `<span class="text-rose-600">✗${r.wrong}</span>` : ''}${r?.seen ? `<span>⏱${Math.round(r.time / r.seen / 1000)}s</span>` : '<span>नया</span>'}</div>
    </button>`;
  };

  root.innerHTML = `
    <div class="card mb-3 border-l-4 ${LEVELS[t.m.level].ring}">
      <div class="flex items-center gap-2"><span class="text-lg">${s.icon}</span><span class="min-w-0 flex-1 text-sm text-slate-500">${esc(s.label)}</span>${chip(t.m)}</div>
      <div class="mt-1 text-lg font-bold leading-snug">${esc(t.name)}</div>
      ${cylinderHtml(t.m.mastered, t.m.total)}
      ${line(t.m)}
      <div class="mt-3 grid grid-cols-2 gap-2">
        <button class="btn-primary btn-sm" data-prac="weak" ${notMastered.length ? '' : 'disabled'}>▶ अधूरे ${notMastered.length} प्रश्न</button>
        <button class="btn-outline btn-sm" data-prac="all">▶ सभी ${t.items.length} (रैंडम)</button>
      </div>
    </div>

    ${tagged.length ? `<h2 class="mb-2 text-xs font-bold uppercase tracking-wide text-slate-500">💡 कॉन्सेप्ट (प्रश्नों पर लगे टैग)</h2>
    <div class="mb-3 space-y-1.5">${tagged.map((c, i) => `<details class="rounded-xl border border-l-4 border-slate-200 bg-white p-2 dark:border-slate-700 dark:bg-slate-900 ${LEVELS[c.m.level].ring}">
        <summary class="flex cursor-pointer items-center gap-2 text-sm"><span class="min-w-0 flex-1 font-semibold">${esc(c.name)}</span>${chip(c.m)}</summary>
        ${line(c.m)}<div class="mt-2 space-y-1.5" data-cq="${i}"></div></details>`).join('')}</div>` : ''}

    <h2 class="mb-2 text-xs font-bold uppercase tracking-wide text-slate-500">📘 कॉन्सेप्ट की व्याख्या (${t.notes.length})</h2>
    <div class="mb-3 space-y-2" data-notes>${t.notes.length ? t.notes.map((c) => conceptCard(c, prog, { crumb: c.setTitle })).join('') : `<div class="rounded-xl bg-slate-100 p-3 text-xs text-slate-600 dark:bg-slate-800 dark:text-slate-300">इस टॉपिक से मिलते कॉन्सेप्ट नोट्स नहीं मिले। कॉन्सेप्ट टैब में Selection Book या AI नोट्स जोड़ें, या प्रश्न बैंक में 🏷️ से प्रश्नों पर कॉन्सेप्ट लिखें। हर प्रश्न की व्याख्या नीचे प्रश्न खोलकर देखें।</div>`}</div>

    <h2 class="mb-2 text-xs font-bold uppercase tracking-wide text-slate-500">प्रश्न (${t.items.length}) — टैप करें: प्रश्न, उत्तर और व्याख्या</h2>
    <div class="space-y-1.5" data-qlist>${order(t.items.map((it) => ({ it, m: qstate(it) }))).map((x) => qRow(x.it)).join('')}</div>`;

  wireCards(root.querySelector('[data-notes]'), (id) => t.notes.find((c) => c.id === id), prog);
  root.addEventListener('toggle', (e) => {
    const d = e.target;
    const box = d instanceof HTMLDetailsElement && d.open ? d.querySelector(':scope > [data-cq]') : null;
    if (box && !box.dataset.done) { box.innerHTML = tagged[Number(box.dataset.cq)].items.map(qRow).join(''); box.dataset.done = '1'; }
  }, true);

  const byKey = new Map(t.items.map((it) => [it.key, it]));
  root.addEventListener('click', async (e) => {
    const qb = e.target.closest('[data-q]');
    if (qb) { showQuestion(byKey.get(qb.dataset.q), lang, stats); return; }
    const p = e.target.closest('[data-prac]');
    if (p) {
      const list = p.dataset.prac === 'weak' ? notMastered : t.items;
      const picks = shuffle(list.filter((it) => it.q.correct_option)).map((it) => ({ set: it.set, q: it.q }));
      if (!picks.length) { toast('इस टॉपिक में उत्तर वाले प्रश्न नहीं हैं', 'warn'); return; }
      const row = await buildPracticeSet(picks, { title: `${s.label} › ${t.name} (${picks.length} प्रश्न)` });
      navigate(`pretest/${encodeURIComponent(row.id)}`);
    }
  });
}

async function showQuestion(it, lang, stats) {
  if (!it) return;
  const r = stats.get(it.key);
  const bms = await bookmarkSet(it.set.id);
  const md = modal({
    title: `${esc(it.set.meta.title)} • Q${it.q.q_no}`, size: 'lg',
    body: `${r ? `<div class="mb-2 rounded-xl bg-slate-100 p-2 text-xs dark:bg-slate-800">अब तक: ✓ ${r.correct} सही • ✗ ${r.wrong} गलत • छोड़ा ${r.skipped} • औसत समय ${Math.round(r.time / Math.max(1, r.seen) / 1000)}s${(r.correct || 0) >= MASTERED_AT ? ' • 🟢 तैयार' : ''}</div>` : '<div class="mb-2 text-xs text-slate-500">यह प्रश्न अभी हल नहीं किया।</div>'}
      ${solutionCard(it.set, it.q, lang, null, { bookmarked: bms.has(it.q.q_no) })}`,
  });
  md.el.addEventListener('click', async (e) => {
    const bm = e.target.closest('[data-bm]');
    if (bm) {
      const on = await toggleBookmark(it.set.id, it.q.q_no);
      bm.classList.toggle('text-amber-500', on);
      bm.innerHTML = on ? icon.bookmarkFill('h-5 w-5') : icon.bookmark('h-5 w-5');
      return;
    }
    if (e.target.closest('[data-ask]')) openDoubtChat(it.set, it.q, {});
  });
}
