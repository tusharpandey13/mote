// Spaces (protocol 1.3 / 1.4) checks, run by smoke.mjs against its own helpers started with
// --dry-run --fake-spaces 4 and --fake-spaces 6,3 (two displays): generated thumbnails,
// no Screen Recording, nothing injected.
import crypto from 'node:crypto';
import { spawn } from 'node:child_process';
import path from 'node:path';
import readline from 'node:readline';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
export const BIN = process.env.MOTE_HELPER || path.resolve(here, '../build/MoteHelper.app/Contents/MacOS/MoteHelper');

export async function spacesChecks(ok, opts = {}) {
  await singleDisplay(ok, opts);
  await multiDisplay(ok, opts);
}

/** Starts a --fake-spaces helper; resolves with the process, its log, pairing code, port and a WebSocket opener. */
async function startFake(fake, { tailnet = false } = {}) {
  const args = ['--dry-run', '--headless', '--no-ble', '--memory-store', '--open-pairing', '--port', '0', '--stdin-control', '--fake-spaces', fake];
  if (tailnet) args.push('--allow-tailscale');
  if (!args.includes('--dry-run')) throw new Error('refusing to run the helper without --dry-run');
  const proc = spawn(BIN, args, { stdio: ['pipe', 'pipe', 'pipe'] });
  const log = [];
  let code, port;
  const ready = new Promise((res) => {
    readline.createInterface({ input: proc.stdout }).on('line', (l) => { const m = /PAIRING CODE: (\d{6})/.exec(l); if (m) { code = m[1]; if (port) res(); } });
    readline.createInterface({ input: proc.stderr }).on('line', (l) => {
      log.push(l);
      const m = /wifi: listening on :(\d+)/.exec(l); if (m) { port = m[1]; if (code) res(); }
    });
  });
  const waitLog = async (re, ms = 3000) => { for (let t = 0; t < ms; t += 25) { if (log.some((l) => re.test(l))) return true; await new Promise((r) => setTimeout(r, 25)); } return false; };
  try {
    await Promise.race([ready, new Promise((_, rej) => setTimeout(() => rej(new Error('fake-spaces helper did not start')), 10000))]);
  } catch (e) { proc.kill('SIGTERM'); throw e; }
const open = () => new Promise((res, rej) => {
    const ws = new WebSocket(`ws://127.0.0.1:${port}/ws`), q = [], waiters = [];
    ws.onmessage = (e) => { const m = JSON.parse(e.data); const w = waiters.shift(); w ? w(m) : q.push(m); };
    ws.onerror = rej;
    ws.next = (ms = 3000) => new Promise((r) => { const m = q.shift(); if (m) return r(m); const t = setTimeout(() => { waiters.splice(waiters.indexOf(f), 1); r(null); }, ms); const f = (x) => { clearTimeout(t); r(x); }; waiters.push(f); });
    ws.req = (m) => { ws.send(JSON.stringify(m)); return ws.next(); };
    ws.until = async (pred, ms = 3000) => { const end = Date.now() + ms; for (;;) { const m = await ws.next(Math.max(1, end - Date.now())); if (!m || pred(m)) return m; } };
    ws.onopen = () => res(ws);
    });
  // Pair + auth a new session.
  const authed = async (name) => {
    const a = await open(); const hello = await a.next();
    const p = await a.req({ t: 'pair', code, name });
    const mac = crypto.createHmac('sha256', Buffer.from(p.token, 'hex')).update(hello.nonce).digest('hex');
    const r = await a.req({ t: 'auth', id: p.id, mac });
    return r?.t === 'authed' ? a : null;
  };
  return { proc, log, code, port, open, authed, waitLog };
}

