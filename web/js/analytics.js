// Evaluation + weakness engine.
import { db } from './db.js';
import { round2, pct } from './util.js';

export const WEAK_ACC = 50;
export const STRONG_ACC = 75;

/**
 * Evaluate an attempt. Topic accuracy = correct / total questions of that topic (a skipped
 * question counts as "not mastered"). A topic is Weak if accuracy < 50% OR its average time per
 * question is more than double the overall average; Moderate if 50–75%; Strong if > 75%.
 */
export function evaluate(set, attempt) {
  const pos = Number(attempt.config.pos);
  const neg = Number(attempt.config.neg);
  const qmap = new Map(set.questions.map((q) => [q.q_no, q]));
  const items = [];
  for (const qn of attempt.qNos) {
    const q = qmap.get(qn);
    if (!q) continue;
    const r = attempt.responses[qn] || {};
    const sel = r.sel || null;
    const status = !sel ? 'skipped' : sel === q.correct_option ? 'correct' : 'wrong';
    items.push({ q_no: qn, section: q.section, topic: q.topic, sel, correct: q.correct_option, status, time: r.time || 0, marked: !!r.marked, visited: !!r.visited });
  }
  const sum = (arr, f) => arr.reduce((n, x) => n + f(x), 0);
  const correct = items.filter((i) => i.status === 'correct').length;
  const wrong = items.filter((i) => i.status === 'wrong').length;
  const skipped = items.length - correct - wrong;
  const attempted = correct + wrong;
  const totalTime = sum(items, (i) => i.time);
  const timed = items.filter((i) => i.time > 0);
  const avgTime = timed.length ? sum(timed, (i) => i.time) / timed.length : 0;

  // ---- Sections
  const sectionOrder = set.sections.filter((s) => items.some((i) => i.section === s));
  for (const i of items) if (!sectionOrder.includes(i.section)) sectionOrder.push(i.section);
  const sections = sectionOrder.map((name) => {
    const s = items.filter((i) => i.section === name);
    const c = s.filter((i) => i.status === 'correct').length;
    const w = s.filter((i) => i.status === 'wrong').length;
    return {
      name, total: s.length, correct: c, wrong: w, skipped: s.length - c - w, attempted: c + w,
      time: sum(s, (i) => i.time), score: round2(c * pos - w * neg), max: round2(s.length * pos),
      accuracy: pct(c, c + w), scorePct: pct(c, s.length),
    };
  });
  const weakestSection = sections.length > 1 ? sections.slice().sort((a, b) => a.scorePct - b.scorePct || b.wrong - a.wrong)[0].name : null;

  // ---- Topics
  const tmap = new Map();
  for (const i of items) {
    const key = `${i.section}||${i.topic}`;
    if (!tmap.has(key)) tmap.set(key, { section: i.section, topic: i.topic, total: 0, correct: 0, wrong: 0, skipped: 0, time: 0, q: [] });
    const t = tmap.get(key);
    t.total++; t[i.status === 'correct' ? 'correct' : i.status === 'wrong' ? 'wrong' : 'skipped']++; t.time += i.time; t.q.push(i.q_no);
  }
  const topics = [...tmap.values()].map((t) => {
    const accuracy = pct(t.correct, t.total);
    const avg = t.total ? t.time / t.total : 0;
    const slow = avgTime > 0 && avg > 2 * avgTime;
    const level = accuracy < WEAK_ACC || slow ? 'weak' : accuracy <= STRONG_ACC ? 'moderate' : 'strong';
    return { ...t, accuracy, avgTime: avg, slow, level };
  }).sort((a, b) => a.accuracy - b.accuracy || b.avgTime - a.avgTime);

  // ---- Time traps: wrong answers that took more than double the average time (min 20 s)
  const trapLimit = Math.max(avgTime * 2, 20000);
  const timeTraps = items.filter((i) => i.status === 'wrong' && i.time >= trapLimit).sort((a, b) => b.time - a.time);
  const slowSkips = items.filter((i) => i.status === 'skipped' && i.time >= trapLimit).sort((a, b) => b.time - a.time);

  const score = round2(correct * pos - wrong * neg);
  const maxScore = round2(items.length * pos);
  return {
    total: items.length, correct, wrong, skipped, attempted, score, maxScore,
    accuracy: pct(correct, attempted), scorePct: pct(Math.max(0, score), maxScore),
    totalTime, avgTime, sections, weakestSection, topics, timeTraps, slowSkips, trapLimit, items,
    evaluatedAt: Date.now(),
  };
}

// Question numbers for a "weakness practice" mini test.
export function pickQuestions(result, kind) {
  if (kind === 'wrong') return result.items.filter((i) => i.status === 'wrong').map((i) => i.q_no);
  if (kind === 'skipped') return result.items.filter((i) => i.status === 'skipped').map((i) => i.q_no);
  if (kind === 'wrong_skipped') return result.items.filter((i) => i.status !== 'correct').map((i) => i.q_no);
  if (kind === 'weak') {
    const weak = new Set(result.topics.filter((t) => t.level === 'weak').flatMap((t) => t.q));
    return result.items.filter((i) => weak.has(i.q_no)).map((i) => i.q_no);
  }
  if (kind === 'traps') return result.timeTraps.map((i) => i.q_no);
  return result.items.map((i) => i.q_no);
}

// Lifetime topic analytics across all attempts (stored in IndexedDB "topicStats").
export async function recordTopicStats(result) {
  const rows = [];
  for (const t of result.topics) {
    const key = `${t.section}||${t.topic}`;
    const prev = (await db.get('topicStats', key)) || { key, section: t.section, topic: t.topic, total: 0, correct: 0, wrong: 0, skipped: 0, time: 0, attempts: 0 };
    prev.total += t.total; prev.correct += t.correct; prev.wrong += t.wrong; prev.skipped += t.skipped; prev.time += t.time;
    prev.attempts += 1; prev.lastAt = Date.now();
    rows.push(prev);
  }
  if (rows.length) await db.putMany('topicStats', rows);
}

export function levelOf(accuracy) {
  return accuracy < WEAK_ACC ? 'weak' : accuracy <= STRONG_ACC ? 'moderate' : 'strong';
}

export const LEVEL_UI = {
  weak: { label: 'कमजोर / Weak', cls: 'bg-rose-100 text-rose-700 dark:bg-rose-900/40 dark:text-rose-300', bar: 'bg-rose-500' },
  moderate: { label: 'मध्यम / Moderate', cls: 'bg-amber-100 text-amber-800 dark:bg-amber-900/40 dark:text-amber-300', bar: 'bg-amber-500' },
  strong: { label: 'मजबूत / Strong', cls: 'bg-emerald-100 text-emerald-700 dark:bg-emerald-900/40 dark:text-emerald-300', bar: 'bg-emerald-500' },
};
