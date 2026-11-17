// Small UI kit: toasts, modals, confirm dialogs, icons.
import { esc } from './util.js';

const svg = (d, cls = 'h-5 w-5') => `<svg class="${cls}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${d}</svg>`;

export const icon = {
  home: (c) => svg('<path d="M3 11l9-8 9 8"/><path d="M5 10v10h14V10"/>', c),
  upload: (c) => svg('<path d="M12 16V4"/><path d="M7 9l5-5 5 5"/><path d="M4 20h16"/>', c),
  bookmark: (c) => svg('<path d="M6 3h12v18l-6-4-6 4z"/>', c),
  bookmarkFill: (c) => `<svg class="${c || 'h-5 w-5'}" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><path d="M6 3h12v18l-6-4-6 4z"/></svg>`,
  history: (c) => svg('<path d="M3 12a9 9 0 1 0 3-6.7L3 8"/><path d="M3 3v5h5"/><path d="M12 7v5l3 3"/>', c),
  settings: (c) => svg('<circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.7 1.7 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.8-.3 1.7 1.7 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1a1.7 1.7 0 0 0-1.1-1.5 1.7 1.7 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 .3-1.8 1.7 1.7 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1a1.7 1.7 0 0 0 1.5-1.1 1.7 1.7 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.8.3H9a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.8V9a1.7 1.7 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1z"/>', c),
  back: (c) => svg('<path d="M15 18l-6-6 6-6"/>', c),
  moon: (c) => svg('<path d="M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8z"/>', c),
  sun: (c) => svg('<circle cx="12" cy="12" r="4"/><path d="M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4"/>', c),
  grid: (c) => svg('<rect x="3" y="3" width="7" height="7" rx="1"/><rect x="14" y="3" width="7" height="7" rx="1"/><rect x="3" y="14" width="7" height="7" rx="1"/><rect x="14" y="14" width="7" height="7" rx="1"/>', c),
  pause: (c) => svg('<rect x="6" y="4" width="4" height="16" rx="1"/><rect x="14" y="4" width="4" height="16" rx="1"/>', c),
  play: (c) => svg('<path d="M6 4l14 8-14 8z"/>', c),
  close: (c) => svg('<path d="M18 6L6 18M6 6l12 12"/>', c),
  copy: (c) => svg('<rect x="9" y="9" width="12" height="12" rx="2"/><path d="M5 15V5a2 2 0 0 1 2-2h10"/>', c),
  file: (c) => svg('<path d="M14 3H6a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V9z"/><path d="M14 3v6h6"/>', c),
  pdf: (c) => svg('<path d="M14 3H6a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V9z"/><path d="M14 3v6h6"/><path d="M8 15h1.5a1.5 1.5 0 0 0 0-3H8v5"/>', c),
  trash: (c) => svg('<path d="M3 6h18"/><path d="M8 6V4h8v2"/><path d="M19 6l-1 14H6L5 6"/>', c),
  download: (c) => svg('<path d="M12 4v12"/><path d="M7 11l5 5 5-5"/><path d="M4 20h16"/>', c),
  chart: (c) => svg('<path d="M4 20V10M10 20V4M16 20v-7M22 20H2"/>', c),
  clock: (c) => svg('<circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/>', c),
  spark: (c) => svg('<path d="M12 3l1.8 5.2L19 10l-5.2 1.8L12 17l-1.8-5.2L5 10l5.2-1.8z"/><path d="M19 17l.8 2.2L22 20l-2.2.8L19 23l-.8-2.2L16 20l2.2-.8z"/>', c),
  send: (c) => svg('<path d="M22 2L11 13"/><path d="M22 2l-7 20-4-9-9-4z"/>', c),
  lang: (c) => svg('<path d="M4 5h8M8 3v2M6 5c0 4 3 7 6 8M10 5c-1 4-4 7-6 8"/><path d="M13 21l4-9 4 9M14.5 18h5"/>', c),
  check: (c) => svg('<path d="M20 6L9 17l-5-5"/>', c),
  x: (c) => svg('<path d="M18 6L6 18M6 6l12 12"/>', c),
  play2: (c) => svg('<circle cx="12" cy="12" r="9"/><path d="M10 8l6 4-6 4z"/>', c),
  info: (c) => svg('<circle cx="12" cy="12" r="9"/><path d="M12 16v-4M12 8h.01"/>', c),
  bulb: (c) => svg('<path d="M9 18h6"/><path d="M10 21h4"/><path d="M12 3a6 6 0 0 0-3.5 10.9c.6.5 1 1.2 1 2V17h5v-1.1c0-.8.4-1.5 1-2A6 6 0 0 0 12 3z"/>', c),
  target: (c) => svg('<circle cx="12" cy="12" r="9"/><circle cx="12" cy="12" r="5"/><circle cx="12" cy="12" r="1"/>', c),
};

// ---------- Toasts ----------
export function toast(msg, type = 'info', ms = 2600) {
  const root = document.getElementById('toast-root');
  const el = document.createElement('div');
  const color = { info: 'bg-slate-900 text-white dark:bg-white dark:text-slate-900', success: 'bg-emerald-600 text-white', error: 'bg-rose-600 text-white', warn: 'bg-amber-500 text-slate-900' }[type] || '';
  el.className = `fade-in pointer-events-auto max-w-[92vw] rounded-xl px-4 py-2.5 text-sm font-medium shadow-lg ${color}`;
  el.textContent = msg;
  root.appendChild(el);
  setTimeout(() => { el.style.opacity = '0'; el.style.transition = 'opacity .3s'; setTimeout(() => el.remove(), 320); }, ms);
}

