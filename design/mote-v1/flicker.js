// Mote wordmark lightbulb flicker (rev 7). Reference implementation of tokens.json components.wordmark.flicker.
// Deterministic: same seed -> same schedule. The app must port this exactly (mulberry32 + the dip curve below).
(function () {
  var el = document.querySelector('.wordmark.bulb');
  if (!el || (window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches)) return;
  var glow = el.querySelector('.wm-glow'), core = el.querySelector('.wm-core'), spill = el.querySelector('.wm-spill');
  function mulberry32(a) { return function () { a |= 0; a = a + 0x6D2B79F5 | 0; var t = Math.imul(a ^ a >>> 15, 1 | a);
    t = t + Math.imul(t ^ t >>> 7, 61 | t) ^ t; return ((t ^ t >>> 14) >>> 0) / 4294967296; }; }
  var rnd = mulberry32(0x4D4F5445);                       // "MOTE"
  function easeOut(x) { return 1 - (1 - x) * (1 - x); }
  function easeInOut(x) { return x < .5 ? 4 * x * x * x : 1 - Math.pow(-2 * x + 2, 3) / 2; }
  function dip(t, depth) {                               // t in ms since dip start; 30ms fall, 80ms recover
    if (t < 30) return 1 - depth * easeOut(t / 30);
    if (t < 110) return 1 - depth * (1 - easeInOut((t - 30) / 80));
    return 1;
  }
  function apply(lum) {
    glow.style.opacity = lum; if (spill) spill.style.opacity = lum;
    core.style.opacity = 1 - 0.4 * (1 - lum);              // filament core dims less than the glow
  }
  function schedule() {
    var wait = 4000 + Math.floor(rnd() * 11001);           // 4-15 s
    var depth = 0.05 + rnd() * 0.15;                       // 5-20 %
    var dbl = rnd() < 0.3;                                 // 30 % double flicker
    setTimeout(function () {
      var t0 = performance.now(), total = dbl ? 110 + 60 + 110 : 110;
      (function frame(now) {
        var t = now - t0, lum = 1;
        if (t < 110) lum = dip(t, depth);
        else if (dbl && t >= 170 && t < 280) lum = dip(t - 170, depth * 0.6);
        apply(lum);
        if (t < total) requestAnimationFrame(frame); else { apply(1); schedule(); }
      })(t0);
    }, wait);
  }
  schedule();
})();
