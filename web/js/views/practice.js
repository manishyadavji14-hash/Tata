// "अभ्यास" tab: subject-wise custom practice across all sets + the wrong-answers bank.
import { practiceSources, subjectsOf, subjectKey, createSubjectPractice, createMistakePractice, mistakeRows, backfillMistakes, MASTER_AFTER } from '../practice.js';
import { db } from '../db.js';
import { icon, toast, emptyState } from '../ui.js';
import { esc, pickLang } from '../util.js';

const QUICK = [10, 25, 50, 100];

export async function render({ root, settings, navigate, setHeader }) {
  setHeader({ title: 'अभ्यास / Practice', sub: 'विषयवार प्रश्न और गलत हुए प्रश्नों का अभ्यास' });
  await backfillMistakes();
  const [sets, mistakes, bmCount] = await Promise.all([practiceSources(), mistakeRows(), db.all('bookmarks').then((r) => r.length)]);
  if (!sets.length) {
    root.innerHTML = emptyState('कोई प्रैक्टिस सेट नहीं', 'पहले इम्पोर्ट टैब से पेपर जोड़ें, फिर यहाँ विषयवार अभ्यास करें।');
    return;
  }
  const lang = settings.lang || 'both';
  const st = {
    setIds: new Set(sets.map((s) => s.id)),
    subjects: new Set(),
    count: 25, shuffled: true,
    mSubjects: new Set(), mCount: 25, mShuffled: false,
  };

  root.innerHTML = `
    <section class="card mb-3" data-sub>
      <div class="mb-1 flex items-center gap-2 font-bold">${icon.target('h-5 w-5')} विषयवार अभ्यास</div>
      <div class="mb-3 text-xs text-slate-500">एक या अधिक विषय चुनें। प्रश्न आपके चुने हुए सभी प्रैक्टिस सेट से लिए जाएँगे।</div>
      <details class="mb-3 rounded-xl border border-slate-200 p-2 dark:border-slate-700">
        <summary class="cursor-pointer text-sm font-semibold">📚 प्रैक्टिस सेट: <span data-setsum></span></summary>
        <div class="mt-2 flex gap-2"><button class="btn-outline btn-sm" data-sets="all">सभी</button><button class="btn-outline btn-sm" data-sets="none">कोई नहीं</button></div>
        <div class="mt-2 max-h-56 space-y-1 overflow-y-auto">${sets.map((s) => `
          <label class="flex items-center gap-2 rounded-lg px-1 py-1 text-sm"><input type="checkbox" class="h-4 w-4" data-set="${esc(s.id)}" checked><span class="min-w-0 flex-1 truncate">${esc(s.meta.title)}</span><span class="text-xs text-slate-400">${s.questions.length}</span></label>`).join('')}
        </div>
      </details>
      <div class="mb-1 flex items-center justify-between text-sm font-semibold"><span>विषय चुनें</span><button class="btn-ghost btn-sm" data-suball>सभी विषय</button></div>
      <div class="mb-3 flex flex-wrap gap-2" data-subjects></div>
      <div class="mb-1 text-sm font-semibold">प्रश्नों की संख्या <span class="font-normal text-slate-500" data-avail></span></div>
      <div class="mb-3 flex flex-wrap items-center gap-2">
        <input type="number" min="1" class="input !w-24 !py-1.5" value="25" data-count>
        ${QUICK.map((n) => `<button class="btn-outline btn-sm" data-q="${n}">${n}</button>`).join('')}
        <button class="btn-outline btn-sm" data-q="all">सभी</button>
      </div>
      <div class="mb-3 grid grid-cols-2 gap-2" data-modes="sub"></div>
      <button class="btn-primary w-full" data-start="sub">▶ अभ्यास शुरू करें</button>
    </section>

    <section class="card mb-3" data-mis>
      <div class="mb-1 flex items-center gap-2 font-bold">❌ गलत हुए प्रश्न <span class="chip bg-rose-100 text-rose-700 dark:bg-rose-900/40 dark:text-rose-300">${mistakes.length}</span></div>
      <div class="mb-3 text-xs text-slate-500">किसी भी टेस्ट या अभ्यास में गलत हुआ प्रश्न यहाँ जुड़ जाता है। कोई प्रश्न कुल <b>${MASTER_AFTER} बार सही</b> करने पर सूची से अपने-आप हट जाता है।</div>
      ${mistakes.length ? `
        <div class="mb-1 text-sm font-semibold">विषय (खाली छोड़ें = सभी)</div>
        <div class="mb-3 flex flex-wrap gap-2" data-msubjects></div>
        <div class="mb-1 text-sm font-semibold">प्रश्नों की संख्या <span class="font-normal text-slate-500" data-mavail></span></div>
        <div class="mb-3 flex flex-wrap items-center gap-2">
          <input type="number" min="1" class="input !w-24 !py-1.5" value="25" data-mcount>
          ${QUICK.map((n) => `<button class="btn-outline btn-sm" data-mq="${n}">${n}</button>`).join('')}
          <button class="btn-outline btn-sm" data-mq="all">सभी</button>
        </div>
        <div class="mb-3 grid grid-cols-2 gap-2" data-modes="mis"></div>
        <button class="btn-danger w-full" data-start="mis">▶ गलत प्रश्न हल करें</button>
        <details class="mt-3">
          <summary class="cursor-pointer text-sm font-semibold text-slate-600 dark:text-slate-300">सूची देखें</summary>
          <div class="mt-2 max-h-96 space-y-2 overflow-y-auto">${mistakes.map((m) => {
            const v = pickLang(m.q.question_hi, m.q.question_en, lang);
            const text = (typeof v === 'string' ? v : v.hi || v.en).replace(/<[^>]+>/g, ' ');
            const dots = Array.from({ length: MASTER_AFTER }, (_, i) => `<span class="inline-block h-2.5 w-2.5 rounded-full ${i < m.correct ? 'bg-emerald-500' : 'bg-slate-300 dark:bg-slate-600'}"></span>`).join('');
            return `<a href="#/solutions/study?set=${encodeURIComponent(m.set.id)}&q=${m.q.q_no}" class="block rounded-xl border border-slate-200 p-2 text-sm dark:border-slate-700">
              <div class="flex items-center gap-2 text-[11px] text-slate-500"><span class="truncate">${esc(m.set.meta.title)} • Q${m.q.q_no}</span><span class="ml-auto flex gap-1" title="${m.correct}/${MASTER_AFTER} सही">${dots}</span></div>
              <div class="mt-1 line-clamp-2">${esc(text.slice(0, 160))}</div>
              <div class="mt-1 text-[11px] text-rose-600">गलत: ${m.wrong} बार • सही: ${m.correct}/${MASTER_AFTER}</div></a>`;
          }).join('')}</div>
        </details>` : '<div class="rounded-xl bg-emerald-50 p-3 text-center text-sm text-emerald-700 dark:bg-emerald-950/30 dark:text-emerald-300">🎉 अभी कोई गलत प्रश्न नहीं है। कोई टेस्ट या अभ्यास देने के बाद गलत प्रश्न यहाँ दिखेंगे।</div>'}
    </section>

    <a href="#/bookmarks" class="card flex items-center gap-3 !p-3">${icon.bookmark('h-5 w-5')}<span class="flex-1 font-semibold">बुकमार्क किए प्रश्न</span><span class="chip bg-amber-100 text-amber-700">${bmCount}</span></a>`;

  const $ = (s) => root.querySelector(s);
  const chosenSets = () => sets.filter((s) => st.setIds.has(s.id));
  const chip = (on, attr, key, label, n) => `<button class="tab !px-3 !py-1.5 text-sm ${on ? 'tab-on' : 'tab-off'}" ${attr}="${esc(key)}">${on ? '✓ ' : ''}${esc(label)} <span class="opacity-70">${n}</span></button>`;
  const modeTabs = (sh, prefix) => `<button class="tab ${!sh ? 'tab-on' : 'tab-off'}" data-${prefix}mode="normal">📋 नॉर्मल (क्रम से)</button><button class="tab ${sh ? 'tab-on' : 'tab-off'}" data-${prefix}mode="shuffle">🔀 Shuffle</button>`;

  const subAvail = () => {
    let n = 0;
    for (const s of chosenSets()) for (const q of s.questions) if (st.subjects.has(subjectKey(q.section))) n++;
    return n;
  };
  const drawSub = () => {
    const subs = subjectsOf(chosenSets());
    for (const k of [...st.subjects]) if (!subs.some((s) => s.key === k)) st.subjects.delete(k);
    $('[data-subjects]').innerHTML = subs.map((s) => chip(st.subjects.has(s.key), 'data-subj', s.key, s.label, s.count)).join('');
    $('[data-setsum]').textContent = `${st.setIds.size} / ${sets.length}`;
    const n = subAvail();
    $('[data-avail]').textContent = st.subjects.size ? `(उपलब्ध: ${n})` : '(पहले विषय चुनें)';
    $('[data-modes="sub"]').innerHTML = modeTabs(st.shuffled, '');
  };
  const misFiltered = () => mistakes.filter((m) => !st.mSubjects.size || st.mSubjects.has(m.subject));
  const drawMis = () => {
    if (!mistakes.length) return;
    const counts = new Map();
    for (const m of mistakes) {
      const e = counts.get(m.subject) || { n: 0, label: m.q.section };
      e.n++;
      counts.set(m.subject, e);
    }
    $('[data-msubjects]').innerHTML = [...counts.entries()].map(([k, e]) => chip(st.mSubjects.has(k), 'data-msubj', k, e.label, e.n)).join('');
    $('[data-mavail]').textContent = `(उपलब्ध: ${misFiltered().length})`;
    $('[data-modes="mis"]').innerHTML = modeTabs(st.mShuffled, 'm');
  };
  drawSub();
  drawMis();

  root.addEventListener('change', (e) => {
    const cb = e.target.closest('[data-set]');
    if (cb) { if (cb.checked) st.setIds.add(cb.dataset.set); else st.setIds.delete(cb.dataset.set); drawSub(); }
  });
  root.addEventListener('click', async (e) => {
    const t = (sel) => e.target.closest(sel);
    let b;
    if ((b = t('[data-sets]'))) {
      const all = b.dataset.sets === 'all';
      root.querySelectorAll('[data-set]').forEach((c) => { c.checked = all; if (all) st.setIds.add(c.dataset.set); else st.setIds.delete(c.dataset.set); });
      drawSub(); return;
    }
    if ((b = t('[data-subj]'))) { const k = b.dataset.subj; if (st.subjects.has(k)) st.subjects.delete(k); else st.subjects.add(k); drawSub(); return; }
    if (t('[data-suball]')) { const subs = subjectsOf(chosenSets()); const all = subs.every((s) => st.subjects.has(s.key)); st.subjects = new Set(all ? [] : subs.map((s) => s.key)); drawSub(); return; }
    if ((b = t('[data-q]'))) { $('[data-count]').value = b.dataset.q === 'all' ? subAvail() || 1 : b.dataset.q; return; }
    if ((b = t('[data-mode]'))) { st.shuffled = b.dataset.mode === 'shuffle'; drawSub(); return; }
    if ((b = t('[data-msubj]'))) { const k = b.dataset.msubj; if (st.mSubjects.has(k)) st.mSubjects.delete(k); else st.mSubjects.add(k); drawMis(); return; }
    if ((b = t('[data-mq]'))) { $('[data-mcount]').value = b.dataset.mq === 'all' ? misFiltered().length || 1 : b.dataset.mq; return; }
    if ((b = t('[data-mmode]'))) { st.mShuffled = b.dataset.mmode === 'shuffle'; drawMis(); return; }
    if ((b = t('[data-start]'))) {
      try {
        let row;
        if (b.dataset.start === 'sub') {
          if (!st.setIds.size) { toast('कम से कम एक प्रैक्टिस सेट चुनें', 'warn'); return; }
          if (!st.subjects.size) { toast('कम से कम एक विषय चुनें', 'warn'); return; }
          const avail = subAvail();
          const count = Math.min(avail, Math.max(1, Number($('[data-count]').value) || 25));
          row = await createSubjectPractice({ setIds: [...st.setIds], subjects: [...st.subjects], count, shuffled: st.shuffled });
        } else {
          const avail = misFiltered().length;
          if (!avail) { toast('चुने गए विषय में कोई गलत प्रश्न नहीं', 'warn'); return; }
          const count = Math.min(avail, Math.max(1, Number($('[data-mcount]').value) || 25));
          row = await createMistakePractice({ subjects: [...st.mSubjects], count, shuffled: st.mShuffled });
        }
        navigate(`pretest/${encodeURIComponent(row.id)}?from_practice=1`);
      } catch (err) { toast(err.message, 'error', 5000); }
    }
  });
}

