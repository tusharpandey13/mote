// Labs video bridge test (docs/labs/video-bridge.md). Starts its own --dry-run helper with the bridge on:
//   node helper/tests/bridge.mjs [path/to/MoteHelper]
// Checks: loopback-only bind, Host + Origin guards, pairing, auth required, origin pinning, phone relay,
// command routing, pause, revoke. Uses a raw WebSocket client so it can send any Origin (like a local process could).
import { spawn } from 'node:child_process';
import crypto from 'node:crypto';
import net from 'node:net';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const BIN = process.argv[2] || process.env.MOTE_HELPER || path.join(here, '../build/MoteHelper.app/Contents/MacOS/MoteHelper');
let failed = 0;
const ok = (c, msg) => { console.log((c ? 'PASS ' : 'FAIL ') + msg); if (!c) failed++; };
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const mac = (token, nonce) => crypto.createHmac('sha256', Buffer.from(token, 'hex')).update(nonce).digest('hex');

// --- helper process
const proc = spawn(BIN, ['--dry-run', '--headless', '--no-ble', '--memory-store', '--stdin-control', '--open-pairing',
  '--port', '0', '--labs-bridge', '--bridge-port', '0'], { stdio: ['pipe', 'pipe', 'pipe'] });
let out = '';
const waiters = [];
for (const s of [proc.stdout, proc.stderr]) s.on('data', (d) => { out += d; waiters.splice(0).forEach((w) => w()); });
async function waitFor(re, ms = 5000) {
  const end = Date.now() + ms;
  for (;;) {
    const m = out.match(re); if (m) return m;
    if (Date.now() > end) throw new Error('timeout waiting for ' + re + '\n' + out);
    await new Promise((r) => { waiters.push(r); setTimeout(r, 100); });
  }
}
const cmd = (line) => proc.stdin.write(line + '\n');
process.on('exit', () => proc.kill());

const port = +(await waitFor(/wifi: listening on :(\d+)/))[1];
const bport = +(await waitFor(/bridge: listening on 127\.0\.0\.1:(\d+)/))[1];
const phoneCode = (await waitFor(/PAIRING CODE: (\d{6})/))[1];

// --- minimal WebSocket client over a raw socket (any Host / Origin)
function wsOpen({ host = '127.0.0.1', p = bport, hostHeader = `127.0.0.1:${p}`, origin = 'chrome-extension://abcdefghijklmnop', path: wpath = '/bridge' } = {}) {
  return new Promise((res) => {
    const s = net.connect(p, host);
    let buf = Buffer.alloc(0), upgraded = false;
    const q = [], wait = [];
    const api = {
      status: '', closed: false,
      next: (ms = 3000) => new Promise((r) => { if (q.length) return r(q.shift()); const t = setTimeout(() => r(null), ms); wait.push((m) => { clearTimeout(t); r(m); }); }),
      send: (m) => {
        const pl = Buffer.from(JSON.stringify(m)), mask = crypto.randomBytes(4);
        const h = pl.length < 126 ? Buffer.from([0x81, 0x80 | pl.length]) : Buffer.from([0x81, 0x80 | 126, pl.length >> 8, pl.length & 255]);
        s.write(Buffer.concat([h, mask, Buffer.from(pl.map((b, i) => b ^ mask[i & 3]))]));
      },
      req: (m) => { api.send(m); return api.next(); },
      // Skips messages until one matches (broadcast states arrive in between).
      until: async (pred, ms = 3000) => { const end = Date.now() + ms; for (;;) { const m = await api.next(Math.max(1, end - Date.now())); if (!m || pred(m)) return m; } },
      ask: (m, ms) => { api.send(m); return api.until((x) => x.t === 'err' && x.ref === m.t, ms); },
      close: () => s.destroy(),
    };
    const push = (m) => { const w = wait.shift(); w ? w(m) : q.push(m); };
    s.on('error', (e) => { api.status = 'error ' + e.code; api.closed = true; res(api); });
    s.on('close', () => { api.closed = true; if (!upgraded) res(api); wait.splice(0).forEach((w) => w(null)); });
    s.on('connect', () => {
      let h = `GET ${wpath} HTTP/1.1\r\nHost: ${hostHeader}\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: ${crypto.randomBytes(16).toString('base64')}\r\nSec-WebSocket-Version: 13\r\n`;
      if (origin) h += `Origin: ${origin}\r\n`;
      s.write(h + '\r\n');
    });
    s.on('data', (d) => {
      buf = Buffer.concat([buf, d]);
      if (!upgraded) {
        const i = buf.indexOf('\r\n\r\n'); if (i < 0) return;
        api.status = buf.subarray(0, i).toString().split('\r\n')[0];
        buf = buf.subarray(i + 4);
        if (!api.status.includes('101')) { res(api); return; }
        upgraded = true; res(api);
      }
      while (buf.length >= 2) {
        let len = buf[1] & 0x7f, off = 2;
        if (len === 126) { if (buf.length < 4) return; len = buf.readUInt16BE(2); off = 4; }
        else if (len === 127) { if (buf.length < 10) return; len = Number(buf.readBigUInt64BE(2)); off = 10; }
        if (buf.length < off + len) return;
        const op = buf[0] & 0x0f, pl = buf.subarray(off, off + len);
        buf = buf.subarray(off + len);
        if (op === 1) push(JSON.parse(pl.toString()));
      }
    });
  });
}

