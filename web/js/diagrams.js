// Renders diagram items (flow, cycle, pyramid, timeline, layers, rings, tree, venn, map, image) inside concept cards.
// Maps are drawn lazily: the card gets a placeholder and the (large) map data is imported only when one is shown.
import { esc, renderRich } from './util.js';
import { matchFeatures, canonName } from './diagram-spec.js';

const PAL = ['#f97316', '#22c55e', '#a855f7', '#ef4444', '#06b6d4', '#eab308', '#ec4899', '#84cc16'];
const head = (t, icon = '🖼') => (t ? `<div class="dg-title">${icon} ${renderRich(t)}</div>` : '');
const split2 = (s) => { const i = s.indexOf(':'); return i > 0 && i < 40 ? [s.slice(0, i), s.slice(i + 1)] : [s, '']; };

// Plain text wrapped into ≤ maxLines lines of ≈ width characters (for SVG labels).
function wrap(s, width = 14, maxLines = 3) {
  const words = String(s).replace(/\$[^$]*\$/g, (m) => m.replace(/\s/g, '\u00a0')).split(/\s+/);
  const out = [];
  let cur = '';
  for (const w of words) {
    if (cur && (cur + ' ' + w).length > width) { out.push(cur); cur = w; } else cur = cur ? `${cur} ${w}` : w;
  }
  if (cur) out.push(cur);
  if (out.length > maxLines) { const keep = out.slice(0, maxLines); keep[maxLines - 1] += '…'; return keep; }
  return out;
}
const plain = (s) => String(s).replace(/\$([^$]*)\$/g, '$1').replace(/\\[a-z]+/gi, '').replace(/[{}]/g, '');
const svgText = (x, y, s, { size = 11, width = 14, lines = 3, anchor = 'middle', cls = 'dg-svgtext', weight = '' } = {}) => {
  const ls = wrap(plain(s), width, lines);
  const y0 = y - ((ls.length - 1) * size * 1.15) / 2;
  return `<text x="${x}" y="${y0}" font-size="${size}" text-anchor="${anchor}" dominant-baseline="middle" class="${cls}" ${weight ? `font-weight="${weight}"` : ''}>${ls.map((l, i) => `<tspan x="${x}" dy="${i ? size * 1.15 : 0}">${esc(l)}</tspan>`).join('')}</text>`;
};

function flow(it) {
  return `<div class="dg">${head(it.title, '🔀')}<div class="dg-flow">${it.steps.map((s, i) => {
    const [a, b] = split2(s);
    return `${i ? '<div class="dg-arrow"><svg viewBox="0 0 12 14" width="12" height="14"><path d="M5 0h2v8h4l-5 6-5-6h4z" fill="currentColor"/></svg></div>' : ''}<div class="dg-step"><span class="dg-num">${i + 1}</span><div>${b ? `<b>${renderRich(a)}</b>:${renderRich(b)}` : renderRich(a)}</div></div>`;
  }).join('')}</div></div>`;
}

function timeline(it) {
  return `<div class="dg">${head(it.title, '🕰')}<div class="dg-tl">${it.rows.map(([a, b]) => `<div class="dg-tl-row"><div class="dg-tl-when">${renderRich(a)}</div><div class="dg-tl-what">${renderRich(b)}</div></div>`).join('')}</div></div>`;
}

function layers(it) {
  const n = it.rows.length;
  return `<div class="dg">${head(it.title, '🧱')}<div class="dg-layers">${it.rows.map(([a, b], i) => `<div class="dg-layer" style="--t:${n > 1 ? i / (n - 1) : 0}"><b>${renderRich(a)}</b>${b ? `<span>${renderRich(b)}</span>` : ''}</div>`).join('')}</div></div>`;
}

function rings(it) {
  const n = it.rows.length, R = 70;
  const circles = it.rows.map((_, i) => `<circle cx="75" cy="75" r="${R - (i * (R - 12)) / Math.max(1, n - 1)}" fill="${PAL[i % PAL.length]}" fill-opacity=".75" stroke="#fff" stroke-width="1.5"/>`).join('');
  return `<div class="dg">${head(it.title, '🎯')}<div class="dg-rings"><svg viewBox="0 0 150 150" width="150" height="150" role="img">${circles}</svg>
    <div class="dg-legend">${it.rows.map(([a, b], i) => `<div><span class="dg-sw" style="background:${PAL[i % PAL.length]}"></span><b>${renderRich(a)}</b>${b ? ` — ${renderRich(b)}` : ''}</div>`).join('')}</div></div></div>`;
}

