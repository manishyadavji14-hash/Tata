// Shared rendering pieces for questions (used by the exam player, solutions and bookmarks).
import { bilingualHtml, imgTag, renderRich, esc, pickLang, fmtDur } from './util.js';
import { icon } from './ui.js';

export function passageHtml(set, q, lang) {
  if (!q.passage_id || !set.passages[q.passage_id]) return '';
  const p = set.passages[q.passage_id];
  const linked = set.questions.filter((x) => x.passage_id === q.passage_id).map((x) => x.q_no);
  const range = linked.length > 1 ? `Q.${linked[0]}–${linked[linked.length - 1]}` : `Q.${linked[0]}`;
  return `<details class="mb-3 overflow-hidden rounded-xl border border-amber-300 bg-amber-50 dark:border-amber-700/60 dark:bg-amber-950/30" open>
    <summary class="cursor-pointer select-none px-3 py-2 text-xs font-bold text-amber-800 dark:text-amber-300">📖 गद्यांश / Passage (${range})${p.title ? ` — <span class="font-medium">${esc(p.title)}</span>` : ''}</summary>
    <div class="max-h-56 overflow-y-auto border-t border-amber-200 px-3 py-2 text-[15px] dark:border-amber-800/60">${bilingualHtml(p.text_hi, p.text_en, lang)}</div>
  </details>`;
}

export function questionHtml(q, lang) {
  let html = bilingualHtml(q.question_hi, q.question_en, lang, 'text-[16px]');
  if (q.question_media?.table_html) html += `<div class="rich mt-2">${renderRich(q.question_media.table_html)}</div>`;
  if (q.question_media?.image_base64) html += `<div class="mt-2">${imgTag(q.question_media.image_base64, `Q${q.q_no} figure`)}</div>`;
  return html;
}

export function optionInner(o, lang) {
  const v = pickLang(o.text_hi, o.text_en, lang);
  let t = typeof v === 'string' ? `<div class="rich">${renderRich(v)}</div>` : `<div class="rich">${renderRich(v.hi)}</div><div class="rich text-slate-500 dark:text-slate-400">${renderRich(v.en)}</div>`;
  if (o.image_base64) t += imgTag(o.image_base64, `Option ${o.id}`);
  return t;
}

const STATUS_CHIP = {
  correct: '<span class="chip bg-emerald-100 text-emerald-700 dark:bg-emerald-900/40 dark:text-emerald-300">✓ सही / Correct</span>',
  wrong: '<span class="chip bg-rose-100 text-rose-700 dark:bg-rose-900/40 dark:text-rose-300">✗ गलत / Incorrect</span>',
  skipped: '<span class="chip bg-slate-200 text-slate-600 dark:bg-slate-800 dark:text-slate-300">— छोड़ा / Unattempted</span>',
};

// Full solution card. `item` = evaluation item ({ sel, status, time }) or null (bookmark view).
export function solutionCard(set, q, lang, item, { bookmarked = false, showPassage = true } = {}) {
  const sel = item?.sel || null;
  const opts = q.options.map((o) => {
    const isCorrect = o.id === q.correct_option;
    const isSel = o.id === sel;
    const cls = isCorrect ? 'opt-correct' : isSel ? 'opt-wrong' : '';
    const badge = isCorrect ? 'border-emerald-500 bg-emerald-500 text-white' : isSel ? 'border-rose-500 bg-rose-500 text-white' : '';
    const tag = isCorrect && isSel ? '<span class="chip ml-auto shrink-0 bg-emerald-600 text-white">आपका ✓</span>' : isCorrect ? '<span class="chip ml-auto shrink-0 bg-emerald-600 text-white">सही</span>' : isSel ? '<span class="chip ml-auto shrink-0 bg-rose-600 text-white">आपका</span>' : '';
    return `<div class="opt ${cls}"><span class="opt-badge ${badge}">${o.id}</span><div class="min-w-0 flex-1">${optionInner(o, lang)}</div>${tag}</div>`;
  }).join('');
  const sol = q.solution_hi || q.solution_en
    ? bilingualHtml(q.solution_hi, q.solution_en, lang)
    : '<div class="text-sm italic text-slate-500">इस प्रश्न की व्याख्या उपलब्ध नहीं है। नीचे "Ask AI" से समझें।</div>';
  return `<article class="card fade-in" data-q="${q.q_no}">
    <div class="mb-2 flex flex-wrap items-center gap-2">
      <span class="text-sm font-bold">Q.${q.q_no}</span>
      ${item ? STATUS_CHIP[item.status] : ''}
      <span class="chip bg-brand-50 text-brand-700 dark:bg-brand-900/30 dark:text-brand-300">${esc(q.section)}</span>
      <span class="chip bg-slate-100 text-slate-600 dark:bg-slate-800 dark:text-slate-300">${esc(q.topic)}</span>
      <button class="btn-ghost btn-sm ml-auto ${bookmarked ? 'text-amber-500' : ''}" data-bm="${q.q_no}" aria-label="Bookmark">${bookmarked ? icon.bookmarkFill('h-5 w-5') : icon.bookmark('h-5 w-5')}</button>
    </div>
    ${showPassage ? passageHtml(set, q, lang) : ''}
    <div class="mb-3">${questionHtml(q, lang)}</div>
    <div class="space-y-2">${opts}</div>
    <div class="mt-3 grid grid-cols-3 gap-2 text-center text-xs">
      <div class="stat !p-2"><div class="stat-l">आपका उत्तर</div><div class="font-bold ${item?.status === 'wrong' ? 'text-rose-600' : item?.status === 'correct' ? 'text-emerald-600' : ''}">${sel || '—'}</div></div>
      <div class="stat !p-2"><div class="stat-l">सही उत्तर</div><div class="font-bold text-emerald-600">${q.correct_option || '?'}${q.answer_source === 'ai' ? '<span class="ml-1 text-[10px] font-normal text-amber-600">(AI)</span>' : ''}</div></div>
      <div class="stat !p-2"><div class="stat-l">लिया समय</div><div class="font-bold">${item ? fmtDur(item.time) : '—'}</div></div>
    </div>
    <div class="mt-3 rounded-xl border border-emerald-200 bg-emerald-50/60 p-3 dark:border-emerald-900 dark:bg-emerald-950/20">
      <div class="mb-1 text-xs font-bold uppercase tracking-wide text-emerald-700 dark:text-emerald-400">व्याख्या / Solution</div>
      ${sol}
      ${q.key_concept ? `<div class="mt-2 rounded-lg bg-white/70 p-2 text-sm dark:bg-slate-900/60"><span class="font-bold text-brand-700 dark:text-brand-300">💡 Key Concept:</span> <span class="rich">${renderRich(q.key_concept)}</span></div>` : ''}
    </div>
    <div class="mt-3 flex flex-wrap items-center gap-2">
      <button class="btn-purple btn-sm" data-ask="${q.q_no}">${icon.spark('h-4 w-4')} Ask AI / डाउट पूछें</button>
      <span class="text-[11px] text-slate-400">${esc(q.syllabus_tag || '')}${q.difficulty ? ` • ${esc(q.difficulty)}` : ''}</span>
    </div>
  </article>`;
}
