# mac-update: A2. Mac: login, Tailscale, update and uninstall

Artboard: `design/mote-v1/journey-mac-update.html` (Shipped). Code: `site/install.sh`, `helper/Sources/MoteHelper/MenuBar.swift`, `Config.swift` (Settings, Tailnet), `InstantPairing.swift` (LabsKeyStore, launch-at-login), `Hub.swift` (AX poll), `android/.../ui/HostsUi.kt` (Devices sheet, HostRowCap). Docs: `docs/design/design-language.md` (coverage "Update + Accessibility re-grant…, uninstall"), `docs/pairing.md` §5 (reconnect), `AGENTS.md` (ad-hoc signing).
Test: `e2e/journeys` → `dev.mote.remote.journeys.MacUpdateJourney` (one @Test per path below). Installer steps reuse the `e2e/web/tests/install.spec.ts` fixture.

## Preconditions
- P1. App: debug `dev.mote.remote.dev`, has saved + trusted Mac X (helper host, selected, Wi-Fi), permissions irrelevant.
- P2. Phone: emulator on "AndroidWifi", no Tailscale VPN.
- P3. Mac / helper: an older `MoteHelper.app` already in `<tmp HOME>/Applications`; a helper "running" (stub `pgrep -x MoteHelper` exits 0). Helper stand-in: `MoteHelper --dry-run --headless --no-ble --memory-store --port N --stdin-control` (phone already trusted on it).
- P4. Network: same LAN (10.0.2.2 ↔ 10.0.2.17); installer fixture on loopback.

## Main path `mac-update`
| # | Actor | Action (exact) | Expected observable (exact) | Seam |
|---|---|---|---|---|
| S1 | Mac user | Menu: check "Launch at login" / "Allow Tailscale connections" | "Launch at login" ✓ iff `SMAppService.mainApp.status == .enabled` (already ✓ after first real launch, see setup-mac S7); "Allow Tailscale connections" ✓ (default on) → next line "Tailscale: http://<MagicDNS or 100.x>:8790" or "Tailscale: not detected (is it running?)"; log `tailscale connections allowed\|refused` on toggle | needs user: menu only. Stand-in for Tailscale on: start with `--allow-tailscale` → log `hostId <id>, tailscale connections allowed`; runtime toggle: faked: needs new stdin-control command `tailscale on\|off` |
| S2 | Mac user | Re-run `bash site/install.sh` (fixture serves the newer zip) | stdout `==> This will:` … `==> Downloading MoteHelper-macos-arm64.zip`, `==> Verifying SHA-256`, `    ok <sha>`, `==> Unpacking`, `==> Quitting the running Mote Helper`, `==> Installing to <tmp>/Applications/MoteHelper.app`, `==> Opening Mote Helper`, then the "Done. Next steps:" block incl. "After an update you may have to switch it off and on" / "again there (the app is ad-hoc signed, so each version has a new signature)."; stub log has `pgrep -x MoteHelper`, `pkill -x MoteHelper`, `open <tmp>/Applications/MoteHelper.app`; installed bundle is the new one | faked: fixture + stubs (`pgrep` exit 0) |
| S3 | system | Old helper quits | phone: helper WebSocket closes; Devices row "Connecting…" while reconnecting | faked: stand-in helper killed with SIGTERM (`main.swift:82`, exits 0) and restarted with the same flags |
| S4 | system | New helper starts | new build = new ad-hoc signature: Accessibility may read as not granted → menu "Mote Helper: Needs Accessibility permission"; possible Keychain access prompt for service `dev.mote.helper` / `dev.mote.helper.labs` | needs user: TCC + Keychain; not reproducible with `--memory-store` |
| S5 | Mac user | System Settings › Accessibility: MoteHelper off, then on (or remove and re-add) | ≤ 2 s: menu "Mote Helper: Ready"; helper broadcasts `{"t":"state","paused":false,"ax":true}` | needs user: TCC |
| S6 | system | Phone reconnects silently (trusted key, no token) | helper log `labs: trusted <phone> via trusted`; no `NEW DEVICE:` line; Devices row "Connected · Wi-Fi"; pill contentDescription "Device: <Mac> via Wi-Fi. Tap to choose" | real only if the restarted stand-in keeps its identity: with `--memory-store` the helper key and hostId change on restart → faked: needs a persistent test store (e.g. `--keychain-service mote-test-<n>` is forbidden by the no-Keychain constraint → needs new flag `--store-file PATH`) |
| S7 | Mac user | Menu › uncheck "Launch at login" | `SMAppService.mainApp.unregister()`; item unchecked | needs user |
| S8 | Mac user | Menu › "Quit Mote Helper" (⌘Q) | process exits (`NSApp.terminate`) | needs user (menu); stand-in: SIGTERM |
| S9 | Mac user | `rm -rf ~/Applications/MoteHelper.app` | app gone; install.sh's own line is `Uninstall: quit it from the menu, then  rm -rf "<abs path>/MoteHelper.app"` | manual: user shell (installer text asserted in S2) |