function pyramid(it) {
  const n = it.levels.length, W = 320, h = 46, H = h * n;
  const wAt = (k) => W * (0.2 + 0.8 * (k / n)); // width at the k-th boundary (0 = top)
  let s = '';
  it.levels.forEach((lv, i) => {
    const top = wAt(i) / 2, bot = wAt(i + 1) / 2, y = i * h;
    s += `<polygon points="${W / 2 - top},${y} ${W / 2 + top},${y} ${W / 2 + bot},${y + h} ${W / 2 - bot},${y + h}" fill="${PAL[i % PAL.length]}" fill-opacity=".8" stroke="#fff" stroke-width="2"/>`;
    s += svgText(W / 2, y + h / 2, lv, { size: 11, width: Math.round(top / 3.4), lines: 3, cls: 'dg-svgtext-dark', weight: '600' });
  });
  return `<div class="dg">${head(it.title, '🔺')}<svg viewBox="0 0 ${W} ${H}" class="dg-svg" role="img">${s}</svg></div>`;
}

function cycle(it) {
  const n = it.steps.length, W = 340, H = 330, cx = W / 2, cy = H / 2, R = n <= 4 ? 105 : 118, bw = n <= 5 ? 112 : 96, bh = 46;
  const pos = it.steps.map((_, i) => { const a = -Math.PI / 2 + (2 * Math.PI * i) / n; return [cx + R * Math.cos(a), cy + R * Math.sin(a)]; });
  let arrows = '';
  for (let i = 0; i < n; i++) {
    const a1 = -Math.PI / 2 + (2 * Math.PI * (i + 0.3)) / n, a2 = -Math.PI / 2 + (2 * Math.PI * (i + 0.7)) / n;
    const r = R - 6;
    arrows += `<path d="M${cx + r * Math.cos(a1)} ${cy + r * Math.sin(a1)} A${r} ${r} 0 0 1 ${cx + r * Math.cos(a2)} ${cy + r * Math.sin(a2)}" fill="none" stroke="#1b5ef5" stroke-width="2" marker-end="url(#dgarr)"/>`;
  }
  const boxes = pos.map(([x, y], i) => `<rect x="${x - bw / 2}" y="${y - bh / 2}" width="${bw}" height="${bh}" rx="10" fill="${PAL[i % PAL.length]}" fill-opacity=".18" stroke="${PAL[i % PAL.length]}" stroke-width="1.5" class="dg-box"/>${svgText(x, y, it.steps[i], { size: 10.5, width: Math.round(bw / 6.5), lines: 3 })}`).join('');
  return `<div class="dg">${head(it.title, '🔄')}<svg viewBox="0 0 ${W} ${H}" class="dg-svg" role="img"><defs><marker id="dgarr" viewBox="0 0 10 10" refX="8" refY="5" markerWidth="6" markerHeight="6" orient="auto-start-reverse"><path d="M0 0L10 5L0 10z" fill="#1b5ef5"/></marker></defs>${arrows}${boxes}</svg></div>`;
}

function tree(it) {
  const node = (n, d) => `<li><div class="dg-node dg-d${Math.min(d, 3)}">${renderRich(n.text)}</div>${n.children?.length ? `<ul>${n.children.map((c) => node(c, d + 1)).join('')}</ul>` : ''}</li>`;
  return `<div class="dg">${head(it.title, '🧠')}<ul class="dg-tree">${node(it.node, 0)}</ul></div>`;
}

