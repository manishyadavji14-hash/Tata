// Concepts: subject → topic → concept cards for quick revision.
// Sources: (1) imported concept files (abhyas_concepts JSON), (2) AI-made concept sets (background job),
// (3) a virtual set built on the fly from the solutions / key concepts of every imported question paper.
import { db } from './db.js';
import { subjectKey } from './practice.js';
import { uid, slug, extractJson } from './util.js';
import { DIAGRAM_TYPES, cleanDiagram, diagramText } from './diagram-spec.js';

export const QSRC = '__questions__'; // id of the virtual "from question papers" source

// ---------- subject grouping ----------
const CONCEPT_ALIASES = [
  ['lifeskills', /जीवन कौशल|life\s*skill|अभिवृत्ति/i],
  ['teaching', /शिक्षण कौशल|teaching\s*skill|शिक्षण विधि/i],
  ['cdp', /बाल मनोविज्ञान|बाल विकास|\bcdp\b|मनोविज्ञान|psychology|pedagogy|शिक्षाशास्त्र/i],
  ['evs', /पर्यावरण|पारिस्थितिक|ecology|environment|\bevs\b|सामाजिक अध्ययन/i],
];
export function conceptSubjectKey(name) {
  const s = String(name || '').trim();
  for (const [k, re] of CONCEPT_ALIASES) if (re.test(s)) return k;
  return subjectKey(s);
}

const SUBJECT_ORDER = ['lifeskills', 'teaching', 'cdp', 'evs', 'hindi', 'sanskrit', 'english', 'science', 'maths', 'gk', 'reasoning', 'it'];
const orderOf = (k) => { const i = SUBJECT_ORDER.indexOf(k); return i < 0 ? 99 : i; };
const SUBJECT_ICON = { lifeskills: '🧭', teaching: '🧑‍🏫', cdp: '🧠', evs: '🌿', hindi: '📕', sanskrit: '🕉️', english: '🔤', science: '🔬', maths: '➗', gk: '🌏', reasoning: '🧩', it: '💻' };
export const subjectIcon = (k) => SUBJECT_ICON[k] || '📘';

// ---------- normalising (files, AI replies) ----------
const str = (v) => (v == null ? '' : String(v)).trim();

function itemsOf(c) {
  const items = [];
  const push = (type, v) => { const t = str(v); if (t) items.push({ type, text: t }); };
  if (Array.isArray(c.items)) {
    for (const it of c.items) {
      if (!it) continue;
      if (typeof it === 'string') { push('point', it); continue; }
      if (DIAGRAM_TYPES.includes(it.type)) { const d = cleanDiagram(it); if (d) items.push(d); continue; }
      if (it.type === 'table' && Array.isArray(it.rows)) { const rows = it.rows.filter(Array.isArray).map((r) => r.map(str)); if (rows.length) items.push({ type: 'table', rows }); continue; }
      const type = ['point', 'tip', 'trick', 'html'].includes(it.type) ? it.type : 'point';
      push(type, it.text);
    }
  }
  for (const p of [].concat(c.points || [], c.bullets || [])) push('point', typeof p === 'string' ? p : p?.text);
  if (c.text || c.explanation) push('point', c.text || c.explanation);
  if (c.example) push('point', `उदाहरण: ${str(c.example)}`);
  for (const t of [].concat(c.tips || [], c.tip || [], c.trap || [])) push('tip', t);
  for (const t of [].concat(c.tricks || [], c.trick || [], c.mnemonic || [], c.formula || [])) push('trick', t);
  for (const d of Array.isArray(c.diagrams) ? c.diagrams : c.diagram ? [c.diagram] : []) { const x = cleanDiagram(d); if (x) items.push(x); }
  const tables = Array.isArray(c.tables) ? c.tables : c.table ? [c.table] : [];
  for (const t of tables) {
    const rows = Array.isArray(t) ? t : Array.isArray(t?.rows) ? t.rows : null;
    if (rows && rows.length) items.push({ type: 'table', rows: rows.filter(Array.isArray).map((r) => r.map(str)) });
  }
  return items;
}

/** Any JSON that looks like concept data (a set, a bundle of sets, or an AI reply). */
export function isConceptData(d) {
  if (!d || typeof d !== 'object') return false;
  if (d.abhyas_concepts || d.abhyas_concept_bundle) return true;
  if (Array.isArray(d.concept_sets)) return true;
  return Array.isArray(d.subjects) && d.subjects.some((s) => s && Array.isArray(s.topics)) && !Array.isArray(d.questions);
}

