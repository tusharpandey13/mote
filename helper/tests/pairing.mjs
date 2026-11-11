// Instant pairing (docs/pairing.md) against the real helper in --dry-run (nothing is injected).
// Starts its own helpers, one per scenario, on ports base..base+10 (default 8800..8810):
//   MoteHelper --dry-run --headless --no-ble --memory-store --fake-proximity --port N [scenario flags]
//   node helper/tests/pairing.mjs [basePort=8800]     (MOTE_HELPER= overrides the binary)
// --fake-proximity answers `labs.pair.prox {dw}` with the BLE proximity token for window w+dw (stands in for the BLE read).
import { spawn } from 'node:child_process';
import crypto from 'node:crypto';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const bin = process.env.MOTE_HELPER || path.join(here, '../build/MoteHelper.app/Contents/MacOS/MoteHelper');
const basePort = +(process.argv[2] || 8800);
let failed = 0;
const ok = (c, msg) => { console.log((c ? 'PASS ' : 'FAIL ') + msg); if (!c) failed++; };
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function startHelper(port, extra) {
  const p = spawn(bin, ['--dry-run', '--headless', '--no-ble', '--memory-store', '--fake-proximity', '--port', String(port), ...extra]);
  p.logs = ''; p.out = '';
  p.stderr.on('data', (d) => (p.logs += d));
  p.stdout.on('data', (d) => (p.out += d));
  // Lines `NEW DEVICE: {json}` printed so far.
  p.newDevices = () => [...p.out.matchAll(/^NEW DEVICE: (.*)$/gm)].map((m) => JSON.parse(m[1]));
  return p;
}

function open(port) {
  return new Promise((res, rej) => {
    const ws = new WebSocket(`ws://127.0.0.1:${port}/ws`), q = [], waiters = [];
    ws.onmessage = (e) => { const m = JSON.parse(e.data); const w = waiters.shift(); w ? w(m) : q.push(m); };
    ws.onerror = rej;
    ws.next = (ms = 3000) => new Promise((r) => {
      const m = q.shift(); if (m) return r(m);
      const t = setTimeout(() => { const k = waiters.indexOf(w); if (k >= 0) waiters.splice(k, 1); r({ t: 'timeout' }); }, ms);
      const w = (x) => { clearTimeout(t); r(x); };
      waiters.push(w);
    });
    ws.req = (m) => { ws.send(JSON.stringify(m)); return ws.next(); };
    ws.onopen = () => res(ws);
  });
}

async function up(port) {
  for (let i = 0; i < 100; i++) {
    try { const ws = await open(port); await ws.next(); ws.close(); return; } catch { await sleep(100); }
  }
  throw new Error(`helper on ${port} did not start`);
}

async function withHelper(port, extra, fn) {
  const h = startHelper(port, extra);
  try { await up(port); await fn(h); } finally { h.kill(); }
}

const keypair = () => {
  const { publicKey, privateKey } = crypto.generateKeyPairSync('ed25519');
  const pub = publicKey.export({ format: 'der', type: 'spki' }).subarray(-32).toString('hex');
  return { pub, privateKey };
};
const fp = (pubHex) => crypto.createHash('sha256').update(Buffer.from(pubHex, 'hex')).digest().subarray(0, 16).toString('hex');
const sign = (k, s) => crypto.sign(null, Buffer.from(s), k.privateKey).toString('hex');
const verify = (pubHex, s, sigHex) => {
  const key = crypto.createPublicKey({ key: Buffer.concat([Buffer.from('302a300506032b6570032100', 'hex'), Buffer.from(pubHex, 'hex')]), format: 'der', type: 'spki' });
  return crypto.verify(null, Buffer.from(s), key, Buffer.from(sigHex, 'hex'));
};

// The proximity window is 30 s; tests that depend on "the current window" first make sure >= `need` s are left in it.
async function roomInWindow(need = 10) {
  const left = 30 - ((Date.now() / 1000) % 30);
  if (left < need) await sleep(left * 1000 + 300);
}

// Reads a token like the phone reads the BLE characteristic.
async function prox(port, dw = 0) {
  const ws = await open(port); await ws.next();
  const r = await ws.req({ t: 'labs.pair.prox', dw });
  ws.close();
  return r;
}

