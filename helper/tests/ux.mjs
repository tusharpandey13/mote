// Helper UX seams against the real helper in --dry-run (never injects input, memory store, no BLE):
//   U16 --fake-ax-denied: Accessibility reads as denied even in dry-run (state.ax false, key -> err ax, diag permission).
//   U21 --update-url / --update-check-now: the daily update check against a local fake endpoint (newer / same / failing),
//       reported in diag.update and the update.check event; nothing is downloaded.
//   U11 Allow Tailscale defaults off (diag.helper tailscale.allowed false), U3 now playing on by default.
//   node helper/tests/ux.mjs         (MOTE_HELPER= overrides the binary)
import { spawn } from 'node:child_process';
import crypto from 'node:crypto';
import fs from 'node:fs';
import http from 'node:http';
import os from 'node:os';
import path from 'node:path';
import readline from 'node:readline';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const BIN = process.env.MOTE_HELPER || path.join(here, '../build/MoteHelper.app/Contents/MacOS/MoteHelper');
let failed = 0;
const ok = (c, msg) => { console.log((c ? 'PASS ' : 'FAIL ') + msg); if (!c) failed++; };
const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'mote-ux-'));

async function start(extra) {
  const events = path.join(tmp, `events-${Math.random().toString(36).slice(2)}.jsonl`);
  const args = ['--dry-run', '--headless', '--no-ble', '--memory-store', '--open-pairing', '--port', '0', '--events', events, '--data-dir', tmp, ...extra];
  const proc = spawn(BIN, args, { stdio: ['ignore', 'pipe', 'pipe'] });
  let code, port;
  const ready = new Promise((res) => {
    readline.createInterface({ input: proc.stdout }).on('line', (l) => { const m = /PAIRING CODE: (\d{6})/.exec(l); if (m) { code = m[1]; if (port) res(); } });
    readline.createInterface({ input: proc.stderr }).on('line', (l) => { const m = /wifi: listening on :(\d+)/.exec(l); if (m) { port = m[1]; if (code) res(); } });
  });
  await Promise.race([ready, new Promise((_, rej) => setTimeout(() => rej(new Error('helper did not start')), 10000))]);
  return { proc, port, code, events, stop: () => proc.kill('SIGTERM') };
}

function open(port) {
  return new Promise((res, rej) => {
    const ws = new WebSocket(`ws://127.0.0.1:${port}/ws`), q = [], waiters = [];
    ws.onmessage = (e) => { const m = JSON.parse(e.data); const w = waiters.shift(); w ? w(m) : q.push(m); };
    ws.onerror = rej;
    ws.next = (ms = 3000) => new Promise((r) => { const m = q.shift(); if (m) return r(m); const t = setTimeout(() => { waiters.splice(waiters.indexOf(f), 1); r(null); }, ms); const f = (x) => { clearTimeout(t); r(x); }; waiters.push(f); });
    ws.until = async (pred, ms = 3000) => { const end = Date.now() + ms; for (;;) { const m = await ws.next(Math.max(1, end - Date.now())); if (!m || pred(m)) return m; } };
    ws.req = (m, pred) => { ws.send(JSON.stringify(m)); return ws.until(pred ?? ((x) => x.t === m.t || x.t === 'err')); };
    ws.onopen = () => res(ws);
  });
}

async function session(h) {
  const ws = await open(h.port);
  const hello = await ws.next();
  const p = await ws.req({ t: 'pair', code: h.code, name: 'ux test' }, (m) => m.t === 'paired' || m.t === 'err');
  const mac = crypto.createHmac('sha256', Buffer.from(p.token, 'hex')).update(hello.nonce).digest('hex');
  const a = await ws.req({ t: 'auth', id: p.id, mac }, (m) => m.t === 'authed' || m.t === 'err');
  ok(a.t === 'authed', 'paired and authed');
  return { ws, authed: a };
}

const events = (h) => (fs.existsSync(h.events) ? fs.readFileSync(h.events, 'utf8').split('\n').filter(Boolean).map((l) => JSON.parse(l)) : []);
const until = async (f, ms = 5000) => { const end = Date.now() + ms; for (;;) { const v = f(); if (v || Date.now() > end) return v; await new Promise((r) => setTimeout(r, 100)); } };

// --- run 1: defaults + --fake-ax-denied (U16, U11, U3)
{
  const h = await start(['--fake-ax-denied']);
  try {
    const { ws, authed } = await session(h);
    ok(authed.ax === false, 'U16: authed.ax is false with --fake-ax-denied');
    const k = await ws.req({ t: 'key', usage: 4, mods: 0, action: 'tap' }, (m) => m.t === 'err' || m.t === 'ok');
    ok(k && k.t === 'err' && k.code === 'ax', 'U16: key refused with err ax');
    const d = await ws.req({ t: 'diag' }, (m) => m.t === 'diag');
    ok(d?.permissions?.accessibility?.apiState === 'notDetermined' && d.permissions.accessibility.state !== 'ok', 'U16: diag permissions.accessibility is not granted ' + JSON.stringify(d?.permissions?.accessibility));
    ok(d?.features?.input?.state === 'blocked', 'U16: diag features.input is blocked ' + JSON.stringify(d?.features?.input));
    ok(d?.features?.input?.blockedBy === 'accessibility-not-granted', 'SM-24: diag.features.input.blockedBy is accessibility-not-granted ' + JSON.stringify(d?.features?.input));
    const blocked = await until(() => events(h).find((e) => e.area === 'input' && e.event === 'blocked' && e.fields.reason === 'accessibility'));
    ok(!!blocked, 'SM-24: the refused key is recorded as input/blocked{reason:accessibility}');
    ok(d?.transports?.tailscale?.allowed === false,
       'U11: Allow Tailscale connections is off when never set');
    ok(d?.labs?.nowPlaying?.enabled === true, 'U3: now playing is on by default');
    ok(d?.update && d.update.enabled === true, 'U21: diag.update present, check enabled by default');
    ws.close();
  } finally { h.stop(); }
}

