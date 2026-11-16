// Converts a compact, human-editable paper file (papers/*.txt) into the App Standard Schema JSON.
// Usage: node tools/paper2json.mjs papers/supertet_model_08.txt dist/papers/
//
// Format (one item per line):
//   @id <exam_id>           @title <title>          @duration <min>      @key <answer letters, spaces allowed>
//   @pos <marks per correct> @neg <marks per wrong>  @instr <Hindi instructions>
//   # <SECTION>             starts a section
//   P <passage_id> [title]  starts a passage; following "> text" lines are its text
//   <q_no> <topic> [@passage_id]   starts a question
//   ? <Hindi stem line>     ! <English stem line>   (several lines allowed; HTML and $KaTeX$ allowed)
//   - <option>              4 or 5 per question; "हिंदी / English" is split into both languages
//   -* <image> | <option>   option with a picture (path relative to the paper file); text after | is optional
//   * <image>               picture for the question (png/jpg/webp, embedded as base64)
//   = <solution (Hindi)>    ~ <key concept>
//   =* <image>              picture inside the solution (diagram that explains the answer)
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { basename, join, dirname, resolve, extname } from 'node:path';

const DEV = /[\u0900-\u097F]/;
const splitOpt = (t) => {
  const m = t.match(/^(.*\S)\s+\/\s+(.*)$/);
  if (m && DEV.test(m[1]) !== DEV.test(m[2])) return DEV.test(m[1]) ? [m[1], m[2]] : [m[2], m[1]];
  return DEV.test(t) ? [t, ''] : ['', t];
};

const MIME = { '.png': 'image/png', '.jpg': 'image/jpeg', '.jpeg': 'image/jpeg', '.webp': 'image/webp', '.gif': 'image/gif' };
const embed = (baseDir, rel) => {
  const f = resolve(baseDir, rel.trim());
  const mime = MIME[extname(f).toLowerCase()];
  if (!mime) throw new Error(`unsupported image type: ${rel}`);
  return `data:${mime};base64,${readFileSync(f).toString('base64')}`;
};

