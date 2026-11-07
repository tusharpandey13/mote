// Labs "Open… + TV mode" checks (docs/labs/open-tv.md), run by smoke.mjs against its own helpers
// in --dry-run: nothing opens, nothing is injected; actions are checked against `DRY labs ...` log lines.
import crypto from 'node:crypto';
import { spawn } from 'node:child_process';
import readline from 'node:readline';
import { BIN } from './spaces.mjs';

async function start(extra) {
  const args = ['--dry-run', '--headless', '--no-ble', '--memory-store', '--open-pairing', '--port', '0', ...extra];
  if (!args.includes('--dry-run')) throw new Error('refusing to run the helper without --dry-run');
  const proc = spawn(BIN, args, { stdio: ['ignore', 'pipe', 'pipe'] });
  const log = [];
  let code, port;
  const ready = new Promise((res) => {
    readline.createInterface({ input: proc.stdout }).on('line', (l) => { const m = /PAIRING CODE: (\d{6})/.exec(l); if (m) { code = m[1]; if (port) res(); } });
    readline.createInterface({ input: proc.stderr }).on('line', (l) => { log.push(l); const m = /wifi: listening on :(\d+)/.exec(l); if (m) { port = m[1]; if (code) res(); } });
  });
  await Promise.race([ready, new Promise((_, rej) => setTimeout(() => rej(new Error('labs helper did not start')), 10000))]);
  const open = () => new Promise((res, rej) => {
    const ws = new WebSocket(`ws://127.0.0.1:${port}/ws`), q = [], waiters = [];
    ws.onmessage = (e) => { const m = JSON.parse(e.data); const w = waiters.shift(); w ? w(m) : q.push(m); };
    ws.onerror = rej;
    ws.next = (ms = 3000) => new Promise((r) => { const m = q.shift(); if (m) return r(m); const t = setTimeout(() => { waiters.splice(waiters.indexOf(f), 1); r(null); }, ms); const f = (x) => { clearTimeout(t); r(x); }; waiters.push(f); });
    ws.req = (m) => { ws.send(JSON.stringify(m)); return ws.next(); };
    ws.until = async (pred, ms = 3000) => { const end = Date.now() + ms; for (;;) { const m = await ws.next(Math.max(1, end - Date.now())); if (!m || pred(m)) return m; } };
    ws.onopen = () => res(ws);
  });
  const authed = async (name) => {
    const a = await open(); const hello = await a.next();
    const p = await a.req({ t: 'pair', code, name });
    const mac = crypto.createHmac('sha256', Buffer.from(p.token, 'hex')).update(hello.nonce).digest('hex');
    return (await a.req({ t: 'auth', id: p.id, mac }))?.t === 'authed' ? a : null;
  };
  const waitLog = async (re, ms = 3000) => { for (let t = 0; t < ms; t += 25) { if (log.some((l) => re.test(l))) return true; await new Promise((r) => setTimeout(r, 25)); } return false; };
  return { proc, log, open, authed, waitLog };
}

