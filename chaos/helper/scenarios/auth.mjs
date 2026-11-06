// Challenge-response edge cases: replayed HMAC, stale nonce, nonce uniqueness, identity switching,
// pre-auth input. Clock skew: the protocol carries no timestamps (the challenge is a per-connection
// random nonce), so there is nothing to skew; see chaos/README.md.
import { Checker, PEERS, invariants } from '../lib/check.mjs';
import { startHelper, sleep } from '../lib/helper.mjs';
import { authed, hmac, open, pair } from '../lib/ws.mjs';

export const name = 'auth';
export const weight = 0.05;

export async function run({ deadline }) {
  const chk = new Checker(name);
  const h = await startHelper();
  const base = { baseFds: (await h.fds()).all, baseFp: await h.footprint() };
  const cred = await pair(h);

  // Pre-auth input on a fresh session is refused, never injected.
  const pre = await open({ port: h.port, host: PEERS[0] });
  for (const m of [{ t: 'text', s: 'MKpre-1' }, { t: 'key', usage: 4 }, { t: 'macro', steps: [{ t: 'text', s: 'MKpre-2' }] }, { t: 'move', dx: 1, dy: 1 }]) {
    const e = await pre.req(m);
    chk.ok(e?.t === 'err' && e.code === 'unauth', `pre-auth ${m.t} -> err unauth`, e);
  }

  // Regression: numbers that parse to +-inf (-1e400) in a pre-auth ping used to abort the helper
  // (NSJSONSerialization raises an ObjC exception writing the echoed pong).
  for (const n of ['-1e400', '[-1e400]', '{"a":-1e400}']) {
    const pong = await pre.req(`{"t":"ping","n":${n}}`, 2000);
    await sleep(50);
    chk.ok(pong?.t === 'pong' && h.alive(), `pre-auth ping n=${n}: answered, helper alive`, { pong, exit: h.exit });
    if (!h.alive()) return chk;
  }

  // A captured auth replayed on a new connection (different nonce) is rejected.
  const a = await authed(h, cred, { host: PEERS[0] });
  const captured = { t: 'auth', id: cred.id, mac: hmac(cred.token, a.hello.nonce) };
  const b = await open({ port: h.port, host: PEERS[1] });
  chk.ok(b.hello.nonce !== a.hello.nonce, 'nonce differs per connection');
  const rep = await b.req(captured);
  chk.ok(rep?.code === 'auth', 'replayed HMAC (other connection’s challenge) rejected', rep);
  // Stale nonce: MAC over the nonce of a connection that has already closed.
  const old = await open({ port: h.port, host: PEERS[1] });
  const staleNonce = old.hello.nonce; old.close();
  const st = await b.req({ t: 'auth', id: cred.id, mac: hmac(cred.token, staleNonce) });
  chk.ok(st?.code === 'auth', 'MAC over a stale nonce rejected', st);
  // Wrong id with a valid MAC shape; uppercase hex of the right MAC is fine (same bytes).
  const wid = await b.req({ t: 'auth', id: 'not-a-device', mac: hmac(cred.token, b.hello.nonce) });
  chk.ok(wid?.code === 'auth', 'valid MAC with unknown id rejected', wid);
  const e1 = await b.req({ t: 'text', s: 'MKpre-3' });
  chk.ok(e1?.code === 'unauth', 'still unauthenticated after failed auths', e1);
  b.close(); pre.close();

  // An authed session ignores a second auth (no identity switch, no error spam).
  const second = await a.req({ t: 'auth', id: 'other', mac: '00' }, 500);
  chk.ok(second === null, 'second auth on an authed session is ignored', second);
  a.send({ t: 'text', s: 'MKpost-1' });
  chk.ok((await a.ping()) !== null, 'authed session still works');
  a.close();

  // Nonce uniqueness and format across many connections (spread over peers: 8 per peer cap).
  const seen = new Set();
  let n = 0, bad = 0;
  while (n < 600 && Date.now() < deadline - 3000) {
    const cs = await Promise.all(PEERS.map((host) => open({ port: h.port, host }).catch(() => null)));
    for (const c of cs) { if (!c) continue; n++; if (!/^[0-9a-f]{32}$/.test(c.hello?.nonce ?? '') || seen.has(c.hello.nonce)) bad++; seen.add(c.hello?.nonce); c.destroy(); }
  }
  chk.ok(bad === 0 && n > 50, `${n} nonces: 32 hex, all distinct`, { n, bad });
  chk.cases = n + 10;

  await sleep(300);
  chk.ok(!h.markers.has('MKpre-1') && !h.markers.has('MKpre-2') && !h.markers.has('MKpre-3'), 'no pre-auth marker injected');
  chk.ok(h.markers.get('MKpost-1') === 1, 'post-auth marker injected exactly once', h.markers.get('MKpost-1'));
  chk.ok(!h.log.some((l) => /DRY key usage=4 /.test(l)), 'no pre-auth key injected');
  chk.note('clock skew: N/A, the HMAC challenge is a random per-connection nonce with no timestamp');
  await invariants(h, chk, base);
  await h.stop();
  return chk;
}
