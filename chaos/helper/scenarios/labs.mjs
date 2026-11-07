// Labs previews under abuse (chaos/RUBRIC.md section 4, "labs"): the browser video bridge (origin /
// Host / loopback abuse, pinned origin, input smuggling, reconnect storm), live screen (close during a
// frame, take-over storm, stop budget), the now-playing reader (crash loop, garbage and an endless line,
// via the dry-run --fake-nowplaying-reader seam), TV-mode window churn, and all panels at once.
// Always --dry-run: no window, browser, capture or input is real.
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { Checker, PEERS, invariants, pct } from '../lib/check.mjs';
import { hasFlag, startHelper, sleep } from '../lib/helper.mjs';
import { lanIPv4 } from '../lib/labs.mjs';
import { authed, connect, hmac, pair } from '../lib/ws.mjs';

export const name = 'labs';
export const weight = 0.08;

const ORIGIN = 'chrome-extension://abcdefghijklmnop';
const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'mote-chaos-labs-'));
process.on('exit', () => fs.rmSync(tmp, { recursive: true, force: true }));

async function bridgeOpen(bport, o = {}) {
  try {
    const c = await connect({ port: bport, path: '/bridge', origin: o.origin === undefined ? ORIGIN : o.origin, hostHdr: o.hostHdr ?? `127.0.0.1:${bport}`, host: o.host ?? '127.0.0.1', timeout: 2000 });
    c.hello = await c.next(2000);
    return c;
  } catch (e) { return { refused: e.message }; }
}

