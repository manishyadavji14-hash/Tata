#!/usr/bin/env node
// Build compact SVG map data (web/js/mapdata.js) for the concept-note maps.
// Source: Natural Earth (public domain) — https://www.naturalearthdata.com
//   ne_10m_admin_0_countries_ind  (India's official point of view boundaries)
//   ne_10m_admin_1_states_provinces (states / UTs)
//   ne_10m_rivers_lake_centerlines
// Usage: node tools/make-maps.mjs <dir with the .geojson files>
import fs from 'node:fs';
import path from 'node:path';

const dir = process.argv[2];
if (!dir) { console.error('usage: make-maps.mjs <geojson dir>'); process.exit(1); }
const load = (f) => JSON.parse(fs.readFileSync(path.join(dir, f), 'utf8'));

// ---------- geometry helpers ----------
function simplify(pts, tol) { // Douglas–Peucker on projected points
  if (pts.length < 3) return pts;
  const keep = new Uint8Array(pts.length); keep[0] = keep[pts.length - 1] = 1;
  const stack = [[0, pts.length - 1]];
  const t2 = tol * tol;
  while (stack.length) {
    const [a, b] = stack.pop();
    const [x1, y1] = pts[a], [x2, y2] = pts[b];
    const dx = x2 - x1, dy = y2 - y1, L = dx * dx + dy * dy;
    let md = -1, mi = -1;
    for (let i = a + 1; i < b; i++) {
      const [x, y] = pts[i];
      let d;
      if (!L) d = (x - x1) ** 2 + (y - y1) ** 2;
      else { const t = Math.max(0, Math.min(1, ((x - x1) * dx + (y - y1) * dy) / L)); d = (x - x1 - t * dx) ** 2 + (y - y1 - t * dy) ** 2; }
      if (d > md) { md = d; mi = i; }
    }
    if (md > t2) { keep[mi] = 1; stack.push([a, mi], [mi, b]); }
  }
  return pts.filter((_, i) => keep[i]);
}
const area = (r) => { let s = 0; for (let i = 0, j = r.length - 1; i < r.length; j = i++) s += (r[j][0] + r[i][0]) * (r[j][1] - r[i][1]); return Math.abs(s / 2); };
function centroid(r) {
  let a = 0, cx = 0, cy = 0;
  for (let i = 0, j = r.length - 1; i < r.length; j = i++) {
    const f = r[j][0] * r[i][1] - r[i][0] * r[j][1];
    a += f; cx += (r[j][0] + r[i][0]) * f; cy += (r[j][1] + r[i][1]) * f;
  }
  if (!a) return r[0];
  return [cx / (3 * a), cy / (3 * a)];
}
const polysOf = (g) => (g.type === 'Polygon' ? [g.coordinates] : g.type === 'MultiPolygon' ? g.coordinates : []);
const linesOf = (g) => (g.type === 'LineString' ? [g.coordinates] : g.type === 'MultiLineString' ? g.coordinates : []);
const r1 = (v) => Math.round(v * 10) / 10;

function pathOf(geom, proj, tol, minArea, bbox) {
  let d = '', best = null, bestA = 0;
  for (const poly of polysOf(geom)) {
    poly.forEach((ring, ri) => {
      let pts = ring.map(([lon, lat]) => proj(lon, lat));
      if (bbox && !pts.some(([x, y]) => x >= bbox[0] && x <= bbox[2] && y >= bbox[1] && y <= bbox[3])) return;
      const A = area(pts);
      if (A < minArea) return;
      pts = simplify(pts, tol);
      if (pts.length < 3) return;
      if (ri === 0 && A > bestA) { bestA = A; best = pts; }
      d += 'M' + pts.map(([x, y]) => `${r1(x)} ${r1(y)}`).join('L') + 'Z';
    });
  }
  const c = best ? centroid(best) : null;
  return { d, c: c ? [r1(c[0]), r1(c[1])] : null };
}
function linePath(geom, proj, tol) {
  let d = '';
  for (const line of linesOf(geom)) {
    const pts = simplify(line.map(([lon, lat]) => proj(lon, lat)), tol);
    if (pts.length < 2) continue;
    d += 'M' + pts.map(([x, y]) => `${r1(x)} ${r1(y)}`).join('L');
  }
  return d;
}

const countries = load('ne_10m_admin_0_countries_ind.geojson').features;
const states = load('ne_10m_admin_1_states_provinces.geojson').features.filter((f) => f.properties.adm0_a3 === 'IND');
const rivers = load('ne_10m_rivers_lake_centerlines.geojson').features;

