// Labs › Live screen (docs/labs/live-screen.md) against real --dry-run helpers. Synthetic frames only
// (--fake-screen); the real screen is never captured and nothing is clicked.
//   node helper/tests/screen.mjs            (MOTE_HELPER_BIN overrides the binary)
// Checks: lab off by default, auth required, frames + flow control (credits), lease timeout, stop /
// disconnect within 2 s, take-over, pause sharing, lab toggle, click mapping (Retina display with a
// negative origin). Prints latency (generated -> received on loopback) and bandwidth.
import { startHelper, sleep } from '../../chaos/helper/lib/helper.mjs';
import { authed, open, pair } from '../../chaos/helper/lib/ws.mjs';

let failed = 0;
const ok = (c, msg, detail) => { console.log((c ? 'PASS ' : 'FAIL ') + msg + (c || detail === undefined ? '' : ` (${JSON.stringify(detail)})`)); if (!c) failed++; };
const pct = (a, p) => { const s = [...a].sort((x, y) => x - y); return s.length ? s[Math.min(s.length - 1, Math.floor(p / 100 * s.length))] : null; };
const isFrame = (m) => m?.t === 'labs.screen.frame';
/** Sends m and waits for the first message matching pred (frames of an earlier stream are skipped). */
const ask = (conn, m, pred, ms = 2000) => { conn.send(m); return conn.until(pred, ms); };
const start = (conn, m = {}) => ask(conn, { t: 'labs.screen.start', ...m }, (x) => x.t === 'labs.screen.started' || x.t === 'err');
const err = (x) => x.t === 'err';

// --- 1. default helper: the lab is off.
{
  const h = await startHelper();
  const cred = await pair(h);
  const c = await authed(h, cred);
  const r = await start(c);
  ok(r?.t === 'err' && r.code === 'labs-off' && r.ref === 'labs.screen.start', 'lab off by default: start refused with labs-off', r);
  c.destroy(); await h.stop();
}

const h = await startHelper({ extraArgs: ['--fake-screen', '--stdin-control'], stdin: true });
ok(h.log.some((l) => l.includes('fake screen: fake-screen 1512x982')), 'helper runs with --fake-screen');
const cred = await pair(h);

// --- 2. unauthenticated: refused, no frames.
{
  const u = await open({ port: h.port });
  const r = await u.req({ t: 'labs.screen.start', fps: 15 });
  ok(r?.t === 'err' && r.code === 'unauth', 'unauthenticated start refused', r);
  const f = await u.until(isFrame, 800);
  ok(f === null, 'no frame reaches an unauthenticated socket');
  u.destroy();
}

// --- 3. authed stream: frames, acks, latency and bandwidth.
const c = await authed(h, cred);
{
  const r = await c.req({ t: 'labs.screen.click', x: 0.5, y: 0.5 });
  ok(r?.t === 'err' && r.code === 'notstreaming', 'click before start refused', r);
}
const st = await start(c, { w: 960, fps: 15, credits: 2 });
ok(st?.t === 'labs.screen.started' && st.display === 'fake-screen' && st.encrypted === false && st.fake === true && st.w === 960 && st.h === 623,
  'started: fake display, 960x623, not encrypted (loopback is plain Wi-Fi)', st);
ok(h.log.some((l) => l.includes('screen: streaming to')), 'helper logs the stream start');

const lat = [], sizes = [], fpsSeen = [], t0 = Date.now();
let frames = 0, bad = 0, lastSeq = 0, inOrder = true, cursor = 0;
while (Date.now() - t0 < 5000) {
  const m = await c.until(isFrame, 2000);
  if (!m) break;
  const now = Date.now();
  frames++;
  const j = Buffer.from(m.data ?? '', 'base64');
  if (!(j[0] === 0xff && j[1] === 0xd8 && m.w === 960 && m.display === 'fake-screen' && JSON.stringify(m).length <= 240 * 1024)) bad++;
  if (m.seq <= lastSeq) inOrder = false;
  lastSeq = m.seq;
  if (typeof m.cx === 'number' && typeof m.cy === 'number') cursor++;
  lat.push(now - m.ts); sizes.push(j.length); fpsSeen.push(m.fps);
  c.send({ t: 'labs.screen.ack', seq: m.seq });
}
const secs = (Date.now() - t0) / 1000;
ok(frames >= 30, `frames flow with acks (${frames} in ${secs.toFixed(1)} s)`);
ok(bad === 0, 'every frame is a JPEG of the fake display within the 240 KiB message budget', bad);
ok(inOrder, 'frame seq increases');
ok(cursor === frames, 'every frame carries the cursor position');
const kbps = Math.round(sizes.reduce((a, b) => a + b, 0) * 8 / secs / 1000);
console.log(`  METRIC latency gen->recv ms p50=${pct(lat, 50)} p95=${pct(lat, 95)} max=${Math.max(...lat)}; fps=${(frames / secs).toFixed(1)} (helper ${fpsSeen.at(-1)}); ` +
  `jpeg KiB p50=${(pct(sizes, 50) / 1024).toFixed(1)} max=${(Math.max(...sizes) / 1024).toFixed(1)}; ${kbps} kbit/s`);
