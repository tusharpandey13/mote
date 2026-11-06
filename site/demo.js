// Mote demo: switches between design artboards (design/*.html, copied in by the Pages workflow),
// applies the Silver/Titanium finish and adds press feedback. No Bluetooth: nothing is sent.
(function () {
  var frame = document.getElementById('screen');
  var phone = document.getElementById('phone');
  var readout = document.getElementById('readout');
  var readoutT = document.getElementById('readout-t');
  var buttons = document.querySelectorAll('#screens button');
  var themeBtns = document.querySelectorAll('[data-theme]');
  var names = {};
  buttons.forEach(function (b) { names[b.dataset.s] = b.textContent; });

  var mode = 'media-light'; // last main mode, where sheets return to
  var MODES = { 'media-light': 1, pad: 1, 'pad-landscape': 1, keys: 1, macros: 1 };
  var dark = false;
  try { dark = localStorage.getItem('mote-theme') === 'dark'; } catch (e) {}

  // tapping these inside the phone navigates to another artboard
  var NAV = {
    'rail-media': 'media-light', 'rail-pad': 'pad', 'rail-keys': 'keys', 'rail-macros': 'macros',
    'connection-pill': 'device-picker', 'menu': 'menu-sheet', 'pair-new': 'pairing', 'pair-another': 'pairing',
    'macro-screenshot': 'macro-running', 'macros-edit': 'macro-editor', 'fav-edit': 'macro-editor',
    'scrim': '<', 'picker-close': '<', 'cancel-pairing': '<', 'back': '<', 'known-macbook': '<'
  };
  var PRESSABLE = '.raised,.key,.mod,.tile,.cap,.accent-cap,.seg,.fav,.icon-btn,.pill,.kc,.side,g[data-dc-id^="dpad-"],[data-dc-id="menu"],[data-dc-id="scrim"]';
  var CSS =
    PRESSABLE + '{cursor:pointer;transition:transform 80ms cubic-bezier(.2,0,0,1),filter 80ms cubic-bezier(.2,0,0,1)}' +
    '.demo-press:not(g):not(.seg):not([data-dc-id="scrim"]){transform:translateY(1px) scale(.97);filter:brightness(.93);box-shadow:inset 0 2px 4px rgba(0,0,0,.3),inset 0 -1px 0 var(--hi)!important}' +
    '.accent-cap.demo-press{filter:brightness(.82)}' +
    'g.demo-press{filter:brightness(.9)}g.demo-press .tri,g.demo-press .ok{fill:var(--accent)}' +
    '*{-webkit-tap-highlight-color:transparent;user-select:none}';

  function human(id) { return id.replace(/^(mod|key|rail|macro|fav|menu|known)-/, '').replace(/-/g, ' ').replace(/^./, function (c) { return c.toUpperCase(); }); }
  function label(el) {
    var id = el.getAttribute('data-dc-id');
    if (id) return human(id);
    var t = (el.textContent || '').replace(/\s+/g, ' ').trim();
    return t ? t.slice(0, 24) : 'Control';
  }

  function show(name, push) {
    if (!names[name]) name = 'media-light';
    if (MODES[name]) mode = name;
    phone.classList.toggle('land', name === 'pad-landscape');
    buttons.forEach(function (b) { b.setAttribute('aria-pressed', String(b.dataset.s === name)); });
    if (frame.getAttribute('src') !== 'design/' + name + '.html') frame.setAttribute('src', 'design/' + name + '.html');
    frame.title = 'Mote: ' + names[name] + ' screen';
    if (push !== false) history.replaceState(null, '', '#' + name);
  }

  function applyTheme() {
    document.documentElement.classList.toggle('dark', dark);
    themeBtns.forEach(function (b) { b.setAttribute('aria-pressed', String((b.dataset.theme === 'dark') === dark)); });
    try { frame.contentDocument.documentElement.classList.toggle('dark', dark); } catch (e) {}
    try { localStorage.setItem('mote-theme', dark ? 'dark' : 'light'); } catch (e) {}
  }

  frame.addEventListener('load', function () {
    var doc;
    try { doc = frame.contentDocument; } catch (e) { return; }
    if (!doc || !doc.body) return;
    var st = doc.createElement('style'); st.textContent = CSS; doc.head.appendChild(st);
    doc.documentElement.classList.toggle('dark', dark);
    var held = null;
    function release() { if (held) { held.classList.remove('demo-press'); held = null; } }
    doc.addEventListener('pointerdown', function (e) {
      var el = e.target.closest(PRESSABLE);
      if (!el || el.classList.contains('disabled')) return;
      held = el; el.classList.add('demo-press');
      readout.classList.add('hit');
      readoutT.innerHTML = '';
      var b = document.createElement('b'); b.textContent = label(el);
      readoutT.append('Pressed ', b, '. Nothing sent.');
    });
    doc.addEventListener('pointerup', release);
    doc.addEventListener('pointercancel', release);
    doc.addEventListener('pointerleave', release);
    doc.addEventListener('click', function (e) {
      var mod = e.target.closest('.mod');
      if (mod && !mod.classList.contains('disabled')) { // sticky modifier: idle > armed > locked > idle
        if (mod.classList.contains('locked')) mod.classList.remove('locked');
        else if (mod.classList.contains('armed')) { mod.classList.remove('armed'); mod.classList.add('locked'); }
        else mod.classList.add('armed');
        return;
      }
      if (e.target.closest('[data-dc-id="menu-theme"]')) { dark = !dark; applyTheme(); return; }
      var n = e.target.closest('[data-dc-id]');
      while (n) {
        var to = NAV[n.getAttribute('data-dc-id')];
        if (to) { show(to === '<' ? mode : to); return; }
        n = n.parentElement && n.parentElement.closest('[data-dc-id]');
      }
    });
  });

  buttons.forEach(function (b) { b.addEventListener('click', function () { show(b.dataset.s); }); });
  themeBtns.forEach(function (b) { b.addEventListener('click', function () { dark = b.dataset.theme === 'dark'; applyTheme(); }); });

  applyTheme();
  show(location.hash.slice(1) || 'media-light', false);
})();
