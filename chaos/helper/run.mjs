#!/usr/bin/env node
// Chaos / robustness suite for the Mote Mac helper. Always --dry-run: nothing is ever injected.
//   node chaos/helper/run.mjs [--budget 10m] [--seed N] [--only fuzz,net] [--keychain]
import { BIN } from './lib/helper.mjs';
import { rng } from './lib/rng.mjs';
import * as fuzz from './scenarios/fuzz.mjs';
import * as net from './scenarios/net.mjs';
import * as load from './scenarios/load.mjs';
import * as proc from './scenarios/process.mjs';
import * as authsc from './scenarios/auth.mjs';
import * as spaces from './scenarios/spaces.mjs';

const ALL = [authsc, spaces, load, net, proc, fuzz];

function args() {
  const a = process.argv.slice(2), o = { budget: '10m', seed: null, only: null, keychain: false };
  for (let i = 0; i < a.length; i++) {
    if (a[i] === '--budget') o.budget = a[++i];
    else if (a[i] === '--seed') o.seed = Number(a[++i]);
    else if (a[i] === '--only') o.only = a[++i].split(',');
    else if (a[i] === '--keychain') o.keychain = true;
    else if (a[i] === '-h' || a[i] === '--help') { console.log('node chaos/helper/run.mjs [--budget 10m|90s] [--seed N] [--only ' + ALL.map((s) => s.name).join(',') + '] [--keychain]'); process.exit(0); }
    else { console.error('unknown arg ' + a[i]); process.exit(2); }
  }
  const m = /^(\d+(?:\.\d+)?)(ms|s|m|h)?$/.exec(o.budget);
  if (!m) { console.error('bad --budget'); process.exit(2); }
  o.budgetMs = parseFloat(m[1]) * { ms: 1, s: 1e3, m: 60e3, h: 3600e3 }[m[2] ?? 's'];
  o.seed ??= (Math.random() * 2 ** 31) >>> 0;
  return o;
}

const o = args();
const scenarios = ALL.filter((s) => !o.only || o.only.includes(s.name));
const totalW = scenarios.reduce((s, x) => s + x.weight, 0);
console.log(`mote helper chaos: seed=${o.seed} budget=${o.budget} scenarios=${scenarios.map((s) => s.name).join(',')}`);
console.log(`binary: ${BIN}  (always --dry-run --headless --no-ble)\n`);

const root = rng(o.seed);
const results = [];
const t0 = Date.now();
for (const s of scenarios) {
  const share = (o.budgetMs - (Date.now() - t0)) * (s.weight / scenarios.slice(scenarios.indexOf(s)).reduce((a, x) => a + x.weight, 0));
  const start = Date.now();
  process.stdout.write(`== ${s.name} (${Math.round(share / 1000)} s) ... `);
  let chk;
  try {
    chk = await s.run({ r: root.fork(s.name), deadline: start + share, keychain: o.keychain, seed: o.seed });
  } catch (e) {
    chk = { name: s.name, pass: 0, fail: 1, failures: ['scenario threw: ' + (e.stack || e)], metrics: {}, notes: [], cases: 0 };
  }
  chk.ms = Date.now() - start;
  results.push(chk);
  console.log(chk.fail ? `FAIL (${chk.fail})` : 'ok');
  for (const f of chk.failures) console.log('   FAIL ' + f);
  for (const n of chk.notes) console.log('   note ' + n);
}

// Summary table
const rows = results.map((c) => [c.name, String(c.cases ?? ''), `${c.pass}/${c.pass + c.fail}`, c.fail ? 'FAIL' : 'ok', `${Math.round(c.ms / 1000)}s`,
  Object.entries(c.metrics).map(([k, v]) => `${k}=${typeof v === 'object' ? JSON.stringify(v) : v}`).join(' ')]);
const head = ['scenario', 'cases', 'checks', 'result', 'time', 'metrics'];
const w = head.map((h, i) => Math.max(h.length, ...rows.map((r) => (i === 5 ? 0 : r[i].length))));
const line = (r) => r.map((x, i) => (i === 5 ? x : x.padEnd(w[i]))).join('  ');
console.log('\n' + line(head) + '\n' + line(w.map((n) => '-'.repeat(n)).concat(['-------'])));
for (const r of rows) console.log(line(r));
const failed = results.reduce((s, c) => s + c.fail, 0);
console.log(`\nseed=${o.seed}  ${failed ? failed + ' check(s) FAILED' : 'all checks passed'}  (${Math.round((Date.now() - t0) / 1000)} s)`);
process.exit(failed ? 1 : 0);
