// Domain-level data helpers on top of the IndexedDB layer.
import { db } from './db.js';
import { uid } from './util.js';

export async function listSets() {
  const sets = (await db.all('sets')).filter((s) => s.kind !== 'practice');
  return sets.sort((a, b) => (b.createdAt || 0) - (a.createdAt || 0)); // newest import first
}

export const getSet = (id) => db.get('sets', id);

export async function saveNewSet(set) {
  const all = await db.all('sets');
  let id = set.meta.exam_id || uid('set');
  if (all.some((s) => s.id === id)) id = `${id}_${Date.now().toString(36)}`;
  const row = { ...set, id, createdAt: Date.now() };
  await db.put('sets', row);
  return row;
}

export async function findSetByExamId(examId) {
  const all = await db.all('sets');
  return all.find((s) => s.meta.exam_id === examId || s.id === examId) || null;
}

// Merge another part (e.g. questions 41-80) into an existing set. Same q_no = replaced.
export async function mergeIntoSet(existing, incoming) {
  const map = new Map(existing.questions.map((q) => [q.q_no, q]));
  let added = 0, replaced = 0;
  for (const q of incoming.questions) { if (map.has(q.q_no)) replaced++; else added++; map.set(q.q_no, q); }
  const questions = [...map.values()].sort((a, b) => a.q_no - b.q_no);
  const sections = [...existing.sections];
  for (const s of incoming.sections) if (!sections.includes(s)) sections.push(s);
  const meta = { ...existing.meta };
  meta.max_marks = Math.max(Number(meta.max_marks) || 0, questions.length * (Number(meta.positive_marks) || 1));
  const row = { ...existing, meta, sections, passages: { ...existing.passages, ...incoming.passages }, questions, updatedAt: Date.now() };
  await db.put('sets', row);
  return { row, added, replaced };
}

// Add new questions after the existing ones (numbers continue, nothing is replaced).
export async function appendToSet(existing, incoming) {
  const start = Math.max(0, ...existing.questions.map((q) => q.q_no));
  const tag = Date.now().toString(36);
  const pmap = {};
  const passages = { ...existing.passages };
  for (const [k, v] of Object.entries(incoming.passages || {})) { pmap[k] = `${k}_${tag}`; passages[pmap[k]] = v; }
  const added = incoming.questions.map((q, i) => ({ ...q, q_no: start + i + 1, passage_id: q.passage_id ? pmap[q.passage_id] || null : null }));
  const questions = [...existing.questions, ...added];
  const sections = [...existing.sections];
  for (const s of incoming.sections) if (!sections.includes(s)) sections.push(s);
  const meta = { ...existing.meta };
  const per = (Number(existing.meta.duration_minutes) || questions.length) / Math.max(1, existing.questions.length);
  meta.max_marks = questions.length * (Number(meta.positive_marks) || 1);
  meta.duration_minutes = Math.max(meta.duration_minutes, Math.round(per * questions.length));
  const row = { ...existing, meta, sections, passages, questions, updatedAt: Date.now() };
  await db.put('sets', row);
  return { row, added: added.length };
}

export async function deleteSet(id) {
  for (const a of await db.byIndex('attempts', 'setId', id)) await db.delete('attempts', a.id);
  for (const b of await db.byIndex('bookmarks', 'setId', id)) await db.delete('bookmarks', b.key);
  for (const c of await db.all('chats')) if (c.setId === id) await db.delete('chats', c.key);
  for (const m of await db.all('mistakes')) if (m.setId === id) await db.delete('mistakes', m.key);
  await db.delete('sets', id);
}

// ---------- Bookmarks ----------
const bmKey = (setId, qn) => `${setId}|${qn}`;

export async function bookmarkSet(setId) {
  const rows = await db.byIndex('bookmarks', 'setId', setId);
  return new Set(rows.map((r) => r.q_no));
}

export async function toggleBookmark(setId, qn) {
  const key = bmKey(setId, qn);
  const has = await db.get('bookmarks', key);
  if (has) { await db.delete('bookmarks', key); return false; }
  await db.put('bookmarks', { key, setId, q_no: qn, createdAt: Date.now() });
  return true;
}

// ---------- Attempts ----------
export async function attemptsForSet(setId) {
  const rows = await db.byIndex('attempts', 'setId', setId);
  return rows.sort((a, b) => b.startedAt - a.startedAt);
}

export async function allAttempts() {
  const rows = await db.all('attempts');
  return rows.sort((a, b) => b.startedAt - a.startedAt);
}

export async function inProgressAttempts() {
  return (await allAttempts()).filter((a) => a.state === 'in_progress');
}

export function createAttempt(set, { qNos, durationMin, pos, neg, lang, mode = 'full', parentId = null, title }) {
  const responses = {};
  for (const qn of qNos) responses[qn] = { sel: null, visited: false, marked: false, time: 0 };
  return {
    id: uid('att'), setId: set.id, title: title || set.meta.title, mode, parentId, qNos,
    config: { durationMin, pos, neg }, lang, state: 'in_progress',
    startedAt: Date.now(), submittedAt: null, remainingMs: durationMin * 60000, elapsedMs: 0,
    current: 0, responses, result: null,
  };
}

// ---------- Chats ----------
export const chatKey = (setId, qn) => `${setId}|${qn}`;
