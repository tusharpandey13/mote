# setup-mac: A. Mac setup

Artboard: `design/mote-v1/journey-setup-mac.html` (Shipped). Code: `site/install.sh`, `helper/Sources/MoteHelper/main.swift`, `MenuBar.swift`, `Hub.swift`, `Config.swift`, `helper/Info.plist`, `android/.../ui/HostsUi.kt` (HostRowCap). Docs: `docs/design/design-language.md` (User journeys, coverage rows "Helper: install.sh…", "Helper: Accessibility…", "Helper: Local network…"), `docs/pairing.md` §8, `AGENTS.md`.
Test: `e2e/journeys` → `dev.mote.remote.journeys.SetupMacJourney` (one @Test per path below). The installer half reuses the `e2e/web/tests/install.spec.ts` fixture (local HTTP release server, temp `HOME`, stubs for `uname`/`pgrep`/`pkill`/`open`).

## Preconditions
- P1. App: not involved in the main path; branch `setup-mac.ax-denied` needs the debug app `dev.mote.remote.dev` with a trusted, selected helper Mac (Wi-Fi).
- P2. Phone: n/a (main path); branch `ax-denied`: emulator on "AndroidWifi" (10.0.2.17/24).
- P3. Mac / helper: no `~/Applications/MoteHelper.app` in the temp `HOME`; no helper process (stub `pgrep` exits 1). Helper stand-in for the launch: `MoteHelper --dry-run --headless --no-ble --memory-store --port N` (instant pairing on by default). Never `open` the real app.
- P4. Network: installer: loopback fixture `MOTE_RELEASE_URL=http://127.0.0.1:<p>` serving `MoteHelper-macos-<arch>.zip` (containing `MoteHelper.app/`) + `.sha256`. Helper: loopback.

## Main path `setup-mac`
| # | Actor | Action (exact) | Expected observable (exact) | Seam |
|---|---|---|---|---|
| S1 | Mac user | Open the site, copy `curl -fsSL https://tusharpandey13.github.io/mote/install.sh \| bash` | site shows that line + "Copy"/"Copied" | manual: site/index copy not in this journey's code scope (artboard jA-s1/s2); `INSTALL_CMD` in `ConnectUi.kt:56` is the same string |
| S2 | Mac user | Run `bash site/install.sh` with `HOME=<tmp>`, `PATH=<stubs>:/usr/bin:/bin:/usr/sbin:/sbin`, `MOTE_RELEASE_URL=<fixture>` | stdout, in order: `==> This will:` / `    download  <fixture>/MoteHelper-macos-arm64.zip` / `    verify    its SHA-256 against MoteHelper-macos-arm64.zip.sha256 from the same release` / `    install   <tmp>/Applications/MoteHelper.app` / `    clear the quarantine flag on that app only, then open it` / `==> Downloading MoteHelper-macos-arm64.zip` / `==> Verifying SHA-256` / `    ok <sha256>` / `==> Unpacking` / `==> Installing to <tmp>/Applications/MoteHelper.app` / `==> Opening Mote Helper` (`==>` wrapped in `\033[1m…\033[0m`) | faked: fixture server + stubs (install.spec.ts) |
| S3 | system | (script ends) | exit 0; stdout ends with the "Done. Next steps:" block (steps 1-4 verbatim from `install.sh:80-90`) and `Uninstall: quit it from the menu, then  rm -rf "<tmp>/Applications/MoteHelper.app"` (two spaces before `rm`); stub log contains `open <tmp>/Applications/MoteHelper.app`; `<tmp>/Applications/MoteHelper.app` exists; stub log has `pgrep -x MoteHelper` and no `pkill` | faked: stubs |
| S4 | system | Helper launches (`open`) | real app, non-headless: `AXIsProcessTrustedWithOptions(prompt)` shows the macOS Accessibility prompt (`main.swift:25-28`) | needs user: TCC prompt. Stand-in: start `MoteHelper --dry-run --headless --no-ble --memory-store --port N`; stderr has `[mote] instant pairing: on (key <8 hex>)` then `[mote] Mote Helper starting (dry run) on <LocalHostName>.local:N`, `[mote] hostId <16 hex>, tailscale connections refused`, `[mote] wifi: listening on :N`; stdout `LABS PAIR INFO: {…}`; NO `PAIRING CODE:` line (pairing window only when `!instantPairing.enabled`, `main.swift:53`) |
| S5 | Mac user | System Settings › Privacy & Security › Accessibility › turn on MoteHelper | menu status becomes "Mote Helper: Ready" within ≤ 2 s (AX poll, `Hub.swift:52-56`) | needs user: TCC toggle |
| S6 | system | Listener starts / BLE starts advertising | Local Network prompt (Info.plist `NSLocalNetworkUsageDescription` = "Mote Helper serves the Mote web remote to your phone on this network."); Bluetooth prompt (`NSBluetoothAlwaysUsageDescription` = "Mote Helper lets your phone's browser connect over Bluetooth to control this Mac.") | needs user: TCC prompts (tests: `--no-ble`; Bonjour still advertised, see UNDEFINED) |
| S7 | system | First real launch with instant pairing on | `SMAppService.mainApp.register()`; log `instant pairing: launch at login on` (`InstantPairing.swift:82-86`) | needs user: skipped under `--dry-run` |
| S8 | Mac user | Open the menu-bar icon | items in order (`MenuBar.swift:68-163`): "Mote Helper: Ready" (dry run: "Mote Helper: Ready (dry run: logging only)"); [only while pairing is open: "Pairing code: NNN NNN", "Show pairing code and QR…", "Stop pairing"]; "Wi-Fi remote: http://<LocalHostName>.local:8790"; "     or http://<LAN IP>:8790"; "Allow Tailscale connections" (✓ by default); "Tailscale: http://<ts>:8790" or "Tailscale: not detected (is it running?)"; "Space thumbnails: grant…" / "allowed" / "need macOS 14" / "off (dry run)"; "Share Space thumbnails" ✓; "Clear Space thumbnails"; "Wi-Fi: listening on :8790"; "Bluetooth: <BLE status>"; "No connected devices" / "Connected devices:"; "Paired devices: N"; "Trusted phones: N" ▸; "This Mac's key: <grouped fp>"; "Require approval for new devices"; "Labs" ▸; "Advanced" ▸ ("Pair with code (web remote)…"); "Pause input" ⌘P; "Revoke all devices…"; "Launch at login" (✓ after S7); "Quit Mote Helper" ⌘Q | needs user: menu only (headless-unobservable) |

