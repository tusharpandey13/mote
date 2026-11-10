# reconnect: 4. Reconnect and disconnected states

Artboard: `design/mote-v1/journey-reconnect.html` (Shipped). Code: `android/.../ui/HostsUi.kt` (HostPill 87-137, HostSheet LIST 255-328, HostRowCap 415-465, failedText 485-490), `ui/App.kt` (Root, LocalLinked), `ui/Components.kt` (Cap `needsLink`, linkLight 224-236), `hid/Hid.kt` (select 214-227, tryReconnect 248-262, RECONNECT_MS 56), `hid/Reconnect.kt`, `service/HidService.kt:66-71` + `res/values/strings.xml:4-6`, `net/Net.kt` (apply 176-195, foreground 162-169, forget 278-287, NetworkCallback 115-118), `net/WsTransport.kt` (run 90-144), `pairing/InstantPairing.kt` (target/reconnect 152-167, watchForFallback 170-186, probeBack 189-201, rediscover 207-223), `pairing/Sweep.kt:130-147` (tcpReach, reachNote). Helper `Hub.swift` (Session.markAuthed log), `InstantPairing.swift` (hello trust order 151-191). Docs: `docs/pairing.md` §5 "Reconnect", `android/README.md:139,153-156`, `docs/design/design-language.md` §User journeys.
Test: `e2e/journeys` → `dev.mote.remote.journeys.ReconnectJourney` (one @Test per path below).

Overlaps: the helper asleep / helper-not-running copy is specified in `daily.md` (`daily.asleep`, `daily.nohelper`); revoke / block refusals in `recovery.md`. This spec covers the Bluetooth HID searching states drawn on the artboard and the helper reconnect machinery (backoff, network-back kick, background close, 15 s Bluetooth fallback, 30 s probe back, 4 s LAN re-discovery, Forget).

## Preconditions
- P1. App: debug `dev.mote.remote.dev`. Runtime permissions `android.permission.BLUETOOTH_CONNECT`, `android.permission.BLUETOOTH_ADVERTISE`, `android.permission.BLUETOOTH_SCAN`, `android.permission.POST_NOTIFICATIONS` granted (`MoteRule.grantAll`). `TestSeam.transport = FakeTransport(Caps.HID)` with `linked = false`; `TestSeam.adapter = Adapter(enabled = true, hosts = [Host("AA:00:00:00:00:01", "MacBook Pro", LAPTOP), Host("AA:00:00:00:00:02", "Mac mini", DESKTOP)])`. App data: Bluetooth chosen (`bt_chosen = true`), `selection = bt:AA:00:00:00:00:01`, Hid selected host "MacBook Pro" (Prefs `selectedHost`). No saved helper Mac.
- P2. Phone: Wi-Fi on ("AndroidWifi", 10.0.2.17/24), Bluetooth adapter faked present + on, no Tailscale VPN.
- P3. Mac / helper: none for the main path. Helper branches: H1 = `MoteHelper --dry-run --headless --no-ble --memory-store --port N1 --stdin-control --fake-proximity` started before the branch and kept running (memory store: a restart forgets the phone and changes `hostId`).
- P4. Network: same LAN (phone → 10.0.2.2:N1) for helper branches; Bonjour off (`TestSeam.noBonjour = true`), `TestSeam.sweepPort = N1`.

