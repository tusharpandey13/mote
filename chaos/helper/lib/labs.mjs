// Labs helpers shared by the chaos scenarios: Ed25519 instant-pairing handshake (docs/pairing.md),
// helper feature detection (flags in --help, message probes), bridge/raw sockets.
import crypto from 'node:crypto';
import os from 'node:os';
import { hasFlag, helperHelp } from './helper.mjs';
import { open } from './ws.mjs';

export function keypair() {
  const { publicKey, privateKey } = crypto.generateKeyPairSync('ed25519');
  return { pub: publicKey.export({ format: 'der', type: 'spki' }).subarray(-32).toString('hex'), privateKey };
}
export const fp = (pubHex) => crypto.createHash('sha256').update(Buffer.from(pubHex, 'hex')).digest().subarray(0, 16).toString('hex');
export const sign = (k, s) => crypto.sign(null, Buffer.from(s), k.privateKey).toString('hex');
export function verify(pubHex, s, sigHex) {
  try {
    const key = crypto.createPublicKey({ key: Buffer.concat([Buffer.from('302a300506032b6570032100', 'hex'), Buffer.from(pubHex, 'hex')]), format: 'der', type: 'spki' });
    return crypto.verify(null, Buffer.from(s), key, Buffer.from(sigHex, 'hex'));
  } catch { return false; }
}
export const clientProof = (hostId, nonce, pub) => `mote-labs-pair-v1|client|${hostId}|${nonce}|${pub}`;
export const helperProof = (hostId, nonce, cn, pub) => `mote-labs-pair-v1|helper|${hostId}|${nonce}|${cn}|${pub}`;

/**
 * Sends labs.pair.hello on an open connection `c` (from ws.open). `mutate(msg, ctx)` may tamper with it.
 * Resolves { hi, verdict } where verdict is the first labs.pair.ok / labs.pair.denied / err (or null).
 */
export async function labsHello(c, k, { name = 'chaos', mutate, waitMs = 5000 } = {}) {
  const cn = crypto.randomBytes(16).toString('hex');
  const m = { t: 'labs.pair.hello', pub: k.pub, name, cn, sig: sign(k, clientProof(c.hello.hostId, c.hello.nonce, k.pub)) };
  if (mutate) mutate(m, { cn });
  c.send(m);
  const hi = await c.until((x) => x.t === 'labs.pair.hi' || x.t === 'err', 3000);
  if (hi?.t !== 'labs.pair.hi') return { hi: null, verdict: hi, cn };
  const verdict = await c.until((x) => x.t === 'labs.pair.ok' || x.t === 'labs.pair.denied' || x.t === 'err', waitMs);
  return { hi, verdict, cn, helperSigOk: verify(hi.pub, helperProof(c.hello.hostId, c.hello.nonce, cn, k.pub), hi.sig) };
}


/**
 * Probes whether the helper knows a message type: sends it before auth and returns true unless the
 * helper treats it like any unknown type. (Labs messages are refused with `unauth` before auth when
 * off, so a probe needs an authed session `c` for those; then `unknown` means "not built".)
 */
export async function knowsMessage(c, t, extra = {}) {
  c.send({ t, ...extra });
  const m = await c.until((x) => (x.t === 'err' && x.ref === t) || x.t === t || x.t?.startsWith(t.split('.').slice(0, 2).join('.')), 1500);
  if (!m) return true;   // no error: accepted silently
  return !(m.t === 'err' && m.code === 'unknown');
}

/** First local IPv4 in 100.64/10 (Tailscale), or null. */
export function tailnetIPv4() {
  for (const list of Object.values(os.networkInterfaces())) for (const a of list ?? []) {
    if (a.family !== 'IPv4') continue;
    const [x, y] = a.address.split('.').map(Number);
    if (x === 100 && y >= 64 && y <= 127) return a.address;
  }
  return null;
}

/** First LAN IPv4 (RFC 1918) of this Mac, or null. */
export function lanIPv4() {
  for (const [name, list] of Object.entries(os.networkInterfaces())) for (const a of list ?? []) {
    if (a.family !== 'IPv4' || a.internal || !/^(en|bridge)/.test(name)) continue;
    if (/^(10\.|192\.168\.|172\.(1[6-9]|2\d|3[01])\.)/.test(a.address)) return a.address;
  }
  return null;
}

export { open, hasFlag, helperHelp };
