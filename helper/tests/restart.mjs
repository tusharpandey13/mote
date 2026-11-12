// RC-17: a helper restart keeps its identity (hostId, Ed25519 key, trusted phones, paired token).
//   node helper/tests/restart.mjs [port=8830]     (MOTE_HELPER= overrides the binary). The refusal without --dry-run is a unit test (ConfigSecretsDirTests): never started here.
// --secrets-dir DIR (dry-run only) stores the Vault's items as files: the real Vault logic, no Keychain, no prompt.
// Contrast: with --memory-store the same restart yields a new hostId and key, and the phone must pair again.
import { spawn } from 'node:child_process';
import crypto from 'node:crypto';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const bin = process.env.MOTE_HELPER || path.join(here, '../build/MoteHelper.app/Contents/MacOS/MoteHelper');
const port = +(process.argv[2] || 8830);
let failed = 0;
const ok = (c, msg) => { console.log((c ? 'PASS ' : 'FAIL ') + msg); if (!c) failed++; };
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function open() {
  return new Promise((res, rej) => {
    const ws = new WebSocket(`ws://127.0.0.1:${port}/ws`), q = [], waiters = [];
    ws.onmessage = (e) => { const m = JSON.parse(e.data); const w = waiters.shift(); w ? w(m) : q.push(m); };
    ws.onerror = rej;
    ws.next = () => new Promise((r) => { const m = q.shift(); m ? r(m) : waiters.push(r); });
    ws.req = (m) => { ws.send(JSON.stringify(m)); return ws.next(); };
    ws.onopen = () => res(ws);
  });
}

// Starts a helper and waits until it answers; stop() ends it and waits for exit.
async function start(storeArgs, extra = []) {
  const p = spawn(bin, ['--dry-run', '--headless', '--no-ble', '--fake-proximity', '--port', String(port), ...storeArgs, ...extra]);
  p.out = ''; p.stdout.on('data', (d) => (p.out += d)); p.stderr.on('data', () => {});
  const exited = new Promise((r) => p.on('exit', r));
  p.stop = () => { p.kill(); return exited; };
  for (let i = 0; i < 100; i++) {
    try { const ws = await open(); ws.close(); return p; } catch { await sleep(100); }
  }
  throw new Error('helper did not start');
}

const keypair = () => {
  const { publicKey, privateKey } = crypto.generateKeyPairSync('ed25519');
  return { pub: publicKey.export({ format: 'der', type: 'spki' }).subarray(-32).toString('hex'), privateKey };
};
const mac = (token, nonce) => crypto.createHmac('sha256', Buffer.from(token, 'hex')).update(nonce).digest('hex');

// Instant pairing handshake as a phone: returns {hello, hi, d} (d = labs.pair.ok / denied).
async function instant(k, withProx) {
  const ws = await open(); const hello = await ws.next();
  const m = { t: 'labs.pair.hello', pub: k.pub, name: 'restart phone', cn: crypto.randomBytes(16).toString('hex') };
  m.sig = crypto.sign(null, Buffer.from(`mote-labs-pair-v1|client|${hello.hostId}|${hello.nonce}|${k.pub}`), k.privateKey).toString('hex');
  if (withProx) m.prox = (await (async () => { const w = await open(); await w.next(); const r = await w.req({ t: 'labs.pair.prox', dw: 0 }); w.close(); return r; })()).v;
  const hi = await ws.req(m);
  const d = await ws.next(); ws.close();
  return { hello, hi, d };
}

// Legacy pairing (code, token) then auth by token: smoke.mjs style.
async function legacyPair(code) {
  const a = await open(); const hello = await a.next();
  const p = await a.req({ t: 'pair', code, name: 'restart legacy' });
  const au = await a.req({ t: 'auth', id: p.id, mac: mac(p.token, hello.nonce) });
  a.close();
  return { hello, cred: { id: p.id, token: p.token }, authed: au.t === 'authed' };
}
async function legacyAuth(cred) {
  const a = await open(); const hello = await a.next();
  const au = await a.req({ t: 'auth', id: cred.id, mac: mac(cred.token, hello.nonce) });
  a.close();
  return au.t === 'authed';
}
async function code(h) {
  for (let i = 0; i < 50 && !/PAIRING CODE\D*(\d{6})/.test(h.out); i++) await sleep(100);
  return /PAIRING CODE\D*(\d{6})/.exec(h.out)?.[1];
}

async function scenario(label, storeArgs, persists) {
  const k = keypair();
  let h = await start(storeArgs, ['--open-pairing']);
  const c = await code(h);
  ok(!!c, `${label}: pairing code printed`);
  const legacy = await legacyPair(c);
  ok(legacy.authed, `${label}: legacy phone paired and authed`);
  const first = await instant(k, true);
  ok(first.d.t === 'labs.pair.ok' && first.d.via === 'nearby', `${label}: instant phone paired (nearby)`);
  const hostId = first.hello.hostId, key = first.hi.pub;
  await h.stop();

  h = await start(storeArgs);
  const second = await instant(k, false);   // no proximity token: only a remembered key gets in
  const sameHost = second.hello.hostId === hostId, sameKey = second.hi.pub === key;
  if (persists) {
    ok(sameHost, `${label}: same hostId after restart`);
    ok(sameKey, `${label}: same identity key after restart`);
    ok(second.d.t === 'labs.pair.ok' && second.d.via === 'trusted', `${label}: instant phone reconnects without pairing again`);
    ok(await legacyAuth(legacy.cred), `${label}: legacy token still authenticates`);
  } else {
    ok(!sameHost, `${label}: new hostId after restart`);
    ok(!sameKey, `${label}: new identity key after restart`);
    ok(second.d.t === 'labs.pair.denied', `${label}: instant phone must pair again`);
    ok(!(await legacyAuth(legacy.cred)), `${label}: legacy token is gone`);
  }
  await h.stop();
}

const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'mote-rc17-'));
try {
  await scenario('secrets-dir', ['--secrets-dir', dir], true);
  ok(fs.readdirSync(path.join(dir, 'dev.mote.helper')).some((f) => f.startsWith('secrets.v4.')), 'secrets-dir: vault account secrets.v4.* written as a file');
  await scenario('memory-store', ['--memory-store'], false);
} finally {
  fs.rmSync(dir, { recursive: true, force: true });
}
console.log(failed ? `${failed} FAILED` : 'all passed');
process.exit(failed ? 1 : 0);
