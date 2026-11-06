// Seeded PRNG (mulberry32). Every random choice in the chaos suite goes through one of these,
// so a run is reproducible from its --seed (timing-dependent outcomes aside).
function hash(s) {
  let h = 2166136261;
  for (const c of String(s)) h = Math.imul(h ^ c.charCodeAt(0), 16777619);
  return h >>> 0;
}

export function rng(seed) {
  let a = seed >>> 0;
  const next = () => {
    a = (a + 0x6d2b79f5) | 0;
    let t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
  const r = {
    seed,
    next,
    int: (lo, hi) => lo + Math.floor(next() * (hi - lo + 1)),
    pick: (arr) => arr[Math.floor(next() * arr.length)],
    chance: (p) => next() < p,
    bytes: (n) => { const b = Buffer.alloc(n); for (let i = 0; i < n; i++) b[i] = (next() * 256) | 0; return b; },
    /** Independent stream for a named sub-task, stable regardless of how much the parent consumed. */
    fork: (salt) => rng((seed ^ hash(salt)) >>> 0),
  };
  return r;
}
