// Spawns the Mote helper for tests. ALWAYS with --dry-run --headless --no-ble: nothing is ever injected.
import { spawn, execFile } from 'node:child_process';
import fs from 'node:fs';
import net from 'node:net';
import path from 'node:path';
import readline from 'node:readline';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
export const REPO = path.resolve(here, '../../..');
export const BIN = process.env.MOTE_HELPER_BIN || path.join(REPO, 'helper/build/MoteHelper.app/Contents/MacOS/MoteHelper');

// Never leave a helper behind, even if the runner crashes or is interrupted.
const live = new Set();
const reap = () => { for (const p of live) { try { process.kill(p.pid, 'SIGCONT'); p.kill('SIGKILL'); } catch {} } };
process.on('exit', reap);
for (const s of ['SIGINT', 'SIGTERM']) process.on(s, () => { reap(); process.exit(130); });

export const sleep =(ms) => new Promise((r) => setTimeout(r, ms));

// Picks a free port below the ephemeral range (49152+): a listener on a recently used ephemeral port
// can collide with the thousands of client sockets (and TIME_WAITs) a chaos run creates.
export async function freePort() {
  for (;;) {
    const p = 20000 + Math.floor(Math.random() * 25000);
    const ok = await new Promise((res) => {
      const s = net.createServer();
      s.on('error', () => res(false));
      s.listen({ port: p, host: '::', ipv6Only: false }, () => s.close(() => res(true)));
    });
    if (ok) return p;
  }
}

let helpText;
/** The helper's --help text: chaos feature-detects test seams by their flags. */
export async function helperHelp() {
  helpText ??= await new Promise((res) => execFile(BIN, ['--help'], { timeout: 5000 }, (_e, out) => res(String(out ?? ''))));
  return helpText;
}
export const hasFlag = async (flag) => (await helperHelp()).includes(flag);

function run(cmd, args) {
  return new Promise((res) => execFile(cmd, args, { maxBuffer: 1 << 24 }, (err, out) => res(err && !out ? '' : out)));
}

/**
 * opts.stdin: pipe stdin (for --stdin-control), written with h.stdin(). opts.store: 'memory' (default) or { keychainService } (opt-in; touches the login Keychain).
 * Returns a handle with the pairing code, a parsed DRY-line log, and RSS / fd samplers.
 */