## Postconditions
- `<HOME>/Applications/MoteHelper.app` installed, quarantine xattr removed, temp dir deleted (trap).
- Helper running, instant pairing on, no pairing window, Launch at login registered (real app only), hostId persisted in UserDefaults (not with `--memory-store`).

## Branches
### `setup-mac.checksum-mismatch`: checksum mismatch (from S2; artboard jA-b1s)
Preconditions delta: fixture `.sha256` holds a wrong hash.
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | Mac user | run install.sh | stderr `error: checksum mismatch (expected <exp>, got <act>). Not installing.` (`error:` in red); exit 1; no `<tmp>/Applications/MoteHelper.app`; stub log has no `open ` | faked: fixture |
Postconditions: nothing installed; temp dir removed.

### `setup-mac.no-sha-file`: .sha256 missing (from S2; not drawn)
| B1 | Mac user | run install.sh, fixture 404s the `.sha256` | curl's own error on stderr (no Mote copy), exit ≠ 0 (`set -e`), nothing installed | faked: fixture |

### `setup-mac.bad-archive`: archive without the app (from S2; artboard jA-b1s "bad archive")
| B1 | Mac user | run install.sh, zip without `MoteHelper.app/` (valid sha) | stderr `error: archive doesn't contain MoteHelper.app`; exit 1; nothing installed | faked: fixture |

### `setup-mac.not-mac`: not macOS (from S2; artboard jA-b1s)
| B1 | Mac user | run with stub `uname -s` → `Linux` (`FAKE_UNAME_S=Linux`) | stderr `error: Mote Helper is a macOS app. On other systems use the Android app or the web remote.`; exit 1; fixture saw no request | faked: uname stub |

### `setup-mac.bad-cpu`: unsupported CPU (from S2; artboard jA-b1s)
| B1 | Mac user | run with `FAKE_UNAME_M=ppc` | stderr `error: unsupported CPU: ppc`; exit 1; no request | faked: uname stub |

### `setup-mac.missing-tool`: curl / shasum / ditto missing (from S2; not drawn)
| B1 | Mac user | run with a PATH lacking `shasum` (resp. `curl`, `ditto`) | stderr `error: shasum is required` (resp. `curl is required`, `ditto is required`); exit 1 | faked: PATH without the tool |

### `setup-mac.x86`: Intel Mac (from S2; not drawn)
| B1 | Mac user | run with `FAKE_UNAME_M=x86_64` | `    download  <fixture>/MoteHelper-macos-x86_64.zip`; installs | faked: uname stub |

