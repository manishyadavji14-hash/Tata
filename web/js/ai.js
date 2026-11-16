// Provider-agnostic AI client. Supports OpenAI-compatible (/chat/completions), Anthropic Claude (/messages)
// and Google Gemini (generateContent). Inside the Android APK requests go through a native bridge so
// endpoints without CORS headers also work.
import { getSettings } from './db.js';
import { sleep } from './util.js';

// Default model IDs change often: the Settings screen can fetch the live list ("Models लाएँ").
export const PROVIDERS = {
  openai: { label: 'OpenAI-compatible (OpenAI, OpenRouter, Groq, DeepSeek, Ollama…)', base: 'https://api.openai.com/v1', model: '' },
  anthropic: { label: 'Anthropic Claude', base: 'https://api.anthropic.com/v1', model: '' },
  gemini: { label: 'Google Gemini (AI Studio — फ़्री टियर उपलब्ध)', base: 'https://generativelanguage.googleapis.com/v1beta', model: 'gemini-3.8-flash' },
};

// Recognise the provider from the key itself so a user can paste only the key.
const KEY_PATTERNS = [
  [/^AIza[\w-]{30,}$/, { provider: 'gemini', apiBase: PROVIDERS.gemini.base, name: 'Google Gemini' }],
  [/^sk-ant-/, { provider: 'anthropic', apiBase: PROVIDERS.anthropic.base, name: 'Anthropic Claude' }],
  [/^sk-or-/, { provider: 'openai', apiBase: 'https://openrouter.ai/api/v1', name: 'OpenRouter' }],
  [/^gsk_/, { provider: 'openai', apiBase: 'https://api.groq.com/openai/v1', name: 'Groq' }],
  [/^xai-/, { provider: 'openai', apiBase: 'https://api.x.ai/v1', name: 'xAI Grok' }],
  [/^sk-/, { provider: 'openai', apiBase: PROVIDERS.openai.base, name: 'OpenAI (या DeepSeek — Base URL बदलें)' }],
];

export function detectProvider(key) {
  const k = String(key || '').trim();
  for (const [re, info] of KEY_PATTERNS) if (re.test(k)) return info;
  return null;
}

// Hosts that are known NOT to be usable as a chat API from this app.
export function baseUrlProblem(base) {
  const b = String(base || '').trim();
  if (!b) return '';
  if (!/^https?:\/\/[^/\s]+/i.test(b)) return 'Base URL http:// या https:// से शुरू होना चाहिए।';
  if (/kiro\.dev/i.test(b)) return 'Kiro का कोई सार्वजनिक Chat API नहीं है — Kiro की API Key सिर्फ़ Kiro CLI के लिए होती है, इस ऐप में काम नहीं करेगी। Gemini/OpenAI/Claude/OpenRouter की Key इस्तेमाल करें।';
  return '';
}

export function modelProblem(model) {
  const m = String(model || '').trim();
  if (!m) return 'Model Name खाली है — "Models लाएँ" बटन से चुनें।';
  if (/\s/.test(m)) return `Model Name में स्पेस नहीं होता ("${m}" गलत है)। सही ID जैसे gemini-3.8-flash — "Models लाएँ" बटन से चुनें।`;
  return '';
}

// ---------- Transport ----------
let nativeSeq = 0;
const nativeWaiters = new Map();
window.__abhyasHttpDone = (id) => {
  const w = nativeWaiters.get(id);
  if (!w) return;
  nativeWaiters.delete(id);
  try {
    const r = JSON.parse(window.AndroidBridge.takeResponse(id));
    if (r.error) w.reject(new Error(r.error));
    else w.resolve({ status: r.status, text: r.body });
  } catch (e) { w.reject(e); }
};

function nativeRequest(url, { method, headers, body, signal }) {
  const nb = window.AndroidBridge;
  return new Promise((resolve, reject) => {
    const id = `r${++nativeSeq}`;
    nativeWaiters.set(id, { resolve, reject });
    if (signal) {
      if (signal.aborted) { nativeWaiters.delete(id); reject(new DOMException('Aborted', 'AbortError')); return; }
      signal.addEventListener('abort', () => {
        if (!nativeWaiters.has(id)) return;
        nativeWaiters.delete(id);
        try { nb.cancelRequest(id); } catch { /* ignore */ }
        reject(new DOMException('Aborted', 'AbortError'));
      }, { once: true });
    }
    nb.httpRequest(id, url, method, JSON.stringify(headers), body || '');
  });
}

