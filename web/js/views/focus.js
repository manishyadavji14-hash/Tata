// Focus mode: full-screen, one concept at a time, big text, swipe / buttons, mark learned, star.
import { sourceSets, buildTree, progressMap, setProgress, conceptPlainText } from '../concepts.js';
import { conceptBody } from './concepts.js';
import { toast } from '../ui.js';
import { esc } from '../util.js';

const FS_KEY = 'abhyas-focus-font';
const SIZES = [15, 17, 19, 21, 24, 27];

export async function render({ root, query, settings, navigate }) {
  const src = query.src || 'all';
  const mode = query.mode || 'all'; // all | unlearned | star
  const [sets, prog] = await Promise.all([sourceSets(src, settings.lang || 'both'), progressMap()]);
  let tree = buildTree(sets);
  if (query.subj) tree = tree.filter((s) => s.key === query.subj);
  let list = tree.flatMap((s) => s.topics.filter((t) => !query.topic || t.name === query.topic).flatMap((t) => t.concepts.map((c) => ({ c, s, t }))));
  if (query.q) {
    const words = String(query.q).toLowerCase().split(/\s+/).filter(Boolean);
    list = list.filter((x) => { const h = conceptPlainText(x.c).toLowerCase(); return words.every((w) => h.includes(w)); });
  }
  const back = () => (history.length > 1 ? history.back() : navigate('concepts'));
  const base = list;
  if (mode === 'unlearned') list = list.filter((x) => !prog.get(x.c.id)?.learned);
  if (mode === 'star') list = list.filter((x) => prog.get(x.c.id)?.star);

  let fs = Number(localStorage.getItem(FS_KEY)) || 1;
  let i = Math.max(0, list.findIndex((x) => x.c.id === query.cid));
  let learnedNow = 0;

  root.innerHTML = `<div class="focus-wrap flex min-h-screen flex-col bg-amber-50/40 dark:bg-slate-950" data-focus>
    <div class="safe-top sticky top-0 z-10 border-b border-slate-200 bg-white/95 px-3 py-2 backdrop-blur dark:border-slate-800 dark:bg-slate-900/95">
      <div class="flex items-center gap-2">
        <button class="btn-ghost !px-2" data-x title="बंद करें">✕</button>
        <div class="min-w-0 flex-1"><div class="truncate text-sm font-bold" data-crumb></div><div class="text-[11px] text-slate-500" data-count></div></div>
        <button class="btn-ghost !px-2 text-sm" data-fs="-1" title="छोटा">A−</button>
        <button class="btn-ghost !px-2 text-base font-bold" data-fs="1" title="बड़ा">A+</button>
      </div>
      <div class="bar mt-2 !h-1.5"><span class="bg-brand-600 transition-all" data-pbar></span></div>
    </div>
    <div class="mx-auto w-full max-w-3xl flex-1 px-4 py-4" data-stage></div>
    <div class="safe-bottom sticky bottom-0 border-t border-slate-200 bg-white/95 px-3 py-2 backdrop-blur dark:border-slate-800 dark:bg-slate-900/95">
      <div class="mx-auto grid max-w-3xl grid-cols-4 gap-2">
        <button class="btn-outline" data-prev>◀</button>
        <button class="btn-outline" data-star>☆</button>
        <button class="btn-success" data-learn>✓ याद</button>
        <button class="btn-primary" data-next>▶</button>
      </div>
    </div>
  </div>`;
  const $ = (s) => root.querySelector(s);
  const stage = $('[data-stage]');

  if (!list.length) {
    $('[data-crumb]').textContent = 'फोकस मोड';
    stage.innerHTML = `<div class="mt-10 text-center"><div class="text-5xl">🎉</div><div class="mt-3 text-lg font-bold">${base.length ? (mode === 'star' ? 'कोई स्टार वाला कॉन्सेप्ट नहीं' : 'सब याद हो गए!') : 'यहाँ कोई कॉन्सेप्ट नहीं'}</div>
      <div class="mt-1 text-sm text-slate-500">${base.length && mode !== 'all' ? 'सभी कॉन्सेप्ट दोबारा पढ़ने के लिए नीचे दबाएँ।' : ''}</div>
      ${base.length && mode !== 'all' ? `<a class="btn-primary mt-4" href="#/focus?${new URLSearchParams({ ...query, mode: 'all' })}">📖 सभी ${base.length} दोबारा पढ़ें</a>` : ''}</div>`;
    root.querySelector('.safe-bottom').classList.add('hidden');
    $('[data-x]').onclick = back;
    return;
  }

  const show = () => {
    const { c, s, t } = list[i];
    const p = prog.get(c.id) || {};
    $('[data-crumb]').textContent = `${s.icon} ${s.label} › ${t.name}`;
    $('[data-count]').textContent = `${i + 1} / ${list.length}${mode === 'unlearned' ? ' • केवल न याद हुए' : mode === 'star' ? ' • ⭐ स्टार' : ''}`;
    $('[data-pbar]').style.width = `${Math.round(((i + 1) / list.length) * 100)}%`;
    stage.innerHTML = `<article class="focus-card rounded-2xl border border-slate-200 bg-white p-4 shadow-sm dark:border-slate-800 dark:bg-slate-900" style="font-size:${SIZES[fs]}px">
      <h2 class="mb-3 font-bold leading-snug text-brand-700 dark:text-brand-300" style="font-size:1.2em">${p.learned ? '✅ ' : ''}${esc(c.title)}</h2>
      <div class="cbody focus-body">${conceptBody(c)}</div>
      ${c.ref ? `<div class="mt-3 text-[12px] text-slate-400">${esc(c.ref.setTitle)} • Q${c.ref.q_no}</div>` : c.setTitle ? `<div class="mt-3 text-[12px] text-slate-400">${esc(c.setTitle)}</div>` : ''}
    </article>`;
    $('[data-star]').textContent = p.star ? '★' : '☆';
    $('[data-learn]').textContent = p.learned ? '↩ फिर पढ़ें' : '✓ याद';
    $('[data-prev]').disabled = i === 0;
    $('[data-next]').textContent = i === list.length - 1 ? '🏁 पूरा' : '▶';
    window.scrollTo(0, 0);
    setProgress(c.id, { views: (p.views || 0) + 1 }).then((r) => prog.set(c.id, r)).catch(() => {});
  };
  const finish = () => {
    const d = list.filter((x) => prog.get(x.c.id)?.learned).length;
    stage.innerHTML = `<div class="mt-8 text-center"><div class="text-5xl">🏁</div><div class="mt-3 text-xl font-bold">सत्र पूरा!</div>
      <div class="mt-2 text-sm text-slate-600 dark:text-slate-300">${list.length} कॉन्सेप्ट पढ़े • इस सत्र में ${learnedNow} याद हुए • कुल ✅ ${d}/${list.length}</div>
      <div class="mx-auto mt-5 grid max-w-xs gap-2">
        ${d < list.length ? `<a class="btn-primary" href="#/focus?${new URLSearchParams({ ...query, mode: 'unlearned' })}" data-again>🎯 जो याद नहीं हुए (${list.length - d}) फिर से</a>` : ''}
        <button class="btn-outline" data-restart>🔁 शुरू से दोबारा</button>
        <button class="btn-outline" data-x2>✕ बंद करें</button></div></div>`;
    root.querySelector('.safe-bottom').classList.add('hidden');
    $('[data-pbar]').style.width = '100%';
  };
  const go = (d) => {
    if (d > 0 && i === list.length - 1) { finish(); return; }
    i = Math.max(0, Math.min(list.length - 1, i + d));
    root.querySelector('.safe-bottom').classList.remove('hidden');
    show();
  };
  show();

  root.addEventListener('click', async (e) => {
    const t = (s) => e.target.closest(s);
    if (t('[data-x]') || t('[data-x2]')) { back(); return; }
    if (t('[data-again]')) { e.preventDefault(); navigate(`focus?${new URLSearchParams({ ...query, mode: 'unlearned' })}`, { replace: true }); return; }
    if (t('[data-restart]')) { i = 0; root.querySelector('.safe-bottom').classList.remove('hidden'); show(); return; }
    if (t('[data-prev]')) { go(-1); return; }
    if (t('[data-next]')) { go(1); return; }
    const f = t('[data-fs]');
    if (f) { fs = Math.max(0, Math.min(SIZES.length - 1, fs + Number(f.dataset.fs))); localStorage.setItem(FS_KEY, fs); show(); return; }
    if (t('[data-star]')) {
      const { c } = list[i];
      const r = await setProgress(c.id, { star: !prog.get(c.id)?.star });
      prog.set(c.id, r);
      $('[data-star]').textContent = r.star ? '★' : '☆';
      toast(r.star ? '⭐ स्टार किया' : 'स्टार हटाया', 'info', 1000);
      return;
    }
    if (t('[data-learn]')) {
      const { c } = list[i];
      const was = !!prog.get(c.id)?.learned;
      const r = await setProgress(c.id, { learned: !was });
      prog.set(c.id, r);
      if (!was) { learnedNow++; go(1); } else show();
    }
  });

  // swipe left/right
  let sx = null, sy = null;
  stage.addEventListener('touchstart', (e) => { const p = e.touches[0]; sx = p.clientX; sy = p.clientY; }, { passive: true });
  stage.addEventListener('touchend', (e) => {
    if (sx == null) return;
    const p = e.changedTouches[0];
    const dx = p.clientX - sx, dy = p.clientY - sy;
    sx = null;
    if (Math.abs(dx) > 70 && Math.abs(dx) > Math.abs(dy) * 1.5) go(dx < 0 ? 1 : -1);
  }, { passive: true });
  const onKey = (e) => {
    if (e.key === 'ArrowRight') go(1);
    else if (e.key === 'ArrowLeft') go(-1);
    else if (e.key === 'Escape') back();
  };
  document.addEventListener('keydown', onKey);
  // keep the screen on while revising (Android wrapper)
  try { window.AndroidBridge?.keepScreenOn?.(true); } catch { /* ignore */ }
  return () => { document.removeEventListener('keydown', onKey); try { window.AndroidBridge?.keepScreenOn?.(false); } catch { /* ignore */ } };
}
