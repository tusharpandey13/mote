// Spaces per display (protocol 1.4) under churn, against a --fake-spaces helper: an active-display switch
// storm, simulated display hot-plug (stdin `displays N,M,...`), and goto bursts that land while the
// previous goto is still "animating" (stepping). The pushed state must stay consistent with the layout,
// the last goto must win, and a goto to a removed display must be refused, not applied elsewhere.
import { Checker, PEERS, invariants, pct } from '../lib/check.mjs';
import { hasFlag, startHelper, sleep } from '../lib/helper.mjs';
import { authed, pair } from '../lib/ws.mjs';

export const name = 'displays';
export const weight = 0.05;

const valid = (s, layout) => s.displays?.length === layout.length && s.displays.every((d, k) => d.id === `fake-${k + 1}` && d.count === layout[k] && d.current >= 1 && d.current <= d.count)
  && s.displays.some((d) => d.id === s.display && d.count === s.count && d.current === s.current);

export async function run({ r, deadline }) {
  const chk = new Checker(name);
  const h = await startHelper({ stdin: true, extraArgs: ['--fake-spaces', '4,3', '--stdin-control'] });
  const base = { baseFds: (await h.fds()).all, baseFp: await h.footprint() };
  const cred = await pair(h);
  const sub = await authed(h, cred, { host: PEERS[1] });
  const a = await authed(h, cred, { host: PEERS[2] });
  const latest = async (ms = 600) => { let s = null; for (;;) { const m = await sub.next(ms); if (!m) return s; if (m.t === 'spaces') s = m; } };
  const s0 = await sub.req({ t: 'spaces.sub' });
  chk.ok(valid(s0, [4, 3]), 'initial state: two fake displays', s0, 'S2');

  // 1. Active-display switch storm (200 flips, 0-15 ms apart) while gotos target both displays.
  {
    const lat = [];
    for (let i = 0; i < 200 && Date.now() < deadline - 8000; i++) {
      h.stdin(`display ${r.int(1, 2)}\n`);
      if (r.chance(0.3)) a.send({ t: 'spaces.goto', i: r.int(1, 3), display: `fake-${r.int(1, 2)}` });
      if (i % 25 === 0) { const t0 = performance.now(); await a.ping(); lat.push(performance.now() - t0); }
      await sleep(r.int(0, 15));
    }
    h.stdin('display 2\n');
    const s = await latest(800);
    chk.ok(s && valid(s, [4, 3]) && s.display === 'fake-2', 'switch storm: final state consistent, last active display wins', s && { display: s.display, displays: s.displays }, 'S2');
    chk.budget('spaces.pingP95Ms(switch storm)', pct(lat, 95), 250);
  }

  // 2. Hot-plug: layouts change under subscribers; state follows each layout; goto to a removed display is refused.
  if (await hasFlag('--fake-spaces')) {
    const layouts = [[4, 3], [2], [4, 3, 2], [1], [5, 5], [4, 3]];
    let bad = [];
    for (const L of layouts) {
      h.stdin(`displays ${L.join(',')}\n`);
      await sleep(250);
      const s = (await latest(500)) ?? (await sub.req({ t: 'spaces.sub' }));   // unchanged layout: nothing pushed
      if (!s || !valid(s, L)) bad.push({ L, got: s?.displays?.map((d) => d.count) });
      if (L.length === 1) {
        const e = await a.req({ t: 'spaces.goto', i: 1, display: 'fake-2' }, 1000);
        if (!(e?.t === 'err' && e.code === 'notfound')) bad.push({ L, gotoRemoved: e });
      }
    }
    if (h.log.some((l) => l.includes("unknown command 'displays"))) chk.pending('display hot-plug', 'helper has no `displays` stdin command');
    else chk.ok(bad.length === 0, `hot-plug: ${layouts.length} layouts, state follows, goto to a removed display -> notfound`, bad.slice(0, 3), 'S2');
  }

  // 3. goto during the previous goto's animation: bursts of 2-4 gotos 0-40 ms apart; the last one wins
  //    and each goto is executed once (DRY spaces goto lines == gotos sent).
  {
    h.stdin('displays 4,3\n'); h.stdin('display 1\n'); await sleep(300); await latest(300);
    const before = h.log.filter((l) => /DRY spaces goto=/.test(l)).length;
    let sent = 0, wrong = [], drift = [];
    for (let b = 0; b < 15 && Date.now() < deadline - 3000; b++) {
      let last;
      const s0 = (await sub.req({ t: 'spaces.sub' }))?.displays?.[0]?.current;
      const mark = h.log.length;
      for (let n = r.int(2, 4); n > 0; n--) { last = r.int(1, 4); a.send({ t: 'spaces.goto', i: last, display: 'fake-1' }); sent++; await sleep(r.int(0, 40)); }
      await sleep(250);
      const s = await latest(400);
      const d1 = s?.displays?.find((d) => d.id === 'fake-1');
      if (s && d1?.current !== last) wrong.push({ last, current: d1?.current });
      await sleep(500);   // let the last key run finish
      const net = h.log.slice(mark).reduce((a, l) => a + (/DRY key usage=79 mods=1/.test(l) ? 1 : /DRY key usage=80 mods=1/.test(l) ? -1 : 0), 0);
      if (s0 && net !== last - s0) drift.push({ from: s0, to: last, net });
    }
    await sleep(200);
    const done = h.log.filter((l) => /DRY spaces goto=/.test(l)).length - before;
    chk.ok(wrong.length === 0, 'goto during animation: the last goto wins', wrong.slice(0, 3), 'S2');
    chk.ok(done <= sent, `goto bursts: ${done} executed for ${sent} sent (none twice)`, { done, sent }, 'S2 I2');
    chk.ok(drift.length === 0, 'goto during animation: arrow keys sent add up to the last target (no overshoot)', drift.slice(0, 3), 'S2 I2');
  }
  sub.destroy(); a.destroy();
  await invariants(h, chk, base);
  await h.stop();
  return chk;
}
