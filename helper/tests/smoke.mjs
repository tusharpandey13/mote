// Smoke test against a running helper started with --dry-run (nothing is injected).
//   node helper/tests/smoke.mjs <port> <pairing code|-> [credFile]
// With a code: pairs, saves {id, token} to credFile. With "-": reuses credFile (tests Keychain persistence).
import crypto from 'node:crypto';
import fs from 'node:fs';
import net from 'node:net';

const [port, code, credFile = '/tmp/mote-cred.json'] = process.argv.slice(2);
const url = `ws://127.0.0.1:${port}/ws`;
let failed = 0;
const ok = (c, msg) => { console.log((c ? 'PASS ' : 'FAIL ') + msg); if (!c) failed++; };

function open() {
  return new Promise((res, rej) => {
    const ws = new WebSocket(url), q = [], waiters = [];
    ws.onmessage = (e) => { const m = JSON.parse(e.data); const w = waiters.shift(); w ? w(m) : q.push(m); };
    ws.onerror = rej;
    ws.next = () => new Promise((r) => { const m = q.shift(); m ? r(m) : waiters.push(r); });
    ws.req = (m) => { ws.send(JSON.stringify(m)); return ws.next(); };
    ws.onopen = () => res(ws);
  });
}
const mac = (token, nonce) => crypto.createHmac('sha256', Buffer.from(token, 'hex')).update(nonce).digest('hex');

async function http(path, host = `127.0.0.1:${port}`, extra = '') {
  return new Promise((res) => {
    const s = net.connect(+port, '127.0.0.1', () => s.write(`GET ${path} HTTP/1.1\r\nHost: ${host}\r\n${extra}\r\n`));
    let d = ''; s.on('data', (b) => (d += b)); s.on('close', () => res(d.split('\r\n')[0]));
  });
}

// --- static files and HTTP guards
ok((await http('/')).includes('200'), 'GET / serves the web remote');
ok((await http('/design/mote.css')).includes('200'), 'GET /design/mote.css');
ok((await http('/../../etc/passwd')).includes('404'), 'path traversal refused');
ok((await http('/', 'evil.example.com')).includes('421'), 'foreign Host refused (DNS rebinding)');
ok((await http('/ws', `127.0.0.1:${port}`, 'Upgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\nOrigin: http://evil.example.com\r\n')).includes('403'), 'cross-origin WebSocket refused');

let cred;
if (code !== '-') {
  const a = await open();
  const hello = await a.next();
  ok(hello.t === 'hello' && hello.v === 1 && hello.nonce.length === 32 && hello.pairing === true, 'hello with nonce, pairing open');
  const r = await a.req({ t: 'key', usage: 4 });
  ok(r.t === 'err' && r.code === 'unauth', 'input before auth rejected');
  const bad = await a.req({ t: 'pair', code: code === '000000' ? '111111' : '000000', name: 'smoke' });
  ok(bad.code === 'code', 'wrong pairing code rejected');
  const p = await a.req({ t: 'pair', code, name: 'smoke test' });
  ok(p.t === 'paired' && /^[0-9a-f]{64}$/.test(p.token), 'paired, got token');
  const au = await a.req({ t: 'auth', id: p.id, mac: mac(p.token, hello.nonce) });
  ok(au.t === 'authed' && au.name === 'smoke test', 'authed after pairing');
  cred = { id: p.id, token: p.token };
  fs.writeFileSync(credFile, JSON.stringify(cred));
  a.close();
  const c = await open(); const h2 = await c.next();
  ok(h2.pairing === false, 'pairing window closed after one device');
  const again = await c.req({ t: 'pair', code, name: 'x' });
  ok(again.code === 'pairing-closed', 'code is single-use');
  c.close();
} else {
  cred = JSON.parse(fs.readFileSync(credFile, 'utf8'));
}

// --- authenticated session: harmless dry-run input
const b = await open();
const hb = await b.next();
const replay = await b.req({ t: 'auth', id: cred.id, mac: mac(cred.token, 'not-the-nonce') });
ok(replay.code === 'auth', 'MAC over a different nonce (replay) rejected');
const au2 = await b.req({ t: 'auth', id: cred.id, mac: mac(cred.token, hb.nonce) });
ok(au2.t === 'authed', 'reconnect auth via HMAC challenge (token from ' + (code === '-' ? 'Keychain after restart' : 'pairing') + ')');
for (const m of [
  { t: 'move', dx: 1, dy: 0 },
  { t: 'consumer', key: 'volup' },
  { t: 'key', usage: 80, mods: 1 },
  { t: 'text', s: 'Grüße 東京 👋' },
  { t: 'scroll', dx: 0, dy: 40 },
  { t: 'clickAt', x: 0.5, y: 0.5 },
  { t: 'macro', steps: [{ t: 'key', usage: 44, mods: 8 }, { t: 'wait', ms: 50 }, { t: 'text', s: 'Safari' }] },
]) b.send(JSON.stringify(m));
const e1 = await b.req({ t: 'consumer', key: 'nope' });
ok(e1.code === 'bad', 'invalid consumer key rejected');
const e2 = await b.req({ t: 'macro', steps: Array(65).fill({ t: 'wait', ms: 1 }) });
ok(e2.code === 'bad', 'oversized macro rejected');
const pong = await b.req({ t: 'ping', n: 7 });
ok(pong.t === 'pong' && pong.n === 7, 'ping/pong');
b.close();

// --- rate limit (last: it blocks this peer for 60 s)
const d = await open(); await d.next();
let last;
for (let i = 0; i < 6; i++) last = await d.req({ t: 'auth', id: cred.id, mac: '00'.repeat(32) });
ok(last.code === 'rate' && last.retryAfter > 0, 'auth attempts rate-limited (retryAfter ' + last.retryAfter + 's)');
d.close();

console.log(failed ? `${failed} FAILED` : 'ALL PASSED');
process.exit(failed ? 1 : 0);