// Opens a connection and runs the handshake; returns the socket, hello, cn and the helper's reply.
async function handshake(port, k, name, { tamper = false, prox } = {}) {
  const ws = await open(port);
  const hello = await ws.next();
  const cn = crypto.randomBytes(16).toString('hex');
  const proof = `mote-labs-pair-v1|client|${hello.hostId}|${hello.nonce}|${k.pub}`;
  const m = { t: 'labs.pair.hello', pub: k.pub, name, cn, sig: sign(k, tamper ? proof + 'x' : proof) };
  if (prox !== undefined) m.prox = prox;
  const hi = await ws.req(m);
  return { ws, hello, cn, hi };
}

// Handshake and the decision after `labs.pair.hi`: {ws, hi, d (ok | denied | pending), authed}.
async function pairAttempt(port, k, name, opts) {
  const r = await handshake(port, k, name, opts);
  if (r.hi.t !== 'labs.pair.hi') return { ...r, d: r.hi };
  r.d = await r.ws.next();
  if (r.d.t === 'labs.pair.ok') r.authed = await r.ws.next();
  return r;
}

let port = basePort;

// --- default helper: proximity decisions
await withHelper(port, ['--stdin-control'], async (h) => {
  {
    const ws = await open(port);
    const hello = await ws.next();
    const info = await ws.req({ t: 'labs.pair.info' });
    ok(info.t === 'labs.pair.info' && info.v === 1 && info.id === hello.hostId && info.fp.length === 32 && info.p === port && Array.isArray(info.lan),
      'instant pairing on by default: labs.pair.info carries id, fp, lan and the port');
    const r = await ws.req({ t: 'key', usage: 4 });
    ok(r.t === 'err' && r.code === 'unauth', 'input before the handshake is rejected');
    const p = await ws.req({ t: 'labs.pair.prox', dw: -9 });
    ok(p.t === 'labs.pair.prox' && /^-?\d+:[0-9a-f]{32}$/.test(p.v) && p.v.startsWith(`${p.w}:`), 'fake proximity read: <w>:<32 hex>');
    const p0 = await ws.req({ t: 'labs.pair.prox' });
    ok(p.w === p0.w - 3 || p.w === p0.w - 4, 'dw is clamped to -3...3');
    ws.close();
  }
  {
    const { ws, hi } = await handshake(port, keypair(), 'evil', { tamper: true });
    ok(hi.t === 'err' && hi.code === 'bad', 'tampered client proof rejected');
    ws.close();
  }

  await roomInWindow(12);
  const phone = keypair();
  const tok = (await prox(port)).v;
  {
    const { ws, hello, cn, hi } = await handshake(port, phone, 'node phone', { prox: tok });
    ok(hi.t === 'labs.pair.hi' && hi.hostId === hello.hostId, 'labs.pair.hi from the helper');
    ok(fp(hi.pub) === hi.fp, 'helper fp = sha256(pub)[0:16]');
    ok(verify(hi.pub, `mote-labs-pair-v1|helper|${hello.hostId}|${hello.nonce}|${cn}|${phone.pub}`, hi.sig), 'helper proof verifies');
    const d = await ws.next();
    ok(d.t === 'labs.pair.ok' && d.via === 'nearby' && d.id === 'k:' + fp(phone.pub), `valid proximity token: ok via nearby (${d.t} ${d.via ?? d.reason})`);
    const a = await ws.next();
    ok(a.t === 'authed' && a.id === d.id && a.name === 'node phone', 'authed after labs.pair.ok');
    ok((await ws.req({ t: 'ping', n: 7 })).t === 'pong', 'session stays up');
    await sleep(100);
    const nd = h.newDevices();
    ok(nd.length === 1 && nd[0].name === 'node phone' && nd[0].via === 'nearby' && nd[0].id === d.id, 'NEW DEVICE printed (name, via, id)');
    ws.close();
  }
  {
    const r = await pairAttempt(port, phone, 'node phone');
    ok(r.d.t === 'labs.pair.ok' && r.d.via === 'trusted' && r.authed?.t === 'authed', 'same key again without prox: trusted');
    await sleep(100);
    ok(h.newDevices().length === 1, 'no NEW DEVICE for a known key');
    r.ws.close();
  }
  {
    const r = await pairAttempt(port, keypair(), 'far phone');
    ok(r.d.t === 'labs.pair.denied' && r.d.reason === 'far', `new key without prox: denied far (${r.d.reason})`);
    r.ws.close();
  }
  for (const dw of [-1, 1]) {
    const r = await pairAttempt(port, keypair(), `skew ${dw}`, { prox: (await prox(port, dw)).v });
    ok(r.d.t === 'labs.pair.ok' && r.d.via === 'nearby', `prox for dw=${dw}: ok (clock skew)`);
    r.ws.close();
  }
  {
    const r = await pairAttempt(port, keypair(), 'old', { prox: (await prox(port, -2)).v });
    ok(r.d.t === 'labs.pair.denied' && r.d.reason === 'stale', `prox for dw=-2: denied stale (${r.d.reason})`);
    r.ws.close();
  }
  {
    const r = await pairAttempt(port, keypair(), 'replayer', { prox: tok });
    ok(r.d.t === 'labs.pair.denied' && r.d.reason === 'stale', `replayed token by a second key: denied stale (${r.d.reason})`);
    await sleep(100);
    ok(/proximity token replayed/.test(h.logs), 'logged as a replay');
    r.ws.close();
  }
  for (const junk of ['garbage', `${(await prox(port)).w}:${'0'.repeat(32)}`, 42]) {
    const r = await pairAttempt(port, keypair(), 'forger', { prox: junk });
    ok(r.d.t === 'labs.pair.denied' && r.d.reason === 'bad-proximity', `bad prox ${JSON.stringify(junk).slice(0, 16)}: denied bad-proximity`);
    r.ws.close();
  }
  ok(/labs: denied \(far\)/.test(h.logs), 'decisions are logged');
});