function venn(it) {
  const C = new Map();
  const add = (k, c) => { C.set(k, c); return c; };
  for (const [a, op, b] of it.rel) {
    const A = C.get(a), B = C.get(b);
    if (!A && !B) { add(a, { x: 0, y: 0, r: op === '<' ? 30 : 42 }); }
    const A1 = C.get(a), B1 = C.get(b);
    if (op === '<') {
      if (A1 && !B1) add(b, { x: A1.x + A1.r * 0.35, y: A1.y - A1.r * 0.25, r: A1.r * 1.9 });
      else if (!A1 && B1) { const k = [...C.values()].filter((c) => c.r < B1.r && Math.hypot(c.x - B1.x, c.y - B1.y) < B1.r).length; add(a, { x: B1.x + (k % 2 ? 1 : -1) * B1.r * 0.38, y: B1.y + B1.r * 0.25, r: B1.r * 0.42 }); }
    } else if (op === '&') {
      if (A1 && !B1) add(b, { x: A1.x + A1.r * 1.25, y: A1.y, r: A1.r });
      else if (!A1 && B1) add(a, { x: B1.x - B1.r * 1.25, y: B1.y, r: B1.r });
    } else if (op === 'x') {
      if (A1 && !B1) add(b, { x: A1.x + A1.r * 2.3 + 8, y: A1.y, r: Math.max(28, A1.r * 0.8) });
      else if (!A1 && B1) add(a, { x: B1.x - B1.r * 2.3 - 8, y: B1.y, r: Math.max(28, B1.r * 0.8) });
    }
  }
  const cs = [...C.entries()];
  let x0 = Infinity, y0 = Infinity, x1 = -Infinity, y1 = -Infinity;
  for (const [, c] of cs) { x0 = Math.min(x0, c.x - c.r); y0 = Math.min(y0, c.y - c.r); x1 = Math.max(x1, c.x + c.r); y1 = Math.max(y1, c.y + c.r); }
  const pad = 6;
  const body = cs.sort((p, q) => q[1].r - p[1].r).map(([k, c], i) => `<circle cx="${c.x}" cy="${c.y}" r="${c.r}" fill="${PAL[i % PAL.length]}" fill-opacity=".22" stroke="${PAL[i % PAL.length]}" stroke-width="1.6"/>${svgText(c.x, c.y - c.r * 0.62, k, { size: Math.max(8, Math.min(12, c.r / 3.2)), width: 10, lines: 2, weight: '700' })}`).join('');
  const legend = it.rel.map(([a, op, b]) => `${esc(a)} ${op === '<' ? '⊂' : op === '&' ? '∩' : '≠'} ${esc(b)}`).join(' • ');
  return `<div class="dg">${head(it.title, '⭕')}<svg viewBox="${x0 - pad} ${y0 - pad} ${x1 - x0 + 2 * pad} ${y1 - y0 + 2 * pad}" class="dg-svg dg-venn" role="img">${body}</svg><div class="dg-cap">${legend}</div></div>`;
}

function image(it) {
  return `<figure class="dg">${head(it.title, '🖼')}<img src="${esc(it.src)}" alt="${esc(it.title || 'चित्र')}" loading="lazy" class="dg-img"></figure>`;
}

function mapPlaceholder(it) {
  return `<div class="dg">${head(it.title, '🗺')}<div class="dmap" data-spec="${esc(JSON.stringify(it))}"><div class="dg-cap">मानचित्र लोड हो रहा है…</div></div></div>`;
}

export function diagramHtml(it) {
  try {
    switch (it.type) {
      case 'flow': return flow(it);
      case 'cycle': return cycle(it);
      case 'pyramid': return pyramid(it);
      case 'timeline': return timeline(it);
      case 'layers': return layers(it);
      case 'rings': return rings(it);
      case 'tree': return tree(it);
      case 'venn': return venn(it);
      case 'map': return mapPlaceholder(it);
      case 'image': return image(it);
      default: return '';
    }
  } catch (e) {
    console.warn('diagram', e);
    return '';
  }
}

// ---------- maps ----------
let mapsP = null;
const getMaps = () => (mapsP ||= import('./mapdata.js').then((m) => m.MAPS));

function bboxOfPath(d) {
  const n = d.match(/-?\d+(?:\.\d+)?/g)?.map(Number) || [];
  let x0 = Infinity, y0 = Infinity, x1 = -Infinity, y1 = -Infinity;
  for (let i = 0; i + 1 < n.length; i += 2) { x0 = Math.min(x0, n[i]); x1 = Math.max(x1, n[i]); y0 = Math.min(y0, n[i + 1]); y1 = Math.max(y1, n[i + 1]); }
  return [x0, y0, x1, y1];
}
function midOfPath(d) {
  const n = d.match(/-?\d+(?:\.\d+)?/g)?.map(Number) || [];
  const k = Math.floor(n.length / 4) * 2;
  return [n[k], n[k + 1]];
}
const grow = (b, x0, y0, x1, y1) => { b[0] = Math.min(b[0], x0); b[1] = Math.min(b[1], y0); b[2] = Math.max(b[2], x1); b[3] = Math.max(b[3], y1); };

