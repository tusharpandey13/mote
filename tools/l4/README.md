# L4 checklist

The manual real-phone, real-Mac steps (level L4 rows of `docs/journeys/*.md`). Run it before each release; it takes about 15 minutes.

```sh
node tools/l4/checklist.mjs            # asks p pass / f fail / n note / s skip / q quit, one step at a time
node tools/l4/checklist.mjs --list     # print the steps only
node tools/l4/checklist.mjs --only RC-13,FC-5   # run some rows; today's other answers are kept
node tools/l4/checklist.mjs --out DIR  # write elsewhere (default docs/journeys/l4-<date>.md)
node --test tools/l4/checklist.test.mjs                 # tests, scripted with --dry-run-answers p,f,...
```

Exact steps per row live in `tools/l4/steps.mjs` (the specs only hold one-line summaries); every L4 row needs an entry. It writes `docs/journeys/l4-<date>.md` (date, commit, app and helper version, one result per step, totals). Quitting early marks the rest "not run". Commit the file with the release.
