// Protocol fuzzing: grammar + mutational JSON over raw (sometimes broken) WebSocket framing.
// Phase "unauth": a helper with no paired device; nothing may ever be injected and every complete
// message must get a reply (err/pong) or a close. Phase "authed": an authenticated session; the helper
// must stay alive and responsive, and every marker text message must execute exactly once.
import { Checker, PEERS, invariants } from '../lib/check.mjs';
import { gen } from '../lib/fuzzgen.mjs';
import { sampler, startHelper, sleep } from '../lib/helper.mjs';
import { authed, open, pair } from '../lib/ws.mjs';

export const name = 'fuzz';
export const weight = 0.4;

const WORKERS = 3;

export async function run({ r, deadline }) {
  const chk = new Checker(name);
  const mid = Date.now() + (deadline - Date.now()) * 0.5;
  const u = await phase(chk, r, 'unauth', mid);
  const a = await phase(chk, r, 'authed', deadline);
  chk.cases = u + a;
  chk.metric('cases', `${u} unauth + ${a} authed`);
  if (chk.cases < 50000) chk.note(`only ${chk.cases} cases in the budget (target ~50k): give it a bigger --budget`);
  return chk;
}

async function phase(chk, r, kind, until) {
  let h = await startHelper();
  let cred = null;
  if (kind === 'authed') {
    cred = await pair(h);
  }
  const base = { baseFds: (await h.fds()).all, baseFp: await h.footprint() };
  let mon = sampler(h, 1000);
  const st = { cases: 0, batches: 0, hangs: [], crashes: [], missing: [], unexpectedClose: [], closes: 0, completed: [], pending: [] };
  let restarting = null;

  const restart = async (why, dead) => {
    st.crashes.push(why);   // every report, so the culprit batch isn't lost when another worker saw the death first
    if (dead !== h && !restarting) return;   // already replaced
    if (!restarting) {
      restarting = (async () => {
        if (process.env.CHAOS_VERBOSE) console.error('\n[fuzz] ' + why);
        mon.stop();
        await h.stop();
        h = await startHelper();
        for (let i = 0; i < 20; i++) { try { cred = kind === 'authed' ? await pair(h) : null; break; } catch { await sleep(100); } }
        mon = sampler(h, 1000);
      })().finally(() => { restarting = null; });
    }
    await restarting;
  };

  const worker = async (w) => {
    const gr = r.fork(`fuzz-${kind}-${w}`);
    const ctx = {
      tag: `${kind[0]}${w}`, seq: 0, markers: kind === 'authed',
      badCode: (rr) => { let c; do c = String(rr.int(0, 999999)).padStart(6, '0'); while (c === h.code); return c; },
    };
    let conn = null, lastErr;
    while (Date.now() < until) {
      const myH = h;
      if (!conn || conn.closed || kind === 'unauth') {
        conn?.destroy();
        conn = null;
        for (let i = 0; i < 50 && !conn && Date.now() < until; i++) {
          try { conn = kind === 'authed' ? await authed(myH, cred, { host: PEERS[w] }) : await open({ port: myH.port, host: PEERS[w] }); }
          catch (e) { lastErr = e.message; if (!myH.alive()) break; await sleep(20); }
        }
        if (!conn) { if (!myH.alive()) { await restart(`helper died (connect) exit=${JSON.stringify(myH.exit)} log: ${myH.log.slice(-3).join(" / ")}`, myH); continue; } chk.ok(false, `${kind}: could not connect`, `${lastErr}; log: ${myH.log.slice(-3).join(' / ')}`, myH); return; }
      }
      ctx.id = cred?.id;
      const batch = [];
      for (let i = 0; i < (kind === 'unauth' ? 12 : 40); i++) {
        ctx.seq++;
        const c = gen(gr, ctx);
        batch.push(c);
        if (c.mayClose) break;
      }
      const sentinel = 1e9 + w * 1e7 + st.batches++;
      for (const c of batch) conn.raw(c.bytes);
      conn.send({ t: 'ping', n: sentinel });
      let replies = 0, gotPong = false;
      for (;;) {
        const m = await conn.next(5000);
        if (!m) break;
        if (m.t === 'pong' && m.n === sentinel) { gotPong = true; break; }
        replies++;
      }
      st.cases += batch.length;
      const last = batch.at(-1);
      const descs = () => batch.map((c) => c.desc).join(' ') + ' | last payload: ' + (last.payload ?? Buffer.alloc(0)).subarray(0, 120).toString('hex');
      if (!myH.alive()) {
        await restart(`crash after batch [${descs()}] exit=${JSON.stringify(myH.exit)} log: ${myH.log.slice(-3).join(' / ')}`, myH);
        conn = null;
        continue;
      }
      for (const c of batch) if (c.marker) (gotPong ? st.completed : st.pending).push(c.marker);
      if (gotPong) {
        const expected = batch.filter((c) => c.expectReply).length;
        if (kind === 'unauth' && replies < expected) st.missing.push(`${replies}/${expected}: ${descs()}`);
      } else if (conn.closed) {
        if (last.mayClose) st.closes++;
        else if (kind === 'unauth' && replies >= batch.filter((c) => c.expectReply).length && replies >= 20) st.closes++; // strike limit
        else st.unexpectedClose.push(descs());
      } else if (last.mayClose && !(await myH.holdsPeerPort(conn.sock.localPort))) {
        // The helper closed it (its socket is gone) but this client never saw the FIN: not a hang.
        st.closes++; st.unobservedCloses = (st.unobservedCloses || 0) + 1;
        conn.destroy(); conn = null;
      } else {
        const f = await myH.fds();
        st.hangs.push(`${descs()} | closeCode=${conn.closeCode} rx=${conn.rx} sock=${conn.sock.readyState} buf=${conn.buf.length} helperTcp=${JSON.stringify(f.states)} fresh=${await (async () => { try { const x = await open({ port: myH.port, host: PEERS[(w + 1) % 3] }); const t = await x.ping(9, 2000); x.destroy(); return t; } catch (e) { return e.message; } })()}`);
        conn.destroy();
        conn = null;
      }
    }
    conn?.destroy();
  };

  await Promise.all(Array.from({ length: WORKERS }, (_, w) => worker(w)));
  await sleep(kind === 'authed' ? 11000 : 300); // let fuzzed macros (<= 10 s of waits) finish before counting markers
  const m = mon.stop();

  const P = kind;
  chk.ok(st.crashes.length === 0, `${P}: helper never crashed`, st.crashes.slice(0, 3));
  chk.ok(st.hangs.length === 0, `${P}: helper never hung (sentinel ping answered within 5 s)`, st.hangs.slice(0, 3));
  chk.ok(st.unexpectedClose.length === 0, `${P}: no unexpected closes`, st.unexpectedClose.slice(0, 3));
  if (kind === 'unauth') {
    chk.ok(st.missing.length === 0, 'unauth: every complete message got a protocol reply or a close', st.missing.slice(0, 3));
    chk.ok(h.dryCount === 0, 'unauth: nothing injected before auth (0 DRY events)', h.dryByKind);
  } else {
    const twice = st.completed.concat(st.pending).filter((mk) => (h.markers.get(mk) || 0) > 1);
    const lost = st.completed.filter((mk) => (h.markers.get(mk) || 0) !== 1);
    chk.ok(twice.length === 0, 'authed: no message executed twice', twice.slice(0, 5));
    chk.ok(lost.length === 0, `authed: every acknowledged marker executed exactly once (${st.completed.length})`, lost.slice(0, 5));
    chk.metric('authedDryEvents', h.dryCount);
  }
  chk.metric(`${P}.cases`, st.cases);
  chk.metric(`${P}.closes`, st.closes);
  if (st.unobservedCloses) chk.metric(`${P}.closesSeenOnlyViaLsof`, st.unobservedCloses);
  chk.metric(`${P}.rssMaxMB`, Math.round(m.rssMax / 1024));
  chk.metric(`${P}.fdMax`, m.fdMax);
  chk.metric(`${P}.footprintMB/5s`, m.fpSeries.join(','));
  // Allocator high-water from 64 KiB messages / deep JSON; a leak would keep climbing (see the 5 s series).
  await invariants(h, chk, { ...base, fpBoundKB: 192 * 1024 });
  await h.stop();
  return st.cases;
}
