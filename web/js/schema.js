// App Standard Schema: validation + normalisation of practice sets coming from JSON files or AI output.
import { slug } from './util.js';

export const DEFAULT_SECTIONS = ['HINDI', 'ENGLISH', 'SANSKRIT', 'SCIENCE', 'MATHS', 'पर्यावरण एवं सामाजिक अध्ययन', 'बाल विकास (CDP)', 'सामान्य ज्ञान एवं समसायिक घटनाएं', 'REASONING', 'सूचना तकनीक'];
const OPTION_IDS = ['A', 'B', 'C', 'D', 'E'];
const DIFFS = ['Easy', 'Medium', 'Hard'];

const DEVANAGARI = /[\u0900-\u097F]/;
const str = (v) => (v == null ? '' : typeof v === 'string' ? v : typeof v === 'number' ? String(v) : '');
const num = (v, d) => (Number.isFinite(Number(v)) && v !== '' && v !== null ? Number(v) : d);

// "A" | "a" | "(B)" | "1" | 2 | "option c" -> "A".."E" or null
export function normalizeOptionId(v, count = 5) {
  if (v == null || v === '') return null;
  if (typeof v === 'number') return v >= 1 && v <= count ? OPTION_IDS[v - 1] : v === 0 ? 'A' : null;
  const s = String(v).trim().toUpperCase().replace(/^OPTION\s*/, '').replace(/[()[\].\s]/g, '');
  if (/^[A-E]$/.test(s)) return s;
  if (/^[1-5]$/.test(s)) return OPTION_IDS[Number(s) - 1];
  const hi = { 'अ': 'A', 'ब': 'B', 'स': 'C', 'द': 'D', 'य': 'E', 'क': 'A', 'ख': 'B', 'ग': 'C', 'घ': 'D', 'ङ': 'E' }[s];
  return hi || null;
}

function splitLang(text) {
  const t = str(text);
  if (!t) return { hi: '', en: '' };
  return DEVANAGARI.test(t) ? { hi: t, en: '' } : { hi: '', en: t };
}

function normOptions(raw, warnings, qn) {
  let list = [];
  if (Array.isArray(raw)) list = raw;
  else if (raw && typeof raw === 'object') list = Object.entries(raw).map(([id, v]) => (typeof v === 'object' ? { id, ...v } : { id, text: v }));
  const out = list.slice(0, 5).map((o, i) => {
    if (typeof o === 'string' || typeof o === 'number') {
      const l = splitLang(o);
      return { id: OPTION_IDS[i], text_hi: l.hi, text_en: l.en, image_base64: null };
    }
    const o2 = o || {};
    let hi = str(o2.text_hi), en = str(o2.text_en);
    if (!hi && !en) { const l = splitLang(o2.text ?? o2.value ?? o2.label); hi = l.hi; en = l.en; }
    return {
      id: normalizeOptionId(o2.id, 5) || OPTION_IDS[i],
      text_hi: hi,
      text_en: en,
      image_base64: str(o2.image_base64) || null,
    };
  });
  const seen = new Set();
  out.forEach((o, i) => { if (seen.has(o.id)) o.id = OPTION_IDS[i]; seen.add(o.id); });
  if (out.length < 2) warnings.push(`Q${qn}: ${out.length} विकल्प मिले (कम से कम 2 चाहिए)`);
  return out;
}

export function normalizeQuestion(q, i, warnings) {
  const raw = q || {};
  const qn = num(raw.q_no ?? raw.qno ?? raw.number, i + 1);
  let qhi = str(raw.question_hi), qen = str(raw.question_en);
  if (!qhi && !qen) { const l = splitLang(raw.question ?? raw.text); qhi = l.hi; qen = l.en; }
  const options = normOptions(raw.options, warnings, qn);
  const media = raw.question_media || {};
  let correct = normalizeOptionId(raw.correct_option ?? raw.answer ?? raw.correct, options.length);
  if (correct && !options.some((o) => o.id === correct)) correct = null;
  if (!correct) warnings.push(`Q${qn}: सही उत्तर (correct_option) नहीं मिला`);
  let diff = str(raw.difficulty);
  diff = DIFFS.find((d) => d.toLowerCase() === diff.toLowerCase()) || 'Medium';
  return {
    q_no: qn,
    section: str(raw.section).trim() || 'GENERAL',
    topic: str(raw.topic).trim() || 'अवर्गीकृत / Uncategorised',
    syllabus_tag: str(raw.syllabus_tag).trim(),
    difficulty: diff,
    passage_id: str(raw.passage_id) || null,
    question_hi: qhi,
    question_en: qen,
    question_media: {
      image_base64: str(media.image_base64 ?? raw.image_base64) || null,
      table_html: str(media.table_html) || null,
    },
    options,
    correct_option: correct,
    solution_hi: str(raw.solution_hi),
    solution_en: str(raw.solution_en) || (!raw.solution_hi ? str(raw.solution) : ''),
    key_concept: str(raw.key_concept),
    answer_source: str(raw.answer_source) || (correct ? 'key' : ''),
  };
}

