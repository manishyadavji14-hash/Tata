// Background AI jobs: PDF digitising ("fixed") and custom question generation.
// A job keeps running while the user moves around the app (or another app is on screen). Its progress,
// log and partial results are saved in IndexedDB, so it can be watched, paused, resumed, reviewed,
// saved, shared or deleted any time — and it resumes after the app is closed or killed.
import { db, getSettings } from './db.js';
import { uid } from './util.js';

export const MAX_PARALLEL = 3;
export const STATUS = {
  queued: ['⏳', 'कतार में', 'bg-slate-100 text-slate-700 dark:bg-slate-800 dark:text-slate-200'],
  running: ['⚙️', 'चल रहा है', 'bg-brand-100 text-brand-700 dark:bg-brand-900/40 dark:text-brand-300'],
  paused: ['⏸', 'रुका हुआ', 'bg-amber-100 text-amber-800 dark:bg-amber-900/40 dark:text-amber-300'],
  interrupted: ['⚠️', 'बीच में रुका', 'bg-amber-100 text-amber-800 dark:bg-amber-900/40 dark:text-amber-300'],
  error: ['❌', 'त्रुटि', 'bg-rose-100 text-rose-700 dark:bg-rose-900/40 dark:text-rose-300'],
  done: ['✅', 'पूरा', 'bg-emerald-100 text-emerald-700 dark:bg-emerald-900/40 dark:text-emerald-300'],
};
export const isActive = (j) => j.status === 'running' || j.status === 'queued';

const bus = new EventTarget();
const live = new Map();   // jobs that are queued or running (authoritative copy)
const ctrls = new Map();  // id -> AbortController
const timers = new Map(); // throttled saves
const deleted = new Set();

export function onJobsChange(fn) {
  const h = (e) => fn(e.detail);
  bus.addEventListener('change', h);
  return () => bus.removeEventListener('change', h);
}
function emit(job) { bus.dispatchEvent(new CustomEvent('change', { detail: job })); nativeStatus(); }

export async function getJob(id) { return live.get(id) || (await db.get('jobs', id)) || null; }
export async function listJobs() {
  const map = new Map((await db.all('jobs')).map((j) => [j.id, j]));
  for (const [k, v] of live) map.set(k, v);
  return [...map.values()].sort((a, b) => b.createdAt - a.createdAt);
}
export function activeJobs() { return [...live.values()]; }

async function persist(job, now = false) {
  if (deleted.has(job.id)) return;
  job.updatedAt = Date.now();
  if (now) {
    clearTimeout(timers.get(job.id)); timers.delete(job.id);
    await db.put('jobs', job);
    return;
  }
  if (timers.has(job.id)) return;
  timers.set(job.id, setTimeout(() => {
    timers.delete(job.id);
    if (!deleted.has(job.id)) db.put('jobs', job).catch(() => {});
  }, 900));
}

export function jobLog(job, msg, level = 'info') {
  job.log = job.log || [];
  job.log.push({ t: Date.now(), msg: String(msg), level });
  if (job.log.length > 400) job.log.splice(0, job.log.length - 400);
  persist(job);
  emit(job);
}

/** Save the job (e.g. after the user removed questions in review). */
export async function updateJob(job) {
  const cur = live.get(job.id);
  if (cur && cur !== job) { Object.assign(cur, job); job = cur; } // the runner keeps working on the live copy
  await persist(job, true);
  emit(job);
}

/**
 * sources: [{ file, pages }] (new uploads, stored as Blobs) or [{ fileId, name, type, size, pages }] (reused).
 */
