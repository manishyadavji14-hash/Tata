// "Ask AI / डाउट पूछें" — per-question doubt-solving chat, history saved in IndexedDB.
import { db } from './db.js';
import { chat, aiConfig, isConfigured } from './ai.js';
import { doubtSystemPrompt } from './prompts.js';
import { chatKey } from './store.js';
import { modal, icon, toast } from './ui.js';
import { esc, renderRich, plainText, copyText } from './util.js';

const QUICK = [
  'इसे आसान भाषा में स्टेप-बाय-स्टेप समझाओ',
  'इसे शॉर्ट ट्रिक से समझाओ',
  'बाकी विकल्प गलत क्यों हैं?',
  'इस टॉपिक के 5 और सवाल बनाओ (उत्तर सहित)',
  'Explain in English',
  'इस टॉपिक के मुख्य नियम/सूत्र बताओ',
];

export async function openDoubtChat(set, q, { userAnswer = null } = {}) {
  const key = chatKey(set.id, q.q_no);
  const saved = (await db.get('chats', key)) || { key, setId: set.id, q_no: q.q_no, messages: [] };
  const cfg = await aiConfig();
  const configured = isConfigured(cfg);
  let controller = null;

  const m = modal({
    title: `${icon.spark('inline h-5 w-5 text-violet-600')} Ask AI — Q.${q.q_no}`,
    size: 'lg', full: true,
    body: `
      <div class="mb-3 rounded-xl bg-slate-100 p-3 text-sm dark:bg-slate-800">
        <div class="text-[11px] font-semibold uppercase text-slate-500">${esc(q.section)} • ${esc(q.topic)}</div>
        <div class="mt-1">${esc(plainText(q.question_hi || q.question_en, 220))}</div>
        <div class="mt-1 text-xs text-slate-500">सही उत्तर: <b>${q.correct_option || '?'}</b>${userAnswer ? ` • आपका: <b>${userAnswer}</b>` : ''}</div>
      </div>
      ${configured ? '' : `<div class="mb-3 rounded-xl border border-amber-300 bg-amber-50 p-3 text-sm text-amber-900 dark:border-amber-700 dark:bg-amber-950/40 dark:text-amber-200">AI चैट के लिए पहले <a href="#/settings" class="font-bold underline" data-close>Settings</a> में API Key, Base URL और Model सेव करें।</div>`}
      <div data-msgs class="space-y-3"></div>`,
    footer: `
      <div class="w-full">
        <div class="no-scrollbar mb-2 flex gap-2 overflow-x-auto">${QUICK.map((t) => `<button class="tab tab-off" data-quick="${esc(t)}">${esc(t)}</button>`).join('')}</div>
        <form data-form class="flex items-end gap-2">
          <textarea data-input rows="1" class="input max-h-32 min-h-[44px] flex-1 resize-none" placeholder="अपना डाउट लिखें… / Type your doubt…"></textarea>
          <button class="btn-primary h-11 w-11 !p-0" data-send aria-label="Send">${icon.send('h-5 w-5')}</button>
        </form>
        <div class="mt-1 flex justify-between text-[11px] text-slate-400"><span>${esc(cfg.model || '')}</span><button type="button" class="underline" data-clear>चैट साफ़ करें / Clear</button></div>
      </div>`,
    onClose: () => controller && controller.abort(),
  });

  const box = m.el.querySelector('[data-msgs]');
  const input = m.el.querySelector('[data-input]');
  const body = m.el.querySelector('.modal-body');
  const scrollDown = () => { body.scrollTop = body.scrollHeight; };

  const bubble = (msg, i) => msg.role === 'user'
    ? `<div class="flex justify-end"><div class="max-w-[85%] whitespace-pre-wrap rounded-2xl rounded-br-sm bg-brand-600 px-3 py-2 text-sm text-white">${esc(msg.text)}</div></div>`
    : `<div class="flex justify-start"><div class="max-w-[92%] rounded-2xl rounded-bl-sm bg-slate-100 px-3 py-2 text-[15px] dark:bg-slate-800">
        <div class="rich">${renderRich(msg.text, { markdown: true })}</div>
        <button class="mt-1 text-[11px] text-slate-500 underline" data-copy="${i}">कॉपी / Copy</button></div></div>`;

  const draw = (pending = false) => {
    box.innerHTML = saved.messages.length || pending
      ? saved.messages.map(bubble).join('') + (pending ? '<div class="flex"><div class="typing rounded-2xl bg-slate-100 px-3 py-3 text-slate-500 dark:bg-slate-800"><span></span><span></span><span></span></div></div>' : '')
      : '<div class="py-6 text-center text-sm text-slate-500">नीचे से कोई सवाल चुनें या अपना डाउट लिखें।<br>e.g. "इसे ट्रिक से समझाओ"</div>';
    scrollDown();
  };
  draw();

  const send = async (text) => {
    text = String(text || '').trim();
    if (!text || controller) return;
    if (!configured) { toast('पहले Settings में API सेट करें', 'warn'); return; }
    saved.messages.push({ role: 'user', text, at: Date.now() });
    input.value = '';
    draw(true);
    controller = new AbortController();
    try {
      const passage = q.passage_id && set.passages[q.passage_id] ? (set.passages[q.passage_id].text_hi || set.passages[q.passage_id].text_en) : '';
      // Providers require alternating user/assistant turns starting with user: drop errors, merge repeats.
      const history = [];
      for (const x of saved.messages.filter((y) => !y.error).slice(-12)) {
        const last = history[history.length - 1];
        if (last && last.role === x.role) last.text += `\n\n${x.text}`;
        else history.push({ role: x.role, text: x.text });
      }
      while (history.length && history[0].role !== 'user') history.shift();
      const r = await chat({ system: doubtSystemPrompt(q, { passage, userAnswer }), messages: history, maxTokens: 3000, temperature: 0.5, signal: controller.signal }, cfg);
      saved.messages.push({ role: 'assistant', text: r.text || '(खाली जवाब / empty reply)', at: Date.now() });
      await db.put('chats', saved);
    } catch (e) {
      if (e.name !== 'AbortError') {
        saved.messages.push({ role: 'assistant', text: `⚠️ ${e.message}`, at: Date.now(), error: true });
      }
    } finally {
      controller = null;
      if (m.el.isConnected) draw();
    }
  };

  m.el.querySelector('[data-form]').onsubmit = (e) => { e.preventDefault(); send(input.value); };
  input.addEventListener('keydown', (e) => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); send(input.value); } });
  input.addEventListener('input', () => { input.style.height = 'auto'; input.style.height = `${Math.min(128, input.scrollHeight)}px`; });
  m.el.addEventListener('click', async (e) => {
    const qb = e.target.closest('[data-quick]');
    if (qb) send(qb.dataset.quick);
    const cp = e.target.closest('[data-copy]');
    if (cp) { await copyText(saved.messages[Number(cp.dataset.copy)].text); toast('कॉपी हो गया', 'success'); }
    if (e.target.closest('[data-clear]')) { saved.messages = []; await db.delete('chats', key); draw(); }
  });
}
