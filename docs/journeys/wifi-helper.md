# wifi-helper: 2. Wi-Fi / Tailscale connect

Artboard: `design/mote-v1/journey-wifi-helper.html` (Next (0.6.0)). Code: `site/install.sh`, `android/.../pairing/ConnectUi.kt` (FindMacScreen, FindMacPanel, ConnectCard), `pairing/Finder.kt`, `pairing/Sweep.kt`, `pairing/InstantPairing.kt`, `pairing/Handshake.kt`, `pairing/FirstRun.kt`, `ui/App.kt`, `ui/HostsUi.kt` (HostPill), `helper/.../InstantPairing.swift`, `Proximity.swift`, `Hub.swift`, `MenuBar.swift` (showNewDevice), `main.swift`. Docs: `docs/pairing.md` §2, §4, §5.
Test: `e2e/journeys` → `dev.mote.remote.journeys.WifiHelperJourney` (one @Test per path below).

Every failure branch of the same Connect card is specced once, in `connect-next.md`; this journey is the happy path from "helper just installed" plus the Tailscale-owner variant.

Notation: `<Mac>` = helper `Host.computerName` (sent as `hello.host` and `labs.pair.hi.host`); `<phone>` = phone `Settings.Global.DEVICE_NAME`, else `Build.MODEL` (`InstantPairing.deviceName()`; read it in the test with `adb shell settings get global device_name`); `$P` = free helper port; `<fp>` = phone key fingerprint (32 hex).

## Preconditions
- P1. App: fresh install of the debug app `dev.mote.remote.dev` (no app data: no saved host, no trust record, `wifi_chosen`/`bt_chosen` unset, no selection). No runtime permission granted (`BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`, `BLUETOOTH_ADVERTISE`, `POST_NOTIFICATIONS` all not granted). Seams: `TestSeam.noBonjour = true`, `TestSeam.sweepPort = $P`, `TestSeam.proximity` (to be added: returns the token from `{"t":"labs.pair.prox"}` asked over the WebSocket instead of a BLE read).
- P2. Phone: Wi-Fi on ("AndroidWifi", 10.0.2.17/24), Bluetooth: no Mote BLE peripheral in range; no Tailscale app/VPN.
- P3. Mac / helper: `MoteHelper --dry-run --headless --no-ble --memory-store --port $P --stdin-control --fake-proximity` (+ `--no-bonjour` once it exists). Instant pairing on (default), Require approval off, not paused, trust list empty.
- P4. Network: emulator NAT; the helper is reachable at 10.0.2.2:$P (peer seen by the helper: 127.0.0.1).

## Main path `wifi-helper`
| # | Actor | Action (exact) | Expected observable (exact) | Seam |
|---|---|---|---|---|
| S1 | Mac user | `curl -fsSL https://tusharpandey13.github.io/mote/install.sh \| bash` | Terminal: `==> This will:` … `==> Downloading MoteHelper-macos-<arch>.zip`, `==> Verifying SHA-256`, `    ok <sha>`, `==> Unpacking`, `==> Installing to ~/Applications/MoteHelper.app` (path as `$APP`), `==> Opening Mote Helper`, then the "Done. Next steps:" block (install.sh:78-91) | needs user: installs and opens the real app (TCC, Keychain). Stand-in: start the dry-run helper of P3 and wait for stderr `[mote] wifi: listening on :$P` and stdout `LABS PAIR INFO: {...}` |
| S2 | system (helper) | — (launch) | stderr `[mote] instant pairing: on (key <8 hex>)`; stdout `LABS PAIR INFO: {"fp":"…","id":"<hostId>","lan":[…],"n":"<Mac>","p":$P,"v":1}` | real (dry-run) |
| S3 | phone user | Launch Mote | `FindMacScreen`: "Connect to your Mac", "Over Wi-Fi or Tailscale, through the free Mote Helper on the Mac.", "New phone, or reinstalled Mote? Let's reconnect: tap Connect, nothing changes on the Mac.", button "Use Bluetooth instead (no helper)" (contentDescription = label). No permission dialog. | real |
| S4 | system | (≤ 1 s after S3) | `FindMacPanel` Searching: "Looking for your Mac…", "Searching this Wi-Fi, Bluetooth and Tailscale." (may be skipped if the sweep answers first) | real (sources: sweep of 10.0.2.0/24 on $P; BLE: no results without permission; Bonjour off by seam) |
| S5 | system | (≤ 5 s after S3) | `ConnectCard`: title "<Mac>", button "Connect to <Mac>"; helper stderr shows the probe: `[mote] authed …` NOT logged (probe never authenticates) | real |
| S6 | phone user | Tap "Connect to <Mac>" | "Connecting…" (transient) | real |
| S7 | system (helper) | — | stderr in order: `[mote] labs: trusted <phone> via nearby`, `[mote] authed <phone> via Wi-Fi from 127.0.0.1`, `[mote] labs: new device <phone> via nearby (k:<fp>)`; stdout `NEW DEVICE: {"id":"k:<fp>","name":"<phone>","via":"nearby"}`. Wire: `{"t":"labs.pair.ok","via":"nearby","id":"k:<fp>","name":"<phone>"}` then `authed` | faked: proximity token via `TestSeam.proximity` + `--fake-proximity` (InstantPairing.swift:143) |
| S8 | system (phone) | — | App leaves `FindMacScreen` for the remote (Media mode). Pill contentDescription "Device: <Mac> via Wi-Fi. Tap to choose", badge text "WI-FI", LED green once `WsStatus.Ready`. The card's "Connected" / Bluetooth offer is NOT shown (see DEFECTS connect-next-D2) | real |
| S9 | system (helper) | — (reconnect by `WsTransport`) | stderr `[mote] labs: trusted <phone> via trusted`, `[mote] authed <phone> via Wi-Fi from 127.0.0.1`; no second `NEW DEVICE` | real |
| S10 | Mac user | — | Floating panel "Mote" at top right: "<phone> connected to Mote." / "via Bluetooth proximity" / button "Block"; closes after 15 s | faked: headless has no panel; S7 stdout `NEW DEVICE` stands in (MenuBar.swift:236) |

