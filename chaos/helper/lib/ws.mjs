// Minimal raw WebSocket client over net.Socket, so tests can send malformed / fragmented / unmasked
// frames, stop reading, half-close or RST at any byte.
import crypto from 'node:crypto';
import net from 'node:net';

export function hmac(token, nonce) {
  return crypto.createHmac('sha256', Buffer.from(token, 'hex')).update(nonce).digest('hex');
}

/** Encodes one client frame. `len` overrides the declared payload length (for lying headers). */
export function frame(opcode, payload = Buffer.alloc(0), { fin = true, mask = true, len, rsv = 0 } = {}) {
  const p = Buffer.isBuffer(payload) ? payload : Buffer.from(payload);
  const L = len ?? p.length;
  const head = [((fin ? 0x80 : 0) | (rsv << 4) | opcode) & 0xff];
  let ext = Buffer.alloc(0);
  if (L < 126) head.push((mask ? 0x80 : 0) | L);
  else if (L <= 0xffff) { head.push((mask ? 0x80 : 0) | 126); ext = Buffer.alloc(2); ext.writeUInt16BE(L); }
  else { head.push((mask ? 0x80 : 0) | 127); ext = Buffer.alloc(8); ext.writeBigUInt64BE(BigInt(L)); }
  if (!mask) return Buffer.concat([Buffer.from(head), ext, p]);
  const key = crypto.randomBytes(4);
  const body = Buffer.from(p);
  for (let i = 0; i < body.length; i++) body[i] ^= key[i & 3];
  return Buffer.concat([Buffer.from(head), ext, key, body]);
}

export function handshake(hostHeader, extra = '') {
  const key = crypto.randomBytes(16).toString('base64');
  return Buffer.from(`GET /ws HTTP/1.1\r\nHost: ${hostHeader}\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n` +
    `Sec-WebSocket-Key: ${key}\r\nSec-WebSocket-Version: 13\r\n${extra}\r\n`);
}

export function hostHeader(host, port) { return host.includes(':') ? `[${host}]:${port}` : `${host}:${port}`; }

/**
 * Opens a WebSocket to the helper. Resolves after the 101 (or rejects on refusal / timeout).
 * c.next(ms) -> next JSON message or null on timeout/close; c.req(obj) sends and waits.
 */