## Postconditions
- After S6: phone still has Mac X saved + trusted (same hostId, same key fp), selection = Helper(X); helper trusted list still contains the phone.
- After S9: no app bundle; UserDefaults (`hostId`, `allowTailscale`, `loginApplied`, …) and Keychain items (`dev.mote.helper`, `dev.mote.helper.labs`) remain (no code removes them); TCC entries remain.

## Branches
### `mac-update.skip-regrant`: Skipped the re-grant (from S5; artboard jA2-b1s)
Preconditions delta: new helper with Accessibility not granted.
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | system | phone reconnects | `{"t":"state",…,"ax":false}`; Devices row "Mote Helper needs Accessibility on the Mac"; pill LED amber | faked: needs new flag `--fake-ax-denied` (dry-run forces `inputReady` true today) |
| B2 | phone user | tap a key | `{"t":"err","code":"ax","msg":"Accessibility permission not granted to Mote Helper"}`; no `DRY` line | faked: same flag |
| B3 | Mac user | do S5 | row → "Connected · Wi-Fi" | faked: needs stdin `ax-on` |

### `mac-update.not-running`: update while the helper isn't running (from S2; not drawn)
| B1 | Mac user | run install.sh with stub `pgrep` exit 1 | no `==> Quitting the running Mote Helper` line, no `pkill` in stub log; otherwise as S2 | faked: stubs |

### `mac-update.update-fails`: checksum mismatch during update (from S2; not drawn)
| B1 | Mac user | run install.sh, bad `.sha256` | `error: checksum mismatch (expected …, got …). Not installing.`; old app untouched; running helper NOT quit (quit happens after verify+unpack) | faked: fixture |

### `mac-update.keychain-denied`: Deny on the Keychain prompt after an update (from S4; not drawn)
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | Mac user | Deny Keychain access to the new build | `labs: keychain read failed: <OSStatus>`; `LabsKeyStore.identity()` generates a NEW key and tries to overwrite the old one (`InstantPairing.swift:463-467, 502-511`) → new fp in `instant pairing: on (key <8 hex>)` | needs user: Keychain prompt |
| B2 | system | phone reconnects | phone pins the old fp → `helper-key` → mapped to `auth` → transport stops; Devices shows the failure (journey reconnect / recovery) | faked: needs a stand-in with a different key for the same hostId (new flag, e.g. `--host-id ID` with `--memory-store`) |

### `mac-update.tailscale-off`: Allow Tailscale off (from S1; not drawn)
| B1 | Mac user | uncheck "Allow Tailscale connections" | log `tailscale connections refused`; tailnet peers dropped (`web?.dropTailnetPeers()`); no "Tailscale:" line | needs user (menu); faked: needs stdin `tailscale off` |

## Disagreements → DEFECTS.md
- `mac-update-D1` (keychain-denied): a denied/failed Keychain read after an update silently mints a new helper identity, breaking every trusted phone.
- `mac-update-D2` (S1/S7): Launch at login is auto-enabled by the helper; artboard caption and install.sh's uninstall line don't say to turn it off before deleting the app (artboard jA2-s4 does, install.sh doesn't).
- `mac-update-D3` (S4): artboard jA2-s3 caption says "click Always Allow on the Keychain prompt"; install.sh never mentions the Keychain prompt.
- `mac-update-D4` (skip-regrant B1): artboard jA2-b1s Devices sheet shows "Add Mac over Wi-Fi", "Enter address", "WI-FI" section; code shows "Add a Mac", "More options" (→ "Pair with code", "Enter address").

## UNDEFINED
- S4: whether macOS keeps or drops the Accessibility grant for a re-signed ad-hoc build, and whether/which Keychain prompt appears — OS behaviour, not in code.
- S3: reconnect timing after the helper restarts (WsTransport backoff, not specified here; see journey reconnect).
- S1: MagicDNS detection depends on a Tailscale CLI at the paths in `Config.swift:141-143`.