export async function labsChecks(ok) {
  // Default: Labs › TV mode is off, every labs action is refused, the state says so.
  {
    const h = await start([]);
    try {
      const u = await h.open(); await u.next();
      const r0 = await u.req({ t: 'labs.open.url', url: 'https://example.com', mode: 'browser' });
      ok(r0?.code === 'unauth', 'labs: labs.open.url before auth refused (unauth)');
      u.close();
      const a = await h.authed('labs off');
      const st = await a.req({ t: 'labs.tv.get' });
      ok(st?.t === 'labs.tv.state' && st.enabled === false && st.on === false, `labs: state when off (${JSON.stringify(st)})`);
      const r = await a.req({ t: 'labs.open.url', url: 'https://example.com', mode: 'browser' });
      ok(r?.t === 'err' && r.code === 'disabled' && r.ref === 'labs.open.url', 'labs: disabled by default (err disabled)');
      ok(!h.log.some((l) => /DRY labs/.test(l)), 'labs: nothing done while disabled');
      a.close();
    } finally { h.proc.kill('SIGTERM'); }
  }

  // --labs-tv: on for this run.
  const h = await start(['--labs-tv']);
  try {
    const a = await h.authed('labs smoke');
    ok(!!a, 'labs: authed');
    const s0 = await a.req({ t: 'labs.tv.get' });
    ok(s0?.enabled === true && s0.on === false, 'labs: enabled with --labs-tv, TV off');

    // URL validation (security): only http(s) with a host, no credentials, no control characters.
    for (const url of ['javascript:alert(1)', 'file:///etc/passwd', 'data:text/html,<b>x</b>', 'intent://x#Intent;end', 'ftp://example.com',
      'https://user:pw@example.com', 'https://', 'https://exa mple.com', 'https://example.com/a\nb', 'https://example.com/\u0000', 'x'.repeat(3000), '', 'JAVASCRIPT:alert(1)']) {
      const r = await a.req({ t: 'labs.open.url', url, mode: 'browser' });
      ok(r?.t === 'err' && r.code === 'bad', `labs: refused ${JSON.stringify(url.slice(0, 32))}`);
    }
    ok((await a.req({ t: 'labs.open.url', url: 'https://example.com', mode: 'sideways' }))?.code === 'bad', 'labs: bad mode refused');
    ok(!h.log.some((l) => /DRY labs\.open\.url/.test(l)), 'labs: nothing opened for refused urls');

    a.send(JSON.stringify({ t: 'labs.open.url', url: 'https://www.netflix.com/browse', mode: 'browser', fullscreen: true }));
    ok(await h.waitLog(/DRY labs\.open\.url mode=browser fullscreen=true url=https:\/\/www\.netflix\.com\/browse/), 'labs: browser open logged (dry run)');

    // TV mode: a YouTube watch link maps to leanback; the state is pushed.
    a.send(JSON.stringify({ t: 'labs.open.url', url: 'https://youtu.be/jfKfPfyJRdk?t=30', mode: 'tv' }));
    const s1 = await a.until((m) => m.t === 'labs.tv.state' && m.on);
    ok(s1?.page === 'youtube' && s1.url === 'https://www.youtube.com/tv#/watch?v=jfKfPfyJRdk', `labs: youtu.be -> leanback watch (${JSON.stringify(s1)})`);
    a.send(JSON.stringify({ t: 'labs.open.url', url: 'https://www.youtube.com/results?search_query=lofi beats&x=1'.replace(' ', '%20'), mode: 'tv' }));
    const s2 = await a.until((m) => m.t === 'labs.tv.state' && m.url?.includes('search'));
    ok(s2?.url === 'https://www.youtube.com/tv#/search?q=lofi%20beats', `labs: search results -> leanback search (${s2?.url})`);

    for (const k of ['up', 'ok', 'back', 'playpause']) a.send(JSON.stringify({ t: 'labs.tv.key', k }));
    ok(await h.waitLog(/DRY labs\.tv key playpause/), 'labs: TV keys delivered to the TV window (dry run)');
    ok((await a.req({ t: 'labs.tv.key', k: 'launch-missiles' }))?.code === 'bad', 'labs: unknown TV key refused');

    // While TV mode is frontmost, plain D-pad keys go to the window, not global injection.
    a.send(JSON.stringify({ t: 'key', usage: 0x51 }));
    ok(await h.waitLog(/DRY labs\.tv key down/), 'labs: D-pad key routed to the TV window');
    ok(!h.log.some((l) => /DRY key usage=81/.test(l)), 'labs: ...and not injected globally');

    // Launcher with phone pins; bad pins are refused.
    ok((await a.req({ t: 'labs.tv.open', pins: [{ title: 'x', url: 'javascript:alert(1)' }] }))?.code === 'bad', 'labs: javascript: pin refused');
    a.send(JSON.stringify({ t: 'labs.tv.open', pins: [{ title: 'YouTube', url: 'https://www.youtube.com/tv' }] }));
    const s3 = await a.until((m) => m.t === 'labs.tv.state' && m.page === 'home');
    ok(s3?.on === true && s3.url === undefined, 'labs: TV home (launcher)');

    a.send(JSON.stringify({ t: 'labs.open.action', a: 'pauseAll' }));
    ok(await h.waitLog(/DRY labs\.open\.action a=pauseAll tv=true/), 'labs: pause everything logged');
    ok((await a.req({ t: 'labs.open.action', a: 'rm-rf' }))?.code === 'bad', 'labs: unknown action refused');

    a.send(JSON.stringify({ t: 'labs.tv.exit' }));
    const s4 = await a.until((m) => m.t === 'labs.tv.state' && !m.on);
    ok(s4?.on === false && (await h.waitLog(/DRY labs\.tv exit/)), 'labs: exit TV mode');
    ok((await a.req({ t: 'labs.tv.key', k: 'up' }))?.code === 'tv-off', 'labs: TV key with TV mode closed refused (tv-off)');
    a.send(JSON.stringify({ t: 'key', usage: 0x51 }));
    ok(await h.waitLog(/DRY key usage=81/), 'labs: D-pad goes back to global injection after exit');
    a.send(JSON.stringify({ t: 'labs.open.action', a: 'closeTab' }));
    ok(await h.waitLog(/DRY labs\.open\.action a=closeTab tv=false/), 'labs: close tab logged');
    a.close();
  } finally { h.proc.kill('SIGTERM'); }
}
