// Shared reader for the core journey specs (docs/journeys/README.md, "Core journeys"): the step tables whose header
// starts with `| ID | Actor |`. Used by e2e/journeys (L2), tools/l3 (L3) and tools/l4 (L4 checklist).
//   import { coreJourneys, readRows } from "../journeys/rows.mjs"
//   node tools/journeys/rows.mjs [--level L4]     prints the rows as JSON lines
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";

export const ROOT = join(dirname(fileURLToPath(import.meta.url)), "../..");
export const coreJourneys = ["install-helper", "setup-mac", "setup-phone", "first-connect", "reconnect", "daily", "mac-update"];

const cells = (line) => line.trim().replace(/^\||\|$/g, "").split(/(?<!\\)\|/).map((c) => c.trim().replace(/\\\|/g, "|"));
const key = (h) => h.toLowerCase().replace(/[^a-z]+(.)/g, (_, c) => c.toUpperCase()).replace(/[^a-zA-Z]/g, "");

/** Rows of one spec: { journey, section, id, actor, step, expected, failureMode, detectedBy, userSees, recovery, level, test, status, tests[] }. */
export function readRows(journey, root = ROOT) {
  const lines = readFileSync(join(root, "docs/journeys", `${journey}.md`), "utf8").split("\n");
  const rows = [];
  let section = "", header = null;
  for (const line of lines) {
    if (line.startsWith("## ")) { section = line.slice(3).trim(); header = null; continue; }
    if (!line.startsWith("|")) { header = null; continue; }
    const c = cells(line);
    if (c[0] === "ID" && c[1] === "Actor") { header = c.map(key); continue; }
    if (!header || /^-+$/.test(c[0])) continue;
    const r = { journey, section };
    header.forEach((h, i) => { r[h] = c[i] ?? ""; });
    r.tests = (r.test || "").split(/;\s*/).map((t) => t.trim()).filter(Boolean);
    rows.push(r);
  }
  return rows;
}

export const allRows = (root = ROOT) => coreJourneys.flatMap((j) => readRows(j, root));

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const i = process.argv.indexOf("--level"), level = i > 0 ? process.argv[i + 1] : null;
  for (const r of allRows()) if (!level || r.level === level) console.log(JSON.stringify(r));
}