// --- run 2: update check against a fake endpoint (U21)
async function updateRun(label, handler, expect) {
  let hits = 0, lastUA = '', lastAuth;
  const srv = http.createServer((req, res) => { hits++; lastUA = req.headers['user-agent'] || ''; lastAuth = req.headers.authorization; handler(req, res); });
  await new Promise((r) => srv.listen(0, '127.0.0.1', r));
  const url = `http://127.0.0.1:${srv.address().port}/repos/x/releases/latest`;
  const h = await start(['--update-url', url, '--update-check-now']);
  try {
    const ev = await until(() => events(h).find((e) => e.event === 'update.check' && e.fields.result !== 'scheduled'));
    ok(!!ev, `U21 ${label}: an update.check event is recorded`);
    expect(ev?.fields, label);
    ok(hits === 1 && lastAuth === undefined && /^MoteHelper\//.test(lastUA), `U21 ${label}: one unauthenticated request with a generic User-Agent`);
    const { ws } = await session(h);
    const d = await ws.req({ t: 'diag' }, (m) => m.t === 'diag');
    ws.close();
    return d;
  } finally { h.stop(); srv.close(); }
}
const json = (o) => (_req, res) => { res.writeHead(200, { 'content-type': 'application/json' }); res.end(JSON.stringify(o)); };
{
  const d = await updateRun('newer', json({ tag_name: 'v99.1.0', html_url: 'https://github.com/tusharpandey13/mote/releases/tag/v99.1.0' }),
    (f) => ok(f?.result === 'ok' && f.latest === '99.1.0' && f.available === true, 'U21 newer: result ok, available'));
  ok(d.update?.available === true && d.update.latest === '99.1.0', 'U21 newer: diag.update.available');
}
{
  const d = await updateRun('same', json({ tag_name: 'v0.0.1', html_url: 'https://github.com/x/y' }),
    (f) => ok(f?.result === 'ok' && f.available === false, 'U21 same/older: not available'));
  ok(d.update?.available === false, 'U21 older: diag.update.available false');
}
{
  const d = await updateRun('failing', (_q, res) => { res.writeHead(503); res.end('nope'); },
    (f) => ok(f?.result === 'error' && f.reason === 'http 503', 'U21 failure: silent, event has the reason'));
  ok(d.update?.available === false && /^error/.test(d.update.last), 'U21 failure: diag.update.last records the error');
}
{
  const d = await updateRun('garbage', (_q, res) => { res.writeHead(200); res.end('<html>'); },
    (f) => ok(f?.result === 'error' && f.reason === 'bad response', 'U21 garbage body: rejected'));
  ok(d.update?.available === false, 'U21 garbage: nothing offered');
}

// --- run 3: the check can be switched off (U21), and a real (non-dry-run) endpoint override is ignored
{
  let hits = 0;
  const srv = http.createServer((_q, res) => { hits++; res.writeHead(200); res.end('{}'); });
  await new Promise((r) => srv.listen(0, '127.0.0.1', r));
  const h = await start(['--update-url', `http://127.0.0.1:${srv.address().port}/`, '--update-check-now']);
  try {
    await until(() => events(h).find((e) => e.event === 'update.check' && e.fields.result !== 'scheduled'));
    h.proc.kill('SIGTERM');
  } finally { srv.close(); }
  ok(hits >= 1, 'U21: sanity, the first run hit the endpoint');
}

// --- run 4: a phone on an older protocol (J7 UP-10): auth without `client`, only the 1.0 messages, then a type this helper
// has never heard of. Nothing is `protocol/bad`, the session stays up.
{
  const h = await start([]);
  try {
    const { ws } = await session(h);   // pair + auth with no `client` field
    const pong = await ws.req({ t: 'ping', n: 7 }, (m) => m.t === 'pong');
    ok(pong?.n === 7, 'UP-10: ping answered');
    ws.send(JSON.stringify({ t: 'key', usage: 4, mods: 0, action: 'tap' }));
    ws.send(JSON.stringify({ t: 'move', dx: 3, dy: 3 }));
    const unk = await ws.req({ t: 'from.the.future', x: 1 }, (m) => m.t === 'err');
    ok(unk?.t === 'err', 'UP-10: an unknown type is answered with err, not dropped silently: ' + JSON.stringify(unk));
    const pong2 = await ws.req({ t: 'ping', n: 8 }, (m) => m.t === 'pong');
    ok(pong2?.n === 8, 'UP-10: the session is still up after the unknown type');
    await until(() => events(h).find((e) => e.area === 'protocol' && e.event === 'in'));
    ok(!events(h).some((e) => e.area === 'protocol' && e.event === 'bad'), 'UP-10: no protocol/bad event for the older phone');
    ws.close();
  } finally { h.stop(); }
}

fs.rmSync(tmp, { recursive: true, force: true });
console.log(failed ? `${failed} FAILED` : 'all passed');
process.exit(failed ? 1 : 0);
