// Per-attempt random option order.
// The stored answer (`responses[q].sel`) and `correct_option` always keep the ORIGINAL option id,
// so scoring, stats and mistakes are unaffected. Only what the student sees is relabelled A, B, C…
import { shuffle } from './practice.js';

const LETTERS = ['A', 'B', 'C', 'D', 'E'];

// Options that point at other options ("A और B दोनों", "उपर्युक्त सभी", "None of these") lose
// their meaning when the order changes, so such questions keep the printed order.
const REF_RE = [
  /(उपर्युक्त|उपरोक्त|ऊपर\s*दिए|इनमें\s*से|इनमे\s*से|सभी\s*विकल्प|\babove\b|\bnone of (these|them|the)\b|\ball of (these|them|the)\b)/i,
  /(^|[\s(])\(?[a-eA-E]\)?\s*(,|और|तथा|एवं|व|&|and|or|या)\s*\(?[a-eA-E]\)?(?=[\s),।.]|$)/, // "A और B", "(a), (b)"
  /^\s*(कोई\s*(भी\s*)?नहीं|ये\s*सभी|सभी|सब|none|all|both)\s*[।.]?\s*$/i, // the whole option is "none" / "all"
];

export function canShuffle(q) {
  const opts = q?.options || [];
  if (opts.length < 2) return false;
  return !opts.some((o) => [o.text_hi, o.text_en].some((t) => t && REF_RE.some((re) => re.test(String(t).replace(/<[^>]+>/g, ' ')))));
}

/** { [q_no]: [originalId, …] } for the questions that may be shuffled. */
export function makeOptOrder(set, qNos) {
  const byNo = new Map(set.questions.map((q) => [q.q_no, q]));
  const out = {};
  for (const qn of qNos) {
    const q = byNo.get(qn);
    if (!q || !canShuffle(q)) continue;
    const ids = q.options.map((o) => o.id);
    let order = shuffle(ids.slice());
    // Make sure the order really changes (a 4-option shuffle keeps the same order 1 time in 24).
    for (let t = 0; t < 4 && order.every((id, i) => id === ids[i]); t++) order = shuffle(ids.slice());
    out[qn] = order;
  }
  return out;
}

/** Options in display order (falls back to the stored order; tolerates later edits of the set). */
export function orderedOptions(q, order) {
  if (!order || !order.length) return q.options;
  const byId = new Map(q.options.map((o) => [o.id, o]));
  const list = order.map((id) => byId.get(id)).filter(Boolean);
  for (const o of q.options) if (!list.includes(o)) list.push(o);
  return list;
}

/** Maps between original ids and displayed letters. */
export function letterMaps(q, order) {
  const list = orderedOptions(q, order);
  const toDisp = {}, toOrig = {};
  list.forEach((o, i) => { const L = LETTERS[i] || o.id; toDisp[o.id] = L; toOrig[L] = o.id; });
  return { list, toDisp, toOrig };
}

// "उत्तर: (c)", "Ans. b", "विकल्प (b) सही है", "option C" → the displayed letter.
function relabelText(text, toDisp) {
  if (!text) return text;
  const map = (m, pre, open, L, close) => {
    const d = toDisp[L.toUpperCase()];
    if (!d) return m;
    return `${pre}${open}${L === L.toLowerCase() ? d.toLowerCase() : d}${close}`;
  };
  return text.replace(/((?:सही\s*)?उत्तर\s*[:：\-–]?\s*|\bAns(?:wer)?\.?\s*[:：\-–]?\s*|विकल्प\s*|\b[Oo]ption\s*)(\(?)([a-eA-E])(\)?)(?![A-Za-z])/g, map);
}

/**
 * A copy of the question exactly as the student saw it: options relabelled A, B, C… in the
 * shuffled order, correct_option and the option letter inside the solution translated.
 * Returns the question unchanged when there is no stored order.
 */
export function displayQuestion(q, order) {
  if (!q || !order || !order.length) return q;
  const { list, toDisp } = letterMaps(q, order);
  return {
    ...q,
    options: list.map((o) => ({ ...o, id: toDisp[o.id] })),
    correct_option: q.correct_option ? toDisp[q.correct_option] || q.correct_option : q.correct_option,
    solution_hi: relabelText(q.solution_hi, toDisp),
    solution_en: relabelText(q.solution_en, toDisp),
  };
}

/** Original option id → displayed letter (for "आपका उत्तर" style labels). */
export function dispLetter(q, order, id) {
  if (!id || !order || !order.length) return id;
  return letterMaps(q, order).toDisp[id] || id;
}