export function connect({ port, host = '127.0.0.1', connectVia, timeout = 5000, read = true } = {}) {
  return new Promise((resolve, reject) => {
    const sock = net.connect({ host: connectVia?.host ?? host, port: connectVia?.port ?? port });
    sock.setNoDelay(true);
    const c = {
      sock, q: [], waiters: [], closed: false, closeCode: null, upgraded: false, rx: 0, buf: Buffer.alloc(0), frag: [],
      control: [],
    };
    const timer = setTimeout(() => { sock.destroy(); reject(new Error('connect timeout')); }, timeout);
    const deliver = (m) => { const w = c.waiters.shift(); if (w) w(m); else c.q.push(m); };
    const onClose = () => {
      if (c.closed) return;
      c.closed = true; clearTimeout(timer);
      if (!c.upgraded) reject(new Error('closed before upgrade'));
      while (c.waiters.length) c.waiters.shift()(null);
    };
    sock.on('error', () => {});
    sock.on('close', onClose);
    sock.on('end', () => { onClose(); sock.destroy(); }); // peer FIN: done, even if our writes are still queued
    sock.on('data', (d) => {
      c.rx += d.length;
      c.buf = Buffer.concat([c.buf, d]);
      if (!c.upgraded) {
        const i = c.buf.indexOf('\r\n\r\n');
        if (i < 0) return;
        const head = c.buf.subarray(0, i).toString();
        c.buf = c.buf.subarray(i + 4);
        if (!head.startsWith('HTTP/1.1 101')) { clearTimeout(timer); sock.destroy(); reject(new Error(head.split('\r\n')[0])); return; }
        c.upgraded = true; clearTimeout(timer); resolve(c);
        if (!read) { sock.pause(); return; }
      }
      // Server frames are unmasked.
      while (c.buf.length >= 2) {
        const op = c.buf[0] & 0x0f, fin = (c.buf[0] & 0x80) !== 0;
        let len = c.buf[1] & 0x7f, off = 2;
        if (len === 126) { if (c.buf.length < 4) return; len = c.buf.readUInt16BE(2); off = 4; }
        else if (len === 127) { if (c.buf.length < 10) return; len = Number(c.buf.readBigUInt64BE(2)); off = 10; }
        if (c.buf.length < off + len) return;
        const p = c.buf.subarray(off, off + len);
        c.buf = c.buf.subarray(off + len);
        if (op === 8) { c.closeCode = p.length >= 2 ? p.readUInt16BE(0) : 1005; c.control.push({ op }); continue; }
        if (op === 9 || op === 10) { c.control.push({ op, len }); continue; }
        c.frag.push(p);
        if (!fin) continue;
        const txt = Buffer.concat(c.frag).toString(); c.frag = [];
        let m; try { m = JSON.parse(txt); } catch { m = { t: '__nonjson', raw: txt }; }
        deliver(m);
      }
    });
    sock.write(handshake(hostHeader(host, port)));
    c.send = (m) => { if (!sock.destroyed) sock.write(frame(1, typeof m === 'string' ? m : JSON.stringify(m))); };
    c.raw = (b) => { if (!sock.destroyed) sock.write(b); };
    c.next = (ms = 3000) => new Promise((r) => {
      if (c.q.length) return r(c.q.shift());
      if (c.closed) return r(null);
      const w = (m) => { clearTimeout(t); r(m); };
      const t = setTimeout(() => { c.waiters.splice(c.waiters.indexOf(w) >>> 0, 1); r(null); }, ms);
      c.waiters.push(w);
    });
    /** Next message matching pred (others are discarded). */
    c.until = async (pred, ms = 3000) => {
      const end = Date.now() + ms;
      for (;;) { const left = end - Date.now(); if (left <= 0) return null; const m = await c.next(left); if (!m || pred(m)) return m; }
    };
    c.req = (m, ms) => { c.send(m); return c.next(ms); };
    c.ping = async (n = Math.floor(Math.random() * 1e9), ms = 3000) => {
      const t0 = performance.now(); c.send({ t: 'ping', n });
      const m = await c.until((x) => x.t === 'pong' && x.n === n, ms);
      return m ? performance.now() - t0 : null;
    };
    c.close = () => { if (!sock.destroyed) { sock.write(frame(8, Buffer.from([0x03, 0xe8]))); sock.end(); } };
    c.destroy = () => sock.destroy();
    c.reset = () => { try { sock.resetAndDestroy(); } catch { sock.destroy(); } };
    c.waitClosed = (ms = 3000) => new Promise((r) => { if (c.closed) return r(true); const t = setTimeout(() => r(false), ms); sock.once('close', () => { clearTimeout(t); r(true); }); });
  });
}

/** connect + read hello. */
export async function open(o) { const c = await connect(o); c.hello = await c.next(o.timeout ?? 3000); return c; }

/** Pairs a new device on a helper whose window is open; returns { id, token }. */
export async function pair(h, o = {}) {
  const c = await open({ port: h.port, ...o });
  const p = await c.req({ t: 'pair', code: h.code, name: o.name ?? 'chaos' });
  c.close();
  if (p?.t !== 'paired') throw new Error('pairing failed: ' + JSON.stringify(p));
  return { id: p.id, token: p.token };
}

/** Opens and authenticates a session; throws if auth fails. */
export async function authed(h, cred, o = {}) {
  const c = await open({ port: h.port, ...o });
  if (c.hello?.t !== 'hello') { c.destroy(); throw new Error('no hello: ' + JSON.stringify(c.hello)); }
  const a = await c.req({ t: 'auth', id: cred.id, mac: hmac(cred.token, c.hello.nonce) });
  if (a?.t !== 'authed') { c.destroy(); throw new Error('auth failed: ' + JSON.stringify(a)); }
  return c;
}

/** Plain HTTP request with arbitrary raw bytes; resolves { status, closed, ms }. */
export function rawHttp(port, bytes, { host = '127.0.0.1', timeout = 5000 } = {}) {
  return new Promise((res) => {
    const t0 = Date.now(); let data = '';
    const s = net.connect({ host, port });
    s.on('error', () => {});
    s.on('data', (d) => (data += d));
    s.on('close', () => { clearTimeout(t); res({ status: parseInt(data.split(' ')[1], 10) || 0, closed: true, ms: Date.now() - t0 }); });
    const t = setTimeout(() => { s.destroy(); res({ status: parseInt(data.split(' ')[1], 10) || 0, closed: false, ms: Date.now() - t0 }); }, timeout);
    s.write(bytes);
  });
}
