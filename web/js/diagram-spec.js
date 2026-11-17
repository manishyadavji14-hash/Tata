// Diagram items for concept cards: text syntax → JSON item, validation, name matching.
// Pure module (no DOM) — shared by the app (rendering) and tools/concepts2json.mjs (building notes).
//
// Text syntax inside a ### concept (block ends with @end):
//   @flow [title]        one step per line                      → vertical flow chart
//   @cycle [title]       one stage per line                     → circular cycle
//   @pyramid [title]     top level first, one per line          → pyramid
//   @timeline [title]    when | what                            → timeline
//   @layers [title]      name | detail (top → bottom)           → stacked layers
//   @rings [title]       name | detail (outer → inner)          → concentric circles (e.g. earth's interior)
//   @tree [title]        indented lines (2 spaces per level)    → mind map
//   @venn [title]        A < B (A inside B) · A & B (overlap) · A x B (separate)
//   @map india|up|world [title]
//        राज्य: केरल, तमिलनाडु = legend text      (one colour per line; also देश:, महाद्वीप:, नदी:)
//        नाम | 28.61, 77.21                        (a marked point: lat, lon)
//   @img fig/file.webp [caption]   (single line, file next to the notes .txt; inlined by the converter)

export const DIAGRAM_TYPES = ['flow', 'cycle', 'pyramid', 'timeline', 'layers', 'rings', 'tree', 'venn', 'map', 'image'];
const BLOCKS = ['flow', 'cycle', 'pyramid', 'timeline', 'layers', 'rings', 'tree', 'venn', 'map'];
export const isDiagramBlock = (k) => BLOCKS.includes(k);

const str = (v) => (v == null ? '' : String(v)).trim();
const cap = (s, n = 300) => str(s).slice(0, n);
const bullet = (s) => str(s).replace(/^([-•*]|\d+[.)])\s+/, '');
const pair = (line) => { const i = line.indexOf('|'); return i < 0 ? [str(line), ''] : [str(line.slice(0, i)), str(line.slice(i + 1))]; };

const GROUP_KINDS = { 'राज्य': 'state', 'राज्यों': 'state', state: 'state', states: 'state', 'देश': 'country', country: 'country', countries: 'country', 'महाद्वीप': 'continent', continent: 'continent', 'नदी': 'river', 'नदियाँ': 'river', river: 'river', rivers: 'river' };

/** Parse a diagram block → { item } or { error } */
export function parseDiagram(kind, args, rawLines) {
  const lines = rawLines.filter((l) => l.trim());
  const title = cap(args, 120);
  switch (kind) {
    case 'flow': case 'cycle': case 'pyramid': {
      const steps = lines.map(bullet).filter(Boolean);
      if (steps.length < 2) return { error: `@${kind}: कम से कम 2 पंक्तियाँ चाहिए` };
      return { item: kind === 'pyramid' ? { type: kind, title, levels: steps } : { type: kind, title, steps } };
    }
    case 'timeline': case 'layers': case 'rings': {
      const rows = lines.map((l) => pair(bullet(l))).filter((r) => r[0]);
      if (rows.length < 2) return { error: `@${kind}: कम से कम 2 पंक्तियाँ (नाम | विवरण) चाहिए` };
      return { item: { type: kind, title, rows } };
    }
    case 'tree': {
      const ind = (l) => { const m = l.match(/^[\t ]*/)[0]; return m.replace(/\t/g, '  ').length; };
      const base = Math.min(...lines.map(ind));
      const root = { text: title || '', children: [] };
      const stack = [{ lvl: -1, node: root }];
      for (const l of lines) {
        const lvl = Math.floor((ind(l) - base) / 2);
        const node = { text: bullet(l), children: [] };
        while (stack.length > 1 && stack[stack.length - 1].lvl >= lvl) stack.pop();
        stack[stack.length - 1].node.children.push(node);
        stack.push({ lvl, node });
      }
      const single = root.children.length === 1;
      const top = single ? root.children[0] : root;
      if (!top.text) top.text = 'मुख्य बिंदु';
      return { item: { type: 'tree', title: single ? title : '', node: top } };
    }
    case 'venn': {
      const rel = [];
      for (const l of lines) {
        const m = bullet(l).match(/^(.+?)\s*(<|⊂|&|∩|x|×)\s*(.+)$/);
        if (!m) return { error: `@venn: "${l.trim()}" समझ नहीं आया (A < B, A & B, A x B)` };
        rel.push([str(m[1]), { '⊂': '<', '∩': '&', '×': 'x' }[m[2]] || m[2], str(m[3])]);
      }
      if (!rel.length) return { error: '@venn खाली है' };
      return { item: { type: 'venn', title, rel } };
    }
    case 'map': {
      const [region0, ...rest] = str(args).split(/\s+/);
      const region = ['india', 'up', 'world'].includes(region0) ? region0 : null;
      if (!region) return { error: '@map के बाद india, up या world लिखें' };
      const item = { type: 'map', region, title: cap(rest.join(' '), 120), groups: [], points: [] };
      for (const l0 of lines) {
        const l = bullet(l0);
        const g = l.match(/^([^\s:|]+)\s*:\s*(.+)$/);
        if (g && GROUP_KINDS[g[1].toLowerCase()]) {
          const [names, label] = g[2].split(/\s+=\s+/);
          item.groups.push({ kind: GROUP_KINDS[g[1].toLowerCase()], names: names.split(/[,،;]/).map(str).filter(Boolean), label: cap(label || '', 100) });
          continue;
        }
        const p = l.match(/^(.+?)\s*[|@]\s*(-?\d+(?:\.\d+)?)\s*[,\s]\s*(-?\d+(?:\.\d+)?)\s*$/);
        if (p) { item.points.push({ name: cap(p[1], 60), lat: +p[2], lon: +p[3] }); continue; }
        return { error: `@map: "${l0.trim()}" समझ नहीं आया (राज्य: …, नदी: …, या नाम | lat, lon)` };
      }
      if (!item.groups.length && !item.points.length) return { error: '@map खाली है' };
      return { item };
    }
    default: return { error: `अज्ञात डायग्राम @${kind}` };
  }
}

