import { db } from '../db.js';
import { listSets, toggleBookmark } from '../store.js';
import { solutionCard } from '../components.js';
import { openDoubtChat } from '../chat.js';
import { toast, emptyState } from '../ui.js';
import { esc } from '../util.js';
import { refresh } from '../app.js';

export async function render({ root, settings, setHeader }) {
  setHeader({ title: 'बुकमार्क / Bookmarks' });
  const [rows, sets] = await Promise.all([db.all('bookmarks'), listSets()]);
  const setMap = new Map(sets.map((s) => [s.id, s]));
  const groups = new Map();
  for (const b of rows.sort((a, c) => c.createdAt - a.createdAt)) {
    const s = setMap.get(b.setId);
    const q = s?.questions.find((x) => x.q_no === b.q_no);
    if (!q) continue;
    if (!groups.has(s.id)) groups.set(s.id, { set: s, qs: [] });
    groups.get(s.id).qs.push(q);
  }
  if (!groups.size) {
    root.innerHTML = emptyState('कोई बुकमार्क नहीं', 'टेस्ट या सॉल्यूशन स्क्रीन पर 🔖 दबाकर ज़रूरी प्रश्न सेव करें।');
    return;
  }
  const lang = settings.lang || 'both';
  root.innerHTML = [...groups.values()].map((g) => `
    <section class="mb-5">
      <h2 class="mb-2 flex items-center justify-between text-xs font-bold uppercase tracking-wide text-slate-500"><span class="truncate">${esc(g.set.meta.title)}</span><span>${g.qs.length}</span></h2>
      <div class="space-y-3" data-set="${esc(g.set.id)}">${g.qs.map((q) => solutionCard(g.set, q, lang, null, { bookmarked: true })).join('')}</div>
    </section>`).join('');

  root.addEventListener('click', async (e) => {
    const holder = e.target.closest('[data-set]');
    if (!holder) return;
    const set = setMap.get(holder.dataset.set);
    const bm = e.target.closest('[data-bm]');
    if (bm) { await toggleBookmark(set.id, Number(bm.dataset.bm)); toast('बुकमार्क हटाया', 'info', 1200); refresh(); return; }
    const ask = e.target.closest('[data-ask]');
    if (ask) openDoubtChat(set, set.questions.find((q) => q.q_no === Number(ask.dataset.ask)));
  });
}