async function httpRequest(url, opts) {
  if (window.AndroidBridge && typeof window.AndroidBridge.httpRequest === 'function') return nativeRequest(url, opts);
  let res;
  try {
    res = await fetch(url, { method: opts.method, headers: opts.headers, body: opts.body, signal: opts.signal });
  } catch (e) {
    if (e.name === 'AbortError') throw e;
    throw fatal(`नेटवर्क त्रुटि / Network error: ${e.message}. इंटरनेट, Base URL या CORS जाँचें (Android APK में CORS की समस्या नहीं होती).`);
  }
  return { status: res.status, text: await res.text() };
}

// A "fatal" error means every further request will fail the same way (wrong URL, key, model,
// no internet). Long jobs such as PDF import stop immediately instead of repeating it per page.
function fatal(message) { return Object.assign(new Error(message), { fatal: true }); }

function explainNetwork(msg) {
  if (/resolve host|ENOTFOUND|UnknownHost|No address associated/i.test(msg)) {
    return fatal(`${msg}\n➜ यह Base URL इंटरनेट पर मौजूद ही नहीं है (host नहीं मिला)। Base URL गलत है — Settings में सही Provider चुनें या Base URL खाली छोड़ दें।`);
  }
  if (/timeout|timed out/i.test(msg)) return new Error(`${msg}\n➜ सर्वर ने समय पर जवाब नहीं दिया। इंटरनेट जाँचें या Render Scale घटाएँ।`);
  return fatal(`${msg}\n➜ इंटरनेट कनेक्शन या Base URL जाँचें।`);
}

async function request(url, opts) {
  try { return await httpRequest(url, opts); } catch (e) {
    if (e.name === 'AbortError' || e.fatal) throw e;
    throw explainNetwork(e.message);
  }
}

// ---------- Request builders ----------
const trimSlash = (s) => String(s || '').trim().replace(/\/+$/, '');
const splitDataUrl = (d) => { const m = /^data:([^;]+);base64,(.*)$/s.exec(d); return m ? { mime: m[1], data: m[2] } : { mime: 'image/jpeg', data: d }; };

