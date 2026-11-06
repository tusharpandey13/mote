// Grammar-based + mutational generator for v1 protocol messages and WebSocket framing.
// gen(r, ctx) -> { bytes, desc, expectReply, mayClose, marker }
import { frame } from './ws.mjs';

class Raw { constructor(s) { this.s = s; } }
const raw = (s) => new Raw(s);

function ser(v) {
  if (v instanceof Raw) return v.s;
  if (Array.isArray(v)) return '[' + v.map(ser).join(',') + ']';
  if (v && typeof v === 'object') return '{' + Object.entries(v).map(([k, x]) => JSON.stringify(k) + ':' + ser(x)).join(',') + '}';
  return JSON.stringify(v);
}

const WEIRD_NUM = ['1e400', '-1e400', '1e-400', '-0', '9007199254740993', '18446744073709551616', '-9223372036854775809',
  '123456789012345678901234567890123456789', '1.7976931348623157e308', '4.9e-324', '0.1e1', '1E+2'];
const BAD_TOKENS = ['NaN', 'Infinity', '-Infinity', 'undefined', '0x10', '01', '1.', '.5', '+1', "'x'", 'tru', 'nul'];
const STR_ESC = ['\\ud800', '\\udfff', '\\ud83d', '\\u0000', '\\u001f', '\\uffff', '\\ufeff', '\\u202e'];
const TYPES = ['hello', 'pair', 'paired', 'auth', 'authed', 'state', 'err', 'ping', 'pong', 'key', 'text', 'move', 'button',
  'scroll', 'clickAt', 'consumer', 'macro', 'cancel', 'wait'];

function weird(r, depth = 0) {
  switch (r.int(0, 13)) {
    case 0: return raw(r.pick(WEIRD_NUM));
    case 1: return r.int(-(2 ** 31), 2 ** 31);
    case 2: return r.next() * 1e6 - 5e5;
    case 3: return r.pick([true, false, null]);
    case 4: return '';
    case 5: return 'x'.repeat(r.int(1, r.chance(0.2) ? 70000 : 3000));
    case 6: return raw('"' + Array.from({ length: r.int(1, 8) }, () => r.pick(STR_ESC)).join('') + '"');
    case 7: return depth < 3 ? [weird(r, depth + 1), weird(r, depth + 1)] : [];
    case 8: return depth < 3 ? { t: weird(r, depth + 1), x: weird(r, depth + 1) } : {};
    case 9: return String(r.int(-5, 300));
    case 10: return r.pick([2 ** 53, -(2 ** 53), Number.MAX_SAFE_INTEGER, -1, 0, 1, 255, 256, 65535, 65536, 2 ** 31, 2 ** 32]);
    case 11: return 'Grüße 東京 👋\n\t\r';
    case 12: return r.pick(TYPES);
    default: return raw('"' + 'a'.repeat(r.int(0, 10)) + '"');
  }
}

