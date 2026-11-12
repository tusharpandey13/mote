# Journeys

User journeys of Mote as testable specs (plan: [docs/plans/journey-testing.md](../plans/journey-testing.md), phase 2). Behaviour is derived from the code at next-0.7 (`android/`, `helper/`, `site/install.sh`); where code and an older draft or doc disagree, the row says so and the file lists a defect (D-…). Unknowns are UNDEFINED and collected as owner questions in [UNDEFINED.md](UNDEFINED.md).

## Index
| # | Journey | File | Rows | Supersedes (phase-1 drafts, kept until phase 4) |
|---|---|---|---|---|
| J1 | Install the helper | [install-helper.md](install-helper.md) | 18 | (new) |
| J2 | Mac first-run setup | [setup-mac.md](setup-mac.md) | 26 | setup-mac (rewritten) |
| J3 | Phone first-run setup | [setup-phone.md](setup-phone.md) | 20 | setup-phone (rewritten), first-run-bt (entry points) |
| J4 | First connect | [first-connect.md](first-connect.md) | 20 | connect-next, connect-now, instant-pairing, wifi-helper |
| J5 | Reconnect | [reconnect.md](reconnect.md) | 20 | reconnect (rewritten), bt-quirks, recovery (entry points) |
| J6 | Daily use (incl. media notification, B3, B4) | [daily.md](daily.md) | 22 | daily (rewritten), media, macros, spaces, settings (entry points) |
| J7 | Update helper and app | [mac-update.md](mac-update.md) | 16 | mac-update (rewritten) |
| - | Permission statecharts | [permissions.md](permissions.md) | - | - |
| - | Owner questions | [UNDEFINED.md](UNDEFINED.md) | 22 | - |

Second pass (kept as-is, phase-1 format): Labs `live-screen.md`, `smart-remote.md`, `video-bridge.md`, `open-tv.md`; `tailscale.md`. Drafts listed under "Supersedes" are not phase-2 specs; their per-control branches move into the specs in phase 3.

## Format
Each journey file: title, sources, `## UNDEFINED` (ids into UNDEFINED.md), Goal, Preconditions matrix (install fresh/upgrade; each permission granted/denied/unknown/stale; Wi-Fi/Bluetooth/Tailscale; multicast; signing identity), Success criteria, one step table, Defects.

`| ID | Actor | Step | Expected | Failure mode | Detected by | User sees | Recovery | Level | Test | Status |`

- ID: `INS`, `SM`, `SP`, `FC`, `RC`, `DU`, `UP` + number. Stable; tests reference it.
- Actor: User, App (Android app), Android, Helper (incl. install.sh), macOS.
- Detected by: event `area/name{fields}` (helper catalog in `docs/protocol.md`, app `Events.log`) or `diag.<path>` field.
- Level: L0 unit; L1 OS adapter against recorded fixtures; L2 `--dry-run` helper + emulator / e2e harness; L3 real macOS in the clean user `motetest` (owner clicks dialogs); L4 real phone, Bluetooth and home network.
- Test: `android:Class#method`, `helper:Class#method` (Swift), `node:path`, `e2e:file "title"`, `TODO-<row id>` (to write), or `MANUAL-n` (L3/L4 checklist item; the row is its definition).
- Status: `pass` (a cited test asserts Expected; not re-run for this doc, no CI runs on next-0.7 yet), `fail` (code does not meet Expected, incl. known bugs; a cited test may pin today's behaviour), `untested`, `UNDEFINED` (Expected needs an owner answer; none left, all 22 questions decided 2026-11-10, see [UNDEFINED.md](UNDEFINED.md)).

Known bugs: B1 SM-14, B2 SM-11, B3 DU-13, B4 DU-14.

## Coverage
| Journey | L0 | L1 | L2 | L3 | L4 | pass | fail | untested | UNDEFINED |
|---|---|---|---|---|---|---|---|---|---|
| J1 install-helper | 1 | 1 | 9 | 6 | 1 | 14 | 0 | 4 | 0 |
| J2 setup-mac | 11 | 2 | 3 | 10 | 0 | 11 | 2 | 13 | 0 |
| J3 setup-phone | 4 | 0 | 13 | 0 | 3 | 16 | 1 | 3 | 0 |
| J4 first-connect | 0 | 0 | 16 | 2 | 2 | 17 | 1 | 2 | 0 |
| J5 reconnect | 6 | 0 | 12 | 1 | 1 | 16 | 3 | 1 | 0 |
| J6 daily | 3 | 0 | 15 | 2 | 2 | 18 | 1 | 3 | 0 |
| J7 mac-update | 1 | 1 | 6 | 5 | 3 | 8 | 1 | 7 | 0 |
| Total (142) | 26 | 4 | 74 | 26 | 12 | 100 | 9 | 33 | 0 |

Gaps: L1 is nearly empty (no recorded TCC/Keychain/NetService fixtures); Mac-side failure paths (Accessibility denied, Keychain unreadable) are L3-only because `--dry-run` forces `inputReady` and there is no fake for either (U16, decided: `--fake-ax-denied`, dry-run only, not yet built).

## Cross-cutting defects (top)
1. Keychain partition change (every Mote Helper Local build; any identity switch) makes `secrets.v3` unreadable with no prompt and no UI; the helper mints a new identity each launch, trusted and blocked lists are lost; `docs/signing.md` promises one prompt (D-UP-1, D-RC-1, D-FC-3, D-FC-4, D-SM-1).
2. (fixed, U9) install.sh now resets Accessibility, ScreenCapture, BluetoothAlways and AppleEvents on a DR change (D-INS-1).
3. POST_NOTIFICATIONS is never asked on the helper path, and the phase-1b media notification vanishes 30 s after backgrounding (D-SP-3, D-DU-3).
4. B1/B2: Screen Recording and Local Network rows rest on weak signals (preflight, Bonjour inference); root causes unverified (SM-11, SM-14).
5. Test seams: no fake for Accessibility denied or Keychain error; phase-1 drafts cite `--fake-ax-denied` and `--store-file`, which do not exist (D-SM-2).
6. `docs/protocol.md` §4 says Allow Tailscale defaults off; code defaulted on (D-FC-5; fixed, U11: off).
7. `e2e/journeys/report.mjs` parses `| S<n> |` rows and takes the last column as Seam; phase-2 rows use `<PREFIX>-<n>` and end with Status, and `permissions.md` / `UNDEFINED.md` are not excluded. Phase 4 must adapt it.

Per-file defects: J1 4, J2 7, J3 5, J4 5, J5 6, J6 4, J7 4 (35, incl. carried phase-1 draft defects).
