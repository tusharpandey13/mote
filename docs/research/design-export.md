# Exporting the design canvas to GitHub

Question: how do the `design/mote-v1` artboards become browsable on GitHub, automatically, on every artboard-update commit?

## Facts that drive the answer
- 29 artboards in 7 groups (media 7, pad 2, keys 1, macros 3, connection 3, journeys-shipped 8, journeys-labs 5). Phone boards are 412x915; journey boards are 1600-2500 x 1000-1100 (wide storyboards).
- `design/mote-v1` is 1.1 MB of HTML/CSS/JS plus one 3.7 KB font. Boards are self-contained, offline, no network.
- `pages.yml` already triggers on `design/**` and copies every artboard to `https://tusharpandey.com/mote/design/<id>.html`. So all 29 boards are already hosted and live-rendered; only an index is missing.
- `readme-shots.yml` already has the whole pattern: path-triggered, Playwright + pngquant, `[skip ci]` bot commit, `workflow_dispatch` of pages.yml. `shots.mjs` already uses `reducedMotion: 'reduce'`, which `flicker.js` and `mote.css` both honour, so renders are deterministic (fonts awaited via `document.fonts.ready`).
- README shots today: 744 KB for ~10 PNGs, so a quantized 600 px phone shot is ~50-90 KB.

## Options
| # | Option | Browsable on github.com | Size / churn | Maintenance | Verdict |
|---|---|---|---|---|---|
| A | PNG of every board in `design/exports/` + generated `README.md` gallery | Yes, inline, no click-out | See estimate below; binary churn only when a board changes | One script (~80 lines) reusing shots.mjs plumbing | Do (small form) |
| B | Pages gallery `/mote/design/` (generated `index.html`, grouped, iframes/thumbs, deep links `#media-light`) | Linked from README; not in repo view | Zero git bytes (text index only) | ~60-line generator; boards already deployed | Do (primary) |
| C | SVG export | Renders in repo view | Poor: boards are HTML with CSS gradients, filters, noise, `@font-face`; SVG `foreignObject` is not rendered by GitHub's sanitiser/img path, and a true vector conversion drops fidelity | High, fragile | Reject |
| D | PDF contact sheet per group | Downloads, no inline preview of quality | Larger than PNG, text churn unclear | Playwright `page.pdf` works but page breaks/backgrounds need tuning | Reject (maybe later as release asset) |
| E | Actions artifacts | No (login, 90 day expiry) | None in git | Trivial | Only as debug aid |
| F | Release assets per design revision | Yes, but buried | Not in git history | Needs revision tags separate from app tags (`design-r8`) | Optional, later |
| G | Git LFS / orphan `design-exports` branch | LFS: GitHub shows image fine but quota (1 GB free) and clone friction; orphan branch: README on main cannot embed it cleanly across forks, extra moving part | Solves bloat | Extra branch/quota to manage | Reject now; revisit if history exceeds ~50 MB |

Format notes: PNG for GitHub inline (WebP/AVIF do not render reliably in README markdown/`<img>` across clients; skip). Light/dark: `media-light` and `media` are already separate boards, so no theme matrix; do not add `prefers-color-scheme` variants. @2x: skip in git (use @1x width as designed; the Pages link gives the live vector-sharp version).

## Size estimate (pngquant `0-60`, @1x CSS px)
- Phone board 412x915: ~40-70 KB. 16 boards -> ~0.9 MB.
- Journey board ~2000x1000: ~120-250 KB. 13 boards -> ~2.5 MB.
- Full set ~3.5 MB (about 4 MB with 1.5x for text-heavy ones). A typical revision touches 1-6 boards -> 0.1-1 MB added to history. 20 revisions = ~10-20 MB. Acceptable for a public repo; the guard is below.
- Cap: the script fails (and the workflow does not commit) if any file exceeds 300 KB or the total exceeds 5 MB.

## Churn control (why no LFS/orphan branch)
1. Determinism: same Chromium (pinned Playwright via `e2e/web/package-lock.json`), `reducedMotion: 'reduce'`, `animations: 'disabled'` on screenshot, fonts awaited, fixed viewport = board width/height, pngquant same version (apt on ubuntu-latest; pin by running only on that runner). Never render locally for commit; CI is the sole writer of `design/exports/`.
2. Commit only on change: `git add -A design/exports; git diff --cached --quiet` (same as readme-shots). Unchanged boards produce byte-identical PNGs so git stores nothing.
3. Optional hash skip: write `design/exports/.manifest.json` mapping board id -> sha1(html + css + js + fonts it uses); re-render only boards whose hash changed. Not needed for correctness; it saves ~1 min CI and guards against pngquant nondeterminism. Defer unless diffs show spurious churn.
4. Mixed-tool caveat: local renders with a different pngquant/Chromium would differ; document "do not commit exports by hand" (AGENTS.md line).