// --- Block: the open session is revoked, the next hello is denied blocked; Revoke all unblocks
await withHelper(++port, ['--stdin-control'], async (h) => {
  await roomInWindow(12);
  const phone = keypair();
  const r = await pairAttempt(port, phone, 'blocked phone', { prox: (await prox(port)).v });
  ok(r.d.via === 'nearby' && r.authed?.t === 'authed', 'phone trusted via nearby');
  h.stdin.write('block\n');
  const e = await r.ws.next();
  ok(e.t === 'err' && e.code === 'revoked', `block: the open session gets err revoked (${e.t} ${e.code})`);
  const again = await pairAttempt(port, phone, 'blocked phone', { prox: (await prox(port, 1)).v });
  ok(again.d.t === 'labs.pair.denied' && again.d.reason === 'blocked', `blocked key: denied blocked even with a valid token (${again.d.reason})`);
  again.ws.close();
  h.stdin.write('unblock\n');
  await sleep(200);
  const back = await pairAttempt(port, phone, 'blocked phone', { prox: (await prox(port, 1)).v });
  ok(back.d.t === 'labs.pair.ok' && back.d.via === 'nearby' && back.authed?.t === 'authed', `unblock: a fresh token connects again via nearby (${back.d.via ?? back.d.reason})`);
  h.stdin.write('block\n');
  ok((await back.ws.next()).code === 'revoked', 'blocked again');
  h.stdin.write('revoke\n');
  await sleep(200);
  const after = await pairAttempt(port, phone, 'blocked phone', { prox: (await prox(port, -1)).v });
  ok(after.d.t === 'labs.pair.ok' && after.d.via === 'nearby', 'Revoke all clears the blocked list');
  after.ws.close();
});

