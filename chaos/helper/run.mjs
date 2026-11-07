#!/usr/bin/env node
// Chaos / robustness suite for the Mote Mac helper. Always --dry-run: nothing is ever injected.
//   node chaos/helper/run.mjs [--budget 10m] [--seed N] [--only fuzz,net] [--keychain] [--json FILE]
// Scoring follows chaos/RUBRIC.md: findings by severity (S1-S4), invariants I1-I10, budgets vs actuals.
import fs from 'node:fs';
import { BIN } from './lib/helper.mjs';
import { INVARIANTS } from './lib/check.mjs';
import { rng } from './lib/rng.mjs';
import * as fuzz from './scenarios/fuzz.mjs';
import * as net from './scenarios/net.mjs';
import * as load from './scenarios/load.mjs';
import * as proc from './scenarios/process.mjs';
import * as authsc from './scenarios/auth.mjs';
import * as spaces from './scenarios/spaces.mjs';
import * as displays from './scenarios/displays.mjs';
import * as screen from './scenarios/screen.mjs';
import * as discovery from './scenarios/discovery.mjs';
import * as trust from './scenarios/trust.mjs';
import * as labs from './scenarios/labs.mjs';
import * as disk from './scenarios/disk.mjs';

const ALL = [authsc, trust, discovery, spaces, displays, screen, labs, load, net, proc, disk, fuzz];

function args() {
  const a = process.argv.slice(2), o = { budget: '10m', seed: null, only: null, keychain: false, json: null };
  for (let i = 0; i < a.length; i++) {
    if (a[i] === '--budget') o.budget = a[++i];
    else if (a[i] === '--seed') o.seed = Number(a[++i]);
    else if (a[i] === '--only') o.only = a[++i].split(',');
    else if (a[i] === '--keychain') o.keychain = true;
    else if (a[i] === '--json') o.json = a[++i];
    else if (a[i] === '-h' || a[i] === '--help') { console.log('node chaos/helper/run.mjs [--budget 10m|90s] [--seed N] [--only ' + ALL.map((s) => s.name).join(',') + '] [--keychain] [--json FILE]'); process.exit(0); }
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
console.log(`mote helper chaos: seed=${o.seed} budget=${o.budget} scenarios=${scenarios.map((s) => s.name).join(',')}`);
console.log(`binary: ${BIN}  (always --dry-run --headless --no-ble)  rubric: chaos/RUBRIC.md\n`);

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
    chk = { name: s.name, pass: 0, fail: 1, failures: ['[S2 I10] scenario threw: ' + (e.stack || e)], metrics: {}, notes: [], cases: 0,
      findings: [{ sev: 'S2', inv: 'I10', msg: 'scenario threw: ' + String(e.message || e).slice(0, 200) }], inv: { I10: { pass: 0, fail: 1 } }, budgets: [], pendings: [] };
  }
  chk.ms = Date.now() - start;
  results.push(chk);
  const status = chk.fail ? `FAIL (${chk.fail})` : chk.pendings?.length ? `ok (+${chk.pendings.length} pending)` : 'ok';
  console.log(status);
  for (const f of chk.failures) console.log('   FAIL ' + f);
  for (const n of chk.notes) console.log('   note ' + n);
  for (const p of chk.pendings ?? []) console.log('   PENDING ' + p);
}

// ---- summary: scenarios
const table = (head, rows, last = -1) => {
  const w = head.map((h, i) => Math.max(h.length, ...rows.map((r) => (i === last ? 0 : String(r[i]).length))));
  const line = (r) => r.map((x, i) => (i === last ? String(x) : String(x).padEnd(w[i]))).join('  ');
  console.log(line(head) + '\n' + line(w.map((n, i) => '-'.repeat(i === last ? 7 : n))));
  for (const r of rows) console.log(line(r));
};
console.log('');
table(['scenario', 'cases', 'checks', 'result', 'time', 'metrics'], results.map((c) => [c.name, String(c.cases ?? ''), `${c.pass}/${c.pass + c.fail}`,
  c.fail ? 'FAIL' : c.pendings?.length ? 'ok+P' : 'ok', `${Math.round(c.ms / 1000)}s`,
  Object.entries(c.metrics).map(([k, v]) => `${k}=${typeof v === 'object' ? JSON.stringify(v) : v}`).join(' ')]), 5);

// ---- rubric: findings by severity
const findings = results.flatMap((c) => (c.findings ?? []).map((f) => ({ ...f, scenario: c.name })));
const bySev = { S1: [], S2: [], S3: [], S4: [] };
for (const f of findings) (bySev[f.sev] ?? bySev.S3).push(f);
console.log('\nRubric findings (chaos/RUBRIC.md section 1): ' + Object.entries(bySev).map(([k, v]) => `${k}=${v.length}`).join(' '));
for (const [sev, list] of Object.entries(bySev)) for (const f of list.slice(0, 10)) console.log(`  ${sev} ${f.inv ?? '--'} ${f.scenario}: ${f.msg}`);

// ---- rubric: invariants
console.log('\nInvariants (section 2):');
const invRows = Object.entries(INVARIANTS).map(([id, text]) => {
  const per = results.filter((c) => c.inv?.[id]);
  const pass = per.reduce((s, c) => s + c.inv[id].pass, 0), fail = per.reduce((s, c) => s + c.inv[id].fail, 0);
  return [id, fail ? 'FAIL' : pass ? 'PASS' : 'n/a', `${pass}/${pass + fail}`, text, per.map((c) => c.name).join(',') || (['I7', 'I8', 'I9'].includes(id) ? '(chaos/android)' : '')];
});
table(['inv', 'result', 'checks', 'invariant', 'scenarios'], invRows, 4);

// ---- rubric: budgets
const budgets = results.flatMap((c) => (c.budgets ?? []).map((b) => [c.name, b.name, `${b.actual ?? 'n/a'} ${b.unit}`, `<= ${b.limit} ${b.unit}`, b.ok ? 'ok' : 'OVER']));
if (budgets.length) { console.log('\nBudgets (section 3):'); table(['scenario', 'budget', 'actual', 'limit', 'result'], budgets); }

// ---- pending (feature-detected, not built yet)
const pend = results.flatMap((c) => (c.pendings ?? []).map((p) => `${c.name}: ${p}`));
if (pend.length) { console.log(`\nPending (${pend.length}, not failures):`); for (const p of pend) console.log('  ' + p); }

const failed = results.reduce((s, c) => s + c.fail, 0);
const blocking = bySev.S1.length + bySev.S2.length;
const verdict = failed === 0 ? 'CHAOS-PASS' : blocking ? 'CHAOS-FAIL (S1/S2 findings)' : 'CHAOS-FAIL (S3/S4 only: reproduce or attribute, see section 3.5)';
console.log(`\nseed=${o.seed} budget=${o.budget}  ${verdict}  ${failed ? failed + ' check(s) failed' : 'all checks passed'}  (${Math.round((Date.now() - t0) / 1000)} s)`);
if (o.json) fs.writeFileSync(o.json, JSON.stringify({ seed: o.seed, budget: o.budget, verdict, results: results.map(({ name, pass, fail, ms, metrics, findings: f, inv, budgets: b, pendings, notes }) => ({ name, pass, fail, ms, metrics, findings: f, inv, budgets: b, pendings, notes })) }, null, 1));
process.exit(failed ? 1 : 0);
