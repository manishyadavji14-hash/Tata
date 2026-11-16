import { listJobs, onJobsChange, pauseJob, startJob, deleteJob, STATUS, isActive, jobQuestionCount, MAX_PARALLEL } from '../jobs.js';
import { toast, confirmDialog, emptyState } from '../ui.js';
import { esc, fmtDate } from '../util.js';

export function jobCard(j) {
  const [ic, label, cls] = STATUS[j.status] || STATUS.queued;
  const pct = Math.max(0, Math.min(100, j.progress?.pct || 0));
  const n = jobQuestionCount(j);
  const want = j.kind === 'generate' ? Number(j.params?.count) || 0 : 0;
  const canPause = isActive(j);
  const canResume = ['paused', 'interrupted', 'error'].includes(j.status);
  return `<div class="card !p-3" data-job="${esc(j.id)}">
    <a href="#/job/${encodeURIComponent(j.id)}" class="block">
      <div class="flex items-start gap-2">
        <span class="text-xl">${j.kind === 'pdf' ? '📑' : j.kind === 'concepts' ? '💡' : '✨'}</span>
        <div class="min-w-0 flex-1">
          <div class="truncate text-sm font-bold">${esc(j.title)}</div>
          <div class="text-[11px] text-slate-500">${j.kind === 'pdf' ? 'Fixed — PDF डिजिटाइज़' : j.kind === 'concepts' ? 'AI — कॉन्सेप्ट नोट्स' : 'Custom — AI प्रश्न'}${j.parentId ? ' • और बनवाए' : ''} • ${fmtDate(j.createdAt)}</div>
        </div>
        <span class="chip shrink-0 ${cls}">${ic} ${label}</span>
      </div>
      ${j.status !== 'done' ? `<div class="bar mt-2"><span data-pbar class="${j.status === 'error' ? 'bg-rose-500' : 'bg-brand-600'} transition-all" style="width:${pct}%"></span></div>` : ''}
      <div class="mt-1 truncate text-xs text-slate-600 dark:text-slate-300" data-pmsg>${esc(j.progress?.msg || '')}</div>
      <div class="mt-1 text-[11px] text-slate-500"><span data-pn>${n}</span> ${j.kind === 'concepts' ? 'कॉन्सेप्ट' : 'प्रश्न'}${want ? ` / ${want}` : ''}${j.savedSetId || j.savedConceptId ? ' • 💾 सेव' : ''}</div>
    </a>
    <div class="mt-2 flex gap-2">
      ${canPause ? '<button class="btn-outline btn-sm flex-1" data-jact="pause">⏸ रोकें</button>' : ''}
      ${canResume ? '<button class="btn-primary btn-sm flex-1" data-jact="resume">▶ जारी रखें</button>' : ''}
      <a href="#/job/${encodeURIComponent(j.id)}" class="btn-outline btn-sm flex-1">${j.status === 'done' ? '👁 देखें / सेव' : '📋 विवरण'}</a>
      <button class="btn-outline btn-sm text-rose-600" data-jact="delete">🗑</button>
    </div>
  </div>`;
}

// Update progress of a card in place (no re-render, so taps on its buttons are never lost).
export function patchCard(root, j) {
  const c = root.querySelector(`[data-job="${CSS.escape(j.id)}"]`);
  if (!c) return false;
  const bar = c.querySelector('[data-pbar]');
  if (bar) bar.style.width = `${Math.max(0, Math.min(100, j.progress?.pct || 0))}%`;
  const m = c.querySelector('[data-pmsg]');
  if (m) m.textContent = j.progress?.msg || '';
  const n = c.querySelector('[data-pn]');
  if (n) n.textContent = jobQuestionCount(j);
  return true;
}

