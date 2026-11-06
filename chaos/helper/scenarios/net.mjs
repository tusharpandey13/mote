// Network faults through the TCP fault proxy (latency, jitter, frame drop, bandwidth caps, half-open,
// RST mid-frame, slow drip) plus slowloris and slow-reader attacks directly against the helper.
// Invariants: other clients stay responsive, sockets/fds don't leak, memory stays bounded, nothing is
// injected before auth and no message is executed twice.
import net from 'node:net';
import { Checker, PEERS, invariants, pct } from '../lib/check.mjs';
import { sampler, startHelper, sleep, waitTcpDrain } from '../lib/helper.mjs';
import { startProxy } from '../lib/proxy.mjs';
import { authed, connect, frame, hmac, open, pair } from '../lib/ws.mjs';

export const name = 'net';
export const weight = 0.25;

export async function run({ r, deadline }) {
  const chk = new Checker(name);
  const h = await startHelper();
  const base = { baseFds: (await h.fds()).all, baseFp: await h.footprint() };
  const mon = sampler(h, 500);
  const cred = await pair(h);

  // Observer on its own peer address: its ping RTT is the "responsive for other clients" signal.
  const obs = await authed(h, cred, { host: PEERS[2] });
  const rtts = [];
  let observing = true, obsLost = 0;
  const observer = (async () => { let n = 1; while (observing) { const t = await obs.ping(n++, 2000); if (t === null) obsLost++; else rtts.push(t); await sleep(200); } })();

  const sent = { pre: [], post: [], rst: [] };
  const fixedTail = 40000; // reserved for the fixed-length attacks below
  let rounds = 0, authedOk = 0, helloLost = 0, halfOpenHeld = [];
  while (Date.now() < deadline - fixedTail || rounds < 3) {
    rounds++;
    const rr = r.fork(`net-round-${rounds}`);
    const lo = rr.int(0, 800);
    const prof = { latency: [lo, Math.min(800, lo + rr.int(0, 200))], jitter: rr.int(0, 150), drop: rr.pick([0.01, 0.05, 0.1, 0.2, 0.3]), bps: rr.pick([0, 2000, 20000, 200000]) };
    const proxy = await startProxy({ target: { host: '127.0.0.1', port: h.port }, rng: rr, ...prof });
    const ending = rr.pick(['fin', 'rst', 'halfopen']);
    const slow = (prof.latency[1] + prof.jitter) * 2 + 3000;
    const clients = await Promise.all(Array.from({ length: 4 }, async (_, k) => {
      let c;
      try { c = await connect({ port: h.port, connectVia: { host: '127.0.0.1', port: proxy.port }, timeout: slow + 4000 }); } catch { return null; }
      const hello = await c.next(slow);
      const tag = `${rounds}-${k}`;
      for (let i = 0; i < 2; i++) { const mk = `MKU-${tag}-${i}`; sent.pre.push(mk); c.send({ t: 'text', s: mk }); }
      if (!hello?.nonce) { helloLost++; return c; }
      c.send({ t: 'auth', id: cred.id, mac: hmac(cred.token, hello.nonce) });
      const a = await c.until((m) => m.t === 'authed' || m.t === 'err' && m.ref === 'auth', slow);
      if (a?.t !== 'authed') return c;
      authedOk++;
      for (let i = 0; i < 10; i++) { const mk = `MKN-${tag}-${i}`; sent.post.push(mk); c.send({ t: 'text', s: mk }); }
      return c;
    }));
    await sleep(prof.latency[1] + prof.jitter + 300);
    if (ending === 'fin') for (const c of clients) c?.close();
    else if (ending === 'rst') {
      for (const c of clients) {
        if (!c) continue;
        const mk = `MKR-${rounds}-${sent.rst.length}`; sent.rst.push(mk);
        const f = frame(1, JSON.stringify({ t: 'text', s: mk }));
        c.raw(f.subarray(0, f.length - 3)); // all but the last 3 bytes: the message is never complete
        await sleep(20);
        c.reset();
      }
    } else {
      proxy.halfOpenAll();
      await sleep(1500);
      halfOpenHeld.push((await h.fds()).established);
      for (const c of clients) c?.destroy();
    }
    await proxy.close();
    chk.cases += 4;
  }
  chk.metric('rounds', rounds);
  chk.metric('authedThroughFaults', `${authedOk}/${rounds * 4} (hello lost ${helloLost})`);

  // RST right after a message (regression): every reset connection must release its socket.
  {
    let n = 0;
    await Promise.all(PEERS.map(async (host) => {
      for (let i = 0; i < 100; i++) {
        let c; try { c = await open({ port: h.port, host }); } catch { await sleep(5); continue; }
        c.send({ t: 'text', s: 'MKU-rst' }); sent.pre.push('MKU-rst'); n++;
        c.reset();
      }
    }));
    const f = await waitTcpDrain(h, 2, 5000); // listener + observer
    chk.ok(f.tcp <= 2, `RST after a message: no socket left behind (${n} resets)`, f);
  }

  // Slowloris on HTTP: 8 sockets (the per-peer cap) drip a never-ending request header.
  const sl = await Promise.all(Array.from({ length: 8 }, () => new Promise((res) => {
    const s = net.connect({ host: '127.0.0.1', port: h.port }); const t0 = Date.now();
    s.on('error', () => {});
    const hdr = 'GET / HTTP/1.1\r\nHost: 127.0.0.1\r\nX-Slow: ' + 'a'.repeat(100);
    let i = 0;
    const iv = setInterval(() => { if (!s.destroyed) s.write(hdr[i++ % hdr.length]); }, 300);
    s.on('close', () => { clearInterval(iv); res(Date.now() - t0); });
    setTimeout(() => { clearInterval(iv); s.destroy(); res(null); }, 15000);
  }).then((v) => v)).concat([(async () => {
    // During the attack another peer can still connect and authenticate.
    await sleep(1500);
    try { const c = await authed(h, cred, { host: PEERS[1] }); const t = await c.ping(); c.close(); return t === null ? -1 : 0; } catch { return -1; }
  })()]));
  const other = sl.pop();
  chk.ok(sl.every((ms) => ms !== null && ms < 12000), 'HTTP slowloris: helper drops header drippers within ~10 s', sl);
  chk.ok(other === 0, 'HTTP slowloris: other peers can still connect and authenticate');

  // Slow drip on WebSocket: an authed client whose every byte arrives 15 ms apart.
  {
    const proxy = await startProxy({ target: { host: '127.0.0.1', port: h.port }, rng: r.fork('drip'), drip: { bytes: 1, ms: 15 } });
    let ok = false;
    try {
      const c = await connect({ port: h.port, connectVia: { host: '127.0.0.1', port: proxy.port }, timeout: 9000 });
      const hello = await c.next(8000);
      const a = await c.req({ t: 'auth', id: cred.id, mac: hmac(cred.token, hello.nonce) }, 8000);
      if (a?.t === 'authed') { for (let i = 0; i < 3; i++) { const mk = `MKD-${i}`; sent.post.push(mk); c.send({ t: 'text', s: mk }); } ok = (await c.ping(5, 10000)) !== null; }
      await sleep(200);
      ok = ok && [0, 1, 2].every((i) => h.markers.get(`MKD-${i}`) === 1);
      c.close();
    } catch {}
    chk.ok(ok, 'WS slow drip (1 byte / 15 ms): handshake, auth and input each complete exactly once');
    await proxy.close();
  }
  // Slow drip of an unauthenticated WS frame: held until the 5-minute auth deadline (documented limit).
  {
    const c = await open({ port: h.port, host: PEERS[0] });
    const f = frame(1, JSON.stringify({ t: 'ping', n: 1, pad: 'x'.repeat(500) }));
    for (let i = 0; i < 20; i++) { c.raw(f.subarray(i, i + 1)); await sleep(200); }
    chk.ok(!c.closed && h.alive(), 'unauth WS drip: connection still open after 4 s (bounded only by the 5 min auth timeout)');
    c.destroy();
  }

  // Slow reader (regression): an unauthenticated peer floods 45 KB pings and never reads the pongs.
  for (const variant of ['json-ping', 'ws-ping-frame']) {
    const fp0 = await h.footprint();
    const closes0 = h.log.filter((l) => l.includes("isn't reading replies")).length;
    const c = await connect({ port: h.port, host: PEERS[0], read: false });
    const big = variant === 'json-ping'
      ? frame(1, JSON.stringify({ t: 'ping', n: r.fork(variant).bytes(33000).toString('base64') }))
      : frame(9, r.fork(variant).bytes(65000));
    const t0 = Date.now(); let bytes = 0;
    while (Date.now() - t0 < 4000 && !c.sock.destroyed) {
      if (!c.sock.write(big)) {
        await new Promise((res) => { const done = () => { c.sock.off('drain', done); clearTimeout(t); res(); }; const t = setTimeout(done, 300); c.sock.on('drain', done); });
      }
      bytes += big.length;
    }
    const fp1 = await h.footprint();
    const fds = await h.fds();
    c.destroy();
    chk.ok(fp1 - fp0 < 64 * 1024, `slow reader (${variant}): helper memory bounded (+${Math.round((fp1 - fp0) / 1024)} MB after ${Math.round(bytes / 1e6)} MB sent)`);
    chk.ok(h.log.filter((l) => l.includes("isn't reading replies")).length > closes0, `slow reader (${variant}): helper closes the non-reading peer`);
    chk.ok(fds.established <= 2, `slow reader (${variant}): connection dropped`, fds);
  }

  // HTTP pipelining without reading: many GETs in one write; memory must stay bounded.
  {
    const fp0 = await h.footprint();
    const s = net.connect({ host: '127.0.0.1', port: h.port });
    s.on('error', () => {});
    s.pause();
    s.write(Buffer.from('GET / HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n'.repeat(4000)));
    await sleep(1500);
    const fp1 = await h.footprint();
    s.destroy();
    chk.ok(fp1 - fp0 < 32 * 1024, `HTTP pipelining without reading: memory bounded (+${Math.round((fp1 - fp0) / 1024)} MB)`);
  }

  observing = false; await observer; obs.close();
  await sleep(500);
  const pre = sent.pre.concat(sent.rst).filter((mk) => h.markers.has(mk));
  const dup = sent.post.filter((mk) => (h.markers.get(mk) || 0) > 1);
  chk.ok(pre.length === 0, `no input injected before auth / from incomplete frames (${sent.pre.length + sent.rst.length} probes)`, pre.slice(0, 5));
  chk.ok(dup.length === 0, `no message executed twice (${sent.post.length} sent)`, dup.slice(0, 5));
  chk.metric('postExecuted', `${sent.post.filter((mk) => h.markers.get(mk) === 1).length}/${sent.post.length}`);
  chk.ok(obsLost === 0 && pct(rtts, 99) < 500, 'observer stayed responsive (no lost pings, p99 < 500 ms)', { obsLost, p99: pct(rtts, 99) });
  chk.metric('observerRtt', { p50: pct(rtts, 50), p99: pct(rtts, 99), max: pct(rtts, 100) });
  if (halfOpenHeld.length) {
    chk.metric('halfOpenEstablished', halfOpenHeld);
    chk.note('half-open: an authenticated session whose peer silently vanished is held until the peer sends or the OS gives up; there is no idle timeout or keepalive (unauthenticated: 5 min)');
  }
  const m = mon.stop();
  chk.metric('rssMaxMB', Math.round(m.rssMax / 1024));
  await invariants(h, chk, base);
  await h.stop();
  return chk;
}
