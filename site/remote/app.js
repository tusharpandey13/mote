// Mote web remote UI. Consumes window.Mote (mote.js). Protocol: docs/protocol.md.
(() => {
  'use strict';
  const $ = (s, r) => (r || document).querySelector(s);
  const $$ = (s, r) => Array.from((r || document).querySelectorAll(s));
  const LS = {
    get(k, d) { try { const v = localStorage.getItem('mote.ui.' + k); return v === null ? d : v; } catch (e) { return d; } },
    set(k, v) { try { localStorage.setItem('mote.ui.' + k, v); } catch (e) { /* private mode */ } },
  };
  const buzz = (ms) => { if (navigator.vibrate) { try { navigator.vibrate(ms); } catch (e) { /* ignore */ } } };
  const CTRL = 1, SHIFT = 2, OPT = 4, CMD = 8;
  const HELPER_URL = 'http://<your-mac>.local:8790';

  const screen = $('#screen'), stage = $('#stage');

  // ---------------------------------------------------------------- theme
  const mq = matchMedia('(prefers-color-scheme: dark)');
  function applyTheme() {
    const t = LS.get('theme', 'system');
    const dark = t === 'dark' || (t === 'system' && mq.matches);
    document.documentElement.classList.toggle('dark', dark);
    $('meta[name=theme-color]').content = dark ? '#222428' : '#E3E5E8';
    $$('#s-theme [data-theme]').forEach((d) => { const on = d.dataset.theme === t; d.classList.toggle('on', on); d.classList.toggle('raised', on); });
  }
  if (mq.addEventListener) mq.addEventListener('change', applyTheme); else mq.addListener(applyTheme);
  $('#s-theme').addEventListener('click', (e) => { const d = e.target.closest('[data-theme]'); if (d) { LS.set('theme', d.dataset.theme); applyTheme(); } });

  // ---------------------------------------------------------------- connection state
  const M = window.Mote;
  const env = M ? M.env() : { wifi: false, ble: false, secure: false };
  let conn = null, kind = null, authed = false, everAuthed = false, host = '', want = false;
  let retryT = 0, backoff = 1000, ready = false, pendingSubmit = false, submitRetries = 0;
  let paused = false, ax = true, view = 'connect', mode = LS.get('mode', 'media');
  let autoCode = M && M.autoPairCode ? M.autoPairCode() : null;

  const send = (m) => { if (authed && conn) conn.send(m); };
  const key = (usage, mods) => send({ t: 'key', usage, mods: mods || 0 });
  const click = (b, count) => send({ t: 'button', b, action: 'click', count: count || 1 });

  function start(k) {
    stop();
    kind = k; want = true; ready = false; LS.set('kind', k);
    const c = conn = M.connect(k);
    c.on('state', (s) => { if (c === conn) onState(s); });
    c.on('paused', (p) => { if (c === conn) setPaused(p); });
  }
  function stop() {
    clearTimeout(retryT);
    if (conn) { const c = conn; conn = null; try { c.close(); } catch (e) { /* ignore */ } }
    setAuthed(false);
  }
  function scheduleRetry(ms) {
    clearTimeout(retryT);
    retryT = setTimeout(() => { if (want) start('wifi'); }, ms === undefined ? backoff : ms);
    backoff = Math.min(backoff * 2, 10000);
  }

  function onState(s) {
    if (s.host) host = s.host;
    switch (s.state) {
      case 'connecting':
        ready = false;
        setPill('amber', 'Connecting…');
        if (view === 'pair') break;
        if (everAuthed) showLost(); else showConnect(kind === 'ble' ? 'Connecting over Bluetooth…' : 'Connecting over Wi-Fi…', true);
        break;
      case 'need-pair':
        setAuthed(false); ready = true; backoff = 1000;
        setPill('amber', 'Not paired');
        showPair(s);
        break;
      case 'authed':
        backoff = 1000; ready = false; submitRetries = 0;
        setAuthed(true); setPaused(s); setPill('', '');
        show('main');
        break;
      case 'closed':
      case 'error':
        setAuthed(false); ready = false; setPill('red', 'Offline');
        if (s.reason === 'user') { if (view !== 'connect') showConnect(); break; }
        if (s.reason === 'rate') { showPair({}); pairMsg(rateText(s.retryAfter)); }
        else if (s.reason === 'cancelled') { want = false; showConnect('No Mac was chosen. Tap Bluetooth to try again.'); break; }
        if (kind === 'wifi') {
          scheduleRetry();
          if (view === 'pair') break;
          if (everAuthed) showLost(); else showConnect("Can't reach Mote Helper. Retrying…", true);
        } else if (view === 'pair') {
          // BLE needs a user gesture to reconnect; the Pair button does it.
        } else if (everAuthed) showLost();
        else showConnect('Bluetooth connection failed' + (s.reason && s.reason !== 'ble' ? ': ' + s.reason : '') + '. Tap Bluetooth to try again.');
        break;
    }
  }

  function setAuthed(v) {
    authed = v;
    if (v) everAuthed = true;
    stage.classList.toggle('offline', !v);
    if (!v) { releaseAll(); setLock(false, true); }
    updateLost();
  }
  function setPaused(p) {
    if (typeof p.paused === 'boolean') paused = p.paused;
    if (typeof p.ax === 'boolean') ax = p.ax;
    $('#notice').hidden = view !== 'main' || !(paused || !ax);
    $('#notice-tx').textContent = paused ? 'Input paused on the Mac' : 'Grant Accessibility to Mote Helper on the Mac';
    $('#notice-sub').textContent = paused ? 'Resume it from the Mote Helper menu.' : 'System Settings > Privacy & Security > Accessibility.';
  }
  function setPill(led, state) {
    $('#pill-led').className = 'led ' + led;
    $('#pill-name').textContent = host || 'Mote';
    $('#pill-state').textContent = state;
    $('#pill-state').hidden = !state;
    $('#s-sub').textContent = (host || 'Not connected') + (kind ? ' · ' + (kind === 'ble' ? 'Bluetooth' : 'Wi-Fi') : '');
  }

  // ---------------------------------------------------------------- views
  function show(v) {
    view = v;
    screen.dataset.view = v;
    $('#v-connect').hidden = v !== 'connect';
    $('#v-pair').hidden = v !== 'pair';
    $('#rail').hidden = v !== 'main';
    $$('.pane').forEach((p) => { p.hidden = v !== 'main' || p.dataset.mode !== mode; });
    setPaused({});
    updateLost();
  }
  function updateLost() {
    const vis = view === 'main' && !authed;
    $('#lost').hidden = !vis;
    if (!vis) return;
    const name = host || 'your Mac';
    const ble = kind === 'ble';
    $('#lost-title').textContent = ble ? 'Bluetooth connection lost' : 'Reconnecting to ' + name + '…';
    $('#lost-body').textContent = ble
      ? 'Tap Reconnect and choose ' + name + ' again. Nothing you press is sent until then.'
      : 'Mote reconnects on its own while it\'s open. Wake ' + name + ' or check the Wi-Fi. Nothing you press is sent until then.';
  }

  function showConnect(msg, busy) {
    show('connect');
    $('#c-status').textContent = msg || "Mote talks to the Mote Helper app in your Mac's menu bar.";
    $('#c-scan').classList.toggle('idle', !busy);
    $('#c-title').textContent = busy && host ? 'Connecting to ' + host : 'Connect to your Mac';
    const last = LS.get('kind', env.wifi ? 'wifi' : 'ble');
    [['wifi', '#c-wifi'], ['ble', '#c-ble']].forEach(([k, sel]) => {
      const b = $(sel), primary = k === last && env[k];
      b.disabled = !env[k];
      b.classList.toggle('accent-cap', primary); b.classList.toggle('raised', !primary);
      b.classList.toggle('btn-primary', primary); b.classList.toggle('btn-secondary', !primary);
    });
  }
  function initConnectNotes() {
    const wifiHow = 'on the same network, open ' + HELPER_URL + ' (the URL is in the Mote Helper menu)';
    $('#c-wifi-note').textContent = env.wifi
      ? 'Connects to the Mac that served this page.'
      : 'To use Wi-Fi, ' + wifiHow + '.';
    let ble = '';
    if (!env.ble && !env.secure) ble = 'Bluetooth needs the secure page: open https://tusharpandey13.github.io/mote/remote/ in Chrome. Wi-Fi works here.';
    else if (!env.ble) ble = "Web Bluetooth isn't available in this browser (iOS and Firefox don't support it). Use Wi-Fi: " + wifiHow + '.';
    else ble = 'Chrome will ask you to choose your Mac.';
    $('#c-ble-note').textContent = ble;
  }
  $('#c-wifi').addEventListener('click', () => start('wifi'));
  $('#c-ble').addEventListener('click', () => start('ble'));
  $('#lost-retry').addEventListener('click', () => start(kind || 'wifi'));
  $('#lost-opts').addEventListener('click', () => { want = false; stop(); everAuthed = false; showConnect(); });

  // ---------------------------------------------------------------- pairing
  const codeIn = $('#p-code'), nameIn = $('#p-name');
  function defaultName() {
    const ua = navigator.userAgent;
    const os = /Android/.test(ua) ? 'Android' : /iPhone|iPad/.test(ua) ? 'iPhone' : /Mac/.test(ua) ? 'Mac' : /Windows/.test(ua) ? 'Windows' : /Linux/.test(ua) ? 'Linux' : '';
    const br = /Firefox\//.test(ua) ? 'Firefox' : /Edg\//.test(ua) ? 'Edge' : /Chrome\//.test(ua) ? 'Chrome' : /Safari\//.test(ua) ? 'Safari' : 'Browser';
    return os ? br + ' on ' + os : 'Web remote';
  }
  function pairMsg(t, info) { const e = $('#p-err'); e.textContent = t || ''; e.classList.toggle('info', !!info); }
  const rateText = (s) => 'Too many attempts. Try again in ' + (s || 60) + 's.';
  function showPair(s) {
    if (view !== 'pair') pairMsg('');
    show('pair');
    $('#p-host').textContent = host || 'your Mac';
    if (s.pairing === false) $('#p-closed').hidden = false;
    else if (s.pairing === true) $('#p-closed').hidden = true;
    if (s.reason === 'auth' || s.reason === 'revoked') pairMsg("This Mac doesn't recognise this browser any more. Pair again.");
    if (autoCode) { codeIn.value = autoCode; autoCode = null; submitPair(); }
    else if (pendingSubmit && ready) submitPair();
  }
  function submitPair() {
    const code = codeIn.value.replace(/\D/g, '');
    codeIn.value = code;
    if (code.length !== 6) { pairMsg('Enter the 6-digit code shown on the Mac.'); return; }
    const name = (nameIn.value.trim() || defaultName()).slice(0, 64);
    LS.set('name', name);
    if (!ready || !conn) {
      pendingSubmit = true; pairMsg('Connecting…', true);
      if (!conn || kind === 'ble') start(kind || 'wifi');
      return;
    }
    pendingSubmit = false; ready = false;
    $('#p-go').disabled = true; pairMsg('Pairing…', true);
    conn.pair(code, name).then(() => {
      $('#p-go').disabled = false; codeIn.value = ''; pairMsg('');
    }).catch((e) => {
      $('#p-go').disabled = false;
      ready = !!conn;
      if (e.code === 'closed' && submitRetries++ < 1) { pendingSubmit = true; pairMsg('Reconnecting…', true); return; }
      if (e.code === 'code') pairMsg('Wrong code. Check the code on the Mac and try again.');
      else if (e.code === 'rate') pairMsg(rateText(e.retryAfter));
      else if (e.code === 'pairing-closed') { pairMsg('Pairing window closed: open Pair new device on the Mac, then try again.'); $('#p-closed').hidden = false; }
      else pairMsg('Pairing failed' + (e.message ? ': ' + e.message : '') + '.');
      codeIn.focus();
    });
  }
  $('#p-form').addEventListener('submit', (e) => { e.preventDefault(); submitRetries = 0; submitPair(); });
  $('#p-back').addEventListener('click', () => { want = false; stop(); showConnect(); });

  // ---------------------------------------------------------------- menu sheet
  function sheet(open) { $('#scrim').hidden = !open; $('#sheet').hidden = !open; }
  $('#pill').addEventListener('click', () => sheet(true));
  $('#menu-btn').addEventListener('click', () => sheet(true));
  $('#scrim').addEventListener('click', () => sheet(false));
  $('#s-close').addEventListener('click', () => sheet(false));
  $('#s-disc').addEventListener('click', () => { sheet(false); want = false; everAuthed = false; stop(); showConnect('Disconnected.'); });
  $('#s-forget').addEventListener('click', () => {
    sheet(false); want = false; everAuthed = false; stop();
    M.forget('wifi'); M.forget('ble'); host = ''; setPill('red', 'Not connected');
    showConnect('This browser no longer has a pairing for the Mac. Connect to pair again.');
  });

  // ---------------------------------------------------------------- modes
  function setMode(m) {
    mode = m; LS.set('mode', m);
    $$('#rail .seg').forEach((s) => { const on = s.dataset.mode === m; s.classList.toggle('on', on); s.classList.toggle('raised', on); });
    $$('.pane').forEach((p) => { p.hidden = view !== 'main' || p.dataset.mode !== m; });
  }
  $('#rail').addEventListener('click', (e) => { const s = e.target.closest('[data-mode]'); if (s && s.dataset.mode !== mode) { buzz(8); setMode(s.dataset.mode); } });

  // ---------------------------------------------------------------- buttons: press, auto-repeat
  const active = new Map(); // pointerId -> {el, t1, t2, mods}
  function fire(el, st) {
    const d = el.dataset;
    if (d.key) key(+d.key, st.mods);
    else if (d.cons) send({ t: 'consumer', key: d.cons });
    else if (d.btn) click(d.btn, 1);
    else if (d.macro) runMacro(el);
  }
  stage.addEventListener('pointerdown', (e) => {
    const modEl = e.target.closest('[data-mod]');
    if (modEl) { if (authed) { e.preventDefault(); buzz(8); tapMod(+modEl.dataset.mod); } return; }
    if (e.target.closest('[data-sym]')) { e.preventDefault(); buzz(8); $('#k-sym').hidden = !$('#k-sym').hidden; return; }
    const el = e.target.closest('[data-key],[data-cons],[data-btn],[data-macro]');
    if (!el || !authed || e.button > 0) return;
    e.preventDefault();
    buzz(10);
    el.classList.add(el.closest('.dpad') ? 'hit' : 'pressed');
    const st = { el, mods: +(el.dataset.mods || 0) };
    if (el.dataset.key && el.closest('.keys')) { st.mods |= modsNow(); consumeArmed(); }
    fire(el, st);
    if ('rep' in el.dataset) st.t1 = setTimeout(() => { st.t2 = setInterval(() => fire(el, st), 120); }, 400);
    active.set(e.pointerId, st);
  });
  function release(e) {
    const st = active.get(e.pointerId);
    if (!st) return;
    clearTimeout(st.t1); clearInterval(st.t2);
    st.el.classList.remove('pressed', 'hit');
    active.delete(e.pointerId);
  }
  function releaseAll() { Array.from(active.keys()).forEach((id) => release({ pointerId: id })); }
  addEventListener('pointerup', release);
  addEventListener('pointercancel', release);

  // ---------------------------------------------------------------- keys: sticky modifiers
  const modState = { 1: 0, 4: 0, 2: 0, 8: 0 }; // 0 off, 1 armed, 2 locked
  const modTapAt = {};
  const GLYPH = { 1: '⌃', 4: '⌥', 2: '⇧', 8: '⌘' };
  function tapMod(bit) {
    const now = Date.now(), s = modState[bit];
    if (s === 0) modState[bit] = 1;
    else if (s === 1) modState[bit] = now - (modTapAt[bit] || 0) < 400 ? 2 : 0;
    else modState[bit] = 0;
    modTapAt[bit] = now;
    renderMods();
  }
  function modsNow() { let m = 0; Object.keys(modState).forEach((b) => { if (modState[b]) m |= +b; }); return m; }
  function consumeArmed() {
    let ch = false;
    Object.keys(modState).forEach((b) => { if (modState[b] === 1) { modState[b] = 0; ch = true; } });
    if (ch) renderMods();
  }
  function renderMods() {
    $$('.keys [data-mod]').forEach((el) => { const s = modState[el.dataset.mod]; el.classList.toggle('armed', s === 1); el.classList.toggle('locked', s === 2); });
    const minis = [1, 4, 2, 8].filter((b) => modState[b]).map((b) => '<span class="mini' + (modState[b] === 2 ? ' lk' : '') + '">' + GLYPH[b] + '</span>').join('');
    $('#chord-mods').innerHTML = minis || '<span class="plus">Tap a modifier, double-tap to lock</span>';
    $('#chord-next').hidden = !minis;
  }
  $('#chord-clear').addEventListener('click', () => { Object.keys(modState).forEach((b) => { modState[b] = 0; }); renderMods(); });

  // text box
  const retBox = $('#k-ret');
  retBox.checked = LS.get('sendReturn', '0') === '1';
  retBox.addEventListener('change', () => LS.set('sendReturn', retBox.checked ? '1' : '0'));
  $('#k-form').addEventListener('submit', (e) => {
    e.preventDefault();
    const inp = $('#k-text');
    const s = inp.value + (retBox.checked ? '\n' : '');
    if (!s || !authed) return;
    send({ t: 'text', s: s.slice(0, 2000) });
    inp.value = '';
    buzz(10);
  });

  // fn block collapse
  function setFn(open) {
    $('#upper').hidden = !open; $('#fn-tx').textContent = open ? 'Hide' : 'Show';
    $('#fn-up').hidden = !open; $('#fn-down').hidden = open;
    LS.set('fnOpen', open ? '1' : '0');
  }
  $('#fn-tog').addEventListener('click', () => setFn($('#upper').hidden));

  // ---------------------------------------------------------------- media: scroll lever
  const lever = $('#lever'), thumb = $('#lever-thumb');
  let lv = null;
  lever.addEventListener('pointerdown', (e) => {
    if (!authed) return;
    e.preventDefault();
    try { lever.setPointerCapture(e.pointerId); } catch (x) { /* ignore */ }
    lv = { id: e.pointerId, y0: e.clientY, d: 0, max: Math.max(20, (lever.clientHeight - thumb.offsetHeight) / 2 - 6) };
    buzz(8);
    thumb.classList.add('pressed');
    thumb.style.transition = 'none';
    thumb.style.transform = 'translateY(-50%)';
    lv.iv = setInterval(() => {
      const f = lv.d / lv.max;
      if (Math.abs(f) > 0.08) send({ t: 'scroll', dx: 0, dy: Math.round(Math.sign(f) * Math.pow(Math.abs(f), 1.6) * 80) });
    }, 50);
  });
  lever.addEventListener('pointermove', (e) => {
    if (!lv || e.pointerId !== lv.id) return;
    lv.d = Math.max(-lv.max, Math.min(lv.max, e.clientY - lv.y0));
    thumb.style.transform = 'translateY(calc(-50% + ' + lv.d + 'px))';
  });
  const leverEnd = (e) => {
    if (!lv || (e && e.pointerId !== lv.id)) return;
    clearInterval(lv.iv); lv = null;
    thumb.classList.remove('pressed');
    thumb.style.transition = 'transform 260ms cubic-bezier(.3,1.5,.5,1)';
    thumb.style.transform = '';
  };
  ['pointerup', 'pointercancel', 'lostpointercapture'].forEach((t) => lever.addEventListener(t, leverEnd));

  // ---------------------------------------------------------------- Spaces gesture tiles (hold & slide)
  $$('[data-spaces]').forEach((el) => {
    let s = null;
    el.addEventListener('pointerdown', (e) => {
      if (!authed) return;
      e.preventDefault();
      try { el.setPointerCapture(e.pointerId); } catch (x) { /* ignore */ }
      s = { id: e.pointerId, x: e.clientX, x0: e.clientX, moved: false };
      el.classList.add('pressed'); buzz(10);
    });
    el.addEventListener('pointermove', (e) => {
      if (!s || e.pointerId !== s.id) return;
      let d = e.clientX - s.x;
      while (Math.abs(d) >= 48) {
        const right = d > 0;
        key(right ? 79 : 80, CTRL); buzz(8);
        s.x += right ? 48 : -48; s.moved = true;
        d = e.clientX - s.x;
      }
    });
    const end = (e) => {
      if (!s || e.pointerId !== s.id) return;
      if (e.type === 'pointerup' && !s.moved && Math.abs(e.clientX - s.x0) < 16) key(82, CTRL);
      s = null; el.classList.remove('pressed');
    };
    el.addEventListener('pointerup', end);
    el.addEventListener('pointercancel', end);
  });

  // ---------------------------------------------------------------- macros
  const MACROS = {
    spotlight: { key: [44, CMD] },
    mission: { key: [82, CTRL] },
    shot: { key: [33, CMD | SHIFT] },
    safari: { steps: [{ t: 'key', usage: 44, mods: CMD }, { t: 'wait', ms: 300 }, { t: 'text', s: 'Safari' }, { t: 'wait', ms: 200 }, { t: 'key', usage: 40 }] },
    lock: { key: [20, CTRL | CMD], confirm: true },
    forcequit: { key: [41, OPT | CMD] },
    hide: { key: [11, OPT | CMD] },
  };
  function tileText(el, nm, ch) {
    const n = $('.nm', el), c = $('.ch', el);
    if (!n || !c) return;
    if (el._nm === undefined) { el._nm = n.textContent; el._ch = c.textContent; }
    n.textContent = nm === undefined ? el._nm : nm;
    c.textContent = ch === undefined ? el._ch : ch;
  }
  function endRun(el) { clearTimeout(el._rt); el.classList.remove('running'); tileText(el); }
  function unconfirm(el) { clearTimeout(el._ct); el.classList.remove('confirm'); tileText(el); }
  function runMacro(el) {
    const m = MACROS[el.dataset.macro];
    if (!m) return;
    if (el.classList.contains('running')) { send({ t: 'cancel' }); endRun(el); return; }
    if (m.confirm && !el.classList.contains('confirm')) {
      el.classList.add('confirm'); tileText(el, 'Tap again', el._nm || $('.nm', el).textContent);
      el._ct = setTimeout(() => unconfirm(el), 3000);
      return;
    }
    if (m.confirm) unconfirm(el);
    if (m.steps) {
      send({ t: 'macro', steps: m.steps });
      el.classList.add('running'); tileText(el, undefined, 'Running… tap to cancel');
      el._rt = setTimeout(() => endRun(el), m.steps.reduce((a, s) => a + (s.ms || 0) + 30, 300));
    } else key(m.key[0], m.key[1]);
  }

  // ---------------------------------------------------------------- mouse: touchpad
  const pad = $('#touchpad'), glow = $('#glow'), chip = $('#chip-last');
  const pts = new Map();
  let g = null, lastTapAt = 0, sAcc = { x: 0, y: 0 }, sRaf = 0, locked = false;
  const lastGesture = (t) => { chip.textContent = t; };
  function queueScroll(dx, dy) {
    sAcc.x += dx; sAcc.y += dy;
    if (sRaf) return;
    sRaf = requestAnimationFrame(() => {
      sRaf = 0;
      const x = Math.round(sAcc.x), y = Math.round(sAcc.y);
      if (x || y) { send({ t: 'scroll', dx: x, dy: y }); sAcc.x -= x; sAcc.y -= y; }
    });
  }
  function moveGlow(x, y) {
    const r = pad.getBoundingClientRect();
    glow.style.left = (x - r.left) + 'px'; glow.style.top = (y - r.top) + 'px';
  }
  pad.addEventListener('pointerdown', (e) => {
    if (e.target.closest('#hints-btn') || !authed) return;
    e.preventDefault();
    try { pad.setPointerCapture(e.pointerId); } catch (x) { /* ignore */ }
    pts.set(e.pointerId, { x: e.clientX, y: e.clientY, t: e.timeStamp });
    if (pts.size === 1) {
      g = { t0: e.timeStamp, max: 1, moved: false, done: false, travel: 0, sx: 0, sy: 0 };
      g.lp = setTimeout(() => {
        if (g && pts.size === 1 && !g.moved && !g.done) { g.done = true; click('right'); buzz(20); lastGesture('Right click'); }
      }, 500);
      moveGlow(e.clientX, e.clientY); glow.hidden = false;
    } else if (g) {
      g.max = Math.max(g.max, pts.size); clearTimeout(g.lp); g.sx = g.sy = 0;
    }
  });
  pad.addEventListener('pointermove', (e) => {
    const p = pts.get(e.pointerId);
    if (!p || !g) return;
    const dx = e.clientX - p.x, dy = e.clientY - p.y, dt = Math.max(1, e.timeStamp - p.t);
    p.x = e.clientX; p.y = e.clientY; p.t = e.timeStamp;
    g.travel += Math.abs(dx) + Math.abs(dy);
    if (!g.moved && g.travel > 8) { g.moved = true; clearTimeout(g.lp); }
    const n = pts.size;
    if (n === 1 && g.max === 1) {
      const v = Math.hypot(dx, dy) / dt; // px per ms
      const gain = 1.6 + Math.min(2.4, v * 1.5);
      if (authed && conn) conn.move(dx * gain, dy * gain);
      moveGlow(e.clientX, e.clientY);
      if (g.moved) lastGesture(locked ? 'Drag' : 'Move');
    } else if (n === 2) {
      queueScroll(-dx / n * 2, -dy / n * 2); // natural: content follows the fingers
      if (g.moved) lastGesture('Scroll');
    } else if (n >= 3 && !g.done) {
      g.sx += dx / n; g.sy += dy / n;
      if (Math.abs(g.sx) > 50 && Math.abs(g.sx) > Math.abs(g.sy)) {
        g.done = true; key(g.sx < 0 ? 79 : 80, CTRL); buzz(15);
        lastGesture(g.sx < 0 ? 'Next space' : 'Previous space');
      } else if (g.sy < -50) {
        g.done = true; key(82, CTRL); buzz(15); lastGesture('Mission Control');
      }
    }
  });
  function padUp(e) {
    if (!pts.delete(e.pointerId) || !g || pts.size) return;
    clearTimeout(g.lp); glow.hidden = true;
    if (e.type === 'pointerup' && !g.moved && !g.done && e.timeStamp - g.t0 < 500) {
      if (g.max === 1) {
        const dbl = e.timeStamp - lastTapAt < 300;
        lastTapAt = dbl ? 0 : e.timeStamp;
        click('left', dbl ? 2 : 1); buzz(10); lastGesture(dbl ? 'Double click' : 'Click');
      } else if (g.max === 2) { click('right'); buzz(15); lastGesture('Right click'); }
    }
    g = null;
  }
  pad.addEventListener('pointerup', padUp);
  pad.addEventListener('pointercancel', padUp);

  // drag lock
  function setLock(v, silent) {
    if (v === locked) return;
    locked = v;
    if (!silent) send({ t: 'button', b: 'left', action: v ? 'down' : 'up' });
    pad.classList.toggle('locked', v);
    $('#m-lock').classList.toggle('on', v);
    $('#m-lock-ic').hidden = !v; $('#m-unlock-ic').hidden = v;
    if (!silent) lastGesture(v ? 'Drag lock on' : 'Drag lock off');
  }
  $('#m-lock').addEventListener('pointerdown', (e) => { if (!authed) return; e.preventDefault(); buzz(12); setLock(!locked); });

  // hints
  function setHints(on) {
    $('#hints').hidden = !on; $('#hints-x').hidden = !on; $('#hints-i').hidden = on;
    LS.set('hints', on ? '1' : '0');
  }
  $('#hints-btn').addEventListener('click', () => setHints($('#hints').hidden));

  // ---------------------------------------------------------------- lifecycle
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'visible' && want && kind === 'wifi' && !authed) { backoff = 1000; scheduleRetry(0); }
  });

  // ---------------------------------------------------------------- init
  applyTheme();
  renderMods();
  setFn(LS.get('fnOpen', '1') === '1');
  setHints(LS.get('hints', '1') === '1');
  nameIn.value = LS.get('name', defaultName());
  setMode(mode);
  initConnectNotes();
  setPill('red', 'Not connected');
  if (!M) { showConnect('The connection module (mote.js) failed to load. Reload the page.'); return; }
  if (env.wifi) start('wifi'); else showConnect();
})();
