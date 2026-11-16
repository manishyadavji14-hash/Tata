// Applies the saved theme before first paint (avoids a white flash in dark mode).
(function () {
  try {
    var t = localStorage.getItem('abhyas-theme') || 'system';
    var dark = t === 'dark' || (t === 'system' && window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches);
    if (dark) document.documentElement.classList.add('dark');
  } catch (e) { /* storage unavailable */ }
})();