/** Mostly-valid values per type, with a chance of any field going weird. */
function message(r, ctx) {
  const t = r.chance(0.08) ? r.pick(['nope', '', 'PING', 'Key', 'ping\u0000', 'x'.repeat(5000), '__proto__', 'constructor']) : r.pick(TYPES);
  const usage = () => r.pick([4, 40, 44, 80, 82, 58, 104, 224, 0, 3, 300, -1]);
  const mods = () => r.pick([0, 1, 2, 8, 15, 256, 511, -1, 2 ** 31]);
  const step = () => r.pick([
    { t: 'key', usage: usage(), mods: mods() }, { t: 'wait', ms: r.pick([0, 10, 300, 5000, 5001, -1]) },
    { t: 'text', s: 'abc' }, { t: 'move', dx: 3, dy: -2 }, { t: 'macro', steps: [] }, { t: 'cancel' }, {},
  ]);
  const base = {
    pair: () => ({ code: ctx.badCode(r), name: r.pick(['chaos', '', 'x'.repeat(200), 'Grüße']) }),
    auth: () => ({ id: r.pick([ctx.id ?? 'x', 'x', '']), mac: r.pick(['00'.repeat(32), 'zz', '0', '', 'ff'.repeat(200), '+f'.repeat(32)]) }),
    ping: () => ({ n: r.pick([1, 0, -1, 'str', null, [1, 2], { a: 1 }]) }),
    key: () => ({ usage: usage(), mods: mods(), action: r.pick(['tap', 'down', 'up', 'hold', '']) }),
    text: () => ({ s: r.pick(['hi', '', 'Grüße, 東京 👋\n', 'x'.repeat(2000), 'x'.repeat(2001), '👋'.repeat(1001)]) }),
    move: () => ({ dx: r.pick([0, 1, -1, 4000, 4001, 0.5, -3999.9]), dy: r.pick([0, 2, -2, 4000, -4001]) }),
    button: () => ({ b: r.pick(['left', 'right', 'middle', 'x']), action: r.pick(['click', 'down', 'up', 'x']), count: r.pick([0, 1, 2, 3, 4]) }),
    scroll: () => ({ dx: r.pick([0, 40, -40, 1e9]), dy: r.pick([0, 40, -2000, 2001]) }),
    clickAt: () => ({ x: r.pick([0, 0.5, 1, 1.1, -0.1, 5000]), y: r.pick([0, 0.5, 1, 2]), unit: r.pick(['norm', 'pt', 'x']), display: r.pick([0, 1, 15, 16, -1, 2 ** 40]), b: 'left', count: r.pick([0, 1, 3]) }),
    consumer: () => ({ key: r.pick(['play', 'next', 'prev', 'volup', 'voldown', 'mute', 'brightup', 'brightdown', 'nope']) }),
    macro: () => ({ steps: r.chance(0.1) ? Array.from({ length: r.int(60, 70) }, step) : Array.from({ length: r.int(0, 6) }, step) }),
    wait: () => ({ ms: r.pick([0, 100, 5000, 5001]) }),
  };
  const m = { t, ...(Object.hasOwn(base, t) ? base[t]() : {}) };
  // Never send real-looking brightness or click storms more than the fuzzer needs; all of it is dry-run anyway.
  for (const k of Object.keys(m)) if (r.chance(0.15)) m[k] = weird(r);
  if (r.chance(0.1)) Object.defineProperty(m, r.pick(['extra', 'T', 'v', '__proto__']), { value: weird(r), enumerable: true, writable: true, configurable: true });
  if (r.chance(0.05)) delete m.t;
  return m;
}

function topLevel(r) {
  switch (r.int(0, 9)) {
    case 0: return '[' .repeat(r.int(1, 40000));                                          // deep unterminated
    case 1: { const d = r.int(100, 30000); return '['.repeat(d) + ']'.repeat(d); }        // deep array
    case 2: { const d = r.int(100, 10000); return '{"t":'.repeat(d) + '1' + '}'.repeat(d); } // deep object
    case 3: return r.pick(BAD_TOKENS);
    case 4: return r.pick(['null', '"ping"', '1', '[]', '{}', '[{"t":"ping"}]', '{"t":null}', '{"t":1}', '{"t":["ping"]}']);
    case 5: return '{"t":"ping","n":"' + 'y'.repeat(r.int(60000, 66000)) + '"}';      // around the 64 KiB cap
    case 6: return '{"t":"text","s":"' + 'z'.repeat(r.int(1, 100)) + '",' + '"k":1,'.repeat(r.int(1000, 9000)) + '"e":0}'; // many keys
    case 7: return '{"t":"ping","t":"key","usage":4}';                                  // duplicate keys
    case 8: return '﻿{"t":"ping"}';                                                 // BOM
    default: return ' \n\t{"t" : "ping" , "n" : 2 }\r\n ';
  }
}

function mutate(r, buf) {
  let b = Buffer.from(buf);
  for (let k = r.int(1, 4); k > 0; k--) {
    const i = r.int(0, Math.max(0, b.length - 1));
    switch (r.int(0, 6)) {
      case 0: if (b.length) b[i] ^= 1 << r.int(0, 7); break;
      case 1: b = Buffer.concat([b.subarray(0, i), r.bytes(r.int(1, 8)), b.subarray(i)]); break;
      case 2: b = Buffer.concat([b.subarray(0, i), b.subarray(Math.min(b.length, i + r.int(1, 16)))]); break;
      case 3: b = Buffer.concat([b.subarray(0, i), b.subarray(i, i + r.int(1, 32)), b.subarray(i)]); break;
      case 4: b = b.subarray(0, i); break;
      case 5: b = Buffer.concat([b.subarray(0, i), Buffer.from(r.pick([[0xff], [0xc0, 0x80], [0xed, 0xa0, 0x80], [0xf8, 0x88, 0x80, 0x80, 0x80], [0xe2, 0x82]])), b.subarray(i)]); break;
      default: b = Buffer.concat([b.subarray(0, i), Buffer.from(r.pick(['"', '{', '}', '[', ']', ',', ':', '\\', '\\u', 'e9999'])), b.subarray(i)]);
    }
  }
  return b;
}