export function convert(src, file = 'paper', baseDir = '.') {
  const meta = { duration_minutes: 150, positive_marks: 1, negative_marks: 0 };
  const sections = [];
  const passages = {};
  const qs = [];
  let key = '';
  let section = null, q = null, p = null;
  const errors = [];
  src.split(/\r?\n/).forEach((raw, i) => {
    const line = raw.trim();
    const at = `${file}:${i + 1}`;
    if (!line || line.startsWith('//')) return;
    let m;
    if ((m = line.match(/^@(\w+)\s+(.*)$/))) {
      if (m[1] === 'key') key += m[2].replace(/[^A-Ea-e]/g, '').toUpperCase();
      else if (m[1] === 'id') meta.exam_id = m[2];
      else if (m[1] === 'title') meta.title = m[2];
      else if (m[1] === 'duration') meta.duration_minutes = Number(m[2]);
      else if (m[1] === 'pos') meta.positive_marks = Number(m[2]);
      else if (m[1] === 'neg') meta.negative_marks = Number(m[2]);
      else if (m[1] === 'instr') meta.instr = m[2];
      return;
    }
    if (line.startsWith('# ')) { section = line.slice(2).trim(); if (!sections.includes(section)) sections.push(section); q = null; p = null; return; }
    if ((m = line.match(/^P\s+(\S+)\s*(.*)$/))) { p = passages[m[1]] = { title: m[2], text_hi: '', text_en: '' }; q = null; return; }
    if (line.startsWith('> ')) {
      if (!p) { errors.push(`${at}: passage text outside passage`); return; }
      const t = line.slice(2);
      const k = DEV.test(t) ? 'text_hi' : 'text_en';
      p[k] += (p[k] ? '<br>' : '') + t;
      return;
    }
    if ((m = line.match(/^(\d{1,3})\s+(.+?)(?:\s+@(\S+))?$/))) {
      if (!section) errors.push(`${at}: question before any section`);
      p = null;
      q = { q_no: Number(m[1]), section, topic: m[2].trim(), passage_id: m[3] || null, hi: [], en: [], options: [], sol: [], kc: '', img: null };
      qs.push(q);
      return;
    }
    if (!q) { errors.push(`${at}: unexpected line "${line.slice(0, 40)}"`); return; }
    if (line.startsWith('? ')) q.hi.push(line.slice(2));
    else if (line.startsWith('! ')) q.en.push(line.slice(2));
    else if (line.startsWith('-* ')) {
      const [path, ...rest] = line.slice(3).split('|');
      try { q.options.push({ text: rest.join('|').trim(), img: embed(baseDir, path) }); } catch (e) { errors.push(`${at}: ${e.message}`); }
    } else if (line.startsWith('- ')) q.options.push({ text: line.slice(2).trim(), img: null });
    else if (line.startsWith('* ')) {
      try { q.img = embed(baseDir, line.slice(2)); } catch (e) { errors.push(`${at}: ${e.message}`); }
    }
    else if (line.startsWith('=* ')) {
      try { q.sol.push(`<img src="${embed(baseDir, line.slice(3))}" alt="हल का चित्र">`); } catch (e) { errors.push(`${at}: ${e.message}`); }
    } else if (line.startsWith('= ')) q.sol.push(line.slice(2));
    else if (line.startsWith('~ ')) q.kc = line.slice(2);
    else errors.push(`${at}: unknown line "${line.slice(0, 40)}"`);
  });

  if (!meta.exam_id || !meta.title) errors.push(`${file}: @id and @title are required`);
  qs.forEach((x, idx) => {
    if (x.q_no !== idx + 1) errors.push(`${file}: expected Q${idx + 1}, found Q${x.q_no}`);
    if (x.options.length < 4 || x.options.length > 5) errors.push(`${file}: Q${x.q_no} has ${x.options.length} options`);
    if (!x.hi.length && !x.en.length) errors.push(`${file}: Q${x.q_no} has no question text`);
    if (x.passage_id && !passages[x.passage_id]) errors.push(`${file}: Q${x.q_no} unknown passage ${x.passage_id}`);
  });
  qs.forEach((x, i) => { if (key[i] && 'ABCDE'.indexOf(key[i]) >= x.options.length) errors.push(`${file}: Q${x.q_no} key ${key[i]} but only ${x.options.length} options`); });
  if (key.length !== qs.length) errors.push(`${file}: answer key has ${key.length} letters for ${qs.length} questions`);
  if (errors.length) throw new Error(errors.join('\n'));

  const questions = qs.map((x, i) => ({
    q_no: x.q_no,
    section: x.section,
    topic: x.topic,
    syllabus_tag: `${x.section} - ${x.topic}`,
    difficulty: 'Medium',
    passage_id: x.passage_id,
    question_hi: x.hi.join('<br>'),
    question_en: x.en.join('<br>'),
    question_media: { image_base64: x.img, table_html: null },
    options: x.options.map((o, k) => { const [hi, en] = splitOpt(o.text); return { id: 'ABCDE'[k], text_hi: hi, text_en: en, image_base64: o.img }; }),
    correct_option: key[i],
    solution_hi: x.sol.join('<br>'),
    solution_en: '',
    key_concept: x.kc,
  }));
  return {
    exam_meta: {
      exam_id: meta.exam_id, title: meta.title, duration_minutes: meta.duration_minutes,
      max_marks: questions.length * meta.positive_marks, positive_marks: meta.positive_marks, negative_marks: meta.negative_marks,
      instructions_hi: meta.instr || 'सभी प्रश्नों के अंक समान हैं। उत्तर कुंजी मूल प्रश्न-पत्र (चन्द्रा इंस्टीट्यूट) के अनुसार है।',
      instructions_en: 'All questions carry equal marks. Answer key as printed in the original paper.',
    },
    sections, passages, questions,
  };
}

if (process.argv[2]) {
  const [, , input, outDir = 'dist/papers/'] = process.argv;
  const data = convert(readFileSync(input, 'utf8'), basename(input), dirname(input));
  mkdirSync(outDir, { recursive: true });
  const out = join(outDir, `${data.exam_meta.exam_id}.json`);
  writeFileSync(out, `${JSON.stringify(data, null, 1)}\n`);
  const bySec = data.sections.map((s) => `${s}:${data.questions.filter((q) => q.section === s).length}`).join(' ');
  console.log(`${out}: ${data.questions.length} questions, ${Object.keys(data.passages).length} passages | ${bySec}`);
}
