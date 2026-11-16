// Sharing tests between students: build a bundle (one or many papers, whole or filtered by
// subject/topic, or one combined test), send it through any app (Telegram, WhatsApp, Drive,
// Bluetooth…) and import bundles received from others.
import { db } from './db.js';
import { normalizeSet, toStandardJson } from './schema.js';
import { subjectKey, subjectsOf } from './practice.js';
import { uid, downloadText, slug } from './util.js';

export const TELEGRAM_URL = 'https://t.me/+vNwQ4MGblU4wYTc1';
export const BUNDLE_VERSION = 1;

export async function shareableSets() {
  return (await db.all('sets')).filter((s) => s.kind !== 'practice')
    .sort((a, b) => (a.meta.title || '').localeCompare(b.meta.title || '', 'hi', { numeric: true }));
}

// [{ key, label, count }] topics of the chosen subjects
export function topicsOf(sets, subjects) {
  const want = new Set(subjects);
  const map = new Map();
  for (const s of sets) for (const q of s.questions) {
    if (want.size && !want.has(subjectKey(q.section))) continue;
    const t = String(q.topic || 'अन्य').trim() || 'अन्य';
    const k = t.toLowerCase();
    const e = map.get(k) || { key: k, label: t, count: 0 };
    e.count++;
    map.set(k, e);
  }
  return [...map.values()].sort((a, b) => b.count - a.count);
}

export { subjectsOf };

const shuffle = (arr) => { for (let i = arr.length - 1; i > 0; i--) { const j = Math.floor(Math.random() * (i + 1)); [arr[i], arr[j]] = [arr[j], arr[i]]; } return arr; };

function matches(q, subj, tops) {
  if (subj.size && !subj.has(subjectKey(q.section))) return false;
  if (tops.size && !tops.has(String(q.topic || 'अन्य').trim().toLowerCase())) return false;
  return true;
}

// Questions in a shared file never point at the sender's own database.
const clean = ({ answer_source, origin, ...q }) => q;

function subsetSet(set, subj, tops, label) {
  const questions = set.questions.filter((q) => matches(q, subj, tops)).map(clean);
  if (!questions.length) return null;
  const used = new Set(questions.map((q) => q.passage_id).filter(Boolean));
  const passages = Object.fromEntries(Object.entries(set.passages || {}).filter(([k]) => used.has(k)));
  const sections = set.sections.filter((s) => questions.some((q) => q.section === s));
  const whole = questions.length === set.questions.length;
  const meta = { ...set.meta };
  if (!whole) {
    meta.exam_id = `${set.meta.exam_id}__${slug(label).slice(0, 40) || 'part'}`;
    meta.title = `${set.meta.title} — ${label}`;
    meta.max_marks = questions.length * (Number(meta.positive_marks) || 1);
    meta.duration_minutes = Math.max(5, Math.round(set.meta.duration_minutes * questions.length / set.questions.length));
  }
  return { exam_meta: meta, sections, passages, questions };
}

/**
 * opts: { setIds, subjects:[key], topics:[key], combine:boolean, count, shuffled, title, pos, neg, minutes }
 * Returns { bundle, qCount, setCount, name }
 */
