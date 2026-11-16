import { db } from '../db.js';
import { getSet } from '../store.js';
import { evaluate, pickQuestions, LEVEL_UI } from '../analytics.js';
import { MODE_LABEL } from './pretest.js';
import { icon } from '../ui.js';
import { esc, fmtDur, fmtDate, plainText } from '../util.js';

export async function render({ root, params, setHeader }) {
  const attempt = await db.get('attempts', params[0]);
  if (!attempt) { root.innerHTML = '<div class="card">परिणाम नहीं मिला</div>'; return; }
  const set = await getSet(attempt.setId);
  if (!set) { root.innerHTML = '<div class="card">प्रैक्टिस सेट डिलीट हो चुका है</div>'; return; }
  // Re-evaluate so edits to the set (e.g. corrected answer key) are reflected.
  const r = evaluate(set, attempt);
  attempt.result = r;
  db.put('attempts', attempt);
  setHeader({ title: 'परिणाम / Result', sub: attempt.title, back: 'home' });

  const qmap = new Map(set.questions.map((q) => [q.q_no, q]));
  const allotted = attempt.config.durationMin * 60000;
  const ring = Math.max(0, Math.min(100, r.scorePct));
  const groups = { weak: [], moderate: [], strong: [] };
  for (const t of r.topics) groups[t.level].push(t);
  const counts = {
    wrong: pickQuestions(r, 'wrong').length, skipped: pickQuestions(r, 'skipped').length,
    wrong_skipped: pickQuestions(r, 'wrong_skipped').length, weak: pickQuestions(r, 'weak').length, traps: r.timeTraps.length,
  };
  const practice = (mode) => `<a href="#/pretest/${encodeURIComponent(set.id)}?mode=${mode}&from=${encodeURIComponent(attempt.id)}"
      class="btn-outline justify-between ${counts[mode] ? '' : 'pointer-events-none opacity-40'}"><span>${MODE_LABEL[mode].split(' / ')[0]}</span><span class="chip bg-slate-100 dark:bg-slate-800">${counts[mode]}</span></a>`;
  const topicRow = (t) => `
    <div class="py-2">
      <div class="flex items-center gap-2 text-sm">
        <span class="min-w-0 flex-1 truncate font-medium">${esc(t.topic)}</span>
        ${t.slow ? '<span class="chip bg-orange-100 !py-0 text-[10px] text-orange-700" title="औसत से दोगुना समय">⏱️ धीमा</span>' : ''}
        <span class="text-xs text-slate-500">${t.correct}/${t.total}</span>
        <span class="w-12 text-right text-xs font-bold">${t.accuracy}%</span>
      </div>
      <div class="mt-1 flex items-center gap-2">
        <div class="bar flex-1"><span class="${LEVEL_UI[t.level].bar}" style="width:${Math.max(3, t.accuracy)}%"></span></div>
        <span class="w-24 truncate text-right text-[10px] text-slate-400">${esc(t.section)}</span>
      </div>
      <div class="text-[10px] text-slate-400">औसत समय ${fmtDur(t.avgTime)} • ✗ ${t.wrong} • छोड़े ${t.skipped}</div>
    </div>`;

  root.innerHTML = `
    <div class="card mb-3">
      <div class="flex items-center gap-4">
        <div class="relative h-28 w-28 shrink-0 rounded-full" style="background:conic-gradient(#1b5ef5 ${ring * 3.6}deg, rgb(148 163 184 / .25) 0)">
          <div class="absolute inset-2 flex flex-col items-center justify-center rounded-full bg-white dark:bg-slate-900">
            <div class="text-2xl font-extrabold text-brand-600">${r.score}</div>
            <div class="text-[11px] text-slate-500">/ ${r.maxScore} अंक</div>
          </div>
        </div>
        <div class="min-w-0 flex-1">
          <div class="text-xs uppercase text-slate-500">${esc(MODE_LABEL[attempt.mode] || '')}</div>
          <div class="font-bold leading-snug">${esc(attempt.title)}</div>
          <div class="mt-1 text-[11px] text-slate-500">${fmtDate(attempt.submittedAt || attempt.startedAt)} • +${attempt.config.pos} / −${attempt.config.neg}</div>
          <div class="mt-2 text-sm"><b>${r.scorePct}%</b> स्कोर • <b>${r.accuracy}%</b> एक्यूरेसी</div>
        </div>
      </div>
      <div class="mt-4 grid grid-cols-3 gap-2">
        <div class="stat"><div class="stat-v">${r.attempted}<span class="text-xs text-slate-400">/${r.total}</span></div><div class="stat-l">Attempted</div></div>
        <div class="stat"><div class="stat-v text-emerald-600">${r.correct}</div><div class="stat-l">सही / Correct</div></div>
        <div class="stat"><div class="stat-v text-rose-600">${r.wrong}</div><div class="stat-l">गलत / Wrong</div></div>
        <div class="stat"><div class="stat-v text-slate-500">${r.skipped}</div><div class="stat-l">छोड़े / Skipped</div></div>
        <div class="stat"><div class="stat-v">${r.accuracy}%</div><div class="stat-l">Accuracy</div></div>
        <div class="stat"><div class="stat-v text-base">${fmtDur(r.totalTime)}</div><div class="stat-l">समय / ${fmtDur(allotted)}</div></div>
      </div>
      <div class="mt-3 grid grid-cols-2 gap-2">
        <a href="#/solutions/${encodeURIComponent(attempt.id)}" class="btn-primary">📖 सॉल्यूशन देखें</a>
        <a href="#/pretest/${encodeURIComponent(set.id)}" class="btn-outline">🔁 दोबारा टेस्ट दें</a>
      </div>
    </div>

    <div class="card mb-3 !p-0 overflow-hidden">
      <div class="px-4 pt-4 text-sm font-bold">📊 विषयवार रिपोर्ट / Subject-wise report</div>
      <div class="overflow-x-auto p-2">
        <table class="w-full min-w-[520px] text-xs">
          <thead><tr class="text-left text-slate-500"><th class="p-2">विषय</th><th class="p-2 text-center">Q</th><th class="p-2 text-center">✓</th><th class="p-2 text-center">✗</th><th class="p-2 text-center">–</th><th class="p-2 text-center">अंक</th><th class="p-2 text-center">Acc</th><th class="p-2 text-right">समय</th></tr></thead>
          <tbody>${r.sections.map((s) => `
            <tr class="border-t border-slate-100 dark:border-slate-800 ${s.name === r.weakestSection ? 'bg-rose-50 dark:bg-rose-950/30' : ''}">
              <td class="p-2 font-medium">${esc(s.name)}${s.name === r.weakestSection ? ' <span class="text-rose-600">⚠️</span>' : ''}</td>
              <td class="p-2 text-center">${s.total}</td><td class="p-2 text-center text-emerald-600">${s.correct}</td><td class="p-2 text-center text-rose-600">${s.wrong}</td><td class="p-2 text-center">${s.skipped}</td>
              <td class="p-2 text-center font-semibold">${s.score}/${s.max}</td><td class="p-2 text-center">${s.attempted ? `${s.accuracy}%` : '—'}</td><td class="p-2 text-right">${fmtDur(s.time)}</td>
            </tr>`).join('')}</tbody>
        </table>
      </div>
      ${r.weakestSection ? `<div class="border-t border-slate-100 px-4 py-3 text-xs text-rose-700 dark:border-slate-800 dark:text-rose-300">⚠️ सबसे कमजोर प्रदर्शन: <b>${esc(r.weakestSection)}</b> — इस विषय पर अधिक अभ्यास करें।</div>` : ''}
    </div>

    <div class="card mb-3">
      <div class="mb-1 text-sm font-bold">🎯 टॉपिक-वार कमजोरी विश्लेषण / Topic-wise Weakness</div>
      <div class="mb-3 text-[11px] text-slate-500">कमजोर: &lt;50% या औसत से दोगुना समय • मध्यम: 50–75% • मजबूत: &gt;75% (छोड़ा गया प्रश्न = सही नहीं)</div>
      ${['weak', 'moderate', 'strong'].map((lv) => `
        <details class="mb-2 rounded-xl border border-slate-200 dark:border-slate-800" ${lv === 'weak' ? 'open' : ''}>
          <summary class="flex cursor-pointer items-center gap-2 px-3 py-2"><span class="chip ${LEVEL_UI[lv].cls}">${LEVEL_UI[lv].label}</span><span class="text-xs text-slate-500">${groups[lv].length} टॉपिक</span></summary>
          <div class="divide-y divide-slate-100 px-3 pb-2 dark:divide-slate-800">${groups[lv].length ? groups[lv].map(topicRow).join('') : '<div class="py-2 text-xs text-slate-400">कोई नहीं</div>'}</div>
        </details>`).join('')}
    </div>

    <div class="card mb-3">
      <div class="mb-1 text-sm font-bold">⏳ टाइम ट्रैप / Time Trap Detector</div>
      <div class="mb-2 text-[11px] text-slate-500">जिन प्रश्नों पर ${fmtDur(r.trapLimit)} से अधिक समय (औसत ${fmtDur(r.avgTime)} का दोगुना) लगा और उत्तर फिर भी गलत हुआ।</div>
      ${r.timeTraps.length ? `<div class="divide-y divide-slate-100 dark:divide-slate-800">${r.timeTraps.map((i) => {
        const q = qmap.get(i.q_no);
        return `<a href="#/solutions/${encodeURIComponent(attempt.id)}?q=${i.q_no}" class="flex items-center gap-3 py-2">
          <span class="flex h-9 w-9 shrink-0 items-center justify-center rounded-lg bg-rose-100 text-xs font-bold text-rose-700 dark:bg-rose-900/40 dark:text-rose-300">${i.q_no}</span>
          <span class="min-w-0 flex-1"><span class="block truncate text-sm">${esc(plainText(q?.question_hi || q?.question_en, 80))}</span>
          <span class="block text-[11px] text-slate-500">${esc(i.topic)} • आपका ${i.sel} • सही ${i.correct}</span></span>
          <span class="text-right text-sm font-bold text-rose-600">${fmtDur(i.time)}</span></a>`;
      }).join('')}</div>` : '<div class="text-sm text-emerald-600">👍 कोई टाइम ट्रैप नहीं मिला।</div>'}
      ${r.slowSkips.length ? `<div class="mt-2 text-[11px] text-slate-500">इसके अलावा ${r.slowSkips.length} प्रश्नों पर ज़्यादा समय लगाकर छोड़ दिया: Q.${r.slowSkips.map((i) => i.q_no).join(', ')}</div>` : ''}
    </div>

    <div class="card mb-3">
      <div class="mb-1 text-sm font-bold">💪 कमजोरी अभ्यास मोड / Weakness Practice</div>
      <div class="mb-3 text-[11px] text-slate-500">सिर्फ़ चुने हुए प्रश्नों का तुरंत मिनी-टेस्ट शुरू करें।</div>
      <div class="grid gap-2">${practice('wrong')}${practice('skipped')}${practice('wrong_skipped')}${practice('weak')}${practice('traps')}</div>
    </div>
    <a href="#/analytics" class="btn-ghost mb-4 w-full">${icon.chart('h-4 w-4')} सभी टेस्ट का Overall Topic Analytics</a>`;
}
