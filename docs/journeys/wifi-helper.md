# wifi-helper: 2. Wi-Fi / Tailscale connect

Artboard: `design/mote-v1/journey-wifi-helper.html` (Next (0.6.0)). Code: `site/install.sh`, `android/.../pairing/ConnectUi.kt` (FindMacScreen, FindMacPanel, ConnectCard), `pairing/Finder.kt`, `pairing/Sweep.kt`, `pairing/InstantPairing.kt`, `pairing/Handshake.kt`, `pairing/FirstRun.kt`, `ui/App.kt`, `ui/HostsUi.kt` (HostPill), `helper/.../InstantPairing.swift`, `Proximity.swift`, `SetupWindow.swift`, `Hub.swift`, `MenuBar.swift` (showNewDevice), `main.swift`. Docs: `docs/pairing.md` §2, §4, §5.
Test: `e2e/journeys` → `dev.mote.remote.journeys.WifiHelperJourney` (one @Test per path below).

Every failure branch of the same Connect card is specced once, in `connect-next.md`; this journey is the happy path from "helper just installed" plus the Tailscale-owner variant.

Notation: `<Mac>` = helper `Host.computerName` (sent as `hello.host` and `labs.pair.hi.host`); `<phone>` = phone `Settings.Global.DEVICE_NAME`, else `Build.MODEL` (`InstantPairing.deviceName()`; read it in the test with `adb shell settings get global device_name`); `$P` = free helper port; `<fp>` = phone key fingerprint (32 hex).

## Preconditions
- P1. App: fresh install of the debug app `dev.mote.remote.dev` (no app data: no saved host, no trust record, `wifi_chosen`/`bt_chosen` unset, no selection). No runtime permission granted (`BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`, `BLUETOOTH_ADVERTISE`, `POST_NOTIFICATIONS` all not granted). Seams: `TestSeam.noBonjour = true`, `TestSeam.sweepPort = $P`, `TestSeam.fakeProximity = true` (the token comes from `{"t":"labs.pair.prox"}` asked over the WebSocket instead of a BLE read).
- P2. Phone: Wi-Fi on ("AndroidWifi", 10.0.2.17/24), Bluetooth: no Mote BLE peripheral in range; no Tailscale app/VPN.
- P3. Mac / helper: `MoteHelper --dry-run --headless --no-ble --memory-store --port $P --stdin-control --fake-proximity` --no-bonjour. Instant pairing on (default), Require approval off, not paused, trust list empty.
- P4. Network: emulator NAT; the helper is reachable at 10.0.2.2:$P (peer seen by the helper: 127.0.0.1).

