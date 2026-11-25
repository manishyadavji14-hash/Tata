// App bootstrap: theme, demo seeding, service worker, hash router and the shell (header + bottom nav).
import { db, getSettings, saveSettings } from './db.js';
import { normalizeSet } from './schema.js';
import { DEMO_SET } from './demo-set.js';
import { icon, toast, confirmDialog } from './ui.js';
import { $, esc } from './util.js';

export const APP_VERSION = '1.9.5';

const routes = {
  home: () => import('./views/home.js'),
  import: () => import('./views/import.js'),
  set: () => import('./views/set.js'),
  pretest: () => import('./views/pretest.js'),
  exam: () => import('./views/exam.js'),
  result: () => import('./views/result.js'),
  solutions: () => import('./views/solutions.js'),
  history: () => import('./views/history.js'),
  bookmarks: () => import('./views/bookmarks.js'),
  practice: () => import('./views/practice.js'),
  analytics: () => import('./views/analytics.js'),
  settings: () => import('./views/settings.js'),
  share: () => import('./views/share.js'),
  jobs: () => import('./views/jobs.js'),
  job: () => import('./views/job.js'),
  concepts: () => import('./views/concepts.js'),
  focus: () => import('./views/focus.js'),
  bank: () => import('./views/bank.js'),
  builder: () => import('./views/builder.js'),
  mastery: () => import('./views/mastery.js'),
};

const NAV = [
  ['home', 'होम', 'home'],
  ['import', 'इम्पोर्ट', 'upload'],
  ['practice', 'अभ्यास', 'target'],
  ['concepts', 'कॉन्सेप्ट', 'bulb'],
  ['history', 'हिस्ट्री', 'history'],
  ['settings', 'सेटिंग्स', 'settings'],
];

let cleanup = null;
let leaveGuard = null; // () => Promise<boolean>
let renderSeq = 0;

// ---------- Theme ----------
export function applyTheme(theme) {
  const dark = theme === 'dark' || (theme === 'system' && matchMedia('(prefers-color-scheme: dark)').matches);
  document.documentElement.classList.toggle('dark', dark);
  try { localStorage.setItem('abhyas-theme', theme); } catch { /* ignore */ }
  const btn = $('#hdr-theme');
  if (btn) btn.innerHTML = dark ? icon.sun() : icon.moon();
  document.querySelector('meta[name=theme-color]').setAttribute('content', dark ? '#020617' : '#1b5ef5');
  if (window.AndroidBridge && window.AndroidBridge.setDarkMode) window.AndroidBridge.setDarkMode(dark);
}

// ---------- Navigation ----------
export function navigate(path, { replace = false } = {}) {
  const h = `#/${path.replace(/^#?\/?/, '')}`;
  if (replace) { history.replaceState(history.state, '', h); route(); } else location.hash = h;
}

export function setLeaveGuard(fn) { leaveGuard = fn; }

// Re-render the current route (after an in-place edit).
export function refresh() { lastHash = location.hash; route(); }

