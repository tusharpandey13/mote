# mac-update: A2. Mac: login, Tailscale, update and uninstall

Artboard: `design/mote-v1/journey-mac-update.html` (Shipped). Code (next-0.6): `site/install.sh`, `helper/Sources/MoteHelper/MenuBar.swift`, `SetupWindow.swift`, `Config.swift` (Settings, Tailnet), `Vault.swift`, `InstantPairing.swift` (LabsKeyStore, launch-at-login), `main.swift` (stdin `tailscale on|off`), `helper/build.sh` (stable signing), `android/.../net/WsTransport.kt`, `ui/HostsUi.kt` (HostRowCap). Docs: `docs/design/design-language.md` (coverage "Update + Accessibility re-grant…, uninstall"), `docs/pairing.md` §5, `AGENTS.md`.
Test: `e2e/journeys` → `dev.mote.remote.journeys.MacUpdateJourney` (one @Test per path below). Installer steps are exercised by `e2e/web/tests/install.spec.ts` (`static:` here).

## Preconditions
- P1. App: debug `dev.mote.remote.dev`, has trusted + selected Mac X over Wi-Fi (established in the test by a first-run connect with `TestSeam.adapter(supported = false)`, so no Bluetooth step).
- P2. Phone: emulator on "AndroidWifi", no Tailscale VPN.
- P3. Mac / helper: stand-in `MoteHelper --dry-run --headless --memory-store --no-ble --no-bonjour --stdin-control --port N --fake-proximity`. Installer: an older `MoteHelper.app` in `<tmp HOME>/Applications`, stub `pgrep -x MoteHelper` exits 0.
- P4. Network: same LAN (10.0.2.2 ↔ 10.0.2.17).

## Main path `mac-update`
| # | Actor | Action (exact) | Expected observable (exact) | Seam |
|---|---|---|---|---|
| S1 | Mac user | Advanced › "Allow Tailscale connections" (on) | helper log `[mote] tailscale connections allowed`; menu line "     Tailscale: http://<MagicDNS or 100.x>:8790" or "     Tailscale: not detected (is it running?)" | faked: stdin-control `tailscale on` stands in for the menu click (menu lines headless-unobservable) |
| S2 | Mac user | Advanced › "Open at login" / Setup toggle "Open Mote Helper at login" | ✓ iff `SMAppService.mainApp.status == .enabled` | needs user: menu / window |
| S3 | Mac user | Re-run `bash site/install.sh` (fixture serves the newer zip, helper running) | stdout `==> Downloading MoteHelper-macos-arm64.zip`, `==> Verifying SHA-256`, `    ok <sha>`, `==> Unpacking`, `==> Quitting the running Mote Helper`, `==> Installing to <tmp>/Applications/MoteHelper.app`, `==> Opening Mote Helper Setup`, Done text; stub log `pkill -x MoteHelper`, `open <APP> --args --setup` | static: install.spec.ts ("replaces an older install") |
| S4 | system | Old helper quits | phone: Devices sheet row of X "Connecting…" (WsTransport retry loop, `WsTransport.kt:140`) | faked: stand-in helper stopped by the controller (SIGTERM) |
| S5 | system | New helper starts with the same signature (build.sh stable identity) | permissions kept; Setup window opens once (`--setup`); no Keychain prompt after an earlier "Always Allow" | needs user: TCC / Keychain / window |
| S6 | system | Phone reconnects silently (trusted key, no token) | helper log `labs: trusted <phone> via trusted`; no `NEW DEVICE:`; row "Connected · Wi-Fi" | faked: needs a persistent non-Keychain test store (e.g. `--store-file PATH`): with `--memory-store` a restarted helper has a new hostId and key |
| S7 | Mac user | uncheck "Open at login", "Quit Mote Helper" (⌘Q) | `SMAppService.mainApp.unregister()`; process exits | needs user |
| S8 | Mac user | `rm -rf ~/Applications/MoteHelper.app` | app gone; installer's line `Uninstall: quit it from the menu, then  rm -rf "<abs path>/MoteHelper.app"` | manual: user shell |