/** → { set, warnings, count } */
export function normalizeConceptSet(d, { origin = 'import' } = {}) {
  const warnings = [];
  const set = {
    abhyas_concepts: 1,
    id: str(d.id) || `${slug(d.title || 'concepts').slice(0, 40) || 'concepts'}_${uid('c').slice(-6)}`,
    title: str(d.title) || 'कॉन्सेप्ट नोट्स',
    source: str(d.source),
    version: str(d.version),
    origin,
    subjects: [],
  };
  let n = 0;
  for (const s of d.subjects || []) {
    if (!s) continue;
    const subject = str(s.subject || s.name) || 'सामान्य';
    const topics = [];
    for (const t of s.topics || []) {
      if (!t) continue;
      const concepts = [];
      for (const c of t.concepts || []) {
        if (!c) continue;
        const title = str(c.title || c.name || c.concept);
        const items = itemsOf(c);
        if (!title && !items.length) continue;
        n++;
        concepts.push({ id: str(c.id) || `${set.id}-${String(n).padStart(4, '0')}`, title: title || items[0]?.text?.slice(0, 60) || 'कॉन्सेप्ट', items });
      }
      if (concepts.length) topics.push({ topic: str(t.topic || t.name) || 'सामान्य', concepts });
    }
    if (topics.length) set.subjects.push({ subject, topics });
    else warnings.push(`विषय "${subject}" में कोई कॉन्सेप्ट नहीं`);
  }
  if (!n) throw new Error('इस फ़ाइल में कोई कॉन्सेप्ट नहीं मिला');
  return { set, warnings, count: n };
}

/** A file may hold one concept set or a bundle of several. */
export function expandConcepts(d) {
  const list = Array.isArray(d.sets) && (d.abhyas_concept_bundle || d.sets.every(isConceptData)) ? d.sets : Array.isArray(d.concept_sets) ? d.concept_sets : [d];
  return list.map((x) => normalizeConceptSet(x, { origin: x.origin || 'import' }));
}

export const countConcepts = (set) => set.subjects.reduce((a, s) => a + s.topics.reduce((b, t) => b + t.concepts.length, 0), 0);

// ---------- storage ----------
export async function listConceptSets() {
  return (await db.all('concepts')).sort((a, b) => (b.createdAt || 0) - (a.createdAt || 0));
}
export async function saveConceptSet(set) {
  const old = await db.get('concepts', set.id);
  const row = { ...set, createdAt: old?.createdAt || Date.now(), updatedAt: Date.now() };
  await db.put('concepts', row);
  return { row, replaced: !!old };
}
/** Replace a stored set with a newer version; learned/star marks follow concepts with the same title. */
export async function replaceConceptSet(set) {
  const old = await db.get('concepts', set.id);
  if (old) {
    const byTitle = new Map();
    for (const s of set.subjects) for (const t of s.topics) for (const c of t.concepts) if (!byTitle.has(`${t.topic}|${c.title}`)) byTitle.set(`${t.topic}|${c.title}`, c.id);
    const moves = [];
    for (const s of old.subjects) for (const t of s.topics) for (const c of t.concepts) {
      const p = await db.get('conceptProgress', c.id);
      if (!p) continue;
      await db.delete('conceptProgress', c.id); // ids may be renumbered: clear first, then re-attach by title
      const to = byTitle.get(`${t.topic}|${c.title}`);
      if (to) moves.push({ ...p, key: to });
    }
    for (const m of moves) await db.put('conceptProgress', m);
  }
  return saveConceptSet(set);
}
export async function deleteConceptSet(id) {
  const set = await db.get('concepts', id);
  await db.delete('concepts', id);
  if (set) for (const s of set.subjects) for (const t of s.topics) for (const c of t.concepts) await db.delete('conceptProgress', c.id);
}

export async function progressMap() {
  return new Map((await db.all('conceptProgress')).map((r) => [r.key, r]));
}
export async function setProgress(key, patch) {
  const cur = (await db.get('conceptProgress', key)) || { key, learned: false, star: false, views: 0 };
  const row = { ...cur, ...patch, ts: Date.now() };
  await db.put('conceptProgress', row);
  return row;
}

// ---------- concepts from the question papers ----------
const plain = (s) => String(s || '').replace(/<img[^>]*>/gi, ' [चित्र] ').replace(/<[^>]+>/g, ' ').replace(/\s+/g, ' ').trim();

