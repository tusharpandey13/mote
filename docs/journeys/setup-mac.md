# setup-mac: A. Mac setup

Artboard: `design/mote-v1/journey-setup-mac.html` (Shipped). Code (next-0.6): `site/install.sh`, `helper/Sources/MoteHelper/main.swift`, `SetupWindow.swift`, `Permissions.swift`, `MenuBar.swift`, `Hub.swift`, `Vault.swift`, `InstantPairing.swift`, `Config.swift`, `helper/Info.plist`, `android/.../ui/HostsUi.kt` (HostRowCap). Docs: `docs/design/design-language.md` (User journeys, coverage rows "Helper: install.sh…", "Helper: Accessibility…", "Helper: Local network…"), `docs/pairing.md` §8, `AGENTS.md`.
Test: `e2e/journeys` → `dev.mote.remote.journeys.SetupMacJourney` (one @Test per path below). Only the helper stand-in steps run on the journey harness; the installer is exercised by `e2e/web/tests/install.spec.ts` (Playwright, local fixture + stubs), so its steps are `static:` here.

## Preconditions
- P1. App: not involved, except branch `setup-mac.ax-denied` (debug `dev.mote.remote.dev`, trusted + selected helper Mac).
- P2. Phone: n/a (main path); `ax-denied`: emulator on "AndroidWifi".
- P3. Mac / helper: no `~/Applications/MoteHelper.app` in the temp `HOME`, no helper process. Helper stand-in for the launch: `MoteHelper --dry-run --headless --memory-store --no-ble --no-bonjour --stdin-control --port N` (instant pairing on by default). Never `open` the real app.
- P4. Network: installer: loopback fixture `MOTE_RELEASE_URL=http://127.0.0.1:<p>`; helper: loopback.