async function bridge(chk, r, deadline) {
  const h = await startHelper({ stdin: true, extraArgs: ['--stdin-control', '--labs-bridge', '--bridge-port', '0'] });
  const bport = Number(/bridge: listening on 127\.0\.0\.1:(\d+)/.exec(h.log.join('\n'))?.[1] ?? (await h.waitLine(/bridge: listening/, 3000))?.match(/:(\d+)/)?.[1]);
  const base = { baseFds: (await h.fds()).all, baseFp: await h.footprint(), listeners: 2 };
  const cred = await pair(h);
  const phone = await authed(h, cred, { host: PEERS[1] });
  const st0 = await phone.req({ t: 'labs.video.sub', on: true });
  chk.ok(st0?.t === 'labs.video.state' && st0.bridge === 'waiting', 'bridge: phone sees waiting with no extension', st0, 'S3 I8');

  // Origin / Host / path / address abuse: no 101.
  const lan = lanIPv4();
  const abuse = [['no Origin', { origin: null }], ['web page Origin', { origin: 'http://evil.example' }], ['loopback page Origin', { origin: `http://127.0.0.1:${bport}` }],
    ['null Origin', { origin: 'null' }], ['extension origin with path', { origin: 'chrome-extension://x/../y' }], ['DNS rebinding Host', { hostHdr: `evil.example:${bport}` }],
    ['LAN Host literal', { hostHdr: `${lan ?? '192.168.0.2'}:${bport}` }], ...(lan ? [['LAN address', { host: lan }]] : [])];
  const leaked = [];
  for (const [label, o] of abuse) { const c = await bridgeOpen(bport, o); if (!c.refused) { leaked.push(label); c.destroy(); } }
  chk.ok(leaked.length === 0, `bridge: ${abuse.length} origin / Host / address abuses refused before upgrade`, leaked, 'S1 I1');

  // Pair the extension; another origin can't use its token; input messages are never routed.
  const ext = await bridgeOpen(bport);
  h.stdin('bridge pair\n');
  const code = (await h.waitLine(/BRIDGE CODE: \d{6}/, 3000))?.match(/(\d{6})/)?.[1];
  const paired = await ext.req({ t: 'labs.video.pair', code });
  const tok = { id: paired?.id, token: paired?.token };
  ext.destroy();
  chk.ok(paired?.t === 'labs.video.paired', 'bridge: extension pairs with the bridge code', paired, 'S2');
  const other = await bridgeOpen(bport, { origin: 'chrome-extension://otherextensionid' });
  const oa = await other.req({ t: 'labs.video.auth', id: tok.id, mac: hmac(tok.token, other.hello.nonce) });
  chk.ok(oa?.t === 'err' && oa.code === 'auth', 'bridge: another extension origin with the stolen token is refused (pinned origin)', oa, 'S1 I6');
  other.destroy();
  const authExt = async () => { const c = await bridgeOpen(bport); const a = c.refused ? null : await c.req({ t: 'labs.video.auth', id: tok.id, mac: hmac(tok.token, c.hello.nonce) }); return a?.t === 'labs.video.authed' ? c : (c.destroy?.(), null); };
  const e1 = await authExt();
  for (const m of [{ t: 'text', s: 'MKbridge' }, { t: 'key', usage: 4 }, { t: 'macro', steps: [{ t: 'text', s: 'MKbridge2' }] }, { t: 'labs.screen.click', x: 0.5, y: 0.5 }]) e1?.send(m);
  await e1?.ping();
  await sleep(200);
  chk.ok(!!e1 && !h.markers.has('MKbridge') && !h.markers.has('MKbridge2') && !(h.dryByKind.key > 0), 'bridge: input messages from the extension are never executed', h.dryByKind, 'S1 I1');
  e1?.destroy();

  // Reconnect storm: connect, auth, state, then close / RST / vanish; the phone ends on the last state.
  let n = 0, last = null;
  const end = Math.max(Date.now() + 2000, Math.min(deadline - 3000, Date.now() + 6000));
  while (Date.now() < end && n < 150) {
    const c = await authExt();
    if (!c) { n++; continue; }
    last = `title-${n}`;
    c.send({ t: 'labs.video.state', active: true, title: last, paused: r.chance(0.5), time: r.int(0, 100), duration: 200 });
    await sleep(r.int(0, 20));
    const how = r.int(0, 2); if (how === 0) c.close(); else if (how === 1) c.reset(); else c.destroy();
    n++;
  }
  const keep = await authExt();
  keep?.send({ t: 'labs.video.state', active: true, title: 'final', duration: 10, time: 1, paused: true });
  const fin = await phone.until((m) => m.t === 'labs.video.state' && m.title === 'final', 3000);
  chk.ok(!!fin && fin.bridge === 'connected', `bridge reconnect storm (${n} cycles): phone follows the live extension`, fin, 'S2');
  const cmd = await phone.req({ t: 'labs.video.cmd', cmd: 'seekBy', value: 10 }, 300);
  const routed = await keep?.until((m) => m.t === 'labs.video.cmd', 1500);
  chk.ok(routed?.cmd === 'seekBy' && cmd === null, 'bridge: phone command routed once to the live extension', { routed, cmd }, 'S2 I2');
  keep?.destroy();
  await sleep(300);
  const gone = await phone.until((m) => m.t === 'labs.video.state' && m.bridge === 'waiting', 3000);
  chk.ok(!!gone, 'bridge: extension gone -> phone sees waiting (visible state)', gone, 'S3 I8');
  chk.metric('bridgeCycles', n);
  phone.destroy();
  await invariants(h, chk, base);
  await h.stop();
}

