// Labs › Live screen (docs/labs/live-screen.md) under faults, against a --fake-screen helper (synthetic
// frames; the real screen is never captured, nothing is clicked). Flow control must drop frames, never
// queue them: a slow link adapts, a stalled reader that keeps acking blindly is held back by the socket
// backlog guard instead of hitting the 1 MiB unread cap, a half-open viewer is dropped by the 2 s lease,
// and fuzzed labs.screen.* never crashes or hangs the helper.
import { Checker, PEERS, invariants, pct } from '../lib/check.mjs';
import { startHelper, sleep } from '../lib/helper.mjs';
import { startProxy } from '../lib/proxy.mjs';
import { authed, connect, hmac, open, pair } from '../lib/ws.mjs';

export const name = 'screen';
export const weight = 0.08;

const isFrame = (m) => m?.t === 'labs.screen.frame';
const JUNK = [0, 1, -1, 0.5, 2, 1e400, -(2 ** 53), NaN, '0.5', null, true, [], {}, 'x'.repeat(300), 'left', 'right', 'middle'];

function msg(r) {
  const t = r.pick(['labs.screen.start', 'labs.screen.stop', 'labs.screen.ack', 'labs.screen.click', 'labs.screen.frame', 'labs.screen.', 'labs.screen.x', 'labs.']);
  const m = { t };
  for (const k of ['w', 'fps', 'credits', 'seq', 'x', 'y', 'button', 'count', 'display']) if (r.chance(0.4)) m[k] = r.pick(JUNK);
  if (r.chance(0.3)) { m.x = r.next(); m.y = r.next(); }
  return m;
}