/** Virtual concept set: every question that has a solution or key concept becomes a card. */
export async function questionConceptSet(lang = 'both') {
  const sets = (await db.all('sets')).filter((s) => s.kind !== 'practice' && !s.isDemo);
  const bySub = new Map();
  const seen = new Set();
  for (const set of sets) {
    for (const q of set.questions || []) {
      const sol = (lang === 'en' ? q.solution_en || q.solution_hi : q.solution_hi || q.solution_en) || '';
      const kc = q.key_concept || '';
      if (!plain(sol) && !plain(kc)) continue;
      const stem = lang === 'en' ? q.question_en || q.question_hi : q.question_hi || q.question_en;
      const sig = `${plain(stem).slice(0, 80)}|${plain(kc).slice(0, 60)}`.toLowerCase();
      if (seen.has(sig)) continue; // same question in several papers → one card
      seen.add(sig);
      const key = conceptSubjectKey(q.section);
      if (!bySub.has(key)) bySub.set(key, { names: new Map(), topics: new Map() });
      const e = bySub.get(key);
      e.names.set(q.section, (e.names.get(q.section) || 0) + 1);
      const topic = String(q.topic || '').trim() || 'सामान्य';
      if (!e.topics.has(topic)) e.topics.set(topic, []);
      const ans = (q.options || []).find((o) => o.id === q.correct_option);
      const ansText = ans ? (lang === 'en' ? ans.text_en || ans.text_hi : ans.text_hi || ans.text_en) : '';
      const items = [];
      if (plain(kc)) items.push({ type: 'tip', text: kc });
      const t0 = plain(kc) || plain(stem);
      const titleIsStem = !plain(kc) && t0.length <= 110;
      if (!titleIsStem || q.question_media?.table_html || q.question_media?.image_base64) items.push({ type: 'q', text: titleIsStem ? '' : stem || '', table: q.question_media?.table_html || '', image: q.question_media?.image_base64 || '' });
      if (ans) items.push({ type: 'ans', text: `(${q.correct_option}) ${ansText || ''}`, image: ans.image_base64 || '' });
      if (plain(sol)) items.push({ type: 'html', text: sol });
      const t = plain(kc) || plain(stem);
      e.topics.get(topic).push({
        id: `q:${set.id}:${q.q_no}`,
        title: t.length > 110 ? `${t.slice(0, 107)}…` : t || `प्रश्न ${q.q_no}`,
        items,
        ref: { setId: set.id, q_no: q.q_no, setTitle: set.meta?.title || '' },
      });
    }
  }
  const subjects = [...bySub.entries()].map(([, e]) => ({
    subject: [...e.names.entries()].sort((a, b) => b[1] - a[1])[0][0],
    topics: [...e.topics.entries()].map(([topic, concepts]) => ({ topic, concepts })),
  }));
  return { id: QSRC, title: 'प्रश्न-पत्रों के हल से', source: `${sets.length} प्रश्न-पत्र`, origin: 'questions', subjects, virtual: true };
}

/**
 * Merge sets into one tree grouped by subject key:
 * [{ key, label, icon, count, topics: [{ name, concepts: [{ ...c, setId, setTitle }] }] }]
 */
export function buildTree(sets) {
  const map = new Map();
  for (const set of sets) {
    for (const s of set.subjects || []) {
      const key = conceptSubjectKey(s.subject);
      if (!map.has(key)) map.set(key, { key, labels: new Map(), topics: new Map(), count: 0, fromFile: false });
      const e = map.get(key);
      e.labels.set(s.subject, (e.labels.get(s.subject) || 0) + (set.virtual ? 1 : 1000)); // file subject names win
      if (!set.virtual) e.fromFile = true;
      for (const t of s.topics || []) {
        const tk = t.topic.trim();
        if (!e.topics.has(tk)) e.topics.set(tk, []);
        const arr = e.topics.get(tk);
        for (const c of t.concepts) { arr.push({ ...c, setId: set.id, setTitle: set.title }); e.count++; }
      }
    }
  }
  return [...map.values()]
    .map((e) => ({ key: e.key, label: [...e.labels.entries()].sort((a, b) => b[1] - a[1])[0][0], icon: subjectIcon(e.key), count: e.count, topics: [...e.topics.entries()].map(([name, concepts]) => ({ name, concepts })) }))
    .sort((a, b) => orderOf(a.key) - orderOf(b.key) || a.label.localeCompare(b.label, 'hi'));
}

