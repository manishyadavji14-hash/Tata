#!/usr/bin/env node
// Convert a concept-notes text file (papers/concepts/*.txt) into Abhyas concept JSON.
//
// Format (diagrams: see web/js/diagram-spec.js — @flow @cycle @pyramid @timeline @layers @rings @tree @venn @map … @end, @img file caption):
//   @title  ...          set title
//   @source ...          source / book
//   @id     ...          stable set id
//   # Subject            subject heading
//   ## Topic             topic heading
//   ### Concept title    concept card
//   - point              bullet point
//   ! tip                exam tip / trap (highlighted)
//   = trick              mnemonic / formula
//   |a|b|c|              table row (first row of a run = header)
//
// Usage: node tools/concepts2json.mjs papers/concepts/x.txt dist/concepts/x.json
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { parseDiagram, isDiagramBlock, matchFeatures, canonName } from '../web/js/diagram-spec.js';
import { MAPS } from '../web/js/mapdata.js';

// Check that every state / country / river named in a map exists in the map data.
function checkMap(it) {
  const bad = [];
  const world = it.region === 'world';
  for (const g of it.groups) for (const n of g.names) {
    if (g.kind === 'river') { if (world || !(/^(सभी|all)$/i.test(n) || matchFeatures(MAPS.india.rivers, n).length)) bad.push(`नदी ${n}`); }
    else if (g.kind === 'continent') { if (!world || !MAPS.world.countries.some((c) => canonName(c.cont) === canonName(n))) bad.push(`महाद्वीप ${n}`); }
    else if (!matchFeatures(world ? MAPS.world.countries : [...MAPS.india.states, ...MAPS.india.neighbours], n).length) bad.push(n);
  }
  for (const p of it.points) {
    if (!world && (p.lat < 5 || p.lat > 38 || p.lon < 66 || p.lon > 99)) bad.push(`बिंदु ${p.name} भारत के बाहर (${p.lat}, ${p.lon})`);
  }
  return bad;
}

export function parseConcepts(text, { baseDir = '.' } = {}) {
  const out = { abhyas_concepts: 1, id: '', title: '', source: '', created_at: new Date().toISOString().slice(0, 10), subjects: [] };
  let subj = null, topic = null, concept = null, table = null;
  const warnings = [];
  const slug = s => s.toLowerCase().replace(/[^a-z0-9\u0900-\u097f]+/g, '-').replace(/^-|-$/g, '').slice(0, 40);
  const ensureTopic = () => {
    if (!subj) { subj = { subject: 'सामान्य', topics: [] }; out.subjects.push(subj); }
    if (!topic) { topic = { topic: 'सामान्य', concepts: [] }; subj.topics.push(topic); }
  };
  const lines = text.split(/\r?\n/);
  let block = null; // { kind, args, lines, at }
  const stats = {};
  lines.forEach((raw, i) => {
    const line = raw.trim();
    if (block) {
      if (line === '@end') {
        const { item, error } = parseDiagram(block.kind, block.args, block.lines);
        if (error) warnings.push(`line ${block.at}: ${error}`);
        else if (!block.orphan) {
          if (item.type === 'map') checkMap(item).forEach((b) => warnings.push(`line ${block.at}: मानचित्र में नहीं मिला: ${b}`));
          concept.items.push(item); stats[item.type] = (stats[item.type] || 0) + 1;
        }
        block = null;
      } else block.lines.push(raw);
      return;
    }
    if (!line.startsWith('|')) table = null;
    if (!line) return;
    const dm = line.match(/^@(\w+)\b\s*(.*)$/);
    if (dm && (isDiagramBlock(dm[1]) || dm[1] === 'img')) {
      if (!concept) { warnings.push(`line ${i + 1}: diagram outside a concept`); if (dm[1] !== 'img') block = { kind: dm[1], args: dm[2], lines: [], at: i + 1, orphan: true }; return; }
      if (dm[1] === 'img') {
        const [file, ...cap] = dm[2].split(/\s+/);
        const fp = [path.join(baseDir, file), path.join(baseDir, '..', file)].find((p) => fs.existsSync(p)) || path.join(baseDir, file);
        if (!fs.existsSync(fp)) { warnings.push(`line ${i + 1}: image not found: ${file}`); return; }
        const ext = path.extname(fp).slice(1).toLowerCase().replace('jpg', 'jpeg');
        concept.items.push({ type: 'image', title: cap.join(' '), src: `data:image/${ext};base64,${fs.readFileSync(fp).toString('base64')}` });
        stats.image = (stats.image || 0) + 1;
        return;
      }
      block = { kind: dm[1], args: dm[2], lines: [], at: i + 1 };
      return;
    }
    if (line === '@end') { warnings.push(`line ${i + 1}: @end without a diagram`); return; }
    if (line.startsWith('@')) {
      const m = line.match(/^@(\w+)\s+(.*)$/);
      if (m && m[1] in out) out[m[1]] = m[2].trim();
      return;
    }
    if (line.startsWith('### ')) {
      ensureTopic();
      concept = { id: '', title: line.slice(4).trim(), items: [] };
      topic.concepts.push(concept);
      return;
    }
    if (line.startsWith('## ')) {
      if (!subj) { subj = { subject: 'सामान्य', topics: [] }; out.subjects.push(subj); }
      topic = { topic: line.slice(3).trim(), concepts: [] };
      subj.topics.push(topic); concept = null;
      return;
    }
    if (line.startsWith('# ')) {
      subj = { subject: line.slice(2).trim(), topics: [] };
      out.subjects.push(subj); topic = null; concept = null;
      return;
    }
    if (!concept) { warnings.push(`line ${i + 1}: content outside a concept: ${line.slice(0, 60)}`); return; }
    if (line.startsWith('|')) {
      const cells = line.replace(/^\||\|$/g, '').split('|').map(c => c.trim());
      if (!table) { table = { type: 'table', rows: [] }; concept.items.push(table); }
      table.rows.push(cells);
      return;
    }
    const kind = line[0];
    const body = line.slice(1).trim();
    if (kind === '-') concept.items.push({ type: 'point', text: body });
    else if (kind === '!') concept.items.push({ type: 'tip', text: body });
    else if (kind === '=') concept.items.push({ type: 'trick', text: body });
    else concept.items.push({ type: 'point', text: line });
  });
  if (block) warnings.push(`line ${block.at}: @${block.kind} बिना @end के`);
  // ids + cleanup
  const base = out.id || slug(out.title) || 'concepts';
  out.id = base;
  let n = 0;
  for (const s of out.subjects) {
    s.topics = s.topics.filter(t => t.concepts.length);
    for (const t of s.topics) for (const c of t.concepts) { n++; c.id = `${base}-${String(n).padStart(4, '0')}`; }
  }
  out.subjects = out.subjects.filter(s => s.topics.length);
  for (const s of out.subjects) for (const t of s.topics) for (const c of t.concepts) {
    if (!c.items.length) warnings.push(`empty concept: ${c.title}`);
  }
  return { data: out, warnings, count: n, stats };
}

