// Labs "Context & now playing" (preview) against the real helper in --dry-run with fake sources.
// Starts the helper itself (nothing is injected: --dry-run, --memory-store, fake context + now playing).
//   node helper/tests/labs-context.mjs [port]     (MOTE_HELPER= overrides the binary)
import { spawn } from 'node:child_process';
import crypto from 'node:crypto';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const bin = process.env.MOTE_HELPER || path.join(here, '../build/MoteHelper.app/Contents/MacOS/MoteHelper');
const basePort = +(process.argv[2] || 8796);
let failed = 0;
const ok = (c, msg) => { console.log((c ? 'PASS ' : 'FAIL ') + msg); if (!c) failed++; };
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'mote-labs-'));
const ctxFile = path.join(dir, 'ctx.json');
const writeCtx = (o) => fs.writeFileSync(ctxFile, JSON.stringify(o));

// The helper replies on the socket before its stderr line reaches this process through the pipe: poll the log, never read it once.
async function logged(h, test, ms = 3000) {
  const end = Date.now() + ms;
  while (Date.now() < end) { if (test(h.logs)) return true; await sleep(25); }
  return test(h.logs);
}

function startHelper(port, extra) {
  const p = spawn(bin, ['--dry-run', '--headless', '--no-ble', '--memory-store', '--open-pairing', '--port', String(port), ...extra]);
  p.logs = '';
  p.stderr.on('data', (d) => (p.logs += d));
  p.code = new Promise((res) => {
    let out = '';
    p.stdout.on('data', (d) => { out += d; const m = /PAIRING CODE: (\d{6})/.exec(out); if (m) res(m[1]); });
  });
  return p;
}

// The helper prints PAIRING CODE before its listener is necessarily accepting: retry a refused connect instead of crashing.
async function open(port) {
  for (let i = 0; ; i++) {
    try { return await open1(port); } catch (e) { if (i >= 40) throw e; await sleep(100); }
  }
}
function open1(port) {
  return new Promise((res, rej) => {
    const ws = new WebSocket(`ws://127.0.0.1:${port}/ws`), q = [], waiters = [];
    ws.onmessage = (e) => { const m = JSON.parse(e.data); const w = waiters.shift(); w ? w(m) : q.push(m); };
    ws.onerror = rej;
    ws.all = q;
    // Next message matching [f] within [ms] (others stay queued), or null.
    ws.wait = (f = () => true, ms = 3000) => new Promise((r) => {
      const i = q.findIndex(f);
      if (i >= 0) return r(q.splice(i, 1)[0]);
      const t = setTimeout(() => { const k = waiters.indexOf(w); if (k >= 0) waiters.splice(k, 1); r(null); }, ms);
      const w = (m) => { if (f(m)) { clearTimeout(t); r(m); } else { q.push(m); waiters.push(w); } };
      waiters.push(w);
    });
    ws.req = (m, f) => { ws.send(JSON.stringify(m)); return ws.wait(f); };
    ws.onopen = () => res(ws);
  });
}
const mac = (token, nonce) => crypto.createHmac('sha256', Buffer.from(token, 'hex')).update(nonce).digest('hex');

async function session(port, code) {
  const ws = await open(port);
  const hello = await ws.wait();
  const early = await ws.req({ t: 'labs.ctx.sub', on: true });
  ok(early.t === 'err' && early.code === 'unauth', 'labs.ctx.sub before auth rejected');
  const p = await ws.req({ t: 'pair', code, name: 'labs test' });
  const a = await ws.req({ t: 'auth', id: p.id, mac: mac(p.token, hello.nonce) });
  ok(a.t === 'authed', 'paired and authed');
  return ws;
}

const isCtx = (m) => m.t === 'labs.ctx';
const isNp = (m) => m.t === 'labs.np';

