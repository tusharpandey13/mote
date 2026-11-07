// Connection and discovery (chaos/RUBRIC.md section 4, "connect"): Bonjour on/off (multicast blocked is
// simulated with the dry-run --no-bonjour flag), a LAN sweep field of fake listeners (slow, half-open,
// garbage, wrong app) around one real helper, sweep probes not burning the phone's limits, helper restart
// during pairing, two phones pairing at once, and a Tailscale toggle storm over this Mac's own tailnet
// address. The phone side of the sweep and of LAN<->tailnet<->BT failover lives in the Android app and is
// feature-detected (pending until `pairing/Sweep.kt` lands on this branch).
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import net from 'node:net';
import path from 'node:path';
import { Checker, PEERS, invariants, pct } from '../lib/check.mjs';
import { REPO, startHelper, sleep } from '../lib/helper.mjs';
import { hasFlag, tailnetIPv4 } from '../lib/labs.mjs';
import { authed, connect, hmac, open, pair } from '../lib/ws.mjs';

export const name = 'discovery';
export const weight = 0.06;

/** hostIds advertised as _mote._tcp right now (dns-sd -Z dumps PTR/SRV/TXT for ~ms seconds). */
function browse(ms = 2500) {
  return new Promise((res) => {
    const p = spawn('dns-sd', ['-Z', '_mote._tcp', 'local.']);
    let out = '';
    p.stdout.on('data', (d) => (out += d));
    p.on('error', () => res(null));
    setTimeout(() => { p.kill(); res(new Set([...out.matchAll(/"id=([0-9a-f]{16})"/g)].map((m) => m[1]))); }, ms);
  });
}

/** A field of fake listeners a LAN sweep must get past. Returns { ports, kinds, close }. */
async function sweepField(n, r) {
  const kinds = ['silent', 'slow', 'garbage', 'http', 'wrongapp', 'rst', 'drip'];
  const servers = [], ports = [], kindOf = [], socks = new Set();
  for (let i = 0; i < n; i++) {
    const kind = kinds[i % kinds.length];
    const s = net.createServer((c) => {
      c.on('error', () => {});
      socks.add(c); c.on('close', () => socks.delete(c));
      if (kind !== 'wrongapp') c.resume();                              // read (and drop) so FIN is seen
      if (kind === 'silent') return;                                    // accepts, never answers (half-open)
      if (kind === 'rst') { c.resetAndDestroy?.() ?? c.destroy(); return; }
      if (kind === 'garbage') { c.end(Buffer.from(Array.from({ length: 64 }, () => r.int(0, 255)))); return; }
      if (kind === 'http') { c.end('HTTP/1.1 404 Not Found\r\ncontent-length: 0\r\n\r\n'); return; }
      if (kind === 'slow') { setTimeout(() => c.destroy(), 4000); return; }
      if (kind === 'drip') { const t = setInterval(() => c.write('H'), 200); c.on('close', () => clearInterval(t)); return; }
      // wrongapp: a WebSocket server that says hello as something else
      c.once('data', () => c.write('HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n\r\n' +
        String.fromCharCode(0x81, 30) + '{"t":"hello","app":"not-mote"}'));
    });
    await new Promise((ok) => s.listen(0, '127.0.0.1', ok));
    servers.push(s); ports.push(s.address().port); kindOf.push(kind);
  }
  return { ports, kinds: kindOf, close: () => { for (const c of socks) c.destroy(); return Promise.all(servers.map((s) => new Promise((ok) => s.close(ok)))); } };
}

/** Reference probe (what a sweep does per address): WS upgrade + hello within `ms`, app must be mote-helper. */
async function probe(port, ms) {
  const t0 = performance.now();
  try {
    const c = await connect({ port, timeout: ms });
    const hello = await c.next(Math.max(1, ms - (performance.now() - t0)));
    c.destroy();
    return { mote: hello?.app === 'mote-helper', hostId: hello?.hostId, ms: performance.now() - t0 };
  } catch { return { mote: false, ms: performance.now() - t0 }; }
}