## Main path `setup-mac`
| # | Actor | Action (exact) | Expected observable (exact) | Seam |
|---|---|---|---|---|
| S1 | Mac user | Copy `curl -fsSL https://tusharpandey13.github.io/mote/install.sh \| bash` from the site | site shows that line + "Copy"/"Copied" | manual: site page copy button (artboard jA-s1/s2); same string as `INSTALL_CMD` (`ConnectUi.kt:56`) |
| S2 | Mac user | Run `bash site/install.sh` with `HOME=<tmp>`, stub PATH, `MOTE_RELEASE_URL=<fixture>` | stdout, in order: `==> This will:` / `    download  <fixture>/MoteHelper-macos-arm64.zip` / `    verify    its SHA-256 against MoteHelper-macos-arm64.zip.sha256 from the same release` / `    install   <tmp>/Applications/MoteHelper.app` / `    clear the quarantine flag on that app only, then open its Setup window` / `==> Downloading MoteHelper-macos-arm64.zip` / `==> Verifying SHA-256` / `    ok <sha256>` / `==> Unpacking` / `==> Installing to <tmp>/Applications/MoteHelper.app` / `==> Opening Mote Helper Setup` | static: asserted by e2e/web/tests/install.spec.ts (fixture + stubs), not by this harness |
| S3 | system | (script ends) | exit 0; stdout ends with "Done. Follow the Setup window (allow Accessibility; Bluetooth is recommended), then open Mote on" / "your phone. Reopen it any time from the menu-bar icon: Setup…" / `Uninstall: quit it from the menu, then  rm -rf "<tmp>/Applications/MoteHelper.app"`; stub log `open <tmp>/Applications/MoteHelper.app --args --setup`; no `tccutil` call on a fresh install (no old signature) | static: install.spec.ts |
| S4 | system | Helper launches | stand-in stderr: `[mote] instant pairing: on (key <8 hex>)`, `[mote] Mote Helper starting (dry run) on <LocalHostName>.local:N`, `[mote] hostId <16 hex>, tailscale connections refused`, `[mote] wifi: listening on :N`; stdout `LABS PAIR INFO: {…"n":"<computer name>"…}`; no `PAIRING CODE:` line | faked: dry-run headless helper stands in for the installed app |
| S5 | system | Real app, `--setup` (or first launch of setup version 1, or Accessibility missing) | no TCC prompt at launch; window "Mote Helper Setup": "Set up Mote Helper", headline "Mote needs Accessibility to control this Mac. Everything else is optional."; rows "Accessibility" REQUIRED "Lets your phone move the pointer, click and type." [Grant]; "Bluetooth" RECOMMENDED "Finds your phone nearby for one-tap pairing, and works without Wi-Fi." [Grant]; "Local Network" RECOMMENDED (macOS 15+) "Lets your phone reach this Mac over Wi-Fi." [Open Settings]; "Screen Recording" OPTIONAL "Space thumbnails on the phone, and Labs › Live screen." [Enable]; "Automation" OPTIONAL "Labs › Context: reads your browser's active tab for smarter controls." [Enable]; toggle "Open Mote Helper at login"; button "Later" | needs user: window (headless: never shown; `main.swift` skips it under `--dry-run`) |
| S6 | Mac user | Accessibility › "Grant" | macOS Accessibility prompt + System Settings `Privacy_Accessibility` | needs user: TCC |
| S7 | Mac user | Turn MoteHelper on in System Settings | ≤ 1 s: row shows "Allowed"; headline "You're set. Open Mote on your phone and tap this Mac."; button "Done"; menu "Mote Helper: Ready" (AX poll 2 s) | needs user: TCC |
| S8 | Mac user | Bluetooth › "Grant" | `startBLE()` → macOS Bluetooth prompt (`NSBluetoothAlwaysUsageDescription` = "Mote Helper lets your phone's browser connect over Bluetooth to control this Mac.") | needs user: TCC (tests run `--no-ble`) |
| S9 | system | First real launch with instant pairing on | `SMAppService.mainApp.register()`, log `instant pairing: launch at login on` (`InstantPairing.swift:82-86`); Setup toggle "Open Mote Helper at login" on | needs user: skipped under `--dry-run` |
| S10 | Mac user | Open the menu-bar icon | items in order (`MenuBar.swift`): "Mote Helper: Ready" (dry run: "Mote Helper: Ready (dry run: logging only)"); ["Pairing code: NNN NNN" / "Show pairing code and QR…" / "Stop pairing" only while open]; "No phone connected" / "Connected:"; "Devices (N)" ▸ (Trusted phones, Block/Unblock, "Revoke all devices…"); "Pause input" ⌘P; "Setup…" ⌘,; "Advanced" ▸ (Wi-Fi remote URLs, "Wi-Fi: …", "Bluetooth: …", "Allow Tailscale connections", "Share Space thumbnails", "Require approval for new devices", "This Mac's key: …", "Pair with code (web remote)…", "Open at login"); "Labs" ▸; "Quit Mote Helper" ⌘Q | needs user: menu only (headless-unobservable) |

## Postconditions
- App installed in `<HOME>/Applications`, quarantine removed; helper running with instant pairing on, no pairing window; `setupShown=1` in UserDefaults (real app only).

## Branches
### `setup-mac.checksum-mismatch`: checksum mismatch (from S2; artboard jA-b1s)
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | Mac user | run install.sh, fixture `.sha256` wrong | stderr `error: checksum mismatch (expected <exp>, got <act>). Not installing.`; exit 1; nothing installed, no `open` | static: install.spec.ts |

### `setup-mac.no-sha-file`: .sha256 missing (from S2; not drawn)
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | Mac user | run install.sh, fixture 404s the `.sha256` | curl's own error, exit ≠ 0, nothing installed | static: install.spec.ts |

### `setup-mac.bad-archive`: archive without the app (from S2; artboard jA-b1s)
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | Mac user | run install.sh, zip without `MoteHelper.app/` | stderr `error: archive doesn't contain MoteHelper.app`; exit 1 | static: install.spec.ts |

### `setup-mac.not-mac`: not macOS (from S2; artboard jA-b1s)
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | Mac user | run with stub `uname -s` → `Linux` | stderr `error: Mote Helper is a macOS app. On other systems use the Android app or the web remote.`; exit 1, no request | static: install.spec.ts |

