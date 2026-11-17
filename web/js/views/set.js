import { getSet, deleteSet, attemptsForSet } from '../store.js';
import { db } from '../db.js';
import { toStandardJson } from '../schema.js';
import { icon, toast, confirmDialog, modal, promptDialog } from '../ui.js';
import { renameSet, renameSection, renameTopicInSet } from '../bank.js';
import { esc, downloadText, fmtDate, fmtDur, slug } from '../util.js';
import { refresh } from '../app.js';
import { requirePin } from '../pin.js';

export async function render({ root, params, navigate, setHeader }) {
  const set = await getSet(params[0]);
  if (!set) { root.innerHTML = '<div class="card">सेट नहीं मिला / Set not found</div>'; return; }
  setHeader({ title: set.meta.title, sub: `${set.questions.length} प्रश्न • ${set.meta.duration_minutes} मिनट`, back: 'home' });
  const attempts = await attemptsForSet(set.id);

  const bySection = set.sections.map((s) => {
    const qs = set.questions.filter((q) => q.section === s);
    const topics = [...new Set(qs.map((q) => q.topic))];
    const counts = topics.map((t) => qs.filter((q) => q.topic === t).length);
    return { s, n: qs.length, topics, counts };
  });
  const noKey = set.questions.filter((q) => !q.correct_option).map((q) => q.q_no);
  const noSol = set.questions.filter((q) => !q.solution_hi && !q.solution_en).length;
  const aiKey = set.questions.filter((q) => q.answer_source === 'ai').length;
  const imgs = set.questions.filter((q) => q.question_media?.image_base64 || q.options.some((o) => o.image_base64)).length;

  root.innerHTML = `
    <div class="card mb-3">
      <div class="grid grid-cols-4 gap-2">
        <div class="stat"><div class="stat-v">${set.questions.length}</div><div class="stat-l">प्रश्न</div></div>
        <div class="stat"><div class="stat-v">${set.meta.duration_minutes}</div><div class="stat-l">मिनट</div></div>
        <div class="stat"><div class="stat-v">${set.meta.max_marks}</div><div class="stat-l">अंक</div></div>
        <div class="stat"><div class="stat-v">-${set.meta.negative_marks}</div><div class="stat-l">नेगेटिव</div></div>
      </div>
      <div class="mt-3 space-y-1 text-xs text-slate-500">
        <div>स्रोत / Source: <b>${esc(set.source || 'json')}</b> • जोड़ा: ${fmtDate(set.createdAt)}</div>
        <div>पैसेज: ${Object.keys(set.passages || {}).length} • चित्र वाले प्रश्न: ${imgs} • बिना व्याख्या: ${noSol}${aiKey ? ` • AI द्वारा हल किए उत्तर: ${aiKey}` : ''}</div>
        ${noKey.length ? `<div class="text-rose-600">⚠️ ${noKey.length} प्रश्नों का सही उत्तर नहीं है (Q.${noKey.slice(0, 15).join(', ')}${noKey.length > 15 ? '…' : ''}) — इन्हें मूल्यांकन में गलत/छोड़ा गिना जाएगा।</div>` : ''}
      </div>
      <div class="mt-4 grid grid-cols-2 gap-2">
        <a href="#/pretest/${encodeURIComponent(set.id)}" class="btn-primary col-span-2">${icon.play('h-4 w-4')} टेस्ट शुरू करें / Start Test</a>
        <a href="#/solutions/study?set=${encodeURIComponent(set.id)}" class="btn-outline">📚 प्रश्न + हल देखें</a>
        <button class="btn-outline" data-act="rename">✏️ नाम बदलें</button>
        <button class="btn-outline" data-act="edit">⚙️ सेटिंग बदलें</button>
        <a href="#/bank?target=${encodeURIComponent(set.id)}" class="btn-outline">➕ प्रश्न जोड़ें</a>
        <a href="#/share?set=${encodeURIComponent(set.id)}" class="btn-outline">📤 शेयर करें</a>
        <button class="btn-outline" data-act="export">${icon.download('h-4 w-4')} JSON Export</button>
        <button class="btn-outline text-rose-600" data-act="delete">${icon.trash('h-4 w-4')} डिलीट</button>
      </div>
    </div>

    <h2 class="mb-2 text-xs font-bold uppercase tracking-wide text-slate-500">विषय और टॉपिक <span class="font-normal normal-case">(✏️ से नाम बदलें, टॉपिक पर टैप करके उसका नाम बदलें)</span></h2>
    <div class="mb-4 space-y-2">${bySection.map((b, i) => `
      <details class="card !p-3">
        <summary class="flex cursor-pointer items-center gap-2 text-sm font-semibold"><span class="min-w-0 flex-1">${esc(b.s)}</span><span class="text-xs font-normal text-slate-500">${b.n} प्रश्न</span>
          <button class="btn-ghost btn-sm !px-2" data-rsec="${i}" aria-label="विषय का नाम बदलें">✏️</button></summary>
        <div class="mt-2 flex flex-wrap gap-1">${b.topics.map((t, j) => `<button class="chip bg-slate-100 text-slate-600 active:scale-95 dark:bg-slate-800 dark:text-slate-300" data-rtop="${i}|${j}">${esc(t)} <span class="opacity-60">${b.counts[j]}</span> ✏️</button>`).join('')}</div>
        <a class="btn-outline btn-sm mt-3" href="#/bank?target=${encodeURIComponent(set.id)}&tsec=${encodeURIComponent(b.s)}">➕ इस विषय में दूसरे सेट से प्रश्न जोड़ें</a>
      </details>`).join('')}</div>

    <h2 class="mb-2 text-xs font-bold uppercase tracking-wide text-slate-500">पिछले प्रयास / Attempts (${attempts.length})</h2>
    <div class="space-y-2">${attempts.length ? attempts.map((a) => `
      <a href="#/${a.state === 'submitted' ? 'result' : 'exam'}/${encodeURIComponent(a.id)}" class="card flex items-center gap-3 !p-3">
        <div class="min-w-0 flex-1">
          <div class="truncate text-sm font-semibold">${esc(a.title)}</div>
          <div class="text-[11px] text-slate-500">${fmtDate(a.startedAt)} • ${a.qNos.length} प्रश्न${a.mode !== 'full' ? ` • ${esc(a.mode)}` : ''}</div>
        </div>
        ${a.state === 'submitted' && a.result
          ? `<div class="text-right"><div class="font-bold text-brand-600">${a.result.score}/${a.result.maxScore}</div><div class="text-[10px] text-slate-500">${a.result.accuracy}% • ${fmtDur(a.result.totalTime)}</div></div>`
          : '<span class="chip bg-amber-100 text-amber-800">अधूरा • Resume</span>'}
      </a>`).join('') : '<div class="text-sm text-slate-500">अभी तक कोई प्रयास नहीं।</div>'}</div>`;

  root.addEventListener('click', async (e) => {
    const rs = e.target.closest('[data-rsec]');
    if (rs) {
      e.preventDefault();
      const from = bySection[Number(rs.dataset.rsec)].s;
      const to = await promptDialog({ title: 'विषय का नाम बदलें', label: `"${from}" का नया नाम`, value: from, suggest: set.sections.filter((s) => s !== from), hint: 'किसी दूसरे विषय का नाम लिखेंगे तो दोनों विषय एक हो जाएँगे।' });
      if (!to || to === from) return;
      const n = await renameSection(set, from, to);
      toast(`${n} प्रश्नों का विषय "${to}" हुआ`, 'success');
      refresh();
      return;
    }
    const rt = e.target.closest('[data-rtop]');
    if (rt) {
      const [i, j] = rt.dataset.rtop.split('|').map(Number);
      const b = bySection[i];
      const from = b.topics[j];
      const to = await promptDialog({ title: 'टॉपिक का नाम बदलें', label: `${b.s} › "${from}" का नया नाम`, value: from, suggest: b.topics.filter((t) => t !== from), hint: 'किसी दूसरे टॉपिक का नाम लिखेंगे तो दोनों टॉपिक एक हो जाएँगे।' });
      if (!to || to === from) return;
      const n = await renameTopicInSet(set, b.s, from, to);
      toast(`${n} प्रश्नों का टॉपिक "${to}" हुआ`, 'success');
      refresh();
      return;
    }
    const act = e.target.closest('[data-act]')?.dataset.act;
    if (act === 'rename') {
      const t = await promptDialog({ title: 'सेट का नाम बदलें', label: 'नया नाम', value: set.meta.title });
      if (t && t !== set.meta.title) { await renameSet(set, t); toast('नाम बदला ✓', 'success'); refresh(); }
      return;
    }
    if (act === 'export') {
      const where = downloadText(`${slug(set.meta.title)}.json`, JSON.stringify(toStandardJson(set), null, 2));
      toast(`JSON सेव हुआ → ${where}`, 'success');
    }
    if (act === 'delete') {
      if (!(await requirePin(`"${esc(set.meta.title)}" हटाने के लिए`))) return;
      if (await confirmDialog(`"${esc(set.meta.title)}" और इसके सभी प्रयास, बुकमार्क व चैट डिलीट होंगे।`, { ok: 'Delete', danger: true })) {
        await deleteSet(set.id);
        toast('सेट डिलीट हुआ', 'success');
        navigate('home');
      }
    }
    if (act === 'edit') editMeta(set, refresh);
  });
}

