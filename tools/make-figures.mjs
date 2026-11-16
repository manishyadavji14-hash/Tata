// Redraws figures whose full data is stated in the question text (no guessing) and writes PNGs
// to papers/figures/. Run: node tools/make-figures.mjs
import sharp from 'sharp';
import { mkdirSync } from 'node:fs';

const OUT = 'papers/figures/';
mkdirSync(OUT, { recursive: true });
const FONT = "font-family='Noto Sans, DejaVu Sans, sans-serif'";

// Isometric die: top, front and right faces with a label on each.
function die(x, y, s, top, front, right, caption) {
  const h = s * 0.5;
  const P = (pts) => pts.map((p) => p.join(',')).join(' ');
  const topF = [[x, y + h], [x + s, y], [x + 2 * s, y + h], [x + s, y + 2 * h]];
  const frontF = [[x, y + h], [x + s, y + 2 * h], [x + s, y + 2 * h + s], [x, y + h + s]];
  const rightF = [[x + s, y + 2 * h], [x + 2 * s, y + h], [x + 2 * s, y + h + s], [x + s, y + 2 * h + s]];
  const t = (cx, cy, v) => `<text x='${cx}' y='${cy}' ${FONT} font-size='${s * 0.42}' font-weight='700' text-anchor='middle' dominant-baseline='central'>${v}</text>`;
  return `<polygon points='${P(topF)}' fill='#f1f5f9' stroke='#0f172a' stroke-width='3'/>
    <polygon points='${P(frontF)}' fill='#fff' stroke='#0f172a' stroke-width='3'/>
    <polygon points='${P(rightF)}' fill='#e2e8f0' stroke='#0f172a' stroke-width='3'/>
    ${t(x + s, y + h, top)}${t(x + s / 2, y + 1.5 * h + s / 2, front)}${t(x + 1.5 * s, y + 1.5 * h + s / 2, right)}
    <text x='${x + s}' y='${y + 2 * h + s + 34}' ${FONT} font-size='26' text-anchor='middle'>${caption}</text>`;
}

const figures = {
  // Model Paper 07, Q112: I = 1 top, 2 front, 3 right; II = 4 top, 5 front, 6 right
  'p07_q112_dice.png': {
    w: 520, h: 300,
    svg: die(40, 20, 110, 1, 2, 3, '(I)') + die(290, 20, 110, 4, 5, 6, '(II)'),
  },
  // Model Paper 05, Q113: die I shows P (front) and A (right); die II shows H (top), R (front), A (right)
  'p05_q113_dice.png': {
    w: 520, h: 300,
    svg: die(40, 20, 110, '', 'P', 'A', '(I)') + die(290, 20, 110, 'H', 'R', 'A', '(II)'),
  },
  // Model Paper 10, Q115: the four diagrams (a)–(d) as printed
  'p10_q115_venn.png': {
    w: 800, h: 230,
    svg: (() => {
      const c = (cx, cy, r) => `<circle cx='${cx}' cy='${cy}' r='${r}' fill='none' stroke='#0f172a' stroke-width='3'/>`;
      const box = (i, inner, label) => `<rect x='${10 + i * 195}' y='10' width='185' height='170' fill='none' stroke='#94a3b8' stroke-width='2'/>${inner}<text x='${102 + i * 195}' y='215' ${FONT} font-size='24' text-anchor='middle'>${label}</text>`;
      const X = (i) => 102 + i * 195;
      return box(0, c(X(0), 95, 70) + c(X(0) - 8, 100, 45) + c(X(0) - 14, 104, 22), '(a)')
        + box(1, c(X(1) - 55, 95, 25) + c(X(1), 95, 25) + c(X(1) + 55, 95, 25), '(b)')
        + box(2, c(X(2) - 25, 80, 42) + c(X(2) + 25, 80, 42) + c(X(2), 120, 42), '(c)')
        + box(3, c(X(3), 95, 70) + c(X(3), 95, 48) + c(X(3), 95, 26), '(d)');
    })(),
  },
};

// ---------- helpers for solution diagrams (Latin labels only: no Devanagari font in the build box) ----------
const GLYPH = { '∥': '||', '∠': 'angle ', '⇒': '=>', '→': '->' };
const T = (x, y, s, v, o = '') => `<text x='${x}' y='${y}' ${FONT} font-size='${s}' ${o}>${String(v).replace(/[∥∠⇒→]/g, (c) => GLYPH[c])}</text>`;
const L = (x1, y1, x2, y2, o = "stroke='#0f172a' stroke-width='3'") => `<line x1='${x1}' y1='${y1}' x2='${x2}' y2='${y2}' ${o}/>`;
// arc around (cx,cy) from math-angle a1 to a2 (degrees, counter-clockwise, y up)
const arc = (cx, cy, r, a1, a2, col) => {
  const p = (a) => [cx + r * Math.cos((a * Math.PI) / 180), cy - r * Math.sin((a * Math.PI) / 180)];
  const [x1, y1] = p(a1); const [x2, y2] = p(a2);
  return `<path d='M${x1.toFixed(1)},${y1.toFixed(1)} A${r},${r} 0 ${a2 - a1 > 180 ? 1 : 0} 0 ${x2.toFixed(1)},${y2.toFixed(1)}' fill='none' stroke='${col}' stroke-width='3'/>`;
};
const arrowDefs = `<defs><marker id='ah' viewBox='0 0 10 10' refX='9' refY='5' markerWidth='7' markerHeight='7' orient='auto-start-reverse'><path d='M0,0 L10,5 L0,10 z' fill='#2563eb'/></marker></defs>`;
const seg = (x1, y1, x2, y2) => L(x1, y1, x2, y2, "stroke='#2563eb' stroke-width='4' marker-end='url(#ah)'");

