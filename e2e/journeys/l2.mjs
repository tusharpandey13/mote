#!/usr/bin/env node
// L2 rows of the core journeys (docs/journeys J1-J7): resolve their cited tests, run them, record results.
//   l2.mjs plan [--level L2] [journey...]   validate every cite (exit 2 on a missing class/method/file/title);
//                                           write <out>/l2/plan.json. Boots nothing.
//   l2.mjs gradle-args                      one line per gradle argument for the single Android invocation
//   l2.mjs collect                          android junit XML -> results
//   l2.mjs run-others                       helper (swift), node and e2e (playwright) cites -> results
// Results: <out>/l2/results.json { "<cite key>": { status: pass|fail|skip, log } }; report.mjs joins them to the rows.
import fs from 'node:fs';
import path from 'node:path';
import { spawnSync, spawn } from 'node:child_process';
import { coreRows, parseCites, ROOT, CORE } from './rows.mjs';

const OUT = path.join(process.env.MJ_OUT || '/tmp/mote-journeys', 'l2');
const PLAN = path.join(OUT, 'plan.json'), RES = path.join(OUT, 'results.json');
const AT = path.join(ROOT, 'android/app/src');
const read = f => fs.readFileSync(f, 'utf8');
const walk = d => fs.readdirSync(d, { withFileTypes: true }).flatMap(e => e.isDirectory() ? walk(path.join(d, e.name)) : [path.join(d, e.name)]);
const loadRes = () => fs.existsSync(RES) ? JSON.parse(read(RES)) : {};
const saveRes = r => fs.writeFileSync(RES, JSON.stringify(r, null, 1));
export const keyOf = c => c.kind + ':' + c.ref + (c.method ? '#' + c.method : '') + (c.title ? ` "${c.title}"` : '');

// ---- resolve ----
function androidIndex() {   // class name -> [{file, fq, instrumented}]
  const idx = {};
  for (const [dir, instrumented] of [['test', false], ['androidTest', true]])
    for (const f of walk(path.join(AT, dir)).filter(f => f.endsWith('.kt') && !f.includes('/androidTest/hid/'))) {
      const src = read(f), pkg = src.match(/^package ([\w.]+)/m)?.[1];
      const classes = [...src.matchAll(/^(?:internal )?class (\w+)/gm)];   // a cite must find its method inside that class
      classes.forEach((m, i) => (idx[m[1]] ||= []).push({ file: f, fq: `${pkg}.${m[1]}`, instrumented, src: src.slice(m.index, classes[i + 1]?.index) }));
    }
  return idx;
}
const hasFun = (src, name) => new RegExp(`func?\\s+\`?${name}\`?\\s*\\(`).test(src);

function resolve(cite, ctx) {   // returns error string or null; fills cite.targets for android
  if (cite.kind === 'android') {
    const hits = (ctx.android[cite.ref] || []).filter(h => !cite.method || hasFun(h.src, cite.method));
    if (!hits.length) return `no test ${cite.ref}${cite.method ? '#' + cite.method : ''} under android/app/src/{test,androidTest}`;
    cite.targets = hits.map(h => ({ fq: h.fq, instrumented: h.instrumented, method: cite.method }));
  } else if (cite.kind === 'helper') {
    const f = ctx.swift.find(f => new RegExp(`struct ${cite.ref}\\b`).test(read(f)));
    if (!f) return `no Swift suite ${cite.ref} in helper/tests/MoteHelperTests`;
    if (cite.method && !hasFun(read(f), cite.method)) return `no @Test ${cite.ref}#${cite.method}`;
  } else if (cite.kind === 'node') {
    if (!fs.existsSync(path.join(ROOT, cite.ref))) return `no file ${cite.ref}`;
  } else if (cite.kind === 'e2e') {
    const f = path.join(ROOT, 'e2e/web/tests', cite.ref);
    if (!fs.existsSync(f)) return `no file e2e/web/tests/${cite.ref}`;
    const prefix = cite.title?.replace(/[….]+$/, '');
    if (prefix && !read(f).includes(prefix)) return `no test titled "${cite.title}" in ${cite.ref}`;
  } else if (cite.kind === 'unparsed') return `cannot parse cite "${cite.raw}"`;
  return null;
}