/** Wraps a payload in WebSocket framing, sometimes broken. */
function framing(r, payload) {
  const roll = r.next();
  if (roll < 0.70) return { bytes: frame(1, payload), how: 'text' };
  if (roll < 0.76) return { bytes: frame(2, payload), how: 'binary' };
  if (roll < 0.86) {                                                   // fragmented, maybe with control frames interleaved
    const k = r.int(2, 6), parts = [];
    let pos = 0;
    for (let i = 0; i < k; i++) {
      const end = i === k - 1 ? payload.length : Math.min(payload.length, pos + r.int(0, Math.ceil(payload.length / k) + 1));
      parts.push(frame(i === 0 ? 1 : 0, payload.subarray(pos, end), { fin: i === k - 1 }));
      if (r.chance(0.3)) parts.push(frame(9, Buffer.from('pp')));
      pos = end;
    }
    return { bytes: Buffer.concat(parts), how: 'fragmented' };
  }
  if (roll < 0.88) return { bytes: frame(0, payload), how: 'continuation-first' };
  if (roll < 0.90) return { bytes: frame(1, payload, { mask: false }), how: 'unmasked', closes: true };
  if (roll < 0.92) return { bytes: frame(r.pick([3, 4, 5, 6, 7, 11, 12, 13, 14, 15]), payload), how: 'reserved-opcode', closes: true };
  if (roll < 0.93) return { bytes: Buffer.concat([frame(1, Buffer.alloc(0), { len: 2 ** 53 }).subarray(0, 14), payload]), how: 'len-2^53', closes: true };
  if (roll < 0.94) return { bytes: frame(1, Buffer.alloc(65537, 0x20)), how: 'oversized', closes: true };
  if (roll < 0.95) return { bytes: Buffer.concat([frame(1, Buffer.alloc(40000, 0x20), { fin: false }), frame(0, Buffer.alloc(30000, 0x20))]), how: 'oversized-fragments', closes: true };
  if (roll < 0.97) return { bytes: frame(9, payload.subarray(0, r.int(0, 300))), how: 'ws-ping', control: true };
  if (roll < 0.98) return { bytes: frame(1, payload, { rsv: r.int(1, 7) }), how: 'rsv-bits' };
  return { bytes: frame(8, Buffer.from([0x03, 0xe8])), how: 'close', closes: true };
}

/** ctx: { badCode(r), id, markers: bool, seq }. */
export function gen(r, ctx) {
  let text, desc, marker;
  const roll = r.next();
  if (ctx.markers && roll < 0.08) {
    marker = `MK${ctx.tag}-${ctx.seq}`;
    text = JSON.stringify({ t: 'text', s: marker });
    desc = 'marker';
  } else if (roll < 0.55) { text = ser(message(r, ctx)); desc = 'grammar'; }
  else if (roll < 0.70) { text = topLevel(r); desc = 'toplevel'; }
  let payload;
  if (text === undefined) { payload = mutate(r, Buffer.from(ser(message(r, ctx)))); desc = 'mutated'; }
  else payload = Buffer.from(text);
  if (marker) return { bytes: frame(1, payload), desc, marker, expectReply: false, mayClose: false };
  const f = framing(r, payload);
  // A JSON reply is guaranteed for every complete data message, except a parsed `pong` (no reply)
  // and, when authed, valid input (no reply). The check is only strict for unauthenticated sessions.
  const maybePong = payload.includes('pong');
  return {
    bytes: f.bytes, desc: `${desc}/${f.how}`, mayClose: !!f.closes || (!f.control && payload.length > 65536),
    expectReply: !f.closes && !f.control && !maybePong && payload.length <= 65536,
    payload,
  };
}