// --- Revoke all: the revoked phone is told `revoked` (not far/stale); a valid token still trusts it and clears the mark
await withHelper(++port, ['--stdin-control'], async (h) => {
  await roomInWindow(12);
  const phone = keypair();
  const r = await pairAttempt(port, phone, 'revoked phone', { prox: (await prox(port)).v });
  ok(r.d.via === 'nearby', 'phone trusted via nearby');
  h.stdin.write('revoke\n');
  ok((await r.ws.next()).code === 'revoked', 'revoke: the open session gets err revoked');
  const quiet = await pairAttempt(port, phone, 'revoked phone');
  ok(quiet.d.t === 'labs.pair.denied' && quiet.d.reason === 'revoked', `revoked key without prox: denied revoked (${quiet.d.reason})`);
  quiet.ws.close();
  const stale = await pairAttempt(port, phone, 'revoked phone', { prox: (await prox(port, -2)).v });
  ok(stale.d.t === 'labs.pair.denied' && stale.d.reason === 'revoked', `revoked key with a stale token: denied revoked (${stale.d.reason})`);
  stale.ws.close();
  const stranger = await pairAttempt(port, keypair(), 'stranger');
  ok(stranger.d.reason === 'far', `another key still gets far (${stranger.d.reason})`);
  stranger.ws.close();
  const back = await pairAttempt(port, phone, 'revoked phone', { prox: (await prox(port, 1)).v });
  ok(back.d.t === 'labs.pair.ok' && back.d.via === 'nearby', 'a valid token trusts the revoked phone again');
  back.ws.close();
  const known = await pairAttempt(port, phone, 'revoked phone');
  ok(known.d.via === 'trusted', 'and it stays trusted');
  known.ws.close();
});

// --- Tailscale same owner, no proximity token
await withHelper(++port, ['--fake-tailscale-owner', 'self'], async (h) => {
  const r = await pairAttempt(port, keypair(), 'ts phone');
  ok(r.d.t === 'labs.pair.ok' && r.d.via === 'tailscale' && r.authed?.t === 'authed', `same Tailscale owner without prox: ok via tailscale (${r.d.via ?? r.d.reason})`);
  await sleep(100);
  ok(h.newDevices()[0]?.via === 'tailscale', 'NEW DEVICE via tailscale');
  r.ws.close();
});
await withHelper(++port, ['--fake-tailscale-owner', 'someone-else'], async () => {
  const r = await pairAttempt(port, keypair(), 'shared-in phone');
  ok(r.d.t === 'labs.pair.denied' && r.d.reason === 'far', 'other Tailscale owner without prox: denied far');
  r.ws.close();
});

// --- Require approval: the Allow / Deny prompt, even with a valid token
await withHelper(++port, ['--require-approval', '--fake-approve'], async (h) => {
  const phone = keypair();
  const r = await pairAttempt(port, phone, 'approved phone', { prox: (await prox(port)).v });
  ok(r.d.t === 'labs.pair.pending' && r.d.timeout === 60, 'require approval: pending (prompt on the Mac) even with a token');
  const d = await r.ws.next();
  ok(d.t === 'labs.pair.ok' && d.via === 'approved' && (await r.ws.next()).t === 'authed', 'approved, then authed');
  await sleep(100);
  ok(h.newDevices()[0]?.via === 'approved', 'NEW DEVICE via approved');
  r.ws.close();
  const again = await pairAttempt(port, phone, 'approved phone');
  ok(again.d.via === 'trusted', 'approved key: trusted on reconnect');
  again.ws.close();
  // prompt budget: 3 per peer per 10 min (the first contact used 1)
  const reasons = [];
  for (let i = 0; i < 3; i++) {
    const a = await pairAttempt(port, keypair(), `spam ${i}`);
    reasons.push(a.d.t === 'labs.pair.denied' ? a.d.reason : a.d.t);
    if (a.d.t === 'labs.pair.pending') await a.ws.next();
    a.ws.close();
  }
  ok(reasons.join() === 'labs.pair.pending,labs.pair.pending,rate', `prompts are rate-limited per peer (${reasons.join(', ')})`);
});
await withHelper(++port, ['--require-approval', '--fake-approve-deny'], async () => {
  const r = await pairAttempt(port, keypair(), 'denied phone');
  const d = r.d.t === 'labs.pair.pending' ? await r.ws.next() : r.d;
  ok(r.d.t === 'labs.pair.pending' && d.t === 'labs.pair.denied' && d.reason === 'denied', 'require approval + Deny: denied');
  r.ws.close();
});