## Main path `reconnect`
| # | Actor | Action (exact) | Expected observable (exact) | Seam |
|---|---|---|---|---|
| S1 | phone user | Launch Mote | `startScreen` → REMOTE (`btSelected`, gate READY). Media screen. Pill: label "MacBook Pro", badge `BT`, LED linkWait pulsing; contentDescription `Device: MacBook Pro via BT. Tap to choose`. No test tag. Wordmark LinkLight CONNECTING (`Components.kt:233`) | faked: `TestSeam.adapter` + FakeTransport; Hid with a test transport has phase READY and `connected = null` (`Hid.kt:103`), which is exactly the searching state |
| S2 | system | (link notification) `HidService.start` (gate READY) | Ongoing notification title "Mote" (app_name), text "Searching for MacBook Pro…" | real (UiAutomator on the shade) |
| S3 | phone user | Tap any Media cap that needs a link (e.g. contentDescription "Play or pause") | Nothing sent: `FakeTransport.calls` stays empty (Cap `enabled = !needsLink || linked`, `Components.kt:129`); no in-screen message | faked: FakeTransport `linked = false` |
| S4 | phone user | Tap the pill | MoteSheet › HostSheet LIST: title "Devices"; note "Mote reconnects to the selected device while the app is open."; row contentDescription `Use MacBook Pro`, status "Searching…", badge `BT` (lit); row `Use Mac mini`, status "Paired", badge `BT`; button "Add a Mac"; link "More options"; button "Pair new Bluetooth device"; note "Makes this phone visible for 2 minutes. Pair it from the computer's Bluetooth settings; Android asks you to confirm the code."; link "Connected over Bluetooth but buttons do nothing?" | real (UI) |
| S5 | phone user | Tap row `Use Mac mini` | Sheet closes. `Net.select(Selection.Bt("AA:00:00:00:00:02"))`, `Hid.select(Mac mini)`. Pill label "Mac mini", badge `BT`, LED linkWait pulsing; notification text "Searching for Mac mini…" | real (UI) / faked (adapter) |

## Postconditions
- DataStore `hosts`: `selection = bt:AA:00:00:00:00:02`; Prefs `selectedHost = AA:00:00:00:00:02`. No helper state. FakeTransport recorded no calls.

## Branches
### `reconnect.signal-lost`: a connected Bluetooth host drops (from S1; artboard step j4-s1)
Preconditions delta: Hid `connected = MacBook Pro` at start.
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | system | Host disconnects (`onConnectionStateChanged` STATE_DISCONNECTED) | Pill turns from linkOk to linkWait pulsing, label "MacBook Pro", badge `BT`; haptic DISCONNECT; notification "Connected to MacBook Pro" → "Searching for MacBook Pro…" | faked: needs a new seam to drive `Hid.state.connected` (no HID proxy in tests) |
| B2 | system | Auto-reconnect: `tryReconnect` every 4 s (`RECONNECT_MS`), at most 3 attempts (`Reconnect.MAX_ATTEMPTS`), budget reset on bond / ACL / adapter change or ON_RESUME (`Hid.envChanged`) | After the 3rd failed attempt (≈ 12 s) nothing is attempted, but the pill keeps pulsing and the notification keeps "Searching for MacBook Pro…" (reconnect-D3) | faked: same seam; manual for real `connect()` |
| B3 | system | Host comes back (STATE_CONNECTED) | Pill linkOk, haptic CONNECT, notification "Connected to MacBook Pro"; Media caps enabled | faked: same seam |
Postconditions: selection unchanged.

### `reconnect.unpaired`: bond removed on the phone (from S1; artboard j4-s3)
Preconditions delta: adapter `hosts` no longer contains AA:00:00:00:00:01 while it is selected.
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | system | `tryReconnect` sees selected ∉ bonded | Pill label "Not paired: pair again", LED linkBad (no pulse), badge `BT`; no auto-connect (`Reconnect.target` only bonded) | faked: needs the HID-state seam (with a test transport `tryReconnect` returns at `proxy ?: return` before the unpaired check, `Hid.kt:251`) |
| B2 | phone user | Tap the pill | LIST note "Mote reconnects to the selected device while the app is open." (no unpaired note; reconnect-D2); row `Use MacBook Pro` kept visible with status "Not paired: pair again" (`HostsUi.kt:279,433`) | faked: same |
| B3 | phone user | Long-press row `Use MacBook Pro` | Nothing: `onLongPress` is null for Bluetooth-only rows (`HostsUi.kt:444`) (reconnect-D1, same root as bt-quirks-D1) | faked: same |
Postconditions: selection still `bt:AA:00:00:00:00:01`.

### `reconnect.mac-forgot`: the Mac removed the bond, the phone kept it (artboard j4-s3 caption "The Mac forgot this phone")
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | Mac user | System Settings › Bluetooth › forget the phone | macOS drops the bond; Android keeps it | manual: real Bluetooth bond + macOS window |
| B2 | system | `tryReconnect` (selected is still bonded on Android) | Pill "MacBook Pro" pulsing, row "Searching…", 3 attempts then silence; never "Not paired: pair again" (reconnect-D4) | manual |