ok(pct(lat, 95) < 150, 'p95 latency on loopback under 150 ms', pct(lat, 95));

// --- 4. click mapping: the fake display is 1512x982 pt at (-1512, -200), Retina 2x.
for (const [m, want] of [
  [{ x: 0.5, y: 0.5 }, 'DRY clickAt (-756.0, 291.0) b=left count=1'],
  [{ x: 0, y: 0, button: 'right' }, 'DRY clickAt (-1512.0, -200.0) b=right count=1'],
  [{ x: 1, y: 1, count: 2 }, 'DRY clickAt (-1.0, 781.0) b=left count=2'],
]) {
  const w = h.waitLine(new RegExp(want.replace(/[().]/g, '\\$&')), 2000);
  c.send({ t: 'labs.screen.click', display: 'fake-screen', ...m });
  ok(await w, `click ${JSON.stringify(m)} -> ${want.slice(4)}`);
}
for (const m of [{ x: 2, y: 0 }, { x: 0.5, y: 'a' }, { x: 0.5, y: 0.5, count: 3 }, { x: 0.5, y: 0.5, button: 'middle' }]) {
  const r = await ask(c, { t: 'labs.screen.click', ...m }, err);
  ok(r?.code === 'bad', `bad click ${JSON.stringify(m)} refused`, r);
}
h.stdin('pause\n');
await sleep(150);
{
  const r = await ask(c, { t: 'labs.screen.click', x: 0.5, y: 0.5 }, err);
  ok(r?.code === 'paused', 'click refused while input is paused', r);
}
h.stdin('resume\n');

// --- 5. flow control: without acks at most `credits` frames are in flight, then frames are dropped.
{
  const s = await start(c, { w: 960, fps: 15, credits: 2 });   // restart resets credits
  ok(s?.t === 'labs.screen.started', 'restart on the same session');
  let n = 0; const end = Date.now() + 1200;
  while (Date.now() < end) { const m = await c.until(isFrame, end - Date.now()); if (m) n++; }
  ok(n === 2, `no acks: exactly 2 frames (the credit window) in 1.2 s`, n);
  // A lost ack's credit is reclaimed after 2 s, but the lease (no message for 2 s) ends the stream first.
  const stop = await c.until((x) => x.t === 'labs.screen.stopped', 2500);
  ok(stop?.reason === 'timeout', 'silent viewer: stream stops on the 2 s lease', stop);
  ok(h.log.some((l) => /screen: stopped \(timeout\).*dropped/.test(l)), 'helper logs dropped frames');
}

// --- 6. explicit stop, then no more frames.
{
  await start(c, { fps: 10 });
  const f = await c.until(isFrame, 2000);
  c.send({ t: 'labs.screen.ack', seq: f?.seq });
  const t = Date.now();
  c.send({ t: 'labs.screen.stop' });
  const s = await c.until((x) => x.t === 'labs.screen.stopped', 2000);
  ok(s?.reason === 'stop' && Date.now() - t < 2000, `stop acknowledged in ${Date.now() - t} ms`, s);
  ok((await c.until(isFrame, 800)) === null, 'no frames after stop');
}

// --- 7. take-over by a second viewer, and disconnect.
{
  await start(c);
  const d = await authed(h, cred, { host: '::1' });
  const s = await start(d);
  ok(s?.t === 'labs.screen.started', 'second viewer starts');
  const old = await c.until((x) => x.t === 'labs.screen.stopped', 1500);
  ok(old?.reason === 'taken', 'first viewer told it was taken over', old);
  const gone = h.waitLine(/screen: stopped \(gone\)/, 2000);
  const t = Date.now();
  d.destroy();
  ok(await gone, `viewer disconnect stops the stream (${Date.now() - t} ms)`);
}

// --- 8. Pause sharing and the lab toggle (menu stand-ins over --stdin-control).
{
  await start(c);
  h.stdin('screen-pause\n');
  const p = await c.until((x) => x.t === 'labs.screen.stopped', 1500);
  ok(p?.reason === 'paused', 'Pause screen sharing stops the stream', p);
  const r = await ask(c, { t: 'labs.screen.start' }, err);
  ok(r?.code === 'sharing-paused', 'start refused while sharing is paused', r);
  h.stdin('screen-resume\n');
  await sleep(100);
  const s = await start(c);
  ok(s?.t === 'labs.screen.started', 'start works after resume');
  h.stdin('screen-lab-off\n');
  const o = await c.until((x) => x.t === 'labs.screen.stopped', 1500);
  ok(o?.reason === 'off', 'turning the lab off stops the stream', o);
}
c.destroy();
ok(h.alive(), 'helper alive');
await h.stop();
console.log(failed ? `${failed} FAILED` : 'all live screen checks passed');
process.exit(failed ? 1 : 0);
