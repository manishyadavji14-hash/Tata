// "नया प्रैक्टिस सेट बनाएँ": choose how many questions to take from each subject (or from each topic of a
// subject), in normal order or at random, from any of your sets → a new permanent practice set.
import { bankItems, bankSets, groupBank, statsMap, MASTERED_AT } from '../qstats.js';
import { buildPracticeSet, shuffle } from '../practice.js';
import { toast, emptyState } from '../ui.js';
import { esc } from '../util.js';

const QUICK = [5, 10, 20, 25, 50];

export async function render({ root, navigate, setHeader }) {
  setHeader({ title: 'नया प्रैक्टिस सेट बनाएँ', sub: 'विषय / टॉपिक से प्रश्नों की संख्या चुनें', back: 'bank' });
  const [sets, stats] = await Promise.all([bankSets(), statsMap()]);
  if (!sets.length) { root.innerHTML = emptyState('कोई प्रश्न नहीं', 'पहले इम्पोर्ट टैब से प्रश्न-पत्र जोड़ें।'); return; }
  sets.sort((a, b) => (a.meta.title || '').localeCompare(b.meta.title || '', 'hi', { numeric: true }));

  const st = { setIds: new Set(sets.map((s) => s.id)), random: true, skipMastered: false, subj: new Map(), topic: new Map() };
  // st.subj: sk -> n (take n from the whole subject); st.topic: `${sk}|${topic}` -> n (overrides the subject number)
  let groups = [];

  const today = new Date().toLocaleDateString('hi-IN', { day: 'numeric', month: 'short' });
  root.innerHTML = `
    <div class="card mb-3 space-y-3">
      <div><label class="label">सेट का नाम</label><input class="input" name="title" value="मेरा प्रैक्टिस सेट • ${esc(today)}"></div>
      <details class="rounded-xl border border-slate-200 p-2 dark:border-slate-700">
        <summary class="cursor-pointer text-sm font-semibold">📚 किन सेट से प्रश्न लें: <span data-setsum></span></summary>
        <div class="mt-2 flex gap-2"><button class="btn-outline btn-sm" data-sets="all">सभी</button><button class="btn-outline btn-sm" data-sets="none">कोई नहीं</button></div>
        <div class="mt-2 max-h-56 space-y-1 overflow-y-auto">${sets.map((s) => `
          <label class="flex items-center gap-2 rounded-lg px-1 py-1 text-sm"><input type="checkbox" class="h-4 w-4" data-set="${esc(s.id)}" checked><span class="min-w-0 flex-1 truncate">${esc(s.meta.title)}</span><span class="text-xs text-slate-400">${s.questions.length}</span></label>`).join('')}</div>
      </details>
      <div class="grid grid-cols-2 gap-2" data-mode></div>
      <label class="flex items-center gap-2 text-sm"><input type="checkbox" class="h-4 w-4" data-skip> ${MASTERED_AT}+ बार सही हो चुके प्रश्न छोड़ दें</label>
      <div class="grid grid-cols-2 gap-2">
        <div><label class="label">समय प्रति प्रश्न (मिनट)</label><input class="input" type="number" step="0.1" min="0.2" value="1" name="mpq"></div>
        <div><label class="label">− गलत (नेगेटिव)</label><input class="input" type="number" step="0.01" min="0" value="0" name="neg"></div>
      </div>
    </div>
    <div class="mb-2 flex items-center justify-between text-xs font-bold uppercase tracking-wide text-slate-500"><span>विषय और टॉपिक</span><button class="btn-ghost btn-sm normal-case" data-reset>सब 0 करें</button></div>
    <div class="mb-1 text-xs text-slate-500">विषय के आगे संख्या लिखें (पूरे विषय से), या ▸ खोलकर हर टॉपिक की संख्या अलग लिखें।</div>
    <div class="space-y-2 pb-24" data-tree></div>
    <div class="fixed inset-x-0 bottom-[60px] z-30 px-3">
      <div class="mx-auto flex max-w-3xl items-center gap-2 rounded-2xl border border-slate-200 bg-white p-2 shadow-lg dark:border-slate-700 dark:bg-slate-900">
        <span class="px-2 text-sm font-bold" data-total>0 प्रश्न</span>
        <button class="btn-outline btn-sm flex-1" data-make="save">💾 सेट सेव करें</button>
        <button class="btn-primary btn-sm flex-1" data-make="start">▶ सेव + शुरू</button>
      </div>
    </div>`;
  const $ = (s) => root.querySelector(s);
  const tree = $('[data-tree]');

  const pool = async () => {
    let items = await bankItems({ sets: sets.filter((s) => st.setIds.has(s.id)) });
    items = items.filter((it) => it.q.correct_option);
    if (st.skipMastered) items = items.filter((it) => (stats.get(it.key)?.correct || 0) < MASTERED_AT);
    return items;
  };
  const topicSum = (s) => s.topics.reduce((n, t) => n + (st.topic.get(`${s.key}|${t.name}`) || 0), 0);
  const subjN = (s) => (topicSum(s) || st.subj.get(s.key) || 0);
  const total = () => groups.reduce((n, s) => n + Math.min(s.items.length, subjN(s)), 0);
  const drawTotal = () => { $('[data-total]').textContent = `${total()} प्रश्न`; };
  const drawMode = () => {
    $('[data-mode]').innerHTML = `<button class="tab ${!st.random ? 'tab-on' : 'tab-off'}" data-m="normal">📋 नॉर्मल (क्रम से)</button><button class="tab ${st.random ? 'tab-on' : 'tab-off'}" data-m="random">🔀 रैंडम</button>`;
    $('[data-setsum]').textContent = `${st.setIds.size} / ${sets.length}`;
  };
  const num = (attr, key, val, max, dis = false) => `<input type="number" min="0" max="${max}" inputmode="numeric" class="input !w-20 !px-2 !py-1 text-center" ${attr}="${esc(key)}" value="${val || ''}" placeholder="0" ${dis ? 'disabled' : ''}>`;
  const drawTree = async () => {
    groups = groupBank(await pool());
    tree.innerHTML = groups.length ? groups.map((s) => {
      const byTopic = topicSum(s) > 0;
      return `<details class="card !p-3">
        <summary class="flex cursor-pointer items-center gap-2">
          <span class="text-lg">${s.icon}</span>
          <span class="min-w-0 flex-1"><span class="block font-bold leading-tight">${esc(s.label)}</span><span class="block text-[11px] text-slate-500">उपलब्ध ${s.items.length} • ${s.topics.length} टॉपिक${byTopic ? ' • टॉपिक-वार' : ''}</span></span>
          ${num('data-ns', s.key, subjN(s), s.items.length, byTopic)}
        </summary>
        <div class="mt-2 flex flex-wrap gap-1">${QUICK.filter((n) => n < s.items.length).map((n) => `<button class="btn-outline btn-sm" data-qs="${esc(s.key)}|${n}">${n}</button>`).join('')}<button class="btn-outline btn-sm" data-qs="${esc(s.key)}|${s.items.length}">सभी ${s.items.length}</button></div>
        <div class="mt-2 space-y-1">${s.topics.map((t) => `<div class="flex items-center gap-2 rounded-lg px-1 py-1 text-sm">
          <span class="min-w-0 flex-1">${esc(t.name)} <span class="text-xs text-slate-400">(${t.items.length})</span></span>
          ${num('data-nt', `${s.key}|${t.name}`, st.topic.get(`${s.key}|${t.name}`), t.items.length)}</div>`).join('')}</div>
      </details>`;
    }).join('') : '<div class="card py-6 text-center text-sm text-slate-500">चुने गए सेट में कोई प्रश्न नहीं</div>';
    drawTotal();
  };
  drawMode();
  await drawTree();

  root.addEventListener('input', (e) => {
    const c = e.target;
    if (c.dataset.ns) { st.subj.set(c.dataset.ns, Math.max(0, Number(c.value) || 0)); drawTotal(); }
    if (c.dataset.nt) {
      st.topic.set(c.dataset.nt, Math.max(0, Number(c.value) || 0));
      const sk = c.dataset.nt.split('|')[0];
      const s = groups.find((g) => g.key === sk);
      const box = c.closest('details').querySelector('[data-ns]');
      box.value = subjN(s) || ''; box.disabled = topicSum(s) > 0;
      drawTotal();
    }
  });
  root.addEventListener('change', async (e) => {
    const c = e.target;
    if (c.dataset.set) { if (c.checked) st.setIds.add(c.dataset.set); else st.setIds.delete(c.dataset.set); drawMode(); await drawTree(); }
    if (c.matches('[data-skip]')) { st.skipMastered = c.checked; await drawTree(); }
  });
  root.addEventListener('click', async (e) => {
    const t = (s) => e.target.closest(s);
    let b;
    if (t('summary input')) { e.preventDefault(); return; } // typing a number must not fold the subject
    if ((b = t('[data-sets]'))) {
      const all = b.dataset.sets === 'all';
      root.querySelectorAll('[data-set]').forEach((c) => { c.checked = all; if (all) st.setIds.add(c.dataset.set); else st.setIds.delete(c.dataset.set); });
      drawMode(); await drawTree(); return;
    }
    if ((b = t('[data-m]'))) { st.random = b.dataset.m === 'random'; drawMode(); return; }
    if ((b = t('[data-qs]'))) {
      const [sk, n] = b.dataset.qs.split('|');
      const s = groups.find((g) => g.key === sk);
      for (const tp of s.topics) st.topic.delete(`${sk}|${tp.name}`);
      st.subj.set(sk, Number(n));
      const d = b.closest('details');
      d.querySelectorAll('[data-nt]').forEach((x) => { x.value = ''; });
      const box = d.querySelector('[data-ns]');
      box.disabled = false; box.value = n;
      drawTotal(); return;
    }
    if (t('[data-reset]')) { st.subj.clear(); st.topic.clear(); await drawTree(); return; }
    if ((b = t('[data-make]'))) {
      const picks = [];
      const choose = (arr, n) => (st.random ? shuffle([...arr]) : [...arr]).slice(0, n);
      for (const s of groups) {
        let chosen = [];
        if (topicSum(s) > 0) for (const tp of s.topics) chosen.push(...choose(tp.items, st.topic.get(`${s.key}|${tp.name}`) || 0));
        else chosen = choose(s.items, st.subj.get(s.key) || 0);
        if (!st.random) chosen.sort((a, c) => s.items.indexOf(a) - s.items.indexOf(c));
        else shuffle(chosen);
        picks.push(...chosen.map((it) => ({ set: it.set, q: { ...it.q, section: s.label } }))); // one section per subject
      }
      if (!picks.length) { toast('कम से कम एक विषय या टॉपिक में संख्या लिखें', 'warn'); return; }
      const title = root.querySelector('[name=title]').value.trim() || 'मेरा प्रैक्टिस सेट';
      const row = await buildPracticeSet(picks, {
        title, kind: null, minutesPerQ: Math.max(0.2, Number(root.querySelector('[name=mpq]').value) || 1), neg: Math.max(0, Number(root.querySelector('[name=neg]').value) || 0),
        instructions: { hi: `यह सेट ${groups.filter((s) => subjN(s)).map((s) => s.label).join(', ')} के प्रश्नों से बना है (${st.random ? 'रैंडम' : 'क्रम से'})। हर प्रश्न के नीचे उसका मूल पेपर और प्रश्न संख्या लिखी है।`, en: '' },
      });
      toast(`"${row.meta.title}" बना — ${row.questions.length} प्रश्न`, 'success', 3000);
      navigate(b.dataset.make === 'start' ? `pretest/${encodeURIComponent(row.id)}` : `set/${encodeURIComponent(row.id)}`);
    }
  });
}