/** All sets for a source filter: 'all' | QSRC | setId */
export async function sourceSets(src = 'all', lang = 'both') {
  const files = await listConceptSets();
  if (src === QSRC) return [await questionConceptSet(lang)];
  if (src && src !== 'all') return files.filter((s) => s.id === src);
  return [...files, await questionConceptSet(lang)];
}

export function conceptPlainText(c) {
  const parts = [c.title];
  for (const it of c.items || []) {
    if (it.type === 'table') parts.push(it.rows.map((r) => r.join(' ')).join(' '));
    else if (DIAGRAM_TYPES.includes(it.type)) parts.push(diagramText(it));
    else parts.push(plain(it.text));
  }
  return parts.join(' ');
}

export function conceptBundle(sets) {
  const clean = sets.map(({ createdAt, updatedAt, ...s }) => s);
  if (clean.length === 1) return { ...clean[0], app: 'abhyas-cbt' };
  return { abhyas_concept_bundle: 1, app: 'abhyas-cbt', title: `${clean.length} कॉन्सेप्ट सेट`, created_at: new Date().toISOString(), sets: clean };
}

// ---------- AI: concepts from documents / question papers ----------
export const CONCEPT_SYSTEM_PROMPT = `You are an expert teacher and note-maker for Indian teacher-recruitment exams (UP SUPER TET, UP Assistant Teacher, CTET, UPTET).
Your job: read the SOURCE and turn it into complete, exam-oriented revision notes organised SUBJECT → TOPIC → CONCEPT, so a student understands and can revise the WHOLE source smartly without feeling anything was missed.

Output ONLY one JSON object, no prose, in exactly this shape:
{"subjects":[{"subject":"<subject name>","topics":[{"topic":"<topic name>","concepts":[
  {"title":"<short concept title>",
   "points":["<one fact per point>", "..."],
   "tip":"<exam trap / most-asked fact, optional>",
   "trick":"<mnemonic or formula, optional>",
   "table":[["<header 1>","<header 2>"],["<cell>","<cell>"]],
   "diagrams":[ <optional visuals, any of:>
     {"type":"flow","title":"..","steps":["step 1","step 2"]},
     {"type":"cycle","title":"..","steps":["stage 1","stage 2","stage 3"]},
     {"type":"timeline","title":"..","rows":[["1857","event"],["1885","event"]]},
     {"type":"tree","title":"..","node":{"text":"root","children":[{"text":"branch","children":[{"text":"leaf"}]}]}},
     {"type":"pyramid","title":"..","levels":["top","middle","bottom"]},
     {"type":"layers","title":"..","rows":[["top layer","detail"],["next layer","detail"]]},
     {"type":"rings","title":"..","rows":[["outer","detail"],["inner","detail"]]},
     {"type":"venn","title":"..","rel":[["A","<","B"],["B","&","C"]]},
     {"type":"map","region":"india|up|world","title":"..","groups":[{"kind":"state|country|continent|river","names":["केरल"],"label":"legend"}],"points":[{"name":"दिल्ली","lat":28.61,"lon":77.21}]}
   ]}
]}]}]}

Rules:
1. Cover EVERY exam-relevant fact in the source: definitions, names of thinkers/commissions, years, article/section numbers, numbers, lists, classifications, examples. Do not skip small facts — they are asked in MCQs.
2. Completeness over brevity: nothing in the source may be left out, and explain the "why/how" briefly where the source does. Keep each point short (one fact or one explanation step, no filler); merge only true repetition. A concept may have as many points as it needs.
2b. Visuals help memory: add "diagrams" wherever they fit — timeline for chronology, flow for processes/steps, cycle for cycles, tree for classifications/mind maps, map for place-based facts (states, rivers, parks, countries; points need accurate lat/lon), layers/rings for structures, pyramid for hierarchies, venn for syllogisms. Start every topic with a concept "🧠 एक नज़र में" holding a tree mind map of the topic.
3. Use a "table" when the source compares things or lists pairs (name–work, year–event, term–meaning). Omit "table", "tip" and "trick" when not useful.
4. Never invent facts that are not in the source (you may fix obvious typos and garbled legacy-font Hindi such as "तशक्षक"→"शिक्षक").
5. Language: write in clear Hindi (Devanagari) with key English terms in brackets, except English-grammar content, which stays in English. Follow the language setting if given.
6. Group sensibly: subject = exam subject (e.g. बाल मनोविज्ञान, हिन्दी, पर्यावरण), topic = chapter/sub-topic, concept = one idea. Keep the source order.
7. If the source is a set of solved questions, extract the underlying concepts (not the questions) and group them by subject/topic.
8. Output must be valid JSON (double quotes, no trailing commas, no comments).`;