### `reconnect.helper-network`: helper Mac, phone network lost and back (from S1 with a helper Mac)
Preconditions delta: one saved, key-trusted Mac "H1" (created by the `wifi-helper` main path against H1 with `TestSeam.proximity`), `selection = ws:<H1 hostId>`, `wifi_chosen = true`, no `btAddress`. FakeTransport not installed for `Net.bt` (or `Caps.HID`, unused).
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | phone user | Launch Mote | Helper stderr `[mote] labs: trusted <Phone> via trusted` then `[mote] authed <Phone> via Wi-Fi from <peer>`; pill label `<H1 name>`, badge `WI-FI`, LED linkOk; contentDescription `Device: <H1 name> via Wi-Fi. Tap to choose` | faked: the silent reconnect right after pairing stands in for a cold launch (the app process cannot be restarted under instrumentation); `<Phone>` = `sdk_gphone64_arm64` |
| B2 | system | `adb shell svc data disable` and `adb shell svc wifi disable` (both: with mobile data on, the emulator still reaches 10.0.2.2) | Within ≤ 40 s (OkHttp ping 20 s + failure) status Connecting: pill LED linkWait pulsing, route badge from saved routes; Media caps disabled | real; exact detection time UNDEFINED (OkHttp ping failure timing) |
| B3 | phone user | Tap the pill | Row status "Connecting…"; after ≥ 1.5 s a danger line from `reachNote`: if the probe's connect throws `ConnectException` (offline: ENETUNREACH on Android; confirmed on the emulator) "Mote Helper isn't running on <H1 name>. Open it from Applications on the Mac." (reconnect-D5), on timeout "<H1 name> seems asleep or away. Wake it (open the lid or press a key) and keep it on this Wi-Fi or Tailscale." | real; which Reach the emulator reports offline is UNDEFINED (verify) |
| B4 | system | `adb shell svc wifi enable` | `registerDefaultNetworkCallback.onAvailable` → `kick()` skips the backoff (≤ 8 s + 25 % jitter otherwise); helper `[mote] authed <Phone> via Wi-Fi from <peer>`; pill linkOk; row "Connected · Wi-Fi" | real |
Postconditions: selection unchanged; H1 has one authed session.