export async function createJob({ kind, title, params, sources = [], pasted = '', parentId = null, avoid = [], rootTitle = null }) {
  const out = [];
  for (const s of sources) {
    if (s.fileId) { out.push({ fileId: s.fileId, name: s.name, type: s.type, size: s.size, lastModified: s.lastModified, pages: s.pages || '' }); continue; }
    const f = s.file;
    const id = uid('file');
    await db.put('jobFiles', { id, name: f.name, type: f.type, size: f.size, lastModified: f.lastModified || Date.now(), blob: f });
    out.push({ fileId: id, name: f.name, type: f.type, size: f.size, lastModified: f.lastModified || Date.now(), pages: s.pages || '' });
  }
  const job = {
    id: uid('job'), kind, title: String(title || '').trim() || (kind === 'pdf' ? 'PDF डिजिटाइज़' : kind === 'concepts' ? 'AI कॉन्सेप्ट' : 'AI प्रश्न'),
    status: 'queued', createdAt: Date.now(), updatedAt: Date.now(), startedAt: null, finishedAt: null,
    params, sources: out, pasted, parentId, avoid, rootTitle, progress: { pct: 0, msg: 'कतार में…' }, log: [], state: null, result: null, savedSetId: null, autoResumes: 0,
  };
  job.log.push({ t: Date.now(), msg: `काम बनाया: ${out.length} फ़ाइल${pasted ? ' + पेस्ट किया टेक्स्ट' : ''}`, level: 'info' });
  await db.put('jobs', job);
  live.set(job.id, job);
  await startJob(job.id);
  return job;
}

export async function startJob(id) {
  const job = await getJob(id);
  if (!job || ctrls.has(id) || job.status === 'done') return;
  job.status = 'queued';
  job.error = null;
  job.progress = { ...(job.progress || {}), msg: 'कतार में…' };
  live.set(id, job);
  await persist(job, true);
  emit(job);
  pump();
}

function pump() {
  const running = [...live.values()].filter((j) => j.status === 'running').length;
  const queued = [...live.values()].filter((j) => j.status === 'queued').sort((a, b) => a.createdAt - b.createdAt);
  for (let i = 0; i < Math.min(queued.length, MAX_PARALLEL - running); i++) run(queued[i]);
}

async function loadFiles(job) {
  const out = [];
  for (const s of job.sources || []) {
    const row = await db.get('jobFiles', s.fileId);
    if (!row) { jobLog(job, `⚠️ फ़ाइल "${s.name}" अब मौजूद नहीं — छोड़ी गई`, 'warn'); continue; }
    const file = row.blob instanceof File ? row.blob : new File([row.blob], row.name, { type: row.type, lastModified: row.lastModified });
    out.push({ file, pages: s.pages });
  }
  return out;
}

async function run(job) {
  const controller = new AbortController();
  ctrls.set(job.id, controller);
  job.status = 'running';
  job.startedAt = job.startedAt || Date.now();
  jobLog(job, '▶ शुरू / जारी');
  const log = (m, l) => jobLog(job, m, l);
  const progress = (p) => { job.progress = { pct: p.pct ?? job.progress?.pct ?? 0, msg: p.msg || '' }; persist(job); emit(job); };
  try {
    const files = await loadFiles(job);
    if (job.kind === 'generate') {
      const { runGeneration } = await import('./generate.js');
      await runGeneration(job, { files, signal: controller.signal, log, progress, saveState: () => persist(job, true) });
    } else if (job.kind === 'concepts') {
      const { runConceptJob } = await import('./concepts.js');
      await runConceptJob(job, { files, signal: controller.signal, log, progress, saveState: () => persist(job, true) });
    } else {
      const { importPdf } = await import('./pdf-import.js');
      if (!files.length) throw new Error('PDF फ़ाइल नहीं मिली');
      const settings = { ...(await getSettings()), ...job.params.pdf };
      job.result = await importPdf(files[0].file, { settings, useAI: job.params.useAI, onProgress: progress, onLog: log, signal: controller.signal });
      job.params.draftKey = job.result.stats?.draftKey || job.params.draftKey;
      log(`${job.result.set.questions.length} प्रश्न तैयार`, 'success');
    }
    job.status = 'done';
    job.finishedAt = Date.now();
    job.progress = { pct: 100, msg: 'पूरा हुआ ✓ — देखें, जाँचें और सेव करें' };
    jobLog(job, '✅ पूरा हुआ', 'success');
    notifyDone(job);
  } catch (e) {
    if (e.name === 'AbortError') {
      job.status = 'paused';
      job.progress = { ...job.progress, msg: 'रोका गया — "जारी रखें" से वहीं से शुरू होगा' };
      jobLog(job, '⏸ रोका गया (प्रगति सुरक्षित)', 'warn');
    } else {
      job.status = 'error';
      job.error = e.message;
      job.progress = { ...job.progress, msg: `त्रुटि: ${String(e.message).slice(0, 160)}` };
      jobLog(job, e.message, 'error');
    }
  } finally {
    ctrls.delete(job.id);
    live.delete(job.id);
    await persist(job, true).catch(() => {});
    emit(job);
    pump();
  }
}