// --- run 1: fake context (Netflix in Chrome) + fake now playing
{
  const port = basePort;
  writeCtx({ app: 'Google Chrome', bundle: 'com.google.Chrome', url: 'https://www.netflix.com/watch/8123?trackId=1', title: 'Netflix' });
  const h = startHelper(port, ['--fake-context', ctxFile, '--fake-nowplaying']);
  try {
    const code = await h.code;
    const ws = await session(port, code);

    const c = await ws.req({ t: 'labs.ctx.sub', on: true }, isCtx);
    ok(c && c.enabled === true, 'labs.ctx: enabled with fake sources');
    // The first state can be the empty one if it races the first debounce; wait for the pushed one.
    const ctx = c.bundle ? c : await ws.wait(isCtx, 2000);
    ok(ctx && ctx.bundle === 'com.google.Chrome' && ctx.browser === true && ctx.host === 'www.netflix.com', 'context: Chrome, browser, host');
    ok(ctx && ctx.url === undefined && ctx.title === undefined && ctx.full === false, 'privacy default: host only, no URL or title');
    ok(ctx && ctx.text === false && ctx.automation === 'granted', 'context: no text focus, automation state');

    const np = await ws.req({ t: 'labs.np.sub', on: true }, isNp);
    ok(np && np.enabled && np.source === 'fake' && np.np?.title === 'Fake Song' && np.np.duration === 300, 'labs.np: fake session');
    ok(np && Array.isArray(np.seek) && np.seek[0] === 'fake' && np.seek.at(-1) === 'keys', 'labs.np: seek paths, best first');
    ok(np && typeof np.np.at === 'number' && np.np.playing === true, 'labs.np: elapsed timestamp and playing');

    const s = await ws.req({ t: 'labs.np.seek', to: 100 }, (m) => m.t === 'labs.np.seek' || m.t === 'err');
    ok(s && s.t === 'labs.np.seek' && s.via === 'fake' && s.to === 100, 'seek to 100 via the now-playing session');
    const after = await ws.wait((m) => isNp(m) && m.np?.elapsed === 100, 2000);
    ok(!!after, 'labs.np pushed with the new position');
    const rel = await ws.req({ t: 'labs.np.seek', by: -10 }, (m) => m.t === 'labs.np.seek');
    ok(rel && rel.to >= 89 && rel.to <= 92, 'relative seek -10 s');
    const big = await ws.req({ t: 'labs.np.seek', to: 9999 }, (m) => m.t === 'labs.np.seek');
    ok(big && big.to === 299, 'seek clamps to the duration');
    const bad = await ws.req({ t: 'labs.np.seek' }, (m) => m.t === 'err');
    ok(bad && bad.code === 'bad', 'seek without to/by rejected');
    const unk = await ws.req({ t: 'labs.ctx.nope' }, (m) => m.t === 'err');
    ok(unk && unk.code === 'unknown', 'unknown labs.ctx message rejected');

    // Debounce: two quick changes, only the last is pushed.
    ws.all.length = 0;
    writeCtx({ app: 'Finder', bundle: 'com.apple.finder' });
    await sleep(60);
    writeCtx({ app: 'Notes', bundle: 'com.apple.Notes', text: true });
    const t0 = Date.now();
    const notes = await ws.wait((m) => isCtx(m) && m.bundle === 'com.apple.Notes', 2000);
    ok(notes && notes.text === true && notes.browser === false, 'text field focus pushed');
    ok(notes && Date.now() - t0 >= 200, `pushed after the 250 ms debounce (${Date.now() - t0} ms)`);
    ok(!ws.all.some((m) => isCtx(m) && m.bundle === 'com.apple.finder'), 'the intermediate context was coalesced');
    await sleep(400);
    ok(!ws.all.some((m) => isCtx(m) && m.bundle === 'com.apple.Notes'), 'no duplicate push for an unchanged context');

    ok(await logged(h, (l) => l.includes('DRY labs seek via=fake to=100.0')), 'helper logged the dry-run seek');
    ws.close();
  } finally { h.kill(); }
}

// --- run 2: fake context only (YouTube in Chrome): seek falls back to JavaScript, then keys
{
  const port = basePort + 1;
  writeCtx({ app: 'Google Chrome', bundle: 'com.google.Chrome', url: 'https://www.youtube.com/watch?v=x', title: 'A video' });
  const h = startHelper(port, ['--fake-context', ctxFile]);
  try {
    const ws = await session(port, await h.code);
    await ws.req({ t: 'labs.ctx.sub', on: true }, isCtx);
    await ws.wait((m) => isCtx(m) && m.host === 'www.youtube.com', 2000);
    const np = await ws.req({ t: 'labs.np.sub', on: true }, isNp);
    ok(np && np.np === null && !np.seek.includes('mediaremote') && np.seek.includes('js'), 'dry run: no real now playing; JS seek offered for Chrome');
    const s = await ws.req({ t: 'labs.np.seek', by: 10 }, (m) => m.t === 'labs.np.seek');
    ok(s && s.via === 'js', 'seek via browser JavaScript (dry run: logged only)');
    ok(await logged(h, (l) => l.includes('DRY labs seek via=js bundle=com.google.Chrome by=10')), 'helper logged the JS seek');

    writeCtx({ app: 'IINA', bundle: 'com.colliderli.iina' });
    await ws.wait((m) => isCtx(m) && m.bundle === 'com.colliderli.iina', 2000);
    const k = await ws.req({ t: 'labs.np.seek', by: 15 }, (m) => m.t === 'labs.np.seek');
    ok(k && k.via === 'keys', 'non-browser app: arrow-key stepping');
    await sleep(300);
    ok(await logged(h, (l) => (l.match(/DRY key usage=79 mods=0 action=tap/g) || []).length === 3), '15 s = 3 Right-arrow taps (dry run)');
    ws.close();
  } finally { h.kill(); }
}

// --- run 3: no fake flags: context lab off by default, now playing on by default
{
  const port = basePort + 2;
  const h = startHelper(port, []);
  try {
    const ws = await session(port, await h.code);
    const c = await ws.req({ t: 'labs.ctx.sub' }, isCtx);
    ok(c && c.enabled === false && c.bundle === undefined, 'lab off by default: labs.ctx says disabled, no context');
    const n = await ws.req({ t: 'labs.np.sub' }, isNp);
    ok(n && n.enabled === true, 'now playing is on by default (not a lab)');
    ws.close();
  } finally { h.kill(); }
}

// --- run 4: "Share now playing" switched off (stdin `nowplaying off`): labs.np disabled, seek refused
{
  const port = basePort + 3;
  const h = startHelper(port, ['--stdin-control']);
  try {
    const ws = await session(port, await h.code);
    h.stdin.write('nowplaying off\n');
    await new Promise((r) => setTimeout(r, 300));
    const n = await ws.req({ t: 'labs.np.sub' }, isNp);
    ok(n && n.enabled === false, 'nowplaying off: labs.np says disabled');
    const s = await ws.req({ t: 'labs.np.seek', by: 10 }, (m) => m.t === 'err');
    ok(s && s.code === 'unsupported', 'seek refused while Share now playing is off');
    ws.close();
  } finally { h.kill(); }
}

fs.rmSync(dir, { recursive: true, force: true });
console.log(failed ? `${failed} FAILED` : 'all passed');
process.exit(failed ? 1 : 0);
