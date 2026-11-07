"""Rubric-scored summary (../RUBRIC.md) of an Android chaos run. usage: python3 -I summary.py OUT_DIR > summary.md

Reads results.tsv (scenario status crashes anrs notes severity invariants violated secs),
devstate.tsv (name diff_count diff), run.env, pss.env, monkey.env.
"""
import os
import sys

out = sys.argv[1]


def env(name):
    d = {}
    try:
        for line in open(os.path.join(out, name)):
            k, _, v = line.strip().partition("=")
            d[k] = v
    except FileNotFoundError:
        pass
    return d


def tsv(name, cols):
    rows = []
    try:
        for line in open(os.path.join(out, name)):
            f = line.rstrip("\n").split("\t")
            rows.append(dict(zip(cols, f + [""] * (len(cols) - len(f)))))
    except FileNotFoundError:
        pass
    return rows


R = tsv("results.tsv", ["scenario", "status", "crashes", "anrs", "notes", "severity", "invariants", "violated", "secs"])
D = tsv("devstate.tsv", ["name", "n", "diff"])
run, pss, mk = env("run.env"), env("pss.env"), env("monkey.env")
num = lambda s: int(s) if s.strip().lstrip("-").isdigit() else 0
ids = lambda s: [i for i in s.split(",") if i.startswith("I")]

INV = {
    "I1": "no input before auth", "I2": "no duplicate execution", "I3": "bounded memory/fds/queues",
    "I4": "streams stop on disconnect", "I5": "no secrets or screen frames on disk",
    "I6": "auto-trust only per trust rules", "I7": "no crash-loop on persisted state",
    "I8": "UI never silent on a failure", "I9": "device state restored", "I10": "liveness (no crash/ANR/hang)",
}
SEV = {"S1": "security / data loss / crash-loop / device state left changed", "S2": "broken feature, wrong input, crash",
       "S3": "degraded / racy, flaky timing", "S4": "cosmetic"}

p = print
p(f"# Mote Android chaos run: seed {run.get('seed', '?')}, budget {run.get('budget_s', '?')}s, {run.get('serial', '?')}"
  f"{' (PHYSICAL)' if run.get('emulator') == '0' else ''}")
p()
p("| scenario | status | sev | crashes | ANRs | secs | notes |")
p("|---|---|---|---|---|---|---|")
for r in R:
    p(f"| {r['scenario']} | {r['status']} | {r['severity'] if r['severity'] != '-' else ''} | {r['crashes']} | {r['anrs']} | {r['secs']} | {r['notes'].replace('|', '/')} |")
p()
n = {}
for r in R:
    n[r["status"]] = n.get(r["status"], 0) + 1
p(", ".join(f"{k} {n.get(k, 0)}" for k in ("PASS", "FAIL", "PENDING", "SKIP")))

p()
p("## Findings by severity")
p()
fails = [r for r in R if r["status"] == "FAIL"]
for s in ("S1", "S2", "S3", "S4"):
    hit = [r for r in fails if r["severity"] == s]
    p(f"- **{s}** ({SEV[s]}): {len(hit)}")
    for r in hit:
        p(f"  - `{r['scenario']}` [{r['violated']}]: {r['notes']}")
if not fails:
    p()
    p("No failing checks.")

p()
p("## Invariants")
p()
p("| id | invariant | result | exercised by |")
p("|---|---|---|---|")
for i, desc in INV.items():
    ran = [r for r in R if r["status"] in ("PASS", "FAIL") and i in ids(r["invariants"]) + ids(r["violated"])]
    bad = [r for r in ran if r["status"] == "FAIL" and i in ids(r["violated"])]
    who = [r["scenario"] for r in (bad or ran)]
    if i == "I9" and D and not bad:  # clean device-state checks leave no results.tsv row
        ran, who = D, [f"{len(D)} checks: " + ", ".join(d["name"] for d in D[:5])]
    res = "FAIL" if bad else ("PASS" if ran else "n/a")
    if len(who) > 6:
        who = who[:6] + [f"+{len(who) - 6} more"]
    p(f"| {i} | {desc} | {res} | {', '.join(who) if who else ('Android: helper-side, see chaos/helper' if i in ('I1', 'I5', 'I6') else '')} |")

p()
p("## Budgets")
p()
crashes = sum(num(r["crashes"]) for r in R)
anr_out = sum(num(r["anrs"]) for r in R if not r["scenario"].startswith("monkey-"))
anr_mk = sum(num(r["anrs"]) for r in R if r["scenario"].startswith("monkey-s"))
ev, att = num(mk.get("monkey_events", "0")), num(mk.get("monkey_anr_attr", "0"))
diffs = [d for d in D if num(d["n"]) > 0]
p("| budget | limit | actual | result |")
p("|---|---|---|---|")
p(f"| crashes | 0 per scenario | {crashes} | {'PASS' if crashes == 0 else 'FAIL'} |")
p(f"| ANRs outside monkey | 0 | {anr_out} | {'PASS' if anr_out == 0 else 'FAIL'} |")
if ev:
    ok = anr_mk == att and att * 50000 <= 2 * ev
    p(f"| monkey ANRs | <= 2 per 50k events, attributed | {anr_mk} in {ev} events, {att} attributed | {'PASS' if ok else 'FAIL'} |")
else:
    p("| monkey ANRs | <= 2 per 50k events, attributed | not run | n/a |")
if pss:
    g, first, last = num(pss["pss_growth_pct"]), num(pss["pss_first_kb"]), num(pss["pss_last_kb"])
    bad = g > 30 and last - first > 20000
    p(f"| PSS growth | fail at >30% and >20 MB | {g}% ({first // 1024}->{last // 1024} MB) | {'FAIL' if bad else 'PASS'} |")
else:
    p("| PSS growth | fail at >30% and >20 MB | not measured | n/a |")
p(f"| device-state diff | empty after every scenario | {len(diffs)} of {len(D)} checks differ | {'FAIL' if diffs else 'PASS'} |")
for d in diffs:
    p(f"|  | | `{d['name']}`: {d['diff'].replace('|', '/')} | |")
el, b = num(run.get("elapsed_s", "0")), num(run.get("budget_s", "0"))
p(f"| time | {b}s (monkey may overrun to reach MONKEY_MIN events) | {el}s | {'PASS' if el <= b else 'OVER'} |")

if any(f.endswith(".crash.txt") for f in os.listdir(os.path.join(out, "logs"))):
    p()
    p(f"Crash excerpts: {out}/logs/*.crash.txt")
