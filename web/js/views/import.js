import { db, saveSettings } from '../db.js';
import { normalizeSet, SCHEMA_EXAMPLE } from '../schema.js';
import { saveNewSet, findSetByExamId, mergeIntoSet } from '../store.js';
import { EXTERNAL_EXTRACTION_PROMPT } from '../prompts.js';
import { isConfigured, PROVIDERS } from '../ai.js';
import { copyPrompt } from './home.js';
import { icon, toast, modal } from '../ui.js';
import { esc, readFile, downloadText, extractJson, copyText } from '../util.js';
import { QTYPES } from '../prompts.js';
import { expandImport, telegramCard, openTelegram } from '../share.js';

const TABS = [['json', '📄 JSON'], ['ai', '🤖 AI से बनाएँ'], ['prompt', '📋 AI Prompt']];

export async function render({ root, query, settings, navigate, setHeader }) {
  setHeader({ title: 'इम्पोर्ट / Import', sub: 'JSON, AI प्रॉम्प्ट या PDF से प्रैक्टिस सेट' });
  let tab = query.tab === 'pdf' ? 'ai' : TABS.some(([t]) => t === query.tab) ? query.tab : 'json';
  let aiMode = query.tab === 'pdf' || query.mode === 'fixed' ? 'fixed' : (query.mode === 'custom' || query.tab === 'ai' ? (query.mode || 'custom') : 'custom');
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
    return expandImport(data);
  }

  // Several texts (files / shared from other apps) → previews. Bundles may hold many tests.
  async function handleTexts(items) {
    const parts = [];
    let conceptSets = 0, conceptCount = 0;
    for (const it of items) {
      let data;
      try { data = JSON.parse(it.text); } catch {
        try { data = extractJson(it.text); } catch (e) { throw new Error(`${it.name}: मान्य JSON नहीं है — ${e.message}`); }
      }
      // Concept / revision-note files go to the कॉन्सेप्ट tab.
      const C = await import('../concepts.js');
      if (C.isConceptData(data)) {
        for (const { set, count } of C.expandConcepts(data)) { await C.saveConceptSet(set); conceptSets++; conceptCount += count; }
        continue;
      }
      if (/"svg"\s*:\s*"/.test(it.text) || /solution_svg/.test(it.text)) {
        const { resolveRawFigures } = await import('../generate.js');
        await resolveRawFigures(data);
      }
      parts.push(...expandImport(data));
    }
    if (conceptSets) {
      toast(`💡 ${conceptSets} कॉन्सेप्ट सेट जुड़े (${conceptCount} कॉन्सेप्ट) — कॉन्सेप्ट टैब में पढ़ें`, 'success', 4000);
      if (!parts.length) { navigate('concepts'); return; }
    }
    if (!parts.length) return;
    // Only parts with the same exam_id are Parts of one paper → combine those; different papers stay separate.
    const groups = new Map();
    for (const p of parts) {
      const id = p.set.meta.exam_id;
      if (!groups.has(id)) groups.set(id, []);
      groups.get(id).push(p);
    }
    const combined = [...groups.values()].map(combineParts);
    if (combined.length === 1) { preview(combined[0].set, combined[0].warnings); return; }
    batchPreview(combined, items.length);
  }

  // ---------------- JSON tab ----------------
  function drawJson() {
    body.innerHTML = `
      <div class="card mb-3">
        <div class="mb-1 font-bold">Import JSON Practice Set</div>
        <div class="mb-3 text-sm text-slate-500">ऑफ़लाइन काम करता है। एक साथ कई .json फ़ाइलें चुन सकते हैं — साथियों की शेयर की हुई फ़ाइलें (एक में कई टेस्ट) भी। अलग-अलग पेपर अलग सेट बनेंगे; एक ही exam_id वाले हिस्से (Part 1, 2, 3…) अपने-आप जुड़ जाएँगे।</div>
        <label class="btn-primary w-full cursor-pointer">${icon.upload('h-5 w-5')} .json फ़ाइल चुनें<input type="file" accept=".json,application/json,text/plain" multiple class="hidden" data-files></label>
      </div>
      ${telegramCard(true)}
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
        const items = [];
        for (const f of files) items.push({ name: f.name, text: await readFile(f) });
        await handleTexts(items);
      } catch (err) { toast(err.message, 'error', 5000); }
    };
  }

  function combineParts(list) {
    if (list.length === 1) return list[0];
    const base = list[0].set;
    const warnings = [...list[0].warnings];
    const map = new Map(base.questions.map((q) => [q.q_no, q]));
    for (const p of list.slice(1)) {
      for (const q of p.set.questions) map.set(q.q_no, q);
      Object.assign(base.passages, p.set.passages);
      for (const s of p.set.sections) if (!base.sections.includes(s)) base.sections.push(s);
      warnings.push(...p.warnings);
    }
    base.questions = [...map.values()].sort((a, b) => a.q_no - b.q_no);
    base.meta.max_marks = Math.max(base.meta.max_marks, base.questions.length * base.meta.positive_marks);
    return { set: base, warnings };
  }

  // Several different papers selected together → one list, save all at once.
  async function batchPreview(items, fileCount) {
    const rows = await Promise.all(items.map(async (it) => ({ ...it, existing: await findSetByExamId(it.set.meta.exam_id) })));
    const md = modal({
      title: `✅ ${rows.length} अलग प्रैक्टिस सेट / ${rows.length} sets`, size: 'lg',
      body: `
        <div class="mb-2 text-xs text-slate-500">${fileCount} फ़ाइलें चुनी गईं → ${rows.length} अलग पेपर मिले। हर पेपर अलग सेट बनेगा।</div>
        <div class="space-y-2">${rows.map((r, i) => {
          const noKey = r.set.questions.filter((q) => !q.correct_option).length;
          return `<div class="rounded-xl border border-slate-200 p-3 dark:border-slate-700">
            <div class="text-sm font-bold">${esc(r.set.meta.title)}</div>
            <div class="mt-0.5 text-xs text-slate-500">${r.set.questions.length} प्रश्न • उत्तर मिले ${r.set.questions.length - noKey}${r.warnings.length ? ` • ⚠️ ${r.warnings.length}` : ''}</div>
            ${r.existing ? `<div class="mt-1 text-xs text-brand-700 dark:text-brand-300">पहले से है (${r.existing.questions.length} प्रश्न)</div>` : ''}
            <select class="input mt-2 !py-1.5 text-sm" data-mode="${i}">
              ${r.existing ? '<option value="merge">पुराने सेट को अपडेट करें (Merge)</option><option value="new">नया अलग सेट सेव करें</option><option value="skip">छोड़ दें</option>'
                : '<option value="new">सेव करें</option><option value="skip">छोड़ दें</option>'}
            </select>
          </div>`;
        }).join('')}</div>`,
      footer: '<button class="btn-outline" data-close>Cancel</button><button class="btn-primary" data-all>सभी सेव करें / Save all</button>',
    });
    md.el.querySelector('[data-all]').onclick = async (e) => {
      e.target.disabled = true;
      let saved = 0;
      for (const [i, r] of rows.entries()) {
        const mode = md.el.querySelector(`[data-mode="${i}"]`).value;
        if (mode === 'skip') continue;
        if (mode === 'merge' && r.existing) await mergeIntoSet(r.existing, r.set);
        else await saveNewSet(r.set);
        saved++;
      }
      md.close();
      toast(`${saved} सेट सेव हुए`, 'success', 3000);
      navigate('home');
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
  function drawPdf(el = body) {
    const cfgOk = isConfigured(settings);
    el.innerHTML = `
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
      <div class="card text-[11px] text-slate-500">▶ दबाते ही काम <b>बैकग्राउंड</b> में चलेगा — आप ऐप में कहीं भी जा सकते हैं या दूसरा ऐप खोल सकते हैं। स्थिति <a href="#/jobs" class="font-semibold text-brand-600 underline">AI काम</a> में दिखेगी, वहीं से रोकें/जारी रखें/सेव करें।
      </div>`;
    let file = null;
    const startBtn = el.querySelector('[data-act=start]');
    el.querySelector('[data-pdf]').onchange = async (e) => {
      file = e.target.files && e.target.files[0];
      e.target.value = '';
      if (!file) return;
      const box = el.querySelector('[data-file]');
      box.classList.remove('hidden');
      const drafts = await db.all('importDrafts');
      const d = drafts.find((x) => x.key.startsWith(`${file.name}|${file.size}|`));
      box.innerHTML = `<div class="font-semibold">📑 ${esc(file.name)}</div><div class="text-xs text-slate-500">${(file.size / 1048576).toFixed(2)} MB${d ? ` • <span class="text-emerald-600">पिछला अधूरा इम्पोर्ट मिला (${d.done.length} पेज पूरे) — वहीं से जारी होगा</span>` : ''}</div>`;
      startBtn.disabled = false;
    };
    el.querySelector('[data-mode]').onchange = async (e) => { settings.pdfMode = e.target.value; await saveSettings({ pdfMode: e.target.value }); };
    el.querySelector('[data-solve]').onchange = async (e) => { settings.autoSolve = e.target.value === '1'; await saveSettings({ autoSolve: settings.autoSolve }); };

    startBtn.onclick = async () => {
      if (!file) return;
      startBtn.disabled = true;
      const useAI = el.querySelector('[data-useai]').checked && cfgOk;
      const pdf = { pdfMode: settings.pdfMode, autoSolve: settings.autoSolve };
      const draftKey = `${file.name}|${file.size}|${file.lastModified}|${useAI ? settings.pdfMode : 'local'}`;
      const { createJob } = await import('../jobs.js');
      const j = await createJob({ kind: 'pdf', title: file.name.replace(/\.pdf$/i, ''), sources: [{ file }], params: { useAI, pdf, draftKey } });
      toast('काम बैकग्राउंड में शुरू हुआ ✓', 'success');
      navigate(`job/${j.id}`);
    };
  }

  function drawAi() {
    body.innerHTML = `
      <div class="mb-3 grid grid-cols-2 gap-2">
        <button class="card !p-3 text-left ${aiMode === 'fixed' ? 'ring-2 ring-brand-500' : ''}" data-aimode="fixed">
          <div class="text-sm font-bold">1️⃣ Fixed — प्रैक्टिस सेट डिजिटाइज़</div>
          <div class="mt-0.5 text-[11px] text-slate-500">छपा हुआ पेपर/प्रैक्टिस सेट PDF जैसा है वैसा ऐप के टेस्ट में</div></button>
        <button class="card !p-3 text-left ${aiMode === 'custom' ? 'ring-2 ring-fuchsia-500' : ''}" data-aimode="custom">
          <div class="text-sm font-bold">2️⃣ Custom — नए प्रश्न बनवाएँ</div>
          <div class="mt-0.5 text-[11px] text-slate-500">बुक/नोट्स/डॉक्यूमेंट से मनचाहे प्रकार, संख्या और निर्देश</div></button>
      </div>
      <div data-aibody></div>`;
    const el = body.querySelector('[data-aibody]');
    if (aiMode === 'fixed') drawPdf(el); else drawCustom(el);
  }

  // ---------------- Custom generation ----------------
  function drawCustom(el) {
    const cfgOk = isConfigured(settings);
    let saved = {};
    try { saved = JSON.parse(localStorage.getItem('abhyas-gen') || '{}'); } catch { /* ignore */ }
    const g = { count: 20, perCall: 8, lang: 'hi', difficulty: 'mix', fidelity: 'source', types: ['mcq'], withImages: true, section: '', custom: '', title: '', pos: 1, neg: 0, minutes: '', ...saved };
    const files = [];
    el.innerHTML = `
      <div class="card mb-3 text-sm">
        <div class="font-bold">✨ अपने स्रोत से नए प्रश्न</div>
        <div class="mt-1 text-xs text-slate-500">कोई भी बुक, नोट्स, NCERT/सरकारी PDF, फ़ोटो या टेक्स्ट दें। प्रश्न का प्रकार चुनें या नीचे अपने शब्दों में कोई भी नया तरीका लिखें — AI उसी तरह टेक्स्ट, तालिका, चित्र/आरेख (SVG) या स्रोत की फ़ोटो से कटे चित्र वाले प्रश्न बनाएगा।</div>
        ${cfgOk ? `<div class="mt-2 text-[11px] text-emerald-700">AI जुड़ा है ✓ ${esc(settings.model)}</div>` : '<div class="mt-2 text-[11px] text-amber-700">API Key नहीं है — Settings में जोड़ें, या नीचे "बाहरी AI के लिए प्रॉम्प्ट" कॉपी करके ChatGPT/Gemini में डॉक्यूमेंट के साथ दें।</div>'}
      </div>
      <div class="card mb-3 space-y-2">
        <div class="font-bold">1️⃣ स्रोत / Source</div>
        <label class="btn-primary w-full cursor-pointer">${icon.upload('h-5 w-5')} फ़ाइल चुनें (PDF, फ़ोटो, .txt — कई एक साथ)<input type="file" multiple accept="application/pdf,.pdf,image/*,.txt,.md,.csv,.html,.htm,text/plain" class="hidden" data-gfiles></label>
        <div data-glist class="space-y-1"></div>
        <textarea class="input h-28 text-sm" placeholder="या यहाँ अध्याय/नोट्स का टेक्स्ट पेस्ट करें…" data-g="pasted"></textarea>
        <label class="flex items-center gap-3 text-sm"><input type="checkbox" class="h-5 w-5 accent-brand-600" data-g="withImages" ${g.withImages ? 'checked' : ''}> पेज की फ़ोटो भी भेजें (चित्र, तालिका, स्कैन/हस्तलिखित के लिए — विज़न मॉडल चाहिए)</label>
      </div>
      <div class="card mb-3">
        <div class="mb-2 font-bold">2️⃣ प्रश्न का प्रकार (एक या अधिक)</div>
        <div class="flex flex-wrap gap-1.5" data-types>${[...QTYPES, ['five', '5 विकल्प (A–E)']].map(([k, l]) => `<button class="chip !py-1.5 ${g.types.includes(k) ? 'bg-fuchsia-600 text-white' : 'bg-slate-100 text-slate-700 dark:bg-slate-800 dark:text-slate-200'}" data-type="${k}">${esc(l)}</button>`).join('')}</div>
        <label class="label mt-3">✍️ अपना तरीका / Custom निर्देश (जो विकल्प ऊपर नहीं है)</label>
        <textarea class="input h-28 text-sm" data-g="custom" placeholder="जैसे: हर प्रश्न में एक छोटी तालिका दें और उसी से पूछें • मानचित्र का चित्र बनाकर राज्य पहचानने वाले प्रश्न • 'कथन 1 और 2' वाले UPSC-शैली प्रश्न • हर हल में चित्र से समझाएँ • केवल पाठ 5 के उदाहरणों जैसे संख्यात्मक प्रश्न">${esc(g.custom)}</textarea>
      </div>
      <div class="card mb-3 grid grid-cols-2 gap-2">
        <div class="col-span-2 font-bold">3️⃣ टेस्ट सेटिंग</div>
        <div><label class="label">कुल प्रश्न</label><input type="number" min="1" max="300" class="input" data-g="count" value="${g.count}"></div>
        <div><label class="label">प्रति AI कॉल</label><input type="number" min="3" max="20" class="input" data-g="perCall" value="${g.perCall}"></div>
        <div><label class="label">भाषा</label><select class="input" data-g="lang">
          <option value="hi" ${g.lang === 'hi' ? 'selected' : ''}>हिंदी</option><option value="en" ${g.lang === 'en' ? 'selected' : ''}>English</option><option value="both" ${g.lang === 'both' ? 'selected' : ''}>द्विभाषी (हिंदी + English)</option></select></div>
        <div><label class="label">कठिनाई</label><select class="input" data-g="difficulty">
          ${[['mix', 'मिश्रित'], ['Easy', 'आसान'], ['Medium', 'मध्यम'], ['Hard', 'कठिन']].map(([v, l]) => `<option value="${v}" ${g.difficulty === v ? 'selected' : ''}>${l}</option>`).join('')}</select></div>
        <div class="col-span-2"><label class="label">स्रोत से बाहर</label><select class="input" data-g="fidelity">
          <option value="source" ${g.fidelity === 'source' ? 'selected' : ''}>सिर्फ़ दिए गए स्रोत के तथ्य</option><option value="related" ${g.fidelity === 'related' ? 'selected' : ''}>स्रोत + उससे जुड़े मानक सिलेबस तथ्य</option></select></div>
        <div><label class="label">विषय/सेक्शन (खाली = स्वतः)</label><input class="input" data-g="section" value="${esc(g.section)}" placeholder="जैसे: बाल विकास"></div>
        <div><label class="label">टेस्ट का नाम</label><input class="input" data-g="title" value="${esc(g.title)}" placeholder="स्वतः"></div>
        <div><label class="label">सही पर अंक</label><input type="number" step="0.25" class="input" data-g="pos" value="${g.pos}"></div>
        <div><label class="label">गलत पर कटौती</label><input type="number" step="0.25" class="input" data-g="neg" value="${g.neg}"></div>
      </div>
      <div class="card mb-3 space-y-2">
        <button class="btn-success w-full" data-gen ${cfgOk ? '' : 'disabled'}>✨ प्रश्न बनाएँ / Generate</button>
        <button class="btn-outline w-full" data-genprompt>${icon.copy('h-4 w-4')} बाहरी AI के लिए प्रॉम्प्ट कॉपी करें</button>
        <div class="text-[11px] text-slate-500">हर बैच = 1 AI कॉल। बड़ी बुक के लिए PDF के पेज चुनें (जैसे 10-25)। बने प्रश्नों को सेव करने से पहले देख सकते हैं; बाद में हर प्रश्न का हल/चित्र बदल सकते हैं।</div>
      </div>
`;
    const remember = () => { const { pasted, ...rest } = g; try { localStorage.setItem('abhyas-gen', JSON.stringify(rest)); } catch { /* ignore */ } };
    const drawList = () => {
      el.querySelector('[data-glist]').innerHTML = files.map((f, i) => `
        <div class="flex items-center gap-2 rounded-lg bg-slate-100 p-2 text-xs dark:bg-slate-800">
          <span class="min-w-0 flex-1 truncate font-semibold">${/pdf/i.test(f.file.type) || /\.pdf$/i.test(f.file.name) ? '📑' : /^image/.test(f.file.type) ? '🖼️' : '📄'} ${esc(f.file.name)}</span>
          ${/pdf/i.test(f.file.type) || /\.pdf$/i.test(f.file.name) ? `<input class="input !w-28 !py-1 text-xs" placeholder="पेज: 1-20" value="${esc(f.pages)}" data-fpages="${i}">` : ''}
          <button class="text-rose-600" data-frm="${i}">✕</button>
        </div>`).join('');
    };
    el.querySelector('[data-gfiles]').onchange = (e) => { for (const f of e.target.files || []) files.push({ file: f, pages: '' }); e.target.value = ''; drawList(); };
    el.addEventListener('input', (e) => {
      const k = e.target.dataset.g;
      if (k) g[k] = e.target.type === 'checkbox' ? e.target.checked : e.target.value;
      if (e.target.dataset.fpages) files[Number(e.target.dataset.fpages)].pages = e.target.value;
      if (k) remember();
    });
    el.addEventListener('change', (e) => { const k = e.target.dataset.g; if (k) { g[k] = e.target.type === 'checkbox' ? e.target.checked : e.target.value; remember(); } });
    el.addEventListener('click', async (e) => {
      const ty = e.target.closest('[data-type]');
      if (ty) {
        const k = ty.dataset.type;
        g.types = g.types.includes(k) ? g.types.filter((x) => x !== k) : [...g.types, k];
        ty.className = `chip !py-1.5 ${g.types.includes(k) ? 'bg-fuchsia-600 text-white' : 'bg-slate-100 text-slate-700 dark:bg-slate-800 dark:text-slate-200'}`;
        remember();
        return;
      }
      const rm = e.target.closest('[data-frm]');
      if (rm) { files.splice(Number(rm.dataset.frm), 1); drawList(); return; }
      const opts = () => ({ ...g, count: Math.max(1, Math.min(300, parseInt(g.count, 10) || 20)), perCall: parseInt(g.perCall, 10) || 8 });
      if (e.target.closest('[data-genprompt]')) {
        const { externalGenPrompt } = await import('../generate.js');
        const ok = await copyText(externalGenPrompt(opts()));
        toast(ok ? 'प्रॉम्प्ट कॉपी हुआ ✓ ChatGPT/Gemini में अपनी बुक/PDF के साथ पेस्ट करें, फिर मिला JSON यहाँ JSON टैब में डालें' : 'कॉपी नहीं हो सका', ok ? 'success' : 'error', 5000);
        return;
      }
      if (!e.target.closest('[data-gen]')) return;
      const o = opts();
      if (!files.length && !String(g.pasted || '').trim() && !String(g.custom || '').trim()) { toast('कोई फ़ाइल/टेक्स्ट दें या Custom निर्देश लिखें', 'warn'); return; }
      const btn = el.querySelector('[data-gen]'); btn.disabled = true;
      const { createJob } = await import('../jobs.js');
      const title = String(g.title || '').trim() || `AI प्रश्न — ${g.section || files[0]?.file.name.replace(/\.[^.]+$/, '') || 'कस्टम'}`;
      const { pasted, ...params } = o;
      const j = await createJob({ kind: 'generate', title, sources: files.map((f) => ({ file: f.file, pages: f.pages })), pasted: String(g.pasted || ''), params });
      toast('काम बैकग्राउंड में शुरू हुआ ✓ — कहीं भी जाएँ, चलता रहेगा', 'success', 3500);
      navigate(`job/${j.id}`);
    });
  }

  const draw = () => { drawTabs(); ({ json: drawJson, prompt: drawPrompt, ai: drawAi })[tab](); };
  draw();

  // Files opened/shared into the app from Telegram, WhatsApp, file manager… (Android)
  const takeIncoming = async () => {
    const list = window.__abhyasTakeIncoming ? window.__abhyasTakeIncoming() : [];
    if (!list.length) return;
    try { await handleTexts(list); } catch (err) { toast(err.message, 'error', 6000); }
  };
  window.__abhyasOnIncoming = takeIncoming;
  if (query.incoming) setTimeout(takeIncoming, 50);

  root.addEventListener('click', async (e) => {
    if (e.target.closest('[data-telegram]')) { openTelegram(); return; }
    const t = e.target.closest('[data-tab]');
    if (t && !job) { tab = t.dataset.tab; history.replaceState(history.state, '', `#/import?tab=${tab}`); draw(); return; }
    const am = e.target.closest('[data-aimode]');
    if (am && !job) { aiMode = am.dataset.aimode; history.replaceState(history.state, '', `#/import?tab=ai&mode=${aiMode}`); drawAi(); return; }
    const act = e.target.closest('[data-act]')?.dataset.act;
    if (act === 'copy') copyPrompt();
    if (act === 'sample') { const w = downloadText('abhyas_sample_practice_set.json', JSON.stringify(SCHEMA_EXAMPLE, null, 2)); toast(`सेव हुआ → ${w}`, 'success'); }
    if (act === 'paste') {
      const text = body.querySelector('[data-paste]').value.trim();
      if (!text) { toast('पहले JSON पेस्ट करें', 'warn'); return; }
      try { await handleTexts([{ name: 'Pasted text', text }]); } catch (err) { toast(err.message, 'error', 5000); }
    }
  });

  return () => { if (job) job.controller.abort(); if (window.__abhyasOnIncoming === takeIncoming) window.__abhyasOnIncoming = null; };
}
