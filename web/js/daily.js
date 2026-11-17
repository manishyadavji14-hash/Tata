// "आज का अभ्यास": every day, fresh practice sets built from the mistakes of earlier days.
// Daily sets are temporary tests (kind 'practice', daily = 'YYYY-MM-DD'). They expire at midnight:
// an untouched expired set is deleted, an attempted one stays only so its result can still be opened.
import { db, getSettings } from './db.js';
import { buildPracticeSet, shuffle } from './practice.js';
import { bankItems, groupBank, statsMap, dayKey, startOfDay, MASTERED_AT } from './qstats.js';

export const DAILY_SIZES = [10, 20, 30, 50, 80, 100];

const fmtDay = (day) => new Date(`${day}T00:00:00`).toLocaleDateString('hi-IN', { day: 'numeric', month: 'short' });

export async function dailySetsOf(day = dayKey()) {
  return (await db.all('sets')).filter((s) => s.kind === 'practice' && s.daily === day).sort((a, b) => (a.dailyNo || 0) - (b.dailyNo || 0));
}

async function attemptsOf(setId) { return db.byIndex('attempts', 'setId', setId); }

/** Delete expired untouched daily sets; create today's sets if they do not exist yet. → today's sets */
export async function ensureDaily({ force = false } = {}) {
  const today = dayKey();
  const all = (await db.all('sets')).filter((s) => s.kind === 'practice' && s.daily);
  for (const s of all) {
    if (s.daily === today && !force) continue;
    if (s.daily !== today && !s.expired) {
      const atts = await attemptsOf(s.id);
      if (!atts.length) { await db.delete('sets', s.id); continue; }
      await db.put('sets', { ...s, expired: true });
    }
  }
  const cfg = await getSettings();
  let todays = await dailySetsOf(today);
  if (force) {
    // keep the ones already started, rebuild the rest with the new settings
    const keep = [];
    for (const s of todays) { if ((await attemptsOf(s.id)).length) keep.push(s); else await db.delete('sets', s.id); }
    todays = keep;
  }
  if (!cfg.dailyOn) return todays;
  const want = Math.max(1, Math.min(10, Number(cfg.dailyCount) || 1));
  if (todays.length >= want && !force) return todays;
  const made = await generate({ size: Math.max(1, Math.min(300, Number(cfg.dailySize) || 20)), count: Math.max(0, want - todays.length), startNo: todays.length + 1, exclude: new Set(todays.flatMap((s) => s.questions.map((q) => `${q.origin?.setId}|${q.origin?.q_no}`))), subjects: cfg.dailySubjects || [], today });
  return [...todays, ...made];
}

/**
 * Picking order for `size × count` questions:
 *  1. questions answered wrong on earlier days and not yet cleared (newest mistake first, more mistakes first)
 *  2. other not-yet-mastered questions from the same concepts, then the same topics as those mistakes
 *     (topics with more mistakes get more questions)
 *  3. questions of the weakest topics overall (low accuracy), then questions never attempted
 * Mistakes made today are used from tomorrow.
 */
