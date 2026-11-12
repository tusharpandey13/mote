import { test } from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, readdirSync, readFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";

const script = fileURLToPath(new URL("./checklist.mjs", import.meta.url));
const run = (...a) => spawnSync("node", [script, ...a], { encoding: "utf8" });

test("--list prints every L4 step", () => {
  const r = run("--list");
  assert.equal(r.status, 0);
  assert.ok((r.stdout.match(/^\S+\t/gm) ?? []).length >= 12);
});

test("scripted answers are recorded; early quit leaves the rest not run", () => {
  const out = mkdtempSync(join(tmpdir(), "l4-"));
  const r = run("--out", out, "--dry-run-answers", "p,f,boom,s,q");
  assert.equal(r.status, 1, "a failure gives exit 1");
  const md = readFileSync(join(out, readdirSync(out)[0]), "utf8");
  assert.match(md, /- App: \d/);
  assert.match(md, /\| Result \| Note \|/);
  assert.match(md, /\| pass \| +\|/);
  assert.match(md, /\| fail \| boom \|/);
  assert.match(md, /Totals: 1 pass, 1 fail, 1 skip, \d+ not run/);
});

test("stdin answers work when not a TTY", () => {
  const out = mkdtempSync(join(tmpdir(), "l4-"));
  const r = spawnSync("node", [script, "--out", out], { input: "p\nn\nnoted\np\n", encoding: "utf8" });
  assert.equal(r.status, 0);
  assert.match(readFileSync(join(out, readdirSync(out)[0]), "utf8"), /\| pass \| noted \|/);
});
