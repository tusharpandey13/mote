// Protocol 1.5 diagnostics against the real helper in --dry-run (nothing is injected, memory store, no BLE):
// diag snapshot, corr echo, diag.subscribe replay + live push + unsubscribe, the unauth error, and --diag / --events.
//   node helper/tests/diag.mjs         (MOTE_HELPER= overrides the binary)
import { spawn, spawnSync } from 'node:child_process';
import crypto from 'node:crypto';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import readline from 'node:readline';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const BIN = process.env.MOTE_HELPER || path.join(here, '../build/MoteHelper.app/Contents/MacOS/MoteHelper');
let failed = 0;
const ok = (c, msg) => { console.log((c ? 'PASS ' : 'FAIL ') + msg); if (!c) failed++; };

// --diag: prints JSON and exits, nothing running, runtime sections unknown.
{
  const r = spawnSync(BIN, ['--diag', '--memory-store'], { encoding: 'utf8', timeout: 15000 });
  let d = null; try { d = JSON.parse(r.stdout); } catch {}
  ok(r.status === 0 && d && d.t === 'diag' && d.v === 1, '--diag prints a snapshot and exits 0');
  ok(d && d.running === false && d.source === 'cli', '--diag: running=false, source=cli');
  ok(d && ['ok', 'blocked', 'off', 'unknown'].includes(d.features?.input?.state), '--diag: features.input has a valid state');
  ok(d && d.features.wifi.state === 'unknown', '--diag: runtime features are unknown');
  ok(d && d.permissions.localNetwork.inferred === true && d.permissions.accessibility.api === 'AXIsProcessTrusted', '--diag: permissions carry api/raw, localNetwork is inferred');
  ok(d && d.signing && 'identity' in d.signing && 'expectedKeychainPartition' in d.signing, '--diag: signing section');
}

const eventsFile = path.join(fs.mkdtempSync(path.join(os.tmpdir(), 'mote-diag-')), 'events.jsonl');
const args = ['--dry-run', '--headless', '--no-ble', '--memory-store', '--open-pairing', '--port', '0', '--events', eventsFile];
if (!args.includes('--dry-run')) throw new Error('refusing to run the helper without --dry-run');
const proc = spawn(BIN, args, { stdio: ['ignore', 'pipe', 'pipe'] });
let code, port;
const ready = new Promise((res) => {
  readline.createInterface({ input: proc.stdout }).on('line', (l) => { const m = /PAIRING CODE: (\d{6})/.exec(l); if (m) { code = m[1]; if (port) res(); } });
  readline.createInterface({ input: proc.stderr }).on('line', (l) => { const m = /wifi: listening on :(\d+)/.exec(l); if (m) { port = m[1]; if (code) res(); } });
});
const stop = () => proc.kill('SIGTERM');
try {
  await Promise.race([ready, new Promise((_, rej) => setTimeout(() => rej(new Error('helper did not start')), 10000))]);
} catch (e) { stop(); throw e; }

const open = () => new Promise((res, rej) => {
  const ws = new WebSocket(`ws://127.0.0.1:${port}/ws`), q = [], waiters = [];
  ws.onmessage = (e) => { const m = JSON.parse(e.data); const w = waiters.shift(); w ? w(m) : q.push(m); };
  ws.onerror = rej;
  ws.next = (ms = 3000) => new Promise((r) => { const m = q.shift(); if (m) return r(m); const t = setTimeout(() => { waiters.splice(waiters.indexOf(f), 1); r(null); }, ms); const f = (x) => { clearTimeout(t); r(x); }; waiters.push(f); });
  ws.req = (m) => { ws.send(JSON.stringify(m)); return ws.next(); };
  ws.until = async (pred, ms = 3000) => { const end = Date.now() + ms; for (;;) { const m = await ws.next(Math.max(1, end - Date.now())); if (!m || pred(m)) return m; } };
  ws.onopen = () => res(ws);
});

