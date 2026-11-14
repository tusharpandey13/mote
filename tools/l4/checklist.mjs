// L4 release checklist: walks the L4 rows of the core journey specs on a real phone + Mac, records the results.
//   node tools/l4/checklist.mjs [--list] [--only ID,ID] [--out DIR] [--dry-run-answers p,f,s,...]
// Steps per row: steps.mjs. --only runs a subset; results of other rows already in today's report are kept.
// Answers: p pass, f fail (asks a note), n note, s skip, q quit (rest = "not run"). Piped stdin supplies answers too.
import { readFileSync, writeFileSync, mkdirSync, existsSync } from "node:fs";
import { join } from "node:path";
import { createInterface } from "node:readline";
import { execSync } from "node:child_process";
import { ROOT, allRows } from "../journeys/rows.mjs";
import { STEPS } from "./steps.mjs";

const arg = (n) => { const i = process.argv.indexOf(n); return i > 0 ? process.argv[i + 1] : null; };
const rows = allRows().filter((r) => r.level === "L4");   // MANUAL-n rows are L4 rows by definition; L3 ones are not ours
const esc = (s) => String(s).replace(/\|/g, "\\|").replace(/\n/g, " ");
const only = arg("--only")?.split(",").map((x) => x.trim());
const missing = rows.filter((r) => !STEPS[r.id]).map((r) => r.id);
const show = (r) => {
  const s = STEPS[r.id];
  const out = [`Goal: ${r.step}`];
  if (!s) return [...out, `Expected: ${r.expected}`, `(no steps written for ${r.id}: add them to tools/l4/steps.mjs)`].join("\n");
  if (s.prep) out.push(`Before: ${s.prep}`);
  out.push("Do:", ...s.do.map((d, i) => `  ${i + 1}. ${d}`), `Pass if: ${s.pass}`);
  return out.join("\n");
};

if (process.argv.includes("--list")) {
  rows.forEach((r) => console.log(`${r.id}\t${r.journey}\n${show(r)}\n`));
  if (missing.length) console.error(`no steps for: ${missing.join(", ")}`);
  process.exit(missing.length ? 1 : 0);
}

const sh = (c) => { try { return execSync(c, { cwd: ROOT, stdio: ["ignore", "pipe", "ignore"] }).toString().trim(); } catch { return "unknown"; } };
const appVersion = readFileSync(join(ROOT, "android/app/build.gradle.kts"), "utf8").match(/versionName\s*=\s*"([^"]+)"/)?.[1] ?? "unknown";
const helperVersion = readFileSync(join(ROOT, "helper/Info.plist"), "utf8").match(/CFBundleShortVersionString<\/key>\s*<string>([^<]+)/)?.[1] ?? "unknown";
const helperDesc = helperVersion.startsWith("__") ? sh("git describe --tags --always") : helperVersion;   // plist is a build template

const scripted = arg("--dry-run-answers")?.split(",");
const rl = scripted ? null : createInterface({ input: process.stdin, output: process.stdout, terminal: false });
const lines = rl ? rl[Symbol.asyncIterator]() : null;
const ask = async (q) => {
  if (scripted) return scripted.shift() ?? "q";
  process.stdout.write(q);
  const { value, done } = await lines.next();
  return done ? "q" : value.trim();
};

const date = new Date().toISOString().slice(0, 10);
const dir = arg("--out") ?? join(ROOT, "docs/journeys");
const file = join(dir, `l4-${date}.md`);
const results = new Map();   // id -> { result, note }; keeps today's earlier answers for rows not run now
if (existsSync(file)) for (const m of readFileSync(file, "utf8").matchAll(/^\| (\S+) \| [^|]+ \| .*? \| (pass|fail|skip) \| (.*?) \|$/gm)) results.set(m[1], { result: m[2], note: m[3].replace(/\\\|/g, "|") });
const todo = only ? only.map((id) => rows.find((r) => r.id === id)).filter(Boolean) : rows;   // in the order given
if (only && todo.length !== only.length) { console.error(`unknown L4 id in --only: ${only.filter((x) => !rows.some((r) => r.id === x)).join(", ")}`); process.exit(2); }
outer: for (const [i, r] of todo.entries()) {
  console.log(`\n[${i + 1}/${todo.length}] ${r.id}  (${r.journey})\n${show(r)}`);
  for (;;) {
    const a = (await ask("p pass, f fail, n note, s skip, q quit > ")).toLowerCase();
    const cur = results.get(r.id) ?? { result: "not run", note: "" };
    if (a === "q") break outer;
    if (a === "n") { cur.note = await ask("note: "); results.set(r.id, cur); continue; }
    if (a === "p" || a === "s" || a === "f") {
      cur.result = { p: "pass", s: "skip", f: "fail" }[a];
      if (a === "f") cur.note = (await ask("note: ")) || cur.note;
      results.set(r.id, cur);
      break;
    }
    console.log("?");
  }
}
rl?.close();

const get = (r) => results.get(r.id) ?? { result: "not run", note: "" };
const count = (k) => rows.filter((r) => get(r).result === k).length;
const md = [
  `# L4 checklist ${date}`, "",
  `- Date: ${date}`, `- Commit: ${sh("git rev-parse --short HEAD")}`, `- App: ${appVersion}`, `- Helper: ${helperDesc}`, "",
  "| ID | Journey | Step | Result | Note |", "|---|---|---|---|---|",
  ...rows.map((r) => `| ${r.id} | ${r.journey} | ${esc(r.step)} | ${get(r).result} | ${esc(get(r).note)} |`), "",
  `Totals: ${count("pass")} pass, ${count("fail")} fail, ${count("skip")} skip, ${count("not run")} not run (of ${rows.length}).`, "",
].join("\n");
mkdirSync(dir, { recursive: true });
writeFileSync(file, md);
console.log(`\nWrote ${file}`);
process.exitCode = count("fail") ? 1 : 0;
