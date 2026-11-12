// Helpers for run.sh (no deps). Subcommands:
//   rows <journey>                        JSON lines: the L3 rows (journey "vault" = built-in vault checks)
//   check <diag.json> <events.jsonl|-> <detectedBy>   (`==` required value, `=` informational) lines "pass|fail|note<TAB>text" for each diag.<path>[=value] / area/event cite
//   report <out.md> <journey> <results.tsv>           writes the markdown report
import { readFileSync, writeFileSync, existsSync } from "node:fs";
import { readRows } from "../journeys/rows.mjs";

export const vaultRows = [
  { id: "VAULT-1", step: "Look at the helper's Keychain state without touching the Keychain (the attribute-only listing)", expected: "No Keychain prompt appeared; readResult is found or notfound; otherAccounts is a list", userSees: "Nothing", detectedBy: "diag.keychain.readResult; diag.keychain.otherAccounts" },
  { id: "VAULT-2", step: "Fresh install (run reset-user.sh first, then install and open the helper)", expected: "Exactly ONE Keychain prompt in total (answer Always Allow)", userSees: "One macOS Keychain dialog", detectedBy: "diag.keychain.readResult" },
  { id: "VAULT-3", step: "Quit Mote Helper from its menu and open it again from ~/Applications", expected: "The secrets.v4.<partition> item is read back with no new prompt", userSees: "No Keychain dialog", detectedBy: "diag.keychain.readResult==found" },
];

export const l3Rows = (journey) =>
  journey === "vault" ? vaultRows : readRows(journey).filter((r) => r.level === "L3");

export function getPath(obj, path) {
  return path.split(".").reduce((o, k) => (o != null && typeof o === "object" ? o[k] : undefined), obj);
}

const AREAS = "lifecycle|signing|keychain|ui|permission|transport|session|pairing|labs|vault";

export function check(diag, events, detectedBy) {
  const out = [];
  for (const m of detectedBy.matchAll(/\bdiag\.([A-Za-z][\w.]*[\w])(?:\s*(==?)\s*([\w-]+))?/g)) {
    const v = getPath(diag, m[1]);
    const shown = JSON.stringify(v);
    if (v === undefined) out.push(["fail", `diag.${m[1]} is missing`]);
    else if (m[2] === "==" && String(v) !== m[3]) out.push(["fail", `diag.${m[1]} = ${shown}, expected ${m[3]}`]);
    else out.push(["pass", `diag.${m[1]} = ${shown}`]);
  }
  for (const m of new Set([...detectedBy.matchAll(new RegExp(`\\b((?:${AREAS})/[a-z][\\w.-]*)`, "g"))].map((x) => x[1]))) {
    if (!events) { out.push(["note", `event ${m}: not checked (no diag-events.jsonl)`]); continue; }
    const [area, ev] = m.split("/");
    const hit = events.some((e) => e.area === area && e.event === ev);
    out.push([hit ? "pass" : "fail", `event ${m}${hit ? " seen" : " not seen"}`]);
  }
  return out;
}

const readEvents = (f) => (f && f !== "-" && existsSync(f)
  ? readFileSync(f, "utf8").split("\n").filter(Boolean).flatMap((l) => { try { return [JSON.parse(l)]; } catch { return []; } })
  : null);

const [cmd, ...a] = process.argv.slice(2);
if (cmd === "rows") for (const r of l3Rows(a[0])) console.log(JSON.stringify(r));
else if (cmd === "check") {
  let diag = {};
  try { diag = JSON.parse(readFileSync(a[0], "utf8")); } catch { console.log("fail\t--diag output is not JSON"); process.exit(0); }
  for (const [s, t] of check(diag, readEvents(a[1]), a[2] || "")) console.log(`${s}\t${t}`);
} else if (cmd === "report") {
  const [out, journey, tsv] = a;
  const res = readFileSync(tsv, "utf8").split("\n").filter(Boolean).map((l) => l.split("\t"));
  const count = (s) => res.filter((r) => r[1] === s).length;
  const md = [`# L3 run: ${journey}, ${new Date().toISOString().slice(0, 10)}`, "",
    `pass ${count("pass")}, fail ${count("fail")}, note ${count("note")} (${res.length} rows). Real macOS, user motetest.`, "",
    "| ID | Result | Detail |", "|---|---|---|",
    ...res.map(([id, s, ...d]) => `| ${id} | ${s} | ${d.join(" ").replace(/\|/g, "\\|")} |`), ""].join("\n");
  writeFileSync(out, md);
  process.exit(count("fail") ? 1 : 0);
}
