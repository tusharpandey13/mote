// Mote web remote transport: WebSocket (page served by the helper) or Web Bluetooth (github.io, https).
// Protocol: docs/protocol.md. Exposes window.Mote.
(function () {
  'use strict';
  var SVC = '3abad8f4-b472-41fc-bfc5-6f645f33564b';
  var RX = '0b43b8f5-113a-4034-8055-9f5b26e475e8';
  var TX = 'b0904884-8e1d-4296-86a3-40fff3fafb55';
  var CHUNK = 180;

  // ---- SHA-256 / HMAC (pure JS: crypto.subtle is missing on plain-http LAN pages) ----
  var K = [];
  (function () {
    function frac(x) { return ((x - Math.floor(x)) * 4294967296) | 0; }
    var n = 2, c = 0;
    while (c < 64) {
      var p = true;
      for (var d = 2; d * d <= n; d++) if (n % d === 0) { p = false; break; }
      if (p) { K[c++] = frac(Math.pow(n, 1 / 3)); }
      n++;
    }
  })();
  function sha256(bytes) {
    var H = [0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19];
    var l = bytes.length, total = ((l + 9 + 63) >> 6) << 6, m = new Uint8Array(total);
    m.set(bytes); m[l] = 0x80;
    var bits = l * 8, dv = new DataView(m.buffer);
    dv.setUint32(total - 4, bits >>> 0); dv.setUint32(total - 8, Math.floor(bits / 4294967296));
    var w = new Int32Array(64);
    for (var off = 0; off < total; off += 64) {
      for (var i = 0; i < 16; i++) w[i] = dv.getInt32(off + i * 4);
      for (i = 16; i < 64; i++) {
        var a = w[i - 15], b = w[i - 2];
        var s0 = ((a >>> 7) | (a << 25)) ^ ((a >>> 18) | (a << 14)) ^ (a >>> 3);
        var s1 = ((b >>> 17) | (b << 15)) ^ ((b >>> 19) | (b << 13)) ^ (b >>> 10);
        w[i] = (w[i - 16] + s0 + w[i - 7] + s1) | 0;
      }
      var A = H[0], B = H[1], C = H[2], D = H[3], E = H[4], F = H[5], G = H[6], Hh = H[7];
      for (i = 0; i < 64; i++) {
        var S1 = ((E >>> 6) | (E << 26)) ^ ((E >>> 11) | (E << 21)) ^ ((E >>> 25) | (E << 7));
        var t1 = (Hh + S1 + ((E & F) ^ (~E & G)) + K[i] + w[i]) | 0;
        var S0 = ((A >>> 2) | (A << 30)) ^ ((A >>> 13) | (A << 19)) ^ ((A >>> 22) | (A << 10));
        var t2 = (S0 + ((A & B) ^ (A & C) ^ (B & C))) | 0;
        Hh = G; G = F; F = E; E = (D + t1) | 0; D = C; C = B; B = A; A = (t1 + t2) | 0;
      }
      H[0] = (H[0] + A) | 0; H[1] = (H[1] + B) | 0; H[2] = (H[2] + C) | 0; H[3] = (H[3] + D) | 0;
      H[4] = (H[4] + E) | 0; H[5] = (H[5] + F) | 0; H[6] = (H[6] + G) | 0; H[7] = (H[7] + Hh) | 0;
    }
    var out = new Uint8Array(32), ov = new DataView(out.buffer);
    for (i = 0; i < 8; i++) ov.setInt32(i * 4, H[i]);
    return out;
  }
  function hexToBytes(h) { var o = new Uint8Array(h.length / 2); for (var i = 0; i < o.length; i++) o[i] = parseInt(h.substr(i * 2, 2), 16); return o; }
  function bytesToHex(b) { var s = ''; for (var i = 0; i < b.length; i++) s += (b[i] < 16 ? '0' : '') + b[i].toString(16); return s; }
  function hmac(keyBytes, msgBytes) {
    var key = keyBytes.length > 64 ? sha256(keyBytes) : keyBytes, ipad = new Uint8Array(64 + msgBytes.length), opad = new Uint8Array(96);
    for (var i = 0; i < 64; i++) { var k = key[i] || 0; ipad[i] = k ^ 0x36; opad[i] = k ^ 0x5c; }
    ipad.set(msgBytes, 64); opad.set(sha256(ipad), 64);
    return bytesToHex(sha256(opad));
  }
  var enc = new TextEncoder(), dec = new TextDecoder();

  // ---- credentials (per origin, per transport) ----
  function credKey(kind) { return 'mote.cred.' + kind; }
  function loadCred(kind) { try { return JSON.parse(localStorage.getItem(credKey(kind)) || 'null'); } catch (e) { return null; } }

  function env() {
    var http = location.protocol === 'http:' && !!location.port;
    return { wifi: http, ble: !!navigator.bluetooth, secure: !!window.isSecureContext };
  }

  function connect(kind) {
    var handlers = {}, ws = null, rx = null, gatt = null, closed = false, authed = false, nonce = null;
    var pendingPair = null, moveX = 0, moveY = 0, moveTimer = null, bleQueue = Promise.resolve(), inbuf = [];
    var conn = {
      on: function (ev, fn) { (handlers[ev] = handlers[ev] || []).push(fn); return conn; },
      send: function (m) { if (authed) raw(m); },
      move: function (dx, dy) {
        moveX += dx; moveY += dy;
        if (!moveTimer) moveTimer = setTimeout(flushMove, kind === 'ble' ? 30 : 12);
      },
      pair: function (code, name) {
        return new Promise(function (res, rej) { pendingPair = { res: res, rej: rej }; raw({ t: 'pair', code: String(code), name: String(name || 'Web remote').slice(0, 64) }); });
      },
      close: function () { closed = true; if (ws) ws.close(); if (gatt && gatt.connected) gatt.disconnect(); emitState({ state: 'closed', reason: 'user' }); },
      get authed() { return authed; }
    };
    function emit(ev, d) { (handlers[ev] || []).forEach(function (f) { try { f(d); } catch (e) { console.error(e); } }); }
    function emitState(d) { emit('state', d); }
    function flushMove() {
      moveTimer = null;
      var dx = Math.round(moveX), dy = Math.round(moveY);
      moveX -= dx; moveY -= dy;
      if (dx || dy) conn.send({ t: 'move', dx: dx, dy: dy });
    }
    function raw(m) {
      var s = JSON.stringify(m);
      if (kind === 'wifi') { if (ws && ws.readyState === 1) ws.send(s); return; }
      if (!rx) return;
      var b = enc.encode(s);
      var n = CHUNK - 1;
      for (var off = 0; ; off += n) {
        var more = off + n < b.length, f = new Uint8Array(Math.min(n, b.length - off) + 1);
        f[0] = more ? 1 : 0; f.set(b.subarray(off, off + n), 1);
        queueWrite(f);
        if (!more) break;
      }
    }
    function queueWrite(frag) {
      // GATT allows one operation at a time; chain writes so Chrome doesn't throw "operation in progress".
      bleQueue = bleQueue.then(function () {
        return rx.writeValueWithoutResponse ? rx.writeValueWithoutResponse(frag) : rx.writeValue(frag);
      }).catch(function (e) { console.warn('ble write', e); });
    }
    function onMessage(m) {
      switch (m.t) {
        case 'hello':
          nonce = m.nonce; conn.host = m.host;
          var c = loadCred(kind);
          if (c && c.id && c.token) raw({ t: 'auth', id: c.id, mac: hmac(hexToBytes(c.token), enc.encode(nonce)) });
          else emitState({ state: 'need-pair', host: m.host, pairing: m.pairing });
          break;
        case 'paired':
          localStorage.setItem(credKey(kind), JSON.stringify({ id: m.id, token: m.token }));
          raw({ t: 'auth', id: m.id, mac: hmac(hexToBytes(m.token), enc.encode(nonce)) });
          break;
        case 'authed':
          authed = true;
          if (pendingPair) { pendingPair.res(); pendingPair = null; }
          emitState({ state: 'authed', host: conn.host, paused: m.paused, ax: m.ax });
          break;
        case 'state': emit('paused', { paused: m.paused, ax: m.ax }); break;
        case 'ping': raw({ t: 'pong', n: m.n }); break;
        case 'err':
          if (m.code === 'auth' || m.code === 'revoked') {
            localStorage.removeItem(credKey(kind)); authed = false;
            emitState({ state: 'need-pair', host: conn.host, reason: m.code });
          } else if (pendingPair && (m.ref === 'pair' || m.ref === 'auth')) {
            var e = new Error(m.msg || m.code); e.code = m.code; e.retryAfter = m.retryAfter;
            pendingPair.rej(e); pendingPair = null;
          } else if (m.code === 'rate' && !authed) {
            emitState({ state: 'error', reason: 'rate', retryAfter: m.retryAfter });
          } else console.warn('mote err', m);
          break;
      }
    }
    function onClosed(reason) {
      authed = false;
      if (pendingPair) { var e = new Error('closed'); e.code = 'closed'; pendingPair.rej(e); pendingPair = null; }
      if (!closed) emitState({ state: 'closed', reason: reason });
    }

    setTimeout(function () {
      emitState({ state: 'connecting' });
      if (kind === 'wifi') {
        ws = new WebSocket((location.protocol === 'https:' ? 'wss://' : 'ws://') + location.host + '/ws');
        ws.onmessage = function (e) { try { onMessage(JSON.parse(e.data)); } catch (x) { console.warn(x); } };
        ws.onclose = function () { onClosed('socket'); };
      } else {
        startBle().catch(function (e) { emitState({ state: 'error', reason: e && e.name === 'NotFoundError' ? 'cancelled' : String(e && e.message || e) }); });
      }
    }, 0);

    function startBle() {
      if (!navigator.bluetooth) return Promise.reject(new Error('Web Bluetooth unavailable'));
      return navigator.bluetooth.requestDevice({ filters: [{ services: [SVC] }] }).then(function (dev) {
        dev.addEventListener('gattserverdisconnected', function () { onClosed('ble'); });
        return dev.gatt.connect();
      }).then(function (g) {
        gatt = g; return g.getPrimaryService(SVC);
      }).then(function (svc) {
        return Promise.all([svc.getCharacteristic(RX), svc.getCharacteristic(TX)]);
      }).then(function (cs) {
        rx = cs[0];
        cs[1].addEventListener('characteristicvaluechanged', function (e) {
          var v = new Uint8Array(e.target.value.buffer, e.target.value.byteOffset, e.target.value.byteLength);
          inbuf.push(v.slice(1));
          if (!(v[0] & 1)) {
            var n = 0; inbuf.forEach(function (p) { n += p.length; });
            var all = new Uint8Array(n), o = 0; inbuf.forEach(function (p) { all.set(p, o); o += p.length; });
            inbuf = [];
            try { onMessage(JSON.parse(dec.decode(all))); } catch (x) { console.warn(x); }
          }
        });
        return cs[1].startNotifications(); // the helper sends hello on subscribe
      });
    }
    return conn;
  }

  function autoPairCode() {
    var m = /(?:^#|&)code=(\d{6})/.exec(location.hash || '');
    if (m) history.replaceState(null, '', location.pathname + location.search);
    return m ? m[1] : null;
  }

  window.Mote = {
    env: env, connect: connect, autoPairCode: autoPairCode,
    forget: function (kind) { localStorage.removeItem(credKey(kind)); },
    _hmac: function (keyHex, msg) { return hmac(hexToBytes(keyHex), enc.encode(msg)); }
  };
})();
