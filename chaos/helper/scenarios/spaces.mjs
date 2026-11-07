// Spaces (protocol 1.3 / 1.4) under fuzz: a two-display --fake-spaces helper (generated thumbnails, no
// Screen Recording, nothing captured or injected), with the active display flipped over stdin-control. Unauthenticated spaces.* is always refused; authenticated fuzzed
// spaces.sub / spaces.thumb / spaces.goto never crash or hang the helper, state stays consistent,
// every thumbnail is a JPEG under the 60 KiB message budget, and a client that requests thumbnails
// without reading them is dropped instead of growing memory.
import { Checker, PEERS, invariants } from '../lib/check.mjs';
import { startHelper, sleep } from '../lib/helper.mjs';
import { authed, open, pair } from '../lib/ws.mjs';

export const name = 'spaces';
export const weight = 0.08;

const COUNTS = [4, 3];                       // desktops per fake display
const IDS = COUNTS.map((_, k) => `fake-${k + 1}`);
const N = COUNTS[0];
const IDX = [1, 2, 3, 4, 0, -1, 5, 16, 17, 2 ** 31, -(2 ** 53), 1.5, '1', null, true, [], {}, 'x'.repeat(300)];
const DISPLAY = [...IDS, ...IDS, 'fake-3', 'Main', '', 0, 1, null, true, [], {}, 'x'.repeat(300), 'fake-1\u0000'];

/** A state is consistent if both displays are listed and the 1.3 fields mirror the active one. */
function consistent(s) {
  if (s.displays?.length !== COUNTS.length || s.shared !== false) return false;
  if (!s.displays.every((d, k) => d.id === IDS[k] && d.count === COUNTS[k] && d.current >= 1 && d.current <= d.count && d.thumbs?.length === d.count)) return false;
  const a = s.displays.find((d) => d.id === s.display);
  return !!a && s.count === a.count && s.current === a.current && JSON.stringify(s.thumbs) === JSON.stringify(a.thumbs);
}

function msg(r) {
  const t = r.pick(['spaces.sub', 'spaces.thumb', 'spaces.goto', 'spaces', 'spaces.', 'Spaces.sub', 'spaces.goto\u0000']);
  const m = { t };
  if (r.chance(0.9)) m.i = r.pick(IDX);
  if (t === 'spaces.sub' || r.chance(0.1)) m.on = r.pick([true, false, 1, 0, 'yes', null]);
  if (r.chance(0.5)) m.display = r.pick(DISPLAY);
  if (r.chance(0.05)) delete m.t;
  return m;
}