async function generate({ size, count, startNo, exclude, subjects, today }) {
  if (!count) return [];
  const [itemsAll, stats, bank] = await Promise.all([bankItems(), statsMap(), db.all('mistakes')]);
  const items = itemsAll.filter((it) => it.q.correct_option && (!subjects.length || subjects.includes(it.sk)) && !exclude.has(it.key));
  if (!items.length) return [];
  const byKey = new Map(items.map((it) => [it.key, it]));
  const dayStart = startOfDay();
  const total = size * count;
  const picked = new Map(); // key -> { it, why }
  const take = (it, why) => { if (picked.size < total && !picked.has(it.key)) picked.set(it.key, { it, why }); };

  // 1. earlier mistakes
  const mistakes = bank
    .map((m) => ({ m, it: byKey.get(`${m.setId}|${m.q_no}`), s: stats.get(`${m.setId}|${m.q_no}`) }))
    .filter((x) => x.it && (x.m.lastWrongAt || 0) < dayStart);
  const ageDays = (ts) => Math.max(0, (dayStart - startOfDay(ts || 0)) / 86400000);
  mistakes.sort((a, b) => (ageDays(a.m.lastWrongAt) - ageDays(b.m.lastWrongAt)) || (b.m.wrong - a.m.wrong) || (a.m.correct - b.m.correct));
  const mistakeQuota = Math.ceil(total * 0.6);
  for (const x of mistakes.slice(0, mistakeQuota)) take(x.it, 'mistake');

  // weights of the concepts / topics behind those mistakes
  const wConcept = new Map(), wTopic = new Map();
  for (const x of mistakes) {
    const w = 1 / (1 + ageDays(x.m.lastWrongAt)) + 0.3 * (x.m.wrong || 1);
    if (x.it.concept) wConcept.set(`${x.it.sk}|${x.it.concept}`, (wConcept.get(`${x.it.sk}|${x.it.concept}`) || 0) + w);
    wTopic.set(`${x.it.sk}|${x.it.topic}`, (wTopic.get(`${x.it.sk}|${x.it.topic}`) || 0) + w);
  }
  const notMastered = (it) => (stats.get(it.key)?.correct || 0) < MASTERED_AT;
  const freshness = (it) => { const s = stats.get(it.key); return s ? (s.correct || 0) - (s.wrong || 0) : -0.5; }; // unseen & weak first
  const fillFrom = (weights, keyOf, why) => {
    const groups = [...weights.entries()].sort((a, b) => b[1] - a[1]);
    const sum = groups.reduce((n, [, w]) => n + w, 0) || 1;
    const room = total - picked.size;
    for (const [g, w] of groups) {
      const n = Math.max(1, Math.round((room * w) / sum));
      const pool = shuffle(items.filter((it) => keyOf(it) === g && !picked.has(it.key) && notMastered(it))).sort((a, b) => freshness(a) - freshness(b));
      for (const it of pool.slice(0, n)) take(it, why);
      if (picked.size >= total) return;
    }
  };
  // 2. related questions
  if (picked.size < total) fillFrom(wConcept, (it) => `${it.sk}|${it.concept}`, 'concept');
  if (picked.size < total) fillFrom(wTopic, (it) => `${it.sk}|${it.topic}`, 'topic');
  if (picked.size < total) for (const x of mistakes) take(x.it, 'mistake'); // the rest of the mistakes

  // 3. weak topics overall, then never-attempted questions
  if (picked.size < total) {
    const tAcc = new Map();
    for (const it of items) {
      const s = stats.get(it.key);
      if (!s) continue;
      const k = `${it.sk}|${it.topic}`;
      const e = tAcc.get(k) || { c: 0, w: 0 };
      e.c += s.correct || 0; e.w += s.wrong || 0;
      tAcc.set(k, e);
    }
    const weak = new Map([...tAcc.entries()].filter(([, e]) => e.w && e.c / (e.c + e.w) < 0.75).map(([k, e]) => [k, e.w / (e.c + e.w)]));
    fillFrom(weak, (it) => `${it.sk}|${it.topic}`, 'weak');
  }
  if (picked.size < total) for (const it of shuffle(items.filter((x) => !stats.get(x.key)))) take(it, 'new');
  if (picked.size < total) for (const it of shuffle(items.filter(notMastered))) take(it, 'revise');
  if (!picked.size) return [];

  // split into `count` sets, each with a mix of every kind; inside a set, group by subject
  const list = shuffle([...picked.values()]);
  const order = ['mistake', 'concept', 'topic', 'weak', 'new', 'revise'];
  list.sort((a, b) => order.indexOf(a.why) - order.indexOf(b.why));
  const labels = new Map(groupBank(items).map((g) => [g.key, g.label])); // HINDI / हिन्दी → one section
  const buckets = Array.from({ length: count }, () => []);
  list.forEach((x, i) => buckets[i % count].push(x));
  const made = [];
  for (let i = 0; i < count; i++) {
    const b = buckets[i];
    if (!b.length) continue;
    const subjOrder = [...new Set(b.map((x) => x.it.sk))];
    b.sort((x, y) => subjOrder.indexOf(x.it.sk) - subjOrder.indexOf(y.it.sk));
    const why = Object.fromEntries(order.map((k) => [k, b.filter((x) => x.why === k).length]));
    const no = startNo + i;
    const title = `आज का अभ्यास • ${fmtDay(today)}${count + startNo - 1 > 1 ? ` • सेट ${no}` : ''} (${b.length} प्रश्न)`;
    const hi = [
      `यह सेट ${fmtDay(today)} के लिए है और रात 12 बजे एक्सपायर हो जाएगा। कल पिछले दिनों की गलतियों के आधार पर नया सेट बनेगा।`,
      why.mistake ? `• ${why.mistake} प्रश्न जो पहले गलत हुए थे` : '',
      why.concept + why.topic ? `• ${why.concept + why.topic} प्रश्न उन्हीं कॉन्सेप्ट/टॉपिक से जिनमें गलती हुई` : '',
      why.weak ? `• ${why.weak} प्रश्न आपके कमजोर टॉपिक से` : '',
      why.new + why.revise ? `• ${why.new + why.revise} नए / दोहराने वाले प्रश्न` : '',
    ].filter(Boolean).join('<br>');
    made.push(await buildPracticeSet(b.map((x) => ({ set: x.it.set, q: { ...x.it.q, section: labels.get(x.it.sk) || x.it.q.section } })), {
      title, minutesPerQ: 1.2, extra: { daily: today, dailyNo: no, dailyWhy: why },
      instructions: { hi, en: 'Daily practice built from your earlier mistakes. Expires at midnight.' },
    }));
  }
  return made;
}

export const isExpiredDaily = (set) => !!set?.daily && set.daily !== dayKey();
