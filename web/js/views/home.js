import { listSets, inProgressAttempts, attemptsForSet } from '../store.js';
import { EXTERNAL_EXTRACTION_PROMPT } from '../prompts.js';
import { icon, toast, emptyState, modal, promptDialog } from '../ui.js';
import { saveSettings } from '../db.js';
import { backfillMistakes, MASTER_AFTER } from '../practice.js';
import { backfillQStats, bankItems, statsMap, groupBank, cylinderHtml, MASTERED_AT } from '../qstats.js';
import { ensureDaily, DAILY_SIZES } from '../daily.js';
import { renameSet } from '../bank.js';
import { refresh } from '../app.js';
import { esc, copyText, fmtDate, fmtClock } from '../util.js';
import { openTelegram } from '../share.js';

export async function copyPrompt() {
  const ok = await copyText(EXTERNAL_EXTRACTION_PROMPT);
  toast(ok ? 'AI Extraction Prompt कॉपी हो गया ✓ अब इसे PDF के साथ किसी AI को दें' : 'कॉपी नहीं हो सका — Import पेज पर प्रॉम्प्ट देखें', ok ? 'success' : 'error', 3500);
}

export async function render({ root, setHeader, settings }) {
  setHeader({ title: 'Abhyas CBT', sub: 'ऑफ़लाइन मॉक टेस्ट • PDF/JSON से प्रैक्टिस सेट' });
  try { await backfillMistakes(); await backfillQStats(); } catch (e) { console.error(e); }
  let daily = [];
  try { daily = await ensureDaily(); } catch (e) { console.error('daily failed', e); }
  const [sets, running, items, stats] = await Promise.all([listSets(), inProgressAttempts(), bankItems(), statsMap()]);
  const lastScores = {};
  for (const s of sets) {
    const done = (await attemptsForSet(s.id)).filter((a) => a.state === 'submitted' && a.mode === 'full');
    if (done[0]?.result) lastScores[s.id] = done[0].result;
  }
  const dailyAtt = {};
  for (const s of daily) dailyAtt[s.id] = (await attemptsForSet(s.id))[0] || null;
  const mastered = items.filter((it) => (stats.get(it.key)?.correct || 0) >= MASTERED_AT).length;
  const seen = items.filter((it) => stats.get(it.key)?.seen).length;

  root.innerHTML = `
    ${items.length ? `<a href="#/mastery" class="card mb-3 block !p-3 active:scale-[.99]">
      <div class="mb-2 flex items-center gap-2"><span class="min-w-0 flex-1 text-sm font-bold">🛢️ ${MASTERED_AT}+ बार सही हल किए प्रश्न</span><span class="text-xs font-semibold text-brand-600">तैयारी देखें ›</span></div>
      ${cylinderHtml(mastered, items.length)}
      <div class="mt-1.5 text-[11px] text-slate-500">कुल ${items.length} प्रश्न • ${seen} कभी हल किए • टैप करें: विषय → टॉपिक → कॉन्सेप्ट, कौन कितना तैयार</div>
    </a>` : ''}

    ${items.length ? `<section class="card mb-3 !p-3" data-daily>
      <div class="flex items-center gap-2"><span class="min-w-0 flex-1"><span class="block text-sm font-bold">📅 आज का अभ्यास</span>
        <span class="block text-[11px] text-slate-500">${settings.dailyOn === false ? 'बंद है' : `पिछली गलतियों से • ${settings.dailyCount || 1} सेट × ${settings.dailySize || 20} प्रश्न • रात 12 बजे एक्सपायर`}</span></span>
        <button class="btn-ghost btn-sm" data-dailycfg>⚙️ सेट करें</button></div>
      ${settings.dailyOn === false ? '' : daily.length ? `<div class="mt-2 space-y-2">${daily.map((s) => {
        const a = dailyAtt[s.id];
        const w = s.dailyWhy || {};
        const tags = [w.mistake ? `❌ ${w.mistake} गलत हुए` : '', w.concept + w.topic ? `🔗 ${w.concept + w.topic} उसी टॉपिक से` : '', w.weak ? `⚠️ ${w.weak} कमजोर` : '', w.new + w.revise ? `🆕 ${w.new + w.revise} नए` : ''].filter(Boolean);
        const action = a?.state === 'submitted' && a.result
          ? `<a href="#/result/${encodeURIComponent(a.id)}" class="text-right"><div class="font-bold text-emerald-600">${a.result.score}/${a.result.maxScore}</div><div class="text-[10px] text-slate-500">✓ हो गया</div></a>`
          : a ? `<a href="#/exam/${encodeURIComponent(a.id)}" class="btn-primary btn-sm">Resume</a>`
            : `<a href="#/pretest/${encodeURIComponent(s.id)}" class="btn-primary btn-sm">▶ शुरू</a>`;
        return `<div class="flex items-center gap-2 rounded-xl border border-slate-200 p-2 dark:border-slate-700">
          <div class="min-w-0 flex-1"><div class="truncate text-sm font-semibold">${esc(s.meta.title)}</div>
          <div class="mt-0.5 flex flex-wrap gap-x-2 text-[11px] text-slate-500">${tags.map((x) => `<span>${x}</span>`).join('')}</div></div>${action}</div>`;
      }).join('')}</div>` : '<div class="mt-2 text-xs text-slate-500">आज के लिए प्रश्न नहीं बन सके (उत्तर-कुंजी वाले प्रश्न नहीं मिले)।</div>'}
    </section>` : ''}

    <section class="mb-4 grid grid-cols-2 gap-3">
      <a href="#/bank" class="card flex flex-col gap-2 !p-3 active:scale-[.98]">
        <span class="flex h-10 w-10 items-center justify-center rounded-xl bg-teal-100 text-xl dark:bg-teal-900/40">🗃️</span>
        <span class="text-sm font-bold leading-tight">प्रश्न बैंक</span>
        <span class="text-[11px] text-slate-500">विषय/टॉपिक बदलें, दूसरे सेट में जोड़ें</span>
      </a>
      <a href="#/builder" class="card flex flex-col gap-2 !p-3 active:scale-[.98]">
        <span class="flex h-10 w-10 items-center justify-center rounded-xl bg-indigo-100 text-xl dark:bg-indigo-900/40">🧩</span>
        <span class="text-sm font-bold leading-tight">नया सेट बनाएँ</span>
        <span class="text-[11px] text-slate-500">विषय/टॉपिक से संख्या चुनकर</span>
      </a>
      <a href="#/import?tab=json" class="card flex flex-col gap-2 !p-3 active:scale-[.98]">
        <span class="flex h-10 w-10 items-center justify-center rounded-xl bg-brand-100 text-brand-700 dark:bg-brand-900/40 dark:text-brand-300">${icon.file()}</span>
        <span class="text-sm font-bold leading-tight">Import JSON Practice Set</span>
        <span class="text-[11px] text-slate-500">बिना इंटरनेट, तुरंत</span>
      </a>
      <a href="#/import?tab=pdf" class="card flex flex-col gap-2 !p-3 active:scale-[.98]">
        <span class="flex h-10 w-10 items-center justify-center rounded-xl bg-violet-100 text-violet-700 dark:bg-violet-900/40 dark:text-violet-300">${icon.pdf()}</span>
        <span class="text-sm font-bold leading-tight">PDF अपलोड (AI)</span>
        <span class="text-[11px] text-slate-500">API Key से सीधे टेस्ट बनाएँ</span>
      </a>
      <a href="#/import?tab=ai" class="card col-span-2 flex items-center gap-3 !p-3 active:scale-[.99]">
        <span class="flex h-10 w-10 shrink-0 items-center justify-center rounded-xl bg-fuchsia-100 text-xl dark:bg-fuchsia-900/40">✨</span>
        <span class="min-w-0 flex-1"><span class="block text-sm font-bold">AI से प्रश्न बनाएँ (Custom)</span>
        <span class="block text-[11px] text-slate-500">बुक/नोट्स/PDF/फ़ोटो दें → मनचाहे प्रकार के प्रश्न: टेबल, चित्र, कथन, मिलान…</span></span>
      </a>
      <a href="#/jobs" class="card col-span-2 flex items-center gap-3 !p-3 active:scale-[.99]" data-jobscard>
        <span class="flex h-10 w-10 shrink-0 items-center justify-center rounded-xl bg-brand-100 text-xl dark:bg-brand-900/40">🗂️</span>
        <span class="min-w-0 flex-1"><span class="block text-sm font-bold">AI काम / Jobs</span>
        <span class="block text-[11px] text-slate-500" data-jobsinfo>बैकग्राउंड में चल रहे काम, स्थिति और इतिहास</span></span>
        <span class="text-slate-400">›</span>
      </a>
      <a href="#/share" class="card flex flex-col gap-2 !p-3 active:scale-[.98]">
        <span class="flex h-10 w-10 items-center justify-center rounded-xl bg-emerald-100 text-xl dark:bg-emerald-900/40">📤</span>
        <span class="text-sm font-bold leading-tight">टेस्ट शेयर करें</span>
        <span class="text-[11px] text-slate-500">पेपर, विषय या टॉपिक — किसी भी ऐप से</span>
      </a>
      <button data-telegram class="card flex flex-col gap-2 !p-3 text-left active:scale-[.98]">
        <span class="flex h-10 w-10 items-center justify-center rounded-xl bg-sky-100 text-xl dark:bg-sky-900/40">✈️</span>
        <span class="text-sm font-bold leading-tight">Telegram ग्रुप</span>
        <span class="text-[11px] text-slate-500">साथियों के टेस्ट लें / अपने भेजें</span>
      </button>
      <button data-copy-prompt class="card col-span-2 flex items-center gap-3 !p-3 text-left active:scale-[.99]">
        <span class="flex h-10 w-10 shrink-0 items-center justify-center rounded-xl bg-amber-100 text-amber-700 dark:bg-amber-900/40 dark:text-amber-300">${icon.copy()}</span>
        <span class="min-w-0 flex-1"><span class="block text-sm font-bold">Copy AI Extraction Prompt</span>
        <span class="block text-[11px] text-slate-500">ChatGPT/Claude/Gemini को PDF के साथ दें → JSON मिलेगा → Import करें</span></span>
      </button>
    </section>

    ${running.length ? `<section class="mb-4">
      <h2 class="mb-2 text-xs font-bold uppercase tracking-wide text-slate-500">अधूरे टेस्ट / Resume</h2>
      <div class="space-y-2">${running.map((a) => `
        <a href="#/exam/${encodeURIComponent(a.id)}" class="card flex items-center gap-3 !p-3 border-amber-300 dark:border-amber-700">
          <span class="text-amber-500">${icon.play2('h-8 w-8')}</span>
          <span class="min-w-0 flex-1"><span class="block truncate text-sm font-semibold">${esc(a.title)}</span>
          <span class="block text-[11px] text-slate-500">${fmtDate(a.startedAt)} • बचा समय ${fmtClock(a.remainingMs)} • ${Object.values(a.responses).filter((r) => r.sel).length}/${a.qNos.length} उत्तर</span></span>
          <span class="btn-primary btn-sm">Resume</span>
        </a>`).join('')}</div></section>` : ''}

    <section>
      <div class="mb-2 flex items-center justify-between">
        <h2 class="text-xs font-bold uppercase tracking-wide text-slate-500">प्रैक्टिस सेट / Practice Sets (${sets.length})</h2>
        <a href="#/analytics" class="text-xs font-semibold text-brand-600">${icon.chart('inline h-4 w-4')} Overall Analytics</a>
      </div>
      <div class="space-y-3">
        ${sets.length ? sets.map((s) => {
          const r = lastScores[s.id];
          const nKey = s.questions.filter((q) => q.correct_option).length;
          return `<div class="card !p-0 overflow-hidden">
            <a href="#/set/${encodeURIComponent(s.id)}" class="block p-4">
              <div class="flex items-start gap-2">
                <div class="min-w-0 flex-1">
                  <div class="font-bold leading-snug">${esc(s.meta.title)}</div>
                  <div class="mt-1 flex flex-wrap gap-x-3 gap-y-1 text-xs text-slate-500">
                    <span>${s.questions.length} प्रश्न</span><span>${s.meta.duration_minutes} मिनट</span><span>${s.meta.max_marks} अंक</span>
                    ${s.isDemo ? '<span class="chip bg-amber-100 !py-0 text-amber-800 dark:bg-amber-900/40 dark:text-amber-300">DEMO</span>' : ''}
                    ${nKey < s.questions.length ? `<span class="text-rose-500">${s.questions.length - nKey} बिना उत्तर</span>` : ''}
                  </div>
                </div>
                ${r ? `<div class="text-right"><div class="text-lg font-bold text-brand-600">${r.score}<span class="text-xs text-slate-400">/${r.maxScore}</span></div><div class="text-[10px] text-slate-500">अंतिम स्कोर</div></div>` : ''}
              </div>
              <div class="no-scrollbar mt-2 flex gap-1 overflow-x-auto">${s.sections.map((x) => `<span class="chip shrink-0 bg-slate-100 text-slate-600 dark:bg-slate-800 dark:text-slate-300">${esc(x)}</span>`).join('')}</div>
            </a>
            <div class="flex border-t border-slate-100 dark:border-slate-800">
              <a href="#/pretest/${encodeURIComponent(s.id)}" class="flex flex-1 items-center justify-center gap-1 py-2.5 text-sm font-semibold text-brand-600">${icon.play('h-4 w-4')} टेस्ट शुरू करें</a>
              <a href="#/set/${encodeURIComponent(s.id)}" class="flex flex-1 items-center justify-center gap-1 border-l border-slate-100 py-2.5 text-sm font-semibold text-slate-600 dark:border-slate-800 dark:text-slate-300">${icon.info('h-4 w-4')} विवरण</a>
              <button data-rename="${esc(s.id)}" class="flex items-center justify-center border-l border-slate-100 px-4 py-2.5 text-sm text-slate-600 dark:border-slate-800 dark:text-slate-300" aria-label="नाम बदलें">✏️</button>
            </div>
          </div>`;
        }).join('') : emptyState('कोई प्रैक्टिस सेट नहीं', 'JSON या PDF इम्पोर्ट करें, या Settings से डेमो सेट वापस लाएँ।', '<a href="#/import" class="btn-primary mt-2">Import</a>')}
      </div>
    </section>`;

  root.querySelector('[data-copy-prompt]').onclick = copyPrompt;
  root.querySelector('[data-telegram]').onclick = openTelegram;
  root.addEventListener('click', async (e) => {
    const rn = e.target.closest('[data-rename]');
    if (rn) {
      const set = sets.find((x) => x.id === rn.dataset.rename);
      const t = await promptDialog({ title: 'सेट का नाम बदलें', label: 'नया नाम', value: set.meta.title });
      if (t && t !== set.meta.title) { await renameSet(set, t); toast('नाम बदला ✓', 'success'); refresh(); }
      return;
    }
    if (e.target.closest('[data-dailycfg]')) dailySettings(settings, items);
  });
  import('../jobs.js').then(async (J) => {
    const all = await J.listJobs();
    const act = all.filter(J.isActive).length, done = all.filter((j) => j.status === 'done').length, other = all.length - act - done;
    const el = root.querySelector('[data-jobsinfo]');
    if (el && all.length) el.textContent = `${act ? `⚙️ ${act} चल रहे • ` : ''}${other ? `⏸ ${other} रुके • ` : ''}📜 ${done} पूरे`;
  });
}

