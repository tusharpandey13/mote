#!/usr/bin/env node
// Host-side controller for the journey suite (e2e/journeys/run.sh starts it).
// The instrumented tests on the emulator reach it at http://10.0.2.2:<port> (the emulator's alias
// for the host loopback); it only listens on 127.0.0.1. It:
//  - starts / stops / signals dry-run Mote Helpers with journey-specific fakes and feeds their
//    --stdin-control; every helper is forced to `--dry-run --headless --memory-store --no-ble
//    --no-bonjour --stdin-control` so nothing can inject input, prompt, touch the Keychain or be
//    seen by a real phone on the LAN;
//  - serves each helper's log (stderr `[mote] ...` + stdout `NEW DEVICE:`, `DRY ...`) for assertions;
//  - stores step screenshots (/tmp/mote-journeys/<journey>/<step>.png) and step results
//    (/tmp/mote-journeys/results.jsonl) for REPORT.md.
import http from 'node:http';
import net from 'node:net';
import fs from 'node:fs';
import path from 'node:path';
import { spawn } from 'node:child_process';

const PORT = Number(process.env.MJ_CONTROLLER_PORT || 8799);
const OUT = process.env.MJ_OUT || '/tmp/mote-journeys';
const BIN = process.env.MOTE_HELPER_BIN ||
  path.resolve(path.dirname(new URL(import.meta.url).pathname), '../../helper/build/MoteHelper.app/Contents/MacOS/MoteHelper');
const FORCED = ['--dry-run', '--headless', '--memory-store', '--no-ble', '--no-bonjour', '--stdin-control'];
// Journey flags only: anything that could reach real state (Keychain service, web root) is refused.
const ALLOWED = /^--(fake-[a-z-]+|require-approval|no-instant-pairing|labs-pairing|labs-tv|allow-tailscale|open-pairing|store-file|host-id)$/;
const WITH_VALUE = new Set(['--fake-spaces', '--fake-context', '--fake-tailscale-owner', '--fake-bt-pair-fail', '--store-file', '--host-id']);

fs.mkdirSync(OUT, { recursive: true });
const helpers = new Map(); // name -> {proc, port, lines: [], args}

async function freePort() {
  for (;;) {
    const p = 18800 + Math.floor(Math.random() * 1000);
    const ok = await new Promise(r => { const s = net.createServer().once('error', () => r(false)).listen(p, '0.0.0.0', () => s.close(() => r(true))); });
    if (ok && ![...helpers.values()].some(h => h.port === p)) return p;
  }
}

function validate(args) {
  const out = [];
  for (let i = 0; i < args.length; i++) {
    const a = String(args[i]);
    if (!ALLOWED.test(a)) throw new Error(`flag not allowed: ${a}`);
    out.push(a);
    if (WITH_VALUE.has(a)) {
      if (i + 1 >= args.length) throw new Error(`${a} needs a value`);
      let v = String(args[++i]);
      // --store-file: a file-backed vault (never the Keychain), confined to <out>/files/<name>
      if (a === '--store-file') { fs.mkdirSync(path.join(OUT, 'files'), { recursive: true }); v = path.join(OUT, 'files', safe(path.basename(v))); }
      out.push(v);
    }
  }
  return out;
}

async function start(name, args, port) {
  await stop(name);
  if (!fs.existsSync(BIN)) throw new Error(`helper binary missing: ${BIN} (ARCHS=arm64 helper/build.sh)`);
  port = port || await freePort();
  const argv = [...FORCED, '--port', String(port), ...validate(args || [])];
  const proc = spawn(BIN, argv, { stdio: ['pipe', 'pipe', 'pipe'] });
  const h = { proc, port, lines: [], args: argv, exited: null };
  const logf = fs.createWriteStream(path.join(OUT, `helper-${name}.log`), { flags: 'a' });
  logf.write(`--- start ${new Date().toISOString()} ${argv.join(' ')}\n`);
  const feed = stream => { let buf = ''; stream.on('data', d => { buf += d; let i; while ((i = buf.indexOf('\n')) >= 0) { const l = buf.slice(0, i); buf = buf.slice(i + 1); h.lines.push(l); logf.write(l + '\n'); } }); };
  feed(proc.stdout); feed(proc.stderr);
  proc.on('exit', (c, s) => { h.exited = { code: c, signal: s }; logf.write(`--- exit ${c} ${s}\n`); });
  helpers.set(name, h);
  const ready = await waitFor(h, /wifi: listening on :\d+/, 0, 10000);
  if (!ready) throw new Error(`helper ${name} did not start: ${h.lines.slice(-5).join(' | ')}`);
  return { port, args: argv };
}