export async function buildBundle(opts) {
  const all = await shareableSets();
  const sets = all.filter((s) => opts.setIds.includes(s.id));
  if (!sets.length) throw new Error('कम से कम एक पेपर चुनें');
  const subj = new Set(opts.subjects || []);
  const tops = new Set(opts.topics || []);
  const subjLabels = subjectsOf(sets).filter((s) => subj.has(s.key)).map((s) => s.label);
  const label = [...subjLabels, ...topicsOf(sets, [...subj]).filter((t) => tops.has(t.key)).map((t) => t.label)].slice(0, 4).join(', ') || 'चयनित प्रश्न';
  let out = [];
  if (!opts.combine) {
    for (const s of sets) { const x = subsetSet(s, subj, tops, label); if (x) out.push(x); }
  } else {
    let picks = [];
    for (const s of sets) for (const q of s.questions) if (matches(q, subj, tops)) picks.push({ s, q });
    if (opts.shuffled) shuffle(picks);
    if (opts.count > 0) picks = picks.slice(0, opts.count);
    const passages = {};
    const sections = [];
    const questions = picks.map(({ s, q }, i) => {
      const pid = q.passage_id && s.passages?.[q.passage_id] ? `${slug(s.meta.exam_id).slice(0, 30)}__${q.passage_id}` : null;
      if (pid && !passages[pid]) passages[pid] = { ...s.passages[q.passage_id] };
      if (!sections.includes(q.section)) sections.push(q.section);
      return { ...clean(q), q_no: i + 1, passage_id: pid };
    });
    if (questions.length) {
      const pos = Number(opts.pos) || 1;
      out.push({
        exam_meta: {
          exam_id: uid('shared'), title: opts.title?.trim() || `${label} (${questions.length} प्रश्न)`,
          duration_minutes: Number(opts.minutes) || Math.max(5, Math.round(questions.length * 1.2)),
          max_marks: questions.length * pos, positive_marks: pos, negative_marks: Number(opts.neg) || 0,
          instructions_hi: 'यह टेस्ट Abhyas CBT ऐप में किसी साथी द्वारा शेयर किया गया है।', instructions_en: '',
        },
        sections, passages, questions,
      });
    }
  }
  if (!out.length) throw new Error('चुने गए विषय/टॉपिक में कोई प्रश्न नहीं मिला');
  const qCount = out.reduce((n, x) => n + x.questions.length, 0);
  const title = out.length === 1 ? out[0].exam_meta.title : `${out.length} टेस्ट (${qCount} प्रश्न)`;
  const bundle = { abhyas_bundle: BUNDLE_VERSION, app: 'Abhyas CBT', title, created_at: new Date().toISOString(), telegram: TELEGRAM_URL, sets: out };
  const name = `abhyas_${slug(title).slice(0, 50) || 'test'}.json`;
  return { bundle, qCount, setCount: out.length, name };
}

export function bundleOfSet(set) {
  return { abhyas_bundle: BUNDLE_VERSION, app: 'Abhyas CBT', title: set.meta.title, created_at: new Date().toISOString(), telegram: TELEGRAM_URL, sets: [toStandardJson({ ...set, questions: set.questions.map(clean) })] };
}

export function shareMessage(bundle) {
  const n = bundle.sets.reduce((a, s) => a + s.questions.length, 0);
  return `📚 ${bundle.title}\n${bundle.sets.length} टेस्ट • ${n} प्रश्न\nAbhyas CBT ऐप में खोलें: फ़ाइल पर टैप करें → Abhyas CBT, या ऐप में Import → JSON फ़ाइल चुनें।\nग्रुप: ${TELEGRAM_URL}`;
}

/** Send the file through the phone's share sheet. Returns 'shared' | 'saved' | 'cancelled'. */
export async function shareFile(name, text, message = '') {
  const nb = window.AndroidBridge;
  if (nb && typeof nb.shareFile === 'function') { nb.shareFile(name, text, 'application/json', message); return 'shared'; }
  try {
    const file = new File([text], name, { type: 'application/json' });
    if (navigator.canShare && navigator.canShare({ files: [file] })) {
      await navigator.share({ files: [file], title: name, text: message });
      return 'shared';
    }
  } catch (e) { if (e.name === 'AbortError') return 'cancelled'; }
  downloadText(name, text);
  return 'saved';
}

export function openTelegram() {
  const nb = window.AndroidBridge;
  if (nb && typeof nb.openUrl === 'function') nb.openUrl(TELEGRAM_URL);
  else window.open(TELEGRAM_URL, '_blank', 'noopener');
}

/** Expand any received JSON (bundle, single set, or AI reply) into [{ set, warnings }]. */
export function expandImport(data) {
  if (data && typeof data === 'object' && Array.isArray(data.sets) && (data.abhyas_bundle || data.sets.every((s) => s && s.questions))) {
    const out = [];
    for (const s of data.sets) { try { out.push(normalizeSet(s, { source: 'shared' })); } catch { /* skip broken part */ } }
    if (!out.length) throw new Error('शेयर की गई फ़ाइल में कोई वैध टेस्ट नहीं है');
    return out;
  }
  return [normalizeSet(data, { source: 'json' })];
}

export const telegramCard = (compact = false) => `
  <div class="card mb-3 flex items-center gap-3 !p-3 border-sky-300 dark:border-sky-800">
    <span class="flex h-10 w-10 shrink-0 items-center justify-center rounded-xl bg-sky-100 text-xl dark:bg-sky-900/40">✈️</span>
    <span class="min-w-0 flex-1"><span class="block text-sm font-bold">Telegram ग्रुप से जुड़ें</span>
    <span class="block text-[11px] text-slate-500">${compact ? 'साथियों से टेस्ट लें और दें' : 'यहाँ टेस्ट फ़ाइलें शेयर करें, दूसरों के टेस्ट डाउनलोड करके Import करें'}</span></span>
    <button class="btn-primary btn-sm !bg-sky-500" data-telegram>जुड़ें</button>
  </div>`;
