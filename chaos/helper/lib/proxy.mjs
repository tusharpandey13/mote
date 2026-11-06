// Small TCP fault proxy (toxiproxy-lite). Per direction it can add latency + jitter, cap bandwidth,
// drip bytes, and drop whole WebSocket frames (frame-aware, so the stream stays parseable).
// Per connection it can go half-open (stop forwarding, keep both sockets open) or RST.
import net from 'node:net';

/** Splits a byte stream into [HTTP head, ...WS frames]; returns complete units, keeps the rest. */
class FrameSplitter {
  constructor() { this.buf = Buffer.alloc(0); this.inHttp = true; }
  push(d) {
    this.buf = Buffer.concat([this.buf, d]);
    const out = [];
    for (;;) {
      if (this.inHttp) {
        const i = this.buf.indexOf('\r\n\r\n');
        if (i < 0) { if (this.buf.length > 16384) { out.push({ bytes: this.buf, head: true }); this.buf = Buffer.alloc(0); } return out; }
        out.push({ bytes: this.buf.subarray(0, i + 4), head: true });
        this.buf = this.buf.subarray(i + 4); this.inHttp = false; continue;
      }
      const b = this.buf;
      if (b.length < 2) return out;
      let len = b[1] & 0x7f, off = 2;
      if (len === 126) { if (b.length < 4) return out; len = b.readUInt16BE(2); off = 4; }
      else if (len === 127) { if (b.length < 10) return out; len = Number(b.readBigUInt64BE(2)); off = 10; }
      if (b[1] & 0x80) off += 4;
      if (len > 1 << 20) { out.push({ bytes: b, head: true }); this.buf = Buffer.alloc(0); return out; } // garbage: pass through
      if (b.length < off + len) return out;
      out.push({ bytes: b.subarray(0, off + len), opcode: b[0] & 0x0f });
      this.buf = b.subarray(off + len);
    }
  }
}

/**
 * opts: { target: {host, port}, rng, latency: [min,max] ms, jitter: ms, drop: 0..1 (data frames),
 *         bps: bytes/s cap (0 = none), drip: {bytes, ms} }
 */
export async function startProxy(opts) {
  const conns = new Set();
  const stats = { accepted: 0, droppedFrames: 0, forwardedBytes: 0 };
  const server = net.createServer((client) => {
    stats.accepted++;
    const upstream = net.connect(opts.target);
    const c = { client, upstream, halfOpen: false, timers: new Set() };
    conns.add(c);
    const end = () => { conns.delete(c); for (const t of c.timers) clearTimeout(t); client.destroy(); upstream.destroy(); };
    for (const s of [client, upstream]) { s.on('error', () => {}); s.setNoDelay(true); }
    const pipe = (from, to) => {
      const split = new FrameSplitter();
      let nextAt = 0;
      from.on('data', (d) => {
        if (c.halfOpen) return;
        for (const unit of split.push(d)) {
          const isData = unit.opcode !== undefined && unit.opcode <= 2;
          if (isData && opts.drop && opts.rng.chance(opts.drop)) { stats.droppedFrames++; continue; }
          const [lo, hi] = opts.latency ?? [0, 0];
          const lat = lo + opts.rng.next() * (hi - lo) + (opts.jitter ? opts.rng.next() * opts.jitter : 0);
          const xfer = opts.bps ? (unit.bytes.length / opts.bps) * 1000 : 0;
          // Keep order: never deliver before the previous unit.
          nextAt = Math.max(Date.now() + lat, nextAt) + xfer;
          const pieces = [];
          if (opts.drip) for (let i = 0; i < unit.bytes.length; i += opts.drip.bytes) pieces.push(unit.bytes.subarray(i, i + opts.drip.bytes));
          else pieces.push(unit.bytes);
          for (const p of pieces) {
            const at = nextAt;
            if (opts.drip) nextAt += opts.drip.ms;
            const t = setTimeout(() => { c.timers.delete(t); if (!c.halfOpen && !to.destroyed) { to.write(p); stats.forwardedBytes += p.length; } }, Math.max(0, at - Date.now()));
            c.timers.add(t);
          }
        }
      });
      from.on('end', () => { if (c.halfOpen) return; const t = setTimeout(() => to.end(), Math.max(0, nextAt - Date.now())); c.timers.add(t); });
      from.on('close', () => { if (!c.halfOpen) setTimeout(end, Math.max(0, nextAt - Date.now()) + 5); });
    };
    pipe(client, upstream);
    pipe(upstream, client);
  });
  await new Promise((r) => server.listen(0, '127.0.0.1', r));
  return {
    port: server.address().port,
    stats,
    conns,
    /** Stop forwarding on every live connection but keep the sockets open (peer looks alive). */
    halfOpenAll() { for (const c of conns) { c.halfOpen = true; for (const t of c.timers) clearTimeout(t); } },
    /** RST both sides of every live connection. */
    resetAll() { for (const c of conns) { for (const s of [c.client, c.upstream]) { try { s.resetAndDestroy(); } catch { s.destroy(); } } conns.delete(c); } },
    async close() { this.resetAll(); await new Promise((r) => server.close(r)); },
  };
}