## Postconditions
- After S6: phone keeps Mac X (same hostId/fp); helper trusted list keeps the phone.
- After S8: UserDefaults and the single Keychain item `dev.mote.helper` / `secrets` remain (nothing removes them); TCC entries remain.

## Branches
### `mac-update.signature-changed`: new build signed differently (from S3; artboard jA2-s3)
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | Mac user | run install.sh where old and new `codesign -d -r-` designated requirements differ | `==> The new version is signed differently: clearing Mote Helper's old permission entries` / `    (only Mote Helper's; grant Accessibility again in the Setup window)`; `tccutil reset Accessibility dev.mote.helper`, `tccutil reset ScreenCapture dev.mote.helper` | static: site/install.sh:72-89 (install.spec.ts has no signed fixture and no `tccutil` stub; never run for real) |
| B2 | Mac user | Setup window → Accessibility "Grant" → turn on | as setup-mac S6-S7 | needs user: TCC |

### `mac-update.skip-regrant`: skipped the re-grant (from S5; artboard jA2-b1s)
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | system | phone reconnects to a helper without Accessibility | `{"t":"state",…,"ax":false}`; row "Mote Helper needs Accessibility on the Mac"; pill LED amber | faked: needs new flag `--fake-ax-denied` |
| B2 | phone user | tap a key | `{"t":"err","code":"ax",…}`; no `DRY` line | faked: needs `--fake-ax-denied` |

### `mac-update.not-running`: update while the helper isn't running (from S3; not drawn)
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | Mac user | run install.sh, stub `pgrep` exit 1 | no `==> Quitting the running Mote Helper`, no `pkill` | static: install.spec.ts |

### `mac-update.update-fails`: checksum mismatch during update (from S3; not drawn)
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | Mac user | run install.sh, bad `.sha256` | `error: checksum mismatch (expected …, got …). Not installing.`; old app untouched; running helper not quit | static: install.spec.ts |

### `mac-update.keychain-denied`: Keychain read fails after an update (from S5; not drawn)
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | Mac user | Deny Keychain access | log `keychain read failed: <OSStatus> (pairings are kept in memory until the next launch)`; `LabsKeyStore.identity()` finds no identity, mints one and `vault.update` writes the whole blob (`Vault.swift:95-99,133-143`, `InstantPairing.swift:446-451`) | static: Vault.swift / InstantPairing.swift (needs a real Keychain Deny) |
| B2 | system | phone reconnects | key pin mismatch `helper-key` → `auth`; Devices "The Mac no longer trusts this phone. Long-press it to forget, then Add a Mac again." | faked: needs a stand-in with the same hostId and a new key (new flag) |

### `mac-update.tailscale-off`: Allow Tailscale off (from S1; not drawn)
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | Mac user | Advanced › uncheck "Allow Tailscale connections" | log `[mote] tailscale connections refused`; tailnet peers dropped; phone on the LAN stays "Connected · Wi-Fi" | faked: stdin-control `tailscale off` stands in for the menu click |

## Disagreements → DEFECTS.md
- `mac-update-D1` (keychain-denied): a failed Keychain read still leads to a new identity being written over the vault blob (now also wiping code pairings and the trusted list); the log line claims pairings are "kept in memory until the next launch".
- `mac-update-D2` (S7): uninstall line still doesn't mention turning off Open at login (auto-enabled).
- `mac-update-D3`: FIXED in next-0.6 for the copy (stable signing; install.sh resets stale TCC entries); artboard jA2-s2/s3 still say "ad-hoc signed… switch it off and on" and "Always Allow on the Keychain prompt".
- `mac-update-D4` (skip-regrant): artboard Devices sheet layout is the pre-0.6 one (unchanged).
- `mac-update-D5` (S1/S2): artboard jA2-s1 shows "Allow Tailscale connections" / "Launch at login" at the menu top level; code moved them to Advanced ("Open at login").

## UNDEFINED
- S5: whether macOS shows a Keychain prompt for the one `secrets` item after an update — OS behaviour.
- S4: reconnect backoff timing after the restart.
