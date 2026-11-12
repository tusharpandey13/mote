// Parses the step rows of the core journey specs (docs/journeys/{install-helper,...,mac-update}.md, J1-J7).
//   import { coreRows, parseCites, coverage } from './rows.mjs'
//   node rows.mjs coverage [--write docs/journeys/README.md]   print (or rewrite) the Coverage table
// Row: | ID | Actor | Step | Expected | Failure mode | Detected by | User sees | Recovery | Level | Test | Status |
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

export const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
export const SPECS = path.join(ROOT, 'docs/journeys');
export const CORE = [['J1', 'install-helper'], ['J2', 'setup-mac'], ['J3', 'setup-phone'], ['J4', 'first-connect'],
  ['J5', 'reconnect'], ['J6', 'daily'], ['J7', 'mac-update']].map(([j, file]) => ({ j, file }));
const cells = l => l.trim().replace(/^\|/, '').replace(/\|$/, '').split(/(?<!\\)\|/).map(c => c.trim());

export function coreRows(specs = SPECS) {
  const rows = [];
  for (const { j, file } of CORE) {
    for (const l of fs.readFileSync(path.join(specs, file + '.md'), 'utf8').split('\n')) {
      if (!/^\| [A-Z]+-\d+ \|/.test(l)) continue;
      const c = cells(l);
      if (c.length !== 11) throw new Error(`${file}: row ${c[0]} has ${c.length} columns, expected 11`);
      rows.push({ j, file, id: c[0], step: c[2], detected: c[5], level: c[8], test: c[9], status: c[10].split(/[\s(]/)[0] });
    }
  }
  return rows;
}

// "android:A#m; node:x.mjs (note); e2e:f.spec.ts \"title\"; TODO-X (note)" -> [{kind, ref, method?, title?, raw}]
export function parseCites(test) {
  const parts = []; let cur = '', depth = 0, q = false;   // split on ';' outside quotes and parentheses
  for (const ch of test) {
    if (ch === '"') q = !q; else if (!q && ch === '(') depth++; else if (!q && ch === ')') depth--;
    if (ch === ';' && !q && depth <= 0) { parts.push(cur); cur = ''; } else cur += ch;
  }
  parts.push(cur);
  return parts.map(s => s.trim()).filter(Boolean).map(raw => {
    let m;
    if ((m = raw.match(/^(TODO)-([A-Z]+-\d+)/)) || (m = raw.match(/^(MANUAL)-(\d+)/))) return { kind: m[1].toLowerCase(), ref: m[2], raw };
    if ((m = raw.match(/^(android|helper):([A-Za-z0-9_]+)(?:#([A-Za-z0-9_]+))?/))) return { kind: m[1], ref: m[2], method: m[3], raw };
    if ((m = raw.match(/^e2e:(\S+)(?:\s+"([^"]*)")?/))) return { kind: 'e2e', ref: m[1], title: m[2], raw };
    if ((m = raw.match(/^node:(\S+)/))) return { kind: 'node', ref: m[1], raw };
    return { kind: 'unparsed', ref: raw, raw };
  });
}

const LEVELS = ['L0', 'L1', 'L2', 'L3', 'L4'], STATES = ['pass', 'fail', 'untested', 'UNDEFINED'];
export function coverage(rows) {
  const line = (label, rs) => `| ${label} | ${LEVELS.map(l => rs.filter(r => r.level === l).length).join(' | ')} | ${STATES.map(s => rs.filter(r => r.status === s).length).join(' | ')} |`;
  return [`| Journey | ${LEVELS.join(' | ')} | ${STATES.join(' | ')} |`, '|' + '---|'.repeat(10),
    ...CORE.map(({ j, file }) => line(`${j} ${file}`, rows.filter(r => r.file === file))),
    line(`Total (${rows.length})`, rows)].join('\n');
}

if (process.argv[1] === fileURLToPath(import.meta.url) && process.argv[2] === 'coverage') {
  const table = coverage(coreRows()), w = process.argv.indexOf('--write');
  if (w < 0) console.log(table);
  else {
    const f = process.argv[w + 1], t = fs.readFileSync(f, 'utf8');
    const re = /\| Journey \| L0 \|[\s\S]*?\| Total \(\d+\)[^\n]*/;
    if (!re.test(t)) throw new Error('no Coverage table in ' + f);
    fs.writeFileSync(f, t.replace(re, table));
  }
}