function parseHash() {
  const raw = location.hash.replace(/^#\/?/, '') || 'home';
  const [path, qs] = raw.split('?');
  const [name, ...params] = path.split('/').map(decodeURIComponent);
  return { name: routes[name] ? name : 'home', params, query: Object.fromEntries(new URLSearchParams(qs || '')) };
}

export function setHeader({ title = 'Abhyas CBT', sub = '', back = null, actions = '' } = {}) {
  $('#hdr-title').textContent = title;
  const s = $('#hdr-sub');
  s.textContent = sub;
  s.classList.toggle('hidden', !sub);
  const b = $('#hdr-back');
  b.classList.toggle('hidden', !back);
  b.innerHTML = icon.back();
  b.onclick = back ? () => (typeof back === 'function' ? back() : navigate(back)) : null;
  $('#hdr-actions').innerHTML = actions;
}

function renderNav(active) {
  const grid = $('#bottom-nav > div');
  grid.innerHTML = NAV.map(([r, label, ic]) => `
    <a href="#/${r}" class="flex flex-col items-center gap-0.5 py-2 text-[11px] font-medium ${r === active ? 'text-brand-600 dark:text-brand-400' : 'text-slate-500'}">
      ${icon[ic]('h-5 w-5')}<span>${label}</span></a>`).join('');
}

function setChrome(full) {
  $('#app-header').classList.toggle('hidden', full);
  $('#bottom-nav').classList.toggle('hidden', full);
  const v = $('#view');
  v.className = full ? 'flex flex-1 flex-col' : 'flex-1 px-3 pb-24 pt-3';
}

// Every history entry of the app carries its position ({ idx }) so the back button knows whether
// there is an in-app page to go back to, and a cancelled "leave?" dialog can restore the exact entry.
let lastHash = location.hash;
let lastIdx = Number.isInteger(history.state?.idx) ? history.state.idx : 0;
let restoring = false;
let routed = false;
const entryIdx = () => (Number.isInteger(history.state?.idx) ? history.state.idx : null);
async function route() {
  const target = location.hash;
  if (restoring) { // we are only stepping back to the page the user chose to stay on
    restoring = false;
    if (target === lastHash) return;
  }
  const known = entryIdx();
  if (leaveGuard && target !== lastHash) {
    const ok = await leaveGuard();
    if (!ok) {
      if (known === null) { restoring = true; history.back(); } // undo the new entry
      else if (known !== lastIdx) { restoring = true; history.go(lastIdx - known); } // undo back/forward
      else history.replaceState(history.state, '', lastHash);
      return;
    }
  }
  lastIdx = known !== null ? known : routed ? lastIdx + 1 : 0;
  routed = true;
  if (known === null) history.replaceState({ ...(history.state || {}), idx: lastIdx }, '', location.href);
  lastHash = target;
  const seq = ++renderSeq;
  const { name, params, query } = parseHash();
  if (cleanup) { try { cleanup(); } catch { /* ignore */ } cleanup = null; }
  leaveGuard = null;
  document.getElementById('modal-root').innerHTML = '';
  const full = name === 'exam' || name === 'focus';
  setChrome(full);
  if (!full) renderNav(['home', 'import', 'practice', 'concepts', 'history', 'settings'].includes(name) ? name : name === 'analytics' ? 'history' : name === 'bookmarks' ? 'practice' : name === 'jobs' || name === 'job' ? 'import' : 'home');
  setHeader({});
  // Fresh <main> per route so event listeners of the previous view are dropped.
  const old = $('#view');
  const view = old.cloneNode(false);
  old.replaceWith(view);
  setChrome(full);
  try {
    const mod = await routes[name]();
    if (seq !== renderSeq) return;
    view.innerHTML = '';
    const settings = await getSettings();
    cleanup = (await mod.render({ root: view, params, query, settings, navigate, setHeader, setLeaveGuard })) || null;
    if (!full) window.scrollTo(0, 0);
  } catch (e) {
    console.error(e);
    view.innerHTML = `<div class="card border-rose-300 text-sm"><div class="mb-1 font-bold text-rose-600">त्रुटि / Error</div>${esc(e.message || e)}</div>`;
  }
}

// Android hardware / gesture back (called from the native wrapper). Returns true if handled.
// Order: close the top dialog → close the palette drawer → previous in-app page → (on the first
// page) home → on home ask once before leaving the app. Nothing is lost by an accidental back.
let exitAsking = false;
const typedIn = (el) => [...el.querySelectorAll('textarea, input:not([type]), input[type=text], input[type=search], input[type=url], input[type=password]')]
  .some((x) => x.value.trim() && x.value !== x.defaultValue);
window.__abhyasBack = () => {
  const modals = document.querySelectorAll('#modal-root > div');
  if (modals.length) {
    const last = modals[modals.length - 1];
    const closeBtn = last.querySelector('[data-close]');
    if (!closeBtn) return true; // a dialog that must be answered (e.g. running task)
    if (typedIn(last) && !last.dataset.backAsked) {
      last.dataset.backAsked = '1';
      confirmDialog('इस खिड़की में लिखा/चुना हुआ हट जाएगा। फिर भी बंद करें?', { title: 'बंद करें?', ok: 'हाँ, बंद करें', cancel: 'नहीं' })
        .then((ok) => { if (ok) closeBtn.click(); else delete last.dataset.backAsked; });
      return true;
    }
    closeBtn.click();
    return true;
  }
  const drawer = document.querySelector('[data-drawer-open]:not(.hidden)');
  if (drawer) { drawer.click(); return true; }
  const { name } = parseHash();
  if (name !== 'home') {
    if (lastIdx > 0) history.back();
    else navigate('home', { replace: true }); // opened directly on an inner page: go home, don't quit
    return true;
  }
  if (exitAsking) return true;
  exitAsking = true;
  confirmDialog('क्या आप Abhyas CBT से बाहर निकलना चाहते हैं?', { title: 'ऐप बंद करें?', ok: 'बाहर निकलें', cancel: 'ऐप में रहें' }).then((ok) => {
    exitAsking = false;
    if (ok && window.AndroidBridge?.exitApp) window.AndroidBridge.exitApp();
  });
  return true;
};

// ---------- Files shared into the app (Android: Telegram/WhatsApp/file manager → Abhyas CBT) ----------
window.__abhyasTakeIncoming = () => {
  try { return JSON.parse((window.AndroidBridge && window.AndroidBridge.takeIncoming && window.AndroidBridge.takeIncoming()) || '[]'); } catch { return []; }
};
window.__abhyasIncomingReady = () => {
  if (window.__abhyasOnIncoming) window.__abhyasOnIncoming();
  else navigate('import?tab=json&incoming=1');
};

// ---------- Demo set ----------
export async function seedDemo(force = false) {
  const s = await getSettings();
  if (s.demoSeeded && !force) return;
  const { set } = normalizeSet(DEMO_SET, { source: 'demo' });
  await db.put('sets', { ...set, id: 'demo_supertet_mini_01', isDemo: true, createdAt: Date.now() });
  await saveSettings({ demoSeeded: true });
}

async function boot() {
  const s = await getSettings();
  applyTheme(s.theme);
  matchMedia('(prefers-color-scheme: dark)').addEventListener('change', async () => applyTheme((await getSettings()).theme));
  $('#hdr-theme').onclick = async () => {
    const isDark = document.documentElement.classList.contains('dark');
    const theme = isDark ? 'light' : 'dark';
    await saveSettings({ theme });
    applyTheme(theme);
  };
  try { await seedDemo(); } catch (e) { console.error('demo seed failed', e); }
  // Background AI jobs: header indicator + resume jobs that were running when the app closed.
  import('./jobs.js').then(async (J) => {
    const chip = $('#hdr-jobs');
    const upd = () => { const n = J.activeJobs().length; chip.classList.toggle('hidden', !n); chip.classList.toggle('flex', !!n); chip.querySelector('[data-n]').textContent = n; };
    J.onJobsChange(upd);
    await J.initJobs();
    upd();
  }).catch((e) => console.error('jobs init failed', e));
  window.__abhyasOpenJobs = () => navigate('jobs');
  if (navigator.storage && navigator.storage.persist) navigator.storage.persist().catch(() => {});
  window.addEventListener('hashchange', route);
  await route();
  try { if (window.AndroidBridge?.hasIncoming && window.AndroidBridge.hasIncoming()) window.__abhyasIncomingReady(); } catch { /* ignore */ }
  if ('serviceWorker' in navigator && !window.AndroidBridge && (location.protocol === 'https:' || location.hostname === 'localhost')) {
    navigator.serviceWorker.register('./sw.js').then((reg) => {
      reg.addEventListener('updatefound', () => {
        const w = reg.installing;
        w && w.addEventListener('statechange', () => {
          if (w.state === 'installed' && navigator.serviceWorker.controller) toast('नया अपडेट तैयार — ऐप दोबारा खोलें / Update ready, reopen the app', 'info', 5000);
        });
      });
    }).catch((e) => console.warn('SW registration failed', e));
  }
}

window.addEventListener('error', (e) => console.error(e.error || e.message));
window.addEventListener('unhandledrejection', (e) => { console.error(e.reason); toast(String(e.reason?.message || e.reason).slice(0, 140), 'error', 4000); });

boot();