// Does a cited test assert the row's "Detected by" event / diag field, or only UI text and log lines?
// 'n/a': the column names none; 'asserted': a cited test's source mentions `area/event` (or area and event as literals, or the last
// segment of a `diag.x.y` field); 'gap': none does. A static check on the sources, not a proof of the assertion.
function diagCoverage(row, ctx) {
  const refs = [...row.detected.matchAll(/`?\b(?:diag\.([\w.]+)|([a-z]+)\/([a-z][\w.]*))/g)].map(m => m[1] ? { field: m[1].split('.').pop() } : { area: m[2], event: m[3] });
  if (!refs.length) return { diag: 'n/a' };
  const srcs = row.cites.flatMap(c => c.kind === 'android' ? (ctx.android[c.ref] || []).map(h => h.src)
    : c.kind === 'helper' ? ctx.swift.map(read).filter(t => t.includes(`struct ${c.ref}`))
    : c.kind === 'node' ? [read(path.join(ROOT, c.ref))] : c.kind === 'e2e' ? [read(path.join(ROOT, 'e2e/web/tests', c.ref))] : []);
  const hit = r => srcs.some(t => r.field ? t.includes(r.field) : t.includes(`${r.area}/${r.event}`) || (t.includes(`"${r.area}"`) && t.includes(`"${r.event}"`)));
  return { diag: refs.some(hit) ? 'asserted' : 'gap' };
}

function plan(level, journeys) {
  const ctx = { android: androidIndex(), swift: walk(path.join(ROOT, 'helper/tests/MoteHelperTests')).filter(f => f.endsWith('.swift')) };
  const known = CORE.map(c => c.file);
  for (const j of journeys) if (!known.includes(j) && !CORE.some(c => c.j === j.toUpperCase())) throw new Error(`unknown journey ${j} (${known.join(', ')})`);
  const want = new Set(journeys.map(j => CORE.find(c => c.j === j.toUpperCase())?.file || j));
  const rows = coreRows().filter(r => r.level === level && (!want.size || want.has(r.file)));
  const errors = [];
  for (const r of rows) {
    r.cites = parseCites(r.test);
    for (const c of r.cites) { const e = resolve(c, ctx); if (e) errors.push(`${r.id}: ${e}`); c.key = keyOf(c); }
  }
  for (const r of rows) Object.assign(r, diagCoverage(r, ctx));
  if (errors.length) { console.error('l2: invalid cites in the L2 rows:\n  ' + errors.join('\n  ')); process.exit(2); }
  fs.mkdirSync(OUT, { recursive: true });
  fs.writeFileSync(PLAN, JSON.stringify({ level, journeys: [...want], rows }, null, 1));
  fs.rmSync(RES, { force: true });
  const n = k => new Set(rows.flatMap(r => r.cites).filter(c => c.kind === k).map(c => c.key)).size;
  console.error(`l2: ${rows.length} ${level} rows; cites: android ${n('android')}, helper ${n('helper')}, node ${n('node')}, e2e ${n('e2e')}, todo ${n('todo')}, manual ${n('manual')}`);
}

const cites = (...kinds) => JSON.parse(read(PLAN)).rows.flatMap(r => r.cites).filter(c => kinds.includes(c.kind));
const uniq = a => [...new Set(a)];

// ---- android: one gradle invocation ----
function gradleArgs() {
  const t = cites('android').flatMap(c => c.targets);
  const unit = uniq(t.filter(x => !x.instrumented).map(x => x.fq + (x.method ? '.' + x.method : '')));
  const instr = uniq(t.filter(x => x.instrumented).map(x => x.fq + (x.method ? '#' + x.method : '')));
  const out = ['--continue', '-PmoteJourneys'];
  if (unit.length) out.push(':app:testDevUnitTest', ...unit.flatMap(u => ['--tests', u]));
  if (instr.length) out.push(':app:connectedDevAndroidTest', '-Pandroid.testInstrumentationRunnerArguments.class=' + instr.join(','));
  console.log(out.join('\n'));
}
function collect() {
  const dirs = ['android/app/build/test-results/testDevUnitTest', 'android/app/build/outputs/androidTest-results/connected'];
  const seen = {};   // "fq#method" -> pass|fail|skip
  for (const f of dirs.flatMap(d => fs.existsSync(path.join(ROOT, d)) ? walk(path.join(ROOT, d)) : []).filter(f => /TEST-.*\.xml$/.test(f)))
    for (const m of read(f).matchAll(/<testcase name="([^"]+)" classname="([^"]+)"[^>]*?(?:\/>|>([\s\S]*?)<\/testcase>)/g)) {
      const st = /<failure|<error/.test(m[3] || '') ? 'fail' : /<skipped/.test(m[3] || '') ? 'skip' : 'pass';
      seen[`${m[2]}#${m[1]}`] = st;
    }
  const res = loadRes(), log = path.join(OUT, 'gradle.log');
  for (const c of cites('android')) {
    const sts = c.targets.map(t => {
      const hit = Object.entries(seen).filter(([k]) => k.startsWith(t.fq + '#') && (!t.method || k === `${t.fq}#${t.method}`)).map(e => e[1]);
      return hit.length ? (hit.includes('fail') ? 'fail' : hit.every(s => s === 'skip') ? 'skip' : 'pass') : 'skip';
    });
    // a Class#method cite may exist in both trees (unit and instrumented): either running counts, a failure wins
    res[c.key] = { status: sts.includes('fail') ? 'fail' : sts.includes('pass') ? 'pass' : 'skip', log };
  }
  saveRes(res);
}

// ---- helper / node / e2e ----
const run = (cmd, args, log, opts = {}) => {
  const fd = fs.openSync(log, 'w');
  const r = spawnSync(cmd, args, { stdio: ['ignore', fd, fd], cwd: ROOT, ...opts });
  fs.closeSync(fd); return r.status === 0 ? 'pass' : 'fail';
};
const HELPER = process.env.MOTE_HELPER || path.join(ROOT, 'helper/build/MoteHelper.app/Contents/MacOS/MoteHelper');

// node:helper/tests/smoke.mjs needs a paired helper: start one with --open-pairing, read its code.
async function smoke(log) {
  const port = 8791, lines = [];
  const h = spawn(HELPER, ['--dry-run', '--headless', '--no-ble', '--memory-store', '--open-pairing', '--port', String(port)], { stdio: ['ignore', 'pipe', 'pipe'] });
  h.stdout.on('data', d => lines.push(d)); h.stderr.on('data', d => lines.push(d));
  let code; for (let i = 0; i < 100 && !code; i++) { await new Promise(r => setTimeout(r, 100)); code = Buffer.concat(lines).toString().match(/PAIRING CODE:?\s*(\d+)/)?.[1]; }
  const st = code ? run('node', ['helper/tests/smoke.mjs', String(port), code, path.join(OUT, 'smoke-cred.json')], log) : 'fail';
  h.kill(); fs.appendFileSync(log, `\n--- helper ---\n${Buffer.concat(lines)}`);
  return st;
}

async function runOthers() {
  const res = loadRes(); fs.mkdirSync(OUT, { recursive: true });
  const swift = uniq(cites('helper').map(c => c.key));
  if (swift.length) {   // one swift test run; verdicts from Swift Testing's "Test name() passed|failed" lines
    const log = path.join(OUT, 'helper-unit.log');
    run(path.join(ROOT, 'helper/tests/unit.sh'), cites('helper').flatMap(c => ['--filter', c.ref + (c.method ? '/' + c.method : '')]), log);
    const txt = read(log);
    for (const c of cites('helper')) {
      const name = c.method ? new RegExp(`Test ${c.method}\\(\\) (passed|failed)`) : new RegExp(`Suite ${c.ref} (passed|failed)`);
      const m = txt.match(name);
      res[c.key] = { status: m ? (m[1] === 'passed' ? 'pass' : 'fail') : 'skip', log };
    }
  }
  for (const c of uniq(cites('node').map(c => c.ref)).map(r => cites('node').find(c => c.ref === r))) {
    const log = path.join(OUT, 'node-' + path.basename(c.ref) + '.log');
    res[c.key] = { status: c.ref.endsWith('smoke.mjs') ? await smoke(log) : run('node', [c.ref], log), log };
  }
  const e2e = cites('e2e');
  if (e2e.length) {   // one playwright run over the cited files; a cite passes when every test it names passes
    const log = path.join(OUT, 'e2e-web.log'), json = path.join(OUT, 'e2e-web.json');
    run('npx', ['playwright', 'test', ...uniq(e2e.map(c => c.ref)), '--reporter=json'], log, { cwd: path.join(ROOT, 'e2e/web'), env: { ...process.env, PLAYWRIGHT_JSON_OUTPUT_NAME: json } });
    const tests = [];
    const visit = (s, file) => { for (const sp of s.specs || []) tests.push({ file: s.file || file, title: sp.title, ok: sp.ok }); for (const c of s.suites || []) visit(c, s.file || file); };
    if (fs.existsSync(json)) for (const s of JSON.parse(read(json)).suites) visit(s, s.file);
    for (const c of e2e) {
      const prefix = c.title?.replace(/[….]+$/, '');
      const hit = tests.filter(t => path.basename(t.file) === c.ref && (!prefix || t.title.startsWith(prefix)));
      res[c.key] = { status: hit.length ? (hit.every(t => t.ok) ? 'pass' : 'fail') : 'skip', log };
    }
  }
  saveRes(res);
}

const [cmd, ...rest] = process.argv.slice(2);
if (cmd === 'plan') { const li = rest.indexOf('--level'); const level = li >= 0 ? rest.splice(li, 2)[1] : 'L2'; plan(level, rest); }
else if (cmd === 'gradle-args') gradleArgs();
else if (cmd === 'collect') collect();
else if (cmd === 'run-others') await runOthers();
else { console.error('usage: l2.mjs plan|gradle-args|collect|run-others'); process.exit(2); }
