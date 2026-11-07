// Assertion collector + shared post-scenario invariants.
import { sleep, waitTcpDrain } from './helper.mjs';
import { open } from './ws.mjs';

/** The three loopback peer addresses the helper sees as distinct rate-limit / connection-cap keys. */
export const PEERS = ['127.0.0.1', '::1', 'fe80::1%lo0'];

/**
 * Rubric invariants (chaos/RUBRIC.md section 2). Every check maps to at most one; the runner prints
 * pass/fail per invariant. I5 (nothing on disk), I7 (no crash-loop on persisted state), I8 (UI never
 * silent) and I9 (device state restored) are checked by the Android suite and by `disk` here.
 */
export const INVARIANTS = {
  I1: 'no input injected before auth',
  I2: 'no duplicate execution',
  I3: 'bounded memory / fds / queues',
  I4: 'streams stop on disconnect within budget',
  I5: 'no secrets or screen frames written to disk',
  I6: 'auto-trust only per the trust rules',
  I7: 'never crash-loops on persisted state',
  I8: 'every failure maps to a visible state',
  I9: 'user device state restored',
  I10: 'liveness: no crash, hang or unanswered client',
};

// Default classification of untagged checks, by message (first match). Explicit tags win.
const RULES = [
  [/pre-?auth|unauth|before auth|auth bypass|bypass/i, 'I1', 'S1'],
  [/twice|exactly once|executed once|duplicate|more than once/i, 'I2', 'S2'],
  [/trust|whois|approve|prompt|pinn|tailnet peer/i, 'I6', 'S1'],
  [/memory|footprint|fds|leak|drain|bounded|queue|timers left|unread cap/i, 'I3', 'S2'],
  [/stopp|lease|half-open/i, 'I4', 'S2'],
  [/alive|crash|hang|pong|responsive|survived|reconnect/i, 'I10', 'S2'],
];

/** "S1 I6" | {sev, inv} | undefined -> {sev, inv} */
function classify(msg, tag) {
  let sev, inv;
  if (typeof tag === 'string') { sev = /S[1-4]/.exec(tag)?.[0]; inv = /I\d+/.exec(tag)?.[0]; }
  else if (tag) ({ sev, inv } = tag);
  const r = RULES.find(([re]) => re.test(msg));
  return { sev: sev ?? r?.[2] ?? 'S3', inv: inv ?? r?.[1] ?? null };
}

export class Checker {
  constructor(name) {
    this.name = name; this.pass = 0; this.fail = 0; this.failures = []; this.metrics = {}; this.notes = []; this.cases = 0;
    this.findings = [];     // {sev, inv, msg}
    this.inv = {};          // I -> {pass, fail}
    this.budgets = [];      // {name, actual, limit, unit, ok}
    this.pendings = [];     // features not built yet (feature-detected), reported as PENDING, never a failure
  }
  /** tag: optional "S1 I6" (severity and invariant), else classified from msg. */
  ok(cond, msg, detail, tag) {
    const c = classify(msg, tag);
    if (c.inv) { const e = (this.inv[c.inv] ??= { pass: 0, fail: 0 }); cond ? e.pass++ : e.fail++; }
    if (cond) this.pass++;
    else {
      this.fail++;
      const text = detail === undefined ? msg : `${msg}: ${typeof detail === 'string' ? detail : JSON.stringify(detail)}`;
      if (this.failures.length < 20) this.failures.push(`[${c.sev}${c.inv ? ' ' + c.inv : ''}] ${text}`);
      this.findings.push({ sev: c.sev, inv: c.inv, msg: text.slice(0, 300) });
    }
    return cond;
  }
  /** A budget from RUBRIC.md section 3: passes when actual <= limit (null actual = not measured = fail). */
  budget(name, actual, limit, { unit = 'ms', tag } = {}) {
    const ok = actual !== null && actual !== undefined && Number.isFinite(actual) && actual <= limit;
    this.budgets.push({ name, actual, limit, unit, ok });
    return this.ok(ok, `budget ${name}: ${actual ?? 'n/a'} ${unit} (limit ${limit})`, undefined, tag ?? 'S3');
  }
  /** Feature-detected skip: the feature isn't in this build yet. */
  pending(what, why) { this.pendings.push(`${what}: ${why}`); }
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
export async function invariants(h, chk, { baseFds, baseFp, fpBoundKB = 96 * 1024, host = PEERS[2], listeners = 1 } = {}) {
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
  const f = await waitTcpDrain(h, listeners, 8000);
  chk.ok(f.tcp <= listeners, `no leaked TCP connections (only the listener${listeners > 1 ? 's' : ''} left)`, f);
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
