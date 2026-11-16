import { getSet, createAttempt } from '../store.js';
import { db, saveSettings } from '../db.js';
import { pickQuestions } from '../analytics.js';
import { esc, renderRich } from '../util.js';
import { toast } from '../ui.js';

export const MODE_LABEL = {
  full: 'पूरा टेस्ट / Full Test',
  wrong: 'गलत प्रश्न / Incorrect only',
  skipped: 'छोड़े गए प्रश्न / Unattempted only',
  wrong_skipped: 'गलत + छोड़े गए / Incorrect + Unattempted',
  weak: 'कमजोर टॉपिक / Weak topics',
  traps: 'टाइम ट्रैप प्रश्न / Time traps',
};

export async function render({ root, params, query, settings, navigate, setHeader }) {
  const set = await getSet(params[0]);
  if (!set) { root.innerHTML = '<div class="card">सेट नहीं मिला</div>'; return; }
  const mode = query.mode && MODE_LABEL[query.mode] ? query.mode : 'full';
  let qNos = set.questions.map((q) => q.q_no);
  let parent = null;
  if (mode !== 'full') {
    parent = query.from ? await db.get('attempts', query.from) : null;
    if (!parent?.result) { root.innerHTML = '<div class="card">पिछला प्रयास नहीं मिला</div>'; return; }
    qNos = pickQuestions(parent.result, mode);
  }
  setHeader({ title: 'टेस्ट निर्देश / Instructions', sub: set.meta.title, back: parent ? `result/${parent.id}` : set.kind === 'practice' ? 'practice' : `set/${set.id}` });
  if (!qNos.length) {
    root.innerHTML = `<div class="card text-center"><div class="text-3xl">🎉</div><div class="mt-2 font-semibold">इस श्रेणी में कोई प्रश्न नहीं है!</div><a href="#/result/${parent.id}" class="btn-primary mt-3">वापस</a></div>`;
    return;
  }
  const total = set.questions.length;
  const defMin = mode === 'full' ? set.meta.duration_minutes : Math.max(2, Math.round((set.meta.duration_minutes * qNos.length) / total));
  const qs = set.questions.filter((q) => qNos.includes(q.q_no));
  const secCounts = set.sections.map((s) => [s, qs.filter((q) => q.section === s).length]).filter(([, n]) => n);
  const lang = settings.lang || 'both';
  const instr = [set.meta.instructions_hi, set.meta.instructions_en].filter(Boolean).map((t) => `<div class="rich">${renderRich(t)}</div>`).join('');

  root.innerHTML = `
    <div class="card mb-3">
      <div class="mb-1 text-xs font-semibold uppercase text-brand-600">${MODE_LABEL[mode]}</div>
      <h2 class="text-lg font-bold leading-snug">${esc(set.meta.title)}</h2>
      <div class="mt-3 grid grid-cols-3 gap-2">
        <div class="stat"><div class="stat-v">${qNos.length}</div><div class="stat-l">कुल प्रश्न</div></div>
        <div class="stat"><div class="stat-v" data-maxmarks>${qNos.length * set.meta.positive_marks}</div><div class="stat-l">अधिकतम अंक</div></div>
        <div class="stat"><div class="stat-v" data-hm></div><div class="stat-l">समय</div></div>
      </div>
    </div>

    <div class="card mb-3 space-y-3">
      <div class="text-sm font-bold">⚙️ टेस्ट सेटिंग / Test settings</div>
      <div class="grid grid-cols-3 gap-2">
        <div><label class="label">समय (मिनट)</label><input class="input" type="number" min="1" max="600" name="dur" value="${defMin}"></div>
        <div><label class="label">+ सही</label><input class="input" type="number" step="0.01" min="0" name="pos" value="${set.meta.positive_marks}"></div>
        <div><label class="label">− गलत</label><input class="input" type="number" step="0.01" min="0" name="neg" value="${set.meta.negative_marks}"></div>
      </div>
      <div class="flex flex-wrap gap-2" data-neg-presets>
        ${[0, 0.25, 0.33, 0.5, 1].map((n) => `<button class="tab tab-off" data-neg="${n}">−${n}</button>`).join('')}
      </div>
      <div><label class="label">प्रश्न की भाषा / Question language</label>
        <div class="grid grid-cols-3 gap-2" data-langs>
          ${[['hi', 'हिंदी'], ['en', 'English'], ['both', 'दोनों / Both']].map(([v, l]) => `<button class="tab ${v === lang ? 'tab-on' : 'tab-off'}" data-lang="${v}">${l}</button>`).join('')}
        </div>
      </div>
    </div>

    <div class="card mb-3">
      <div class="mb-2 text-sm font-bold">📚 सेक्शन / Sections</div>
      <div class="divide-y divide-slate-100 text-sm dark:divide-slate-800">${secCounts.map(([s, n]) => `<div class="flex justify-between py-1.5"><span>${esc(s)}</span><span class="font-semibold">${n}</span></div>`).join('')}</div>
    </div>

    <div class="card mb-3 text-sm">
      <div class="mb-2 font-bold">📋 निर्देश / Instructions</div>
      ${instr}
      <ul class="mt-2 list-disc space-y-1 pl-5 text-slate-600 dark:text-slate-300">
        <li>ऊपर टाइमर उल्टी गिनती दिखाएगा; समय समाप्त होने पर टेस्ट अपने-आप सबमिट हो जाएगा।</li>
        <li>विकल्प चुनते ही उत्तर सेव हो जाता है। <b>Save &amp; Next</b> से अगले प्रश्न पर जाएँ, <b>Clear Response</b> से उत्तर हटाएँ।</li>
        <li>ऐप बैकग्राउंड में जाने पर टेस्ट अपने-आप पॉज़ होता है और बाद में वहीं से शुरू किया जा सकता है।</li>
        <li>बाएँ/दाएँ स्वाइप करके भी प्रश्न बदल सकते हैं।</li>
      </ul>
      <div class="mt-3 grid grid-cols-1 gap-2 text-xs xs:grid-cols-2">
        <div class="flex items-center gap-2"><span class="pal pal-answered !h-7 !w-7 text-xs">1</span> उत्तर दिया / Answered</div>
        <div class="flex items-center gap-2"><span class="pal pal-notanswered !h-7 !w-7 text-xs">2</span> उत्तर नहीं दिया / Not Answered</div>
        <div class="flex items-center gap-2"><span class="pal pal-notvisited !h-7 !w-7 text-xs">3</span> देखा नहीं / Not Visited</div>
        <div class="flex items-center gap-2"><span class="pal pal-marked !h-7 !w-7 text-xs">4</span> समीक्षा हेतु / Marked for Review</div>
        <div class="flex items-center gap-2"><span class="pal pal-ansmarked tick !h-7 !w-7 text-xs">5</span> उत्तर + समीक्षा / Answered &amp; Marked</div>
      </div>
    </div>

    <label class="mb-3 flex items-start gap-3 px-1 text-sm">
      <input type="checkbox" name="agree" class="mt-1 h-5 w-5 accent-brand-600" checked>
      <span>मैंने सभी निर्देश पढ़ लिए हैं। / I have read all the instructions.</span>
    </label>
    <button class="btn-primary w-full !py-3.5 text-base" data-start>टेस्ट शुरू करें / Start Test →</button>`;

  let chosenLang = lang;
  const $in = (n) => root.querySelector(`[name=${n}]`);
  const upd = () => {
    const d = Math.max(1, Number($in('dur').value) || defMin);
    root.querySelector('[data-hm]').textContent = d >= 60 ? `${Math.floor(d / 60)}:${String(d % 60).padStart(2, '0')} घं.` : `${d} मि.`;
    root.querySelector('[data-maxmarks]').textContent = Math.round(qNos.length * (Number($in('pos').value) || 0) * 100) / 100;
  };
  upd();
  root.addEventListener('input', upd);
  root.addEventListener('click', async (e) => {
    const n = e.target.closest('[data-neg]');
    if (n) { $in('neg').value = n.dataset.neg; upd(); }
    const l = e.target.closest('[data-lang]');
    if (l) {
      chosenLang = l.dataset.lang;
      root.querySelectorAll('[data-lang]').forEach((b) => { b.classList.toggle('tab-on', b === l); b.classList.toggle('tab-off', b !== l); });
    }
    if (e.target.closest('[data-start]')) {
      if (!$in('agree').checked) { toast('पहले निर्देश स्वीकार करें', 'warn'); return; }
      const attempt = createAttempt(set, {
        qNos,
        durationMin: Math.max(1, Number($in('dur').value) || defMin),
        pos: Math.max(0, Number($in('pos').value) || 0),
        neg: Math.max(0, Number($in('neg').value) || 0),
        lang: chosenLang,
        mode,
        parentId: parent?.id || null,
        title: mode === 'full' ? set.meta.title : `${set.meta.title} — ${MODE_LABEL[mode].split(' / ')[0]}`,
      });
      await db.put('attempts', attempt);
      await saveSettings({ lang: chosenLang });
      navigate(`exam/${attempt.id}`, { replace: true });
    }
  });
}