export async function startHelper(opts = {}) {
  if (!fs.existsSync(BIN)) throw new Error(`helper binary missing: ${BIN}\n  build it: ARCHS=arm64 helper/build.sh`);
  const port = opts.port ?? (await freePort());
  const args = ['--dry-run', '--headless', '--no-ble', '--port', String(port)];
  if (opts.openPairing !== false) args.push('--open-pairing');
  if (opts.store?.keychainService) args.push('--keychain-service', opts.store.keychainService);
  else args.push('--memory-store');
  if (opts.extraArgs) args.push(...opts.extraArgs);   // e.g. --fake-spaces N
  // Don't advertise dry-run helpers on the user's LAN (phones nearby would list them), unless asked.
  if (opts.bonjour !== true && !args.includes('--no-bonjour') && (await hasFlag('--no-bonjour'))) args.push('--no-bonjour');
  if (!args.includes('--dry-run')) throw new Error('refusing to start the helper without --dry-run');

  const proc = spawn(BIN, args, { stdio: [opts.stdin ? 'pipe' : 'ignore', 'pipe', 'pipe'] });
  live.add(proc);
  proc.on('exit', () => live.delete(proc));
  const h = {
    proc, pid: proc.pid, port, args, code: null, exit: null,
    log: [],            // last N stderr lines
    out: [],            // last N stdout lines (PAIRING CODE, BRIDGE CODE, NEW DEVICE, ...)
    dryCount: 0,        // DRY lines (one per executed input)
    dryByKind: {},
    markers: new Map(), // text marker -> times executed (DRY text "<marker>")
    listeners: new Set(),
  };
  let readyResolve;
  const ready = new Promise((r) => (readyResolve = r));
  readline.createInterface({ input: proc.stdout }).on('line', (l) => {
    const m = /PAIRING CODE: (\d{6})/.exec(l);
    if (m) h.code = m[1];
    h.out.push(l);
    if (h.out.length > 400) h.out.splice(0, 200);
    for (const f of h.listeners) f(l);
  });
  // Always drain stderr: a full pipe would block the helper's main thread and look like a hang.
  readline.createInterface({ input: proc.stderr }).on('line', (l) => {
    h.log.push(l);
    if (h.log.length > 4000) h.log.splice(0, 2000);
    const d = /^\[mote\] DRY (\w+)/.exec(l);
    if (d) {
      h.dryCount++;
      h.dryByKind[d[1]] = (h.dryByKind[d[1]] || 0) + 1;
      const mk = /^\[mote\] DRY text "(MK[^"]*)"/.exec(l);
      if (mk) h.markers.set(mk[1], (h.markers.get(mk[1]) || 0) + 1);
    }
    if (l.includes('wifi: listening')) readyResolve();
    for (const f of h.listeners) f(l);
  });
  proc.on('exit', (code, signal) => { h.exit = { code, signal }; readyResolve(); });

  h.alive = () => h.exit === null;
  /** Writes to the helper's stdin (opts.stdin + --stdin-control); no-op otherwise. */
  h.stdin = (s) => { if (proc.stdin && h.alive()) proc.stdin.write(s); };
  h.kill = (sig = 'SIGKILL') => { try { process.kill(h.pid, sig); } catch {} };
  h.stop = async () => {
    if (!h.alive()) return h.exit;
    h.kill('SIGCONT'); h.kill('SIGTERM');
    for (let i = 0; i < 40 && h.alive(); i++) await sleep(50);
    if (h.alive()) { h.kill('SIGKILL'); for (let i = 0; i < 40 && h.alive(); i++) await sleep(50); }
    return h.exit;
  };
  h.waitExit = async (ms = 5000) => { for (let t = 0; t < ms && h.alive(); t += 25) await sleep(25); return h.exit; };
  /** Resident set size in KiB. */
  h.rss = async () => parseInt(await run('ps', ['-o', 'rss=', '-p', String(h.pid)]), 10) || 0;
  /** Physical footprint in KiB (includes compressed pages, which RSS hides). */
  h.footprint = async () => {
    const m = /Footprint: ([\d.]+) (KB|MB|GB)/.exec(await run('footprint', [String(h.pid)]));
    return m ? Math.round(parseFloat(m[1]) * { KB: 1, MB: 1024, GB: 1048576 }[m[2]]) : 0;
  };
  /** { all: open fds, tcp: TCP sockets, established } from lsof. */
  h.fds = async () => {
    const all = (await run('lsof', ['-n', '-P', '-p', String(h.pid)])).trim().split('\n').length - 1;
    const tcpLines = (await run('lsof', ['-n', '-P', '-a', '-p', String(h.pid), '-iTCP'])).trim().split('\n').slice(1).filter(Boolean);
    const states = {};
    for (const l of tcpLines) { const st = /\((\w+)\)\s*$/.exec(l)?.[1] ?? 'none'; states[st] = (states[st] || 0) + 1; }
    return { all, tcp: tcpLines.length, established: states.ESTABLISHED || 0, states };
  };
  /** Live heap objects of a class, via heap(1) (e.g. 'dispatch_source_t'). */
  h.heapCount = async (cls) => {
    const out = await run('heap', [String(h.pid)]);
    const m = new RegExp(`^\\s*(\\d+)\\s+\\d+\\s+[\\d.]+\\s+${cls}\\s`, 'm').exec(out);
    return m ? parseInt(m[1], 10) : 0;
  };
  /** True if the helper still holds a TCP socket whose remote port is `port` (our client's local port). */
  h.holdsPeerPort = async (port) => (await run('lsof', ['-n', '-P', '-a', '-p', String(h.pid), '-iTCP'])).split('\n').some((l) => l.includes(`:${port} (`) || l.includes(`:${port}->`) || new RegExp(`->\\S*:${port}\\b`).test(l));
  h.waitLine = (re, ms = 3000) => new Promise((res) => {
    const f = (l) => { if (re.test(l)) { h.listeners.delete(f); clearTimeout(t); res(l); } };
    const t = setTimeout(() => { h.listeners.delete(f); res(null); }, ms);
    h.listeners.add(f);
  });

  await Promise.race([ready, sleep(10000)]);
  if (!h.alive()) throw new Error(`helper exited at start: ${JSON.stringify(h.exit)}\n${h.log.join('\n')}`);
  for (let i = 0; i < 40 && opts.openPairing !== false && !h.code; i++) await sleep(25);
  return h;
}

/** Samples RSS and fds every `ms` until stopped; returns { stop(): {rssMaxKB, rssLastKB, fdMax, tcpMax} }. */
export function sampler(h, ms = 500) {
  const s = { rssMax: 0, rssFirst: 0, rssLast: 0, fdMax: 0, tcpMax: 0, n: 0, fpSeries: [] };
  const t0 = Date.now();
  let on = true;
  (async () => {
    while (on && h.alive()) {
      const [r, f] = await Promise.all([h.rss(), h.fds()]);
      if (!s.n) s.rssFirst = r;
      if (s.fpSeries.length < Math.floor((Date.now() - t0) / 5000) + 1) s.fpSeries.push(Math.round((await h.footprint()) / 1024));
      s.n++; s.rssLast = r; s.rssMax = Math.max(s.rssMax, r); s.fdMax = Math.max(s.fdMax, f.all); s.tcpMax = Math.max(s.tcpMax, f.tcp);
      await sleep(ms);
    }
  })();
  return { stats: s, stop: () => { on = false; return s; } };
}

/** Polls until the helper's TCP socket count drops to `max` (the listener counts as 1). */
export async function waitTcpDrain(h, max = 1, ms = 5000) {
  let f;
  for (let t = 0; t <= ms; t += 100) { f = await h.fds(); if (f.tcp <= max) return f; await sleep(100); }
  return f;
}