try {
  const a = await open(); const hello = await a.next();
  // Before auth: err unauth, with corr echoed.
  const e1 = await a.req({ t: 'diag', corr: 'u1' });
  ok(e1.t === 'err' && e1.code === 'unauth' && e1.corr === 'u1', 'diag before auth: err unauth, corr echoed');
  const e2 = await a.req({ t: 'diag.subscribe', on: true });
  ok(e2.t === 'err' && e2.code === 'unauth', 'diag.subscribe before auth: err unauth');

  const p = await a.req({ t: 'pair', code, name: 'diag test' });
  const mac = crypto.createHmac('sha256', Buffer.from(p.token, 'hex')).update(hello.nonce).digest('hex');
  const au = await a.req({ t: 'auth', id: p.id, mac, client: { app: 'mote-test', proto: '1.5' } });
  ok(au.t === 'authed', 'paired and authed');

  // diag
  const d = await a.req({ t: 'diag', corr: 'c-diag', client: { app: 'mote-test', proto: '1.5' } });
  ok(d.t === 'diag' && d.v === 1 && d.corr === 'c-diag' && d.running === true && d.source === 'ws', 'diag: snapshot with corr echoed');
  ok(d.helper.protocol === '1.5' && d.helper.dryRun === true && d.helper.memoryStore === true, 'diag: helper section');
  for (const k of ['input', 'keychain', 'wifi', 'bonjour', 'bluetooth', 'tailscale', 'spaceThumbnails', 'labs.liveScreen', 'labs.context', 'labs.nowPlaying', 'labs.tvMode', 'labs.bridge'])
    ok(['ok', 'blocked', 'off', 'unknown'].includes(d.features[k]?.state), `diag: feature ${k} = ${d.features[k]?.state}`);
  ok(d.features.input.state === 'ok' && d.features.wifi.state === 'ok', 'diag: dry-run input ok, wifi listening');
  ok(d.features.bluetooth.state === 'off', 'diag: --no-ble => bluetooth off');
  ok(d.features.keychain.state === 'off' && d.keychain.memoryOnly === true, 'diag: --memory-store => keychain off');
  for (const p of ['accessibility', 'bluetooth', 'localNetwork', 'screenRecording', 'automation'])
    ok(d.permissions[p] && 'state' in d.permissions[p] && 'api' in d.permissions[p] && 'raw' in d.permissions[p] && 'apiState' in d.permissions[p], `diag: permission ${p} has state/api/raw/apiState`);
  const me = d.sessions.find((s) => s.authed);
  ok(me && me.protocol.client === '1.5' && me.protocol.clientApp === 'mote-test' && me.subscriptions.diag === false, 'diag: sessions[] shows client {app, proto}');
  ok(d.events.capacity === 5000 && d.events.head > 0, 'diag: events head/capacity');

  // corr: bad values are ignored, step does not leak into replies
  const bad = await a.req({ t: 'diag', corr: 'x'.repeat(65) });
  ok(bad.t === 'diag' && !('corr' in bad), 'corr longer than 64 chars is ignored');
  const pg = await a.req({ t: 'ping', n: 7, corr: 'c-ping' });
  ok(pg.t === 'pong' && pg.n === 7 && pg.corr === 'c-ping', 'corr echoed on pong');

  // diag.subscribe with replay
  const ack = await a.req({ t: 'diag.subscribe', on: true, since: 0, corr: 'c-sub' });
  ok(ack.t === 'diag.events' && ack.subscribed === true && ack.corr === 'c-sub' && ack.capacity === 5000 && Array.isArray(ack.events), 'diag.subscribe: ack');
  ok(ack.events.length > 0 && ack.events.every((e) => e.side === 'helper' && typeof e.seq === 'number' && typeof e.t === 'number' && e.area && e.event && 'corr' in e && typeof e.fields === 'object'), 'replay: events have the schema');
  const seqs = ack.events.map((e) => e.seq);
  ok(seqs.every((s, i) => i === 0 || s > seqs[i - 1]), 'replay: seq strictly increasing');
  ok(ack.events.some((e) => e.area === 'session' && e.event === 'authed'), 'replay: contains session.authed');
  const withCorr = ack.events.find((e) => e.corr === 'c-diag' && e.area === 'protocol' && e.event === 'in');
  ok(!!withCorr, 'replay: the diag request is logged with its corr');

  // live push: a ping with corr + step produces protocol.in carrying both
  a.send(JSON.stringify({ t: 'ping', corr: 'c-live', step: 'journey.test' }));
  const push = await a.until((m) => m.t === 'diag.events' && m.events.some((e) => e.corr === 'c-live' && e.event === 'in'), 4000);
  ok(push && push.head > 0, 'live push: diag.events batch arrives');
  const le = push?.events.find((e) => e.corr === 'c-live' && e.event === 'in');
  ok(le && le.journeyStep === 'journey.test' && le.fields.t === 'ping', 'live push: event carries corr, journeyStep and fields');
  const d2 = await (async () => { a.send(JSON.stringify({ t: 'diag' })); return a.until((m) => m.t === 'diag'); })();
  ok(d2 && d2.sessions.find((s) => s.authed)?.subscriptions.diag === true, 'diag: sessions[].subscriptions.diag true while subscribed');
  ok(!ack.events.concat(push.events).some((e) => e.area === 'protocol' && e.fields?.t === 'diag.events'), 'diag.events is never logged as an event');

  // unsubscribe
  a.send(JSON.stringify({ t: 'diag.subscribe', on: false, corr: 'c-off' }));
  const off2 = await a.until((m) => m.t === 'diag.events' && m.subscribed === false, 3000);
  ok(off2 && off2.corr === 'c-off' && off2.events.length === 0, 'diag.subscribe on:false: ack with subscribed=false');
  a.send(JSON.stringify({ t: 'ping' }));
  await a.until((m) => m.t === 'pong', 2000);
  const after = await a.next(400);
  ok(after === null || after.t !== 'diag.events', 'no events after unsubscribe');
  a.close();

  // --events file: JSONL, same schema
  await new Promise((r) => setTimeout(r, 200));
  const lines = fs.readFileSync(eventsFile, 'utf8').trim().split('\n').map((l) => JSON.parse(l));
  ok(lines.length > 5 && lines.every((e) => e.side === 'helper' && e.seq && e.area && e.event), '--events file: JSONL events');
  ok(lines.some((e) => e.area === 'lifecycle' && e.event === 'start') && lines.some((e) => e.area === 'session' && e.event === 'paired'), '--events file: lifecycle.start and session.paired');
  const text = fs.readFileSync(eventsFile, 'utf8');
  ok(!text.includes(p.token) && !text.includes(code) && !text.includes(hello.nonce), '--events file holds no token, pairing code or nonce');
} finally { stop(); }
console.log(failed ? `${failed} FAILED` : 'diag: all passed');
process.exit(failed ? 1 : 0);