// Returns { set, warnings }. Throws on unusable input.
export function normalizeSet(input, { source = 'json' } = {}) {
  let raw = input;
  if (typeof raw === 'string') raw = JSON.parse(raw);
  if (Array.isArray(raw)) raw = { questions: raw };
  if (!raw || typeof raw !== 'object') throw new Error('JSON object नहीं है / Not a JSON object');
  if (raw.practice_set) raw = raw.practice_set;
  if (!Array.isArray(raw.questions) || raw.questions.length === 0) throw new Error('"questions" array खाली है या मौजूद नहीं है / "questions" array missing or empty');

  const warnings = [];
  const m = raw.exam_meta || {};
  const title = str(m.title).trim() || 'Imported Practice Set';
  const questions = raw.questions.map((q, i) => normalizeQuestion(q, i, warnings))
    .filter((q) => {
      const ok = (q.question_hi || q.question_en || q.question_media.image_base64 || q.question_media.table_html) && q.options.length >= 2;
      if (!ok) warnings.push(`Q${q.q_no}: प्रश्न टेक्स्ट/विकल्प न होने से हटाया गया`);
      return ok;
    });
  if (!questions.length) throw new Error('कोई वैध प्रश्न नहीं मिला / No valid questions');

  // Unique, ordered question numbers
  questions.sort((a, b) => a.q_no - b.q_no);
  const used = new Set();
  for (const q of questions) {
    if (used.has(q.q_no)) {
      const nq = Math.max(...used) + 1;
      warnings.push(`Q${q.q_no}: डुप्लिकेट नंबर, Q${nq} किया गया`);
      q.q_no = nq;
    }
    used.add(q.q_no);
  }

  const passages = {};
  for (const [id, p] of Object.entries(raw.passages || {})) {
    if (typeof p === 'string') {
      const l = splitLang(p);
      passages[id] = { text_hi: l.hi, text_en: l.en, title: '' };
    } else {
      passages[id] = { text_hi: str(p?.text_hi), text_en: str(p?.text_en || p?.text), title: str(p?.title) };
    }
  }
  for (const q of questions) {
    if (q.passage_id && !passages[q.passage_id]) { warnings.push(`Q${q.q_no}: passage "${q.passage_id}" नहीं मिला`); q.passage_id = null; }
  }

  let sections = Array.isArray(raw.sections) ? raw.sections.map((s) => str(s).trim()).filter(Boolean) : [];
  for (const q of questions) if (!sections.includes(q.section)) sections.push(q.section);
  sections = sections.filter((s) => questions.some((q) => q.section === s));

  const pos = num(m.positive_marks, 1);
  const meta = {
    exam_id: str(m.exam_id).trim() || slug(title),
    title,
    duration_minutes: Math.max(1, num(m.duration_minutes, Math.max(10, Math.round(questions.length * 1)))),
    max_marks: num(m.max_marks, questions.length * pos),
    positive_marks: pos,
    negative_marks: Math.abs(num(m.negative_marks, 0)),
    instructions_hi: str(m.instructions_hi),
    instructions_en: str(m.instructions_en),
  };
  return { set: { meta, sections, passages, questions, source }, warnings };
}

// Convert a stored set back to the App Standard Schema (for export / sharing)
export function toStandardJson(set) {
  return {
    exam_meta: set.meta,
    sections: set.sections,
    passages: set.passages,
    questions: set.questions.map(({ answer_source, ...q }) => q),
  };
}

export const SCHEMA_EXAMPLE = {
  exam_meta: {
    exam_id: 'supertet_model_08', title: 'SUPER TET प्राथमिक MODEL PAPER-08', duration_minutes: 150, max_marks: 150,
    positive_marks: 1.0, negative_marks: 0.0, instructions_hi: 'सभी प्रश्नों के अंक समान हैं।', instructions_en: 'All questions carry equal marks.',
  },
  sections: DEFAULT_SECTIONS,
  passages: { p_env_1: { text_en: 'The environment is everything around us...', text_hi: '' } },
  questions: [{
    q_no: 1, section: 'HINDI', topic: 'कारक एवं विभक्ति', syllabus_tag: 'हिंदी व्याकरण - कारक', difficulty: 'Easy', passage_id: null,
    question_hi: '"चारपाई पर राम बैठा है।" इस वाक्य में कारक बताइए-', question_en: '',
    question_media: { image_base64: null, table_html: null },
    options: [
      { id: 'A', text_hi: 'करण', text_en: '', image_base64: null },
      { id: 'B', text_hi: 'सम्प्रदान', text_en: '', image_base64: null },
      { id: 'C', text_hi: 'संबंध', text_en: '', image_base64: null },
      { id: 'D', text_hi: 'अधिकरण', text_en: '', image_base64: null },
    ],
    correct_option: 'D',
    solution_hi: "वाक्य में 'चारपाई पर' क्रिया का आधार बता रहा है। 'में, पर' चिह्न अधिकरण कारक के होते हैं।",
    solution_en: '', key_concept: "अधिकरण कारक के चिह्न 'में' और 'पर' होते हैं।",
  }],
};
