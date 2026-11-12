#!/usr/bin/env node
// Builds REPORT.md from the specs (docs/journeys/*.md: every path and step) and the results the
// instrumented tests recorded through the controller (<out>/results.jsonl).
// A step without a result is classified from its spec Seam column: manual / needs user -> MANUAL,
// UNDEFINED -> UNDEFINED, static -> STATIC; anything else -> NOT RUN (blocked by an earlier failure
// in the same path, or no automated test yet). Never PASS without a recorded result.
import fs from 'node:fs';
import path from 'node:path';
import { coreRows, coverage, parseCites, CORE } from './rows.mjs';

const arg = k => { const i = process.argv.indexOf(k); return i > 0 ? process.argv[i + 1] : undefined; };
const OUT = arg('--out') || '/tmp/mote-journeys';
const SPECS = arg('--specs') || 'docs/journeys';
const WRITE = arg('--write') || 'e2e/journeys/REPORT.md';

const cells = row => row.trim().replace(/^\|/, '').replace(/\|$/, '').split(/(?<!\\)\|/).map(c => c.trim());

function parseSpec(file) {
  const id = path.basename(file, '.md');
  const paths = []; let cur = null;
  for (const line of fs.readFileSync(file, 'utf8').split('\n')) {
    const h = line.match(/^#{2,4}\s.*?`([a-z0-9-]+(?:\.[A-Za-z0-9._-]+)?)`/);
    if (h && /^#{2,4}\s+(Main path|`)/.test(line)) { cur = { id: h[1], title: line.replace(/^#+\s*/, ''), steps: [] }; paths.push(cur); continue; }
    if (/^#{1,3}\s/.test(line) && !h) { cur = null; continue; }
    if (cur && /^\|\s*[A-Z]?[A-Za-z]*\d+[a-z]?\s*\|/.test(line)) {
      const c = cells(line);
      if (c.length >= 4) cur.steps.push({ step: c[0], actor: c[1], action: c[2], expected: c[3], seam: c[c.length - 1] });
    }
  }
  return { id, paths: paths.filter(p => p.steps.length) };
}

const specs = fs.readdirSync(SPECS).filter(f => f.endsWith('.md') && !['DEFECTS.md', 'README.md', 'QUESTIONS.md', 'UNDEFINED.md', 'permissions.md'].includes(f) && !CORE.some(c => c.file + '.md' === f)).sort().map(f => parseSpec(path.join(SPECS, f)));
const results = new Map();
const rf = path.join(OUT, 'results.jsonl');
if (fs.existsSync(rf)) for (const l of fs.readFileSync(rf, 'utf8').split('\n').filter(Boolean)) {
  try { const r = JSON.parse(l); results.set(`${r.path}#${r.step}`, r); } catch {}
}

const ICON = { pass: 'pass', fail: '**FAIL**', faked: 'faked', undefined: 'undefined', manual: 'manual', static: 'static', 'not run': 'not run' };
const totals = { pass: 0, fail: 0, faked: 0, undefined: 0, manual: 0, static: 0, 'not run': 0 };
const esc = s => String(s || '').replace(/\|/g, '\\|').replace(/\n/g, ' ');
let body = '';
const summary = [];
for (const s of specs) {
  const jt = { pass: 0, fail: 0, faked: 0, undefined: 0, manual: 0, static: 0, 'not run': 0 };
  let sec = `\n## ${s.id}\n\nSpec: [docs/journeys/${s.id}.md](../../docs/journeys/${s.id}.md)\n\n| Path | Step | Actor | Result | Seam / note | Shot |\n|---|---|---|---|---|---|\n`;
  for (const p of s.paths) {
    const failAt = p.steps.find(st => results.get(`${p.id}#${st.step}`)?.status === 'fail');
    const anyResult = p.steps.some(st => results.has(`${p.id}#${st.step}`));
    for (const st of p.steps) {
      const r = results.get(`${p.id}#${st.step}`);
      let status, note;
      const seam = st.seam.toLowerCase();
      if (r) { status = r.status; note = r.status === 'fail' ? r.note : (r.status === 'faked' ? r.seam : (r.note || r.seam)); }
      else if (/^(manual|needs user)/.test(seam)) { status = 'manual'; note = st.seam; }
      else if (/^undefined/.test(seam)) { status = 'undefined'; note = st.seam; }
      else if (/^static/.test(seam)) { status = 'static'; note = st.seam; }
      else { status = 'not run'; note = failAt ? `blocked: ${failAt.step} failed` : anyResult ? 'not asserted by the test' : 'no automated test for this path'; }
      jt[status]++; totals[status]++;
      const png = path.join(OUT, p.id, `${st.step}.png`);
      const shot = fs.existsSync(png) ? `[png](file://${png})` : '';
      sec += `| \`${p.id}\` | ${st.step} | ${esc(st.actor)} | ${ICON[status]} | ${esc(note).slice(0, 300)} | ${shot} |\n`;
    }
  }
  summary.push(`| [${s.id}](#${s.id}) | ${s.paths.length} | ${Object.keys(jt).map(k => jt[k]).join(' | ')} |`);
  body += sec;
}
const keys = Object.keys(totals);
// ---- core journeys J1-J7 by level (rows of docs/journeys/<core>.md; results from `run.sh --level L2`: l2.mjs) ----
const L2DIR = path.join(OUT, 'l2');
const readJson = f => fs.existsSync(f) ? JSON.parse(fs.readFileSync(f, 'utf8')) : null;
const plan = readJson(path.join(L2DIR, 'plan.json')), l2res = readJson(path.join(L2DIR, 'results.json')) || {};
const planned = new Map((plan?.rows || []).map(r => [r.id, r]));
// pass = every real cite passed and nothing is TODO; partial = real cites passed, a TODO part remains; fail wins;
// a cite that did not run (skipped, missing result) is not run; MANUAL-only rows are manual.
function rowStatus(row) {
  const cites = planned.get(row.id)?.cites || parseCites(row.test);
  const real = cites.filter(c => !['todo', 'manual'].includes(c.kind)), todo = cites.some(c => c.kind === 'todo');
  if (!real.length) return todo ? 'not run' : cites.some(c => c.kind === 'manual') ? 'manual' : 'not run';
  const sts = real.map(c => planned.has(row.id) ? (l2res[c.key]?.status || 'skip') : 'skip');
  if (sts.includes('fail')) return 'fail';
  if (sts.includes('skip')) return 'not run';
  return todo ? 'partial' : 'pass';
}
const core = coreRows(SPECS).map(r => ({ ...r, run: rowStatus(r), diag: planned.get(r.id)?.diag }));
const table = coverage(core);
const readme = fs.readFileSync(path.join(SPECS, 'README.md'), 'utf8');
const inSync = readme.includes(table);
const KS = ['pass', 'partial', 'fail', 'not run', 'manual'];
const lvls = ['L0', 'L1', 'L2', 'L3', 'L4'];
const logLinks = r => [...new Set((planned.get(r.id)?.cites || []).map(c => l2res[c.key]?.log).filter(Boolean))].map(f => `[${path.basename(f)}](file://${f})`).join(' ');
let coreMd = `\n## Core journeys by level\n\nRows of the J1-J7 specs (\`docs/journeys/README.md\`). Status comes from the last \`run.sh --level ${plan?.level || 'L2'}\` run` +
  (plan ? ` (${plan.rows.length} rows${plan.journeys.length ? ', ' + plan.journeys.join(', ') : ''})` : ' (none recorded: every row is not run)') +
  `; rows of other levels are not run by it. **pass** = all cited tests passed; **partial** = cited tests passed, a \`TODO-\` part remains; **not run** = skipped, missing or TODO only; **manual** = \`MANUAL-n\` only.\n\n` +
  `Row counts per level ${inSync ? 'match' : '**DO NOT match**'} the Coverage table in docs/journeys/README.md${inSync ? '' : ' (regenerate: `node e2e/journeys/rows.mjs coverage --write docs/journeys/README.md`)'}.\n\n` +
  `| Journey | ${lvls.join(' | ')} | L2 ${KS.join(' | L2 ')} | L2 detected-by gap |\n|---|${lvls.map(() => '---').join('|')}|${KS.map(() => '---').join('|')}|---|\n` +
  CORE.map(c => { const rs = core.filter(r => r.file === c.file); return `| ${c.j} ${c.file} | ${lvls.map(l => rs.filter(r => r.level === l).length).join(' | ')} | ${KS.map(k => rs.filter(r => r.level === 'L2' && r.run === k).length).join(' | ')} | ${rs.filter(r => r.level === 'L2' && r.diag === 'gap').length} |`; }).join('\n') + '\n';
for (const c of CORE) {
  coreMd += `\n### ${c.j} ${c.file}\n\n| Row | Level | Step | Status | Detected by asserted | Cited tests | Logs |\n|---|---|---|---|---|---|---|\n` +
    core.filter(r => r.file === c.file).map(r => `| ${r.id} | ${r.level} | ${esc(r.step).slice(0, 80)} | ${r.run} | ${r.diag || ''} | ${esc(r.test).slice(0, 160)} | ${logLinks(r)} |`).join('\n') + '\n';
}
const l2fail = core.filter(r => r.level === (plan?.level || 'L2') && r.run === 'fail');
if (l2fail.length) console.log('L2 failed rows: ' + l2fail.map(r => r.id).join(', '));
const l2sum = CORE.map(c => `${c.j}: ` + KS.map(k => `${k} ${core.filter(r => r.file === c.file && r.level === 'L2' && r.run === k).length}`).join(', ')).join('\n');

const md = `# Journey acceptance report\n\nGenerated by \`e2e/journeys/report.mjs\` on ${new Date().toISOString()} from \`docs/journeys/*.md\` and \`${rf}\`.\n` +
  `Emulator \`emulator-5562\` (AVD \`mote-journeys\`, API 35), app \`dev.mote.remote.dev\`, dry-run helpers only. Screenshots: \`${OUT}/<path>/<step>.png\`.\n\n` +
  `Statuses: **pass** = asserted for real; **faked** = asserted against a labelled test seam (never counted as pass); **fail**; **undefined** = the spec can't define it from code; ` +
  `**manual** = needs a person or real hardware (Mac dialogs, BLE, BT HID, Tailscale); **static** = checked by reading code/docs only; **not run** = no automated step yet or blocked by an earlier failure.\n\n` +
  `## Totals\n\n| ${keys.join(' | ')} | steps |\n|${keys.map(() => '---').join('|')}|---|\n| ${keys.map(k => totals[k]).join(' | ')} | ${keys.reduce((a, k) => a + totals[k], 0)} |\n\n` +
  `| Journey | paths | ${keys.join(' | ')} |\n|---|---|${keys.map(() => '---').join('|')}|\n${summary.join('\n')}\n` + coreMd + body;
fs.writeFileSync(WRITE, md);
console.log(`L2 rows by journey:\n${l2sum}`);
console.log(`report: ${keys.map(k => `${k} ${totals[k]}`).join(', ')}`);
if (process.argv.includes('--strict') && (l2fail.length || !inSync)) process.exit(1);
