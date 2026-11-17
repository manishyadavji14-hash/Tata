// "प्रश्न बैंक": every question of every set, grouped subject → topic. Rename subjects/topics everywhere,
// change the subject/topic/concept of chosen questions, or copy them into any set (e.g. the same subject
// of another job's paper). ?target=<setId>&tsec=<subject> opens it in "add to this set" mode.
import { db } from '../db.js';
import { bankItems, groupBank, statsMap, MASTERED_AT, NO_CONCEPT } from '../qstats.js';
import { renameSubjectEverywhere, renameTopicEverywhere, retagItems, copyItemsToSet } from '../bank.js';
import { refresh } from '../app.js';
import { icon, toast, modal, promptDialog, emptyState } from '../ui.js';
import { esc, plainText, debounce } from '../util.js';

const open = new Set(); // expanded subjects / topics survive a re-render
const PAGE = 150;

const qText = (q) => plainText(q.question_hi || q.question_en || (q.question_media?.image_base64 ? '[चित्र वाला प्रश्न]' : ''), 150);

export async function render({ root, query, navigate, setHeader }) {
  const target = query.target ? await db.get('sets', query.target) : null;
  const [items, stats, allSets] = await Promise.all([bankItems(), statsMap(), db.all('sets')]);
  const sets = allSets.filter((s) => s.kind !== 'practice').sort((a, b) => (b.createdAt || 0) - (a.createdAt || 0));
  setHeader({ title: target ? 'प्रश्न जोड़ें / Add questions' : 'प्रश्न बैंक / Question Bank', sub: `${items.length} प्रश्न • सभी सेट से`, back: target ? `set/${target.id}` : 'home' });
  if (!items.length) { root.innerHTML = emptyState('कोई प्रश्न नहीं', 'पहले इम्पोर्ट टैब से प्रश्न-पत्र जोड़ें।'); return; }

  const sel = new Set();
  let search = '';
  let groups = groupBank(items);
  const allSubjects = [...new Set(items.map((i) => i.subject))];
  const allTopics = [...new Set(items.map((i) => i.topic))];

  root.innerHTML = `
    ${target ? `<div class="card mb-3 border-brand-300 bg-brand-50/60 !p-3 text-sm dark:border-brand-800 dark:bg-brand-950/30">
      <div class="font-bold">➕ "${esc(target.meta.title)}" में जोड़ें${query.tsec ? ` › ${esc(query.tsec)}` : ''}</div>
      <div class="mt-1 text-xs text-slate-600 dark:text-slate-300">नीचे से विषय / टॉपिक / प्रश्न चुनें (☐), फिर नीचे "जोड़ें" दबाएँ। जो प्रश्न उस सेट में पहले से है वह दोबारा नहीं जुड़ेगा।</div></div>` : `
    <div class="mb-3 grid grid-cols-2 gap-2">
      <a href="#/builder" class="card flex flex-col gap-1 !p-3 active:scale-[.98]"><span class="text-xl">🧩</span><span class="text-sm font-bold leading-tight">नया प्रैक्टिस सेट बनाएँ</span><span class="text-[11px] text-slate-500">विषय/टॉपिक और संख्या चुनें</span></a>
      <a href="#/mastery" class="card flex flex-col gap-1 !p-3 active:scale-[.98]"><span class="text-xl">📊</span><span class="text-sm font-bold leading-tight">तैयारी रिपोर्ट</span><span class="text-[11px] text-slate-500">कौन सा टॉपिक कितना तैयार</span></a>
    </div>
    <div class="mb-3 rounded-xl bg-slate-100 p-3 text-xs leading-relaxed text-slate-600 dark:bg-slate-800 dark:text-slate-300">
      ✏️ से विषय/टॉपिक का नाम <b>सभी सेट में</b> बदलें (दूसरे विषय/टॉपिक का नाम देने पर दोनों एक हो जाते हैं)। ☐ से प्रश्न चुनकर उन्हें <b>किसी भी सेट के किसी विषय/टॉपिक में जोड़ें</b> या उनका विषय/टॉपिक/कॉन्सेप्ट बदलें।</div>`}
    <input class="input mb-3" type="search" placeholder="🔍 प्रश्न खोजें…" data-search>
    <div class="space-y-2 pb-20" data-list></div>
    <div class="fixed inset-x-0 bottom-[60px] z-30 hidden px-3" data-bar>
      <div class="mx-auto flex max-w-3xl items-center gap-2 rounded-2xl border border-slate-200 bg-white p-2 shadow-lg dark:border-slate-700 dark:bg-slate-900">
        <span class="px-2 text-sm font-bold" data-n></span>
        <button class="btn-primary btn-sm flex-1" data-add>➕ ${target ? 'इस सेट में जोड़ें' : 'सेट में जोड़ें'}</button>
        ${target ? '' : '<button class="btn-outline btn-sm" data-retag>🏷️ बदलें</button>'}
        <button class="btn-ghost btn-sm !px-2" data-clear aria-label="Clear">✕</button>
      </div>
    </div>`;
  const $ = (s) => root.querySelector(s);
  const list = $('[data-list]');

  const badge = (key) => {
    const r = stats.get(key);
    if (!r) return '<span class="text-slate-400">नया</span>';
    const dots = Array.from({ length: MASTERED_AT }, (_, i) => `<span class="inline-block h-2 w-2 rounded-full ${i < r.correct ? 'bg-emerald-500' : 'bg-slate-300 dark:bg-slate-600'}"></span>`).join('');
    return `<span class="inline-flex items-center gap-0.5">${dots}</span>${r.wrong ? `<span class="text-rose-600">✗${r.wrong}</span>` : ''}`;
  };
  const qRow = (it) => `<label class="flex items-start gap-2 rounded-lg border border-slate-100 p-2 text-sm dark:border-slate-800">
      <input type="checkbox" class="mt-1 h-4 w-4 shrink-0" data-cq="${esc(it.key)}" ${sel.has(it.key) ? 'checked' : ''}>
      <span class="min-w-0 flex-1"><span class="line-clamp-2">${esc(qText(it.q))}</span>
        <span class="mt-0.5 flex flex-wrap items-center gap-x-2 text-[11px] text-slate-500"><span class="truncate">${esc(it.set.meta.title)} • Q${it.q.q_no}</span>${it.concept ? `<span class="text-violet-600">💡 ${esc(it.concept)}</span>` : ''}<span class="ml-auto flex items-center gap-1">${badge(it.key)}</span></span></span>
    </label>`;

  const topicHtml = (s, si, t, ti) => {
    const id = `${s.key}|${t.name}`;
    const tagged = t.concepts.filter((c) => c.name !== NO_CONCEPT).length;
    return `<details class="rounded-xl border border-slate-200 dark:border-slate-700" data-open="${esc(id)}" ${open.has(id) ? 'open' : ''}>
      <summary class="flex cursor-pointer items-center gap-2 px-2 py-2 text-sm">
        <input type="checkbox" class="h-4 w-4 shrink-0" data-ct="${si}|${ti}">
        <span class="min-w-0 flex-1 font-medium">${esc(t.name)}${tagged ? ` <span class="text-[11px] text-violet-600">💡${tagged}</span>` : ''}</span>
        <span class="text-xs text-slate-500">${t.items.length}</span>
        ${search ? '' : `<button class="btn-ghost btn-sm !px-1.5" data-rt="${si}|${ti}" aria-label="टॉपिक का नाम बदलें">✏️</button>`}
      </summary>
      <div class="space-y-1 px-2 pb-2" data-qs="${si}|${ti}"></div>
    </details>`;
  };
  const subjectHtml = (s, si) => `<details class="card !p-3" data-open="${esc(s.key)}" ${open.has(s.key) || search ? 'open' : ''}>
      <summary class="flex cursor-pointer items-center gap-2">
        <input type="checkbox" class="h-4 w-4 shrink-0" data-cs="${si}">
        <span class="text-lg">${s.icon}</span>
        <span class="min-w-0 flex-1"><span class="block font-bold leading-tight">${esc(s.label)}</span>
          <span class="block text-[11px] text-slate-500">${s.items.length} प्रश्न • ${s.topics.length} टॉपिक${s.names.length > 1 ? ` • नाम: ${esc(s.names.slice(0, 4).join(', '))}` : ''}</span></span>
        ${search ? '' : `<button class="btn-ghost btn-sm !px-2" data-rs="${si}" aria-label="विषय का नाम बदलें">✏️</button>`}
      </summary>
      <div class="mt-2 space-y-1.5">${s.topics.map((t, ti) => topicHtml(s, si, t, ti)).join('')}</div>
    </details>`;

  const fillQs = (box) => {
    const [si, ti] = box.dataset.qs.split('|').map(Number);
    const t = groups[si].topics[ti];
    const n = Number(box.dataset.shown || 0);
    const next = t.items.slice(n, n + PAGE);
    box.querySelector('[data-more]')?.remove();
    box.insertAdjacentHTML('beforeend', next.map(qRow).join('') + (n + next.length < t.items.length ? `<button class="btn-outline btn-sm w-full" data-more>और दिखाएँ (${t.items.length - n - next.length})</button>` : ''));
    box.dataset.shown = n + next.length;
  };
  const sync = () => {
    root.querySelectorAll('[data-cq]').forEach((c) => { c.checked = sel.has(c.dataset.cq); });
    const state = (arr) => { const k = arr.filter((it) => sel.has(it.key)).length; return { all: k && k === arr.length, some: k > 0 && k < arr.length }; };
    root.querySelectorAll('[data-ct]').forEach((c) => { const [si, ti] = c.dataset.ct.split('|').map(Number); const st = state(groups[si].topics[ti].items); c.checked = !!st.all; c.indeterminate = st.some; });
    root.querySelectorAll('[data-cs]').forEach((c) => { const st = state(groups[Number(c.dataset.cs)].items); c.checked = !!st.all; c.indeterminate = st.some; });
    $('[data-bar]').classList.toggle('hidden', !sel.size);
    $('[data-n]').textContent = `${sel.size} चुने`;
  };
  const draw = () => {
    const words = search.toLowerCase().split(/\s+/).filter(Boolean);
    const shown = words.length ? items.filter((it) => { const h = `${qText(it.q)} ${it.topic} ${it.concept}`.toLowerCase(); return words.every((w) => h.includes(w)); }) : items;
    groups = groupBank(shown);
    list.innerHTML = groups.length ? groups.map(subjectHtml).join('') : '<div class="card py-6 text-center text-sm text-slate-500">कोई प्रश्न नहीं मिला</div>';
    list.querySelectorAll('details[open] [data-qs]').forEach((b) => { if (b.closest('details').open) fillQs(b); });
    sync();
  };
  draw();

  list.addEventListener('toggle', (e) => {
    const d = e.target;
    if (!(d instanceof HTMLDetailsElement) || !d.dataset.open) return;
    if (d.open) open.add(d.dataset.open); else open.delete(d.dataset.open);
    const box = d.querySelector(':scope > [data-qs]');
    if (d.open && box && !box.dataset.shown) fillQs(box);
  }, true);
  $('[data-search]').addEventListener('input', debounce((e) => { search = e.target.value.trim(); draw(); }, 250));

  const picked = () => items.filter((it) => sel.has(it.key));
  root.addEventListener('change', (e) => {
    const c = e.target;
    const flip = (arr) => { for (const it of arr) if (c.checked) sel.add(it.key); else sel.delete(it.key); };
    if (c.dataset.cq) { if (c.checked) sel.add(c.dataset.cq); else sel.delete(c.dataset.cq); } else if (c.dataset.ct) { const [si, ti] = c.dataset.ct.split('|').map(Number); flip(groups[si].topics[ti].items); } else if (c.dataset.cs) flip(groups[Number(c.dataset.cs)].items);
    else return;
    sync();
  });

  root.addEventListener('click', async (e) => {
    const t = (s) => e.target.closest(s);
    let b;
    if (t('summary input[type=checkbox]')) { e.stopPropagation(); return; } // tick without opening
    if ((b = t('[data-more]'))) { fillQs(b.closest('[data-qs]')); return; }
    if ((b = t('[data-rs]'))) {
      e.preventDefault();
      const s = groups[Number(b.dataset.rs)];
      const to = await promptDialog({ title: 'विषय का नाम बदलें (सभी सेट में)', label: `"${s.label}" का नया नाम`, value: s.label, suggest: allSubjects.filter((x) => x !== s.label), hint: `इस समूह के सभी नाम (${esc(s.names.join(', '))}) हर सेट में बदल जाएँगे। किसी दूसरे विषय का नाम देंगे तो दोनों एक हो जाएँगे।` });
      if (!to || to === s.label) return;
      const r = await renameSubjectEverywhere(s.key, to);
      toast(`${r.n} प्रश्न, ${r.sets} सेट में विषय "${to}" हुआ`, 'success', 3000);
      refresh();
      return;
    }
    if ((b = t('[data-rt]'))) {
      e.preventDefault();
      const [si, ti] = b.dataset.rt.split('|').map(Number);
      const s = groups[si], tp = s.topics[ti];
      const to = await promptDialog({ title: 'टॉपिक का नाम बदलें (सभी सेट में)', label: `${s.label} › "${tp.name}" का नया नाम`, value: tp.name, suggest: s.topics.map((x) => x.name).filter((x) => x !== tp.name), hint: 'इसी विषय के किसी दूसरे टॉपिक का नाम देंगे तो दोनों टॉपिक एक हो जाएँगे।' });
      if (!to || to === tp.name) return;
      const r = await renameTopicEverywhere(s.key, tp.name, to);
      toast(`${r.n} प्रश्नों का टॉपिक "${to}" हुआ`, 'success', 3000);
      refresh();
      return;
    }
    if (t('[data-clear]')) { sel.clear(); sync(); return; }
    if (t('[data-add]')) { addDialog(picked()); return; }
    if (t('[data-retag]')) { retagDialog(picked()); }
  });

  function addDialog(chosen) {
    if (!chosen.length) return;
    const topicsOf = (set) => [...new Set((set?.questions || []).map((q) => q.topic))];
    const md = modal({
      title: `➕ ${chosen.length} प्रश्न जोड़ें`,
      body: `<div class="space-y-3">
        <div><label class="label">किस सेट में</label>
          <select class="input" name="set">${target ? '' : '<option value="__new">🆕 नया सेट बनाएँ…</option>'}${sets.map((s) => `<option value="${esc(s.id)}" ${target?.id === s.id ? 'selected' : ''}>${esc(s.meta.title)} (${s.questions.length})</option>`).join('')}</select></div>
        <div data-newtitle class="${target || sets.length ? 'hidden' : ''}"><label class="label">नए सेट का नाम</label><input class="input" name="title" placeholder="जैसे: CTET बाल विकास अभ्यास"></div>
        <div><label class="label">किस विषय में <span class="normal-case font-normal">(खाली = प्रश्न का अपना विषय)</span></label><input class="input" name="section" list="bk-secs" value="${esc(query.tsec || '')}" autocomplete="off"><datalist id="bk-secs"></datalist></div>
        <div><label class="label">किस टॉपिक में <span class="normal-case font-normal">(खाली = प्रश्न का अपना टॉपिक)</span></label><input class="input" name="topic" list="bk-tops" autocomplete="off"><datalist id="bk-tops"></datalist></div>
        <div><label class="label">कॉन्सेप्ट <span class="normal-case font-normal">(वैकल्पिक)</span></label><input class="input" name="concept" placeholder="जैसे: पियाजे — संज्ञानात्मक विकास की अवस्थाएँ"></div>
        <div class="text-xs text-slate-500">मूल प्रश्न अपनी जगह रहेंगे; यहाँ उनकी कॉपी जुड़ेगी। कॉपी पर दिया उत्तर मूल प्रश्न के रिकॉर्ड में ही गिना जाता है।</div>
      </div>`,
      footer: '<button class="btn-outline" data-close>Cancel</button><button class="btn-primary" data-go>जोड़ें</button>',
    });
    const f = (n) => md.el.querySelector(`[name=${n}]`);
    const fillLists = () => {
      const s = sets.find((x) => x.id === f('set').value);
      md.el.querySelector('#bk-secs').innerHTML = [...new Set([...(s?.sections || []), ...allSubjects])].map((x) => `<option value="${esc(x)}"></option>`).join('');
      md.el.querySelector('#bk-tops').innerHTML = [...new Set([...topicsOf(s), ...allTopics])].slice(0, 400).map((x) => `<option value="${esc(x)}"></option>`).join('');
      md.el.querySelector('[data-newtitle]').classList.toggle('hidden', f('set').value !== '__new');
    };
    f('set').onchange = fillLists;
    fillLists();
    md.el.querySelector('[data-go]').onclick = async () => {
      const isNew = f('set').value === '__new';
      if (isNew && !f('title').value.trim()) { toast('नए सेट का नाम लिखें', 'warn'); return; }
      const r = await copyItemsToSet(chosen, { setId: isNew ? null : f('set').value, newTitle: f('title').value, section: f('section').value, topic: f('topic').value, concept: f('concept').value });
      md.close();
      toast(`${r.added} प्रश्न "${r.set.meta.title}" में जुड़े${r.skipped ? ` • ${r.skipped} पहले से थे` : ''}`, 'success', 3500);
      sel.clear();
      navigate(`set/${encodeURIComponent(r.set.id)}`);
    };
  }

  function retagDialog(chosen) {
    if (!chosen.length) return;
    const md = modal({
      title: `🏷️ ${chosen.length} प्रश्नों का विषय / टॉपिक / कॉन्सेप्ट बदलें`,
      body: `<div class="space-y-3">
        <div><label class="label">नया विषय <span class="normal-case font-normal">(खाली = न बदलें)</span></label><input class="input" name="subject" list="rt-secs" autocomplete="off"><datalist id="rt-secs">${allSubjects.map((x) => `<option value="${esc(x)}"></option>`).join('')}</datalist></div>
        <div><label class="label">नया टॉपिक <span class="normal-case font-normal">(खाली = न बदलें)</span></label><input class="input" name="topic" list="rt-tops" autocomplete="off"><datalist id="rt-tops">${allTopics.slice(0, 400).map((x) => `<option value="${esc(x)}"></option>`).join('')}</datalist></div>
        <div><label class="label">कॉन्सेप्ट <span class="normal-case font-normal">(खाली = न बदलें)</span></label><input class="input" name="concept" placeholder="जैसे: समास — द्वंद्व और बहुव्रीहि में अंतर"></div>
        <label class="flex items-center gap-2 text-sm"><input type="checkbox" class="h-4 w-4" name="clearc"> कॉन्सेप्ट हटाएँ</label>
        <div class="text-xs text-slate-500">बदलाव उसी सेट में होगा जिसमें प्रश्न है (उसका विषय-टैब भी बदल जाएगा)।</div>
      </div>`,
      footer: '<button class="btn-outline" data-close>Cancel</button><button class="btn-primary" data-go>Save</button>',
    });
    const f = (n) => md.el.querySelector(`[name=${n}]`);
    md.el.querySelector('[data-go]').onclick = async () => {
      const patch = { subject: f('subject').value, topic: f('topic').value, concept: f('clearc').checked ? null : f('concept').value };
      if (!patch.subject.trim() && !patch.topic.trim() && patch.concept !== null && !String(patch.concept).trim()) { toast('कुछ बदलने के लिए लिखें', 'warn'); return; }
      const n = await retagItems(chosen, patch);
      md.close();
      toast(`${n} प्रश्न अपडेट हुए`, 'success');
      refresh();
    };
  }
}