async function singleDisplay(ok, opts) {
  const { proc, log, code, open, waitLog } = await startFake('4', opts);
  try {
    ok(log.some((l) => l.includes('fake spaces: 4')), 'spaces: helper started with --fake-spaces 4');

    // Unauthenticated: every spaces message is refused, nothing is logged as injected.
    const u = await open(); await u.next();
    for (const m of [{ t: 'spaces.sub' }, { t: 'spaces.thumb', i: 1 }, { t: 'spaces.goto', i: 2 }]) {
      const r = await u.req(m);
      ok(r?.t === 'err' && r.code === 'unauth' && r.ref === m.t, `spaces: ${m.t} before auth refused (unauth)`);
    }
    u.close();
    ok(!log.some((l) => /DRY spaces/.test(l)), 'spaces: nothing injected before auth');

    // Pair, auth, subscribe.
    const a = await open(); const hello = await a.next();
    const p = await a.req({ t: 'pair', code, name: 'spaces smoke' });
    const mac = crypto.createHmac('sha256', Buffer.from(p.token, 'hex')).update(hello.nonce).digest('hex');
    ok((await a.req({ t: 'auth', id: p.id, mac }))?.t === 'authed', 'spaces: authed');
    const s0 = await a.req({ t: 'spaces.sub', on: true });
    ok(s0?.t === 'spaces' && s0.count === 4 && s0.current === 1 && s0.capture === 'on' && s0.thumbs.length === 4,
      `spaces: subscribe -> state count=4 current=1 capture=on (${JSON.stringify(s0)})`);
    ok(s0?.display === 'fake-1' && s0.shared === false && s0.displays?.length === 1 && s0.displays[0].id === 'fake-1' &&
      s0.displays[0].count === 4 && s0.displays[0].current === 1 && typeof s0.displays[0].name === 'string',
      'spaces: 1.4 fields: one display, active fake-1');
    const s1 = await a.until((m) => m.t === 'spaces' && m.thumbs[0] > 0);
    ok(!!s1, 'spaces: thumbnail for desktop 1 captured after subscribing');
    const nf = await a.req({ t: 'spaces.thumb', i: 2 });
    ok(nf?.t === 'err' && nf.code === 'notfound', 'spaces: unvisited desktop -> notfound');
    const bad = await a.req({ t: 'spaces.thumb', i: 'x' });
    ok(bad?.code === 'bad', 'spaces: malformed thumb index -> bad');
    const th = await a.req({ t: 'spaces.thumb', i: 1 });
    const jpeg = th?.data ? Buffer.from(th.data, 'base64') : Buffer.alloc(0);
    ok(th?.t === 'spaces.thumb' && th.i === 1 && th.v === s1?.thumbs[0] && th.mime === 'image/jpeg' && th.w === 320 &&
      jpeg[0] === 0xff && jpeg[1] === 0xd8 && JSON.stringify(th).length <= 60 * 1024,
      `spaces: thumbnail fetch is a JPEG, 320 px wide, under 60 KiB (${jpeg.length} B)`);

    // goto: logged (dry run), state follows, a thumbnail for the new desktop arrives.
    a.send(JSON.stringify({ t: 'spaces.goto', i: 3 }));
    ok(await waitLog(/DRY spaces goto=3 from=1 via=step/), 'spaces: goto 3 logged (dry run, steps)');
    const s3 = await a.until((m) => m.t === 'spaces' && m.current === 3 && m.thumbs[2] > 0);
    ok(!!s3, 'spaces: current becomes 3 and its thumbnail is captured');
    ok(log.filter((l) => /DRY key usage=79 mods=1/.test(l)).length === 2, 'spaces: goto stepped twice with ⌃→');
    const oob = await a.req({ t: 'spaces.goto', i: 5 });
    ok(oob?.code === 'bad', 'spaces: goto out of range -> bad');
    a.send(JSON.stringify({ t: 'spaces.sub', on: false }));
    a.send(JSON.stringify({ t: 'spaces.goto', i: 1 }));
    const after = await a.next(1500);
    ok(after === null, 'spaces: no state pushed after unsubscribing');
    a.close();
  } finally {
    proc.kill('SIGTERM');
  }
}