/** Validate/sanitise a diagram item coming from any JSON file. Returns a clean item or null. */
export function cleanDiagram(it) {
  if (!it || !DIAGRAM_TYPES.includes(it.type)) return null;
  const title = cap(it.title, 120);
  const list = (a, n = 40) => (Array.isArray(a) ? a.map((x) => cap(x)).filter(Boolean).slice(0, n) : []);
  const rows = (a) => (Array.isArray(a) ? a.filter(Array.isArray).map((r) => [cap(r[0]), cap(r[1])]).filter((r) => r[0]).slice(0, 60) : []);
  switch (it.type) {
    case 'flow': case 'cycle': { const steps = list(it.steps, it.type === 'cycle' ? 10 : 40); return steps.length >= 2 ? { type: it.type, title, steps } : null; }
    case 'pyramid': { const levels = list(it.levels, 10); return levels.length >= 2 ? { type: 'pyramid', title, levels } : null; }
    case 'timeline': case 'layers': case 'rings': { const r = rows(it.rows); return r.length >= 2 ? { type: it.type, title, rows: it.type === 'rings' ? r.slice(0, 9) : r } : null; }
    case 'tree': {
      let count = 0;
      const walk = (n, d) => (n && count++ < 120 && d < 6 ? { text: cap(n.text) || '•', children: (Array.isArray(n.children) ? n.children : []).map((c) => walk(c, d + 1)).filter(Boolean) } : null);
      const node = walk(it.node, 0);
      return node ? { type: 'tree', title, node } : null;
    }
    case 'venn': {
      const rel = (Array.isArray(it.rel) ? it.rel : []).filter((r) => Array.isArray(r) && ['<', '&', 'x'].includes(r[1])).map((r) => [cap(r[0], 40), r[1], cap(r[2], 40)]).slice(0, 4);
      return rel.length ? { type: 'venn', title, rel } : null;
    }
    case 'map': {
      if (!['india', 'up', 'world'].includes(it.region)) return null;
      const groups = (Array.isArray(it.groups) ? it.groups : []).filter((g) => g && ['state', 'country', 'continent', 'river'].includes(g.kind))
        .map((g) => ({ kind: g.kind, names: list(g.names, 60), label: cap(g.label, 100) })).slice(0, 8);
      const points = (Array.isArray(it.points) ? it.points : []).filter((p) => p && Number.isFinite(+p.lat) && Number.isFinite(+p.lon) && Math.abs(+p.lat) <= 90 && Math.abs(+p.lon) <= 180)
        .map((p) => ({ name: cap(p.name, 60), lat: +p.lat, lon: +p.lon })).slice(0, 60);
      return groups.length || points.length ? { type: 'map', region: it.region, title, groups, points } : null;
    }
    case 'image': {
      const src = str(it.src);
      return /^data:image\/(png|jpe?g|webp|gif);base64,[a-z0-9+/=]+$/i.test(src) ? { type: 'image', title, src } : null;
    }
    default: return null;
  }
}

