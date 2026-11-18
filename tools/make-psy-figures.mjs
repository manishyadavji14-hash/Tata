// Draws the explanatory figures for the SUPER TET psychology notes (papers/concepts/supertet_psychology.txt)
// from the facts stated in the notes, and writes small webp files to papers/concepts/fig/.
// Needs a Devanagari font visible to fontconfig, e.g.
//   FONTCONFIG_FILE=/path/fonts.conf node tools/make-psy-figures.mjs
import sharp from 'sharp';

const OUT = 'papers/concepts/fig/';
const FONT = "font-family='Noto Sans Devanagari, Noto Sans, sans-serif'";
const T = (x, y, s, txt, o = '') => `<text x='${x}' y='${y}' ${FONT} font-size='${s}' ${o}>${txt}</text>`;
const svg = (w, h, body) => `<svg xmlns='http://www.w3.org/2000/svg' width='${w}' height='${h}' viewBox='0 0 ${w} ${h}'><rect width='100%' height='100%' fill='#fff'/>${body}</svg>`;

// one small learning-curve panel: axes + a path in local coordinates (0..1)
function panel(x0, y0, title, sub, pts, color) {
  const W = 300, H = 200, px = (u) => x0 + 50 + u * (W - 70), py = (v) => y0 + 30 + (1 - v) * (H - 70);
  const d = pts.map(([u, v], i) => `${i ? 'L' : 'M'}${px(u).toFixed(1)},${py(v).toFixed(1)}`).join(' ');
  return `<line x1='${px(0)}' y1='${py(0)}' x2='${px(1.02)}' y2='${py(0)}' stroke='#334155' stroke-width='2'/>
    <line x1='${px(0)}' y1='${py(0)}' x2='${px(0)}' y2='${py(1.08)}' stroke='#334155' stroke-width='2'/>
    <path d='${d}' fill='none' stroke='${color}' stroke-width='5' stroke-linecap='round'/>
    ${T(x0 + 50, y0 + 20, 19, title, "font-weight='700' fill='#0f172a'")}
    ${T(x0 + 50, y0 + H - 12, 15, sub, "fill='#475569'")}
    ${T(px(1) - 30, py(0) + 18, 13, 'समय', "fill='#64748b'")}
    ${T(x0 + 8, py(1) + 4, 13, 'मात्रा', "fill='#64748b'")}`;
}
const curve = (f, n = 40) => Array.from({ length: n + 1 }, (_, i) => [i / n, f(i / n)]);