function clock(h, m, label) {
  const cx = 160, cy = 160, r = 130;
  const pt = (deg, rr) => [cx + rr * Math.sin((deg * Math.PI) / 180), cy - rr * Math.cos((deg * Math.PI) / 180)];
  let s = `<circle cx='${cx}' cy='${cy}' r='${r}' fill='#fff' stroke='#0f172a' stroke-width='4'/>`;
  for (let i = 1; i <= 12; i++) { const [x, y] = pt(i * 30, r - 24); s += T(x, y + 8, 22, i, "text-anchor='middle' font-weight='700'"); }
  const ha = (h % 12) * 30 + m * 0.5, ma = m * 6;
  const [hx, hy] = pt(ha, 70), [mx, my] = pt(ma, 105);
  const a1 = Math.min(ha, ma), a2 = Math.max(ha, ma);
  const [sx, sy] = pt(a1, 50), [ex, ey] = pt(a2, 50);
  s += `<path d='M${sx},${sy} A50,50 0 ${a2 - a1 > 180 ? 1 : 0} 1 ${ex},${ey}' fill='none' stroke='#dc2626' stroke-width='4'/>`;
  s += L(cx, cy, hx, hy, "stroke='#0f172a' stroke-width='9' stroke-linecap='round'") + L(cx, cy, mx, my, "stroke='#2563eb' stroke-width='5' stroke-linecap='round'");
  s += `<circle cx='${cx}' cy='${cy}' r='7' fill='#0f172a'/>`;
  s += T(330, 70, 24, `${h}:${String(m).padStart(2, '0')}`, "font-weight='700'");
  s += T(330, 110, 20, `Hour hand = ${ha}°`) + T(330, 140, 20, `Minute hand = ${ma}°`, "fill='#2563eb'");
  s += T(330, 185, 26, label, "fill='#dc2626' font-weight='700'");
  s += T(330, 225, 18, '|30H − 5.5M|', "fill='#475569'");
  return s;
}

// family tree: nodes {id,x,y,label,sex:'m'|'f'|'?'}, edges [[a,b,'marry'|'child'|'sib']]
function tree(nodes, edges, note) {
  const N = Object.fromEntries(nodes.map((n) => [n.id, n]));
  let s = '';
  for (const [a, b, kind] of edges) {
    const p = N[a], q = N[b];
    if (kind === 'marry') s += L(p.x + 28, p.y - 4, q.x - 28, q.y - 4, "stroke='#0f172a' stroke-width='3'") + L(p.x + 28, p.y + 4, q.x - 28, q.y + 4, "stroke='#0f172a' stroke-width='3'");
    else if (kind === 'sib') s += `<path d='M${p.x},${p.y - 30} V${p.y - 50} H${q.x} V${q.y - 30}' fill='none' stroke='#64748b' stroke-width='3' stroke-dasharray='6 5'/>`;
    else s += `<path d='M${p.x},${p.y + 30} V${(p.y + q.y) / 2} H${q.x} V${q.y - 30}' fill='none' stroke='#0f172a' stroke-width='3'/>`;
  }
  for (const n of nodes) {
    const fill = n.hi ? '#fde68a' : '#fff';
    s += n.sex === 'f' ? `<circle cx='${n.x}' cy='${n.y}' r='28' fill='${fill}' stroke='#db2777' stroke-width='4'/>`
      : n.sex === 'm' ? `<rect x='${n.x - 28}' y='${n.y - 28}' width='56' height='56' rx='6' fill='${fill}' stroke='#2563eb' stroke-width='4'/>`
        : `<rect x='${n.x - 28}' y='${n.y - 28}' width='56' height='56' rx='28' fill='${fill}' stroke='#64748b' stroke-width='4' stroke-dasharray='5 4'/>`;
    s += T(n.x, n.y + 9, n.id.length > 2 ? 15 : 26, n.id, "text-anchor='middle' font-weight='700'");
    if (n.label) s += T(n.x, n.y + 52, 16, n.label, "text-anchor='middle' fill='#475569'");
  }
  s += `<rect x='12' y='12' width='22' height='22' rx='3' fill='none' stroke='#2563eb' stroke-width='3'/>` + T(40, 30, 16, 'male')
    + `<circle cx='112' cy='23' r='11' fill='none' stroke='#db2777' stroke-width='3'/>` + T(128, 30, 16, 'female')
    + L(200, 19, 226, 19) + L(200, 27, 226, 27) + T(232, 30, 16, 'married');
  if (note) s += T(12, 60, 20, note, "fill='#b45309' font-weight='700'");
  return s;
}

