// Lifetime topic analytics across every submitted attempt.
import { db } from '../db.js';
import { levelOf, LEVEL_UI } from '../analytics.js';
import { confirmDialog, toast, emptyState } from '../ui.js';
import { esc, pct, fmtDur } from '../util.js';
import { refresh } from '../app.js';

export async function render({ root, setHeader }) {
  setHeader({ title: 'Overall Topic Analytics', sub: 'सभी टेस्ट का संयुक्त विश्लेषण', back: 'history' });
  const rows = await db.all('topicStats');
  if (!rows.length) { root.innerHTML = emptyState('अभी डेटा नहीं', 'कम से कम एक टेस्ट सबमिट करें।'); return; }

  const bySection = new Map();
  for (const r of rows) {
    const acc = pct(r.correct, r.total);
    const item = { ...r, acc, level: levelOf(acc), avg: r.total ? r.time / r.total : 0 };
    if (!bySection.has(r.section)) bySection.set(r.section, []);
    bySection.get(r.section).push(item);
  }
  const all = [...bySection.values()].flat();
  const lv = { weak: all.filter((t) => t.level === 'weak').length, moderate: all.filter((t) => t.level === 'moderate').length, strong: all.filter((t) => t.level === 'strong').length };

  root.innerHTML = `
    <div class="card mb-3 grid grid-cols-3 gap-2">
      <div class="stat"><div class="stat-v text-rose-600">${lv.weak}</div><div class="stat-l">कमजोर</div></div>
      <div class="stat"><div class="stat-v text-amber-600">${lv.moderate}</div><div class="stat-l">मध्यम</div></div>
      <div class="stat"><div class="stat-v text-emerald-600">${lv.strong}</div><div class="stat-l">मजबूत</div></div>
    </div>
    ${[...bySection.entries()].map(([sec, ts]) => {
      const c = ts.reduce((n, t) => n + t.correct, 0), tot = ts.reduce((n, t) => n + t.total, 0);
      return `<details class="card mb-2" open>
        <summary class="flex cursor-pointer items-center justify-between text-sm font-bold"><span>${esc(sec)}</span><span class="text-xs font-semibold text-slate-500">${pct(c, tot)}% • ${c}/${tot}</span></summary>
        <div class="mt-2 divide-y divide-slate-100 dark:divide-slate-800">${ts.sort((a, b) => a.acc - b.acc).map((t) => `
          <div class="py-2">
            <div class="flex items-center gap-2 text-sm"><span class="min-w-0 flex-1 truncate">${esc(t.topic)}</span><span class="chip !py-0 text-[10px] ${LEVEL_UI[t.level].cls}">${LEVEL_UI[t.level].label.split(' / ')[0]}</span><span class="w-12 text-right text-xs font-bold">${t.acc}%</span></div>
            <div class="bar mt-1"><span class="${LEVEL_UI[t.level].bar}" style="width:${Math.max(3, t.acc)}%"></span></div>
            <div class="text-[10px] text-slate-400">${t.correct}/${t.total} सही • ${t.wrong} गलत • ${t.skipped} छोड़े • औसत ${fmtDur(t.avg)} • ${t.attempts} टेस्ट</div>
          </div>`).join('')}</div>
      </details>`;
    }).join('')}
    <button class="btn-ghost mt-2 w-full text-rose-600" data-reset>Analytics रीसेट करें</button>`;

  root.querySelector('[data-reset]').onclick = async () => {
    if (await confirmDialog('सभी टॉपिक एनालिटिक्स मिटाएँ? (टेस्ट हिस्ट्री बनी रहेगी)', { ok: 'Reset', danger: true })) {
      await db.clear('topicStats');
      toast('रीसेट हुआ', 'success');
      refresh();
    }
  };
}
