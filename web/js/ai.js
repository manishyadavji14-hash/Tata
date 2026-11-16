// Provider-agnostic AI client. Supports OpenAI-compatible (/chat/completions), Anthropic Claude (/messages)
// and Google Gemini (generateContent). Inside the Android APK requests go through a native bridge so
// endpoints without CORS headers also work.
import { getSettings } from './db.js';
import { sleep } from './util.js';

export const PROVIDERS = {
  openai: { label: 'OpenAI-compatible (OpenAI, Kiro, OpenRouter, Groq, DeepSeek, Ollama…)', base: 'https://api.openai.com/v1', model: 'gpt-4o-mini' },
  anthropic: { label: 'Anthropic Claude', base: 'https://api.anthropic.com/v1', model: 'claude-sonnet-4-5' },
  gemini: { label: 'Google Gemini', base: 'https://generativelanguage.googleapis.com/v1beta', model: 'gemini-2.5-flash' },
};

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
    throw new Error(`नेटवर्क त्रुटि / Network error: ${e.message}. इंटरनेट, Base URL या CORS जाँचें (Android APK में CORS की समस्या नहीं होती).`);
  }
  return { status: res.status, text: await res.text() };
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

export async function aiConfig() {
  const s = await getSettings();
  return { provider: s.provider, apiBase: s.apiBase, apiKey: s.apiKey, model: s.model, temperature: Number(s.temperature) || 0.2 };
}

export function isConfigured(cfg) {
  return !!(cfg && cfg.apiKey && cfg.apiBase && cfg.model);
}

/**
 * chat({ system, messages: [{ role: 'user'|'assistant', text, images?: [dataUrl] }], json, maxTokens, signal })
 * Returns { text, truncated }.
 */
export async function chat(req, cfgOverride) {
  const cfg = cfgOverride || (await aiConfig());
  if (!isConfigured(cfg)) throw new Error('पहले Settings में API Key, Base URL और Model सेव करें / Configure the API in Settings first.');
  const build = BUILDERS[cfg.provider] || buildOpenAI;
  const spec = build(cfg, { temperature: req.temperature ?? cfg.temperature, ...req });
  let lastErr;
  for (let attempt = 0; attempt < 5; attempt++) {
    const res = await httpRequest(spec.url, { method: 'POST', headers: spec.headers, body: JSON.stringify(spec.body), signal: req.signal });
    if (res.status >= 200 && res.status < 300) {
      let data;
      try { data = JSON.parse(res.text); } catch { throw new Error(`API ने JSON नहीं लौटाया / Non-JSON response: ${res.text.slice(0, 200)}`); }
      return spec.parse(data);
    }
    lastErr = Object.assign(new Error(errorMessage(res.status, res.text)), { status: res.status });
    if (res.status === 400 && adaptOpenAIBody(spec.body, res.text)) continue; // parameter not supported → retry adapted
    if (!(res.status === 429 || res.status >= 500)) throw lastErr;
    await sleep(res.status === 429 ? 4000 * (attempt + 1) : 1500 * (attempt + 1));
  }
  throw lastErr;
}

export async function testConnection(cfg) {
  const r = await chat({ messages: [{ role: 'user', text: 'Reply with exactly: OK' }], maxTokens: 20 }, cfg);
  return r.text.trim();
}