export async function run({ r, deadline }) {
  const chk = new Checker(name);
  const h = await startHelper({ extraArgs: ['--fake-spaces', COUNTS.join(','), '--stdin-control'], stdin: true });
  chk.ok(h.log.some((l) => l.includes(`fake spaces: ${COUNTS.join(',')}`)), 'helper runs with --fake-spaces 4,3');
  const base = { baseFds: (await h.fds()).all, baseFp: await h.footprint() };
  const cred = await pair(h);

  // 1. Unauthenticated: every spaces message gets err unauth, never state or a thumbnail.
  const u = await open({ port: h.port, host: PEERS[0] });
  let unauthBad = 0, unauthN = 0;
  for (let i = 0; i < 18; i++) {
    const m = msg(r);
    if (typeof m.t !== 'string') continue;
    const e = await u.req(m, 2000);
    unauthN++;
    if (!(e?.t === 'err' && (e.code === 'unauth'))) unauthBad++;
  }
  u.destroy();
  chk.ok(unauthBad === 0, `unauth: ${unauthN} spaces.* messages all refused with unauth`, unauthBad);

  // 2. Authenticated fuzz + goto storm, with a subscriber watching state.
  const watch = await authed(h, cred, { host: PEERS[1] });
  const states = [];
  const s0 = await watch.req({ t: 'spaces.sub' });
  chk.ok(s0?.t === 'spaces' && s0.count === N && consistent(s0), 'subscriber gets state for both displays', s0);
  const a = await authed(h, cred, { host: PEERS[2] });
  let cases = 0, thumbs = 0, badThumbs = [], badReplies = [];
  while (Date.now() < deadline - 6000) {
    const batch = Array.from({ length: 20 }, () => msg(r));
    for (const m of batch) a.send(m);
    if (r.chance(0.3)) h.stdin(`display ${r.int(0, COUNTS.length + 1)}\n`);   // flip (or try a bad) active display
    const n = 1e6 + cases;
    a.send({ t: 'ping', n });
    for (;;) {
      const m = await a.next(5000);
      if (!m) { badReplies.push('no pong (hang?)'); break; }
      if (m.t === 'pong' && m.n === n) break;
      if (m.t === 'spaces') states.push(m);
      else if (m.t === 'spaces.thumb') {
        thumbs++;
        const j = Buffer.from(m.data ?? '', 'base64');
        const k = IDS.indexOf(m.display);
        if (!(j[0] === 0xff && j[1] === 0xd8 && k >= 0 && m.i >= 1 && m.i <= COUNTS[k] && JSON.stringify(m).length <= 60 * 1024)) badThumbs.push({ i: m.i, d: m.display, len: j.length });
      } else if (m.t === 'err') { if (!['bad', 'notfound', 'unknown', 'unsupported'].includes(m.code)) badReplies.push(m); }
      else badReplies.push(m);
    }
    cases += batch.length;
    if (!h.alive()) break;
    await sleep(r.int(0, 30));
  }
  // The goto storm cancels every pending capture (by design); once it settles one capture lands.
  await sleep(1200);
  for (let i = 1; i <= N; i++) {
    a.send({ t: 'spaces.thumb', i });
    const m = await a.until((x) => (x.t === 'spaces.thumb' && x.i === i) || (x.t === 'err' && x.ref === 'spaces.thumb'), 2000);
    if (m?.t !== 'spaces.thumb') continue;
    thumbs++;
    const j = Buffer.from(m.data ?? '', 'base64');
    if (!(j[0] === 0xff && j[1] === 0xd8 && m.i === i && JSON.stringify(m).length <= 60 * 1024)) badThumbs.push({ i: m.i, len: j.length });
  }
  chk.ok(thumbs > 0, 'a thumbnail is captured once Space changes settle', thumbs);
  for (;;) { const m = await watch.next(50); if (!m) break; if (m.t === 'spaces') states.push(m); }
  chk.ok(h.alive(), 'helper survived the spaces fuzz', h.exit);
  chk.ok(badReplies.length === 0, 'only bad/notfound/unknown errors, no hang', badReplies.slice(0, 3));
  chk.ok(badThumbs.length === 0, `every thumbnail is a JPEG within budget (${thumbs})`, badThumbs.slice(0, 3));
  const inconsistent = states.filter((s) => !consistent(s));
  chk.ok(inconsistent.length === 0, `${states.length} states consistent (displays ${COUNTS.join(',')}, top level mirrors the active one)`, inconsistent.slice(0, 2));
  chk.ok(new Set(states.map((s) => s.display)).size === COUNTS.length, 'active display switches were pushed', [...new Set(states.map((s) => s.display))]);
  chk.ok(h.log.some((l) => /DRY spaces goto=/.test(l)), 'gotos were executed (dry run)');
  a.destroy(); watch.destroy();

  // 3. A client that floods thumbnail requests and never reads: dropped, helper stays responsive.
  const s = await authed(h, cred, { host: PEERS[0] });
  s.send({ t: 'spaces.sub' });
  const th = await s.until((m) => m.t === 'spaces' && m.thumbs?.some((v) => v > 0), 5000);
  const have = th ? th.thumbs.findIndex((v) => v > 0) + 1 : 1;
  s.sock.pause();
  for (let i = 0; i < 3000 && !s.closed; i++) { s.send({ t: 'spaces.thumb', i: have }); if (i % 200 === 0) await sleep(5); }
  const p = await open({ port: h.port, host: PEERS[1] });
  chk.ok((await p.ping(7, 3000)) !== null, 'helper responsive while a client hoards thumbnails');
  p.destroy(); s.destroy();
  chk.cases = cases + unauthN + 3000;
  chk.metric('cases', cases);
  chk.metric('thumbs', thumbs);
  chk.metric('states', states.length);

  await invariants(h, chk, base);
  await h.stop();
  return chk;
}