const compass = (x, y) => `<g ${FONT} font-size='16' font-weight='700' fill='#475569'>${L(x, y - 26, x, y + 26, "stroke='#94a3b8' stroke-width='2'")}${L(x - 26, y, x + 26, y, "stroke='#94a3b8' stroke-width='2'")}<text x='${x - 5}' y='${y - 30}'>N</text><text x='${x - 5}' y='${y + 44}'>S</text><text x='${x + 30}' y='${y + 6}'>E</text><text x='${x - 46}' y='${y + 6}'>W</text></g>`;

// OP ∥ RS, ∠OPQ = 100°, ∠QRS = 140°  (coordinates derived so that ∠PQR = 60°)
const LP = { O: [40, 80], P: [200, 80], Q: [240, 306], R: [416, 159], S: [570, 159] };
const linesBase = () => `${L(...LP.O, ...LP.P)}${L(...LP.P, ...LP.Q)}${L(...LP.Q, ...LP.R)}${L(...LP.R, ...LP.S)}
  <g stroke='#64748b' stroke-width='2'><path d='M115,72 l10,8 l-10,8'/><path d='M495,151 l10,8 l-10,8'/></g>
  ${arc(...LP.P, 34, 180, 280, '#dc2626')}${arc(...LP.R, 34, 220, 360, '#16a34a')}
  ${T(22, 70, 24, 'O', "font-weight='700'")}${T(196, 64, 24, 'P', "font-weight='700'")}${T(232, 340, 24, 'Q', "font-weight='700'")}${T(412, 140, 24, 'R', "font-weight='700'")}${T(566, 145, 24, 'S', "font-weight='700'")}
  ${T(130, 130, 22, '100°', "fill='#dc2626' font-weight='700'")}${T(400, 215, 22, '140°', "fill='#16a34a' font-weight='700'")}`;

