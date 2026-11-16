import { listSets, inProgressAttempts, attemptsForSet } from '../store.js';
import { EXTERNAL_EXTRACTION_PROMPT } from '../prompts.js';
import { icon, toast, emptyState } from '../ui.js';
import { esc, copyText, fmtDate, fmtClock } from '../util.js';
import { openTelegram } from '../share.js';

export async function copyPrompt() {
  const ok = await copyText(EXTERNAL_EXTRACTION_PROMPT);
  toast(ok ? 'AI Extraction Prompt कॉपी हो गया ✓ अब इसे PDF के साथ किसी AI को दें' : 'कॉपी नहीं हो सका — Import पेज पर प्रॉम्प्ट देखें', ok ? 'success' : 'error', 3500);
}

export async function render({ root, setHeader }) {
  setHeader({ title: 'Abhyas CBT', sub: 'ऑफ़लाइन मॉक टेस्ट • PDF/JSON से प्रैक्टिस सेट' });
  const [sets, running] = await Promise.all([listSets(), inProgressAttempts()]);
  const lastScores = {};
  for (const s of sets) {
    const done = (await attemptsForSet(s.id)).filter((a) => a.state === 'submitted' && a.mode === 'full');
    if (done[0]?.result) lastScores[s.id] = done[0].result;
  }

  root.innerHTML = `
    <section class="mb-4 grid grid-cols-2 gap-3">
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
            </div>
          </div>`;
        }).join('') : emptyState('कोई प्रैक्टिस सेट नहीं', 'JSON या PDF इम्पोर्ट करें, या Settings से डेमो सेट वापस लाएँ।', '<a href="#/import" class="btn-primary mt-2">Import</a>')}
      </div>
    </section>`;

  root.querySelector('[data-copy-prompt]').onclick = copyPrompt;
  root.querySelector('[data-telegram]').onclick = openTelegram;
  import('../jobs.js').then(async (J) => {
    const all = await J.listJobs();
    const act = all.filter(J.isActive).length, done = all.filter((j) => j.status === 'done').length, other = all.length - act - done;
    const el = root.querySelector('[data-jobsinfo]');
    if (el && all.length) el.textContent = `${act ? `⚙️ ${act} चल रहे • ` : ''}${other ? `⏸ ${other} रुके • ` : ''}📜 ${done} पूरे`;
  });
}