// --- phone (native client, main port)
const phone = await wsOpen({ p: port, hostHeader: `127.0.0.1:${port}`, origin: null, path: '/ws' });
const ph = await phone.next();
const pp = await phone.req({ t: 'pair', code: phoneCode, name: 'bridge test phone' });
const pa = await phone.req({ t: 'auth', id: pp.id, mac: mac(pp.token, ph.nonce) });
ok(pa.t === 'authed', 'phone authed on the main port');
let st = await phone.req({ t: 'labs.video.sub', on: true });
ok(st.t === 'labs.video.state' && st.bridge === 'waiting' && st.active === false, 'phone sub: bridge waiting, no video');
let e = await phone.ask({ t: 'labs.video.cmd', cmd: 'play' });
ok(e.t === 'err' && e.code === 'notfound', 'cmd without extension -> notfound');

// --- loopback only
const lan = Object.values(os.networkInterfaces()).flat().find((i) => i && i.family === 'IPv4' && !i.internal);
if (lan) {
  const x = await wsOpen({ host: lan.address, hostHeader: `127.0.0.1:${bport}` });
  ok(x.status.startsWith('error') || !x.status.includes('101'), `bridge port not reachable on LAN address ${lan.address} (${x.status || 'closed'})`);
} else console.log('SKIP no LAN address for the loopback-only check');
const main = await wsOpen({ p: port, hostHeader: `127.0.0.1:${port}`, origin: 'chrome-extension://abcdefghijklmnop', path: '/ws' });
ok(!main.status.includes('101'), 'extension origin refused on the main port');

// --- Host / Origin guards
for (const [name, opts] of [
  ['no Origin (native process without one)', { origin: null }],
  ['web page Origin', { origin: 'http://evil.example.com' }],
  ['localhost page Origin', { origin: `http://127.0.0.1:${bport}` }],
  ['file Origin', { origin: 'null' }],
  ['malformed extension Origin', { origin: 'chrome-extension://bad/../x' }],
  ['foreign Host (DNS rebinding)', { hostHeader: `evil.example.com:${bport}` }],
  ['wrong path', { path: '/ws' }],
]) {
  const x = await wsOpen(opts);
  ok(!x.status.includes('101'), `refused: ${name} (${x.status})`);
}

// --- auth required, pairing
const ORIGIN = 'chrome-extension://abcdefghijklmnop';
let ext = await wsOpen({ origin: ORIGIN });
let hello = await ext.next();
ok(ext.status.includes('101') && hello?.t === 'hello' && hello.bridge === 1 && hello.nonce.length === 32 && hello.pairing === false, 'extension origin accepted, hello');
e = await ext.req({ t: 'labs.video.state', active: true, title: 'x' });
ok(e.t === 'err' && e.code === 'unauth', 'state before auth rejected');
e = await ext.req({ t: 'labs.video.pair', code: '123456' });
ok(e.t === 'err' && e.code === 'pairing-closed', 'pair while the bridge window is closed rejected');
e = await ext.req({ t: 'labs.video.auth', id: 'nope', mac: '00' });
ok(e.t === 'err' && e.code === 'auth', 'auth with unknown id rejected');
e = await ext.req({ t: 'key', usage: 4 });
ok(e.t === 'err' && e.code === 'unauth', 'input message before auth rejected');
cmd('bridge pair');
const bcode = (await waitFor(/BRIDGE CODE: (\d{6})/))[1];
e = await ext.req({ t: 'labs.video.pair', code: bcode === '000000' ? '111111' : '000000' });
ok(e.code === 'code', 'wrong bridge code rejected');
const paired = await ext.req({ t: 'labs.video.pair', code: bcode });
ok(paired.t === 'labs.video.paired' && /^[0-9a-f]{64}$/.test(paired.token), 'bridge paired, got token');
e = await ext.req({ t: 'labs.video.pair', code: bcode });
ok(e.code === 'pairing-closed', 'bridge code is single use');
const au = await ext.req({ t: 'labs.video.auth', id: paired.id, mac: mac(paired.token, hello.nonce) });
ok(au.t === 'labs.video.authed', 'extension authed');
st = await phone.until((m) => m.bridge === 'connected');
ok(st?.t === 'labs.video.state' && st.bridge === 'connected', 'phone sees bridge connected');