Object.assign(figures, {
  'q_lines_100_140.png': { w: 600, h: 360, svg: linesBase() + T(300, 40, 20, 'OP ∥ RS', "fill='#475569'") },
  'sol_lines_100_140.png': {
    w: 600, h: 420,
    svg: linesBase() + L(110, 306, 540, 306, "stroke='#7c3aed' stroke-width='3' stroke-dasharray='10 7'")
      + arc(...LP.Q, 44, 100, 180, '#7c3aed') + arc(...LP.Q, 60, 0, 40, '#0ea5e9') + arc(...LP.Q, 26, 40, 100, '#0f172a')
      + T(150, 290, 22, '80°', "fill='#7c3aed' font-weight='700'") + T(305, 296, 22, '40°', "fill='#0ea5e9' font-weight='700'")
      + T(250, 268, 20, '60°', "font-weight='700'") + T(546, 312, 18, '∥ OP', "fill='#7c3aed'")
      + T(20, 380, 20, '180° − 100° = 80°,   180° − 140° = 40°', "fill='#334155'")
      + T(20, 408, 22, '∠PQR = 180° − 80° − 40° = 60°', "fill='#0f172a' font-weight='700'"),
  },
  'sol_p01_q47_rhombus.png': {
    w: 520, h: 330,
    svg: `<polygon points='260,30 440,165 260,300 80,165' fill='#eff6ff' stroke='#0f172a' stroke-width='4'/>`
      + L(260, 30, 260, 300, "stroke='#2563eb' stroke-width='3'") + L(80, 165, 440, 165, "stroke='#2563eb' stroke-width='3'")
      + `<path d='M260,145 h20 v20' fill='none' stroke='#dc2626' stroke-width='3'/>`
      + T(250, 22, 22, 'A', "font-weight='700'") + T(448, 172, 22, 'B', "font-weight='700'") + T(250, 324, 22, 'C', "font-weight='700'") + T(56, 172, 22, 'D', "font-weight='700'")
      + T(268, 190, 18, 'O') + T(290, 150, 18, '90°', "fill='#dc2626'")
      + T(330, 70, 18, 'AB = BC = CD = DA', "fill='#475569'") + T(300, 255, 18, 'AO = OC,  BO = OD', "fill='#2563eb'"),
  },
  'sol_p01_q48_quadrant.png': {
    w: 420, h: 380,
    svg: L(20, 180, 400, 180) + L(210, 10, 210, 360)
      + (() => { let g = ''; for (let i = -4; i <= 4; i++) if (i) { g += L(210 + i * 40, 175, 210 + i * 40, 185, "stroke='#0f172a' stroke-width='2'") + L(205, 180 - i * 40, 215, 180 - i * 40, "stroke='#0f172a' stroke-width='2'"); } return g; })()
      + T(290, 90, 26, 'I (+,+)', "fill='#16a34a' font-weight='700'") + T(40, 90, 26, 'II (−,+)', "fill='#64748b' font-weight='700'")
      + T(30, 330, 26, 'III (−,−)', "fill='#dc2626' font-weight='700'") + T(280, 330, 26, 'IV (+,−)', "fill='#64748b' font-weight='700'")
      + `<line x1='130' y1='180' x2='130' y2='300' stroke='#dc2626' stroke-dasharray='6 5' stroke-width='2'/><line x1='210' y1='300' x2='130' y2='300' stroke='#dc2626' stroke-dasharray='6 5' stroke-width='2'/>`
      + `<circle cx='130' cy='300' r='8' fill='#dc2626'/>` + T(140, 290, 20, '(−2, −3)', "fill='#dc2626' font-weight='700'")
      + T(388, 200, 18, 'X') + T(220, 24, 18, 'Y'),
  },
  'sol_p01_q112_clock.png': { w: 560, h: 320, svg: clock(3, 30, 'Angle = 75°') },
  'sol_p02_q112_clock.png': { w: 560, h: 320, svg: clock(3, 20, 'Angle = 20°') },
  'sol_p01_q114_tree.png': {
    w: 460, h: 330,
    svg: tree([{ id: 'D', x: 230, y: 100, sex: 'm', label: '' }, { id: 'C', x: 230, y: 195, sex: 'f', label: '' }, { id: 'A', x: 160, y: 290, sex: 'm', hi: true }, { id: 'B', x: 300, y: 290, sex: '?' }],
      [['D', 'C', 'child'], ['C', 'A', 'child'], ['C', 'B', 'child']], 'D = maternal grandfather (Nana) of A'),
  },
  'sol_p02_q114_tree.png': {
    w: 460, h: 300,
    svg: tree([{ id: 'A', x: 90, y: 140, sex: 'm', hi: true }, { id: 'B', x: 230, y: 140, sex: 'f' }, { id: 'D', x: 370, y: 140, sex: 'm' }, { id: 'C', x: 300, y: 250, sex: 'm' }],
      [['A', 'B', 'sib'], ['B', 'D', 'marry'], ['B', 'C', 'child']], "A = wife's brother (Sala) of D"),
  },
  'sol_p03_q116_tree.png': {
    w: 620, h: 330,
    svg: tree([{ id: 'Nehal', x: 110, y: 140, sex: 'm', hi: true }, { id: 'Lakhan', x: 280, y: 140, sex: 'm' }, { id: 'Malti', x: 430, y: 140, sex: 'f' },
      { id: 'Amrita', x: 230, y: 270, sex: 'f' }, { id: 'Bablu', x: 355, y: 270, sex: 'm' }, { id: 'Deepak', x: 480, y: 270, sex: 'm' }],
    [['Nehal', 'Lakhan', 'sib'], ['Lakhan', 'Malti', 'marry'], ['Malti', 'Amrita', 'child'], ['Malti', 'Bablu', 'child'], ['Malti', 'Deepak', 'child']], "Nehal = father's brother (Chacha) of Amrita"),
  },
  'sol_p04_q114_tree.png': {
    w: 620, h: 330,
    svg: tree([{ id: 'G', x: 90, y: 140, sex: 'm' }, { id: 'A', x: 230, y: 140, sex: 'f' }, { id: 'B', x: 400, y: 140, sex: 'f' },
      { id: 'D', x: 230, y: 270, sex: '?' }, { id: 'C', x: 400, y: 270, sex: 'f', hi: true }, { id: 'F', x: 540, y: 270, sex: 'm' }],
    [['G', 'A', 'marry'], ['A', 'B', 'sib'], ['A', 'D', 'child'], ['B', 'C', 'child'], ['C', 'F', 'marry']], "C = mother's sister's daughter (cousin) of D"),
  },
  'sol_p02_q116_path.png': {
    w: 520, h: 330,
    svg: arrowDefs + seg(120, 260, 120, 110) + seg(120, 110, 360, 110) + seg(360, 110, 360, 260)
      + L(120, 260, 360, 260, "stroke='#dc2626' stroke-width='3' stroke-dasharray='9 6'")
      + `<circle cx='120' cy='260' r='8' fill='#16a34a'/>` + T(60, 292, 18, 'Start', "fill='#16a34a' font-weight='700'")
      + `<circle cx='360' cy='260' r='8' fill='#dc2626'/>` + T(340, 292, 18, 'End', "fill='#dc2626' font-weight='700'")
      + T(60, 190, 18, '10 m N') + T(200, 98, 18, '15 m E') + T(370, 190, 18, '10 m S') + T(150, 250, 18, '15 m East of start', "fill='#dc2626' font-weight='700'")
      + compass(460, 70),
  },
  'sol_p04_q115_path.png': {
    w: 620, h: 340,
    svg: arrowDefs + seg(250, 120, 115, 120) + seg(115, 120, 115, 180) + seg(115, 180, 415, 180)
      + L(415, 180, 415, 128, "stroke='#dc2626' stroke-width='3' stroke-dasharray='8 6' marker-end='url(#ah)'")
      + L(250, 120, 415, 120, "stroke='#94a3b8' stroke-width='2' stroke-dasharray='4 5'")
      + `<circle cx='250' cy='120' r='8' fill='#16a34a'/>` + T(236, 104, 22, 'Q', "fill='#16a34a' font-weight='700'")
      + `<circle cx='415' cy='120' r='8' fill='#0f172a'/>` + T(408, 104, 22, 'P', "font-weight='700'")
      + T(150, 112, 18, 'W 9 km') + T(40, 156, 18, 'S 4 km') + T(230, 210, 18, 'E 20 km') + T(300, 140, 16, '11 km', "fill='#64748b'")
      + T(425, 160, 18, 'N 4 km', "fill='#dc2626' font-weight='700'") + T(20, 300, 20, 'Scale: 15 px = 1 km', "fill='#94a3b8'")
      + compass(560, 70),
  },
  'q_p03_q114_grid.png': {
    w: 260, h: 260,
    svg: (() => { let g = ''; for (let i = 0; i <= 4; i++) g += L(30 + i * 50, 30, 30 + i * 50, 230) + L(30, 30 + i * 50, 230, 30 + i * 50); return g; })(),
  },
  'sol_p03_q114_squares.png': {
    w: 620, h: 270,
    svg: (() => {
      let g = ''; const box = (ox, k, col) => { let s = ''; for (let i = 0; i <= 4; i++) s += L(ox + i * 30, 30, ox + i * 30, 150, "stroke='#94a3b8' stroke-width='2'") + L(ox, 30 + i * 30, ox + 120, 30 + i * 30, "stroke='#94a3b8' stroke-width='2'"); return s + `<rect x='${ox}' y='30' width='${k * 30}' height='${k * 30}' fill='${col}' fill-opacity='0.35' stroke='${col}' stroke-width='4'/>`; };
      [['1×1', 16, '#2563eb'], ['2×2', 9, '#16a34a'], ['3×3', 4, '#d97706'], ['4×4', 1, '#dc2626']].forEach(([t, n, c], i) => { g += box(15 + i * 150, i + 1, c) + T(30 + i * 150, 185, 22, `${t} = ${n}`, `fill='${c}' font-weight='700'`); });
      return g + T(150, 240, 26, '16 + 9 + 4 + 1 = 30', "font-weight='700'");
    })(),
  },
  'sol_p04_q45_roads.png': {
    w: 560, h: 380,
    svg: `<rect x='40' y='40' width='480' height='300' fill='#dcfce7' stroke='#0f172a' stroke-width='3'/>`
      + `<rect x='40' y='175' width='480' height='30' fill='#d6d3d1'/><rect x='265' y='40' width='30' height='300' fill='#d6d3d1'/><rect x='265' y='175' width='30' height='30' fill='#ef4444' fill-opacity='0.7'/>`
      + T(250, 30, 20, '80 m') + T(1, 196, 16, '60 m') + T(60, 168, 18, 'road 80 × 5 = 400 m²') + T(305, 80, 18, 'road 60 × 5 = 300 m²')
      + T(300, 235, 18, 'common 5 × 5 = 25 m²', "fill='#dc2626' font-weight='700'") + T(60, 370, 20, '400 + 300 − 25 = 675 m²  →  675 × ₹10 = ₹6750', "font-weight='700'"),
  },
  'sol_p04_q48_triangle.png': {
    w: 460, h: 360,
    svg: `<polygon points='230,30 60,320 400,320' fill='#eff6ff' stroke='#0f172a' stroke-width='4'/>` + L(230, 30, 230, 320, "stroke='#dc2626' stroke-width='3'")
      + `<path d='M230,300 h20 v20' fill='none' stroke='#dc2626' stroke-width='3'/>`
      + T(222, 22, 22, 'A', "font-weight='700'") + T(36, 340, 22, 'B', "font-weight='700'") + T(404, 340, 22, 'C', "font-weight='700'") + T(222, 348, 22, 'D', "font-weight='700'")
      + T(110, 170, 20, 'a') + T(330, 170, 20, 'a') + T(130, 312, 18, 'a/2') + T(300, 312, 18, 'a/2') + T(240, 190, 20, 'AD', "fill='#dc2626'")
      + T(20, 60, 18, 'AD² = a² − (a/2)² = 3a²/4', "fill='#334155'") + T(20, 86, 18, '⇒ 4 AD² = 3 AB²', "fill='#334155' font-weight='700'"),
  },
  'sol_p04_q49_chord.png': {
    w: 460, h: 380,
    svg: `<circle cx='230' cy='190' r='170' fill='#f8fafc' stroke='#0f172a' stroke-width='4'/>`
      + L(80, 270, 380, 270, "stroke='#2563eb' stroke-width='4'") + L(230, 190, 230, 270, "stroke='#dc2626' stroke-width='3' stroke-dasharray='8 5'") + L(230, 190, 380, 270, "stroke='#16a34a' stroke-width='3'")
      + `<circle cx='230' cy='190' r='6' fill='#0f172a'/><path d='M230,252 h18 v18' fill='none' stroke='#dc2626' stroke-width='3'/>`
      + T(212, 182, 20, 'O', "font-weight='700'") + T(120, 300, 20, '15 cm', "fill='#2563eb'") + T(290, 300, 20, '15 cm', "fill='#2563eb'")
      + T(236, 236, 20, '8', "fill='#dc2626' font-weight='700'") + T(310, 222, 20, 'r', "fill='#16a34a' font-weight='700'")
      + T(110, 120, 20, 'r² = 15² + 8² = 289', "fill='#334155'") + T(150, 148, 22, 'r = 17 cm', "fill='#16a34a' font-weight='700'"),
  },
  'sol_p10_q114_compass.png': {
    w: 360, h: 300,
    svg: arrowDefs + L(180, 40, 180, 260, "stroke='#94a3b8' stroke-width='2'") + L(70, 150, 290, 150, "stroke='#94a3b8' stroke-width='2'")
      + T(173, 34, 18, 'N', "font-weight='700' fill='#475569'") + T(188, 262, 18, 'S', "font-weight='700' fill='#475569'") + T(296, 156, 18, 'E', "font-weight='700' fill='#475569'")
      + L(180, 150, 70, 150, "stroke='#0f172a' stroke-width='5'")
      + seg(180, 150, 105, 75) + arc(180, 150, 50, 135, 180, '#dc2626')
      + T(52, 172, 18, 'W (start)') + T(60, 62, 20, 'NW', "fill='#2563eb' font-weight='700'") + T(108, 128, 18, '45°', "fill='#dc2626' font-weight='700'")
      + T(20, 285, 18, 'clockwise: West -> North-West', "fill='#475569'"),
  },
});

