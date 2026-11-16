import { db, saveSettings, exportAll, importAll } from '../db.js';
import { PROVIDERS, testConnection } from '../ai.js';
import { applyTheme, seedDemo, APP_VERSION } from '../app.js';
import { toast, confirmDialog, icon } from '../ui.js';
import { esc, downloadText, readFile } from '../util.js';

export async function render({ root, settings: s, setHeader }) {
  setHeader({ title: 'सेटिंग्स / Settings' });
  const est = navigator.storage?.estimate ? await navigator.storage.estimate().catch(() => null) : null;
  const persisted = navigator.storage?.persisted ? await navigator.storage.persisted().catch(() => false) : false;

  root.innerHTML = `
    <form class="card mb-3 space-y-3" data-ai autocomplete="off">
      <div class="flex items-center gap-2 font-bold">${icon.spark('h-5 w-5 text-violet-600')} AI / API सेटिंग</div>
      <div><label class="label">Provider / API प्रकार</label>
        <select class="input" name="provider">${Object.entries(PROVIDERS).map(([k, p]) => `<option value="${k}" ${s.provider === k ? 'selected' : ''}>${esc(p.label)}</option>`).join('')}</select></div>
      <div><label class="label">API Base URL</label><input class="input font-mono text-sm" name="apiBase" value="${esc(s.apiBase)}" inputmode="url" spellcheck="false"></div>
      <div><label class="label">API Key</label>
        <div class="flex gap-2"><input class="input font-mono text-sm" name="apiKey" type="password" value="${esc(s.apiKey)}" spellcheck="false" placeholder="sk-… / AIza… / sk-ant-…">
        <button type="button" class="btn-outline !px-3" data-act="show">👁</button></div>
        <div class="mt-1 text-[11px] text-slate-500">Key सिर्फ़ इसी डिवाइस के IndexedDB में रहती है, बैकअप फ़ाइल में शामिल नहीं होती।</div></div>
      <div class="grid grid-cols-3 gap-2">
        <div class="col-span-2"><label class="label">Model Name</label><input class="input font-mono text-sm" name="model" value="${esc(s.model)}" spellcheck="false"></div>
        <div><label class="label">Temperature</label><input class="input" name="temperature" type="number" min="0" max="1.5" step="0.1" value="${s.temperature}"></div>
      </div>
      <div class="text-[11px] text-slate-500">उदाहरण: OpenAI <code>https://api.openai.com/v1</code> + <code>gpt-4o-mini</code> • OpenRouter <code>https://openrouter.ai/api/v1</code> • Gemini <code>gemini-2.5-flash</code> • Claude <code>claude-sonnet-4-5</code> • कोई भी OpenAI-संगत एंडपॉइंट (Kiro/लोकल) — "OpenAI-compatible" चुनें।</div>
      <div class="grid grid-cols-2 gap-2">
        <button type="button" class="btn-outline" data-act="test">🔌 Test Connection</button>
        <button class="btn-primary" type="submit">Save</button>
      </div>
      <div data-test class="hidden rounded-lg p-2 text-xs"></div>
    </form>

    <div class="card mb-3 space-y-3">
      <div class="font-bold">📑 PDF प्रोसेसिंग</div>
      <div class="grid grid-cols-2 gap-2">
        <div><label class="label">मोड</label><select class="input" name="pdfMode">
          <option value="hybrid" ${s.pdfMode === 'hybrid' ? 'selected' : ''}>Hybrid</option>
          <option value="vision" ${s.pdfMode === 'vision' ? 'selected' : ''}>Vision</option>
          <option value="text" ${s.pdfMode === 'text' ? 'selected' : ''}>Text</option></select></div>
        <div><label class="label">Render Scale</label><select class="input" name="renderScale">
          ${[1.2, 1.4, 1.6, 2, 2.5].map((v) => `<option value="${v}" ${Number(s.renderScale) === v ? 'selected' : ''}>${v}× ${v === 1.6 ? '(default)' : ''}</option>`).join('')}</select></div>
      </div>
      <label class="flex items-center gap-3 text-sm"><input type="checkbox" class="h-5 w-5 accent-brand-600" name="autoSolve" ${s.autoSolve ? 'checked' : ''}> AI से सॉल्यूशन + टॉपिक टैगिंग अपने-आप बनाएँ</label>
      <div><label class="label">वॉटरमार्क / कोचिंग नाम (हर लाइन में एक) — टेक्स्ट से हटाए जाएँगे</label>
        <textarea class="input text-sm" rows="3" name="watermarks" placeholder="जैसे: ABC Coaching&#10;www.example.com&#10;Mob. 9876543210">${esc(s.watermarks)}</textarea></div>
      <button class="btn-primary w-full" data-act="savepdf">Save</button>
    </div>

    <div class="card mb-3 space-y-3">
      <div class="font-bold">🎨 दिखावट / Appearance</div>
      <div><label class="label">थीम</label><div class="grid grid-cols-3 gap-2">
        ${[['system', 'System'], ['light', '☀️ Light'], ['dark', '🌙 Dark']].map(([v, l]) => `<button class="tab !py-2 ${s.theme === v ? 'tab-on' : 'tab-off'}" data-theme="${v}">${l}</button>`).join('')}</div></div>
      <div><label class="label">डिफ़ॉल्ट भाषा</label><div class="grid grid-cols-3 gap-2">
        ${[['hi', 'हिंदी'], ['en', 'English'], ['both', 'दोनों']].map(([v, l]) => `<button class="tab !py-2 ${s.lang === v ? 'tab-on' : 'tab-off'}" data-lang="${v}">${l}</button>`).join('')}</div></div>
    </div>

    <div class="card mb-3 space-y-2">
      <div class="font-bold">💾 डेटा / Data</div>
      <div class="text-xs text-slate-500">स्टोरेज: ${est ? `${(est.usage / 1048576).toFixed(1)} MB उपयोग${est.quota ? ` / ${(est.quota / 1073741824).toFixed(1)} GB` : ''}` : '—'} • ${persisted ? '🔒 Persistent storage चालू' : 'ब्राउज़र जगह कम होने पर डेटा हटा सकता है — बैकअप लेते रहें'}</div>
      <button class="btn-outline w-full" data-act="backup">${icon.download('h-4 w-4')} पूरा बैकअप (JSON) डाउनलोड</button>
      <label class="btn-outline w-full cursor-pointer">${icon.upload('h-4 w-4')} बैकअप से रिस्टोर<input type="file" accept=".json,application/json" class="hidden" data-restore></label>
      <button class="btn-outline w-full" data-act="demo">डेमो प्रैक्टिस सेट दोबारा जोड़ें</button>
      <button class="btn-outline w-full text-rose-600" data-act="wipe">${icon.trash('h-4 w-4')} सारा डेटा मिटाएँ</button>
    </div>
    <div class="pb-4 text-center text-[11px] text-slate-400">Abhyas CBT v${APP_VERSION} • Offline-first • PDF.js + KaTeX + IndexedDB</div>`;

  const form = root.querySelector('[data-ai]');
  const f = (n) => root.querySelector(`[name=${n}]`);
  const readAi = () => ({
    provider: f('provider').value,
    apiBase: f('apiBase').value.trim().replace(/\/+$/, ''),
    apiKey: f('apiKey').value.trim(),
    model: f('model').value.trim(),
    temperature: Math.min(1.5, Math.max(0, Number(f('temperature').value) || 0)),
  });

  f('provider').onchange = () => {
    const p = PROVIDERS[f('provider').value];
    const known = Object.values(PROVIDERS).map((x) => x.base);
    if (!f('apiBase').value || known.includes(f('apiBase').value.trim())) f('apiBase').value = p.base;
    if (!f('model').value || Object.values(PROVIDERS).some((x) => x.model === f('model').value.trim())) f('model').value = p.model;
  };

  form.onsubmit = async (e) => {
    e.preventDefault();
    const v = readAi();
    if (v.apiBase && !/^https?:\/\//i.test(v.apiBase)) { toast('Base URL http:// या https:// से शुरू होना चाहिए', 'error'); return; }
    await saveSettings(v);
    Object.assign(s, v);
    toast('API सेटिंग सेव हुई ✓', 'success');
  };

  root.addEventListener('click', async (e) => {
    const th = e.target.closest('[data-theme]');
    if (th) {
      await saveSettings({ theme: th.dataset.theme });
      applyTheme(th.dataset.theme);
      root.querySelectorAll('[data-theme]').forEach((b) => { b.classList.toggle('tab-on', b === th); b.classList.toggle('tab-off', b !== th); });
      return;
    }
    const lg = e.target.closest('[data-lang]');
    if (lg) {
      await saveSettings({ lang: lg.dataset.lang });
      root.querySelectorAll('[data-lang]').forEach((b) => { b.classList.toggle('tab-on', b === lg); b.classList.toggle('tab-off', b !== lg); });
      toast('सेव हुआ', 'success', 1200);
      return;
    }
    const act = e.target.closest('[data-act]')?.dataset.act;
    if (act === 'show') { const k = f('apiKey'); k.type = k.type === 'password' ? 'text' : 'password'; }
    if (act === 'test') {
      const box = root.querySelector('[data-test]');
      box.className = 'rounded-lg bg-slate-100 p-2 text-xs dark:bg-slate-800';
      box.textContent = 'जाँच रहे हैं… / Testing…';
      try {
        const reply = await testConnection(readAi());
        box.className = 'rounded-lg bg-emerald-100 p-2 text-xs text-emerald-800 dark:bg-emerald-900/40 dark:text-emerald-200';
        box.textContent = `✓ कनेक्शन सफल! मॉडल का जवाब: "${reply.slice(0, 80)}" — Save दबाना न भूलें।`;
      } catch (err) {
        box.className = 'rounded-lg bg-rose-100 p-2 text-xs text-rose-800 dark:bg-rose-900/40 dark:text-rose-200';
        box.textContent = `✗ ${err.message}`;
      }
    }
    if (act === 'savepdf') {
      await saveSettings({ pdfMode: f('pdfMode').value, renderScale: Number(f('renderScale').value), autoSolve: f('autoSolve').checked, watermarks: f('watermarks').value });
      toast('PDF सेटिंग सेव हुई', 'success');
    }
    if (act === 'backup') {
      const data = await exportAll();
      const where = downloadText(`abhyas_backup_${new Date().toISOString().slice(0, 10)}.json`, JSON.stringify(data));
      toast(`बैकअप सेव हुआ → ${where}`, 'success');
    }
    if (act === 'demo') { await seedDemo(true); toast('डेमो सेट जोड़ा गया', 'success'); }
    if (act === 'wipe') {
      if (await confirmDialog('सभी प्रैक्टिस सेट, प्रयास, बुकमार्क, एनालिटिक्स और API सेटिंग स्थायी रूप से मिट जाएँगे। पहले बैकअप ले लें।', { ok: 'सब मिटाएँ', danger: true })) {
        for (const st of ['sets', 'attempts', 'bookmarks', 'topicStats', 'chats', 'importDrafts', 'settings']) await db.clear(st);
        toast('सारा डेटा मिटा दिया', 'success');
        setTimeout(() => location.reload(), 600);
      }
    }
  });

  root.querySelector('[data-restore]').onchange = async (e) => {
    const file = e.target.files && e.target.files[0];
    e.target.value = '';
    if (!file) return;
    try {
      await importAll(JSON.parse(await readFile(file)));
      toast('बैकअप रिस्टोर हुआ ✓', 'success');
      setTimeout(() => location.reload(), 600);
    } catch (err) { toast(err.message, 'error', 5000); }
  };
}