// ---------- Modals ----------
// Returns { el, close }. `onClose` fires once when the modal closes.
export function modal({ title = '', body = '', footer = '', size = 'md', onClose, dismissible = true, full = false } = {}) {
  const root = document.getElementById('modal-root');
  const wrap = document.createElement('div');
  const width = { sm: 'sm:max-w-sm', md: 'sm:max-w-lg', lg: 'sm:max-w-2xl', xl: 'sm:max-w-4xl' }[size];
  wrap.className = 'fixed inset-0 z-50 flex items-end justify-center bg-black/50 sm:items-center fade-in';
  wrap.innerHTML = `
    <div class="flex ${full ? 'h-[100dvh] sm:h-[90vh]' : 'max-h-[92dvh]'} w-full ${width} flex-col overflow-hidden rounded-t-2xl bg-white shadow-xl dark:bg-slate-900 sm:rounded-2xl" role="dialog" aria-modal="true">
      ${title !== null ? `<div class="flex items-center gap-2 border-b border-slate-200 px-4 py-3 dark:border-slate-800">
        <h2 class="flex-1 text-base font-bold">${title}</h2>
        ${dismissible ? `<button class="btn-ghost btn-sm" data-close aria-label="Close">${icon.close('h-5 w-5')}</button>` : ''}
      </div>` : ''}
      <div class="modal-body flex-1 overflow-y-auto p-4">${body}</div>
      ${footer ? `<div class="modal-footer flex flex-wrap justify-end gap-2 border-t border-slate-200 px-4 py-3 safe-bottom dark:border-slate-800">${footer}</div>` : ''}
    </div>`;
  let closed = false;
  const close = (val) => {
    if (closed) return;
    closed = true;
    wrap.remove();
    document.removeEventListener('keydown', onKey);
    onClose && onClose(val);
  };
  const onKey = (e) => { if (e.key === 'Escape' && dismissible) close(); };
  wrap.addEventListener('click', (e) => {
    if (e.target === wrap && dismissible) close();
    if (e.target.closest('[data-close]')) close();
  });
  document.addEventListener('keydown', onKey);
  root.appendChild(wrap);
  return { el: wrap, close };
}

export function confirmDialog(message, { title = 'पुष्टि करें / Confirm', ok = 'OK', cancel = 'Cancel', danger = false } = {}) {
  return new Promise((resolve) => {
    const m = modal({
      title: esc(title), size: 'sm',
      body: `<div class="text-sm leading-relaxed">${message}</div>`,
      footer: `<button class="btn-outline" data-act="no">${esc(cancel)}</button><button class="${danger ? 'btn-danger' : 'btn-primary'}" data-act="yes">${esc(ok)}</button>`,
      onClose: (v) => resolve(v === true),
    });
    m.el.querySelector('[data-act=no]').onclick = () => m.close(false);
    m.el.querySelector('[data-act=yes]').onclick = () => m.close(true);
  });
}

export function alertDialog(message, title = 'सूचना / Notice') {
  return new Promise((resolve) => {
    const m = modal({ title: esc(title), size: 'sm', body: `<div class="text-sm leading-relaxed">${message}</div>`, footer: '<button class="btn-primary" data-act="ok">OK</button>', onClose: () => resolve() });
    m.el.querySelector('[data-act=ok]').onclick = () => m.close();
  });
}

/** Ask for one line of text. `suggest` fills a pick-list under the input. Resolves to the text or null. */
export function promptDialog({ title = '', label = '', value = '', ok = 'Save', hint = '', suggest = [] } = {}) {
  return new Promise((resolve) => {
    const lid = `dl${Math.random().toString(36).slice(2, 8)}`;
    const m = modal({
      title: esc(title), size: 'sm',
      body: `<label class="label">${esc(label)}</label><input class="input" data-v value="${esc(value)}" ${suggest.length ? `list="${lid}"` : ''} autocomplete="off">
        ${suggest.length ? `<datalist id="${lid}">${suggest.map((s) => `<option value="${esc(s)}"></option>`).join('')}</datalist>` : ''}
        ${hint ? `<div class="mt-2 text-xs text-slate-500">${hint}</div>` : ''}`,
      footer: `<button class="btn-outline" data-close>Cancel</button><button class="btn-primary" data-ok>${esc(ok)}</button>`,
      onClose: (v) => resolve(typeof v === 'string' ? v : null),
    });
    const inp = m.el.querySelector('[data-v]');
    setTimeout(() => { inp.focus(); inp.select(); }, 50);
    const done = () => { const v = inp.value.trim(); if (v) m.close(v); };
    m.el.querySelector('[data-ok]').onclick = done;
    inp.addEventListener('keydown', (e) => { if (e.key === 'Enter') done(); });
  });
}

export function pickFile(accept) {
  return new Promise((resolve) => {
    const inp = document.createElement('input');
    inp.type = 'file';
    inp.accept = accept;
    inp.style.display = 'none';
    inp.onchange = () => { resolve(inp.files && inp.files[0] ? inp.files[0] : null); inp.remove(); };
    inp.addEventListener('cancel', () => { resolve(null); inp.remove(); });
    document.body.appendChild(inp);
    inp.click();
  });
}

export function emptyState(title, sub = '', action = '') {
  return `<div class="card flex flex-col items-center gap-2 py-10 text-center">
    <div class="text-4xl">📭</div><div class="font-semibold">${title}</div>
    ${sub ? `<div class="max-w-xs text-sm text-slate-500">${sub}</div>` : ''}${action}</div>`;
}
