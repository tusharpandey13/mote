# connect-now: C. Code pairing (legacy 0.4.0, being replaced)

Artboard: `design/mote-v1/journey-connect-now.html` (Legacy (0.4.0, being replaced)). Code: `android/.../ui/HostsUi.kt` (HostSheet pages LIST, NEARBY "Pair with code", ADDRESS, CODE; `friendly()`), `net/Net.kt` (pair, probe), `net/WsSession.kt` (pairWith, reply), `net/Hosts.kt` (PairLink, parseAddress), `net/Discovery.kt` (Bonjour), `MainActivity.kt` (mote://pair), `ui/App.kt` (startScreen, `setup`, BtGateScreen), `helper/.../Hub.swift` (Session.pair), `Security.swift` (Pairing, RateLimiter), `MenuBar.swift` (Advanced › Pair with code, showPairing), `main.swift` (stdin `pair`). Docs: `docs/protocol.md` §1, §4; `docs/pairing.md` (status line); `android/README.md` "Over Wi-Fi or Tailscale".
Test: `e2e/journeys` → `dev.mote.remote.journeys.ConnectNowJourney` (one @Test per path below).

What the code does now: the 6-digit code is a hidden fallback. Phone: pill › Devices › "More options" › "Pair with code" (or a `mote://pair` link). Mac: menu Advanced › "Pair with code (web remote)…" (headless: `--open-pairing` or stdin `pair`). Notation: `<Mac>`, `<phone>`, `$P` as in `wifi-helper.md`; `<code>` = 6 digits from stdout `PAIRING CODE: <code>`.

## Preconditions
- P1. App: `dev.mote.remote.dev`, no saved helper host. To reach the remote screen (and the Devices sheet) without a helper host the app must be on the Bluetooth path: "Use Bluetooth instead (no helper)" tapped (`bt_chosen=true`), `TestSeam.adapter = Adapter(supported=true, enabled=true)`, `TestSeam.transport` = fake BT transport, `BLUETOOTH_CONNECT`, `BLUETOOTH_ADVERTISE`, `BLUETOOTH_SCAN` granted (so `BtGate.READY` → `StartScreen.REMOTE`). Alternative entry: the `mote://pair` link (branch `deep-link`).
- P2. Phone: Wi-Fi on (AndroidWifi).
- P3. Mac / helper: `MoteHelper --dry-run --headless --no-ble --memory-store --port $P --stdin-control --open-pairing` → stdout `PAIRING CODE: <code>`; window 300 s, one device.
- P4. Network: 10.0.2.2:$P reachable; Bonjour does not reach the emulator (NAT, no multicast).

## Main path `connect-now`
| # | Actor | Action (exact) | Expected observable (exact) | Seam |
|---|---|---|---|---|
| S1 | phone user | Tap the pill (contentDescription "Device: Choose a device. Tap to choose") | `HostSheet` LIST: "Devices", note "Mote reconnects to the selected device while the app is open.", button "Add a Mac", link "More options" | faked: adapter + transport seams |
| S2 | phone user | Tap "More options", then "Pair with code" | Page NEARBY: header "Pair with code" (back contentDescription "Back"), "On the Mac, open the Mote Helper menu and choose Pair new device. Then pick the Mac here, or scan its QR with the camera and tap Open in the Mote app.", list "Looking for Macs on this Wi-Fi…" (while NSD scans, 30 s), button "Enter address", "No Mote Helper yet? Get it at tusharpandey13.github.io/mote" | real |
| S3 | system | (30 s) | "No Macs found. Guest and hotel Wi-Fi often block this. Try Enter address, Tailscale or Bluetooth." and button "Scan again" | real (Bonjour can't reach the emulator) |
| S4 | phone user | Tap "Enter address" | Page ADDRESS: header "Enter address", "For Tailscale, use the Mac's MagicDNS name or its 100.x address, and turn on Allow Tailscale connections in the Mote Helper menu. A LAN IP or name.local works too.", field (placeholder / contentDescription "mac.tailnet.ts.net or 100.x.y.z"), button "Connect" | real |
| S5 | phone user | Type `10.0.2.2:$P`, tap "Connect" | "Connecting…", then Page CODE: header "Pair with <Mac>", "Enter the 6-digit code shown in the Mote Helper pairing window on the Mac.", field (placeholder / contentDescription "6-digit code", digits only, max 6), button "Pair" | real |
| S6 | phone user | Type `<code>`, tap "Pair" | "Pairing…" | real |
| S7 | system (helper) | — | stderr `[mote] paired <phone> (Wi-Fi)`, then (WsTransport HMAC auth) `[mote] authed <phone> via Wi-Fi from 127.0.0.1`; `hello.pairing` false from now on | real |
| S8 | system (phone) | — | sheet closes; pill "Device: <Mac> via Wi-Fi. Tap to choose", badge "WI-FI" | real |

## Postconditions
- Phone: `SavedHost(hostId, <Mac>, deviceId=<uuid>, lan="10.0.2.2", port=$P, wrapped=null|<blob>)`, token in the Keystore (`Vault`); no instant-pairing trust record; selection `ws:<hostId>`; `wifi_chosen=true`, `bt_chosen=false`.
- Helper: one `Device` in the (memory) device store; pairing window closed (`Pairing.close()` after one device).

## Branches
### `connect-now.found`: Bonjour finds the Mac (from S2; artboard jC-s3)
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | system | NSD resolves `_mote._tcp` | row: name "<Mac>" (service name), address `<ip>`, badge "WI-FI", contentDescription "Pair <Mac>" | faked: needs a Discovery seam injecting a `Found` (NSD can't cross the emulator NAT; `TestSeam.noBonjour` only affects `Finder`) |
| B2 | phone user | Tap the row | Page CODE "Pair with <Mac>" (no `hello.pairing` check here); continue at S6 | faked |

### `connect-now.wrong-code` (from S6; artboard jC-b2s)
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | phone user | Type a 6-digit code ≠ `<code>`, tap "Pair" | helper `[mote] pairing: wrong code from 127.0.0.1`; phone: "Wrong code. Check the code in the Mote Helper window." | real |
| B2 | phone user | Type 5 digits, tap "Pair" | "Enter the 6-digit code shown on the Mac." (no request sent) | real |

### `connect-now.rate` (from wrong-code; artboard jC-b2s3)
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | phone user | 5 wrong codes within 60 s, then a 6th Pair | helper `err rate` with `retryAfter` (1-60); phone: "Too many attempts. Try again in <retryAfter> s." | real |
| B2 | system (helper) | 10 wrong codes in one window (any peers) | stderr `[mote] pairing: 10 wrong codes, closing the pairing window`; next Pair → closed branch | real (needs > 60 s or several peers) |

### `connect-now.closed`: pairing window not open (from S5/S6; artboard jC-b2s2)
Preconditions delta: helper without `--open-pairing` (or after a device paired, or 300 s).
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | phone user | S4-S5 with `10.0.2.2:$P` | Page CODE opens AND shows at once: "Pairing isn't open. On the Mac choose Pair new device in the Mote Helper menu." (from `hello.pairing == false`) | real |
| B2 | phone user | Type any code, tap "Pair" | helper `err pairing-closed` ("Open “Pair new device” in the Mote Helper menu on the Mac"); phone: "Pairing isn't open. On the Mac choose Pair new device in the Mote Helper menu." | real |
| B3 | Mac user | Advanced › "Pair with code (web remote)…" | window "Pair a device with Mote": "Enter this code in the Mote remote", "<3 digits> <3 digits>", QR, "Or scan with the phone camera (same Wi-Fi) to open http://<LAN IP>:$P", "Pairing closes after 5 minutes or once a device pairs. Only pair devices you own."; menu shows "Pairing code: <3> <3>", "Show pairing code and QR…", "Stop pairing" | faked: stdin `pair` → stdout `PAIRING CODE: <code>` (window/menu headless-unobservable) |
| B4 | phone user | Type the new code, tap "Pair" | continue at S7 | real |

### `connect-now.no-answer`: helper silent after `hello` (from S6; artboard jC-b2s3 caption)
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | phone user | Tap "Pair" | after 5 s: "The Mac didn't answer. Is Mote Helper running?" | faked: chaos proxy forwards `hello`, black-holes the rest |

### `connect-now.unreachable` (from S5/S6; coverage "couldn't reach X", text-only)
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | phone user | S4 with `10.0.2.3:$P`, tap "Connect" | "Couldn't reach 10.0.2.3. Same Wi-Fi as the Mac? Is Mote Helper running?" | real |
| B2 | phone user | On CODE, kill the helper, tap "Pair" | "Couldn't reach 10.0.2.2. Same Wi-Fi as the Mac? Is Mote Helper running?" | real |
| B3 | phone user | S4 with `bad host!` | "Enter a name like mac.tailnet.ts.net or an address like 100.101.102.103." | real |

### `connect-now.known-address`: address of an already saved Mac (from S5; not drawn)
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | phone user | After the main path, S2-S5 again with `10.0.2.2:$P` | no CODE page; address added to the saved host, host selected, sheet closes | real |

### `connect-now.tailnet-address` (from S4; artboard jC-b1s2 text)
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | phone user | Type `mac.example.ts.net`, tap "Connect", VPN off | "Tailscale is off" sheet (tailscale.md); once the VPN is up, `doAddress()` re-runs | faked: needs Tailscale seam (no app → "Get Tailscale" variant only) |

### `connect-now.deep-link`: `mote://pair` from the QR page (not drawn)
Preconditions delta: fresh install, no Bluetooth chosen.
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | system | app already open on `FindMacScreen`; `adb shell am start -a android.intent.action.VIEW -d 'mote://pair?h=10.0.2.2:$P&code=<code>' dev.mote.remote.dev` | next-0.6: the `setup` flag (App.kt) keeps `FindMacScreen` "Connect to your Mac" on screen even though `skip` makes `startScreen` REMOTE; no "Pair with 10.0.2.2" page, no code field. (Cold start by the link: the sheet opens, consumes the link, `chooseWifi()` → FIND_MAC → `FindMacScreen`.) Either way the code is lost (connect-now-D4) | real |
| B2 | (variant) | same link while a helper host is saved or a Bluetooth host is selected | sheet stays: CODE "Pair with <saved name or 10.0.2.2>", code prefilled; tap "Pair" → continue at S7 | real |
| B3 | (variant of B2) | link with `&ts=mac.example.ts.net` | extra line "Tailscale address from the pairing link: mac.example.ts.net"; on a non-HelperError LAN failure the phone retries on `ts` (after the Tailscale prompt if the VPN is off) | real (fallback faked) |

## Disagreements → DEFECTS.md
- `connect-now-D1` (S2, closed B1/B2, B3): phone and helper copy say "Pair new device"; the menu item is Advanced › "Pair with code (web remote)…".
- `connect-now-D2` (S1-S2): artboard entry (gate "Use Wi-Fi with Mote Helper" → "Add Mac over Wi-Fi") no longer exists; code entry is Devices › More options › Pair with code, unreachable on Wi-Fi-only first run without a link.
- `connect-now-D4` (deep-link B1): a `mote://pair` link on a fresh install is dropped: the app stays on / falls back to FindMacScreen (still true in next-0.6).
- `connect-now-D3`: `android/README.md` "Over Wi-Fi or Tailscale" and "First use" still describe code pairing / old button names.

## UNDEFINED
- S3: exact NSD behaviour on the emulator (assumed: nothing found; multicast doesn't cross user-mode NAT).
- B3 of closed: QR/window layout (AppKit), headless-unobservable.
- rate B1: `retryAfter` value depends on the age of the oldest failure (≤ 60 s).