## Main path `wifi-helper`
| # | Actor | Action (exact) | Expected observable (exact) | Seam |
|---|---|---|---|---|
| S1 | Mac user | `curl -fsSL https://tusharpandey13.github.io/mote/install.sh \| bash` | Terminal: `==> This will:` … `==> Downloading MoteHelper-macos-<arch>.zip`, `==> Verifying SHA-256`, `    ok <sha>`, `==> Unpacking`, `==> Installing to ~/Applications/MoteHelper.app` (path as `$APP`), `==> Opening Mote Helper Setup`, then "Done. Follow the Setup window…" | needs user: installs and opens the real app (TCC, Keychain). Stand-in: start the dry-run helper of P3 and wait for stderr `[mote] wifi: listening on :$P` and stdout `LABS PAIR INFO: {...}` |
| S2 | system (helper) | — (launch) | stderr `[mote] instant pairing: on (key <8 hex>)`; stdout `LABS PAIR INFO: {"fp":"…","id":"<hostId>","lan":[…],"n":"<Mac>","p":$P,"v":1}` | real (dry-run) |
| S3 | phone user | Launch Mote | `FindMacScreen`: "Connect to your Mac", "Over Wi-Fi or Tailscale, through the free Mote Helper on the Mac.", "New phone, or reinstalled Mote? Let's reconnect: tap Connect, nothing changes on the Mac.", button "Use Bluetooth instead (no helper)" (contentDescription = label). No permission dialog. | real |
| S4 | system | (≤ 1 s after S3) | `FindMacPanel` Searching: "Looking for your Mac…", "Searching this Wi-Fi, Bluetooth and Tailscale." (may be skipped if the sweep answers first) | real (sources: sweep of 10.0.2.0/24 on $P; BLE: no results without permission; Bonjour off by seam) |
| S5 | system | (≤ 5 s after S3) | `ConnectCard`: title "<Mac>", button "Connect to <Mac>"; helper stderr shows the probe: `[mote] authed …` NOT logged (probe never authenticates) | real |
| S6 | phone user | Tap "Connect to <Mac>" | "Connecting…" (transient) | real |
| S7 | system (helper) | — | stderr in order: `[mote] labs: trusted <phone> via nearby`, `[mote] authed <phone> via Wi-Fi from 127.0.0.1`, `[mote] labs: new device <phone> via nearby (k:<fp>)`; stdout `NEW DEVICE: {"id":"k:<fp>","name":"<phone>","via":"nearby"}`. Wire: `{"t":"labs.pair.ok","via":"nearby","id":"k:<fp>","name":"<phone>"}` then `authed` | faked: proximity token via `TestSeam.fakeProximity` + `--fake-proximity` (InstantPairing.swift:143) |
| S8 | system (phone) | — | Setup stays on screen (App.kt `setup`, next-0.6): `ConnectCard` "Connected over Wi-Fi", checkbox row "Also pair Bluetooth for login screen & backup (recommended)" (checked), "Android asks twice: make the phone visible, then Pair. Nothing to do on the Mac.", button "Continue", link "Skip" (phone has a Bluetooth adapter and the Mac isn't linked) | real |
| S9 | system (helper) | — (reconnect by `WsTransport`, already running behind the setup screen) | stderr `[mote] labs: trusted <phone> via trusted`, `[mote] authed <phone> via Wi-Fi from …`; no second `NEW DEVICE` | real |
| S10 | phone user | Tap "Skip" | remote (Media mode); pill contentDescription "Device: <Mac> via Wi-Fi. Tap to choose", badge "WI-FI" | real |
| S11 | Mac user | — | Floating panel "Mote" at top right: "<phone> connected to Mote." / "via Bluetooth proximity" / button "Block"; closes after 15 s | faked: headless has no panel; S7 stdout `NEW DEVICE` stands in (MenuBar.swift `showNewDevice`) |

## Postconditions
- Phone: remote screen after S10. DataStore `hosts` has one `SavedHost(hostId, name=<Mac>, deviceId="k:<fp>", lan="10.0.2.2", port=$P)`; `labs_pairing.trust` has `TrustRecord(hostId, pub, fp, via="nearby", info)`; selection `ws:<hostId>`; `wifi_chosen=true`, `bt_chosen=false`. Next launch opens straight into the remote (`startScreen` → REMOTE).
- Helper: trust list has 1 key `{fp, name:<phone>, via:"nearby"}`; memory only (`--memory-store`).

## Branches
### `wifi-helper.tailscale-owner`: away from home, same Tailscale account (from S5; artboard step j2-b1s)
Preconditions delta: helper adds `--fake-tailscale-owner self`, drops `--fake-proximity`; phone has no `TestSeam.fakeProximity` (no token sent).
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | phone user | Tap "Connect to <Mac>" | "Connecting…" | real |
| B2 | system (helper) | — | stderr `[mote] labs: fake whois 127.0.0.1 owner=self`, `[mote] labs: trusted <phone> via tailscale`, `[mote] authed <phone> via Wi-Fi from 127.0.0.1`, `[mote] labs: new device <phone> via tailscale (k:<fp>)`; stdout `NEW DEVICE: {"id":"k:<fp>","name":"<phone>","via":"tailscale"}` | faked: owner match by `--fake-tailscale-owner self` over the LAN; a real tailnet peer (100.64/10) is not available on the emulator |
| B3 | system (phone) | — | setup stays: "Connected over Tailscale" (label from `via`, although the route is Wi-Fi 10.0.2.2: connect-next-D4) + the Bluetooth offer ("Also pair Bluetooth for login screen & backup (recommended)"). "Connected. Trusted via Tailscale" is only shown when there is no Bluetooth offer | real |
| B4 | phone user | Tap "Skip" | remote; pill "Device: <Mac> via Wi-Fi. Tap to choose" | real |
| B5 | Mac user | — | Notice "<phone> connected to Mote." / "via Tailscale" / "Block" | faked: stdout `NEW DEVICE` stands in |
Postconditions: as main, but `TrustRecord.via="tailscale"`, helper key `via:"tailscale"`.

### `wifi-helper.tailscale-real`: real tailnet peer (not drawn separately)
Preconditions delta: phone reaches the Mac only on its MagicDNS name / 100.x; helper `--allow-tailscale`.
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | phone user | Tap "Enter address" (only reachable after NotFound; discovery cannot find a tailnet-only Mac, connect-next-D3), type `mac.<tailnet>.ts.net`, tap "Find" | `ConnectCard` "Connect to <Mac>" | manual: needs a real tailnet (no Tailscale on the emulator) |
| B2 | phone user | Tap "Connect to <Mac>" | helper `labs: whois <100.x>: same owner`, `labs.pair.ok via tailscale`; pill badge "TAILSCALE" | manual |

### `wifi-helper.install-next-steps`: what install.sh tells the user next (from S1; not drawn)
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | Mac user | read the end of the install.sh output | "==> Opening Mote Helper Setup" (`open "$APP" --args --setup`), then "Done. Follow the Setup window (allow Accessibility; Bluetooth is recommended), then open Mote on your phone. Reopen it any time from the menu-bar icon: Setup…" | static: `site/install.sh` (next-0.6) |
| B2 | system (helper) | first real launch, instant pairing on, no paired devices | No pairing window opens (`main.swift`: only with `--open-pairing` or `!instantPairing.enabled`); the Setup window opens instead | static (Setup window is macOS UI) |

## Disagreements → DEFECTS.md
- `wifi-helper-D1` (S1): FIXED in next-0.6 (install.sh now opens the Setup window, no code promise).
- `connect-next-D1` (S4/S5): discovery and Connect card layout/copy differ from the artboard (see connect-next).
- `connect-next-D2` (S8): FIXED in next-0.6 (setup stays on screen for the Bluetooth step).
- `connect-next-D4` (B3): "Connected over Tailscale" while the route is Wi-Fi.
- `connect-next-D3` (tailscale branch): a tailnet-only Mac can't be discovered away from home; only Enter address works.

## UNDEFINED
- S1: Terminal output of a real install (arch, SHA, quarantine) depends on the release asset; not testable headless.
- S5: `<Mac>` is the dev Mac's `Host.computerName`; not fixed by code.
- S10: panel position/size are macOS layout (MenuBar.swift sets top-right of `NSScreen.main.visibleFrame`, 320x76); not observable headless.
- S4: whether "Looking for your Mac…" is visible at all depends on sweep latency (< 1 s typical); a test must not require it.
