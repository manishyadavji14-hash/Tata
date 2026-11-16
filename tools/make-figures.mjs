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

for (const [name, f] of Object.entries(figures)) {
  const svg = `<svg xmlns='http://www.w3.org/2000/svg' width='${f.w}' height='${f.h}' viewBox='0 0 ${f.w} ${f.h}'><rect width='100%' height='100%' fill='#fff'/>${f.svg}</svg>`;
  await sharp(Buffer.from(svg)).png({ palette: true }).toFile(OUT + name);
  console.log('wrote', OUT + name);
}