async function stop(name) {
  const h = helpers.get(name);
  if (!h) return false;
  helpers.delete(name);
  if (!h.exited) {
    try { h.proc.kill('SIGCONT'); } catch {}
    h.proc.kill('SIGTERM');
    await new Promise(r => { const t = setTimeout(() => { try { h.proc.kill('SIGKILL'); } catch {} r(); }, 2000); h.proc.once('exit', () => { clearTimeout(t); r(); }); });
  }
  return true;
}

async function waitFor(h, re, from, timeoutMs) {
  const end = Date.now() + timeoutMs;
  for (;;) {
    for (let i = from; i < h.lines.length; i++) if (re.test(h.lines[i])) return { index: i, line: h.lines[i] };
    if (Date.now() > end || h.exited) return null;
    await new Promise(r => setTimeout(r, 50));
  }
}

function body(req) {
  return new Promise((res, rej) => { const c = []; req.on('data', d => c.push(d)); req.on('end', () => res(Buffer.concat(c))); req.on('error', rej); });
}
const safe = s => String(s || '').replace(/[^A-Za-z0-9._-]/g, '_').slice(0, 80);

const server = http.createServer(async (req, res) => {
  const u = new URL(req.url, 'http://x');
  const send = (code, obj) => { res.writeHead(code, { 'content-type': 'application/json' }); res.end(JSON.stringify(obj)); };
  try {
    const q = Object.fromEntries(u.searchParams);
    if (u.pathname === '/shot' && req.method === 'POST') {
      const dir = path.join(OUT, safe(q.journey));
      fs.mkdirSync(dir, { recursive: true });
      fs.writeFileSync(path.join(dir, `${safe(q.step)}.png`), await body(req));
      return send(200, { ok: true });
    }
    const j = req.method === 'POST' ? JSON.parse((await body(req)).toString() || '{}') : q;
    const h = helpers.get(j.name || 'mac');
    switch (u.pathname) {
      case '/ping': return send(200, { ok: true });
      case '/helper/start': return send(200, await start(j.name || 'mac', j.args, Number(j.port) || 0));
      case '/helper/info': if (!h || h.exited) return send(404, { error: 'no helper' }); return send(200, { port: h.port, args: h.args });
      case '/helper/stop': return send(200, { ok: await stop(j.name || 'mac') });
      case '/helper/stopall': for (const n of [...helpers.keys()]) await stop(n); return send(200, { ok: true });
      case '/helper/stdin': if (!h) return send(404, { error: 'no helper' }); h.proc.stdin.write(String(j.line) + '\n'); return send(200, { ok: true });
      case '/helper/signal': {
        if (!h) return send(404, { error: 'no helper' });
        if (!['SIGSTOP', 'SIGCONT'].includes(j.signal)) return send(400, { error: 'signal' });
        h.proc.kill(j.signal); return send(200, { ok: true });
      }
      case '/helper/log': {
        if (!h) return send(404, { error: 'no helper' });
        const from = Number(j.from) || 0;
        return send(200, { lines: h.lines.slice(from), next: h.lines.length, exited: h.exited });
      }
      case '/helper/wait': {
        if (!h) return send(404, { error: 'no helper' });
        // Java-style \Q...\E literal quoting (Pattern.quote) isn't JS syntax: expand it.
        const re = String(j.re).replace(/\\Q([\s\S]*?)(?:\\E|$)/g, (_, lit) => lit.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'));
        const m = await waitFor(h, new RegExp(re), Number(j.from) || 0, Math.min(Number(j.timeoutMs) || 5000, 60000));
        return send(200, m ? { ok: true, ...m } : { ok: false, tail: h.lines.slice(-8) });
      }
      case '/file': { // a fixture for a helper flag (e.g. --fake-context FILE): returns its absolute path
        const dir = path.join(OUT, 'files'); fs.mkdirSync(dir, { recursive: true });
        const f = path.join(dir, safe(j.file));
        fs.writeFileSync(f, String(j.content ?? ''));
        return send(200, { path: f });
      }
      case '/step': {
        fs.appendFileSync(path.join(OUT, 'results.jsonl'), JSON.stringify({ ...j, at: new Date().toISOString() }) + '\n');
        return send(200, { ok: true });
      }
      default: return send(404, { error: 'unknown endpoint' });
    }
  } catch (e) { send(500, { error: String(e.message || e) }); }
});

server.listen(PORT, '127.0.0.1', () => console.log(`journeys controller on 127.0.0.1:${PORT}, out ${OUT}`));
const bye = async () => { for (const n of [...helpers.keys()]) await stop(n); process.exit(0); };
process.on('SIGTERM', bye); process.on('SIGINT', bye);