## Postconditions
- Phone: DataStore `hosts` has one `SavedHost(hostId, name=<Mac>, deviceId="k:<fp>", lan="10.0.2.2", port=$P)`; `labs_pairing.trust` has `TrustRecord(hostId, pub, fp, via="nearby", info)`; selection `ws:<hostId>`; `wifi_chosen=true`, `bt_chosen=false`. Next launch opens straight into the remote (`startScreen` → REMOTE).
- Helper: trust list has 1 key `{fp, name:<phone>, via:"nearby"}`; memory only (`--memory-store`).

## Branches
### `wifi-helper.tailscale-owner`: away from home, same Tailscale account (from S5; artboard step j2-b1s)
Preconditions delta: helper adds `--fake-tailscale-owner self`, drops `--fake-proximity`; phone has no `TestSeam.proximity` (no token sent).
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | phone user | Tap "Connect to <Mac>" | "Connecting…" | real |
| B2 | system (helper) | — | stderr `[mote] labs: fake whois 127.0.0.1 owner=self`, `[mote] labs: trusted <phone> via tailscale`, `[mote] authed <phone> via Wi-Fi from 127.0.0.1`, `[mote] labs: new device <phone> via tailscale (k:<fp>)`; stdout `NEW DEVICE: {"id":"k:<fp>","name":"<phone>","via":"tailscale"}` | faked: owner match by `--fake-tailscale-owner self` over the LAN; a real tailnet peer (100.64/10) is not available on the emulator |
| B3 | system (phone) | — | Remote screen; pill "Device: <Mac> via Wi-Fi. Tap to choose" (route is the address actually used, 10.0.2.2). "Connected. Trusted via Tailscale" is NOT shown on first run (connect-next-D2) | real |
| B4 | Mac user | — | Notice "<phone> connected to Mote." / "via Tailscale" / "Block" | faked: stdout `NEW DEVICE` stands in |
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
| B1 | Mac user | read the end of the install.sh output | "  3. A pairing window shows a 6-digit code and a QR code. On your phone, on the same Wi-Fi: scan the QR, or open http://<LocalHostName>.local:8790 and enter the code." | static: `site/install.sh:85-87` |
| B2 | system (helper) | first real launch, instant pairing on, no paired devices | No pairing window opens (`main.swift:53`: only when `--open-pairing` or `!instantPairing.enabled`) | static; DEFECT wifi-helper-D1 |

## Disagreements → DEFECTS.md
- `wifi-helper-D1` (S1): install.sh "Next steps" promise a 6-digit pairing window that 0.6 never opens, and say Bluetooth is only for the web remote.
- `connect-next-D1` (S4/S5): discovery and Connect card layout/copy differ from the artboard (see connect-next).
- `connect-next-D2` (S8/B3): first-run screen switches to the remote before "Connected…" / Bluetooth offer can show.
- `connect-next-D3` (tailscale branch): a tailnet-only Mac can't be discovered away from home; only Enter address works.

## UNDEFINED
- S1: Terminal output of a real install (arch, SHA, quarantine) depends on the release asset; not testable headless.
- S5: `<Mac>` is the dev Mac's `Host.computerName`; not fixed by code.
- S10: panel position/size are macOS layout (MenuBar.swift sets top-right of `NSScreen.main.visibleFrame`, 320x76); not observable headless.
- S4: whether "Looking for your Mac…" is visible at all depends on sweep latency (< 1 s typical); a test must not require it.
