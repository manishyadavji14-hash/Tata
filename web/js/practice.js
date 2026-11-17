// Cross-set practice: subject-wise custom tests and the "wrong answers" bank.
// A practice test is a temporary set (kind: 'practice') built from copies of questions from many sets;
// each copy remembers where it came from (q.origin) so results update the original question's record.
import { db } from './db.js';
import { uid } from './util.js';

export const MASTER_AFTER = 5; // correct answers needed to clear a question from the wrong-answers bank

// ---------- subject names ----------
// Different papers spell subjects differently (हिन्दी / HINDI / Hindi). Group them under one key.
const ALIASES = [
  ['hindi', /hindi|हिन्दी|हिंदी/i],
  ['english', /english|अंग्रेज/i],
  ['sanskrit', /sanskrit|संस्कृत/i],
  ['science', /science|विज्ञान/i],
  ['maths', /math|गणित/i],
  ['evs', /पर्यावरण|सामाजिक अध्ययन|evs|social/i],
  ['cdp', /cdp|बाल विकास|pedagogy|शिक्षाशास्त्र/i],
  ['gk', /सामान्य ज्ञान|समसा|current|general knowledge|\bgk\b/i],
  ['reasoning', /reasoning|तर्क/i],
  ['it', /सूचना|तकनीक|computer|\bit\b|ict/i],
];
export function subjectKey(section) {
  const s = String(section || '').trim();
  for (const [k, re] of ALIASES) if (re.test(s)) return k;
  return s.toLowerCase().replace(/\s+/g, ' ') || 'other';
}

export async function practiceSources() {
  return (await db.all('sets')).filter((s) => s.kind !== 'practice').sort((a, b) => (a.meta.title || '').localeCompare(b.meta.title || '', 'hi', { numeric: true }));
}

// [{ key, label, count }] across the given sets
export function subjectsOf(sets) {
  const map = new Map();
  for (const s of sets) for (const q of s.questions) {
    const k = subjectKey(q.section);
    const e = map.get(k) || { key: k, count: 0, names: new Map() };
    e.count++;
    e.names.set(q.section, (e.names.get(q.section) || 0) + 1);
    map.set(k, e);
  }
  return [...map.values()].map((e) => ({ key: e.key, count: e.count, label: [...e.names.entries()].sort((a, b) => b[1] - a[1])[0][0] }));
}

export const shuffle = (arr) => { for (let i = arr.length - 1; i > 0; i--) { const j = Math.floor(Math.random() * (i + 1)); [arr[i], arr[j]] = [arr[j], arr[i]]; } return arr; };
const shortTitle = (t) => String(t || '').replace(/SUPER TET प्राथमिक\s*/i, '').replace(/\(चन्द्रा इंस्टीट्यूट\)/, '').trim();

// picks: [{ set, q }] in the order they should appear.
// kind 'practice' = temporary test (hidden from the home list); kind null = a permanent practice set.
export async function buildPracticeSet(picks, { title, minutesPerQ = 1.25, kind = 'practice', extra = {}, instructions = null, neg = 0 }) {
  const passages = {};
  const sections = [];
  const questions = picks.map(({ set, q }, i) => {
    const pid = q.passage_id && set.passages?.[q.passage_id] ? `${set.id}__${q.passage_id}` : null;
    if (pid && !passages[pid]) passages[pid] = { ...set.passages[q.passage_id] };
    if (!sections.includes(q.section)) sections.push(q.section);
    return {
      ...q, q_no: i + 1, passage_id: pid,
      origin: q.origin || { setId: set.id, q_no: q.q_no },
      syllabus_tag: `${shortTitle(set.meta.title)} • Q${q.origin?.q_no ?? q.q_no}`,
    };
  });
  const id = uid(kind === 'practice' ? 'prac' : 'myset');
  const row = {
    id, createdAt: Date.now(), ...(kind ? { kind } : { source: 'builder' }), ...extra,
    meta: {
      exam_id: id, title, duration_minutes: Math.max(1, Math.round(questions.length * minutesPerQ)),
      max_marks: questions.length, positive_marks: 1, negative_marks: neg,
      instructions_hi: instructions?.hi ?? 'यह अभ्यास टेस्ट आपके सभी प्रैक्टिस सेट से चुने गए प्रश्नों से बना है। हर प्रश्न के हल में उसका मूल पेपर और प्रश्न संख्या लिखी है।',
      instructions_en: instructions?.en ?? 'Practice test built from questions across your practice sets.',
    },
    sections, passages, questions,
  };
  if (kind === 'practice') await cleanupPractice();
  await db.put('sets', row);
  return row;
}