export function conceptUserPrompt({ subject = '', lang = 'hi', detail = 'full', custom = '', sourceNote = '', already = [] }) {
  return [
    subject ? `Subject hint: ${subject}` : '',
    `Language: ${lang === 'en' ? 'English' : lang === 'both' ? 'Hindi with English terms' : 'Hindi (Devanagari)'}`,
    detail === 'short' ? 'Detail: SHORT — only the most important, most-asked facts (one-liner revision).' : 'Detail: FULL — every exam-relevant fact.',
    custom ? `Extra instructions from the student: ${custom}` : '',
    already.length ? `Concept titles already made from earlier parts (do not repeat them, add only new ones): ${already.slice(-60).join(' | ')}` : '',
    sourceNote,
  ].filter(Boolean).join('\n');
}

export function externalConceptPrompt(o = {}) {
  return `${CONCEPT_SYSTEM_PROMPT}

The source document is attached to this message.
${conceptUserPrompt(o)}

APP NOTE: Add at the top level "abhyas_concepts": 1, "title": "${String(o.title || 'मेरे कॉन्सेप्ट नोट्स').replace(/"/g, "'")}". If the reply would be too long, give the first chapters as a complete JSON object and continue with the next chapters (same structure) when I say "continue". I will import every reply in the Abhyas CBT app (कॉन्सेप्ट → इम्पोर्ट).`;
}

const norm = (s) => String(s || '').trim().toLowerCase().replace(/\s+/g, ' ');

/** Merge an AI reply ({subjects:[...]}) into the running tree (job.state.subjects). Returns concepts added. */
export function mergeConceptTree(tree, reply) {
  let added = 0;
  const subs = Array.isArray(reply?.subjects) ? reply.subjects : [];
  for (const s of subs) {
    if (!s || !Array.isArray(s.topics)) continue;
    const sname = str(s.subject) || 'सामान्य';
    let S = tree.find((x) => norm(x.subject) === norm(sname));
    if (!S) { S = { subject: sname, topics: [] }; tree.push(S); }
    for (const t of s.topics) {
      if (!t || !Array.isArray(t.concepts)) continue;
      const tname = str(t.topic) || 'सामान्य';
      let T = S.topics.find((x) => norm(x.topic) === norm(tname));
      if (!T) { T = { topic: tname, concepts: [] }; S.topics.push(T); }
      for (const c of t.concepts) {
        if (!c) continue;
        const title = str(c.title || c.name);
        const items = itemsOf(c);
        if (!title && !items.length) continue;
        const old = title && T.concepts.find((x) => norm(x.title) === norm(title));
        if (old) {
          const have = new Set(old.items.map((i) => norm(i.text || JSON.stringify(i.rows))));
          for (const it of items) if (!have.has(norm(it.text || JSON.stringify(it.rows)))) old.items.push(it);
          continue;
        }
        T.concepts.push({ title: title || 'कॉन्सेप्ट', items });
        added++;
      }
    }
  }
  return added;
}

export const jobConceptCount = (job) => (job.state?.subjects || []).reduce((a, s) => a + s.topics.reduce((b, t) => b + t.concepts.length, 0), 0);

/** Question papers → plain text the AI can turn into concepts. */
export async function questionsAsText(setIds, subjects = []) {
  const want = new Set(subjects);
  const out = [];
  for (const id of setIds) {
    const set = await db.get('sets', id);
    if (!set) continue;
    for (const q of set.questions || []) {
      if (want.size && !want.has(conceptSubjectKey(q.section))) continue;
      const ans = (q.options || []).find((o) => o.id === q.correct_option);
      const sol = plain(q.solution_hi || q.solution_en);
      if (!sol && !plain(q.key_concept)) continue;
      out.push(`[विषय: ${q.section} | टॉपिक: ${q.topic || '-'}] प्रश्न: ${plain(q.question_hi || q.question_en).slice(0, 300)}\nउत्तर: ${plain(ans?.text_hi || ans?.text_en)}\nहल: ${sol.slice(0, 900)}${q.key_concept ? `\nकॉन्सेप्ट: ${plain(q.key_concept)}` : ''}`);
    }
  }
  return out.join('\n\n');
}