export async function jobActions(e, jobs) {
  const b = e.target.closest('[data-jact]');
  if (!b) return false;
  e.preventDefault();
  const id = b.closest('[data-job]').dataset.job;
  const j = jobs.find((x) => x.id === id);
  const act = b.dataset.jact;
  if (act === 'pause') { await pauseJob(id); toast('रोका गया — प्रगति सुरक्षित', 'info'); }
  if (act === 'resume') { await startJob(id); toast('फिर शुरू हुआ', 'success'); }
  if (act === 'delete') {
    const msg = `"${esc(j?.title || '')}" काम${isActive(j || {}) ? ' (अभी चल रहा है)' : ''} और इसके बने प्रश्न हटेंगे।${j?.savedSetId ? ' सेव किया गया प्रैक्टिस सेट नहीं हटेगा।' : ' प्रश्न अभी किसी सेट में सेव नहीं हैं।'}`;
    if (await confirmDialog(msg, { ok: 'हटाएँ', danger: true })) { await deleteJob(id); toast('काम हटाया गया', 'success'); }
  }
  return true;
}

export async function render({ root, query, setHeader }) {
  setHeader({ title: 'AI काम / Jobs', sub: 'बैकग्राउंड में चल रहे और पूरे हुए काम', back: 'home' });
  let tab = query.tab === 'history' ? 'history' : 'active';
  let jobs = [];
  const draw = async () => {
    jobs = await listJobs();
    const active = jobs.filter((j) => j.status !== 'done');
    const done = jobs.filter((j) => j.status === 'done');
    const list = tab === 'active' ? active : done;
    const running = jobs.filter((j) => j.status === 'running').length;
    root.innerHTML = `
      <div class="mb-3 grid grid-cols-2 gap-2">
        <button class="tab !py-2 ${tab === 'active' ? 'tab-on' : 'tab-off'}" data-tab="active">⚙️ चालू (${active.length})</button>
        <button class="tab !py-2 ${tab === 'history' ? 'tab-on' : 'tab-off'}" data-tab="history">📜 इतिहास (${done.length})</button>
      </div>
      <div class="mb-3 flex gap-2">
        <a href="#/import?tab=ai&mode=custom" class="btn-primary btn-sm flex-1">✨ नए प्रश्न बनवाएँ</a>
        <a href="#/concepts?ai=1" class="btn-outline btn-sm flex-1">💡 कॉन्सेप्ट बनवाएँ</a>
        <a href="#/import?tab=ai&mode=fixed" class="btn-outline btn-sm flex-1">📑 PDF डिजिटाइज़</a>
      </div>
      ${tab === 'active' ? `<div class="mb-3 rounded-xl bg-slate-100 p-2 text-[11px] text-slate-600 dark:bg-slate-800 dark:text-slate-300">एक साथ ${MAX_PARALLEL} काम चलते हैं (अभी ${running}), बाकी कतार में। ऐप में कहीं भी जाएँ या दूसरा ऐप खोलें — काम चलते रहेंगे (फ़ोन पर सूचना दिखेगी)। ऐप बंद हो जाए तो दोबारा खोलने पर वहीं से जारी होंगे।</div>` : ''}
      <div class="space-y-2">${list.length ? list.map(jobCard).join('') : emptyState(tab === 'active' ? 'कोई चालू काम नहीं' : 'अभी कोई पूरा काम नहीं', tab === 'active' ? 'Import → AI से बनाएँ से नया काम शुरू करें।' : 'पूरे हुए काम यहाँ दिखेंगे — देख, सेव, शेयर या हटा सकते हैं।')}</div>`;
  };
  await draw();
  let pending = null;
  const off = onJobsChange((j) => {
    const old = jobs.find((x) => x.id === j.id);
    // Same status → only patch numbers; anything else (new job, status change, delete) → redraw list.
    if (old && !j.deleted && old.status === j.status && patchCard(root, j)) return;
    if (!pending) pending = setTimeout(() => { pending = null; draw(); }, 300);
  });
  root.addEventListener('click', async (e) => {
    const t = e.target.closest('[data-tab]');
    if (t) { tab = t.dataset.tab; history.replaceState(null, '', `#/jobs?tab=${tab}`); draw(); return; }
    if (await jobActions(e, jobs)) draw();
  });
  return () => { off(); clearTimeout(pending); };
}
