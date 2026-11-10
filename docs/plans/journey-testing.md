# Plan: journeys and end-to-end tests that cover the real OS

Status: approved by the owner 2026-11-10. Research: `/tmp/mote-research/cross-device-e2e.md` (copy it to
`docs/research/cross-device-e2e.md` in phase 0).

## Why

Every serious bug the owner hit lived in the system layer our tests fake: Keychain partitions, TCC grants,
multicast-blocked Wi-Fi, first install, and features that silently do nothing. Current tests (unit, `--dry-run`
helper, web e2e, chaos, emulator) never exercise that layer, and the app gives no reason when something is off.

Open bugs that this plan must turn into visible reasons and failing tests first:

| ID | Bug | Seen |
|---|---|---|
| B1 | Setup window shows Screen Recording "Grant" after granting it and relaunching | 2026-11-10 |
| B2 | Setup window shows Local Network "Open Settings" although it is allowed | 2026-11-10 |
| B3 | Labs on in Mote Dev and the helper, YouTube playing: no change in the phone UI | 2026-11-10 |
| B4 | No Android media notification while the Mac plays. Decided: build it (phase 1b), tested at every level. Now core, not Labs: foreground service while playing, plus a Bluetooth-only variant (transport buttons as HID consumer keys, no metadata); see [UNDEFINED.md](../journeys/UNDEFINED.md) U1-U3 | 2026-11-10 |
| B5 | Cause of B1 found: TCC kept 8 stale Screen Recording entries, one per past signature, and System Settings showed the switch on for a stale one. After `tccutil reset ScreenCapture dev.mote.helper`, Grant brought no prompt and no list entry until the helper relaunched (cached answer in the process). Fix: on a signature change, Setup says "re-grant: remove the old entry"; Grant after a failed request offers Relaunch. U8: CGPreflight and window titles both flipped after relaunch | 2026-11-10 |
| B6 | Phone loops labs.pair.hello -> labs.pair.denied every ~4 s (34 times) with no message to the user, after the move to the secrets.v3 Keychain item dropped its trusted key; the helper event log does not record the deny reason. Fix: phone shows "This Mac doesn't recognise this phone" with re-pair; `pairing.denied {reason}` event; carry trusted keys over on vault migration Android (fix-android-b6): card + `pairing/denied {reason}` event + 30 s..5 min backoff; `keychain-reading` honours `retryAfter`. | 2026-11-10 |
| B7 | Phone TV mode on, helper Labs › TV mode off: `labs.tv.open` fails with err `disabled` and the phone shows no reason. Fix: Blockers.kt maps it to "Turn on TV mode in Mote Helper › Labs" Android: `Blockers.helperLabDisabled`, also when the err has no `ref`. | 2026-11-10 |
| B8 | Mote Dev's Bluetooth HID phase BLOCKED while the installed Mote also exists (one HID app per phone). Copy says "another app"; name the likely cause (Mote installed too) when `dev.mote.remote` is present Android: `hidBlockedText` + `<queries>` for both packages: "Only one app can be a Bluetooth keyboard. Mote is also installed; close or uninstall it." | 2026-11-10 |

## Goals

- Every user journey is written down step by step for all five actors, with what can go wrong and what the user
  sees when it does.
- Every step links to the test that covers it, or is marked MANUAL with a checklist entry.
- When a feature doesn't work, the phone and the helper say why.

Non-goals: device farms, Bluetooth HID automation, a macOS VM now (disk is short), CI for real-OS tests.

## Constraints (from AGENTS.md and the owner)

- Android tests on a headless emulator only (`ANDROID_SERIAL=emulator-…`, `dev.mote.remote.dev`). Never the
  owner's phone or installed app.
- No macOS prompts (Keychain, TCC, dialogs) on the owner's account without asking first. Real-OS runs happen in
  the test account (phase 5) with the owner present.
- Disk budget for the whole plan: under 10 GB.
- Long runs in the background; output to files.

## Test levels

| Level | What | Where | Automated |
|---|---|---|---|
| L0 | Unit | Swift / Kotlin / node | Yes |
| L1 | OS adapters against fixtures recorded on a real Mac | helper unit tests | Yes |
| L2 | `--dry-run` helper + emulator journey harness | owner's Mac, emulator | Yes |
| L3 | Real OS: release artifact installed in a clean macOS user | test account `motetest` | Partly: the owner clicks system dialogs |
| L4 | Real phone, real Bluetooth, home network | owner's devices | Manual checklist |

## Phases

Each phase ends with its acceptance checks passing, a commit on its own branch, a merge into `next-0.7`, and a
push (haiku). One agent per phase; models per the owner's cost rules.

### Phase 0: start (haiku + sonnet, ~1 h)

- Wait for v0.6.0 to be published (`gh release view v0.6.0`). Branch `next-0.7` from `main`.
- Salvage the stopped journey branch `worktree-agent-a3632f54b84cc66cb`: keep its 6 commits (21 journey drafts,
  `e2e/journeys/` harness, `--no-bonjour`). Its uncommitted diff (28 files) is reviewed: keep journey doc edits
  and harness fixes; drop anything touching `Vault.swift` (superseded by `secrets.v3`).