async function screen(chk, r, deadline) {
  const h = await startHelper({ extraArgs: ['--fake-screen'] });
  const cred = await pair(h);
  // Close during a frame: RST while a frame is mid-flight; the stream must stop within the I4 budget.
  const stops = [];
  for (let i = 0; i < 8 && (i < 3 || Date.now() < deadline - 6000); i++) {
    const c = await authed(h, cred, { host: PEERS[i % 3] });
    c.send({ t: 'labs.screen.start', w: 960, fps: 15, credits: 3 });
    await c.until((m) => m.t === 'labs.screen.frame', 2000);
    // Wait until a frame is partially received, then cut the socket.
    const rx0 = c.rx; for (let k = 0; k < 50 && c.rx === rx0; k++) await sleep(2);
    const stopped = h.waitLine(/screen: stopped \(/, 5000);
    const t0 = Date.now();
    r.chance(0.5) ? c.reset() : c.destroy();
    stops.push((await stopped) ? Date.now() - t0 : null);
  }
  const worst = !stops.length || stops.some((x) => x === null) ? null : Math.max(...stops);
  chk.budget('screen.stopAfterCloseMs(max)', worst, 3000, { tag: 'S2 I4' });
  // Take-over storm: two viewers alternate starts; at the end only the last starter receives frames.
  const a = await authed(h, cred, { host: PEERS[0] }), b = await authed(h, cred, { host: PEERS[1] });
  let lastV = a;
  for (let i = 0; i < 30; i++) { lastV = r.chance(0.5) ? a : b; lastV.send({ t: 'labs.screen.start', credits: 2 }); await sleep(r.int(0, 30)); }
  const otherV = lastV === a ? b : a;
  await sleep(400);
  for (const v of [a, b]) for (;;) { const m = await v.next(50); if (!m) break; if (m.t === 'labs.screen.frame') v.send({ t: 'labs.screen.ack', seq: m.seq }); }
  let mine = 0, theirs = 0;
  const t1 = Date.now();
  while (Date.now() - t1 < 1500) {
    const [m1, m2] = await Promise.all([lastV.next(100), otherV.next(1)]);
    if (m1?.t === 'labs.screen.frame') { mine++; lastV.send({ t: 'labs.screen.ack', seq: m1.seq }); }
    if (m2?.t === 'labs.screen.frame') theirs++;
  }
  chk.ok(mine > 0 && theirs === 0, `take-over storm: one viewer at a time (last starter ${mine} frames, other ${theirs})`, { mine, theirs }, 'S1 I4');
  a.destroy(); b.destroy();
  await h.stop();
}

function script(name, body) { const p = path.join(tmp, name); fs.writeFileSync(p, '#!/bin/sh\n' + body + '\n', { mode: 0o755 }); return p; }

async function reader(chk) {
  if (!(await hasFlag('--fake-nowplaying-reader'))) { chk.pending('now-playing reader crash/restart', 'helper has no --fake-nowplaying-reader'); return; }
  const ctx = path.join(tmp, 'ctx.json');
  fs.writeFileSync(ctx, JSON.stringify({ app: 'Finder', bundle: 'com.apple.finder', text: false }));
  // 1. A reader that crashes right after its first line: restarted with backoff, then `unavailable` (visible).
  {
    const exe = script('crash.sh', 'echo \'{"ok":true}\'; echo \'{"np":{"title":"Crashy","duration":100,"elapsed":1,"rate":1}}\'; exit 3');
    const h = await startHelper({ extraArgs: ['--fake-context', ctx, '--fake-nowplaying-reader', exe] });
    const cred = await pair(h);
    const c = await authed(h, cred);
    const t0 = Date.now();
    c.send({ t: 'labs.np.sub', on: true });
    const crashy = await c.until((m) => m.t === 'labs.np' && m.np?.title === 'Crashy', 4000);
    const un = await c.until((m) => m.t === 'labs.np' && m.source === 'unavailable', 15000);
    chk.ok(!!crashy, 'reader crash loop: data from a live reader reaches the phone', crashy, 'S3');
    chk.ok(!!un && h.alive(), 'reader crash loop: helper gives up and reports unavailable (visible), stays alive', { un, alive: h.alive() }, 'S2 I8');
    chk.budget('np.crashLoopToUnavailableMs', un ? Date.now() - t0 : null, 12000);
    c.destroy(); await h.stop();
  }
  // 2. Garbage, wrong types, a 4 MiB line with no newline, then a valid line: bounded memory, still parsed.
  {
    const exe = script('garbage.sh', [
      'echo \'{"ok":true}\'', 'echo "not json"', 'printf \'\\377\\376garbage\\n\'', 'echo \'{"np":{"title":7,"duration":"x","rate":null,"art":[]}}\'',
      'echo \'{"np":{"title":"' + 'x'.repeat(600) + '","duration":1e400}}\'',
      'head -c 4194304 /dev/zero | tr "\\0" "a"', 'echo', 'echo \'{"np":{"title":"After","duration":50,"elapsed":2,"rate":1}}\'', 'sleep 30']
      .join('\n'));
    const h = await startHelper({ extraArgs: ['--fake-context', ctx, '--fake-nowplaying-reader', exe] });
    const fp0 = await h.footprint();
    const cred = await pair(h);
    const c = await authed(h, cred);
    c.send({ t: 'labs.np.sub', on: true });
    const after = await c.until((m) => m.t === 'labs.np' && m.np?.title === 'After', 10000);
    const grew = Math.round(((await h.footprint()) - fp0) / 1024);
    chk.ok(!!after && h.alive(), 'reader garbage + endless line: helper alive, next valid line still parsed', { after: !!after, alive: h.alive() }, 'S2 I10');
    chk.budget('np.readerGarbageFootprintGrowthMB', grew, 24, { unit: 'MB', tag: 'S3 I3' });
    chk.ok(h.log.some((l) => /now-playing (line too long|reader sent)/.test(l)), 'endless reader line is dropped at the cap', undefined, 'S3 I3');
    c.destroy(); await h.stop();
  }
}

async function tv(chk, r, deadline) {
  const h = await startHelper({ extraArgs: ['--labs-tv'] });
  const cred = await pair(h);
  const c = await authed(h, cred);
  const ops = [() => ({ t: 'labs.tv.open' }), () => ({ t: 'labs.tv.open', url: 'https://www.youtube.com/watch?v=dQw4w9WgXcQ' }), () => ({ t: 'labs.tv.exit' }),
    () => ({ t: 'labs.tv.key', k: r.pick(['up', 'down', 'left', 'right', 'ok', 'back', 'home', 'playpause', 'search', 'nope', 7]) }),
    () => ({ t: 'labs.open.url', url: r.pick(['https://example.com/', 'javascript:alert(1)', 'file:///etc/passwd', 'https://youtu.be/abc', 'x'.repeat(3000)]), mode: r.pick(['tv', 'browser', 'evil']) }),
    () => ({ t: 'labs.open.action', a: r.pick(['closeTab', 'exitFullscreen', 'pauseAll', 'rm']) }), () => ({ t: 'labs.tv.get' })];
  let n = 0, badErr = [], badState = [];
  const end = Math.max(Date.now() + 2000, Math.min(deadline - 3000, Date.now() + 6000));
  while (Date.now() < end) {
    for (let i = 0; i < 10; i++) c.send(r.pick(ops)());
    const pn = 3e6 + n; c.send({ t: 'ping', n: pn });
    for (;;) {
      const m = await c.next(3000);
      if (!m) { badErr.push('no pong (hang?)'); break; }
      if (m.t === 'pong' && m.n === pn) break;
      if (m.t === 'err' && !['bad', 'tv-off', 'disabled', 'paused', 'ax'].includes(m.code)) badErr.push(m);
      if (m.t === 'labs.tv.state' && (typeof m.on !== 'boolean' || (m.on && !['home', 'youtube', 'youtube-desktop', 'web'].includes(m.page)))) badState.push(m);
    }
    n += 10;
    if (r.chance(0.2)) await sleep(r.int(0, 30));
  }
  c.send({ t: 'labs.tv.exit' });
  const off = await c.req({ t: 'labs.tv.get' }).then(() => c.until((m) => m.t === 'labs.tv.state' && m.on === false, 1500));
  const k = await c.req({ t: 'labs.tv.key', k: 'ok' });
  c.send({ t: 'labs.tv.open' });
  const on = await c.until((m) => m.t === 'labs.tv.state' && m.on === true, 1500);
  chk.ok(badErr.length === 0 && badState.length === 0, `TV churn (${n} ops): only expected errors, well-formed states`, [...badErr, ...badState].slice(0, 3), 'S2 I10');
  chk.ok(!!off && k?.code === 'tv-off' && !!on, 'TV churn: exit -> off, key while off -> tv-off, open -> on', { off: !!off, k, on: !!on }, 'S2 I8');
  chk.ok(!h.log.some((l) => /DRY labs\.open\.url.*(javascript:|file:)/.test(l)), 'TV churn: javascript: / file: URLs never opened', undefined, 'S1');
  chk.metric('tvOps', n);
  c.destroy(); await h.stop();
}

async function together(chk, r, deadline) {
  const ctx = path.join(tmp, 'ctx2.json');
  const write = (i) => fs.writeFileSync(ctx, JSON.stringify({ app: i % 2 ? 'Safari' : 'Finder', bundle: i % 2 ? 'com.apple.Safari' : 'com.apple.finder', url: 'https://www.youtube.com/watch?v=' + i, title: 't' + i, text: i % 3 === 0, browser: i % 2 === 1 }));
  write(0);
  const h = await startHelper({ stdin: true, extraArgs: ['--stdin-control', '--labs-tv', '--labs-bridge', '--bridge-port', '0', '--fake-screen', '--fake-context', ctx, '--fake-nowplaying'] });
  const base = { baseFds: (await h.fds()).all, baseFp: await h.footprint(), listeners: 2 };
  const bport = Number(/bridge: listening on 127\.0\.0\.1:(\d+)/.exec(h.log.join('\n'))?.[1]);
  const cred = await pair(h);
  const p = await authed(h, cred, { host: PEERS[0] });
  const obs = await authed(h, cred, { host: PEERS[2] });
  for (const t of ['labs.ctx.sub', 'labs.np.sub', 'labs.video.sub']) p.send({ t, on: true });
  p.send({ t: 'labs.screen.start', fps: 15, credits: 3 });
  // extension
  const ext = await bridgeOpen(bport);
  h.stdin('bridge pair\n');
  const code = (await h.waitLine(/BRIDGE CODE: \d{6}/, 3000))?.match(/(\d{6})/)?.[1];
  const paired = await ext.req({ t: 'labs.video.pair', code });
  const ea = await ext.req({ t: 'labs.video.auth', id: paired?.id, mac: hmac(paired?.token ?? '00', ext.hello.nonce) });
  const seen = { ctx: 0, np: 0, video: 0, tv: 0, frames: 0 };
  const rtts = [];
  let on = true;
  const watch = (async () => { while (on) { rtts.push((await obs.ping(undefined, 2000)) ?? 2000); await sleep(50); } })();
  const end = Math.max(Date.now() + 4000, Math.min(deadline - 4000, Date.now() + 8000));
  let i = 0;
  const started = Date.now();
  while (Date.now() < end) {
    i++;
    if (i % 3 === 0) write(i);
    if (ea?.t === 'labs.video.authed') ext.send({ t: 'labs.video.state', active: true, title: 'v' + i, time: i, duration: 999, paused: false });
    if (i % 5 === 0) p.send(r.chance(0.5) ? { t: 'labs.tv.open' } : { t: 'labs.tv.exit' });
    if (i % 7 === 0) p.send({ t: 'labs.np.seek', by: r.int(-30, 30) });
    for (;;) {
      const m = await p.next(5);
      if (!m) break;
      if (m.t === 'labs.ctx') seen.ctx++; else if (m.t === 'labs.np') seen.np++; else if (m.t === 'labs.video.state') seen.video++;
      else if (m.t === 'labs.tv.state') seen.tv++; else if (m.t === 'labs.screen.frame') { seen.frames++; p.send({ t: 'labs.screen.ack', seq: m.seq }); }
    }
    await sleep(40);
  }
  on = false; await watch;
  const secs = (Date.now() - started) / 1000;
  chk.ok(Object.values(seen).every((v) => v > 0), 'all Labs panels at once: every stream keeps delivering (ctx, np, video, tv, frames)', seen, 'S2 I10');
  chk.budget('labs.concurrentFrameIntervalMs(<=333 = >=3 fps)', seen.frames ? Math.round(secs * 1000 / seen.frames) : null, 333);
  chk.budget('labs.concurrentObserverP99Ms', pct(rtts, 99), 500);
  chk.metric('concurrent', seen);
  p.send({ t: 'labs.screen.stop' });
  ext.destroy?.(); p.destroy(); obs.destroy();
  await invariants(h, chk, base);
  await h.stop();
  chk.pending('phone panel priority (TV > web video > now playing)', 'inline in App.kt MEDIA mode; needs a pure selector + unit test or an emulator UI test');
}

export async function run({ r, deadline }) {
  const chk = new Checker(name);
  const slice = (deadline - Date.now()) / 5;
  await bridge(chk, r.fork('bridge'), Date.now() + slice);
  await screen(chk, r.fork('screen'), Date.now() + slice);
  await reader(chk);
  await tv(chk, r.fork('tv'), Date.now() + slice);
  await together(chk, r.fork('all'), Date.now() + Math.max(slice, 12000));
  return chk;
}
