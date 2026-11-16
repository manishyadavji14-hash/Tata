import { db, saveSettings, exportAll, importAll } from '../db.js';
import { PROVIDERS, testConnection, listModels, detectProvider, baseUrlProblem, modelProblem } from '../ai.js';
import { applyTheme, seedDemo, APP_VERSION, refresh } from '../app.js';
import { pinCardHtml, wirePinCard, requirePin } from '../pin.js';
import { TELEGRAM_URL, openTelegram } from '../share.js';
import { toast, confirmDialog, icon } from '../ui.js';
import { esc, downloadText, readFile } from '../util.js';

export async function render({ root, settings: s, setHeader }) {
  setHeader({ title: 'सेटिंग्स / Settings' });
  const est = navigator.storage?.estimate ? await navigator.storage.estimate().catch(() => null) : null;
  const persisted = navigator.storage?.persisted ? await navigator.storage.persisted().catch(() => false) : false;
  const pinCard = await pinCardHtml();

  root.innerHTML = `
    <form class="card mb-3 space-y-3" data-ai autocomplete="off">
      <div class="flex items-center gap-2 font-bold">${icon.spark('h-5 w-5 text-violet-600')} AI / API सेटिंग</div>
      <div class="rounded-xl bg-brand-50 p-3 text-xs leading-relaxed text-brand-900 dark:bg-brand-950/40 dark:text-brand-200">
        <b>सबसे आसान:</b> सिर्फ़ API Key पेस्ट करें → <b>Models लाएँ</b> → सूची से मॉडल चुनें → <b>Save</b>। Provider और Base URL Key देखकर अपने-आप भर जाते हैं।<br>
        मुफ़्त Key: <b>aistudio.google.com</b> → "Get API key" (Google Gemini, <code>AIza…</code> से शुरू)।<br>
        ⚠️ Kiro की API Key इस ऐप में नहीं चलती (वह सिर्फ़ Kiro CLI के लिए है)।
      </div>
      <div><label class="label">API Key</label>
        <div class="flex gap-2"><input class="input font-mono text-sm" name="apiKey" type="password" value="${esc(s.apiKey)}" spellcheck="false" autocapitalize="off" placeholder="AIza… / sk-… / sk-ant-… / sk-or-…">
        <button type="button" class="btn-outline !px-3" data-act="show">👁</button></div>
        <div class="mt-1 text-[11px] text-slate-500" data-keyinfo>Key सिर्फ़ इसी डिवाइस के IndexedDB में रहती है, बैकअप फ़ाइल में शामिल नहीं होती।</div></div>
      <div><label class="label">Provider / API प्रकार</label>
        <select class="input" name="provider">${Object.entries(PROVIDERS).map(([k, p]) => `<option value="${k}" ${s.provider === k ? 'selected' : ''}>${esc(p.label)}</option>`).join('')}</select></div>
      <div><label class="label">API Base URL <span class="normal-case text-slate-400">(खाली छोड़ें = अपने-आप)</span></label>
        <input class="input font-mono text-sm" name="apiBase" value="${esc(s.apiBase)}" inputmode="url" spellcheck="false" autocapitalize="off" placeholder="${esc(PROVIDERS[s.provider]?.base || '')}">
        <div class="mt-1 text-[11px] text-rose-600" data-basewarn></div></div>
      <div><label class="label">Model</label>
        <div class="flex gap-2">
          <input class="input font-mono text-sm" name="model" value="${esc(s.model)}" spellcheck="false" autocapitalize="off" list="model-list" placeholder="Models लाएँ दबाएँ">
          <button type="button" class="btn-outline shrink-0 !px-3 text-xs" data-act="models">📋 Models लाएँ</button>
        </div>
        <datalist id="model-list"></datalist>
        <select class="input mt-2 hidden text-sm" data-modelpick></select>
        <div class="mt-1 text-[11px] text-rose-600" data-modelwarn></div></div>
      <div class="grid grid-cols-3 gap-2">
        <div><label class="label">Temperature</label><input class="input" name="temperature" type="number" min="0" max="1.5" step="0.1" value="${s.temperature}"></div>
        <div class="col-span-2 self-end text-[11px] text-slate-500">PDF के लिए विज़न मॉडल चुनें (नाम में flash, pro, gpt-4o/4.1/5, claude-sonnet/opus आदि)।</div>
      </div>
      <div class="grid grid-cols-2 gap-2">
        <button type="button" class="btn-outline" data-act="test">🔌 Test Connection</button>
        <button class="btn-primary" type="submit">Save</button>
      </div>
      <div data-test class="hidden whitespace-pre-line rounded-lg p-2 text-xs"></div>
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

    ${pinCard}

    <div class="card mb-3 space-y-2">
      <div class="font-bold">💾 डेटा / Data</div>
      <div class="text-xs text-slate-500">स्टोरेज: ${est ? `${(est.usage / 1048576).toFixed(1)} MB उपयोग${est.quota ? ` / ${(est.quota / 1073741824).toFixed(1)} GB` : ''}` : '—'} • ${persisted ? '🔒 Persistent storage चालू' : 'ब्राउज़र जगह कम होने पर डेटा हटा सकता है — बैकअप लेते रहें'}</div>
      <button class="btn-outline w-full" data-act="backup">${icon.download('h-4 w-4')} पूरा बैकअप (JSON) डाउनलोड</button>
      <label class="btn-outline w-full cursor-pointer">${icon.upload('h-4 w-4')} बैकअप से रिस्टोर<input type="file" accept=".json,application/json" class="hidden" data-restore></label>
      <button class="btn-outline w-full" data-act="demo">डेमो प्रैक्टिस सेट दोबारा जोड़ें</button>
      <button class="btn-outline w-full text-rose-600" data-act="wipe">${icon.trash('h-4 w-4')} सारा डेटा मिटाएँ</button>
    </div>
    <div class="card mb-3 text-center" data-about>
      <div class="font-bold">ℹ️ ऐप के बारे में / About</div>
      <div class="mt-2 text-lg font-bold text-brand-600">Abhyas CBT</div>
      <div class="text-xs text-slate-500">संस्करण / Version ${APP_VERSION}</div>
      <div class="mt-3 text-base font-semibold">Created by Maneesh</div>
      <div class="text-sm text-slate-600 dark:text-slate-300">निर्माता: मनीष</div>
      <div class="mt-2 text-xs text-slate-500">SUPER TET / UP सहायक अध्यापक की ऑफ़लाइन CBT तैयारी — PDF/JSON से मॉक टेस्ट, AI प्रश्न, विषयवार अभ्यास और टेस्ट शेयरिंग।</div>
      <button class="btn-outline btn-sm mt-3" data-tg>✈️ Telegram ग्रुप</button>
      <div class="mt-1 break-all text-[10px] text-slate-400">${TELEGRAM_URL}</div>
    </div>
    <div class="pb-4 text-center text-[11px] text-slate-400">Abhyas CBT v${APP_VERSION} • Created by Maneesh • Offline-first</div>`;
  wirePinCard(root, refresh);
  root.querySelector('[data-tg]').onclick = openTelegram;

  const form = root.querySelector('[data-ai]');
  const f = (n) => root.querySelector(`[name=${n}]`);
  const readAi = () => ({
    provider: f('provider').value,
    apiBase: f('apiBase').value.trim().replace(/\/+$/, ''),
    apiKey: f('apiKey').value.trim(),
    model: f('model').value.trim(),
    temperature: Math.min(1.5, Math.max(0, Number(f('temperature').value) || 0)),
  });
  const knownBases = () => Object.values(PROVIDERS).map((x) => x.base).concat(['https://openrouter.ai/api/v1', 'https://api.groq.com/openai/v1', 'https://api.x.ai/v1']);

  const validate = () => {
    root.querySelector('[data-basewarn]').textContent = baseUrlProblem(f('apiBase').value);
    const m = f('model').value.trim();
    root.querySelector('[data-modelwarn]').textContent = m ? modelProblem(m) : '';
  };

  const onKey = () => {
    const det = detectProvider(f('apiKey').value);
    const info = root.querySelector('[data-keyinfo]');
    if (!det) { info.textContent = f('apiKey').value.trim() ? 'Key का प्रकार नहीं पहचाना — Provider और Base URL खुद चुनें।' : 'Key सिर्फ़ इसी डिवाइस के IndexedDB में रहती है, बैकअप फ़ाइल में शामिल नहीं होती।'; return; }
    info.innerHTML = `✓ पहचानी गई: <b>${esc(det.name)}</b> — Provider और Base URL भर दिए गए। अब <b>Models लाएँ</b> दबाएँ।`;
    const changed = f('provider').value !== det.provider;
    f('provider').value = det.provider;
    const base = f('apiBase').value.trim();
    if (!base || knownBases().includes(base) || baseUrlProblem(base)) f('apiBase').value = det.apiBase;
    if (changed) f('model').value = PROVIDERS[det.provider].model || '';
    validate();
  };
  f('apiKey').addEventListener('input', onKey);
  f('apiBase').addEventListener('input', validate);
  f('model').addEventListener('input', validate);
  validate();

  f('provider').onchange = () => {
    const p = PROVIDERS[f('provider').value];
    const base = f('apiBase').value.trim();
    if (!base || knownBases().includes(base) || baseUrlProblem(base)) f('apiBase').value = p.base;
    f('apiBase').placeholder = p.base;
    f('model').value = p.model || '';
    validate();
  };

  const pick = root.querySelector('[data-modelpick]');
  pick.onchange = () => { if (pick.value) { f('model').value = pick.value; validate(); } };

  form.onsubmit = async (e) => {
    e.preventDefault();
    const v = readAi();
    const problem = baseUrlProblem(v.apiBase) || (v.apiKey ? modelProblem(v.model) : '');
    if (problem) { toast(problem, 'error', 6000); return; }
    await saveSettings(v);
    Object.assign(s, v);
    toast('API सेटिंग सेव हुई ✓ — एक बार Test Connection ज़रूर दबाएँ', 'success');
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
    if (act === 'models') {
      const btn = e.target.closest('[data-act]');
      btn.disabled = true;
      btn.textContent = '…';
      try {
        const { ids, cfg } = await listModels(readAi());
        if (!f('apiBase').value.trim()) f('apiBase').value = cfg.apiBase;
        if (!ids.length) throw new Error('इस Key से कोई chat मॉडल नहीं मिला');
        root.querySelector('#model-list').innerHTML = ids.map((id) => `<option value="${esc(id)}"></option>`).join('');
        const cur = f('model').value.trim();
        pick.innerHTML = `<option value="">— ${ids.length} मॉडल मिले, एक चुनें —</option>${ids.map((id) => `<option value="${esc(id)}" ${id === cur ? 'selected' : ''}>${esc(id)}</option>`).join('')}`;
        pick.classList.remove('hidden');
        if (!cur || !ids.includes(cur)) {
          const pref = ids.find((id) => /flash$|flash-\d|^gpt-4\.1-mini|^gpt-4o-mini|sonnet|gpt-5-mini/i.test(id) && !/lite|preview|exp/i.test(id)) || ids[0];
          f('model').value = pref;
          pick.value = pref;
        }
        validate();
        toast(`✓ Key सही है — ${ids.length} मॉडल मिले। मॉडल चुनकर Save करें।`, 'success', 4000);
      } catch (err) {
        const box = root.querySelector('[data-test]');
        box.className = 'whitespace-pre-line rounded-lg bg-rose-100 p-2 text-xs text-rose-800 dark:bg-rose-900/40 dark:text-rose-200';
        box.textContent = `✗ ${err.message}`;
      } finally {
        btn.disabled = false;
        btn.textContent = '📋 Models लाएँ';
      }
    }
    if (act === 'test') {
      const box = root.querySelector('[data-test]');
      box.className = 'whitespace-pre-line rounded-lg bg-slate-100 p-2 text-xs dark:bg-slate-800';
      box.textContent = 'जाँच रहे हैं… / Testing…';
      try {
        const reply = await testConnection(readAi());
        box.className = 'whitespace-pre-line rounded-lg bg-emerald-100 p-2 text-xs text-emerald-800 dark:bg-emerald-900/40 dark:text-emerald-200';
        box.textContent = `✓ कनेक्शन सफल! मॉडल का जवाब: "${reply.slice(0, 80)}" — Save दबाना न भूलें।`;
      } catch (err) {
        box.className = 'whitespace-pre-line rounded-lg bg-rose-100 p-2 text-xs text-rose-800 dark:bg-rose-900/40 dark:text-rose-200';
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
      if (!(await requirePin('सारा डेटा मिटाने के लिए'))) return;
      if (await confirmDialog('सभी प्रैक्टिस सेट, प्रयास, बुकमार्क, एनालिटिक्स और API सेटिंग स्थायी रूप से मिट जाएँगे। पहले बैकअप ले लें।', { ok: 'सब मिटाएँ', danger: true })) {
        for (const st of ['sets', 'attempts', 'bookmarks', 'topicStats', 'chats', 'importDrafts', 'settings', 'mistakes', 'jobs', 'jobFiles']) await db.clear(st);
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
