// Editing the question bank: rename sets / subjects / topics, re-tag questions, copy questions into another set.
import { db } from './db.js';
import { conceptSubjectKey } from './concepts.js';
import { originKey, originOf } from './qstats.js';
import { uid } from './util.js';

const clean = (s) => String(s || '').replace(/\s+/g, ' ').trim();

/** Keep set.sections in step with the questions (old order first, new subjects at the end, empty ones dropped). */
export function fixSections(set) {
  const used = new Set(set.questions.map((q) => q.section));
  const out = set.sections.filter((s, i) => used.has(s) && set.sections.indexOf(s) === i);
  for (const q of set.questions) if (!out.includes(q.section)) out.push(q.section);
  set.sections = out;
  return set;
}

export async function renameSet(set, title) {
  const t = clean(title);
  if (!t) throw new Error('नाम खाली नहीं हो सकता');
  set.meta = { ...set.meta, title: t };
  set.updatedAt = Date.now();
  await db.put('sets', set);
  return set;
}

/** Rename one subject (section) of one set. If the new name already exists in the set, the two merge. */
export async function renameSection(set, from, to) {
  const t = clean(to);
  if (!t) throw new Error('विषय का नाम खाली नहीं हो सकता');
  let n = 0;
  for (const q of set.questions) if (q.section === from) { q.section = t; n++; }
  set.sections = set.sections.map((s) => (s === from ? t : s));
  fixSections(set);
  set.updatedAt = Date.now();
  await db.put('sets', set);
  return n;
}

export async function renameTopicInSet(set, section, from, to) {
  const t = clean(to);
  if (!t) throw new Error('टॉपिक का नाम खाली नहीं हो सकता');
  let n = 0;
  for (const q of set.questions) if (q.section === section && clean(q.topic) === clean(from)) { q.topic = t; n++; }
  set.updatedAt = Date.now();
  await db.put('sets', set);
  return n;
}

const editableSets = async () => (await db.all('sets')).filter((s) => s.kind !== 'practice');

/** Rename a subject everywhere (every set whose subject falls in the same group, e.g. HINDI / हिन्दी / Hindi). */
export async function renameSubjectEverywhere(sk, to) {
  const t = clean(to);
  if (!t) throw new Error('विषय का नाम खाली नहीं हो सकता');
  let n = 0, sets = 0;
  for (const set of await editableSets()) {
    let hit = false;
    for (const q of set.questions) if (conceptSubjectKey(q.section) === sk && q.section !== t) { q.section = t; n++; hit = true; }
    if (!hit) continue;
    set.sections = set.sections.map((s) => (conceptSubjectKey(s) === sk ? t : s));
    fixSections(set);
    set.updatedAt = Date.now();
    await db.put('sets', set);
    sets++;
  }
  return { n, sets };
}

/** Rename (or merge) a topic of a subject group in every set. */
export async function renameTopicEverywhere(sk, from, to) {
  const t = clean(to);
  if (!t) throw new Error('टॉपिक का नाम खाली नहीं हो सकता');
  let n = 0, sets = 0;
  for (const set of await editableSets()) {
    let hit = false;
    for (const q of set.questions) if (conceptSubjectKey(q.section) === sk && (clean(q.topic) || 'सामान्य') === clean(from)) { q.topic = t; n++; hit = true; }
    if (hit) { set.updatedAt = Date.now(); await db.put('sets', set); sets++; }
  }
  return { n, sets };
}

/**
 * Change subject / topic / concept of bank items in place (inside the set each item lives in).
 * Empty values keep what the question already has; concept === null clears it.
 */
export async function retagItems(items, { subject = '', topic = '', concept = '' }) {
  const bySet = new Map();
  for (const it of items) {
    if (!bySet.has(it.set.id)) bySet.set(it.set.id, []);
    bySet.get(it.set.id).push(it.q.q_no);
  }
  let n = 0;
  for (const [id, qns] of bySet) {
    const set = await db.get('sets', id);
    if (!set) continue;
    const want = new Set(qns);
    for (const q of set.questions) {
      if (!want.has(q.q_no)) continue;
      if (clean(subject)) q.section = clean(subject);
      if (clean(topic)) q.topic = clean(topic);
      if (concept === null) q.key_concept = '';
      else if (clean(concept)) q.key_concept = clean(concept);
      n++;
    }
    fixSections(set);
    set.updatedAt = Date.now();
    await db.put('sets', set);
  }
  return n;
}

/**
 * Copy bank items into a set (an existing one, or a new one when target.newTitle is given).
 * Questions already in the target (same original question) are skipped.
 * The copies keep `origin`, so their results count for the original question.
 */
export async function copyItemsToSet(items, { setId = null, newTitle = '', section = '', topic = '', concept = '' }) {
  let set;
  if (setId) set = await db.get('sets', setId);
  if (!set) {
    const id = uid('myset');
    set = {
      id, source: 'builder', createdAt: Date.now(), sections: [], passages: {}, questions: [],
      meta: { exam_id: id, title: clean(newTitle) || 'मेरा प्रैक्टिस सेट', duration_minutes: 1, max_marks: 0, positive_marks: 1, negative_marks: 0, instructions_hi: '', instructions_en: '' },
    };
  }
  const have = new Set(set.questions.map((q) => originKey(set, q)));
  const perQ = set.questions.length ? (Number(set.meta.duration_minutes) || set.questions.length) / set.questions.length : 1.2;
  let next = Math.max(0, ...set.questions.map((q) => q.q_no));
  let added = 0, skipped = 0;
  for (const it of items) {
    if (have.has(it.key)) { skipped++; continue; }
    have.add(it.key);
    const src = it.set, q = it.q;
    let pid = null;
    if (q.passage_id && src.passages?.[q.passage_id]) {
      pid = src.id === set.id ? q.passage_id : `${src.id}__${q.passage_id}`;
      if (!set.passages[pid]) set.passages[pid] = { ...src.passages[q.passage_id] };
    }
    set.questions.push({
      ...q, q_no: ++next, passage_id: pid, origin: originOf(src, q),
      section: clean(section) || q.section, topic: clean(topic) || q.topic,
      key_concept: clean(concept) || q.key_concept || '',
      syllabus_tag: q.syllabus_tag || `${src.meta.title} • Q${q.q_no}`,
    });
    added++;
  }
  fixSections(set);
  const pos = Number(set.meta.positive_marks) || 1;
  set.meta = { ...set.meta, max_marks: Math.round(set.questions.length * pos * 100) / 100, duration_minutes: Math.max(1, Math.round(perQ * set.questions.length)) };
  set.updatedAt = Date.now();
  await db.put('sets', set);
  return { set, added, skipped };
}