// ---------- India ----------
const IK = Math.cos((22 * Math.PI) / 180), IS = 20;
const IND = { lon0: 66, lat0: 38, kx: IK * IS, ky: IS };
const pI = (lon, lat) => [(lon - IND.lon0) * IND.kx, (IND.lat0 - lat) * IND.ky];
const ibox = [0, 0, (99 - IND.lon0) * IND.kx, (IND.lat0 - 5.5) * IND.ky];

const STATE_SHORT = { 'Dadra and Nagar Haveli and Daman and Diu': 'दादरा-दमन', 'Andaman and Nicobar': 'अंडमान-निकोबार' };
const india = {
  vb: [0, 0, r1(ibox[2]), r1(ibox[3])],
  proj: IND,
  neighbours: countries.filter((f) => f.properties.ADM0_A3 !== 'IND').map((f) => {
    const { d, c } = pathOf(f.geometry, pI, 1.2, 4, ibox);
    return d ? { en: f.properties.NAME_EN || f.properties.NAME, hi: f.properties.NAME_HI || '', d, c } : null;
  }).filter(Boolean),
  outline: pathOf(countries.find((f) => f.properties.ADM0_A3 === 'IND').geometry, pI, 0.6, 0.05).d,
  states: states.map((f) => {
    const { d, c } = pathOf(f.geometry, pI, 0.6, 0.05);
    const en = f.properties.name;
    return { en, hi: (f.properties.name_hi || en).replace('गोआ', 'गोवा').replace('उत्तराखण्ड', 'उत्तराखंड').replace('झारखण्ड', 'झारखंड').replace('नागालैण्ड', 'नागालैंड').replace('चण्डीगढ़', 'चंडीगढ़').replace('तमिल नाडु', 'तमिलनाडु').replace('आन्ध्र प्रदेश', 'आंध्र प्रदेश'), short: STATE_SHORT[en] || '', d, c };
  }),
};

// Rivers: Natural Earth name → Hindi
const RIVER_HI = {
  Ganges: 'गंगा', Yamuna: 'यमुना', Brahmaputra: 'ब्रह्मपुत्र', Dihang: 'ब्रह्मपुत्र', Yarlung: 'ब्रह्मपुत्र', Luhit: 'लोहित', Indus: 'सिंधु', Shiquan: 'सिंधु',
  Sutlej: 'सतलुज', Beas: 'ब्यास', Ravi: 'रावी', Chenab: 'चिनाब', Jhelum: 'झेलम', 'Ghäghara': 'घाघरा', Gandak: 'गंडक', Son: 'सोन', Betwa: 'बेतवा',
  Chambal: 'चंबल', Tista: 'तीस्ता', 'Godävari': 'गोदावरी', Krishna: 'कृष्णा', Cauvery: 'कावेरी', Kolidam: 'कावेरी', Narmada: 'नर्मदा', Tapi: 'ताप्ती',
  'Mahäna Nadï': 'महानदी', Mahi: 'माही', Penner: 'पेन्नार', Bhima: 'भीमा', Tungabhadra: 'तुंगभद्रा', Indravati: 'इंद्रावती', Wainganga: 'वैनगंगा',
  Brahmani: 'ब्राह्मणी', Banas: 'बनास', Palar: 'पालार', Parbati: 'पार्वती', Sankh: 'शंख', Tel: 'तेल', Kosi: 'कोसी', Sapt: 'कोसी', Damodar: 'दामोदर', Dam: 'दामोदर',
};
const riv = new Map();
for (const f of rivers) {
  const hi = RIVER_HI[f.properties.name];
  if (!hi) continue;
  const d = linePath(f.geometry, pI, 0.5);
  if (!d) continue;
  riv.set(hi, (riv.get(hi) || '') + d);
}
india.rivers = [...riv.entries()].map(([hi, d]) => ({ hi, d }));

// ---------- World ----------
const WS = 2;
const WLD = { lon0: -180, lat0: 84, kx: WS, ky: WS };
const pW = (lon, lat) => [(lon - WLD.lon0) * WLD.kx, (WLD.lat0 - Math.max(lat, -58)) * WLD.ky];
const world = {
  vb: [0, 0, 360 * WS, (84 + 58) * WS],
  proj: WLD,
  countries: countries.map((f) => {
    const { d, c } = pathOf(f.geometry, pW, 0.45, 0.25);
    const p = f.properties;
    return d ? { en: p.NAME_EN || p.NAME, hi: p.NAME_HI || '', cont: p.CONTINENT, d, c } : null;
  }).filter(Boolean),
};

const out = `// Generated by tools/make-maps.mjs from Natural Earth (public domain, naturalearthdata.com). India boundaries: official Indian point of view.\nexport const MAPS = ${JSON.stringify({ india, world })};\n`;
fs.writeFileSync('web/js/mapdata.js', out);
console.log('web/js/mapdata.js', out.length, 'bytes', 'states', india.states.length, 'rivers', india.rivers.length, 'countries', world.countries.length);
