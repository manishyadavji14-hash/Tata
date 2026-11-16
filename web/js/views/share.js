import { shareableSets, subjectsOf, topicsOf, buildBundle, shareFile, shareMessage, openTelegram, telegramCard, TELEGRAM_URL } from '../share.js';
import { subjectKey } from '../practice.js';
import { toast, emptyState } from '../ui.js';
import { esc, copyText, downloadText } from '../util.js';

export async function render({ root, query, setHeader }) {
  setHeader({ title: 'टेस्ट शेयर करें / Share', sub: 'एक या कई टेस्ट, विषय या टॉपिक — किसी भी ऐप से', back: 'home' });
  const sets = await shareableSets();
  if (!sets.length) { root.innerHTML = telegramCard() + emptyState('शेयर करने को कोई टेस्ट नहीं', 'पहले कोई प्रैक्टिस सेट Import करें।', '<a href="#/import" class="btn-primary mt-2">Import</a>'); wire(); return; }

  const st = {
    setIds: new Set(query.set ? [query.set] : []),
    what: 'whole', // whole | filter
    subjects: new Set(), topics: new Set(),
    combine: false, count: 0, shuffled: true, title: '', pos: 1, neg: 0, minutes: '',
  };
  if (query.set && !sets.some((s) => s.id === query.set)) st.setIds.clear();

  function chosen() { return sets.filter((s) => st.setIds.has(s.id)); }

  function draw() {
    const cs = chosen();
    const subs = subjectsOf(cs.length ? cs : sets);
    const tops = st.subjects.size ? topicsOf(cs, [...st.subjects]) : [];
    let n = 0;
    for (const s of cs) for (const q of s.questions) {
      if (st.what === 'filter') {
        if (st.subjects.size && !st.subjects.has(subjectKey(q.section))) continue;
        if (st.topics.size && !st.topics.has(String(q.topic || 'अन्य').trim().toLowerCase())) continue;
      }
      n++;
    }
    const combineN = st.combine && st.count > 0 ? Math.min(st.count, n) : n;
    const chip = (on, attrs, label) => `<button class="chip ${on ? 'bg-brand-600 text-white' : 'bg-slate-100 text-slate-700 dark:bg-slate-800 dark:text-slate-200'} !py-1.5" ${attrs}>${label}</button>`;
    root.innerHTML = `
      ${telegramCard()}
      <div class="card mb-3">
        <div class="mb-2 flex items-center justify-between"><div class="font-bold">1️⃣ पेपर चुनें</div>
          <button class="text-xs font-semibold text-brand-600" data-all>${st.setIds.size === sets.length ? 'सब हटाएँ' : 'सभी चुनें'}</button></div>
        <div class="max-h-72 space-y-1 overflow-y-auto">${sets.map((s) => `
          <label class="flex items-center gap-3 rounded-lg p-2 text-sm hover:bg-slate-50 dark:hover:bg-slate-800">
            <input type="checkbox" class="h-5 w-5 accent-brand-600" data-set="${esc(s.id)}" ${st.setIds.has(s.id) ? 'checked' : ''}>
            <span class="min-w-0 flex-1"><span class="block truncate font-semibold">${esc(s.meta.title)}</span><span class="text-[11px] text-slate-500">${s.questions.length} प्रश्न</span></span>
          </label>`).join('')}</div>
      </div>
      <div class="card mb-3">
        <div class="mb-2 font-bold">2️⃣ क्या भेजें?</div>
        <div class="grid grid-cols-2 gap-2">
          <button class="tab !py-2 ${st.what === 'whole' ? 'tab-on' : 'tab-off'}" data-what="whole">पूरे पेपर</button>
          <button class="tab !py-2 ${st.what === 'filter' ? 'tab-on' : 'tab-off'}" data-what="filter">विषय / टॉपिक चुनें</button>
        </div>
        ${st.what === 'filter' ? `
          <div class="mt-3 text-xs font-semibold text-slate-500">विषय (एक या अधिक)</div>
          <div class="mt-1 flex flex-wrap gap-1.5">${subs.map((s) => chip(st.subjects.has(s.key), `data-sub="${esc(s.key)}"`, `${esc(s.label)} <span class="opacity-70">${s.count}</span>`)).join('')}</div>
          ${tops.length ? `<div class="mt-3 text-xs font-semibold text-slate-500">टॉपिक (कोई न चुनें = उस विषय के सभी)</div>
          <div class="mt-1 flex max-h-48 flex-wrap gap-1.5 overflow-y-auto">${tops.map((t) => chip(st.topics.has(t.key), `data-top="${esc(t.key)}"`, `${esc(t.label)} <span class="opacity-70">${t.count}</span>`)).join('')}</div>` : ''}` : ''}
      </div>
      <div class="card mb-3">
        <div class="mb-2 font-bold">3️⃣ कैसे भेजें?</div>
        <div class="grid grid-cols-2 gap-2">
          <button class="tab !py-2 ${!st.combine ? 'tab-on' : 'tab-off'}" data-comb="0">हर पेपर अलग</button>
          <button class="tab !py-2 ${st.combine ? 'tab-on' : 'tab-off'}" data-comb="1">सबको मिलाकर 1 टेस्ट</button>
        </div>
        ${st.combine ? `<div class="mt-3 grid grid-cols-2 gap-2">
          <div class="col-span-2"><label class="label">टेस्ट का नाम</label><input class="input" data-f="title" value="${esc(st.title)}" placeholder="जैसे: हिंदी व्याकरण मिक्स टेस्ट"></div>
          <div><label class="label">प्रश्न संख्या (0 = सभी ${n})</label><input type="number" min="0" class="input" data-f="count" value="${st.count || ''}"></div>
          <div><label class="label">समय (मिनट, खाली = स्वतः)</label><input type="number" min="1" class="input" data-f="minutes" value="${esc(st.minutes)}"></div>
          <div><label class="label">सही पर अंक</label><input type="number" step="0.25" class="input" data-f="pos" value="${st.pos}"></div>
          <div><label class="label">गलत पर कटौती</label><input type="number" step="0.25" class="input" data-f="neg" value="${st.neg}"></div>
          <label class="col-span-2 flex items-center gap-3 text-sm"><input type="checkbox" class="h-5 w-5 accent-brand-600" data-f="shuffled" ${st.shuffled ? 'checked' : ''}> प्रश्न Shuffle करें</label>
        </div>` : '<div class="mt-2 text-xs text-slate-500">पाने वाले के पास हर पेपर अलग टेस्ट बनेगा (पूरा पेपर हो तो उसकी अपनी कॉपी से Merge भी हो सकता है)।</div>'}
      </div>
      <div class="card sticky bottom-20 border-brand-300 dark:border-brand-800">
        <div class="mb-2 text-sm"><b>${cs.length}</b> पेपर • <b>${st.combine ? combineN : n}</b> प्रश्न${st.combine ? ' → 1 टेस्ट' : ''}</div>
        <button class="btn-primary w-full" data-go="share" ${n ? '' : 'disabled'}>📤 शेयर करें (Telegram, WhatsApp, Drive…)</button>
        <div class="mt-2 grid grid-cols-2 gap-2">
          <button class="btn-outline btn-sm" data-go="save" ${n ? '' : 'disabled'}>💾 फ़ाइल सेव करें</button>
          <button class="btn-outline btn-sm" data-go="copy" ${n ? '' : 'disabled'}>📋 टेक्स्ट कॉपी करें</button>
        </div>
        <div class="mt-2 text-[11px] text-slate-500">पाने वाला: फ़ाइल पर टैप करके <b>Abhyas CBT</b> चुने, या ऐप में Import → JSON → फ़ाइल चुने। कॉपी किया टेक्स्ट Import → "JSON पेस्ट करें" में चलता है।</div>
      </div>`;
    wire();
  }

  function wire() {
    root.querySelectorAll('[data-telegram]').forEach((b) => { b.onclick = openTelegram; });
  }

  root.addEventListener('change', (e) => {
    const t = e.target;
    if (t.dataset.set) { if (t.checked) st.setIds.add(t.dataset.set); else st.setIds.delete(t.dataset.set); draw(); }
    const f = t.dataset.f;
    if (f === 'shuffled') st.shuffled = t.checked;
    else if (f === 'count') { st.count = Math.max(0, parseInt(t.value, 10) || 0); draw(); }
    else if (f) st[f] = t.value;
  });

  root.addEventListener('click', async (e) => {
    const b = e.target.closest('button');
    if (!b || b.hasAttribute('data-telegram')) return;
    if (b.hasAttribute('data-all')) { if (st.setIds.size === sets.length) st.setIds.clear(); else sets.forEach((s) => st.setIds.add(s.id)); draw(); return; }
    if (b.dataset.what) { st.what = b.dataset.what; if (st.what === 'whole') { st.subjects.clear(); st.topics.clear(); } draw(); return; }
    if (b.dataset.sub) { const k = b.dataset.sub; if (st.subjects.has(k)) st.subjects.delete(k); else st.subjects.add(k); st.topics.clear(); draw(); return; }
    if (b.dataset.top) { const k = b.dataset.top; if (st.topics.has(k)) st.topics.delete(k); else st.topics.add(k); draw(); return; }
    if (b.dataset.comb) { st.combine = b.dataset.comb === '1'; draw(); return; }
    const go = b.dataset.go;
    if (!go) return;
    try {
      const { bundle, qCount, setCount, name } = await buildBundle({
        setIds: [...st.setIds], subjects: st.what === 'filter' ? [...st.subjects] : [], topics: st.what === 'filter' ? [...st.topics] : [],
        combine: st.combine, count: st.count, shuffled: st.shuffled, title: st.title, pos: st.pos, neg: st.neg, minutes: st.minutes,
      });
      const text = JSON.stringify(bundle);
      if (go === 'copy') {
        if (text.length > 900000) { toast('बहुत बड़ा है — कॉपी की जगह फ़ाइल शेयर करें', 'warn', 4000); return; }
        toast((await copyText(text)) ? `कॉपी हुआ: ${setCount} टेस्ट, ${qCount} प्रश्न` : 'कॉपी नहीं हो सका', 'success');
      } else if (go === 'save') {
        toast(`सेव हुआ → ${downloadText(name, text)}`, 'success', 4000);
      } else {
        const how = await shareFile(name, text, shareMessage(bundle));
        if (how === 'saved') toast('इस ब्राउज़र में सीधे शेयर नहीं हो सका — फ़ाइल डाउनलोड हुई, उसे Telegram में भेजें', 'info', 5000);
      }
    } catch (err) { toast(err.message, 'error', 4000); }
  });

  draw();
  return () => {};
}

export { TELEGRAM_URL };