// Daily practice settings: questions per set, sets per day, subjects. Saving rebuilds today's unstarted sets.
function dailySettings(settings, items) {
  const subs = groupBank(items).map((s) => ({ key: s.key, label: s.label, n: s.items.length }));
  const st = { on: settings.dailyOn !== false, size: Number(settings.dailySize) || 20, count: Number(settings.dailyCount) || 1, subj: new Set(settings.dailySubjects || []) };
  const md = modal({
    title: '📅 आज का अभ्यास — सेटिंग',
    body: `<div class="space-y-4" data-body></div>`,
    footer: '<button class="btn-outline" data-close>Cancel</button><button class="btn-primary" data-save>Save + आज का सेट दोबारा बनाएँ</button>',
  });
  const body = md.el.querySelector('[data-body]');
  const draw = () => {
    body.innerHTML = `
      <label class="flex items-center gap-2 text-sm font-semibold"><input type="checkbox" class="h-4 w-4" data-on ${st.on ? 'checked' : ''}> हर दिन अपने-आप सेट बनाएँ</label>
      <div><div class="label">हर सेट में प्रश्न</div>
        <div class="flex flex-wrap items-center gap-2">${DAILY_SIZES.map((n) => `<button class="tab ${st.size === n ? 'tab-on' : 'tab-off'}" data-size="${n}">${n}</button>`).join('')}
        <input type="number" min="1" max="300" class="input !w-24 !py-1.5" data-sizein value="${st.size}"></div></div>
      <div><div class="label">हर दिन कितने सेट</div>
        <div class="flex flex-wrap items-center gap-2">${[1, 2, 3].map((n) => `<button class="tab ${st.count === n ? 'tab-on' : 'tab-off'}" data-count="${n}">${n}</button>`).join('')}
        <input type="number" min="1" max="10" class="input !w-24 !py-1.5" data-countin value="${st.count}"></div></div>
      <div><div class="label">विषय (कोई न चुनें = सभी)</div>
        <div class="flex flex-wrap gap-2">${subs.map((s) => `<button class="tab ${st.subj.has(s.key) ? 'tab-on' : 'tab-off'}" data-subj="${esc(s.key)}">${st.subj.has(s.key) ? '✓ ' : ''}${esc(s.label)} <span class="opacity-70">${s.n}</span></button>`).join('')}</div></div>
      <div class="rounded-xl bg-slate-100 p-3 text-xs leading-relaxed text-slate-600 dark:bg-slate-800 dark:text-slate-300">
        हर दिन पहले वे प्रश्न लिए जाते हैं जो <b>पिछले दिनों गलत</b> हुए (नई गलती पहले), फिर उन्हीं <b>कॉन्सेप्ट और टॉपिक</b> के दूसरे प्रश्न, फिर आपके कमजोर टॉपिक, और जगह बचे तो नए प्रश्न।
        सेट रात 12 बजे एक्सपायर होता है और अगले दिन नई गलतियों के आधार पर फिर बनता है। कोई प्रश्न ${MASTER_AFTER} बार सही करने पर गलत-सूची से हट जाता है।</div>`;
  };
  draw();
  body.addEventListener('click', (e) => {
    const b = e.target.closest('[data-size],[data-count],[data-subj]');
    if (!b) return;
    if (b.dataset.size) st.size = Number(b.dataset.size);
    if (b.dataset.count) st.count = Number(b.dataset.count);
    if (b.dataset.subj) { if (st.subj.has(b.dataset.subj)) st.subj.delete(b.dataset.subj); else st.subj.add(b.dataset.subj); }
    draw();
  });
  body.addEventListener('change', (e) => {
    const c = e.target;
    if (c.matches('[data-on]')) st.on = c.checked;
    if (c.matches('[data-sizein]')) st.size = Math.max(1, Math.min(300, Number(c.value) || 20));
    if (c.matches('[data-countin]')) st.count = Math.max(1, Math.min(10, Number(c.value) || 1));
    draw();
  });
  md.el.querySelector('[data-save]').onclick = async () => {
    const inS = body.querySelector('[data-sizein]'), inC = body.querySelector('[data-countin]');
    st.size = Math.max(1, Math.min(300, Number(inS.value) || st.size));
    st.count = Math.max(1, Math.min(10, Number(inC.value) || st.count));
    await saveSettings({ dailyOn: st.on, dailySize: st.size, dailyCount: st.count, dailySubjects: [...st.subj] });
    await ensureDaily({ force: true });
    md.close();
    toast('आज का अभ्यास अपडेट हुआ ✓', 'success');
    refresh();
  };
}