// --- pinned origin: same token from another origin fails
const other = await wsOpen({ origin: 'chrome-extension://zzzzzzzzzzzzzzzz' });
const oh = await other.next();
e = await other.req({ t: 'labs.video.auth', id: paired.id, mac: mac(paired.token, oh.nonce) });
ok(e.t === 'err' && e.code === 'auth', 'valid token from another extension origin refused (pinned)');
other.close();
const replay = await wsOpen({ origin: ORIGIN });
await replay.next();
e = await replay.req({ t: 'labs.video.auth', id: paired.id, mac: mac(paired.token, hello.nonce) });
ok(e.code === 'auth', 'replayed auth (old nonce) refused');
replay.close();

// --- relay, sanitising
ext.send({ t: 'labs.video.state', active: true, title: 'T'.repeat(500), host: 'example.test', site: 'generic', url: 'http://secret/path',
  duration: 100, time: 12.5, paused: true, rate: 1, muted: false, volume: 0.5, captions: 'off', skips: ['ad', 'evil'], hasNext: 1, extra: 'x' });
st = await phone.until((m) => m.active === true);
ok(st?.active === true && st.title.length === 200 && st.host === 'example.test' && st.time === 12.5 && st.paused === true, 'state relayed to phone');
ok(st.url === undefined && st.extra === undefined && st.hasNext === undefined && JSON.stringify(st.skips) === '["ad"]', 'state sanitised (no url, unknown fields, bad types dropped)');

// --- command routing
phone.send({ t: 'labs.video.cmd', cmd: 'seekBy', value: 10 });
let c = await ext.next();
ok(c?.t === 'labs.video.cmd' && c.cmd === 'seekBy' && c.value === 10, 'phone seekBy routed to the extension');
phone.send({ t: 'labs.video.cmd', cmd: 'rate', value: 9 });
c = await ext.next();
ok(c?.cmd === 'rate' && c.value === 4, 'rate clamped to 4');
e = await phone.ask({ t: 'labs.video.cmd', cmd: 'rm -rf' });
ok(e.code === 'bad', 'unknown cmd rejected');
e = await phone.ask({ t: 'labs.video.cmd', cmd: 'seek' });
ok(e.code === 'bad', 'seek without value rejected');
e = await ext.req({ t: 'labs.video.cmd', cmd: 'play' });
ok(e.t === 'err' && e.code === 'unknown', 'the extension cannot send commands');
e = await ext.req({ t: 'key', usage: 4 });
ok(e.t === 'err' && e.code === 'unknown' && !/DRY key usage=4/.test(out), 'the extension cannot inject input');
ext.send({ t: 'labs.video.result', cmd: 'next', ok: false, code: 'notfound' });
e = await phone.until((m) => m.t === 'err');
ok(e?.t === 'err' && e.code === 'notfound' && e.ref === 'labs.video.cmd', 'extension failure forwarded to the phone');

// --- pause
cmd('pause'); await waitFor(/input paused/);
e = await phone.ask({ t: 'labs.video.cmd', cmd: 'play' });
ok(e.code === 'paused', 'commands follow Pause input');
cmd('resume'); await waitFor(/input resumed/);

// --- unauth phone
const anon = await wsOpen({ p: port, hostHeader: `127.0.0.1:${port}`, origin: null, path: '/ws' });
await anon.next();
e = await anon.req({ t: 'labs.video.sub', on: true });
ok(e.code === 'unauth', 'unauthenticated phone cannot subscribe');
anon.close();

// --- forget / toggle off
cmd('bridge forget');
await sleep(300);
ok(ext.closed, 'forget drops the extension');
const again = await wsOpen({ origin: ORIGIN });
const ah = await again.next();
e = await again.req({ t: 'labs.video.auth', id: paired.id, mac: mac(paired.token, ah.nonce) });
ok(e.code === 'auth', 'forgotten token refused');
again.close();
cmd('bridge off');
await waitFor(/bridge: off/);
await sleep(200);
const off = await wsOpen({});
ok(!off.status.includes('101'), `bridge port closed when the toggle is off (${off.status || 'closed'})`);
e = await phone.ask({ t: 'labs.video.cmd', cmd: 'play' });
ok(e.code === 'unsupported', 'cmd with the bridge off -> unsupported');

phone.close();
proc.kill();
console.log(failed ? `${failed} FAILED` : 'ALL PASS');
process.exit(failed ? 1 : 0);
