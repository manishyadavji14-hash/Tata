import { allAttempts, listSets } from '../store.js';
import { db } from '../db.js';
import { MODE_LABEL } from './pretest.js';
import { icon, confirmDialog, toast, emptyState } from '../ui.js';
import { esc, fmtDate, fmtDur, fmtClock } from '../util.js';
import { refresh } from '../app.js';

export async function render({ root, setHeader }) {
  setHeader({ title: 'हिस्ट्री / Attempt History', actions: `<a href="#/analytics" class="btn-ghost btn-sm">${icon.chart('h-5 w-5')}</a>` });
  const [attempts, sets] = await Promise.all([allAttempts(), listSets()]);
  const titles = new Map(sets.map((s) => [s.id, s.meta.title]));
  const done = attempts.filter((a) => a.state === 'submitted' && a.result);
  const avgAcc = done.length ? Math.round(done.reduce((n, a) => n + a.result.accuracy, 0) / done.length) : 0;
  const totalQ = done.reduce((n, a) => n + a.result.attempted, 0);
  const totalT = done.reduce((n, a) => n + a.result.totalTime, 0);

  root.innerHTML = `
    <div class="card mb-3 grid grid-cols-3 gap-2">
      <div class="stat"><div class="stat-v">${done.length}</div><div class="stat-l">टेस्ट दिए</div></div>
      <div class="stat"><div class="stat-v">${totalQ}</div><div class="stat-l">प्रश्न हल</div></div>
      <div class="stat"><div class="stat-v">${avgAcc}%</div><div class="stat-l">औसत Accuracy</div></div>
      <div class="stat col-span-3"><div class="stat-v text-base">${fmtDur(totalT)}</div><div class="stat-l">कुल अभ्यास समय</div></div>
    </div>
    <div class="space-y-2">${attempts.length ? attempts.map((a) => `
      <div class="card flex items-center gap-3 !p-3">
        <a href="#/${a.state === 'submitted' ? 'result' : 'exam'}/${encodeURIComponent(a.id)}" class="min-w-0 flex-1">
          <div class="truncate text-sm font-semibold">${esc(a.title)}</div>
          <div class="truncate text-[11px] text-slate-500">${esc(titles.get(a.setId) || a.title || 'सेट डिलीट हो चुका')} • ${fmtDate(a.startedAt)}</div>
          <div class="text-[11px] text-slate-500">${esc((MODE_LABEL[a.mode] || a.mode).split(' / ')[0])} • ${a.qNos.length} प्रश्न</div>
        </a>
        ${a.state === 'submitted' && a.result
          ? `<div class="text-right"><div class="font-bold text-brand-600">${a.result.score}/${a.result.maxScore}</div><div class="text-[10px] text-slate-500">${a.result.accuracy}% • ${fmtDur(a.result.totalTime)}</div></div>`
          : `<a href="#/exam/${encodeURIComponent(a.id)}" class="chip bg-amber-100 text-amber-800">Resume ${fmtClock(a.remainingMs)}</a>`}
        <button class="btn-ghost btn-sm !px-2 text-slate-400" data-del="${esc(a.id)}" aria-label="Delete">${icon.trash('h-4 w-4')}</button>
      </div>`).join('') : emptyState('अभी कोई प्रयास नहीं', 'होम से कोई प्रैक्टिस सेट शुरू करें।', '<a href="#/home" class="btn-primary mt-2">होम</a>')}</div>`;

  root.addEventListener('click', async (e) => {
    const d = e.target.closest('[data-del]');
    if (!d) return;
    if (await confirmDialog('यह प्रयास डिलीट करें?', { ok: 'Delete', danger: true })) {
      await db.delete('attempts', d.dataset.del);
      toast('डिलीट हुआ', 'success');
      refresh();
    }
  });
}