- Remove stale agent worktrees whose branches are already in `main` (`git cherry` shows no `+`).
- Copy the research report into `docs/research/`.

### Phase 1: instrumentation (sonnet, ~2-3 days)

Owner: "exhaustive instrumentation before testing anything". Nothing in phases 3-6 starts until this is merged.
The helper and the app report each feature's state and, when it's blocked, why (the snapshot), and record
everything that happens (the event log).

- **Event log, both sides.** Every state change and decision is one structured event:
  `{t, seq, side: helper|app, area, event, journeyStep?, corr, fields}`. Areas: lifecycle, signing, permission,
  keychain, discovery (each sweep / BLE / Bonjour attempt and result), transport, session, protocol (every
  message type in and out, sizes, errors; no payload contents, no secrets), input (counts per kind, never keys
  typed), labs (toggles, subscriptions, every push), ui (screen shown, banner shown, button tapped).
  - `corr` ties a phone action to the helper's handling of it (sent in the message envelope).
  - `journeyStep` is the spec row ID (phase 2) when the code knows it; tests assert on these.
  - Helper: `os_log` subsystem `dev.mote.helper` plus a 5,000-event ring buffer, `--events <file>` (JSONL).
  - App: logcat tag `Mote/<area>` plus a ring buffer in memory, exportable from Settings › Diagnostics
    ("Share log").
  - Both: `diag.events` streams new events to an authed session that asks (`diag.subscribe`), so the harness
    sees the two sides as one timeline.
- A unit test fails if a public code path (message handler, permission check, connect step) has no event.
- **Snapshot.** The `diag` message below.

- **Helper:** a `diag` snapshot, available as:
  - the WebSocket message `diag` (protocol 1.5, authed sessions);
  - `MoteHelper --diag` (prints JSON and exits; no prompts);
  - the menu item "Diagnostics…" (readable view, "Copy").
- **Snapshot contents**, per feature `{state: ok|blocked|off|unknown, blockedBy: <code>, fix: <action>}`:
  - signing: identity, Team ID, cdhash, designated requirement;
  - permissions: Accessibility, Screen Recording, Local Network (inferred: Bonjour `PolicyDenied` -65570 or a
    loopback/LAN probe; `unknown` if it can't tell), Bluetooth, Automation per browser;
    for each, the raw API result and when it was last checked;
  - Keychain: the `secrets.v3` read result (status code), its partition (from our own write), item count;
  - transports: listening ports, BLE advertising, Bonjour, Tailscale;
  - sessions: each connected device, its transport and protocol version;
  - Labs: each lab's helper toggle and, per session, whether the phone subscribed.
- **Phone:** a "Why isn't this working" screen (Settings › Diagnostics) that shows the phone's own state (transport,
  Labs toggles, subscriptions) next to the helper's `diag`. Each Labs panel that can't work shows its blocker
  inline, e.g. "Needs Wi-Fi or Tailscale; connected over Bluetooth".
- **Setup window:** reads from the same snapshot. `unknown` shows as "Can't check: macOS doesn't report this",
  never as a fake "Grant".
- **Accept:** B1, B2 and B3 each show a specific `blockedBy` reason on the owner's Mac (read with `--diag`, no
  prompts) and their event trail explains how it got there. `docs/protocol.md` documents `diag`,
  `diag.subscribe` and `diag.events` as protocol 1.5 (decided: old helpers answer `err unknown`, the app shows
  "Update the helper to see diagnostics"). Unit tests for the snapshot builder and the event coverage test.

### Phase 1b: media notification (sonnet, ~1 day)

- While connected to a helper with now-playing available, the app shows an Android media notification
  (MediaSession + MediaStyle): title, artist, art, play/pause, previous/next, seek bar; actions go to the Mac.
  It disappears when nothing plays or the session ends. Over Bluetooth only (no helper), it shows transport
  buttons without metadata (UNDEFINED for phase 2: or none).
- Specified as journey rows in phase 2 and tested at L0 (state reducer), L2 (emulator: notification present,
  actions send the right messages, removed on disconnect; `dumpsys media_session` and `dumpsys notification`),
  L4 (lock screen, Bluetooth headset buttons, Android 13+ notification permission).


### Phase 2: journey specs (opus, ~1 day; owner review)

- One file per journey in `docs/journeys/`, rewritten from the 21 drafts. The first pass covers the core
  journeys:
  1. install the helper (`install.sh`);
  2. first-run setup on the Mac (Setup window, permissions);
  3. first-run setup on the phone;
  4. first connect (one tap; Wi-Fi first, Bluetooth fallback);
  5. reconnect (app relaunch, helper restart, Mac sleep, network change);
  6. daily use (Media, D-pad, touchpad, keys, macros, Spaces);
  7. update the helper or the app (identity change, re-grants, re-pair).

  Labs (5 journeys) and Tailscale come in a second pass, using the same format.