// ---- UP AT 2026 Mock Test-2 / Mock Test-3 (अध्ययन मंत्र) ----
const cubeFace = (n, kind) => {
  const c = 50, ox = 40, oy = 40; let g = '';
  for (let r = 0; r < n; r++) for (let k = 0; k < n; k++) {
    const inner = r > 0 && k > 0 && r < n - 1 && k < n - 1;
    const fill = inner ? (kind === 'one' ? '#f59e0b' : '#e2e8f0') : '#bfdbfe';
    g += `<rect x='${ox + k * c}' y='${oy + r * c}' width='${c}' height='${c}' fill='${fill}' stroke='#0f172a' stroke-width='2'/>`;
  }
  if (kind === 'none') g += `<rect x='${ox + c}' y='${oy + c}' width='${(n - 2) * c}' height='${(n - 2) * c}' fill='none' stroke='#dc2626' stroke-width='4' stroke-dasharray='10 6'/>`;
  const tx = ox + n * c + 30;
  return { w: tx + 330, h: Math.max(oy * 2 + n * c, 260), tx, g };
};
const altitude = (p, q, h, s) => {
  const B = [40, 40 + h * s], D = [40 + p * s, 40 + h * s], C = [40 + (p + q) * s, 40 + h * s], A = [D[0], 40];
  return {
    w: C[0] + 300, h: B[1] + 60,
    svg: `<polygon points='${A} ${B} ${C}' fill='#eff6ff' stroke='#0f172a' stroke-width='4'/>`
      + L(...A, ...D, "stroke='#dc2626' stroke-width='4'") + `<path d='M${D[0]},${D[1] - 18} h18 v18' fill='none' stroke='#dc2626' stroke-width='3'/>`
      + T(A[0] - 8, A[1] - 10, 22, 'A', "font-weight='700'") + T(B[0] - 26, B[1] + 8, 22, 'B', "font-weight='700'") + T(C[0] + 8, C[1] + 8, 22, 'C', "font-weight='700'") + T(D[0] - 8, D[1] + 30, 22, 'D', "font-weight='700'")
      + T((B[0] + D[0]) / 2 - 20, B[1] + 30, 20, `p = ${p}`, "fill='#2563eb' font-weight='700'") + T((D[0] + C[0]) / 2 - 20, B[1] + 30, 20, `q = ${q}`, "fill='#2563eb' font-weight='700'")
      + T(D[0] + 10, (A[1] + D[1]) / 2, 22, 'h', "fill='#dc2626' font-weight='700'")
      + T(C[0] + 40, 80, 20, 'angle A = 90°', "fill='#334155'") + T(C[0] + 40, 120, 22, 'h² = p × q', "font-weight='700'")
      + T(C[0] + 40, 160, 22, `h² = ${p} × ${q} = ${p * q}`, "fill='#334155'") + T(C[0] + 40, 200, 24, `h = ${h} cm`, "fill='#dc2626' font-weight='700'"),
  };
};
const cube4 = cubeFace(4, 'one'), cube5 = cubeFace(5, 'none');
Object.assign(figures, {
  'sol_m02_q28_path.png': {
    w: 520, h: 300,
    svg: arrowDefs + seg(150, 50, 150, 230) + seg(150, 230, 258, 230) + seg(258, 230, 258, 158) + seg(258, 158, 155, 158)
      + L(150, 50, 150, 158, "stroke='#dc2626' stroke-width='3' stroke-dasharray='8 6'")
      + `<circle cx='150' cy='50' r='8' fill='#16a34a'/>` + T(80, 46, 18, 'Start', "fill='#16a34a' font-weight='700'")
      + `<circle cx='150' cy='158' r='8' fill='#dc2626'/>` + T(92, 164, 18, 'End', "fill='#dc2626' font-weight='700'")
      + T(78, 205, 18, '10 m S') + T(178, 256, 18, '6 m E') + T(268, 200, 18, '4 m N') + T(180, 150, 18, '6 m W')
      + T(160, 108, 18, '10 − 4 = 6 m South', "fill='#dc2626' font-weight='700'") + compass(440, 70),
  },
  'sol_m02_q29_venn.png': {
    w: 600, h: 300,
    svg: `<circle cx='170' cy='150' r='95' fill='#bfdbfe' fill-opacity='0.6' stroke='#2563eb' stroke-width='3'/>`
      + `<circle cx='290' cy='150' r='80' fill='#fde68a' fill-opacity='0.55' stroke='#d97706' stroke-width='3'/>`
      + `<circle cx='480' cy='150' r='80' fill='#bbf7d0' fill-opacity='0.6' stroke='#16a34a' stroke-width='3'/>`
      + T(90, 45, 20, 'Poets', "fill='#2563eb' font-weight='700'") + T(270, 60, 20, 'Teachers', "fill='#d97706' font-weight='700'") + T(430, 60, 20, 'Scientists', "fill='#16a34a' font-weight='700'")
      + T(214, 158, 15, 'T+P', "fill='#0f172a' font-weight='700'") + T(20, 270, 17, 'Teachers who are poets can never be scientists => I follows', "fill='#334155'")
      + T(20, 293, 17, 'Scientists-teachers relation unknown => II does not follow', "fill='#334155'"),
  },
  'sol_cube_n4.png': {
    w: cube4.w, h: cube4.h,
    svg: cube4.g + T(cube4.tx, 70, 20, 'one face, n = 4', "fill='#334155'") + T(cube4.tx, 110, 20, 'orange = exactly 1 face painted', "fill='#d97706' font-weight='700'")
      + T(cube4.tx, 150, 20, 'per face: (n − 2)² = 2² = 4') + T(cube4.tx, 190, 22, '6 faces × 4 = 24', "font-weight='700'"),
  },
  'sol_cube_n5.png': {
    w: cube5.w, h: cube5.h,
    svg: cube5.g + T(cube5.tx, 70, 20, '125 = 5³  =>  n = 5', "fill='#334155'") + T(cube5.tx, 110, 20, 'red box: hidden inner core', "fill='#dc2626' font-weight='700'")
      + T(cube5.tx, 150, 20, 'core edge = n − 2 = 3') + T(cube5.tx, 190, 22, 'no paint: 3 × 3 × 3 = 27', "font-weight='700'"),
  },
  'sol_m02_q63_mirror.png': {
    w: 620, h: 300,
    svg: (() => {
      const row = (y, mx, ix, lab, col) => `<circle cx='60' cy='${y}' r='10' fill='#0f172a'/>` + T(50, y - 18, 18, 'O', "font-weight='700'")
        + L(mx, y - 40, mx, y + 40, `stroke='${col}' stroke-width='6'`) + T(mx - 14, y - 48, 18, lab, `fill='${col}' font-weight='700'`)
        + `<circle cx='${ix}' cy='${y}' r='10' fill='none' stroke='${col}' stroke-width='3' stroke-dasharray='4 3'/>` + T(ix - 8, y - 18, 18, 'I', `fill='${col}' font-weight='700'`)
        + L(60, y + 52, mx, y + 52, "stroke='#94a3b8' stroke-width='2'") + L(mx, y + 52, ix, y + 52, "stroke='#94a3b8' stroke-width='2'");
      return row(80, 200, 340, 'M1', '#2563eb') + T(110, 150, 16, 'd') + T(260, 150, 16, 'd')
        + row(220, 260, 460, 'M2', '#dc2626') + T(140, 290, 16, 'd + x') + T(340, 290, 16, 'd + x')
        + T(370, 70, 18, 'mirror moves x = 2 cm', "fill='#334155'") + T(370, 100, 18, 'image moves 2x = 4 cm', "fill='#dc2626' font-weight='700'");
    })(),
  },
  'sol_altitude_4_9.png': altitude(4, 9, 6, 26),
  'sol_altitude_16_25.png': altitude(16, 25, 20, 9),
  'sol_m03_q28_compass.png': {
    w: 460, h: 340,
    svg: arrowDefs + L(200, 40, 200, 280, "stroke='#94a3b8' stroke-width='2'") + L(80, 160, 320, 160, "stroke='#94a3b8' stroke-width='2'")
      + T(193, 32, 18, 'N', "font-weight='700' fill='#475569'") + T(193, 304, 18, 'S', "font-weight='700' fill='#475569'") + T(326, 166, 18, 'E', "font-weight='700' fill='#475569'") + T(56, 166, 18, 'W', "font-weight='700' fill='#475569'")
      + L(200, 160, 310, 160, "stroke='#0f172a' stroke-width='5'") + T(250, 150, 16, 'start E', "fill='#0f172a'")
      + seg(200, 160, 125, 235) + T(70, 262, 18, 'SW', "fill='#2563eb' font-weight='700'")
      + L(200, 160, 275, 235, "stroke='#dc2626' stroke-width='5'") + T(280, 262, 20, 'SE (final)', "fill='#dc2626' font-weight='700'")
      + arc(200, 160, 45, 225, 360, '#2563eb') + T(300, 205, 16, '135° clockwise', "fill='#2563eb' font-weight='700'")
      + arc(200, 160, 70, 225, 315, '#dc2626') + T(150, 320, 16, 'then 90° anticlockwise: SW -> SE', "fill='#dc2626'"),
  },
});

