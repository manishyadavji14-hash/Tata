import { getJob, listJobs, onJobsChange, pauseJob, startJob, deleteJob, updateJob, createJob, STATUS, isActive, jobQuestionCount } from '../jobs.js';
import { saveNewSet, appendToSet, getSet } from '../store.js';
import { db } from '../db.js';
import { bundleOfSet, shareFile, shareMessage } from '../share.js';
import { QTYPES } from '../prompts.js';
import { toast, confirmDialog, modal } from '../ui.js';
import { esc, fmtDate, fmtDur, bilingualHtml, renderRich, imgTag } from '../util.js';

const PAGE = 25;

async function buildSet(job) {
  if (job.kind === 'generate') {
    const { buildGenSet } = await import('../generate.js');
    return buildGenSet(job);
  }
  if (!job.result?.set) return null;
  return { set: job.result.set, warnings: job.result.warnings || [], rawIndex: job.result.set.questions.map((_, i) => i) };
}

// The saved set of this job, or of the job it continues ("और बनवाएँ").
async function parentSet(job) {
  let pid = job.parentId;
  for (let guard = 0; pid && guard < 20; guard++) {
    const p = await getJob(pid);
    if (!p) return null;
    if (p.savedSetId) { const s = await getSet(p.savedSetId); if (s) return s; }
    pid = p.parentId;
  }
  return null;
}

function qHtml(q, i, passages) {
  const opts = q.options.map((o) => `<div class="flex gap-2 rounded-lg border p-2 text-sm ${o.id === q.correct_option ? 'border-emerald-400 bg-emerald-50 dark:border-emerald-700 dark:bg-emerald-950/30' : 'border-slate-200 dark:border-slate-700'}">
      <b class="shrink-0">${o.id}${o.id === q.correct_option ? ' ✓' : ''}</b><div class="min-w-0 flex-1">${bilingualHtml(o.text_hi, o.text_en, 'both')}${imgTag(o.image_base64)}</div></div>`).join('');
  const p = q.passage_id && passages[q.passage_id];
  return `<div class="card !p-3" data-q="${i}">
    <div class="mb-2 flex items-center gap-2 text-xs">
      <b>Q.${q.q_no}</b><span class="chip bg-slate-100 dark:bg-slate-800">${esc(q.section)}</span><span class="chip bg-slate-100 dark:bg-slate-800">${esc(q.topic)}</span><span class="text-slate-400">${esc(q.difficulty)}</span>
      <button class="ml-auto text-rose-600" data-rmq="${i}" title="यह प्रश्न हटाएँ">✕ हटाएँ</button>
    </div>
    ${p ? `<details class="mb-2 text-xs"><summary class="cursor-pointer text-slate-500">गद्यांश</summary>${bilingualHtml(p.text_hi, p.text_en, 'both')}</details>` : ''}
    ${bilingualHtml(q.question_hi, q.question_en, 'both')}
    ${q.question_media?.table_html ? `<div class="rich mt-2 overflow-x-auto">${renderRich(q.question_media.table_html)}</div>` : ''}
    ${imgTag(q.question_media?.image_base64)}
    <div class="mt-2 space-y-1">${opts}</div>
    ${q.solution_hi || q.solution_en ? `<details class="mt-2 text-sm"><summary class="cursor-pointer font-semibold text-brand-600">हल / Solution</summary>${bilingualHtml(q.solution_hi, q.solution_en, 'both')}${q.key_concept ? `<div class="mt-1 text-xs text-slate-500">💡 ${renderRich(q.key_concept)}</div>` : ''}</details>` : ''}
  </div>`;
}

