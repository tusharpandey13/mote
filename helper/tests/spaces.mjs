// Spaces (protocol 1.3) checks, run by smoke.mjs against its own helper started with
// --dry-run --fake-spaces 4: generated thumbnails, no Screen Recording, nothing injected.
import crypto from 'node:crypto';
import { spawn } from 'node:child_process';
import path from 'node:path';
import readline from 'node:readline';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
export const BIN = process.env.MOTE_HELPER || path.resolve(here, '../build/MoteHelper.app/Contents/MacOS/MoteHelper');

export async function spacesChecks(ok, { tailnet = false } = {}) {
  const args = ['--dry-run', '--headless', '--no-ble', '--memory-store', '--open-pairing', '--port', '0', '--fake-spaces', '4'];
  if (tailnet) args.push('--allow-tailscale');
  if (!args.includes('--dry-run')) throw new Error('refusing to run the helper without --dry-run');
  const proc = spawn(BIN, args, { stdio: ['ignore', 'pipe', 'pipe'] });
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
    ok(log.some((l) => l.includes('fake spaces: 4')), 'spaces: helper started with --fake-spaces 4');
    const open = () => new Promise((res, rej) => {
      const ws = new WebSocket(`ws://127.0.0.1:${port}/ws`), q = [], waiters = [];
      ws.onmessage = (e) => { const m = JSON.parse(e.data); const w = waiters.shift(); w ? w(m) : q.push(m); };
      ws.onerror = rej;
      ws.next = (ms = 3000) => new Promise((r) => { const m = q.shift(); if (m) return r(m); const t = setTimeout(() => { waiters.splice(waiters.indexOf(f), 1); r(null); }, ms); const f = (x) => { clearTimeout(t); r(x); }; waiters.push(f); });
      ws.req = (m) => { ws.send(JSON.stringify(m)); return ws.next(); };
      ws.until = async (pred, ms = 3000) => { const end = Date.now() + ms; for (;;) { const m = await ws.next(Math.max(1, end - Date.now())); if (!m || pred(m)) return m; } };
      ws.onopen = () => res(ws);
    });

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