export async function pauseJob(id) {
  const c = ctrls.get(id);
  if (c) { c.abort(); return; }
  const job = live.get(id);
  if (job && job.status === 'queued') {
    live.delete(id);
    job.status = 'paused';
    jobLog(job, '⏸ कतार से हटाया', 'warn');
    await persist(job, true);
    emit(job);
  }
}

export async function deleteJob(id) {
  deleted.add(id);
  ctrls.get(id)?.abort();
  clearTimeout(timers.get(id)); timers.delete(id);
  const job = await getJob(id);
  live.delete(id);
  await db.delete('jobs', id);
  if (job) {
    const others = await db.all('jobs');
    for (const s of job.sources || []) if (!others.some((o) => (o.sources || []).some((x) => x.fileId === s.fileId))) await db.delete('jobFiles', s.fileId);
    if (job.kind === 'pdf' && job.params?.draftKey) await db.delete('importDrafts', job.params.draftKey);
  }
  emit({ id, deleted: true });
}

// Restore after an app restart: jobs that were running when the app was closed/killed continue.
let inited = false;
export async function initJobs() {
  if (inited) return;
  inited = true;
  for (const job of await db.all('jobs')) {
    if (job.status !== 'running' && job.status !== 'queued') continue;
    job.autoResumes = (job.autoResumes || 0) + 1;
    if (job.autoResumes <= 3) {
      job.log.push({ t: Date.now(), msg: 'ऐप बंद होने से काम रुका था — अपने-आप फिर शुरू', level: 'warn' });
      await db.put('jobs', job);
      startJob(job.id);
    } else {
      job.status = 'interrupted';
      job.log.push({ t: Date.now(), msg: 'कई बार बीच में रुका — "जारी रखें" दबाएँ', level: 'warn' });
      await db.put('jobs', job);
    }
  }
}

// ---------- Android: keep the app alive with a notification while jobs run ----------
let lastNative = 0, nativeTimer = null;
function nativeStatus() {
  const nb = window.AndroidBridge;
  if (!nb || typeof nb.setBackgroundWork !== 'function') return;
  const send = () => {
    lastNative = Date.now();
    const act = [...live.values()];
    const run = act.filter((j) => j.status === 'running');
    const text = run.length ? run.map((j) => `${j.title.slice(0, 28)} ${j.progress?.pct || 0}%`).join(' • ') : act.length ? 'कतार में' : '';
    try { nb.setBackgroundWork(act.length, text); } catch { /* ignore */ }
  };
  clearTimeout(nativeTimer);
  if (Date.now() - lastNative > 1500 || !live.size) send(); else nativeTimer = setTimeout(send, 1500);
}

function notifyDone(job) {
  const nb = window.AndroidBridge;
  if (nb && typeof nb.notifyDone === 'function') { try { nb.notifyDone(job.title, `${jobQuestionCount(job)} ${job.kind === 'concepts' ? 'कॉन्सेप्ट' : 'प्रश्न'} तैयार`); } catch { /* ignore */ } }
}

/** Number of questions produced so far. */
export function jobQuestionCount(job) {
  if (job.kind === 'generate') return job.state?.questions?.length || 0;
  if (job.kind === 'concepts') return (job.state?.subjects || []).reduce((a, s) => a + s.topics.reduce((b, t) => b + t.concepts.length, 0), 0);
  return job.result?.set?.questions?.length || 0;
}