// ---- UP AT 2026 Mock Test-4 / 5 / 6 ----
Object.assign(figures, {
  'sol_altitude_25_36.png': altitude(25, 36, 30, 8),
  'sol_altitude_36_49.png': altitude(36, 49, 42, 6),
  'sol_altitude_1_4.png': altitude(1, 4, 2, 90),
  'sol_m04_q61_track.png': {
    w: 560, h: 330,
    svg: arrowDefs + `<circle cx='200' cy='160' r='120' fill='none' stroke='#cbd5e1' stroke-width='10'/>`
      + `<path d='M80,160 A120,120 0 0 1 320,160' fill='none' stroke='#2563eb' stroke-width='6' marker-end='url(#ah)'/>`
      + L(80, 160, 320, 160, "stroke='#dc2626' stroke-width='4' stroke-dasharray='10 6'")
      + `<circle cx='80' cy='160' r='8' fill='#16a34a'/><circle cx='320' cy='160' r='8' fill='#dc2626'/>`
      + T(30, 190, 18, 'Start (0 s)', "fill='#16a34a' font-weight='700'") + T(290, 190, 18, '20 s', "fill='#dc2626' font-weight='700'")
      + T(150, 30, 18, 'half lap', "fill='#2563eb' font-weight='700'") + T(170, 150, 18, '2r', "fill='#dc2626' font-weight='700'")
      + T(350, 120, 18, 'distance = pi r', "fill='#2563eb' font-weight='700'") + T(350, 160, 18, '(half circumference)', "fill='#2563eb'")
      + T(350, 210, 18, 'displacement = 2r', "fill='#dc2626' font-weight='700'") + T(350, 250, 18, '(diameter)', "fill='#dc2626'"),
  },
  'sol_m05_q30_die.png': {
    w: 620, h: 330,
    svg: die(40, 30, 110, 3, 1, 5, 'view 1') + die(330, 30, 110, 3, 2, 6, 'view 2')
      + T(40, 315, 20, 'adjacent to 3: 1, 5, 2, 6  =>  opposite of 3 = 4', "font-weight='700' fill='#dc2626'"),
  },
  'sol_m05_q61_line.png': {
    w: 620, h: 260,
    svg: arrowDefs + L(30, 200, 590, 200, "stroke='#94a3b8' stroke-width='2'")
      + [0, 4, 10, 12].map((v) => L(50 + v * 40, 192, 50 + v * 40, 208, "stroke='#0f172a' stroke-width='2'") + T(42 + v * 40, 232, 16, `${v} m`)).join('')
      + seg(50, 60, 450, 60) + T(200, 50, 18, '10 m E') + seg(450, 100, 210, 100) + T(300, 92, 18, '6 m W') + seg(210, 140, 530, 140) + T(330, 132, 18, '8 m E')
      + L(50, 180, 530, 180, "stroke='#dc2626' stroke-width='4'") + T(150, 175, 17, 'displacement 12 m E', "fill='#dc2626' font-weight='700'")
      + T(380, 252, 17, 'distance = 10+6+8 = 24 m', "fill='#2563eb' font-weight='700'"),
  },
  'sol_m05_q64_echo.png': {
    w: 600, h: 260,
    svg: arrowDefs + `<circle cx='70' cy='90' r='16' fill='#0f172a'/>` + L(70, 106, 70, 160, "stroke='#0f172a' stroke-width='5'")
      + `<rect x='480' y='30' width='24' height='170' fill='#a8a29e'/>` + T(470, 225, 18, 'wall')
      + seg(100, 100, 470, 100) + T(230, 90, 18, 'go: d', "fill='#2563eb'")
      + L(470, 140, 100, 140, "stroke='#16a34a' stroke-width='4' marker-end='url(#ah)'") + T(220, 165, 18, 'return: d', "fill='#16a34a'")
      + T(60, 220, 20, '2d = v x t = 340 x 0.1 = 34 m', "font-weight='700'") + T(60, 250, 20, 'd = 17 m', "fill='#dc2626' font-weight='700'"),
  },
  'sol_m06_q28_clock.png': { w: 560, h: 320, svg: clock(3, 40, 'Angle = 130°') },
  'sol_m06_q30_books.png': {
    w: 600, h: 230,
    svg: ['P', 'Q', 'R', 'S', 'T'].map((b, i) => `<rect x='${40 + i * 108}' y='40' width='90' height='110' rx='8' fill='${['#bfdbfe', '#fde68a', '#e2e8f0', '#bbf7d0', '#fecaca'][i]}' stroke='#0f172a' stroke-width='3'/>`
      + T(72 + i * 108, 108, 34, b, "font-weight='700'") + T(78 + i * 108, 180, 18, `${i + 1}`, "fill='#475569'")).join('')
      + T(40, 215, 17, 'R = 3rd, T = 5th; PQ together only in 1-2 => Q is 2nd', "fill='#dc2626' font-weight='700'"),
  },
  'sol_m06_q61_vt.png': {
    w: 560, h: 320,
    svg: L(60, 260, 520, 260) + L(60, 260, 60, 30)
      + `<polygon points='60,260 60,120 260,120 420,260' fill='#93c5fd' fill-opacity='0.6' stroke='#2563eb' stroke-width='4'/>`
      + T(20, 40, 18, 'v') + T(510, 290, 18, 't') + T(170, 210, 22, 'area = displacement', "fill='#1e3a8a' font-weight='700'")
      + T(300, 60, 17, 'slope = acceleration', "fill='#475569'"),
  },
});

for (const [name, f] of Object.entries(figures)) {
  const svg = `<svg xmlns='http://www.w3.org/2000/svg' width='${f.w}' height='${f.h}' viewBox='0 0 ${f.w} ${f.h}'><rect width='100%' height='100%' fill='#fff'/>${f.svg}</svg>`;
  await sharp(Buffer.from(svg)).png({ palette: true }).toFile(OUT + name);
  console.log('wrote', OUT + name);
}