function buildOpenAI(cfg, { system, messages, json, maxTokens, temperature }) {
  const msgs = [];
  if (system) msgs.push({ role: 'system', content: system });
  for (const m of messages) {
    if (m.images && m.images.length) {
      msgs.push({ role: m.role, content: [{ type: 'text', text: m.text }, ...m.images.map((u) => ({ type: 'image_url', image_url: { url: u, detail: 'high' } }))] });
    } else msgs.push({ role: m.role, content: m.text });
  }
  const body = { model: cfg.model, messages: msgs, temperature };
  if (maxTokens) body.max_tokens = maxTokens;
  if (json) body.response_format = { type: 'json_object' };
  return {
    url: `${trimSlash(cfg.apiBase)}/chat/completions`,
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${cfg.apiKey}` },
    body,
    parse: (d) => {
      const c = d?.choices?.[0];
      const content = c?.message?.content;
      const text = Array.isArray(content) ? content.map((p) => p.text || '').join('') : content;
      return { text: text || '', truncated: c?.finish_reason === 'length' };
    },
  };
}

function buildAnthropic(cfg, { system, messages, maxTokens, temperature }) {
  const msgs = messages.map((m) => ({
    role: m.role,
    content: [
      ...(m.images || []).map((u) => { const { mime, data } = splitDataUrl(u); return { type: 'image', source: { type: 'base64', media_type: mime, data } }; }),
      { type: 'text', text: m.text },
    ],
  }));
  return {
    url: `${trimSlash(cfg.apiBase)}/messages`,
    headers: {
      'Content-Type': 'application/json', 'x-api-key': cfg.apiKey, 'anthropic-version': '2023-06-01',
      'anthropic-dangerous-direct-browser-access': 'true',
    },
    body: { model: cfg.model, system: system || undefined, messages: msgs, max_tokens: maxTokens || 8192, temperature },
    parse: (d) => ({ text: (d?.content || []).filter((p) => p.type === 'text').map((p) => p.text).join(''), truncated: d?.stop_reason === 'max_tokens' }),
  };
}

function buildGemini(cfg, { system, messages, json, maxTokens, temperature }) {
  const contents = messages.map((m) => ({
    role: m.role === 'assistant' ? 'model' : 'user',
    parts: [
      ...(m.images || []).map((u) => { const { mime, data } = splitDataUrl(u); return { inline_data: { mime_type: mime, data } }; }),
      { text: m.text },
    ],
  }));
  const generationConfig = { temperature };
  if (maxTokens) generationConfig.maxOutputTokens = maxTokens;
  if (json) generationConfig.responseMimeType = 'application/json';
  const model = String(cfg.model || '').replace(/^models\//, '');
  return {
    url: `${trimSlash(cfg.apiBase)}/models/${encodeURIComponent(model)}:generateContent`,
    headers: { 'Content-Type': 'application/json', 'x-goog-api-key': cfg.apiKey },
    body: { contents, generationConfig, ...(system ? { systemInstruction: { parts: [{ text: system }] } } : {}) },
    parse: (d) => {
      const c = d?.candidates?.[0];
      return { text: (c?.content?.parts || []).map((p) => p.text || '').join(''), truncated: c?.finishReason === 'MAX_TOKENS' };
    },
  };
}

const BUILDERS = { openai: buildOpenAI, anthropic: buildAnthropic, gemini: buildGemini };

// OpenAI-compatible servers differ in which optional parameters they accept (e.g. reasoning models reject
// max_tokens/temperature, some local servers reject response_format). Mutates body; returns true if changed.
function adaptOpenAIBody(body, errText) {
  if (!body.messages) return false;
  const t = String(errText).toLowerCase();
  if (body.max_tokens != null && t.includes('max_tokens')) { body.max_completion_tokens = body.max_tokens; delete body.max_tokens; return true; }
  if (body.temperature != null && t.includes('temperature')) { delete body.temperature; return true; }
  if (body.response_format && (t.includes('response_format') || t.includes('json'))) { delete body.response_format; return true; }
  return false;
}

function errorMessage(status, text) {
  let detail = text;
  try { const d = JSON.parse(text); detail = d?.error?.message || d?.message || d?.error || text; } catch { /* raw */ }
  detail = String(typeof detail === 'object' ? JSON.stringify(detail) : detail).slice(0, 300);
  const hint = { 400: 'Request/मॉडल नाम जाँचें', 401: 'API Key गलत है', 403: 'Key को इस मॉडल की अनुमति नहीं है', 404: 'Base URL या Model Name गलत है', 413: 'पेज इमेज बहुत बड़ी है — Settings में Render Scale घटाएँ', 429: 'Rate limit / quota खत्म' }[status] || '';
  return `API ${status}${hint ? ` (${hint})` : ''}: ${detail}`;
}

// Fill gaps: empty Base URL → provider default; provider guessed from the key if it clearly belongs elsewhere.
export function resolveConfig(cfg) {
  const c = { ...cfg, apiKey: String(cfg.apiKey || '').trim(), model: String(cfg.model || '').trim() };
  const det = detectProvider(c.apiKey);
  if (det && (det.provider === 'gemini' || det.provider === 'anthropic') && c.provider !== det.provider) {
    c.provider = det.provider;
    if (!c.apiBase || Object.values(PROVIDERS).some((p) => p.base === c.apiBase)) c.apiBase = det.apiBase;
  }
  if (!String(c.apiBase || '').trim()) c.apiBase = det?.apiBase || PROVIDERS[c.provider]?.base || PROVIDERS.openai.base;
  if (!c.model && c.provider === 'gemini') c.model = PROVIDERS.gemini.model;
  return c;
}

export async function aiConfig() {
  const s = await getSettings();
  return resolveConfig({ provider: s.provider, apiBase: s.apiBase, apiKey: s.apiKey, model: s.model, temperature: Number(s.temperature) || 0.2 });
}

export function isConfigured(cfg) {
  return !!(cfg && cfg.apiKey && cfg.model);
}

function authHeaders(cfg) {
  if (cfg.provider === 'anthropic') return { 'x-api-key': cfg.apiKey, 'anthropic-version': '2023-06-01', 'anthropic-dangerous-direct-browser-access': 'true' };
  if (cfg.provider === 'gemini') return { 'x-goog-api-key': cfg.apiKey };
  return { Authorization: `Bearer ${cfg.apiKey}` };
}

// Lists model IDs the key can use (so the user never has to type a model name).
export async function listModels(cfgIn) {
  const cfg = resolveConfig(cfgIn);
  if (!cfg.apiKey) throw new Error('पहले API Key डालें');
  const p = baseUrlProblem(cfg.apiBase);
  if (p) throw fatal(p);
  const url = cfg.provider === 'gemini' ? `${trimSlash(cfg.apiBase)}/models?pageSize=1000` : `${trimSlash(cfg.apiBase)}/models`;
  const res = await request(url, { method: 'GET', headers: authHeaders(cfg) });
  if (res.status < 200 || res.status >= 300) throw fatal(errorMessage(res.status, res.text));
  let d;
  try { d = JSON.parse(res.text); } catch { throw fatal(`यह Base URL कोई AI API नहीं लगता (JSON नहीं मिला): ${res.text.slice(0, 120)}`); }
  let ids;
  if (cfg.provider === 'gemini') {
    ids = (d.models || []).filter((m) => (m.supportedGenerationMethods || []).includes('generateContent')).map((m) => m.name.replace(/^models\//, ''));
  } else ids = (d.data || d.models || []).map((m) => m.id || m.name).filter(Boolean);
  // Hide models that cannot chat (embeddings, speech, image generation).
  ids = ids.filter((id) => !/embed|tts|whisper|audio|transcribe|dall-e|image-|imagen|moderation|live|veo|omni|banana|aqa|rerank/i.test(id));
  return { ids: [...new Set(ids)].sort(), cfg };
}

/**
 * chat({ system, messages: [{ role: 'user'|'assistant', text, images?: [dataUrl] }], json, maxTokens, signal })
 * Returns { text, truncated }.
 */
export async function chat(req, cfgOverride) {
  const cfg = resolveConfig(cfgOverride || (await aiConfig()));
  if (!cfg.apiKey) throw fatal('पहले Settings में API Key सेव करें / Configure the API in Settings first.');
  const problem = baseUrlProblem(cfg.apiBase) || modelProblem(cfg.model);
  if (problem) throw fatal(problem);
  const build = BUILDERS[cfg.provider] || buildOpenAI;
  const spec = build(cfg, { temperature: req.temperature ?? cfg.temperature, ...req });
  let lastErr;
  for (let attempt = 0; attempt < 5; attempt++) {
    const res = await request(spec.url, { method: 'POST', headers: spec.headers, body: JSON.stringify(spec.body), signal: req.signal });
    if (res.status >= 200 && res.status < 300) {
      let data;
      try { data = JSON.parse(res.text); } catch { throw fatal(`API ने JSON नहीं लौटाया — Base URL किसी AI API का नहीं लगता। जवाब: ${res.text.slice(0, 160)}`); }
      const out = spec.parse(data);
      if (!out.text.trim()) {
        const reason = data?.candidates?.[0]?.finishReason || data?.choices?.[0]?.finish_reason || data?.stop_reason || data?.promptFeedback?.blockReason || '';
        if (/MAX_TOKENS|length|max_tokens/i.test(reason) && spec.body) {
          // Reasoning models can spend the whole budget "thinking"; retry once with a larger budget.
          if (!spec.__grown) { spec.__grown = true; growTokens(spec.body); continue; }
        }
        throw fatal(`API ने खाली जवाब दिया${reason ? ` (कारण: ${reason})` : ''}। Model Name / Base URL जाँचें। सर्वर का जवाब: ${res.text.slice(0, 160)}`);
      }
      return out;
    }
    lastErr = Object.assign(new Error(errorMessage(res.status, res.text)), { status: res.status, fatal: [400, 401, 403, 404].includes(res.status) });
    if (res.status === 400 && adaptOpenAIBody(spec.body, res.text)) continue; // parameter not supported → retry adapted
    if (!(res.status === 429 || res.status >= 500)) throw lastErr;
    await sleep(res.status === 429 ? 4000 * (attempt + 1) : 1500 * (attempt + 1));
  }
  throw lastErr;
}

function growTokens(body) {
  if (body.max_tokens) body.max_tokens = Math.max(body.max_tokens * 4, 4096);
  if (body.max_completion_tokens) body.max_completion_tokens = Math.max(body.max_completion_tokens * 4, 4096);
  if (body.generationConfig?.maxOutputTokens) body.generationConfig.maxOutputTokens = Math.max(body.generationConfig.maxOutputTokens * 4, 4096);
}

export async function testConnection(cfg) {
  // No small token limit here: "thinking" models would spend it all and return an empty reply.
  const r = await chat({ messages: [{ role: 'user', text: 'Reply with exactly: OK' }] }, cfg);
  return r.text.trim();
}