export function renderMapSvg(M, it) {
  const world = it.region === 'world';
  const base = world ? M.world : M.india;
  const proj = (lat, lon) => [(lon - base.proj.lon0) * base.proj.kx, (base.proj.lat0 - lat) * base.proj.ky];
  const areas = world ? base.countries : base.states;
  const fill = new Map(), labelled = new Set(), rivers = [];
  const legend = [], missing = [];
  const focus = [Infinity, Infinity, -Infinity, -Infinity];
  let gi = 0;
  for (const g of it.groups) {
    const col = PAL[gi++ % PAL.length];
    let found = 0;
    for (const name of g.names) {
      let fs = [];
      if (g.kind === 'river') {
        if (world) continue;
        fs = /^(सभी|all)$/i.test(name) ? base.rivers : matchFeatures(base.rivers, name);
        for (const f of fs) { rivers.push({ f, col: g.label ? col : '#2563eb' }); grow(focus, ...bboxOfPath(f.d)); }
      } else if (g.kind === 'continent') {
        fs = world ? base.countries.filter((c) => canonName(c.cont) === canonName(name)) : [];
        for (const f of fs) { fill.set(f, col); grow(focus, ...bboxOfPath(f.d)); }
      } else {
        fs = matchFeatures(world ? base.countries : [...base.states, ...(base.neighbours || [])], name);
        for (const f of fs) { fill.set(f, col); labelled.add(f); grow(focus, ...bboxOfPath(f.d)); }
      }
      if (fs.length) found++; else missing.push(name);
    }
    if (found && (g.label || g.kind !== 'river')) legend.push({ col: g.kind === 'river' && !g.label ? '#2563eb' : col, text: g.label || g.names.join(', '), line: g.kind === 'river' });
  }
  const pts = it.points.map((p) => ({ ...p, xy: proj(p.lat, p.lon) }));
  for (const p of pts) grow(focus, p.xy[0], p.xy[1], p.xy[0], p.xy[1]);

  // view box
  let [vx, vy, vw, vh] = base.vb;
  if (it.region === 'up') {
    const up = base.states.find((s) => s.en === 'Uttar Pradesh');
    const b = bboxOfPath(up.d); grow(b, ...focus.map((v, i) => (Number.isFinite(v) ? v : b[i])));
    [vx, vy, vw, vh] = [b[0], b[1], b[2] - b[0], b[3] - b[1]];
    const m = Math.max(vw, vh) * 0.08; vx -= m; vy -= m; vw += 2 * m; vh += 2 * m;
  } else if (!world && Number.isFinite(focus[0])) {
    // zoom in when everything shown sits in a small part of India
    const w = focus[2] - focus[0], h = focus[3] - focus[1];
    if (w < base.vb[2] * 0.55 && h < base.vb[3] * 0.55) {
      const m = Math.max(w, h) * 0.18 + 12;
      let nw = Math.max(w + 2 * m, base.vb[2] * 0.3), nh = Math.max(h + 2 * m, base.vb[3] * 0.3);
      if (nw / nh > 1.4) nh = nw / 1.4;
      if (nh / nw > 1.25) nw = nh / 1.25;
      vx = (focus[0] + focus[2]) / 2 - nw / 2; vy = (focus[1] + focus[3]) / 2 - nh / 2; vw = nw; vh = nh;
    }
  } else if (world && Number.isFinite(focus[0])) {
    const w = focus[2] - focus[0], h = focus[3] - focus[1];
    if (w < base.vb[2] * 0.6) {
      const m = Math.max(w, h) * 0.25 + 10;
      vx = Math.max(0, focus[0] - m); vy = Math.max(0, focus[1] - m);
      vw = Math.min(base.vb[2] - vx, w + 2 * m); vh = Math.min(base.vb[3] - vy, h + 2 * m);
      if (vw / vh > 2.2) { const nh = vw / 2.2; vy = Math.max(0, vy - (nh - vh) / 2); vh = nh; }
      if (vh / vw > 1.3) { const nw = vh / 1.3; vx = Math.max(0, vx - (nw - vw) / 2); vw = nw; }
    }
  }
  const k = vw / (world ? 360 : 300); // label scale
  const fz = (s) => (s * k).toFixed(2);
  let s = `<rect x="${vx}" y="${vy}" width="${vw}" height="${vh}" class="map-sea"/>`;
  if (!world) {
    s += base.neighbours.map((n) => `<path d="${n.d}" class="map-nb" ${fill.has(n) ? `style="fill:${fill.get(n)};fill-opacity:.55"` : ''}/>`).join('');
    s += `<path d="${base.outline}" class="map-land"/>`;
    s += base.states.map((st) => `<path d="${st.d}" class="map-st" ${fill.has(st) ? `style="fill:${fill.get(st)};fill-opacity:.7"` : ''}/>`).join('');
    if (it.region === 'up') { const up = base.states.find((x) => x.en === 'Uttar Pradesh'); s += `<path d="${up.d}" class="map-up"/>`; }
    s += `<path d="${base.outline}" class="map-border"/>`;
  } else {
    s += base.countries.map((c) => `<path d="${c.d}" class="map-ct" ${fill.has(c) ? `style="fill:${fill.get(c)};fill-opacity:.75"` : ''}/>`).join('');
  }
  s += rivers.map(({ f, col }) => `<path d="${f.d}" class="map-river" style="stroke:${col}"/>`).join('');
  const halo = `class="map-label" paint-order="stroke"`;
  s += rivers.filter((r, i, a) => a.findIndex((x) => x.f === r.f) === i).map(({ f, col }) => { const [x, y] = midOfPath(f.d); return `<text x="${x}" y="${y}" font-size="${fz(7)}" ${halo} style="fill:${col}" stroke-width="${fz(2)}">${esc(f.hi)}</text>`; }).join('');
  s += [...labelled].filter((f) => f.c).map((f) => `<text x="${f.c[0]}" y="${f.c[1]}" font-size="${fz(world ? 6.5 : 7.5)}" text-anchor="middle" ${halo} stroke-width="${fz(2)}" font-weight="700">${esc(f.short || f.hi || f.en)}</text>`).join('');
  // point labels: write names next to the points unless they would overlap → then numbered markers + list below
  const fs7 = 7 * k;
  const boxes = pts.map((p) => { const w = [...p.name].length * fs7 * 0.5; return [p.xy[0] + 4 * k, p.xy[1] - fs7 * 0.9, p.xy[0] + 4 * k + w, p.xy[1] + fs7 * 0.3]; });
  const hit = (a, b) => a[0] < b[2] && b[0] < a[2] && a[1] < b[3] && b[1] < a[3];
  const crowded = pts.length > 14 || boxes.some((a, i) => boxes.some((b, j) => j > i && hit(a, b))) || boxes.some((b, i) => pts.some((p, j) => j !== i && p.xy[0] > b[0] && p.xy[0] < b[2] && p.xy[1] > b[1] && p.xy[1] < b[3]));
  if (!crowded) {
    s += pts.map((p) => `<circle cx="${p.xy[0].toFixed(1)}" cy="${p.xy[1].toFixed(1)}" r="${fz(2.6)}" class="map-pt"/><text x="${(p.xy[0] + 4 * k).toFixed(1)}" y="${(p.xy[1] + 2.3 * k).toFixed(1)}" font-size="${fz(7)}" ${halo} stroke-width="${fz(2)}">${esc(p.name)}</text>`).join('');
  } else {
    s += pts.map((p, i) => `<circle cx="${p.xy[0].toFixed(1)}" cy="${p.xy[1].toFixed(1)}" r="${fz(4.6)}" class="map-num"/><text x="${p.xy[0].toFixed(1)}" y="${(p.xy[1] + 2 * k).toFixed(1)}" font-size="${fz(i >= 9 ? 5.2 : 6)}" text-anchor="middle" class="map-numtext" font-weight="700">${i + 1}</text>`).join('');
  }
  const leg = legend.length ? `<div class="dg-legend dg-legend-row">${legend.map((l) => `<div><span class="dg-sw ${l.line ? 'dg-sw-line' : ''}" style="background:${l.col}"></span>${esc(l.text)}</div>`).join('')}</div>` : '';
  const list = crowded ? `<ol class="dg-ptlist">${pts.map((p, i) => `<li><span class="dg-ptn">${i + 1}</span>${esc(p.name)}</li>`).join('')}</ol>` : '';
  return { svg: `<svg viewBox="${vx.toFixed(1)} ${vy.toFixed(1)} ${vw.toFixed(1)} ${vh.toFixed(1)}" class="dg-map" role="img">${s}</svg>${leg}${list}`, missing };
}

async function hydrate(el) {
  el.dataset.done = '1';
  try {
    const it = JSON.parse(el.dataset.spec);
    const M = await getMaps();
    el.innerHTML = renderMapSvg(M, it).svg;
  } catch (e) {
    el.innerHTML = `<div class="dg-cap">मानचित्र नहीं बन सका</div>`;
    console.warn('map', e);
  }
}
const scan = (root) => root.querySelectorAll?.('.dmap:not([data-done])').forEach(hydrate);
if (typeof document !== 'undefined' && typeof MutationObserver !== 'undefined') {
  new MutationObserver((muts) => { for (const m of muts) for (const n of m.addedNodes) if (n.nodeType === 1) { if (n.matches?.('.dmap:not([data-done])')) hydrate(n); else scan(n); } })
    .observe(document.documentElement, { childList: true, subtree: true });
}