### `reconnect.background`: app leaves the screen (from helper-network B1)
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | phone user | Press Home | `Net.foreground = false`; still `WsStatus.Ready` at 29 s; 30 s after leaving, `WsTransport.stop()`: socket closed, `WsStatus.Idle`. Helper: session ends (no log line for a normal close) | real (asserted on the app's `Net.wsStatus`; no helper line) |
| B2 | phone user | Reopen Mote (after > 30 s; `am start` of MainActivity) | `ws.start(); ws.kick()`: helper `[mote] authed <Phone> via Wi-Fi from <peer>` again; pill `Device: <H1 name> via Wi-Fi. Tap to choose` | real |

### `reconnect.fallback-bt`: helper unreachable 15 s, Mac linked to a Bluetooth host (doc pairing.md §5; not drawn)
Preconditions delta: as helper-network, plus `BLUETOOTH_*` granted, `TestSeam.adapter` with host `AA:00:00:00:00:01` named exactly `<H1 name>` and `TestSeam.transport = FakeTransport(Caps.HID)` at Connect time, so `InstantPairing.adopt` links `SavedHost.btAddress` (the Connect card then shows "Connected", no Bluetooth offer).
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | system | Make H1 unreachable without losing its state: `kill -STOP <H1 pid>` | After the socket fails (OkHttp ping, ≤ ~40 s), status Connecting; 4 s later `rediscover` sweeps the /24 once (no new address); 15 s later (`FALLBACK_MS`) `Net.select(Selection.Bt(AA:00:00:00:00:01))`: pill contentDescription `Device: <H1 name> via BT. Tap to choose`, Hid selected host = the linked one | faked: SIGSTOP on the dry-run helper + adapter seam; BT link itself faked |
| B2 | system | `kill -CONT <H1 pid>` | Within ≤ 30 s (`PROBE_MS`, foreground only) `Net.probe` sees `hello.hostId == H1` → `Net.select(Selection.Helper(H1))`; helper `[mote] authed <Phone> via Wi-Fi from <peer>`; pill badge `WI-FI` (+ `BT` badge only if Hid reports the linked host connected) | faked: SIGCONT |
Postconditions: `selection = ws:<H1>`; `fallbackFrom` cleared.

### `reconnect.rediscover`: the Mac's LAN IP changed (DHCP) and Bonjour is off (doc pairing.md §5; not drawn)
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | system | Saved `lan` and trust-record BLE-info addresses don't answer for 4 s (`REDISCOVER_MS`) | One sweep of the Wi-Fi /24 on the saved port; the address answering `hello.hostId == H1` is added (`Net.addAddress`), socket reconnects; helper `[mote] authed …` | faked: needs a new seam to seed a SavedHost / TrustRecord with a dead `lan` (the dev Mac stays at 10.0.2.2; info `lan` IPs of the Mac also reach it through emulator NAT, UNDEFINED) |

### `reconnect.choose-helper`: pick a found helper from Devices (artboard j4-s2 row "Paired over Bluetooth · Mote Helper found")
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | phone user | Tap the pill while a helper named like a bonded host is found | Row with that name, status "Paired over Bluetooth · Mote Helper found", badges `WI-FI` `BT` | faked: needs a Discovery seam to inject a `Found` whose `service` equals an adapter host name (the dry-run helper's name is the dev Mac's computer name; Bonjour is off) |
| B2 | phone user | Tap the row | `defaultSelection` is Bluetooth (not saved), sheet closes, pill that host via `BT`. The helper is only reachable via "Add a Mac" (FindMacPanel); tapping the row never pairs it | faked: same |

### `reconnect.forget-helper`: long-press › Forget a helper Mac (artboard j4-s4)
Preconditions delta: as helper-network B1.
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | phone user | Tap the pill, long-press row `Use <H1 name>` | Inline row under it (not a dialog): text "Forget <H1 name> on this phone?", then "Forget" (danger), then "Cancel" (reconnect-D6) | real |
| B2 | phone user | Tap "Cancel" | Inline row gone; nothing changed | real |
| B3 | phone user | Long-press again, tap "Forget" | `Net.forget`: Vault entry and SavedHost removed, socket stopped, `selection` removed; the InstantPairing trust record stays. The sheet and remote are replaced at once by FindMacScreen ("Connect to your Mac", then "Connect to <H1 name>" from the sweep): `startScreen` → FIND_MAC because `wifi_chosen` and no helper host (reconnect-D7); helper still trusts the phone (no helper log) | real |
Postconditions: `hosts` empty; `labs_pairing/trust` still has H1; H1 trusted list unchanged; FindMacScreen shown.

## Disagreements → DEFECTS.md
- `reconnect-D1` (unpaired B3): Bluetooth-only rows can't be long-pressed / forgotten, though the artboard and stale-pairing copy say to.
- `reconnect-D2` (unpaired B2): artboard note "The Mac no longer knows this phone. Long-press it to forget, then pair again." is not in code for Bluetooth.
- `reconnect-D3` (signal-lost B2): after 3 attempts the pill and notification keep saying searching while nothing is tried.
- `reconnect-D4` (mac-forgot): a Mac-side unpair is never detected; "Not paired" needs the phone-side bond to go.
- `reconnect-D5` (helper-network B3): an offline phone may be told "Mote Helper isn't running".
- `reconnect-D7` (forget-helper B3): forgetting the last Mac drops the user straight into FindMacScreen; the artboard says pair again from Devices.
- `reconnect-D6` (S1, forget-helper B1): artboard Searching card, "Add Mac over Wi-Fi" / "Enter address" buttons and the Forget dialog differ from code.

## UNDEFINED
- `<Phone>` name sent in the handshake (emulator `DEVICE_NAME`), `<peer>` as the helper sees it through emulator NAT.
- Time for OkHttp to declare a dead socket after the network goes (ping 20 s; failure detection not bounded in code).
- Whether Android reports ENETUNREACH / EHOSTUNREACH as `ConnectException` (→ REFUSED) on API 35; decides helper-network B3 copy.
- Real Bluetooth HID reconnect timing and `connect()` outcome: needs a real phone and Mac.