function editMeta(set, done) {
  const m = set.meta;
  const md = modal({
    title: 'सेट सेटिंग / Edit set',
    body: `<div class="space-y-3">
      <div><label class="label">Title</label><input class="input" name="title" value="${esc(m.title)}"></div>
      <div class="grid grid-cols-3 gap-2">
        <div><label class="label">मिनट</label><input class="input" name="duration_minutes" type="number" min="1" value="${m.duration_minutes}"></div>
        <div><label class="label">+ अंक</label><input class="input" name="positive_marks" type="number" step="0.01" min="0" value="${m.positive_marks}"></div>
        <div><label class="label">− अंक</label><input class="input" name="negative_marks" type="number" step="0.01" min="0" value="${m.negative_marks}"></div>
      </div>
      <div><label class="label">निर्देश (हिंदी)</label><textarea class="input" name="instructions_hi" rows="2">${esc(m.instructions_hi)}</textarea></div>
      <div><label class="label">Instructions (English)</label><textarea class="input" name="instructions_en" rows="2">${esc(m.instructions_en)}</textarea></div>
    </div>`,
    footer: '<button class="btn-outline" data-close>Cancel</button><button class="btn-primary" data-save>Save</button>',
  });
  md.el.querySelector('[data-save]').onclick = async () => {
    const v = (n) => md.el.querySelector(`[name=${n}]`).value;
    set.meta = {
      ...m, title: v('title').trim() || m.title,
      duration_minutes: Math.max(1, Number(v('duration_minutes')) || m.duration_minutes),
      positive_marks: Math.max(0, Number(v('positive_marks'))), negative_marks: Math.max(0, Number(v('negative_marks'))),
      instructions_hi: v('instructions_hi'), instructions_en: v('instructions_en'),
    };
    set.meta.max_marks = Math.round(set.questions.length * set.meta.positive_marks * 100) / 100;
    await db.put('sets', set);
    md.close();
    toast('सेव हुआ', 'success');
    done();
  };
}
