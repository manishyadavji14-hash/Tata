// 4-digit code that protects deleting practice sets (and wiping all data).
// Only a salted SHA-256 hash is stored, never the digits themselves.
import { db } from './db.js';
import { modal, toast } from './ui.js';

const KEY = 'deletePin';
let fails = 0, lockedUntil = 0;

async function hash(pin, salt) {
  const data = new TextEncoder().encode(`${salt}:${pin}`);
  const buf = await crypto.subtle.digest('SHA-256', data);
  return [...new Uint8Array(buf)].map((b) => b.toString(16).padStart(2, '0')).join('');
}

export async function hasPin() { return !!(await db.get('settings', KEY))?.value?.hash; }

export async function verifyPin(pin) {
  const row = (await db.get('settings', KEY))?.value;
  if (!row?.hash) return true;
  return (await hash(pin, row.salt)) === row.hash;
}

export async function setPin(pin) {
  if (!/^\d{4}$/.test(pin)) throw new Error('कोड ठीक 4 अंकों का होना चाहिए');
  const salt = [...crypto.getRandomValues(new Uint8Array(12))].map((b) => b.toString(16).padStart(2, '0')).join('');
  await db.put('settings', { key: KEY, value: { salt, hash: await hash(pin, salt), setAt: Date.now() } });
}

export async function removePin() { await db.delete('settings', KEY); }

export const pinInput = (attr = 'data-pin', ph = '• • • •') =>
  `<input ${attr} type="password" inputmode="numeric" pattern="[0-9]*" maxlength="4" autocomplete="off" class="input text-center font-mono text-2xl tracking-[0.6em]" placeholder="${ph}">`;

/** Ask for the code before a protected action. Resolves true when allowed (or no code is set). */
export async function requirePin(reason = 'प्रैक्टिस सेट हटाने के लिए') {
  if (!(await hasPin())) return true;
  return new Promise((resolve) => {
    const m = modal({
      title: '🔒 डिलीट कोड डालें', size: 'sm',
      body: `<div class="mb-3 text-sm text-slate-600 dark:text-slate-300">${reason} 4 अंकों का कोड डालें।</div>${pinInput()}<div class="mt-2 min-h-[1.2em] text-xs text-rose-600" data-err></div>`,
      footer: '<button class="btn-outline" data-close>Cancel</button><button class="btn-danger" data-ok>पुष्टि करें</button>',
      onClose: (v) => resolve(v === true),
    });
    const inp = m.el.querySelector('[data-pin]');
    const err = m.el.querySelector('[data-err]');
    setTimeout(() => inp.focus(), 60);
    const go = async () => {
      if (Date.now() < lockedUntil) { err.textContent = `बहुत बार गलत — ${Math.ceil((lockedUntil - Date.now()) / 1000)} सेकंड बाद कोशिश करें`; return; }
      if (await verifyPin(inp.value)) { fails = 0; m.close(true); return; }
      fails++;
      inp.value = '';
      if (fails >= 5) { lockedUntil = Date.now() + 30000; fails = 0; err.textContent = '5 बार गलत कोड — 30 सेकंड रुकें'; } else err.textContent = `गलत कोड (${fails}/5)`;
    };
    m.el.querySelector('[data-ok]').onclick = go;
    inp.addEventListener('input', () => { inp.value = inp.value.replace(/\D/g, '').slice(0, 4); if (inp.value.length === 4) go(); });
  });
}

/** Settings card markup + wiring. */
export async function pinCardHtml() {
  const on = await hasPin();
  return `<div class="card mb-3 space-y-2" data-pincard>
    <div class="font-bold">🔒 डिलीट कोड (4 अंक)</div>
    <div class="text-xs text-slate-500">${on ? '✅ कोड लगा है — कोई भी प्रैक्टिस सेट हटाने और "सारा डेटा मिटाएँ" से पहले यह कोड माँगा जाएगा।' : 'कोड लगाने पर कोई भी प्रैक्टिस सेट हटाने से पहले यह कोड माँगा जाएगा (बच्चे या कोई और गलती से टेस्ट न मिटा सके)।'}</div>
    ${on ? `<div class="grid grid-cols-2 gap-2"><button class="btn-outline" data-pinact="change">कोड बदलें</button><button class="btn-outline text-rose-600" data-pinact="remove">कोड हटाएँ</button></div>`
      : '<button class="btn-primary w-full" data-pinact="set">कोड सेट करें</button>'}
    <div class="text-[11px] text-slate-400">कोड याद रखें — भूलने पर इसे हटाया नहीं जा सकेगा, केवल ऐप का डेटा साफ़ (Clear data) करके।</div>
  </div>`;
}

export function wirePinCard(root, redraw) {
  root.addEventListener('click', async (e) => {
    const act = e.target.closest('[data-pinact]')?.dataset.pinact;
    if (!act) return;
    if ((act === 'change' || act === 'remove') && !(await requirePin(act === 'remove' ? 'कोड हटाने के लिए पुराना' : 'कोड बदलने के लिए पुराना'))) return;
    if (act === 'remove') { await removePin(); toast('डिलीट कोड हटाया गया', 'success'); redraw(); return; }
    const m = modal({
      title: act === 'change' ? 'नया कोड' : 'डिलीट कोड सेट करें', size: 'sm',
      body: `<label class="label">नया 4 अंकों का कोड</label>${pinInput('data-p1')}<label class="label mt-3">दोबारा डालें</label>${pinInput('data-p2')}<div class="mt-2 min-h-[1.2em] text-xs text-rose-600" data-err></div>`,
      footer: '<button class="btn-outline" data-close>Cancel</button><button class="btn-primary" data-ok>सेव करें</button>',
    });
    m.el.querySelectorAll('input').forEach((i) => i.addEventListener('input', () => { i.value = i.value.replace(/\D/g, '').slice(0, 4); }));
    m.el.querySelector('[data-ok]').onclick = async () => {
      const a = m.el.querySelector('[data-p1]').value, b = m.el.querySelector('[data-p2]').value;
      const err = m.el.querySelector('[data-err]');
      if (!/^\d{4}$/.test(a)) { err.textContent = 'ठीक 4 अंक डालें'; return; }
      if (a !== b) { err.textContent = 'दोनों कोड एक जैसे नहीं हैं'; return; }
      await setPin(a);
      m.close();
      toast('डिलीट कोड सेट हुआ 🔒', 'success');
      redraw();
    };
  });
}
