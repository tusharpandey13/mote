// Labs instant pairing (docs/labs/pairing.md) against a dry-run helper (nothing is injected):
//   MoteHelper --dry-run --headless --no-ble --memory-store --labs-pairing --fake-approve --port N
//   node helper/tests/labs-pairing.mjs N
import crypto from 'node:crypto';

const [port] = process.argv.slice(2);
const url = `ws://127.0.0.1:${port}/ws`;
let failed = 0;
const ok = (c, msg) => { console.log((c ? 'PASS ' : 'FAIL ') + msg); if (!c) failed++; };

function open() {
  return new Promise((res, rej) => {
    const ws = new WebSocket(url), q = [], waiters = [];
    ws.onmessage = (e) => { const m = JSON.parse(e.data); const w = waiters.shift(); w ? w(m) : q.push(m); };
    ws.onerror = rej;
    ws.next = (ms = 3000) => new Promise((r, j) => {
      const m = q.shift(); if (m) return r(m);
      const t = setTimeout(() => j(new Error('timeout')), ms);
      waiters.push((x) => { clearTimeout(t); r(x); });
    });
    ws.req = (m) => { ws.send(JSON.stringify(m)); return ws.next(); };
    ws.onopen = () => res(ws);
  });
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

async function handshake(k, name, tamper = false) {
  const ws = await open();
  const hello = await ws.next();
  const cn = crypto.randomBytes(16).toString('hex');
  const proof = `mote-labs-pair-v1|client|${hello.hostId}|${hello.nonce}|${k.pub}`;
  const sig = sign(k, tamper ? proof + 'x' : proof);
  const hi = await ws.req({ t: 'labs.pair.hello', pub: k.pub, name, cn, sig });
  return { ws, hello, cn, hi };
}

// info (same object as the BLE characteristic)
{
  const ws = await open();
  const hello = await ws.next();
  const info = await ws.req({ t: 'labs.pair.info' });
  ok(info.t === 'labs.pair.info' && info.v === 1 && info.id === hello.hostId && info.fp.length === 32 && info.p === +port && Array.isArray(info.lan), 'labs.pair.info carries id, fp, lan and the real port');
  const r = await ws.req({ t: 'key', usage: 4 });
  ok(r.t === 'err' && r.code === 'unauth', 'input before the handshake is rejected');
  ws.close();
}

// bad signature
{
  const { ws, hi } = await handshake(keypair(), 'evil', true);
  ok(hi.t === 'err' && hi.code === 'bad', 'tampered client proof rejected');
  ws.close();
}

// first contact: pending -> approved (fake) -> authed; helper proof verifies against fp
const phone = keypair();
{
  const { ws, hello, cn, hi } = await handshake(phone, 'node phone');
  ok(hi.t === 'labs.pair.hi' && hi.hostId === hello.hostId, 'labs.pair.hi from the helper');
  ok(fp(hi.pub) === hi.fp, 'helper fp = sha256(pub)[0:16]');
  ok(verify(hi.pub, `mote-labs-pair-v1|helper|${hello.hostId}|${hello.nonce}|${cn}|${phone.pub}`, hi.sig), 'helper proof verifies');
  ok(!verify(hi.pub, `mote-labs-pair-v1|helper|${hello.hostId}|${hello.nonce}|${'0'.repeat(32)}|${phone.pub}`, hi.sig), 'helper proof is bound to cn');
  const pend = await ws.next();
  ok(pend.t === 'labs.pair.pending' && pend.timeout === 60, 'unknown key: pending (prompt on the Mac)');
  const okm = await ws.next();
  ok(okm.t === 'labs.pair.ok' && okm.via === 'approved' && okm.id === 'k:' + fp(phone.pub), 'approved');
  const authed = await ws.next();
  ok(authed.t === 'authed' && authed.id === okm.id && authed.name === 'node phone', 'authed after labs.pair.ok');
  const r = await ws.req({ t: 'ping', n: 7 });
  ok(r.t === 'pong', 'session stays up');
  ws.send(JSON.stringify({ t: 'key', usage: 4 }));
  await new Promise((r) => setTimeout(r, 100));
  ws.close();
}

// reconnect: trusted, no prompt
{
  const { ws, hi } = await handshake(phone, 'node phone');
  ok(hi.t === 'labs.pair.hi', 'hi on reconnect');
  const okm = await ws.next();
  ok(okm.t === 'labs.pair.ok' && okm.via === 'trusted', 'known key: trusted without a prompt');
  ok((await ws.next()).t === 'authed', 'authed on reconnect');
  ws.close();
}

// prompt budget: 3 per peer per 10 min (first contact above used 1)
{
  const reasons = [];
  for (let i = 0; i < 3; i++) {
    const { ws } = await handshake(keypair(), `spam ${i}`);
    const m = await ws.next();
    reasons.push(m.t === 'labs.pair.denied' ? m.reason : m.t);
    if (m.t === 'labs.pair.pending') await ws.next(); // fake approve -> ok
    ws.close();
  }
  ok(reasons[0] === 'labs.pair.pending' && reasons[1] === 'labs.pair.pending' && reasons[2] === 'rate', `prompts are rate-limited per peer (${reasons.join(', ')})`);
}

console.log(failed ? `${failed} FAILED` : 'ALL PASS');
process.exit(failed ? 1 : 0);
