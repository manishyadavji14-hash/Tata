import { db, saveSettings } from '../db.js';
import { normalizeSet, SCHEMA_EXAMPLE } from '../schema.js';
import { saveNewSet, findSetByExamId, mergeIntoSet } from '../store.js';
import { EXTERNAL_EXTRACTION_PROMPT } from '../prompts.js';
import { isConfigured, PROVIDERS } from '../ai.js';
import { copyPrompt } from './home.js';
import { icon, toast, modal } from '../ui.js';
import { esc, readFile, downloadText, extractJson } from '../util.js';

const TABS = [['json', '📄 JSON Import'], ['prompt', '🤖 AI Prompt'], ['pdf', '📑 PDF → AI']];

export async function render({ root, query, settings, navigate, setHeader }) {
  setHeader({ title: 'इम्पोर्ट / Import', sub: 'JSON, AI प्रॉम्प्ट या PDF से प्रैक्टिस सेट' });
  let tab = TABS.some(([t]) => t === query.tab) ? query.tab : 'json';
  let job = null; // running PDF import { controller }

  root.innerHTML = `
    <div class="mb-3 grid grid-cols-3 gap-2" data-tabs></div>
    <div data-body></div>`;
  const body = root.querySelector('[data-body]');

  const drawTabs = () => {
    root.querySelector('[data-tabs]').innerHTML = TABS.map(([t, l]) => `<button class="tab !py-2 ${t === tab ? 'tab-on' : 'tab-off'}" data-tab="${t}">${l}</button>`).join('');
  };

  // ---------------- Preview + save (shared by JSON and PDF) ----------------
  async function preview(set, warnings, { draftKey = null, stats = null } = {}) {
    const existing = await findSetByExamId(set.meta.exam_id);
    const noKey = set.questions.filter((q) => !q.correct_option).length;
    const secs = set.sections.map((s) => `${esc(s)} (${set.questions.filter((q) => q.section === s).length})`).join(', ');
    const md = modal({
      title: '✅ प्रैक्टिस सेट तैयार / Ready to save', size: 'lg',
      body: `
        <div class="text-base font-bold">${esc(set.meta.title)}</div>
        <div class="mt-2 grid grid-cols-3 gap-2">
          <div class="stat"><div class="stat-v">${set.questions.length}</div><div class="stat-l">प्रश्न</div></div>
          <div class="stat"><div class="stat-v">${Object.keys(set.passages).length}</div><div class="stat-l">पैसेज</div></div>
          <div class="stat"><div class="stat-v ${noKey ? 'text-rose-600' : 'text-emerald-600'}">${set.questions.length - noKey}</div><div class="stat-l">उत्तर मिले</div></div>
        </div>
        <div class="mt-3 text-xs text-slate-500"><b>सेक्शन:</b> ${secs}</div>
        ${stats ? `<div class="mt-2 text-xs text-slate-500">पेज: ${stats.pages} • विज्ञापन पेज छोड़े: ${stats.adPages.length ? stats.adPages.join(', ') : '0'} • उत्तर कुंजी पेज: ${stats.keyPages.join(', ') || '—'} • AI कॉल: ${stats.aiCalls}${stats.errors.length ? ` • <span class="text-rose-600">त्रुटियाँ: ${stats.errors.length}</span>` : ''}</div>` : ''}
        ${warnings.length ? `<details class="mt-3 rounded-xl border border-amber-300 bg-amber-50 p-2 text-xs text-amber-900 dark:border-amber-800 dark:bg-amber-950/30 dark:text-amber-200">
          <summary class="cursor-pointer font-semibold">⚠️ ${warnings.length} चेतावनी / warnings</summary>
          <ul class="mt-1 max-h-40 list-disc overflow-y-auto pl-5">${warnings.slice(0, 200).map((w) => `<li>${esc(w)}</li>`).join('')}</ul></details>` : ''}
        ${existing ? `<div class="mt-3 rounded-xl border border-brand-300 bg-brand-50 p-3 text-sm dark:border-brand-800 dark:bg-brand-950/30">इसी <b>exam_id</b> का सेट पहले से है: "${esc(existing.meta.title)}" (${existing.questions.length} प्रश्न)। अगर यह उसी पेपर का अगला हिस्सा (Part 2/3) है तो <b>Merge</b> करें।</div>` : ''}`,
      footer: `<button class="btn-outline" data-close>Cancel</button>
        ${existing ? '<button class="btn-purple" data-merge>Merge (जोड़ें)</button>' : ''}
        <button class="btn-primary" data-new>${existing ? 'नया सेट सेव करें' : 'Save & Open'}</button>`,
    });
    const done = async (row, msg) => {
      if (draftKey) await db.delete('importDrafts', draftKey);
      md.close();
      toast(msg, 'success', 3000);
      navigate(`set/${row.id}`);
    };
    md.el.querySelector('[data-new]').onclick = async () => { const row = await saveNewSet(set); done(row, `सेव हुआ: ${row.questions.length} प्रश्न`); };
    const mb = md.el.querySelector('[data-merge]');
    if (mb) mb.onclick = async () => { const { row, added, replaced } = await mergeIntoSet(existing, set); done(row, `Merge: ${added} नए, ${replaced} अपडेट — कुल ${row.questions.length}`); };
  }

  function importJsonText(text, label) {
    let data;
    try { data = JSON.parse(text); } catch {
      try { data = extractJson(text); } catch (e) { throw new Error(`${label}: मान्य JSON नहीं है — ${e.message}`); }
    }
    return normalizeSet(data, { source: 'json' });
  }

  // ---------------- JSON tab ----------------
  function drawJson() {
    body.innerHTML = `
      <div class="card mb-3">
        <div class="mb-1 font-bold">Import JSON Practice Set</div>
        <div class="mb-3 text-sm text-slate-500">ऑफ़लाइन काम करता है। एक साथ कई .json फ़ाइलें (Part 1, 2, 3…) चुन सकते हैं — एक ही exam_id वाली फ़ाइलें अपने-आप जुड़ जाएँगी।</div>
        <label class="btn-primary w-full cursor-pointer">${icon.upload('h-5 w-5')} .json फ़ाइल चुनें<input type="file" accept=".json,application/json,text/plain" multiple class="hidden" data-files></label>
      </div>
      <div class="card mb-3">
        <div class="mb-1 font-bold">या JSON पेस्ट करें / Paste JSON</div>
        <div class="mb-2 text-xs text-slate-500">AI का पूरा जवाब यहाँ पेस्ट करें (\`\`\`json वाले हिस्से भी चलेंगे)।</div>
        <textarea class="input h-48 font-mono text-xs" placeholder='{ "exam_meta": { ... }, "questions": [ ... ] }' data-paste></textarea>
        <button class="btn-primary mt-2 w-full" data-act="paste">जाँचें और इम्पोर्ट करें / Validate &amp; Import</button>
      </div>
      <div class="card text-sm">
        <div class="mb-1 font-bold">JSON फ़ॉर्मेट (App Standard Schema)</div>
        <div class="text-xs text-slate-500">ज़रूरी: <code>questions[]</code> जिसमें <code>q_no, section, topic, question_hi/question_en, options[{id,text_hi,text_en}], correct_option</code>। गणित के लिए <code>$...$</code> (KaTeX), टेबल के लिए HTML, चित्र के लिए Base64।</div>
        <button class="btn-outline btn-sm mt-2" data-act="sample">${icon.download('h-4 w-4')} Sample JSON डाउनलोड</button>
      </div>`;
    body.querySelector('[data-files]').onchange = async (e) => {
      const files = Array.from(e.target.files || []);
      e.target.value = '';
      if (!files.length) return;
      try {
        const parts = [];
        for (const f of files) parts.push(importJsonText(await readFile(f), f.name));
        if (parts.length === 1) { preview(parts[0].set, parts[0].warnings); return; }
        // Several parts → combine into the first one before preview
        const base = parts[0].set;
        const warnings = [...parts[0].warnings];
        const map = new Map(base.questions.map((q) => [q.q_no, q]));
        for (const p of parts.slice(1)) {
          for (const q of p.set.questions) map.set(q.q_no, q);
          Object.assign(base.passages, p.set.passages);
          for (const s of p.set.sections) if (!base.sections.includes(s)) base.sections.push(s);
          warnings.push(...p.warnings);
        }
        base.questions = [...map.values()].sort((a, b) => a.q_no - b.q_no);
        base.meta.max_marks = Math.max(base.meta.max_marks, base.questions.length * base.meta.positive_marks);
        preview(base, warnings);
      } catch (err) { toast(err.message, 'error', 5000); }
    };
  }

  // ---------------- Prompt tab ----------------
  function drawPrompt() {
    body.innerHTML = `
      <div class="card mb-3">
        <div class="mb-2 font-bold">🤖 बाहरी AI से JSON बनवाएँ</div>
        <ol class="mb-3 list-decimal space-y-1 pl-5 text-sm text-slate-600 dark:text-slate-300">
          <li>नीचे <b>Copy AI Extraction Prompt</b> दबाएँ।</li>
          <li>ChatGPT / Claude / Gemini / Kiro खोलें, अपनी प्रश्न-पत्र PDF अटैच करें और प्रॉम्प्ट पेस्ट करें।</li>
          <li>AI जो JSON दे उसे कॉपी करें (लंबे पेपर में "continue" लिखकर Part 2, 3 लें)।</li>
          <li><a href="#/import?tab=json" class="font-semibold text-brand-600 underline">JSON Import</a> में पेस्ट करें या .json फ़ाइल चुनें।</li>
        </ol>
        <button class="btn-primary w-full" data-act="copy">${icon.copy('h-5 w-5')} Copy AI Extraction Prompt</button>
      </div>
      <details class="card">
        <summary class="cursor-pointer text-sm font-bold">प्रॉम्प्ट देखें / View prompt (${EXTERNAL_EXTRACTION_PROMPT.length} chars)</summary>
        <pre class="mt-2 max-h-96 overflow-auto whitespace-pre-wrap rounded-lg bg-slate-100 p-3 text-[11px] dark:bg-slate-800">${esc(EXTERNAL_EXTRACTION_PROMPT)}</pre>
      </details>`;
  }

  // ---------------- PDF tab ----------------
  function drawPdf() {
    const cfgOk = isConfigured(settings);
    body.innerHTML = `
      <div class="card mb-3">
        <div class="flex items-start gap-3">
          <span class="flex h-10 w-10 shrink-0 items-center justify-center rounded-xl ${cfgOk ? 'bg-emerald-100 text-emerald-700' : 'bg-amber-100 text-amber-700'}">${cfgOk ? icon.check() : icon.info()}</span>
          <div class="min-w-0 flex-1 text-sm">
            ${cfgOk ? `<div class="font-bold">AI जुड़ा है ✓</div><div class="truncate text-xs text-slate-500">${esc(PROVIDERS[settings.provider]?.label.split(' (')[0] || settings.provider)} • ${esc(settings.model)}</div>`
              : '<div class="font-bold">API Key सेट नहीं है</div><div class="text-xs text-slate-500">बिना AI के ऑफ़लाइन पार्सर चलेगा (सिर्फ़ टेक्स्ट वाली साधारण PDF के लिए)। बेहतर नतीजों के लिए Settings में API जोड़ें।</div>'}
          </div>
          <a href="#/settings" class="btn-outline btn-sm">Settings</a>
        </div>
      </div>
      <div class="card mb-3 space-y-3">
        <label class="btn-primary w-full cursor-pointer">${icon.pdf('h-5 w-5')} PDF फ़ाइल चुनें<input type="file" accept="application/pdf,.pdf" class="hidden" data-pdf></label>
        <div data-file class="hidden rounded-xl bg-slate-100 p-3 text-sm dark:bg-slate-800"></div>
        <label class="flex items-center gap-3 text-sm ${cfgOk ? '' : 'opacity-50'}"><input type="checkbox" class="h-5 w-5 accent-brand-600" data-useai ${cfgOk ? 'checked' : 'disabled'}> AI से पढ़ें (डबल-कॉलम, वॉटरमार्क, उत्तर कुंजी, सॉल्यूशन, टॉपिक)</label>
        <div class="grid grid-cols-2 gap-2">
          <div><label class="label">PDF मोड</label>
            <select class="input !py-2 text-sm" data-mode>
              <option value="hybrid" ${settings.pdfMode === 'hybrid' ? 'selected' : ''}>Hybrid (इमेज + टेक्स्ट)</option>
              <option value="vision" ${settings.pdfMode === 'vision' ? 'selected' : ''}>Vision (सिर्फ़ इमेज)</option>
              <option value="text" ${settings.pdfMode === 'text' ? 'selected' : ''}>Text (सस्ता, कोई इमेज नहीं)</option>
            </select></div>
          <div><label class="label">AI सॉल्यूशन</label>
            <select class="input !py-2 text-sm" data-solve>
              <option value="1" ${settings.autoSolve ? 'selected' : ''}>हाँ — व्याख्या + टॉपिक बनाएँ</option>
              <option value="0" ${!settings.autoSolve ? 'selected' : ''}>नहीं</option>
            </select></div>
        </div>
        <div class="text-[11px] text-slate-500">Hybrid/Vision मोड के लिए विज़न-सपोर्ट वाला मॉडल चाहिए (जैसे Gemini Flash, GPT-4.1/5, Claude Sonnet)। प्रोसेस शुरू होते ही AI कनेक्शन जाँचा जाता है; गड़बड़ हो तो ऑफ़लाइन पार्सर से प्रश्न निकलते हैं। स्कैन की गई या Kruti Dev फ़ॉन्ट वाली PDF के लिए Vision/Hybrid चुनें। हर पेज = लगभग 1 API कॉल।</div>
        <button class="btn-success w-full" data-act="start" disabled>▶ प्रोसेस शुरू करें / Start</button>
      </div>
      <div class="card hidden" data-progress>
        <div class="mb-2 flex items-center justify-between text-sm"><span class="font-bold" data-pmsg>…</span><span class="font-mono text-xs" data-ppct>0%</span></div>
        <div class="bar"><span class="bg-brand-600 transition-all" data-pbar style="width:0%"></span></div>
        <div class="mt-3 max-h-64 space-y-1 overflow-y-auto rounded-lg bg-slate-100 p-2 font-mono text-[11px] dark:bg-slate-800" data-log></div>
        <button class="btn-outline mt-3 w-full" data-act="cancel">रोकें / Cancel</button>
        <div class="mt-1 text-center text-[11px] text-slate-400">बीच में रुकने पर भी प्रगति सेव रहती है — वही PDF दोबारा चुनें, वहीं से शुरू होगा।</div>
      </div>`;
    let file = null;
    const startBtn = body.querySelector('[data-act=start]');
    body.querySelector('[data-pdf]').onchange = async (e) => {
      file = e.target.files && e.target.files[0];
      e.target.value = '';
      if (!file) return;
      const box = body.querySelector('[data-file]');
      box.classList.remove('hidden');
      const drafts = await db.all('importDrafts');
      const d = drafts.find((x) => x.key.startsWith(`${file.name}|${file.size}|`));
      box.innerHTML = `<div class="font-semibold">📑 ${esc(file.name)}</div><div class="text-xs text-slate-500">${(file.size / 1048576).toFixed(2)} MB${d ? ` • <span class="text-emerald-600">पिछला अधूरा इम्पोर्ट मिला (${d.done.length} पेज पूरे) — वहीं से जारी होगा</span>` : ''}</div>`;
      startBtn.disabled = false;
    };
    body.querySelector('[data-mode]').onchange = async (e) => { settings.pdfMode = e.target.value; await saveSettings({ pdfMode: e.target.value }); };
    body.querySelector('[data-solve]').onchange = async (e) => { settings.autoSolve = e.target.value === '1'; await saveSettings({ autoSolve: settings.autoSolve }); };

    startBtn.onclick = async () => {
      if (!file || job) return;
      const useAI = body.querySelector('[data-useai]').checked && cfgOk;
      const prog = body.querySelector('[data-progress]');
      const log = body.querySelector('[data-log]');
      prog.classList.remove('hidden');
      log.innerHTML = '';
      startBtn.disabled = true;
      const color = { info: '', warn: 'text-amber-600', error: 'text-rose-600', success: 'text-emerald-600' };
      const onLog = (msg, level = 'info') => {
        log.insertAdjacentHTML('beforeend', `<div class="${color[level] || ''}">• ${esc(msg)}</div>`);
        log.scrollTop = log.scrollHeight;
      };
      const onProgress = ({ pct, msg }) => {
        body.querySelector('[data-pmsg]').textContent = msg;
        body.querySelector('[data-ppct]').textContent = `${pct}%`;
        body.querySelector('[data-pbar]').style.width = `${pct}%`;
      };
      const controller = new AbortController();
      job = { controller };
      if (window.AndroidBridge?.keepScreenOn) window.AndroidBridge.keepScreenOn(true);
      try {
        const { importPdf } = await import('../pdf-import.js');
        const { set, warnings, stats } = await importPdf(file, { settings, useAI, onProgress, onLog, signal: controller.signal });
        onProgress({ pct: 100, msg: 'पूरा हुआ ✓' });
        preview(set, warnings, { draftKey: stats.draftKey, stats });
      } catch (e) {
        if (e.name === 'AbortError') onLog('रोका गया — प्रगति सेव है', 'warn');
        else { onLog(e.message, 'error'); toast(e.message, 'error', 6000); }
      } finally {
        job = null;
        startBtn.disabled = false;
        if (window.AndroidBridge?.keepScreenOn) window.AndroidBridge.keepScreenOn(false);
      }
    };
  }

  const draw = () => { drawTabs(); ({ json: drawJson, prompt: drawPrompt, pdf: drawPdf })[tab](); };
  draw();

  root.addEventListener('click', async (e) => {
    const t = e.target.closest('[data-tab]');
    if (t && !job) { tab = t.dataset.tab; history.replaceState(null, '', `#/import?tab=${tab}`); draw(); return; }
    const act = e.target.closest('[data-act]')?.dataset.act;
    if (act === 'copy') copyPrompt();
    if (act === 'sample') { const w = downloadText('abhyas_sample_practice_set.json', JSON.stringify(SCHEMA_EXAMPLE, null, 2)); toast(`सेव हुआ → ${w}`, 'success'); }
    if (act === 'paste') {
      const text = body.querySelector('[data-paste]').value.trim();
      if (!text) { toast('पहले JSON पेस्ट करें', 'warn'); return; }
      try { const { set, warnings } = importJsonText(text, 'Pasted text'); preview(set, warnings); } catch (err) { toast(err.message, 'error', 5000); }
    }
    if (act === 'cancel' && job) job.controller.abort();
  });

  return () => { if (job) job.controller.abort(); };
}
