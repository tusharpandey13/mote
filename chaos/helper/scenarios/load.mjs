// Load and storms: connection storm vs. the caps, concurrent pairing-code and auth brute force,
// reconnect storms, and a 1k msg/s mouse-move flood with queue-latency measurement.
import { Checker, PEERS, invariants, pct } from '../lib/check.mjs';
import { sampler, startHelper, sleep } from '../lib/helper.mjs';
import { authed, connect, open, pair } from '../lib/ws.mjs';

export const name = 'load';
export const weight = 0.15;

export async function run({ r, deadline }) {
  const chk = new Checker(name);
  for (const [label, f] of [['connect storm', connectStorm], ['pairing brute force', pairingBruteForce], ['auth brute force', authBruteForce], ['reconnect/flood', reconnectAndFlood]]) {
    try { await f(chk, r, deadline); } catch (e) { chk.ok(false, `${label} threw`, e.stack); }
  }
  return chk;
}

/** 200 concurrent connects: at most 8 per peer and 32 total get in; the rest are refused cleanly. */
async function connectStorm(chk) {
  const h = await startHelper();
  const base = { baseFds: (await h.fds()).all, baseFp: await h.footprint() };
  const cs = await Promise.all(Array.from({ length: 200 }, (_, i) =>
    connect({ port: h.port, host: PEERS[i % 3], timeout: 4000 }).then((c) => ({ c, host: PEERS[i % 3] }), (e) => ({ err: e.message }))));
  const ok = cs.filter((x) => x.c), per = {};
  for (const x of ok) per[x.host] = (per[x.host] || 0) + 1;
  const hung = cs.filter((x) => x.err === 'connect timeout').length;
  chk.ok(ok.length <= 32 && Object.values(per).every((n) => n <= 8), 'connect storm: <= 8 per peer, <= 32 total accepted', per);
  chk.ok(ok.length >= 3, 'connect storm: some connections accepted', ok.length);
  chk.ok(hung === 0, 'connect storm: refused connections are closed, not left hanging', hung);
  chk.metric('storm.accepted', ok.length);
  const hellos = await Promise.all(ok.map((x) => x.c.next(2000)));
  chk.ok(hellos.every((m) => m?.t === 'hello'), 'connect storm: every accepted connection gets hello');
  for (const x of ok) x.c.reset();
  chk.cases += 200;
  await invariants(h, chk, base);
  await h.stop();
}

/**
 * Wrong pairing codes from 3 peers x 8 sockets, all at once. The global limit must close the window
 * after exactly 10 wrong codes (no race lets an 11th through) and per peer at most 5 failures count.
 */
async function pairingBruteForce(chk, r) {
  const h = await startHelper();
  const socks = (await Promise.all(Array.from({ length: 24 }, (_, i) => open({ port: h.port, host: PEERS[i % 3] }).then((c) => ({ c, host: PEERS[i % 3] }), () => null)))).filter(Boolean);
  const counts = { code: 0, rate: 0, 'pairing-closed': 0, other: 0 }, perPeer = {};
  const wrong = () => { let c; do c = String(r.int(0, 999999)).padStart(6, '0'); while (c === h.code); return c; };
  // Fire 8 attempts per socket without waiting, then read all 8 replies.
  for (const { c } of socks) for (let i = 0; i < 8; i++) c.send({ t: 'pair', code: wrong(), name: 'brute' });
  for (const { c, host } of socks) {
    for (let i = 0; i < 8; i++) {
      const m = await c.next(3000);
      const k = m?.code in counts ? m.code : 'other';
      counts[k]++;
      if (k === 'code') perPeer[host] = (perPeer[host] || 0) + 1;
    }
  }
  chk.cases += socks.length * 8;
  chk.ok(counts.code === 10, 'pairing brute force: exactly 10 wrong codes evaluated, then the window closes', counts);
  chk.ok(Object.values(perPeer).every((n) => n <= 5), 'pairing brute force: <= 5 failures per peer per minute', perPeer);
  chk.ok(counts.other === 0, 'pairing brute force: every attempt answered with code/rate/pairing-closed', counts);
  chk.metric('brute.pair', counts);
  for (const { c } of socks) c.close();
  await sleep(300);
  // The real code is now useless: the window is closed (a human must reopen it).
  const late = await open({ port: h.port, host: PEERS[2] });
  chk.ok(late.hello.pairing === false, 'pairing window reported closed after brute force');
  const p = await late.req({ t: 'pair', code: h.code, name: 'late' });
  chk.ok(p?.t !== 'paired', 'correct code no longer pairs after the window closed', p);
  late.close();
  await invariants(h, chk, {});
  await h.stop();
}

