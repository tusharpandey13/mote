// Unreadable Keychain (owner decisions U5/U6/U12, docs/pairing.md section 7) against the real helper in --dry-run.
// --fake-keychain STATE replaces the login Keychain item with a simulation (no macOS prompt, no real Keychain access).
//   node helper/tests/keychain.mjs [basePort=8830]     (MOTE_HELPER= overrides the binary)
import { spawn } from 'node:child_process';
import crypto from 'node:crypto';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const bin = process.env.MOTE_HELPER || path.join(here, '../build/MoteHelper.app/Contents/MacOS/MoteHelper');
let port = +(process.argv[2] || 8830);
let failed = 0;
const ok = (c, msg) => { console.log((c ? 'PASS ' : 'FAIL ') + msg); if (!c) failed++; };
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'mote-kc-'));

function open(p) {
  return new Promise((res, rej) => {
    const ws = new WebSocket(`ws://127.0.0.1:${p}/ws`), q = [], waiters = [];
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
async function up(p) {
  for (let i = 0; i < 100; i++) { try { const ws = await open(p); await ws.next(); ws.close(); return; } catch { await sleep(100); } }
  throw new Error(`helper on ${p} did not start`);
}

const keypair = () => {
  const { publicKey, privateKey } = crypto.generateKeyPairSync('ed25519');
  return { pub: publicKey.export({ format: 'der', type: 'spki' }).subarray(-32).toString('hex'), privateKey };
};
const fpOf = (pubHex) => crypto.createHash('sha256').update(Buffer.from(pubHex, 'hex')).digest().subarray(0, 16).toString('hex');
const sign = (k, s) => crypto.sign(null, Buffer.from(s), k.privateKey).toString('hex');
// The simulation's seed identity: 32 bytes of 0x07 (FakeKeychain.seedIdentity).
const seedPub = (() => {
  const priv = crypto.createPrivateKey({ key: Buffer.concat([Buffer.from('302e020100300506032b657004220420', 'hex'), Buffer.alloc(32, 7)]), format: 'der', type: 'pkcs8' });
  return crypto.createPublicKey(priv).export({ format: 'der', type: 'spki' }).subarray(-32).toString('hex');
})();
const seedFp = fpOf(seedPub);

async function roomInWindow(need = 8) {
  const left = 30 - ((Date.now() / 1000) % 30);
  if (left < need) await sleep(left * 1000 + 300);
}
async function proxToken(p) {
  const ws = await open(p); await ws.next();
  const r = await ws.req({ t: 'labs.pair.prox' });
  ws.close();
  return r.v;
}
/** Handshake (valid proximity token) and the decision after `labs.pair.hi`. */
async function attempt(p, k = keypair()) {
  await roomInWindow();
  const prox = await proxToken(p);
  const ws = await open(p);
  const hello = await ws.next();
  const proof = `mote-labs-pair-v1|client|${hello.hostId}|${hello.nonce}|${k.pub}`;
  const hi = await ws.req({ t: 'labs.pair.hello', pub: k.pub, name: 'Phone', cn: crypto.randomBytes(16).toString('hex'), sig: sign(k, proof), prox });
  const d = await ws.next();
  if (d.t === 'labs.pair.ok') await ws.next();   // authed
  return { ws, hi, d, k };
}

async function scenario(state, flags, fn) {
  const events = path.join(tmp, `ev-${state}-${port}.jsonl`);
  const p = port++;
  const h = spawn(bin, ['--dry-run', '--headless', '--no-ble', '--memory-store', '--fake-proximity', '--fake-tailscale-owner', 'self', '--stdin-control',
    '--fake-keychain', state, '--events', events, '--port', String(p), ...flags]);
  h.stderr.on('data', () => {});
  const evs = () => (fs.existsSync(events) ? fs.readFileSync(events, 'utf8').split('\n').filter(Boolean).map((l) => JSON.parse(l)) : []);
  const kc = (name) => evs().filter((e) => e.area === 'keychain' && e.event === name);
  try { await up(p); await sleep(300); await fn({ p, h, kc, evs }); } finally { h.kill(); }
}
const diag = async (ws) => ws.req({ t: 'diag' });

// found: seed identity, no prompt, trusted by proximity.
await scenario('found', [], async ({ p, kc }) => {
  const r = await attempt(p);
  ok(r.hi.fp === seedFp && r.d.t === 'labs.pair.ok' && r.d.via === 'nearby', 'found: seed identity kept, proximity trusts');
  ok(r.hi.ephemeral === undefined && r.hi.newKey === undefined, 'found: hi has no ephemeral / newKey');
  const d = await diag(r.ws);
  ok(d.keychain.readResult === 'found' && d.keychain.ephemeral === false && d.features.keychain.state === 'ok', 'found: diag ok');
  ok(kc('ephemeral.enter').length === 0 && kc('interactive').length === 0, 'found: no ephemeral, no interactive read');
  r.ws.close();
});

// notfound: a fresh identity is created and saved once.
await scenario('notfound', [], async ({ p, kc }) => {
  const r = await attempt(p);
  ok(r.hi.fp !== seedFp && r.d.t === 'labs.pair.ok', 'notfound: new identity, proximity trusts');
  ok(kc('write').some((e) => e.fields.ok === true), 'notfound: item created');
  r.ws.close();
});

// partition mismatch: ONE interactive read, item rewritten, identity and lists preserved.
await scenario('partitionmismatch', [], async ({ p, kc }) => {
  const r = await attempt(p);
  ok(r.hi.fp === seedFp && r.hi.ephemeral === undefined && r.d.via === 'nearby', 'partitionmismatch: seed identity preserved (not re-minted), not ephemeral');
  ok(kc('read').filter((e) => e.fields.interactive === true).length === 1, 'partitionmismatch: exactly one interactive read');
  ok(kc('rewrite').some((e) => e.fields.ok === true && e.fields.verified === true), 'partitionmismatch: item rewritten and verified');
  const d = await diag(r.ws);
  ok(d.keychain.readResult === 'found' && d.keychain.interactiveRead === 'ok' && d.features.keychain.state === 'ok', 'partitionmismatch: diag found / interactiveRead ok');
  r.ws.close();
});

// denied / error: ephemeral, never saves, never trusts automatically, Repair ends it.
for (const state of ['denied', 'error']) {
  await scenario(state, [], async ({ p, kc, evs }) => {
    const r = await attempt(p);
    ok(r.hi.t === 'labs.pair.hi' && r.hi.ephemeral === true && r.hi.fp !== seedFp, `${state}: per-process identity marked ephemeral`);
    ok(r.d.t === 'labs.pair.pending', `${state}: valid proximity token AND same Tailscale owner are not enough: Allow needed`);
    r.ws.close();
    ok(kc('ephemeral.enter').filter((e) => !e.fields.update).length === 1 && kc('write').filter((e) => e.fields.ok === true).length === 0, `${state}: ephemeral.enter, no Keychain write`);
    ok(kc('read').filter((e) => e.fields.interactive === true).length === (state === 'denied' ? 1 : 0),
      `${state}: ${state === 'denied' ? 'one interactive read' : 'no interactive read (a prompt cannot fix it)'}`);
    ok(evs().every((e) => !(e.area === 'session' && e.event === 'paired' && e.fields?.via !== 'approved')), `${state}: nobody paired`);
    // A pinned phone whose pairing is not loaded: its own error code, it keeps its token.
    const pw = await open(p); await pw.next();
    const ae = await pw.req({ t: 'auth', id: 'nobody', mac: '00' });
    ok(ae.t === 'err' && ae.code === 'keychain-unavailable' && ae.ephemeral === true, `${state}: auth gets err keychain-unavailable (ephemeral)`);
    pw.close();
  });
  await scenario(state, ['--fake-approve'], async ({ p, h, kc }) => {
    const r = await attempt(p);
    ok(r.d.t === 'labs.pair.pending', `${state}: pending`);
    const ok2 = await r.ws.next();
    ok(ok2.t === 'labs.pair.ok' && ok2.via === 'approved', `${state}: explicit Allow pairs (via approved)`);
    await r.ws.next();   // authed
    const d = await diag(r.ws);
    const f = d.features.keychain;
    ok(f.state === 'blocked' && f.blockedBy === 'keychain-unreadable' && f.fix === 'repair-keychain' && /^Pairings aren't being saved: Keychain unavailable/.test(f.detail),
      `${state}: diag keychain blocked / keychain-unreadable / repair-keychain`);
    ok(d.keychain.ephemeral === true && d.keychain.readResult === 'error', `${state}: diag keychain.ephemeral`);
    ok(kc('write').filter((e) => e.fields.ok === true).length === 0, `${state}: still no Keychain write after the Allow`);
    h.stdin.write('repair-keychain\n');
    await sleep(500);
    const d2 = await diag(r.ws);
    ok(d2.keychain.ephemeral === false && d2.features.keychain.state === 'ok', `${state}: Repair leaves ephemeral mode`);
    ok(kc('repair').some((e) => e.fields.ok === true) && kc('ephemeral.leave').length === 1, `${state}: repair + ephemeral.leave events`);
    r.ws.close();
    const again = await attempt(p);
    ok(again.d.t === 'labs.pair.ok' && again.d.via === 'nearby', `${state}: after Repair proximity trusts a new phone again`);
    again.ws.close();
  });
}

// "Try reading Keychain again": denied first, the simulation then answers the retry (non-destructive, one interactive read).
await scenario('denied', ['--fake-approve'], async ({ p, h, kc }) => {
  const r = await attempt(p);
  await r.ws.next(); await r.ws.next();   // ok (approved), authed
  const d = await diag(r.ws);
  ok(d.keychain.ephemeral === true && d.keychain.interactiveRead === 'denied', 'reread: denied first');
  h.stdin.write('reread-keychain\n');
  await sleep(600);
  const d2 = await diag(r.ws);
  ok(kc('reread').length === 1 && kc('read').filter((e) => e.fields.interactive === true).length === 2, 'reread: one more interactive read');
  ok(d2.keychain.ephemeral === true && d2.keychain.interactiveRead === 'denied' && kc('write').filter((e) => e.fields.op === 'delete').length === 0,
    'reread: still denied, nothing deleted');
  r.ws.close();
});

fs.rmSync(tmp, { recursive: true, force: true });
console.log(failed ? `${failed} FAILED` : 'ALL PASS');
process.exit(failed ? 1 : 0);