export async function run({ r, deadline }) {
  const chk = new Checker(name);
  const phase = (n) => { if (process.env.CHAOS_VERBOSE) console.error(`[discovery] ${n}`); };

  // 1. Bonjour on vs off (multicast blocked): the instance disappears from browsing, direct connects still work.
  if (await hasFlag('--no-bonjour')) {
    const on = await startHelper({ bonjour: true });
    const off = await startHelper({ extraArgs: ['--no-bonjour'] });
    const [hOn, hOff] = await Promise.all([open({ port: on.port }), open({ port: off.port })]);
    const ids = await browse();
    if (ids === null) chk.pending('bonjour browse', 'dns-sd not available');
    else {
      chk.ok(ids.has(hOn.hello.hostId), 'bonjour: a normal helper is advertised (TXT id=hostId)', [...ids], 'S2');
      chk.ok(!ids.has(hOff.hello.hostId), 'bonjour: --no-bonjour helper is not advertised (multicast blocked)', [...ids], 'S3');
    }
    const t0 = performance.now();
    const c = await open({ port: off.port });
    const ms = performance.now() - t0;
    chk.ok(c.hello?.t === 'hello', 'multicast blocked: a direct connect by address still gets hello', c.hello, 'S2 I10');
    chk.budget('lan.directHelloMs', Math.round(ms), 500);
    c.destroy(); hOn.destroy(); hOff.destroy();
    await Promise.all([on.stop(), off.stop()]);
  } else chk.pending('bonjour off', 'helper has no --no-bonjour');

  const h = await startHelper({ stdin: true, extraArgs: ['--stdin-control'] });

  phase('2. Sweep');
  // 2. Sweep field: 28 hostile listeners + the real helper; a probe identifies only the helper and each
  //    hostile port costs at most the per-port timeout (no stall on half-open / drip / slow ports).
  {
    const field = await sweepField(28, r.fork('field'));
    const PER_PORT = 600;
    const targets = [...field.ports, h.port].sort(() => r.next() - 0.5);
    const t0 = performance.now();
    const res = [];
    for (let i = 0; i < targets.length; i += 8) res.push(...await Promise.all(targets.slice(i, i + 8).map((p) => probe(p, PER_PORT).then((x) => ({ ...x, p })))));
    const total = performance.now() - t0;
    const found = res.filter((x) => x.mote);
    chk.ok(found.length === 1 && found[0].p === h.port, 'sweep field: only the real helper identifies as mote-helper', found.map((x) => x.p), 'S2');
    chk.ok(res.every((x) => x.ms < PER_PORT + 250), 'sweep field: no port stalls past the per-port timeout', res.filter((x) => x.ms >= PER_PORT + 250).map((x) => x.p), 'S3');
    chk.budget('sweep.fieldTotalMs(29 ports, 8 parallel)', Math.round(total), 4 * (PER_PORT + 250));
    chk.metric('sweepMs', Math.round(total));
    await field.close();
    const sweepKt = path.join(REPO, 'android/app/src/main/java/dev/mote/remote/pairing/Sweep.kt');
    if (!fs.existsSync(sweepKt)) chk.pending('Android LAN sweep vs this field', 'android .../pairing/Sweep.kt not on this branch (one-tap rework)');
    else chk.note('Sweep.kt present: drive it against the field from a JVM test (SweepFieldTest), see RUBRIC.md');
  }

  phase('3. Sweep');
  // 3. Sweep probes must not burn the phone's limits: 60 probe connects (hello, close, no auth) from one
  //    peer, then that peer authenticates at once (not rate limited, not capped).
  const cred = await pair(h, { host: PEERS[0] });
  {
    for (let i = 0; i < 60; i += 6) await Promise.all(Array.from({ length: 6 }, () => open({ port: h.port, host: PEERS[0] }).then((c) => c.destroy()).catch(() => {})));
    await sleep(200);
    let a; try { a = await authed(h, cred, { host: PEERS[0] }); } catch (e) { a = null; chk.note('auth after probes: ' + e.message); }
    chk.ok(!!a, 'after 60 sweep probes the same peer still authenticates (no lockout)', undefined, 'S2 I10');
    a?.destroy();
  }

  phase('4. Two');
  // 4. Two phones pairing at once with the same code: exactly one device is paired.
  {
    const old = h.code;
    h.stdin('pair\n');
    for (let i = 0; i < 40 && h.code === old; i++) await sleep(25);
    const code = h.code;
    const cs = await Promise.all([PEERS[1], PEERS[2]].map((host) => open({ port: h.port, host })));
    const replies = await Promise.all(cs.map((c, k) => c.req({ t: 'pair', code, name: 'phone' + k })));
    const paired = replies.filter((m) => m?.t === 'paired');
    chk.ok(paired.length === 1, 'two phones pairing at once: exactly one paired', replies, 'S1 I2');
    chk.ok(replies.filter((m) => m?.t !== 'paired').every((m) => m?.t === 'err' && ['pairing-closed', 'code'].includes(m.code)), 'the other gets pairing-closed', replies, 'S3 I8');
    cs.forEach((c) => c.destroy());
  }

  phase('5. Helper');
  // 5. Helper restart during pairing: SIGKILL right after `pair` is sent; the restarted helper (memory
  //    store: nothing kept) answers the old credential with a clean err auth, and a fresh pairing works.
  let h2 = h, base;
  {
    h.stdin('pair\n'); await sleep(150);
    const c = await open({ port: h.port, host: PEERS[1] });
    c.send({ t: 'pair', code: h.code, name: 'mid-pair' });
    h.kill('SIGKILL'); await h.waitExit(3000);
    const t0 = Date.now();
    h2 = await startHelper({ port: h.port, stdin: true, extraArgs: ['--stdin-control'] });
    base = { baseFds: (await h2.fds()).all, baseFp: await h2.footprint() };
    const ws = await open({ port: h2.port, host: PEERS[1] });
    const restartMs = Date.now() - t0;
    const a = await ws.req({ t: 'auth', id: cred.id, mac: hmac(cred.token, ws.hello.nonce) });
    chk.ok(a?.t === 'err' && a.code === 'auth', 'restart mid-pairing: old credential gets a clean err auth (visible, not a hang)', a, 'S2 I8');
    let fresh = null; try { fresh = await pair(h2, { host: PEERS[2] }); } catch (e) { chk.note(e.message); }
    chk.ok(!!fresh, 'restart mid-pairing: a fresh pairing works', undefined, 'S2 I10');
    chk.budget('lan.restartToHelloMs', restartMs, 2500);
    ws.destroy(); c.destroy();
  }

  phase('6. Tailscale');
  // 6. Tailscale toggle storm over this Mac's own tailnet address: tailnet sessions only while allowed,
  //    LAN (loopback) observer unaffected, nothing executed twice.
  const ts = tailnetIPv4();
  if (!ts) chk.pending('tailnet toggle storm', 'no 100.64/10 address on this Mac (Tailscale down)');
  else {
    const old = h2.code;
    h2.stdin('pair\n');
    for (let i = 0; i < 40 && h2.code === old; i++) await sleep(25);
    const cred2 = await pair(h2, { host: PEERS[0] });
    const obs = await authed(h2, cred2, { host: PEERS[0] });
    const rtts = []; let on = true;
    const watch = (async () => { while (on) { rtts.push((await obs.ping(undefined, 2000)) ?? 2000); await sleep(50); } })();
    const tryTs = async (k) => {
      try {
        const c = await connect({ port: h2.port, host: ts, hostHdr: `${ts}:${h2.port}`, timeout: 1500 });
        const hello = await c.next(1500);
        const a = hello && await c.req({ t: 'auth', id: cred2.id, mac: hmac(cred2.token, hello.nonce) }, 1500);
        if (a?.t === 'authed') c.send({ t: 'text', s: `MKts-${k}` });
        await sleep(30); c.destroy();
        return a?.t === 'authed';
      } catch { return false; }
    };
    h2.stdin('tailscale off\n'); await sleep(200);
    chk.ok(!(await tryTs('off0')), 'tailscale off: a tailnet peer is refused', undefined, 'S1 I6');
    h2.stdin('tailscale on\n'); await sleep(200);
    chk.ok(await tryTs('on0'), 'tailscale on: a tailnet peer authenticates', undefined, 'S2');
    // a held tailnet session must be dropped when Tailscale is turned off
    const held = await connect({ port: h2.port, host: ts, hostHdr: `${ts}:${h2.port}` });
    const hh = await held.next(1500);
    await held.req({ t: 'auth', id: cred2.id, mac: hmac(cred2.token, hh.nonce) });
    const t0 = Date.now();
    h2.stdin('tailscale off\n');
    await held.waitClosed(3000);
    chk.ok(held.closed, 'tailscale off drops a connected tailnet session', undefined, 'S1 I6');
    chk.budget('tailnet.dropOnToggleOffMs', held.closed ? Date.now() - t0 : null, 1000, { tag: 'S2 I4' });
    let k = 0, okWhileOff = 0;
    const end = Math.min(deadline - 4000, Date.now() + 8000);
    let state = 'off';
    while (Date.now() < end) {
      state = r.chance(0.5) ? 'on' : 'off';
      h2.stdin(`tailscale ${state}\n`);
      await sleep(r.int(5, 60));
      if (state === 'off') { await sleep(60); if (await tryTs('s' + k)) okWhileOff++; } else tryTs('s' + k);
      k++;
    }
    chk.ok(okWhileOff === 0, `toggle storm (${k} flips): no tailnet auth while off`, okWhileOff, 'S1 I6');
    on = false; await watch;
    chk.ok(!obs.closed, 'toggle storm: the LAN session is never dropped', undefined, 'S2 I10');
    chk.budget('lan.observerP99Ms(toggle storm)', pct(rtts, 99), 500);
    const dup = [...h2.markers].filter(([m, n]) => m.startsWith('MKts-') && n > 1);
    chk.ok(dup.length === 0, 'toggle storm: no marker executed more than once', dup, 'S2 I2');
    chk.metric('tsFlips', k);
    obs.destroy();
    h2.stdin('tailscale off\n');
  }

  chk.pending('LAN<->tailnet<->BT failover / switch-back timing', 'phone-side (pairing.md section 5); BT HID needs a real phone: chaos/android physical.sh');
  chk.pending('Wi-Fi off on the phone', 'phone-side: chaos/android `network` scenario');
  await invariants(h2, chk, base);
  await h2.stop();
  return chk;
}