/**
 * Background job runner (kind: 'concepts'). job.params = { subject, lang, detail, custom, fromSets, fromSubjects }.
 * Resumable: job.state = { batchesDone, subjects } is saved after every AI call.
 */
export async function runConceptJob(job, { files, signal, log = () => {}, progress = () => {}, saveState = async () => {} }) {
  const { buildSources } = await import('./generate.js');
  const { chat } = await import('./ai.js');
  const p = job.params || {};
  progress({ pct: 1, msg: 'स्रोत पढ़ रहे हैं…' });
  let pasted = job.pasted || '';
  if (p.fromSets?.length) {
    const t = await questionsAsText(p.fromSets, p.fromSubjects || []);
    log(`📚 ${p.fromSets.length} प्रश्न-पत्रों के हल: ${t.length} अक्षर`);
    pasted = `${pasted}\n\n${t}`.trim();
  }
  const units = await buildSources({ files, pasted, withImages: !!p.withImages, onLog: log, signal });
  if (!units.length) throw new Error('कोई स्रोत नहीं — PDF, फ़ोटो, टेक्स्ट या प्रश्न-पत्र चुनें');
  // ~9000 characters per call keeps the reply (notes are long) inside the model's output limit.
  const groups = [];
  let cur = [], chars = 0, imgs = 0;
  for (const u of units) {
    if (cur.length && (chars + u.text.length > 9000 || imgs + (u.image ? 1 : 0) > 4)) { groups.push(cur); cur = []; chars = 0; imgs = 0; }
    cur.push(u); chars += u.text.length; imgs += u.image ? 1 : 0;
  }
  if (cur.length) groups.push(cur);
  const st = job.state || (job.state = { batchesDone: 0, subjects: [] });
  if (st.batchesDone) log(`पिछली प्रगति: ${jobConceptCount(job)} कॉन्सेप्ट — आगे जारी`, 'success');
  for (let b = st.batchesDone; b < groups.length; b++) {
    if (signal?.aborted) throw new DOMException('Aborted', 'AbortError');
    const g = groups[b];
    const textPart = g.filter((u) => u.text.trim()).map((u) => `[${u.label}]\n"""${u.text.trim()}"""`).join('\n\n');
    const images = g.filter((u) => u.image).map((u) => u.image);
    const sourceNote = `SOURCE (part ${b + 1} of ${groups.length}):${images.length ? ` ${images.length} page image(s) attached — read them carefully (text may be in the images).` : ''}\n${textPart}`;
    const already = st.subjects.flatMap((s) => s.topics.flatMap((t) => t.concepts.map((c) => c.title)));
    progress({ pct: Math.min(97, Math.round((b / groups.length) * 100)), msg: `भाग ${b + 1}/${groups.length}: कॉन्सेप्ट बन रहे हैं… (अब तक ${jobConceptCount(job)})` });
    let added = 0;
    try {
      const r = await chat({ system: CONCEPT_SYSTEM_PROMPT, messages: [{ role: 'user', text: conceptUserPrompt({ ...p, sourceNote, already }), images }], json: true, maxTokens: 16000, temperature: 0.2, signal });
      if (r.truncated) log(`भाग ${b + 1}: जवाब लंबा होकर कट गया — जितने पूरे मिले वे रखे`, 'warn');
      added = mergeConceptTree(st.subjects, extractJson(r.text));
    } catch (e) {
      if (e.name === 'AbortError') throw e;
      log(`भाग ${b + 1}: ${e.message}`, 'error');
      if (e.fatal) throw e;
    }
    log(`भाग ${b + 1}/${groups.length}: ${added} नए कॉन्सेप्ट ✓ (कुल ${jobConceptCount(job)})`, added ? 'success' : 'warn');
    st.batchesDone = b + 1;
    await saveState();
  }
  if (!jobConceptCount(job)) throw new Error('AI ने कोई कॉन्सेप्ट नहीं बनाया — स्रोत/मॉडल जाँचें');
  progress({ pct: 100, msg: `पूरा हुआ ✓ ${jobConceptCount(job)} कॉन्सेप्ट` });
}

/** Concept set built from a finished (or partial) AI job. */
export function conceptSetFromJob(job) {
  if (!jobConceptCount(job)) return null;
  const { set } = normalizeConceptSet({ id: job.params?.setId || `ai_${job.id}`, title: job.title, source: 'AI से बनाए गए', subjects: job.state.subjects }, { origin: 'ai' });
  return set;
}