async function multiDisplay(ok, opts) {
  const { proc, log, authed, waitLog } = await startFake('6,3', opts);
  try {
    ok(log.some((l) => l.includes('fake spaces: 6,3')), 'spaces: helper started with --fake-spaces 6,3');
    const a = await authed('spaces multi');
    ok(!!a, 'spaces multi: authed');
    const s0 = await a.req({ t: 'spaces.sub', on: true });
    ok(s0?.t === 'spaces' && s0.displays?.length === 2 && s0.display === 'fake-1' &&
      s0.displays[0].count === 6 && s0.displays[1].count === 3 && s0.count === 6 && s0.current === 1,
      `spaces multi: two displays (6, 3), active fake-1, top-level mirrors it (${JSON.stringify(s0)})`);
    const both = await a.until((m) => m.t === 'spaces' && m.displays[0].thumbs[0] > 0 && m.displays[1].thumbs[0] > 0);
    ok(!!both, 'spaces multi: each display\'s current desktop gets a thumbnail');
    const t1 = await a.req({ t: 'spaces.thumb', i: 1, display: 'fake-2' });
    ok(t1?.t === 'spaces.thumb' && t1.display === 'fake-2' && t1.v === both?.displays[1].thumbs[0], 'spaces multi: thumb of display fake-2 by id');
    const t0 = await a.req({ t: 'spaces.thumb', i: 1 });
    ok(t0?.display === 'fake-1' && t0.v === both?.displays[0].thumbs[0] && t0.v !== t1?.v, 'spaces multi: thumb without display = active display');
    ok((await a.req({ t: 'spaces.thumb', i: 1, display: 'nope' }))?.code === 'notfound', 'spaces multi: thumb of unknown display -> notfound');
    ok((await a.req({ t: 'spaces.thumb', i: 1, display: 7 }))?.code === 'bad', 'spaces multi: non-string display -> bad');
    ok((await a.req({ t: 'spaces.thumb', i: 5, display: 'fake-2' }))?.code === 'notfound', 'spaces multi: index past that display -> notfound');

    // Active display switch (stdin `display 2`) pushes a state without any client message.
    proc.stdin.write('display 2\n');
    const sw = await a.until((m) => m.t === 'spaces' && m.display === 'fake-2', 3000);
    ok(sw?.count === 3 && sw.current === 1 && sw.thumbs.length === 3 && sw.thumbs[0] === both?.displays[1].thumbs[0],
      `spaces multi: active display switch pushes state mirroring fake-2 (${JSON.stringify(sw)})`);

    // goto on the active display: plain steps, no warp.
    a.send(JSON.stringify({ t: 'spaces.goto', i: 3 }));
    ok(await waitLog(/DRY spaces goto=3 from=1 via=step display=fake-2/), 'spaces multi: goto 3 on the active display (fake-2)');
    const g2 = await a.until((m) => m.t === 'spaces' && m.displays[1].current === 3);
    ok(g2?.current === 3 && g2.display === 'fake-2', 'spaces multi: fake-2 now on desktop 3');
    ok(!log.some((l) => /DRY spaces warp/.test(l)), 'spaces multi: no cursor warp for the active display');

    // goto on the other display: warp the cursor (logged), step there, and it becomes active.
    a.send(JSON.stringify({ t: 'spaces.goto', i: 5, display: 'fake-1' }));
    ok(await waitLog(/DRY spaces goto=5 from=1 via=step display=fake-1/), 'spaces multi: goto 5 on fake-1 logged');
    ok(await waitLog(/DRY spaces warp display=fake-1/), 'spaces multi: cursor warp to fake-1 logged (dry run)');
    const g1 = await a.until((m) => m.t === 'spaces' && m.displays[0].current === 5);
    ok(g1?.display === 'fake-1' && g1.current === 5 && g1.count === 6 && g1.displays[1].current === 3,
      'spaces multi: fake-1 on desktop 5 and active; fake-2 keeps desktop 3');
    ok((await a.req({ t: 'spaces.goto', i: 4, display: 'fake-2' }))?.code === 'bad', 'spaces multi: goto past fake-2\'s 3 desktops -> bad');
    ok((await a.req({ t: 'spaces.goto', i: 1, display: 'gone' }))?.code === 'notfound', 'spaces multi: goto on unknown display -> notfound');
    ok((await a.req({ t: 'spaces.goto', i: 1, display: [] }))?.code === 'bad', 'spaces multi: goto with non-string display -> bad');

    // Regression (chaos `displays`): gotos sent while the previous one is still stepping. Every state
    // change must be pushed (a silent refresh in goto swallowed it), and the arrow keys that are
    // actually sent must add up to the last target (no overshoot from two key runs).
    await new Promise((r) => setTimeout(r, 700));
    const keys0 = log.length;
    for (const i of [2, 6, 6]) { a.send(JSON.stringify({ t: 'spaces.goto', i, display: 'fake-1' })); await new Promise((r) => setTimeout(r, 15)); }
    const g6 = await a.until((m) => m.t === 'spaces' && m.displays[0].current === 6, 2000);
    ok(g6?.displays[0].current === 6, 'spaces multi: back-to-back gotos 2, 6, 6 push the final desktop 6');
    await new Promise((r) => setTimeout(r, 900));
    const net = log.slice(keys0).reduce((s, l) => s + (/DRY key usage=79 mods=1/.test(l) ? 1 : /DRY key usage=80 mods=1/.test(l) ? -1 : 0), 0);
    ok(net === 1, `spaces multi: the arrow keys sent add up to 5 -> 6 (net ${net})`);
    a.close();
  } finally {
    proc.kill('SIGTERM');
  }
}
