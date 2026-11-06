// Assertion collector + shared post-scenario invariants.
import { sleep, waitTcpDrain } from './helper.mjs';
import { open } from './ws.mjs';

/** The three loopback peer addresses the helper sees as distinct rate-limit / connection-cap keys. */
export const PEERS = ['127.0.0.1', '::1', 'fe80::1%lo0'];

export class Checker {
  constructor(name) { this.name = name; this.pass = 0; this.fail = 0; this.failures = []; this.metrics = {}; this.notes = []; this.cases = 0; }
  ok(cond, msg, detail) {
    if (cond) this.pass++;
    else { this.fail++; if (this.failures.length < 20) this.failures.push(detail === undefined ? msg : `${msg}: ${typeof detail === 'string' ? detail : JSON.stringify(detail)}`); }
    return cond;
  }
  metric(k, v) { this.metrics[k] = v; }
  note(s) { this.notes.push(s); }
}

export function pct(arr, p) {
  if (!arr.length) return null;
  const s = [...arr].sort((a, b) => a - b);
  return Math.round(s[Math.min(s.length - 1, Math.floor((p / 100) * s.length))] * 10) / 10;
}

/**
 * Shared invariants after a scenario's clients are gone: alive, a fresh client gets hello + pong,
 * all TCP sockets drain back to the listener, fds back near the baseline, memory bounded.
 */
export async function invariants(h, chk, { baseFds, baseFp, fpBoundKB = 96 * 1024, host = PEERS[2] } = {}) {
  chk.ok(h.alive(), 'helper alive', h.exit);
  if (!h.alive()) { chk.note('helper log tail:\n' + h.log.slice(-15).join('\n')); return; }
  // Retry briefly: closed connections are released asynchronously and may still count toward the caps.
  let c, err;
  for (let i = 0; i < 30 && !c; i++) { try { c = await open({ port: h.port, host }); } catch (e) { err = e; await sleep(100); } }
  if (!c) chk.ok(false, 'fresh client connects', err?.message);
  if (c) {
    chk.ok(c.hello?.t === 'hello', 'fresh client gets hello', c.hello);
    const rtt = await c.ping(424242, 2000);
    chk.ok(rtt !== null, 'fresh client gets pong (not hung)');
    c.close();
    await c.waitClosed(2000);
  }
  const f = await waitTcpDrain(h, 1, 8000);
  chk.ok(f.tcp <= 1, 'no leaked TCP connections (only the listener left)', f);
  if (baseFds !== undefined) chk.ok(f.all <= baseFds + 2, `fds back to baseline (${baseFds})`, f);
  const fp = await h.footprint();
  chk.metric('footprintMB', Math.round(fp / 1024));
  if (baseFp !== undefined) chk.ok(fp - baseFp < fpBoundKB, `memory bounded (footprint +${Math.round((fp - baseFp) / 1024)} MB)`, fp);
}

/** Runs fn until the deadline or n iterations; returns iterations done. */
export async function loop(deadline, n, fn) {
  let i = 0;
  while (i < n && Date.now() < deadline) { await fn(i); i++; }
  return i;
}

export { sleep };