## Recommendation
**B primary + A as a small committed gallery.** Pages gives the full, interactive, zoomable view at no git cost; the committed PNGs give the browsable-in-repo view that the user asked for. Everything else is rejected or deferred.

Fold into the existing `readme-shots.yml` rather than a second workflow: same trigger, same Playwright setup, same bot commit; one extra script and one extra `git add`. This avoids a second 1-2 min CI install and a second commit racing the first.

## Implementation plan
1. `tools/design-export/export.mjs` (new, ~100 lines), reading `design/mote-v1/design.json`:
   - For each artboard: viewport `{width, height||915}`; if `height` is absent use full-page screenshot. Wait for `document.fonts.ready`. Screenshot to `design/exports/<group>/<id>.png` (group folder = `group`, ungrouped -> `misc`). Names are the artboard `id` (stable, comments reference the same id), never the title.
   - pngquant `0-60 --speed 1` (fallback silently if missing, as shots.mjs does); enforce size caps.
   - Write `design/exports/README.md`: title, description from design.json, one `##` per group in design.json order, each board as `### <title>` + `![id](<group>/<id>.png)` + a link to the live board `https://tusharpandey.com/mote/design/<id>.html`. Journeys groups linked to `journeys-index`. Add a "generated by CI, do not edit" header with the source commit short SHA (only in README; PNGs stay byte-stable).
   - The Pages index is generated at deploy time (step 3), so no generated HTML is committed.
2. `.github/workflows/readme-shots.yml`: add `run: node tools/design-export/export.mjs` after shots.mjs; change `git add -A docs/assets/readme site/og.png` to also add `design/exports`; commit message `docs: refresh README screenshots and design exports [skip ci]`. `paths` already include `design/**`; add `tools/design-export/**`. Loop guard: the bot commit changes `design/exports/**`, which matches `design/**`, but `[skip ci]` plus GITHUB_TOKEN pushes trigger nothing, so no loop (the existing pattern already relies on this for `docs/assets/readme`). Optionally add `'!design/exports/**'` to `paths` as belt and braces (negation patterns are allowed after a positive one).
3. `pages.yml`: in "Assemble site", run `node tools/design-export/index.mjs design/mote-v1 _site/design/index.html` (no Playwright needed; node preinstalled). Output: grouped grid with live iframe thumbnails (scaled, `loading=lazy`, `pointer-events:none`), click opens `<id>.html`; `#<id>` anchors for deep links; links to the PNG on GitHub. Keep `comments.json` removed (already). Exclude `design/exports/` from the copy (`cp -R design/mote-v1/.` already only copies mote-v1, fine).
4. README: add a "Design" link row to `design/exports/README.md` and `/mote/design/`. `design/README.md`: one line that `design/exports/` is generated.
5. AGENTS.md: one bullet in "README screenshots": exports are CI-generated from design.json, do not edit or commit by hand.

Effort: ~2 files new (~200 lines), 2 workflow edits, 2 doc lines. CI cost: +~30 s per design push.

## Comments and variants
- Comments (`comments.json`, anchored by `artboardId` + `dcId`) are canvas-only review state; do not export them (Pages already strips the file). The PNG name = `artboardId`, so a comment can be quoted as `media/media-light.png` unambiguously.
- Variants = boards sharing `group`; the gallery lists them in design.json order under one group heading, which preserves the "side by side" semantics (optionally a markdown table, two columns per row, for groups of <=3 phone boards; wide journey boards stay single column).
- A new board needs no extra step: adding it to design.json is enough (same convention as README shots).

## Open questions for the user
1. Is a committed PNG gallery wanted at all, or is the Pages gallery enough (zero git bloat)? Recommendation assumes both.
2. OK to export the Labs journeys publicly? They are already deployed on Pages, but the README gallery makes them more prominent.
3. @1x only (recommended) or also @2x for phone boards (+~2.5x size)?
4. Size ceiling: is ~5 MB total / ~20 MB history after 20 revisions acceptable, or should we switch to an orphan branch now?
5. Should releases also attach a zip of `design/exports` (option F, nearly free)?
6. Folder per group (recommended) or flat `design/exports/<id>.png`?