/** Wrong MACs from 24 sockets at once: per peer at most 5 evaluated failures per minute. */
async function authBruteForce(chk, r) {
  const h = await startHelper();
  const cred = await pair(h);
  const socks = (await Promise.all(Array.from({ length: 24 }, (_, i) => open({ port: h.port, host: PEERS[i % 3] }).then((c) => ({ c, host: PEERS[i % 3] }), () => null)))).filter(Boolean);
  const per = {}, counts = { auth: 0, rate: 0, other: 0 };
  for (const { c } of socks) for (let i = 0; i < 6; i++) c.send({ t: 'auth', id: cred.id, mac: '00'.repeat(32) });
  for (const { c, host } of socks) for (let i = 0; i < 6; i++) {
    const m = await c.next(3000);
    const k = m?.code in counts ? m.code : 'other';
    counts[k]++;
    if (k === 'auth') per[host] = (per[host] || 0) + 1;
  }
  chk.cases += socks.length * 6;
  chk.ok(Object.values(per).every((n) => n <= 5) && counts.auth <= 15, 'auth brute force: <= 5 evaluated failures per peer', per);
  chk.ok(counts.other === 0, 'auth brute force: every attempt answered', counts);
  for (const { c: s } of socks) s.close();
  await sleep(300);
  // A rate-limited peer is refused even with the right MAC (documented: the limit is per address).
  const c = await open({ port: h.port, host: PEERS[0] });
  const { hmac } = await import('../lib/ws.mjs');
  const m = await c.req({ t: 'auth', id: cred.id, mac: hmac(cred.token, c.hello.nonce) });
  chk.ok(m?.code === 'rate', 'rate-limited peer stays limited (no bypass via a new socket)', m);
  c.close();
  chk.metric('brute.auth', counts);
  await invariants(h, chk, {});
  await h.stop();
}

async function reconnectAndFlood(chk, r, deadline) {
  const h = await startHelper();
  const base = { baseFds: (await h.fds()).all, baseFp: await h.footprint() };
  const mon = sampler(h, 500);
  const cred = await pair(h);

  // Reconnect storm: 6 concurrent loops per peer, connect -> auth -> (input | RST | close).
  let reconnects = 0, refused = 0, authFails = 0, sent = 0;
  const stormEnd = Math.min(Date.now() + 8000, deadline - 15000);
  await Promise.all(PEERS.flatMap((host, p) => Array.from({ length: 6 }, async (_, k) => {
    const rr = r.fork(`storm-${p}-${k}`);
    while (Date.now() < stormEnd) {
      let c;
      try { c = await authed(h, cred, { host }); } catch (e) { if (/auth failed/.test(e.message)) authFails++; else refused++; await sleep(5); continue; }
      reconnects++;
      if (rr.chance(0.5)) { c.send({ t: 'text', s: `MKstorm-${p}-${k}-${reconnects}` }); sent++; }
      const how = rr.int(0, 2);
      if (how === 0) c.reset(); else if (how === 1) c.destroy(); else { await c.ping(); c.close(); }
    }
  })));
  chk.cases += reconnects;
  chk.ok(authFails === 0, 'reconnect storm: no valid auth ever rejected', authFails);
  chk.ok(reconnects > 100, `reconnect storm: ${reconnects} reconnects (${refused} refused at the cap)`, reconnects);
  chk.metric('storm.reconnects', reconnects);
  await sleep(300);
  const dupStorm = [...h.markers].filter(([k, v]) => k.startsWith('MKstorm') && v > 1);
  chk.ok(dupStorm.length === 0, 'reconnect storm: no input executed twice', dupStorm.slice(0, 3));

  // 1k msg/s mouse-move flood for 5 s on one session, ping RTT on the same and another session.
  const flood = await authed(h, cred, { host: PEERS[0] });
  const other = await authed(h, cred, { host: PEERS[1] });
  const moves0 = h.dryByKind.move || 0;
  const rttSame = [], rttOther = [];
  let moves = 0, stop = false;
  const pinger = (async () => { let n = 1; while (!stop) { const [a, b] = await Promise.all([flood.ping(n, 3000), other.ping(n, 3000)]); n++; rttSame.push(a ?? 3000); rttOther.push(b ?? 3000); await sleep(100); } })();
  const t0 = Date.now();
  while (Date.now() - t0 < 5000) {
    for (let i = 0; i < 10; i++) { flood.send({ t: 'move', dx: r.int(-5, 5), dy: r.int(-5, 5) }); moves++; }
    await sleep(10);
  }
  const rate = Math.round(moves / ((Date.now() - t0) / 1000));
  // Burst: 20k moves at once, then time until a ping on the same socket returns (queue drain latency).
  for (let i = 0; i < 20000; i++) flood.send({ t: 'move', dx: 1, dy: 0 });
  moves += 20000;
  const drain = await flood.ping(777, 30000);
  stop = true; await pinger;
  await sleep(200);
  const executed = (h.dryByKind.move || 0) - moves0;
  chk.cases += moves;
  chk.ok(executed === moves, `move flood: every move executed once, none dropped or duplicated (${executed}/${moves})`);
  chk.ok(pct(rttSame, 99) < 250, 'move flood @1k/s: same-session queue latency p99 < 250 ms', pct(rttSame, 99));
  chk.ok(pct(rttOther, 99) < 250, 'move flood @1k/s: other-session ping p99 < 250 ms', pct(rttOther, 99));
  chk.ok(drain !== null && drain < 5000, '20k-move burst drains in < 5 s', drain);
  chk.metric('flood', { rate, p50: pct(rttSame, 50), p99: pct(rttSame, 99), otherP99: pct(rttOther, 99), burst20kMs: Math.round(drain) });
  chk.note('moves are not coalesced: each one is executed (FIFO); latency stays bounded by TCP backpressure + per-message cost');
  flood.close(); other.close();
  const m = mon.stop();
  chk.metric('rssMaxMB', Math.round(m.rssMax / 1024));
  await invariants(h, chk, base);
  await h.stop();
}