// ---------- map name matching ----------
export const normName = (s) => str(s).toLowerCase().normalize('NFC').replace(/\u093c/g, '').replace(/[\s\-–—.'’()&]/g, '').replace(/और|एवं|तथा|and/g, '');
const ALIAS = {
  'जम्मूकश्मीर': 'jammukashmir', 'उड़ीसा': 'odisha', 'उडीसा': 'odisha', 'orissa': 'odisha', 'पांडिचेरी': 'puducherry', 'पुडुचेरी': 'puducherry', 'pondicherry': 'puducherry',
  'अंडमाननिकोबार': 'andamannicobar', 'अंडमाननिकोबारद्वीपसमूह': 'andamannicobar', 'दादरानगरहवेलीदमनदीव': 'dadranagarhavelidamandiu', 'दमनदीव': 'dadranagarhavelidamandiu',
  'उत्तरांचल': 'uttarakhand', 'तेलंगाना': 'telangana', 'छत्तीसगढ': 'chhattisgarh', 'up': 'uttarpradesh', 'mp': 'madhyapradesh',
  'गंगा': 'गंगा', 'ganga': 'गंगा', 'ganges': 'गंगा', 'yamuna': 'यमुना', 'brahmaputra': 'ब्रह्मपुत्र', 'indus': 'सिंधु', 'सिन्धु': 'सिंधु', 'godavari': 'गोदावरी', 'krishna': 'कृष्णा',
  'kaveri': 'कावेरी', 'cauvery': 'कावेरी', 'narmada': 'नर्मदा', 'tapi': 'ताप्ती', 'tapti': 'ताप्ती', 'तापी': 'ताप्ती', 'mahanadi': 'महानदी', 'chambal': 'चंबल', 'चम्बल': 'चंबल', 'betwa': 'बेतवा', 'son': 'सोन', 'ghaghara': 'घाघरा', 'घाघरा': 'घाघरा', 'सरयू': 'घाघरा',
  'gandak': 'गंडक', 'kosi': 'कोसी', 'sutlej': 'सतलुज', 'सतलज': 'सतलुज', 'beas': 'ब्यास', 'ravi': 'रावी', 'chenab': 'चिनाब', 'jhelum': 'झेलम', 'teesta': 'तीस्ता', 'tista': 'तीस्ता', 'mahi': 'माही', 'penner': 'पेन्नार', 'पेनार': 'पेन्नार',
  'bhima': 'भीमा', 'tungabhadra': 'तुंगभद्रा', 'damodar': 'दामोदर', 'luni': 'लूनी',
  'usa': 'unitedstatesofamerica', 'अमेरिका': 'unitedstatesofamerica', 'america': 'unitedstatesofamerica', 'unitedstates': 'unitedstatesofamerica', 'uk': 'unitedkingdom', 'britain': 'unitedkingdom', 'इंग्लैंड': 'unitedkingdom', 'ब्रिटेन': 'unitedkingdom',
  'russia': 'russia', 'रूस': 'russia', 'चीन': 'peoplesrepublicofchina', 'china': 'peoplesrepublicofchina', 'myanmar': 'myanmar', 'burma': 'myanmar', 'म्यांमार': 'myanmar',
  'srilanka': 'srilanka', 'श्रीलंका': 'srilanka', 'नेपाल': 'nepal', 'भूटान': 'bhutan', 'बांग्लादेश': 'bangladesh', 'पाकिस्तान': 'pakistan', 'अफगानिस्तान': 'afghanistan',
  'एशिया': 'asia', 'अफ्रीका': 'africa', 'यूरोप': 'europe', 'उत्तरीअमेरिका': 'northamerica', 'दक्षिणीअमेरिका': 'southamerica', 'दक्षिणअमेरिका': 'southamerica', 'ऑस्ट्रेलिया': 'oceania', 'ओशिनिया': 'oceania', 'australia': 'oceania', 'अंटार्कटिका': 'antarctica',
};
export const canonName = (s) => { const n = normName(s); return ALIAS[n] || n; };
/** features: [{ en, hi, short? }] → the matching features for one name */
export function matchFeatures(features, name) {
  const want = canonName(name);
  if (!want) return [];
  const keys = (f) => [f.en, f.hi, f.short].filter(Boolean).map(canonName);
  const exact = features.filter((f) => keys(f).includes(want));
  if (exact.length) return exact;
  return want.length >= 4 ? features.filter((f) => keys(f).some((k) => k.length >= 4 && (k.startsWith(want) || want.startsWith(k)))) : [];
}

/** Searchable plain text of a diagram item. */
export function diagramText(it) {
  const t = [it.title];
  const walk = (n) => { if (!n) return; t.push(n.text); (n.children || []).forEach(walk); };
  switch (it.type) {
    case 'flow': case 'cycle': t.push(...it.steps); break;
    case 'pyramid': t.push(...it.levels); break;
    case 'timeline': case 'layers': case 'rings': t.push(...it.rows.flat()); break;
    case 'tree': walk(it.node); break;
    case 'venn': t.push(...it.rel.map((r) => `${r[0]} ${r[2]}`)); break;
    case 'map': t.push(...it.groups.flatMap((g) => [...g.names, g.label]), ...it.points.map((p) => p.name)); break;
    default: break;
  }
  return t.filter(Boolean).join(' ');
}