const figures = {
  'psy_learning_curves.webp': svg(640, 430,
    panel(10, 0, '1. धनात्मक (Concave)', 'पहले धीमी, बाद में तीव्र', curve((u) => u * u), '#2563eb') +
    panel(330, 0, '2. ऋणात्मक (Convex)', 'पहले तीव्र, बाद में धीमी', curve((u) => 1 - (1 - u) * (1 - u)), '#dc2626') +
    panel(10, 215, '3. सरल रेखीय (Linear)', 'गति सदैव समान (दुर्लभ)', curve((u) => u), '#16a34a') +
    panel(330, 215, '4. S-आकार (मिश्रित)', 'धीमी - तेज - धीमी (सबसे सामान्य)', curve((u) => 1 / (1 + Math.exp(-10 * (u - 0.5)))), '#9333ea')),

  'psy_plateau.webp': svg(640, 300, (() => {
    const px = (u) => 70 + u * 520, py = (v) => 250 - v * 200;
    const pts = [[0, 0], [0.12, 0.25], [0.28, 0.48], [0.38, 0.55], [0.62, 0.56], [0.72, 0.7], [0.86, 0.88], [1, 0.95]];
    const d = pts.map(([u, v], i) => `${i ? 'L' : 'M'}${px(u)},${py(v)}`).join(' ');
    return `<line x1='70' y1='250' x2='600' y2='250' stroke='#334155' stroke-width='2'/><line x1='70' y1='250' x2='70' y2='30' stroke='#334155' stroke-width='2'/>
      <path d='${d}' fill='none' stroke='#2563eb' stroke-width='5' stroke-linejoin='round'/>
      <rect x='${px(0.38)}' y='${py(0.56) - 28}' width='215' height='24' fill='#fef3c7' stroke='#d97706' rx='5'/>
      ${T(px(0.40), py(0.56) - 12, 15, 'अधिगम पठार (प्रगति रुकी)', "fill='#92400e' font-weight='700'")}
      ${T(80, 25, 15, 'सीखने की मात्रा', "fill='#475569'")}${T(500, 275, 15, 'समय', "fill='#475569'")}
      ${T(150, 285, 14, 'पठार के बाद नई विधि/अभिप्रेरणा से फिर प्रगति', "fill='#475569'")}`;
  })()),

  'psy_freud_iceberg.webp': svg(560, 420, `
    <rect x='0' y='120' width='560' height='300' fill='#dbeafe'/>
    <line x1='0' y1='120' x2='560' y2='120' stroke='#2563eb' stroke-width='3' stroke-dasharray='10 6'/>
    <polygon points='230,30 330,30 350,120 210,120' fill='#f8fafc' stroke='#0f172a' stroke-width='3'/>
    <polygon points='210,120 350,120 380,160 180,160' fill='#e2e8f0' stroke='#0f172a' stroke-width='3'/>
    <polygon points='180,160 380,160 470,400 90,400' fill='#cbd5e1' stroke='#0f172a' stroke-width='3'/>
    ${T(238, 75, 17, 'चेतन मन', "font-weight='700'")}${T(240, 98, 14, 'लगभग 1/10 भाग')}
    ${T(222, 147, 15, 'अर्द्धचेतन मन', "font-weight='700'")}
    ${T(222, 270, 19, 'अचेतन मन', "font-weight='700'")}${T(178, 296, 14, 'लगभग 9/10 भाग – दमित इच्छाएँ, कुंठाएँ')}
    ${T(390, 60, 14, 'पानी के ऊपर =', "fill='#475569'")}${T(390, 80, 14, 'जो हमें ज्ञात है', "fill='#475569'")}
    ${T(10, 112, 14, 'जल-रेखा', "fill='#1d4ed8'")}${T(10, 20, 15, 'फ्रायड: मन के तीन स्तर (हिमशैल उपमा)', "font-weight='700'")}`),

  'psy_iq_curve.webp': svg(700, 330, (() => {
    // normal curve: x from 40 to 160 IQ, mean 100, sd 15
    const px = (iq) => 40 + (iq - 40) * 5, py = (y) => 250 - y * 190;
    const f = (iq) => Math.exp(-((iq - 100) ** 2) / (2 * 15 * 15));
    const path = (a, b) => { let d = `M${px(a)},${py(0)}`; for (let iq = a; iq <= b; iq += 1) d += ` L${px(iq)},${py(f(iq))}`; return d + ` L${px(b)},${py(0)} Z`; };
    const bands = [[40, 70, '#fecaca', '&lt;70', 'मंद'], [70, 80, '#fed7aa', '70-79', 'सीमांत'], [80, 90, '#fef08a', '80-89', 'निम्न'], [90, 110, '#bbf7d0', '90-109', 'सामान्य'],
      [110, 120, '#bfdbfe', '110-119', 'उच्च'], [120, 140, '#c7d2fe', '120-139', 'अति उच्च'], [140, 160, '#e9d5ff', '140+', 'प्रतिभाशाली']];
    return bands.map(([a, b, c]) => `<path d='${path(a, b)}' fill='${c}' stroke='#64748b' stroke-width='1'/>`).join('') +
      `<line x1='${px(40)}' y1='${py(0)}' x2='${px(160)}' y2='${py(0)}' stroke='#334155' stroke-width='2'/>` +
      bands.map(([a, b, , lab, name]) => T((px(a) + px(b)) / 2, 272, 12, lab, "text-anchor='middle' fill='#0f172a'") + T((px(a) + px(b)) / 2, 290, 12, name, "text-anchor='middle' fill='#475569'")).join('') +
      T(px(100), py(0.55), 18, '~50%', "text-anchor='middle' font-weight='700'") + T(px(100), 40, 16, 'बुद्धि-लब्धि (IQ) का वितरण', "text-anchor='middle' font-weight='700'") +
      T(px(100), 318, 13, 'IQ = (मानसिक आयु ÷ वास्तविक आयु) × 100  — अधिकांश लोग औसत, बहुत कम प्रतिभाशाली या मंद', "text-anchor='middle' fill='#475569'");
  })()),
};

for (const [file, s] of Object.entries(figures)) {
  await sharp(Buffer.from(s)).webp({ quality: 80 }).toFile(OUT + file);
  console.log('wrote', OUT + file);
}