- **Header per journey:** goal, preconditions matrix (fresh / upgrade; each permission granted / denied /
  unknown / stale; Wi-Fi / Bluetooth / Tailscale; multicast blocked or not; signing identity), success
  criteria.
- **Step table**, one row per step and failure mode:

  | ID | Actor | Step | Expected | Failure mode | Detected by | User sees | Recovery | Level | Test | Status |
  |---|---|---|---|---|---|---|---|---|---|---|

  Actors: User, App, Android, Helper, macOS. `Detected by` names a `diag` field (phase 1). `Test` is a test id
  or `MANUAL-n`. `Status`: `pass`, `fail`, `untested`, `UNDEFINED`.
- `docs/journeys/permissions.md`: one statechart per permission
  (unknown → prompted → granted | denied; granted → stale after an identity change), with what the app,
  the helper and the Setup window show in each state.
- `docs/journeys/README.md`: the index, the format, and a coverage table (rows per level and status).
- Anything the code doesn't decide is `UNDEFINED`, listed at the top of each file for the owner. B4 is one.
- **Accept:** every row has a level and either a test id or MANUAL. The owner has answered each UNDEFINED
  item; answers are written into the spec.

### Phase 3: OS adapters and recorded fixtures (sonnet, ~1-2 days)

- **Protocols in the helper:** `PermissionProbe`, `SecretStore` (the Vault backend), `Discovery` (Bonjour,
  BLE, sweep), `SigningInfo`. Production uses the OS. Tests use fixtures.
- **Recording:** `MoteHelper --record-system <file>` writes the real OS's answers for every probe (no prompts:
  preflight calls only, no-UI Keychain read). The owner runs it once per macOS update; fixtures are committed in
  `helper/Tests/Fixtures/system/` (no secrets: status codes and booleans only).
- **L1 tests** replay the fixtures, including the recorded B1/B2 states, and check `diag` and the Setup window
  state.
- **Accept:** B1 and B2 have failing L1 tests before their fixes and passing after. The fixes are part of this
  phase.

### Phase 4: L2 journey harness (sonnet, ~1 day)

- Extend the salvaged `e2e/journeys/` harness: each journey file's L2 rows become runnable steps (emulator +
  `--dry-run` helper with fake system adapters set per precondition).
- Assertions read `diag` on both sides, never only UI text.
- One report: `e2e/journeys/report.md`, rows by journey, level, status, linked logs.
- **Accept:** all L2 rows of the core journeys run on a headless emulator with one command
  (`e2e/journeys/run.sh --level L2`) and the report matches the spec's coverage table.

### Phase 5: L3 real-OS runs in a clean user (sonnet + owner, ~1 day)

- `tools/l3/setup-user.sh`: creates the standard user `motetest` (`sysadminctl`, needs the owner's admin
  password once). Disk: 3-8 GB. `tools/l3/reset-user.sh` wipes its TCC grants (`tccutil reset … dev.mote.helper`
  inside that user), Keychain and app support between runs.
- `tools/l3/run.sh <journey>`: in the test user (Fast User Switching or `ssh motetest@localhost`):
  1. installs the published release artifact with `install.sh`;
  2. walks the journey's L3 rows;
  3. pauses with a plain instruction when a system dialog needs a click;
  4. checks `diag` after each step;
  5. writes the report.
  The phone side is the emulator connecting to the helper over the LAN.
- **Accept:** journeys 1, 2, 4, 5 and 7 pass at L3 on the release build. B1 is confirmed fixed (or reproduced)
  on the self-signed release artifact, not only on the Apple Development build.

### Phase 6: L4 manual checklist (sonnet, ~0.5 day)

- `tools/l4/checklist.mjs`: generated from the spec's L4 rows (BT HID pairing, home network without multicast,
  Gatekeeper on first launch, real Labs on the owner's phone). It prints one step at a time, records pass, fail
  or note, and writes `docs/journeys/l4-<date>.md`.
- Run before each release. Takes about 15 minutes.

## Definition of done

- Core journeys: 100 % of rows have a level and a test or MANUAL entry; L0-L3 rows pass; the last L4 run is
  recorded.
- B1-B3 fixed with tests; B4 built and passing L0, L2 and L4.
- AGENTS.md documents the levels, `--diag`, the harness commands and the L4 checklist step in the release list.
- Total disk added under 10 GB.

## Owner decisions still open

Decided 2026-11-10: the `motetest` account is OK; B4 is built and tested; `diag` is protocol 1.5; instrumentation
comes before any testing phase. All 22 UNDEFINED items are decided.

Decisions 2026-11-10: see [docs/journeys/UNDEFINED.md](../journeys/UNDEFINED.md) (Decision column); the spec rows
that were UNDEFINED now carry the decided Expected value and status `untested`.

In flight: three fix branches (`fix-vault`, `fix-helper-ux`, `fix-android-ux`) implement these decisions; they are
merged into `next-0.7` after review.