export async function render({ root, params, navigate, setHeader }) {
  const id = params[0];
  let job = await getJob(id);
  if (!job) { root.innerHTML = '<div class="card">यह काम नहीं मिला (शायद हटा दिया गया)। <a class="text-brand-600 underline" href="#/jobs">सभी काम</a></div>'; return; }
  setHeader({ title: job.title, sub: job.kind === 'pdf' ? 'Fixed — PDF डिजिटाइज़' : 'Custom — AI प्रश्न', back: 'jobs' });
  let shown = PAGE, lastCount = -1, built = null;

  root.innerHTML = `<div data-head></div><div data-sources></div><details class="card mb-3" data-logwrap><summary class="cursor-pointer text-sm font-bold">📜 प्रक्रिया लॉग / Log</summary><div class="mt-2 max-h-72 space-y-0.5 overflow-y-auto rounded-lg bg-slate-100 p-2 font-mono text-[11px] dark:bg-slate-800" data-log></div></details><div data-qs></div>`;
  const $ = (s) => root.querySelector(s);

  const color = { info: '', warn: 'text-amber-600', error: 'text-rose-600', success: 'text-emerald-600' };
  let headSig = '';
  async function drawHead(force = false) {
    const [ic, label, cls] = STATUS[job.status] || STATUS.queued;
    const n = jobQuestionCount(job);
    const sig = [job.status, n, job.savedSetId, job.appendedTo, job.error].join('|');
    if (!force && sig === headSig && $('[data-head] [data-hbar]')) {
      // Only progress changed: patch in place so the buttons stay tappable.
      const pct0 = Math.max(0, Math.min(100, job.progress?.pct || 0));
      $('[data-head] [data-hbar]').style.width = `${job.status === 'done' ? 100 : pct0}%`;
      $('[data-head] [data-hmsg]').textContent = job.progress?.msg || '';
      $('[data-head] [data-hpct]').textContent = `${pct0}%`;
      $('[data-head] [data-hbatch]').textContent = job.kind === 'generate' ? job.state?.batchesDone || 0 : job.result?.stats?.aiCalls ?? '—';
      return;
    }
    headSig = sig;
    const want = job.kind === 'generate' ? Number(job.params?.count) || 0 : 0;
    const pct = Math.max(0, Math.min(100, job.progress?.pct || 0));
    const saved = job.savedSetId ? await getSet(job.savedSetId) : null;
    const pset = !saved && job.kind === 'generate' ? await parentSet(job) : null;
    const took = (job.finishedAt || Date.now()) - (job.startedAt || job.createdAt);
    $('[data-head]').innerHTML = `<div class="card mb-3">
      <div class="flex items-center gap-2"><span class="chip ${cls}">${ic} ${label}</span><span class="text-xs text-slate-500">${fmtDate(job.createdAt)} • ${fmtDur(took)}</span></div>
      <div class="bar mt-3"><span data-hbar class="${job.status === 'error' ? 'bg-rose-500' : job.status === 'done' ? 'bg-emerald-500' : 'bg-brand-600'} transition-all" style="width:${job.status === 'done' ? 100 : pct}%"></span></div>
      <div class="mt-2 text-sm font-semibold" data-hmsg>${esc(job.progress?.msg || '')}</div>
      <div class="mt-1 grid grid-cols-3 gap-2 text-center">
        <div class="stat"><div class="stat-v">${n}${want ? `<span class="text-xs text-slate-400">/${want}</span>` : ''}</div><div class="stat-l">प्रश्न बने</div></div>
        <div class="stat"><div class="stat-v" data-hbatch>${job.kind === 'generate' ? job.state?.batchesDone || 0 : job.result?.stats?.aiCalls ?? '—'}</div><div class="stat-l">${job.kind === 'generate' ? 'बैच' : 'AI कॉल'}</div></div>
        <div class="stat"><div class="stat-v" data-hpct>${pct}%</div><div class="stat-l">प्रगति</div></div>
      </div>
      ${job.error ? `<div class="mt-2 rounded-lg bg-rose-50 p-2 text-xs text-rose-700 dark:bg-rose-950/30 dark:text-rose-300">${esc(job.error)}</div>` : ''}
      <div class="mt-3 grid grid-cols-2 gap-2">
        ${isActive(job) ? '<button class="btn-outline" data-a="pause">⏸ रोकें</button>' : ''}
        ${['paused', 'interrupted', 'error'].includes(job.status) ? '<button class="btn-primary" data-a="resume">▶ जारी रखें</button>' : ''}
        ${n ? `<button class="btn-success col-span-2" data-a="save">💾 ${saved ? 'सेट अपडेट करें' : `${job.status === 'done' ? '' : 'अभी तक बने '}${n} प्रश्न सेट में सेव करें`}</button>` : ''}
        ${saved ? `<a class="btn-outline" href="#/set/${encodeURIComponent(saved.id)}">📚 सेट खोलें</a><a class="btn-outline" href="#/pretest/${encodeURIComponent(saved.id)}">▶ टेस्ट दें</a>` : ''}
        ${pset && n ? `<button class="btn-purple col-span-2" data-a="append">➕ "${esc(pset.meta.title).slice(0, 40)}" में जोड़ें (${pset.questions.length} + ${n})</button>` : ''}
        ${n ? '<button class="btn-outline" data-a="share">📤 शेयर करें</button>' : ''}
        ${job.kind === 'generate' && !isActive(job) && n ? '<button class="btn-outline" data-a="more">✨ और बनवाएँ</button>' : ''}
        <button class="btn-outline text-rose-600 ${n ? '' : 'col-span-2'}" data-a="delete">🗑 काम हटाएँ</button>
      </div>
      ${saved ? `<div class="mt-2 text-[11px] text-emerald-700">💾 "${esc(saved.meta.title)}" (${saved.questions.length} प्रश्न) में सेव है${job.appendedTo ? ' — इस काम के प्रश्न उसमें जोड़े गए' : ''}</div>` : ''}
    </div>`;
  }
  function drawSources() {
    const src = job.sources || [];
    const p = job.params || {};
    const types = (p.types || []).map((k) => (QTYPES.find((t) => t[0] === k) || [k, k === 'five' ? '5 विकल्प' : k])[1]);
    $('[data-sources]').innerHTML = `<details class="card mb-3"><summary class="cursor-pointer text-sm font-bold">📂 स्रोत और सेटिंग</summary>
      <div class="mt-2 space-y-1 text-xs">${src.map((s) => `<div>📄 ${esc(s.name)}${s.pages ? ` — पेज ${esc(s.pages)}` : ''} <span class="text-slate-400">(${(s.size / 1048576).toFixed(1)} MB)</span></div>`).join('') || '<div class="text-slate-500">कोई फ़ाइल नहीं</div>'}
      ${job.pasted ? `<div>📝 पेस्ट किया टेक्स्ट: ${job.pasted.length} अक्षर</div>` : ''}
      ${job.kind === 'generate' ? `<div class="pt-1 text-slate-500">प्रकार: ${esc(types.join(', ') || 'स्वतः')} • भाषा: ${esc(p.lang)} • कठिनाई: ${esc(p.difficulty)}</div>${p.custom ? `<div class="text-slate-500">निर्देश: ${esc(p.custom)}</div>` : ''}` : `<div class="pt-1 text-slate-500">AI: ${p.useAI ? `हाँ (${esc(p.pdf?.pdfMode || '')})` : 'नहीं — ऑफ़लाइन पार्सर'}</div>`}</div></details>`;
  }
  function drawLog() {
    const el = $('[data-log]');
    const atEnd = el.scrollTop + el.clientHeight >= el.scrollHeight - 20;
    el.innerHTML = (job.log || []).slice(-200).map((l) => `<div class="${color[l.level] || ''}"><span class="text-slate-400">${new Date(l.t).toLocaleTimeString('hi-IN', { hour: '2-digit', minute: '2-digit', second: '2-digit' })}</span> ${esc(l.msg)}</div>`).join('');
    if (atEnd) el.scrollTop = el.scrollHeight;
  }
  async function drawQs(force = false) {
    const n = jobQuestionCount(job);
    if (!force && n === lastCount) return;
    lastCount = n;
    built = n ? await buildSet(job) : null;
    const qs = built?.set.questions || [];
    $('[data-qs]').innerHTML = qs.length ? `
      <div class="mb-2 flex items-center justify-between"><h2 class="text-xs font-bold uppercase tracking-wide text-slate-500">बने प्रश्न — जाँचें (${qs.length})</h2>
      <span class="text-[11px] text-slate-500">जो ठीक न लगे उसे ✕ से हटाएँ</span></div>
      <div class="space-y-2">${qs.slice(0, shown).map((q, i) => qHtml(q, i, built.set.passages || {})).join('')}</div>
      ${qs.length > shown ? `<button class="btn-outline mt-2 w-full" data-a="moreq">और दिखाएँ (${qs.length - shown})</button>` : ''}` : (isActive(job) ? '<div class="card text-sm text-slate-500">प्रश्न बनते ही यहाँ दिखेंगे…</div>' : '');
  }

  const drawAll = async (force) => { await drawHead(); drawSources(); drawLog(); await drawQs(force); };
  await drawAll(true);
  $('[data-logwrap]').open = isActive(job) || job.status === 'error';

  let pending = null;
  const off = onJobsChange(async (j) => {
    if (j.id !== id) return;
    if (j.deleted) { navigate('jobs'); return; }
    if (pending) return;
    pending = setTimeout(async () => { pending = null; job = (await getJob(id)) || job; await drawHead(); drawLog(); await drawQs(); }, 350);
  });

  async function save(mode) {
    const b = built || (await buildSet(job));
    if (!b) return;
    if (mode === 'append') {
      const pset = await parentSet(job);
      if (!pset) return;
      const { row, added } = await appendToSet(pset, b.set);
      job.savedSetId = row.id; job.appendedTo = row.id;
      await updateJob(job);
      toast(`${added} प्रश्न जोड़े — कुल ${row.questions.length}`, 'success', 3500);
      return;
    }
    const existing = job.savedSetId ? await getSet(job.savedSetId) : null;
    if (existing && !job.appendedTo) {
      const row = { ...existing, meta: { ...existing.meta, max_marks: b.set.meta.max_marks }, sections: b.set.sections, passages: b.set.passages, questions: b.set.questions, updatedAt: Date.now() };
      await db.put('sets', row);
      toast(`सेट अपडेट हुआ: ${row.questions.length} प्रश्न`, 'success');
    } else if (existing && job.appendedTo) {
      toast('ये प्रश्न पहले ही सेट में जोड़े जा चुके हैं', 'info'); return;
    } else {
      const row = await saveNewSet(b.set);
      job.savedSetId = row.id;
      if (job.kind === 'pdf' && job.params?.draftKey) await db.delete('importDrafts', job.params.draftKey);
      toast(`सेव हुआ: ${row.questions.length} प्रश्न`, 'success');
    }
    await updateJob(job);
  }

  function moreDialog() {
    const p = job.params;
    const srcs = (job.sources || []).map((s) => ({ ...s, use: true }));
    const added = [];
    const md = modal({
      title: '✨ ऐसे ही और प्रश्न बनवाएँ', size: 'lg',
      body: `<div class="space-y-3 text-sm">
        <div class="text-xs text-slate-500">पिछली सेटिंग (प्रकार, भाषा, कठिनाई, निर्देश) वही रहेगी। पहले बने ${jobQuestionCount(job)} प्रश्न दोबारा नहीं बनेंगे।</div>
        <div><div class="label">पहले वाले डॉक्यूमेंट — किस हिस्से से? (पेज बदलें, जैसे 21-40)</div>
          <div class="space-y-1">${srcs.map((s, i) => `<div class="flex items-center gap-2 rounded-lg bg-slate-100 p-2 text-xs dark:bg-slate-800">
            <input type="checkbox" class="h-5 w-5 accent-brand-600" data-use="${i}" checked><span class="min-w-0 flex-1 truncate">📄 ${esc(s.name)}</span>
            ${/pdf/i.test(s.type) || /\.pdf$/i.test(s.name) ? `<input class="input !w-28 !py-1 text-xs" data-opg="${i}" value="${esc(s.pages)}" placeholder="पेज: 1-20">` : ''}</div>`).join('') || '<div class="text-xs text-slate-500">कोई फ़ाइल नहीं</div>'}</div></div>
        <div><div class="label">नए डॉक्यूमेंट जोड़ें (एक या अधिक) — हर एक के पेज अलग बताएँ</div>
          <label class="btn-outline w-full cursor-pointer">📎 फ़ाइल जोड़ें (PDF, फ़ोटो, .txt)<input type="file" multiple accept="application/pdf,.pdf,image/*,.txt,.md,.csv,.html,.htm,text/plain" class="hidden" data-addf></label>
          <div class="mt-1 space-y-1" data-newlist></div></div>
        <div><label class="label">पेस्ट किया टेक्स्ट (बदल सकते हैं)</label><textarea class="input h-20 text-sm" data-mpaste>${esc(job.pasted || '')}</textarea></div>
        <div class="grid grid-cols-2 gap-2">
          <div><label class="label">कितने और प्रश्न</label><input type="number" min="1" max="300" class="input" data-mcount value="${p.count}"></div>
          <div><label class="label">प्रति AI कॉल</label><input type="number" min="3" max="20" class="input" data-mper value="${p.perCall}"></div>
        </div>
        <div><label class="label">निर्देश (बदल/जोड़ सकते हैं)</label><textarea class="input h-20 text-sm" data-mcustom>${esc(p.custom || '')}</textarea></div>
      </div>`,
      footer: '<button class="btn-outline" data-close>Cancel</button><button class="btn-success" data-mgo>✨ बनवाना शुरू करें</button>',
    });
    const el = md.el;
    const drawNew = () => {
      el.querySelector('[data-newlist]').innerHTML = added.map((f, i) => `<div class="flex items-center gap-2 rounded-lg bg-emerald-50 p-2 text-xs dark:bg-emerald-950/30">
        <span class="min-w-0 flex-1 truncate">➕ ${esc(f.file.name)}</span>
        ${/pdf/i.test(f.file.type) || /\.pdf$/i.test(f.file.name) ? `<input class="input !w-28 !py-1 text-xs" data-npg="${i}" value="${esc(f.pages)}" placeholder="पेज: 1-20">` : ''}
        <button class="text-rose-600" data-nrm="${i}">✕</button></div>`).join('');
    };
    el.querySelector('[data-addf]').onchange = (e) => { for (const f of e.target.files || []) added.push({ file: f, pages: '' }); e.target.value = ''; drawNew(); };
    el.addEventListener('input', (e) => {
      if (e.target.dataset.opg) srcs[Number(e.target.dataset.opg)].pages = e.target.value;
      if (e.target.dataset.npg) added[Number(e.target.dataset.npg)].pages = e.target.value;
    });
    el.addEventListener('change', (e) => { if (e.target.dataset.use) srcs[Number(e.target.dataset.use)].use = e.target.checked; });
    el.addEventListener('click', async (e) => {
      const rm = e.target.closest('[data-nrm]');
      if (rm) { added.splice(Number(rm.dataset.nrm), 1); drawNew(); return; }
      if (!e.target.closest('[data-mgo]')) return;
      const sources = [...srcs.filter((s) => s.use), ...added];
      const pasted = el.querySelector('[data-mpaste]').value;
      const custom = el.querySelector('[data-mcustom]').value;
      if (!sources.length && !pasted.trim() && !custom.trim()) { toast('कोई स्रोत या निर्देश दें', 'warn'); return; }
      const all = await listJobs();
      const root0 = job.rootTitle || job.title;
      const part = all.filter((x) => (x.rootTitle || x.title) === root0).length + 1;
      const { stemOf } = await import('../generate.js');
      const avoid = [...(job.avoid || []), ...(job.state?.questions || []).map(stemOf)].slice(-200);
      const child = await createJob({
        kind: 'generate', title: `${root0} — भाग ${part}`, sources, pasted, parentId: job.id, avoid, rootTitle: root0,
        params: { ...p, count: Math.max(1, Math.min(300, parseInt(el.querySelector('[data-mcount]').value, 10) || p.count)), perCall: parseInt(el.querySelector('[data-mper]').value, 10) || p.perCall, custom },
      });
      md.close();
      toast('नया काम शुरू — बैकग्राउंड में चलेगा', 'success');
      navigate(`job/${child.id}`);
    });
  }

  root.addEventListener('click', async (e) => {
    const rmq = e.target.closest('[data-rmq]');
    if (rmq) {
      const i = Number(rmq.dataset.rmq);
      if (!built) return;
      if (!(await confirmDialog(`Q.${i + 1} हटाएँ?`, { ok: 'हटाएँ', danger: true }))) return;
      if (job.kind === 'generate') job.state.questions.splice(built.rawIndex[i], 1);
      else { job.result.set.questions.splice(i, 1); job.result.set.questions.forEach((q, k) => { q.q_no = k + 1; }); }
      await updateJob(job);
      await drawHead(true); await drawQs(true);
      return;
    }
    const a = e.target.closest('[data-a]')?.dataset.a;
    if (!a) return;
    if (a === 'pause') { await pauseJob(id); }
    if (a === 'resume') { await startJob(id); job = await getJob(id); await drawHead(true); }
    if (a === 'moreq') { shown += PAGE; await drawQs(true); }
    if (a === 'save' || a === 'append') { await save(a); await drawHead(true); }
    if (a === 'share') {
      const b = built || (await buildSet(job));
      if (!b) return;
      const bundle = bundleOfSet(b.set);
      const how = await shareFile(`abhyas_${(b.set.meta.exam_id || 'ai').slice(0, 50)}.json`, JSON.stringify(bundle), shareMessage(bundle));
      if (how === 'saved') toast('फ़ाइल डाउनलोड हुई — इसे Telegram/WhatsApp में भेजें', 'info', 4000);
    }
    if (a === 'more') moreDialog();
    if (a === 'delete') {
      if (await confirmDialog(`यह काम और इसका लॉग हटेगा।${job.savedSetId ? ' सेव किया गया प्रैक्टिस सेट बना रहेगा।' : ' बने प्रश्न अभी सेव नहीं हैं — वे भी हटेंगे।'}`, { ok: 'हटाएँ', danger: true })) {
        await deleteJob(id);
        toast('काम हटाया गया', 'success');
        navigate('jobs');
      }
    }
  });

  return () => { off(); clearTimeout(pending); };
}