if (import.meta.url === `file://${process.argv[1]}` && process.argv[2] === '--all') {
  // papers/concepts/*.txt → dist/concepts/*.json (readable) + web/concepts/*.json (built into the app) + index
  const dir = 'papers/concepts';
  const index = [];
  for (const f of fs.readdirSync(dir).filter((x) => x.endsWith('.txt')).sort()) {
    const { data, warnings, count, stats } = parseConcepts(fs.readFileSync(path.join(dir, f), 'utf8'), { baseDir: dir });
    warnings.forEach((w) => console.warn('WARN', f, w));
    const name = f.replace(/\.txt$/, '.json');
    const { created_at, ...stable } = data; // version = content hash (not the build date)
    data.version = crypto.createHash('sha1').update(JSON.stringify(stable)).digest('hex').slice(0, 10);
    fs.mkdirSync('dist/concepts', { recursive: true });
    fs.mkdirSync('web/concepts', { recursive: true });
    fs.writeFileSync(path.join('dist/concepts', name), JSON.stringify(data, null, 1));
    fs.writeFileSync(path.join('web/concepts', name), JSON.stringify(data));
    const topics = data.subjects.reduce((a, s) => a + s.topics.length, 0);
    index.push({ file: name, id: data.id, version: data.version, title: data.title, source: data.source, subjects: data.subjects.map((s) => s.subject), topics, concepts: count });
    console.log(`${name}: ${data.subjects.length} subjects, ${topics} topics, ${count} concepts`, JSON.stringify(stats));
  }
  fs.writeFileSync('web/concepts/index.json', JSON.stringify(index));
} else if (import.meta.url === `file://${process.argv[1]}`) {
  const [src, dst] = process.argv.slice(2);
  if (!src) { console.error('usage: concepts2json.mjs in.txt [out.json] | --all'); process.exit(1); }
  const { data, warnings, count, stats } = parseConcepts(fs.readFileSync(src, 'utf8'), { baseDir: path.dirname(src) });
  warnings.forEach(w => console.warn('WARN', w));
  const target = dst || path.join('dist/concepts', path.basename(src).replace(/\.txt$/, '.json'));
  fs.mkdirSync(path.dirname(target), { recursive: true });
  fs.writeFileSync(target, JSON.stringify(data, null, 1));
  const topics = data.subjects.reduce((a, s) => a + s.topics.length, 0);
  console.log(`${target}: ${data.subjects.length} subjects, ${topics} topics, ${count} concepts`, JSON.stringify(stats));
}