### `setup-mac.ax-denied`: Accessibility denied or skipped (from S5; artboard jA-b2s, jA-b2s2)
Preconditions delta: helper stand-in with Accessibility not granted (`Hub.inputReady == false`); phone trusted + selected on it.
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | Mac user | Click Deny on the Accessibility prompt | menu: "Mote Helper: Needs Accessibility permission", then "Grant Accessibility…"; icon `exclamationmark.triangle` | needs user (menu); faked: needs new flag `--fake-ax-denied` (dry-run with `inputReady=false`; today `inputReady = axTrusted \|\| dryRun` makes this unreachable in dry-run) |
| B2 | system | phone connects | helper sends `{"t":"state","paused":false,"ax":false}`; phone Devices sheet row (HostRowCap) "Mote Helper needs Accessibility on the Mac"; pill LED amber (`linkWait`), no test tag | faked: `--fake-ax-denied` |
| B3 | phone user | Tap any remote key | helper replies `{"t":"err","code":"ax","msg":"Accessibility permission not granted to Mote Helper",…}`; no `DRY` input line | faked: `--fake-ax-denied` |
| B4 | Mac user | Menu › Grant Accessibility… | Accessibility prompt again + System Settings opens `Privacy_Accessibility` (`MenuBar.swift:168-171`) | needs user |
| B5 | Mac user | Turn MoteHelper on | ≤ 2 s: `{"t":"state","paused":false,"ax":true}`; row "Connected · Wi-Fi"; menu "Mote Helper: Ready" | faked: needs new stdin-control command `ax-on` (flip the fake) |
Postconditions: input works; nothing persisted by the helper.

### `setup-mac.screen-recording`: first use of Space thumbnails or Live screen (from S8; artboard jA-b3s)
| B1 | Mac user | Menu › "Space thumbnails: grant…" | `hub.spaces.requestPermission()` (TCC prompt) + System Settings `Privacy_ScreenCapture` | needs user: TCC; dry-run shows "Space thumbnails: off (dry run)" instead |
| B2 | Mac user | Labs › "Live screen (preview)" on | `CGRequestScreenCaptureAccess()` when not granted (skipped in dry-run); phone viewer on denial: "Allow Screen Recording for Mote Helper on the Mac (System Settings › Privacy & Security)." (`ScreenProto.kt:64`) | needs user (TCC); journey live-screen covers the phone side |

### `setup-mac.automation`: first use of Smart remote (Labs) (from S8; artboard jA-b4s)
| B1 | system | Labs › Context reads the browser tab | macOS Automation prompt, purpose string `NSAppleEventsUsageDescription` = "Mote Helper reads the active tab of your browser so the Mote remote can show the right controls (Labs, off by default)."; hint "Safari: turn on Develop > Allow JavaScript from Apple Events (Settings > Advanced > Show features for web developers)." / "<App>: turn on View > Developer > Allow JavaScript from Apple Events." (`Labs/Context.swift:380-381`) | needs user: TCC (tests use `--fake-context FILE`) |

## Disagreements → DEFECTS.md
- `setup-mac-D1` (S3/S4): install.sh "Next steps" 3 promises a pairing window with a 6-digit code + QR; with instant pairing on (default) none opens.
- `setup-mac-D2` (S8): artboard menu has "Pair new device… ⌘N"; code has no such item (now Advanced › "Pair with code (web remote)…"), and the artboard omits Trusted phones, This Mac's key, Require approval, Advanced, Wi-Fi:/Bluetooth: lines, Clear Space thumbnails.
- `setup-mac-D3` (S7): helper turns Launch at login on by itself at first launch; artboard, install.sh and the coverage table treat it as a user checkbox.
- `setup-mac-D4` (ax-denied B1): artboard jA-b2s menu shows "Pair new device… ⌘N" under the warning; code status reads "Mote Helper: Needs Accessibility permission" and has no such item. Also the AX-denied state can't be reproduced under `--dry-run`.
- `setup-mac-D5` (screen-recording B2): artboard caption "Allow Screen Recording for Mote Helper in System Settings" ≠ code "Allow Screen Recording for Mote Helper on the Mac (System Settings › Privacy & Security)."
- `setup-mac-D6` (S2): artboard jA-s3 output omits the `==> Unpacking` line and shows the uninstall path as `rm -rf "~/Applications/MoteHelper.app"` (quoted tilde, would not expand if copied).

## UNDEFINED
- S4/S6: exact macOS prompt copy and ordering (Accessibility, Local Network, Bluetooth) — system UI.
- S6: whether the headless dry-run helper's Bonjour advertisement (`WebServer.swift:26`) triggers a Local Network prompt for the launching terminal; a `--no-bonjour` flag is planned.
- S1: the site's copy button behaviour is not in the assigned code.
- S8: BLE status strings ("Bluetooth: …") depend on CoreBluetooth state; not observable with `--no-ble` (shows "Bluetooth: off").
