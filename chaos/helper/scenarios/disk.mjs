// I5: no secrets or screen frames written to disk. Runs a session that handles every secret and every
// picture the helper has (pairing token, instant-pairing key, bridge token, live-screen frames, Space
// thumbnails) and then (a) lists the regular files the helper holds open for writing, and (b) scans
// files modified during the run in the places an app writes (TMPDIR, /tmp, ~/Library caches,
// preferences, application support, saved state) for the token and for bytes of a received frame.
// --memory-store keeps the Keychain out of it; the Keychain is the designed home for secrets.
import { execFile } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { Checker } from '../lib/check.mjs';
import { startHelper, sleep } from '../lib/helper.mjs';
import { authed, pair } from '../lib/ws.mjs';

export const name = 'disk';
export const weight = 0.03;

const ROOTS = [os.tmpdir(), '/tmp', '/private/var/tmp', ...['Caches', 'Preferences', 'Application Support', 'Saved Application State', 'HTTPStorages', 'WebKit', 'Logs']
  .map((d) => path.join(os.homedir(), 'Library', d))];

function recent(dir, since, out, depth = 0) {
  if (depth > 5 || out.length > 5000) return;
  let ents; try { ents = fs.readdirSync(dir, { withFileTypes: true }); } catch { return; }
  for (const e of ents) {
    const p = path.join(dir, e.name);
    if (p.includes('mote-chaos-')) continue;            // our own fixtures
    try {
      if (e.isDirectory()) { if (fs.statSync(p).mtimeMs >= since - 1000 || depth < 2) recent(p, since, out, depth + 1); }
      else if (e.isFile()) { const st = fs.statSync(p); if (st.mtimeMs >= since && st.size < 64 << 20) out.push(p); }
    } catch {}
  }
}

const lsof = (pid) => new Promise((res) => execFile('lsof', ['-n', '-P', '-p', String(pid)], { maxBuffer: 1 << 24 }, (_e, out) => res(String(out ?? ''))));

export async function run() {
  const chk = new Checker(name);
  const since = Date.now();
  const h = await startHelper({ extraArgs: ['--fake-screen', '--fake-spaces', '3', '--labs-pairing', '--fake-approve'] });
  const cred = await pair(h);
  const c = await authed(h, cred);
  c.send({ t: 'spaces.sub' });
  c.send({ t: 'labs.screen.start', fps: 15, credits: 3 });
  const needles = [Buffer.from(cred.token), Buffer.from(cred.token, 'hex')];
  for (let i = 0; i < 40; i++) {
    const m = await c.until((x) => x.t === 'labs.screen.frame' || x.t === 'spaces.thumb', 2000);
    if (!m) break;
    if (m.t === 'labs.screen.frame') c.send({ t: 'labs.screen.ack', seq: m.seq });
    if (needles.length < 6 && m.data) { const j = Buffer.from(m.data, 'base64'); needles.push(j.subarray(j.length >> 1, (j.length >> 1) + 48), Buffer.from(m.data.slice(200, 264))); }
    if (m.t === 'labs.screen.frame' && i === 5) c.send({ t: 'spaces.thumb', i: 1 });
  }
  const open = (await lsof(h.pid)).split('\n').filter((l) => /\sREG\s/.test(l) && /\s\d+[wu]\s/.test(l))
    .filter((l) => !/\/(dev|System|usr\/lib|private\/var\/db|Library\/Caches\/com\.apple)\//.test(l) && !l.includes('/MoteHelper.app/'));
  chk.ok(open.length === 0, 'helper holds no regular file open for writing', open.slice(0, 5).map((l) => l.split(/\s+/).slice(-1)[0]), 'S1 I5');
  c.send({ t: 'labs.screen.stop' });
  c.destroy();
  await sleep(500);
  await h.stop();
  const files = [];
  for (const r of ROOTS) recent(r, since, files);
  const hits = [];
  for (const f of files) {
    let b; try { b = fs.readFileSync(f); } catch { continue; }
    if (needles.some((n) => n.length >= 32 && b.includes(n))) hits.push(f);
  }
  chk.ok(needles.length >= 4, 'collected a token and frame bytes to search for', needles.length, 'S3');
  chk.ok(hits.length === 0, `no token or frame bytes in ${files.length} files written during the run`, hits.slice(0, 5), 'S1 I5');
  chk.metric('scannedFiles', files.length);
  chk.cases = files.length;
  return chk;
}
