// Per-question lifetime record + the unified question bank (subject → topic → concept across all sets)
// + the readiness score that colours the mastery screen.
//
// A question is identified by its *origin*: `${setId}|${q_no}` of the paper it first came from. Copies of a
// question (practice tests, daily sets, sets made in the builder, questions added to another set) carry
// `q.origin`, so answering a copy updates the same record and the same question is never counted twice.
import { db } from './db.js';
import { conceptSubjectKey, subjectIcon, listConceptSets } from './concepts.js';
import { plainText } from './util.js';

export const MASTERED_AT = 3; // a question counts as "तैयार" after this many correct answers

export const qKey = (setId, qn) => `${setId}|${qn}`;
export const originOf = (set, q) => q.origin || { setId: set.id, q_no: q.q_no };
export const originKey = (set, q) => { const o = originOf(set, q); return qKey(o.setId, o.q_no); };

const pad = (n) => String(n).padStart(2, '0');
export function dayKey(ts = Date.now()) { const d = new Date(ts); return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`; }
export function startOfDay(ts = Date.now()) { const d = new Date(ts); d.setHours(0, 0, 0, 0); return d.getTime(); }

// ---------- recording ----------
/** Called after every submitted attempt (any set kind). */
export async function recordQStats(set, result) {
  const qmap = new Map(set.questions.map((q) => [q.q_no, q]));
  const now = Date.now();
  const day = dayKey(now);
  const rows = [];
  for (const it of result.items) {
    const q = qmap.get(it.q_no);
    if (!q) continue;
    const o = originOf(set, q);
    const key = qKey(o.setId, o.q_no);
    const prev = rows.find((r) => r.key === key) || (await db.get('qstats', key)) || { key, setId: o.setId, q_no: o.q_no, correct: 0, wrong: 0, skipped: 0, seen: 0, time: 0, log: [] };
    prev.seen++;
    prev.time += it.time || 0;
    if (it.status === 'correct') prev.correct++;
    else if (it.status === 'wrong') { prev.wrong++; prev.lastWrongAt = now; } else prev.skipped++;
    prev.last = it.status;
    prev.lastAt = now;
    prev.log = [...(prev.log || []), [day, it.status[0], Math.round((it.time || 0) / 1000)]].slice(-40);
    if (!rows.includes(prev)) rows.push(prev);
  }
  if (rows.length) await db.putMany('qstats', rows);
}

/** One-time: build qstats from attempts submitted before v1.8.0. */
export async function backfillQStats() {
  const flag = await db.get('settings', 'qstatsBackfilled');
  if (flag?.value) return;
  const [atts, sets] = await Promise.all([db.all('attempts'), db.all('sets')]);
  const setMap = new Map(sets.map((x) => [x.id, x]));
  for (const a of atts.filter((x) => x.state === 'submitted' && x.result).sort((x, y) => (x.submittedAt || 0) - (y.submittedAt || 0))) {
    const set = setMap.get(a.setId);
    if (set) await recordQStats(set, a.result);
  }
  await db.put('settings', { key: 'qstatsBackfilled', value: true });
}

export async function statsMap() { return new Map((await db.all('qstats')).map((r) => [r.key, r])); }

// ---------- the question bank ----------
export const NO_CONCEPT = '— बिना कॉन्सेप्ट टैग —';
export const conceptOf = (q) => plainText(q.key_concept || '', 90).trim();
export const bankSets = async () => (await db.all('sets')).filter((s) => s.kind !== 'practice' && !s.isDemo);

/**
 * Unique questions across the given sets (default: every imported / made set except demo and temporary tests).
 * The original copy wins; a copy only appears when its original is not in the chosen sets.
 * → [{ key, set, q, sk (subject key), subject, topic, concept }]
 */
export async function bankItems({ setIds = null, sets = null } = {}) {
  let all = sets || (await bankSets());
  if (setIds) all = all.filter((s) => setIds.includes(s.id));
  const out = new Map();
  const add = (set, q) => {
    const key = originKey(set, q);
    if (out.has(key)) return;
    out.set(key, { key, set, q, sk: conceptSubjectKey(q.section), subject: q.section, topic: String(q.topic || '').replace(/\s+/g, ' ').trim() || 'सामान्य', concept: conceptOf(q) });
  };
  for (const s of all) for (const q of s.questions) if (!q.origin) add(s, q);
  for (const s of all) for (const q of s.questions) if (q.origin) add(s, q);
  return [...out.values()];
}

/** Group bank items: [{ key, label, icon, items, topics: [{ name, items, concepts: [{ name, items }] }] }] */
export function groupBank(items) {
  const subj = new Map();
  for (const it of items) {
    if (!subj.has(it.sk)) subj.set(it.sk, { key: it.sk, names: new Map(), topics: new Map(), items: [] });
    const s = subj.get(it.sk);
    s.names.set(it.subject, (s.names.get(it.subject) || 0) + 1);
    s.items.push(it);
    if (!s.topics.has(it.topic)) s.topics.set(it.topic, { name: it.topic, items: [], concepts: new Map() });
    const t = s.topics.get(it.topic);
    t.items.push(it);
    const c = it.concept || NO_CONCEPT;
    if (!t.concepts.has(c)) t.concepts.set(c, { name: c, items: [] });
    t.concepts.get(c).items.push(it);
  }
  return [...subj.values()].map((s) => ({
    key: s.key, label: [...s.names.entries()].sort((a, b) => b[1] - a[1])[0][0], icon: subjectIcon(s.key), items: s.items,
    names: [...s.names.keys()],
    topics: [...s.topics.values()].map((t) => ({ name: t.name, items: t.items, concepts: [...t.concepts.values()].sort((a, b) => (a.name === NO_CONCEPT) - (b.name === NO_CONCEPT) || b.items.length - a.items.length) }))
      .sort((a, b) => b.items.length - a.items.length || a.name.localeCompare(b.name, 'hi')),
  })).sort((a, b) => b.items.length - a.items.length);
}

// ---------- concept notes (Concepts tab) linked to a subject / topic ----------
const norm = (s) => String(s || '').toLowerCase().replace(/\(.*?\)/g, ' ').replace(/[^\p{L}\p{N}\s]/gu, ' ').replace(/\s+/g, ' ').trim();
const words = (s) => new Set(norm(s).split(' ').filter((w) => w.length > 1 && !['एवं', 'और', 'तथा', 'का', 'की', 'के', 'में', 'and', 'of', 'the'].includes(w)));
export function topicsMatch(a, b) {
  const x = norm(a), y = norm(b);
  if (!x || !y) return false;
  if (x === y) return true;
  if (Math.min(x.length, y.length) >= 3 && (x.includes(y) || y.includes(x))) return true;
  const wa = words(a), wb = words(b);
  if (!wa.size || !wb.size) return false;
  let both = 0;
  for (const w of wa) if (wb.has(w)) both++;
  return both / Math.min(wa.size, wb.size) >= 0.6;
}

/** Concept cards from imported / AI concept sets, indexed by subject key: Map<sk, [{ topic, c, setTitle }]> */
export async function conceptCardsBySubject() {
  const map = new Map();
  for (const set of await listConceptSets()) {
    for (const s of set.subjects || []) {
      const k = conceptSubjectKey(s.subject);
      if (!map.has(k)) map.set(k, []);
      for (const t of s.topics || []) for (const c of t.concepts) map.get(k).push({ topic: t.topic, c: { ...c, setId: set.id, setTitle: set.title } });
    }
  }
  return map;
}

/** Concept cards that belong to a topic (matched by topic name or by the concepts tagged on its questions). */
export function linkedConcepts(cards, topic) {
  if (!cards?.length) return [];
  const tags = topic.concepts.map((c) => c.name).filter((n) => n !== NO_CONCEPT);
  return cards.filter((x) => topicsMatch(x.topic, topic.name) || tags.some((t) => topicsMatch(x.c.title, t))).map((x) => x.c);
}

// ---------- readiness ----------
export const LEVELS = {
  new: { label: 'शुरू नहीं', cls: 'bg-slate-100 text-slate-600 dark:bg-slate-800 dark:text-slate-300', bar: 'bg-slate-400', ring: 'border-l-slate-300 dark:border-l-slate-600', dot: '⚪' },
  weak: { label: 'कमजोर', cls: 'bg-rose-100 text-rose-700 dark:bg-rose-900/40 dark:text-rose-300', bar: 'bg-rose-500', ring: 'border-l-rose-500', dot: '🔴' },
  low: { label: 'सुधार चाहिए', cls: 'bg-orange-100 text-orange-700 dark:bg-orange-900/40 dark:text-orange-300', bar: 'bg-orange-500', ring: 'border-l-orange-500', dot: '🟠' },
  mid: { label: 'मध्यम', cls: 'bg-amber-100 text-amber-800 dark:bg-amber-900/40 dark:text-amber-300', bar: 'bg-amber-400', ring: 'border-l-amber-400', dot: '🟡' },
  ready: { label: 'तैयार', cls: 'bg-emerald-100 text-emerald-700 dark:bg-emerald-900/40 dark:text-emerald-300', bar: 'bg-emerald-500', ring: 'border-l-emerald-500', dot: '🟢' },
};
export const levelOfScore = (s) => (s == null ? 'new' : s < 40 ? 'weak' : s < 60 ? 'low' : s < 80 ? 'mid' : 'ready');

/** Global average time per answered question (ms), used for the speed part of the score. */
export function globalAvgTime(stats) {
  let t = 0, n = 0;
  for (const r of stats.values()) { t += r.time || 0; n += r.seen || 0; }
  return n ? t / n : 0;
}

/**
 * Readiness of a group of questions (0–100, or null if none was ever attempted):
 *   35% questions solved correctly ≥3 times (out of all questions in the group)
 *   40% accuracy (correct ÷ (correct + wrong)) of every answer given
 *   10% speed (overall average time ÷ this group's average time, max 1)
 *   15% concept notes marked "याद हो गया" (only when the group has linked concept notes; otherwise its weight goes to accuracy)
 */
export function measure(items, stats, avgAll, concepts = [], prog = null) {
  const m = { total: items.length, mastered: 0, seen: 0, correct: 0, wrong: 0, skipped: 0, time: 0, answers: 0, conceptsTotal: concepts.length, conceptsLearned: 0 };
  for (const it of items) {
    const r = stats.get(it.key);
    if (!r) continue;
    if ((r.correct || 0) >= MASTERED_AT) m.mastered++;
    if (r.seen) m.seen++;
    m.correct += r.correct || 0; m.wrong += r.wrong || 0; m.skipped += r.skipped || 0; m.time += r.time || 0; m.answers += r.seen || 0;
  }
  if (prog) m.conceptsLearned = concepts.filter((c) => prog.get(c.id)?.learned).length;
  m.acc = m.correct + m.wrong ? m.correct / (m.correct + m.wrong) : 0;
  m.avgTime = m.answers ? m.time / m.answers : 0;
  if (!m.seen) { m.score = null; m.level = 'new'; return m; }
  const mastery = m.total ? m.mastered / m.total : 0;
  const speed = !m.avgTime || !avgAll ? 1 : Math.min(1, avgAll / m.avgTime);
  m.score = m.conceptsTotal
    ? 100 * (0.35 * mastery + 0.40 * m.acc + 0.10 * speed + 0.15 * (m.conceptsLearned / m.conceptsTotal))
    : 100 * (0.35 * mastery + 0.55 * m.acc + 0.10 * speed);
  m.score = Math.round(m.score);
  m.level = levelOfScore(m.score);
  return m;
}

export const SCORE_HELP = `<ul class="list-disc space-y-1 pl-5">
  <li><b>35%</b> — टॉपिक के कितने प्रश्न कम से कम ${MASTERED_AT} बार सही हल हुए</li>
  <li><b>40%</b> — Accuracy: सही ÷ (सही + गलत), सभी प्रयासों में</li>
  <li><b>10%</b> — समय: आपके कुल औसत समय की तुलना में इस टॉपिक पर लगा औसत समय (धीमे = कम अंक)</li>
  <li><b>15%</b> — कॉन्सेप्ट समझ: इस टॉपिक के कॉन्सेप्ट नोट्स में से कितने "✓ याद हो गया" किए (नोट्स न हों तो यह हिस्सा Accuracy में जुड़ता है)</li>
</ul>
<div class="mt-2 flex flex-wrap gap-1 text-[11px]">${Object.entries(LEVELS).map(([, l]) => `<span class="chip ${l.cls}">${l.dot} ${l.label}</span>`).join('')}</div>
<div class="mt-1 text-[11px] text-slate-500">🔴 40 से कम • 🟠 40–59 • 🟡 60–79 • 🟢 80+ • ⚪ अभी कोई प्रश्न हल नहीं किया</div>`;

/** Horizontal cylinder gauge (home page + mastery screen). */
export function cylinderHtml(done, total, { big = false } = {}) {
  const p = total ? Math.round((done / total) * 1000) / 10 : 0;
  return `<div class="cyl ${big ? 'cyl-big' : ''}" role="img" aria-label="${done} of ${total}">
    <div class="cyl-body"><div class="cyl-fill" style="width:${Math.max(p, done ? 3 : 0)}%"></div><div class="cyl-cap"></div>
      <div class="cyl-label"><b>${done}</b> / ${total} <span>(${p}%)</span></div></div>
  </div>`;
}