### `setup-mac.bad-cpu`: unsupported CPU (from S2; artboard jA-b1s)
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | Mac user | run with stub `uname -m` → `ppc` | stderr `error: unsupported CPU: ppc`; exit 1 | static: install.spec.ts |

### `setup-mac.missing-tool`: curl / shasum / ditto missing (from S2; not drawn)
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | Mac user | PATH without `shasum` (resp. `curl`, `ditto`) | stderr `error: shasum is required` (resp. `curl is required`, `ditto is required`) | static: site/install.sh:27-29 (not in install.spec.ts) |

### `setup-mac.x86`: Intel Mac (from S2; not drawn)
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | Mac user | run with stub `uname -m` → `x86_64` | `    download  <fixture>/MoteHelper-macos-x86_64.zip`; installs | static: install.spec.ts |

### `setup-mac.ax-denied`: Accessibility denied or skipped (from S6; artboard jA-b2s, jA-b2s2)
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | Mac user | "Later" in Setup without granting | menu "Mote Helper: Needs Accessibility" + "Finish setup…"; Setup reopens at next launch (`shouldAutoOpen`) | needs user: window/menu |
| B2 | system | phone connects | `{"t":"state","paused":false,"ax":false}`; Devices row "Mote Helper needs Accessibility on the Mac"; pill LED amber | faked: needs new flag `--fake-ax-denied` (dry-run forces `inputReady` true) |
| B3 | phone user | tap a remote key | `{"t":"err","code":"ax","msg":"Accessibility permission not granted to Mote Helper",…}`; no `DRY` line | faked: needs `--fake-ax-denied` |
| B4 | Mac user | Menu › "Finish setup…" → Grant → turn on | ≤ 2 s `{"t":"state","paused":false,"ax":true}`; row "Connected · Wi-Fi" | faked: needs stdin `ax-on` |

### `setup-mac.screen-recording`: first use of Space thumbnails or Live screen (from S5; artboard jA-b3s)
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | Mac user | Setup › Screen Recording › "Enable" (or Advanced › "Share Space thumbnails") | thumbnails turned on, macOS Screen Recording prompt in context; row "Relaunch helper" button while still not granted | needs user: TCC (dry-run: "     off in a dry run") |
| B2 | phone | Live screen denied | "Allow Screen Recording for Mote Helper on the Mac (System Settings › Privacy & Security)." (`ScreenProto.kt:64`) | needs user (journey live-screen covers the phone side) |

### `setup-mac.automation`: first use of Smart remote (Labs) (from S5; artboard jA-b4s)
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | Mac user | Setup › Automation › "Enable" | Labs Context on; per-browser Automation prompt, purpose `NSAppleEventsUsageDescription` = "Mote Helper reads the active tab of your browser so the Mote remote can show the right controls (Labs, off by default)." | needs user: TCC |

## Disagreements → DEFECTS.md
- `setup-mac-D1`: FIXED in next-0.6 (install.sh no longer promises a pairing-code window; it opens the Setup window).
- `setup-mac-D2` (S10): artboard menu tour ("Pair new device… ⌘N", Wi-Fi/Tailscale/thumbnail lines at top level, "Launch at login", "Revoke all devices…" top level) ≠ next-0.6 menu ("Devices (N)" ▸, "Setup…", "Advanced" ▸ with "Open at login").
- `setup-mac-D3` (S9): Launch at login is still turned on automatically at the first real launch; artboard treats it as a user choice.
- `setup-mac-D4` (S5/ax-denied): artboard shows a macOS Accessibility prompt at launch and menu "Needs Accessibility permission" / "Grant Accessibility…"; code shows the Setup window (no prompt at launch) and "Mote Helper: Needs Accessibility" / "Finish setup…". The Setup window is not drawn at all.
- `setup-mac-D5` (screen-recording B2): Live screen copy mismatch (unchanged).
- `setup-mac-D6` (S2): artboard installer output is the pre-0.6 one ("then open it", "Opening Mote Helper", numbered next steps, no Unpacking).

## UNDEFINED
- S5-S8: macOS prompt copy and whether Local Network is listed (macOS 15+ only).
- S1: site copy button not in the assigned code.