// Remove old practice sets that were never attempted (keeps the database small). Daily sets have their own expiry.
async function cleanupPractice() {
  const sets = (await db.all('sets')).filter((s) => s.kind === 'practice' && !s.daily);
  for (const s of sets) {
    const atts = await db.byIndex('attempts', 'setId', s.id);
    if (!atts.length && Date.now() - (s.createdAt || 0) > 10 * 60000) await db.delete('sets', s.id);
  }
}

export async function createSubjectPractice({ setIds, subjects, count, shuffled }) {
  const sets = (await practiceSources()).filter((s) => !setIds?.length || setIds.includes(s.id));
  const want = new Set(subjects);
  let picks = [];
  for (const set of sets) for (const q of set.questions) if (want.has(subjectKey(q.section))) picks.push({ set, q });
  if (shuffled) shuffle(picks);
  picks = picks.slice(0, Math.max(1, count));
  const labels = subjectsOf(sets).filter((s) => want.has(s.key)).map((s) => s.label);
  return buildPracticeSet(picks, { title: `विषयवार अभ्यास — ${labels.slice(0, 3).join(', ')}${labels.length > 3 ? ` +${labels.length - 3}` : ''} (${picks.length} प्रश्न${shuffled ? ', Shuffle' : ''})` });
}

// ---------- wrong-answers bank ----------
const mKey = (setId, qn) => `${setId}|${qn}`;

// Resolve bank rows to live questions (skips questions whose set was deleted).
export async function mistakeRows() {
  const [rows, sets] = await Promise.all([db.all('mistakes'), practiceSources()]);
  const setMap = new Map(sets.map((s) => [s.id, s]));
  const out = [];
  for (const r of rows) {
    const set = setMap.get(r.setId);
    const q = set?.questions.find((x) => x.q_no === r.q_no);
    if (q) out.push({ ...r, set, q, subject: subjectKey(q.section) });
  }
  return out.sort((a, b) => (b.lastWrongAt || 0) - (a.lastWrongAt || 0));
}

export async function createMistakePractice({ subjects, count, shuffled }) {
  const rows = (await mistakeRows()).filter((r) => !subjects?.length || subjects.includes(r.subject));
  let picks = rows.map((r) => ({ set: r.set, q: r.q }));
  if (shuffled) shuffle(picks);
  else picks.sort((a, b) => (a.set.meta.title || '').localeCompare(b.set.meta.title || '', 'hi', { numeric: true }) || a.q.q_no - b.q.q_no);
  picks = picks.slice(0, Math.max(1, count));
  return buildPracticeSet(picks, { title: `गलत प्रश्न अभ्यास (${picks.length} प्रश्न${shuffled ? ', Shuffle' : ''})` });
}

// Called after every submitted attempt. Wrong → into the bank; correct → +1 towards MASTER_AFTER.
export async function recordMistakes(set, result) {
  const qmap = new Map(set.questions.map((q) => [q.q_no, q]));
  const now = Date.now();
  let added = 0, cleared = 0;
  for (const it of result.items) {
    if (it.status === 'skipped') continue;
    const q = qmap.get(it.q_no);
    if (!q) continue;
    const o = q.origin || { setId: set.id, q_no: q.q_no };
    const key = mKey(o.setId, o.q_no);
    const prev = await db.get('mistakes', key);
    if (it.status === 'wrong') {
      if (!prev) added++;
      await db.put('mistakes', { key, setId: o.setId, q_no: o.q_no, correct: prev?.correct || 0, wrong: (prev?.wrong || 0) + 1, firstWrongAt: prev?.firstWrongAt || now, lastWrongAt: now, lastSeenAt: now });
    } else if (prev) {
      const correct = (prev.correct || 0) + 1;
      if (correct >= MASTER_AFTER) { await db.delete('mistakes', key); cleared++; } else await db.put('mistakes', { ...prev, correct, lastSeenAt: now });
    }
  }
  return { added, cleared };
}

export async function dropMistakesForSet(setId) {
  for (const r of await db.all('mistakes')) if (r.setId === setId) await db.delete('mistakes', r.key);
}

// One-time: build the bank from attempts submitted before this feature existed.
export async function backfillMistakes() {
  const flag = await db.get('settings', 'mistakesBackfilled');
  if (flag?.value) return;
  const [atts, sets] = await Promise.all([db.all('attempts'), db.all('sets')]);
  const setMap = new Map(sets.map((x) => [x.id, x]));
  for (const a of atts.filter((x) => x.state === 'submitted' && x.result).sort((x, y) => (x.submittedAt || 0) - (y.submittedAt || 0))) {
    const set = setMap.get(a.setId);
    if (set) await recordMistakes(set, a.result);
  }
  await db.put('settings', { key: 'mistakesBackfilled', value: true });
}