export async function run({ r, deadline }) {
  const chk = new Checker(name);
  const h = await startHelper({ extraArgs: ['--fake-screen'] });
  chk.ok(h.log.some((l) => l.includes('fake screen: fake-screen')), 'helper runs with --fake-screen');
  const base = { baseFds: (await h.fds()).all, baseFp: await h.footprint() };
  const cred = await pair(h);
  const observer = await authed(h, cred, { host: PEERS[2] });
  const rtts = [];
  let observing = true;
  const watch = (async () => { while (observing) { const t = await observer.ping(undefined, 3000); rtts.push(t ?? 3000); await sleep(100); } })();

  const T0 = Date.now(); const phase = (n) => { if (process.env.CHAOS_VERBOSE) console.error(`[screen] ${n} at ${Date.now() - T0} ms`); };
  // 1. Slow link (60 KB/s, 80-150 ms): acks flow late, the helper adapts down and keeps the session.
  {
    const proxy = await startProxy({ target: { host: '127.0.0.1', port: h.port }, rng: r.fork('slow'), latency: [80, 150], jitter: 20, bps: 60000 });
    const c = await connect({ port: h.port, connectVia: { host: '127.0.0.1', port: proxy.port }, timeout: 8000 });
    const hello = await c.next(5000);
    await c.req({ t: 'auth', id: cred.id, mac: hmac(cred.token, hello.nonce) }, 5000);
    c.send({ t: 'labs.screen.start', w: 960, fps: 15, credits: 3 });
    let frames = 0, lastFps = 15, maxGap = 0, prev = Date.now();
    const end = Date.now() + Math.max(5000, Math.min(12000, (deadline - Date.now()) / 4));
    while (Date.now() < end) {
      const m = await c.until((x) => isFrame(x) || x.t === 'labs.screen.stopped', 5000);
      if (!m || m.t !== 'labs.screen.frame') break;
      frames++; lastFps = m.fps; maxGap = Math.max(maxGap, Date.now() - prev); prev = Date.now();
      c.send({ t: 'labs.screen.ack', seq: m.seq });
    }
    chk.ok(!c.closed && frames > 5, `slow link: stream keeps going (${frames} frames)`, { frames, closed: c.closed });
    chk.ok(lastFps < 15, 'slow link: helper lowered the frame rate', lastFps);
    chk.metric('slowFrames', frames); chk.metric('slowFps', lastFps); chk.metric('slowMaxGapMs', maxGap);
    c.send({ t: 'labs.screen.stop' });
    await sleep(300);
    c.destroy(); await proxy.close();
  }

  phase('2. Stalled');
  // 2. Stalled reader that acks every seq blindly (a lying client): the backlog guard stops sends, the
  //    1 MiB unread cap is never hit, frames are dropped, and the session survives.
  {
    const c = await authed(h, cred, { host: PEERS[0] });
    c.send({ t: 'labs.screen.start', w: 960, fps: 15, credits: 3 });
    await c.until((m) => m.t === 'labs.screen.started', 2000);
    c.sock.pause();
    let seq = 0;
    const t0 = Date.now();
    while (Date.now() - t0 < 4000) { for (let i = 0; i < 4; i++) c.send({ t: 'labs.screen.ack', seq: ++seq }); await sleep(60); }
    chk.ok(!h.log.some((l) => /isn't reading replies/.test(l)), 'stalled reader: 1 MiB unread cap never hit');
    const fp = await h.footprint();
    chk.ok(fp - base.baseFp < 96 * 1024, 'stalled reader: memory bounded', Math.round((fp - base.baseFp) / 1024) + ' MB');
    c.sock.resume();
    let got = 0, bad = 0;
    for (;;) { const m = await c.next(400); if (!m) break; if (isFrame(m)) { got++; if (!Buffer.from(m.data, 'base64').subarray(0, 2).equals(Buffer.from([0xff, 0xd8]))) bad++; } }
    chk.ok(!c.closed && got > 0 && bad === 0, `stalled reader: session alive, ${got} queued frames intact`, { got, bad, closed: c.closed });
    const stop = h.waitLine(/screen: stopped \(stop\).*(\d+) dropped/, 2000);
    c.send({ t: 'labs.screen.stop' });
    const line = await stop;
    const dropped = Number(/(\d+) dropped/.exec(line ?? '')?.[1] ?? 0);
    chk.ok(dropped > 0, 'stalled reader: frames were dropped, not queued', line);
    chk.metric('stalledDropped', dropped);
    c.destroy();
  }

  phase('3. Half-open');
  // 3. Half-open viewer (stops forwarding, never FIN/RST): the 2 s lease ends the stream. One retry:
  //    on a loaded machine (swap) a whole-system stall can delay one attempt.
  const halfOpen = async (k) => {
    const proxy = await startProxy({ target: { host: '127.0.0.1', port: h.port }, rng: r.fork('half' + k) });
    const c = await connect({ port: h.port, connectVia: { host: '127.0.0.1', port: proxy.port } });
    const hello = await c.next(3000);
    await c.req({ t: 'auth', id: cred.id, mac: hmac(cred.token, hello.nonce) });
    c.send({ t: 'labs.screen.start' });
    const f = await c.until(isFrame, 2000);
    c.send({ t: 'labs.screen.ack', seq: f?.seq });
    await sleep(100);
    const gone = h.waitLine(/screen: stopped \(timeout\)/, 8000);
    const t = Date.now();
    proxy.halfOpenAll();
    const l = await gone;
    const ms = Date.now() - t;
    c.destroy(); await proxy.close();
    return l ? ms : null;
  };
  let ms = await halfOpen(1);
  if (ms === null || ms >= 3500) { chk.note(`half-open attempt 1: ${ms} ms, retrying`); ms = await halfOpen(2); }
  chk.ok(ms !== null && ms < 3500, `half-open viewer: stream stopped in ${ms} ms`, ms);
  chk.metric('halfOpenStopMs', ms);

  phase('4. Fuzz');
  // 4. Fuzz: unauthenticated always refused; authenticated never crashes or hangs.
  let cases = 0, unauthBad = 0, badReplies = [];
  {
    const u = await open({ port: h.port, host: PEERS[1] });
    for (let i = 0; i < 15; i++) { const e = await u.req(msg(r), 2000); cases++; if (!(e?.t === 'err' && e.code === 'unauth')) unauthBad++; }
    u.destroy();
    chk.ok(unauthBad === 0, 'unauth: every labs.screen.* refused with unauth', unauthBad);
    const a = await authed(h, cred, { host: PEERS[1] });
    while (Date.now() < deadline - 5000 && h.alive()) {
      for (let i = 0; i < 20; i++) a.send(msg(r));
      const n = 2e6 + cases;
      a.send({ t: 'ping', n });
      for (;;) {
        const m = await a.next(5000);
        if (!m) { badReplies.push('no pong (hang?)'); break; }
        if (m.t === 'pong' && m.n === n) break;
        if (m.t === 'err' && !['bad', 'notfound', 'notstreaming', 'unknown', 'paused', 'ax'].includes(m.code)) badReplies.push(m);
        if (m.t === 'labs.screen.frame' && r.chance(0.5)) a.send({ t: 'labs.screen.ack', seq: m.seq });
      }
      cases += 20;
      await sleep(r.int(0, 20));
    }
    a.destroy();
  }
  phase('invariants');
  chk.ok(h.alive(), 'helper survived the screen fuzz', h.exit);
  chk.ok(badReplies.length === 0, 'only expected errors, no hang', badReplies.slice(0, 3));
  observing = false; await watch;
  chk.ok(pct(rtts, 99) < 500, `observer p99 ping ${pct(rtts, 99)} ms while streaming`, pct(rtts, 99));
  chk.metric('observerP99', pct(rtts, 99));
  observer.destroy();
  chk.cases = cases;
  await invariants(h, chk, base);
  await h.stop();
  return chk;
}