// --- Bluetooth HID setup (bt.pair / bt.info), started by a trusted phone; --fake-bt-pair: no real Bluetooth
async function trustedSession(p, name = 'bt phone') {
  await roomInWindow(5);
  const r = await pairAttempt(p, keypair(), name, { prox: (await prox(p)).v });
  ok(r.d.via === 'nearby' && r.authed?.t === 'authed', `${name}: trusted via nearby`);
  return r.ws;
}
const statuses = async (ws, n) => { const out = []; for (let i = 0; i < n; i++) out.push(await ws.next()); return out; };
await withHelper(++port, ['--fake-bt-pair'], async () => {
  {
    const ws = await open(port); await ws.next();
    const r = await ws.req({ t: 'bt.pair', name: 'Pixel' });
    ok(r.t === 'err' && r.code === 'unauth', 'bt.pair before auth: unauth');
    ok((await ws.req({ t: 'bt.info' })).code === 'unauth', 'bt.info before auth: unauth');
    ws.close();
  }
  const ws = await trustedSession(port);
  const info = await ws.req({ t: 'bt.info' });
  ok(info.t === 'bt.info' && /^([0-9A-F]{2}:){5}[0-9A-F]{2}$/.test(info.address) && typeof info.name === 'string' && info.on === true, 'bt.info: address, name, on');
  const bad = await ws.req({ t: 'bt.pair', name: '  ' });
  ok(bad.t === 'err' && bad.code === 'bad', 'bt.pair without a name: bad');
  ws.send(JSON.stringify({ t: 'bt.pair', name: ' pixel 8 ' }));
  ws.send(JSON.stringify({ t: 'bt.pair', name: 'Pixel 8' }));
  const st = await statuses(ws, 4);
  const busy = st.find((m) => m.t === 'err');
  ok(busy?.code === 'busy', 'second bt.pair while one runs: busy');
  const s = st.filter((m) => m.t === 'bt.pair.status');
  ok(s.map((m) => m.state).join() === 'searching,pairing,paired' && s[2].address === '02:00:00:00:00:01' && s[1].address === s[2].address,
    `bt.pair: searching -> pairing -> paired with the phone's address (${s.map((m) => m.state).join(', ')})`);
  const again = await ws.req({ t: 'bt.pair', name: 'Pixel 8' });
  ok(again.t === 'bt.pair.status' && again.state === 'searching', 'a new bt.pair after the first finished');
  await statuses(ws, 2);
  ws.close();
});
await withHelper(++port, ['--fake-bt-pair-fail', 'notfound'], async () => {
  const ws = await trustedSession(port);
  ws.send(JSON.stringify({ t: 'bt.pair', name: 'Pixel 8' }));
  const s = await statuses(ws, 2);
  ok(s[0].state === 'searching' && s[1].state === 'failed' && s[1].reason === 'notfound', `fake failure: failed notfound (${s[1].state} ${s[1].reason})`);
  ws.close();
});
await withHelper(++port, [], async () => {   // dry-run + headless without the fake: real Bluetooth is never touched
  const ws = await trustedSession(port);
  const r = await ws.req({ t: 'bt.pair', name: 'Pixel 8' });
  ok(r.t === 'bt.pair.status' && r.state === 'failed' && r.reason === 'unavailable', 'no fake in a headless dry run: failed unavailable');
  const info = await ws.req({ t: 'bt.info' });
  ok(info.t === 'bt.info' && info.address === null && info.on === false, 'bt.info without Bluetooth: address null, on false');
  ws.close();
});

// --- kill switch; without --fake-proximity the token never goes over the WebSocket
await withHelper(++port, ['--no-instant-pairing'], async () => {
  const ws = await open(port); await ws.next();
  const r = await ws.req({ t: 'labs.pair.info' });
  ok(r.t === 'err' && r.code === 'unauth', '--no-instant-pairing: labs.pair.* answered unauth');
  ws.close();
});
{
  const p = ++port;
  const h = spawn(bin, ['--dry-run', '--headless', '--no-ble', '--memory-store', '--port', String(p)]);
  try {
    await up(p);
    const ws = await open(p); await ws.next();
    const r = await ws.req({ t: 'labs.pair.prox' });
    ok(r.t === 'err' && r.code === 'unknown', 'no --fake-proximity: labs.pair.prox is unknown');
    ws.close();
  } finally { h.kill(); }
}

console.log(failed ? `${failed} FAILED` : 'ALL PASS');
process.exit(failed ? 1 : 0);
