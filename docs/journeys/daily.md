# daily: E. Daily use and reconnect

Artboard: `design/mote-v1/journey-daily.html` (Shipped). Code: `android/.../ui/App.kt`, `ui/HostsUi.kt` (HostPill, HostSheet, HostRowCap, failedText), `net/Net.kt` (select/apply/foreground), `net/WsTransport.kt` (run loop, backoff), `net/Hosts.kt` (candidates, routes), `pairing/InstantPairing.kt:152-230` (trusted-host paths, fallback, rediscover), `pairing/Sweep.kt:130-147` (tcpReach, reachNote), `service/HidService.kt` (link notification), `MainActivity.kt:35-46`; helper `Hub.swift` (paused, input gate, markAuthed log), `main.swift` (stdin-control), `MenuBar.swift:68-70,159`. Docs: `docs/pairing.md` §Reconnect (line 118), `android/README.md:154`, `docs/design/design-language.md` §User journeys.
Test: `e2e/journeys` → `dev.mote.remote.journeys.DailyJourney` (one @Test per path below).

## Preconditions
- P1. App: debug `dev.mote.remote.dev`. One saved Mac "H1" created by the `wifi-helper` journey main path (key-trusted record, DataStore `hosts` + `InstantPairing` record), `selection = ws:<hostId H1>`, `wifi_chosen = true`. No Bluetooth link (`SavedHost.btAddress = null`). `android.permission.BLUETOOTH_CONNECT` NOT granted unless a branch says so (then `HidService` runs, see daily.notif). App force-stopped before S1.
- P2. Phone: Wi-Fi on ("AndroidWifi", 10.0.2.17/24), Bluetooth irrelevant, no Tailscale VPN.
- P3. Mac / helper: H1 = `MoteHelper --dry-run --headless --no-ble --memory-store --port N1 --stdin-control` (+ instant-pairing fakes the `wifi-helper` setup needed, e.g. `--fake-proximity`), kept running for the whole test (memory store: a restart forgets the phone). Not paused.
- P4. Network: same LAN (phone → 10.0.2.2:N1). Bonjour may be off (`TestSeam.noBonjour`); the saved `lan` address is used.

## Main path `daily`
| # | Actor | Action (exact) | Expected observable (exact) | Seam |
|---|---|---|---|---|
| S1 | phone user | Launch Mote (`am start -n dev.mote.remote.dev/dev.mote.remote.MainActivity`) | Remote opens directly (no FindMacScreen: `startScreen` → REMOTE because `hasHelperHost && !btSelected`). Media screen; mode rail "Media" selected. Pill contentDescription `Device: <H1 name> via Wi-Fi. Tap to choose` (route from the saved LAN route before Ready). No test tag. | real (`FirstRun.kt:15`, `HostsUi.kt:77-127`) |
| S2 | system | `Net.foreground = true` → `WsTransport.start()`; candidates tried in order (trusted host: Bonjour, BLE-info LAN, saved `lan`, `<local>.local`, manual LAN, tailnet names, tailnet IPs; `Paths.kt:18-27`) | Helper stderr `[mote] authed <phone device name> via Wi-Fi from <peer>`; phone gets `{"t":"authed",...,"paused":false,"ax":true}` then sends `{"t":"spaces.sub","on":true}` | real (dry-run). `<peer>` = source IP as the Mac sees it: UNDEFINED for emulator NAT |
| S3 | system | (WsStatus.Ready) | Pill LED linkOk (no semantics), label `<H1 name>`, badge `WI-FI`; contentDescription `Device: <H1 name> via Wi-Fi. Tap to choose`. Media caps enabled (LocalLinked = true) | real (LED colour: screenshot only) |
| S4 | phone user | Tap the pill | MoteSheet with HostSheet LIST: title "Devices", note "Mote reconnects to the selected device while the app is open.", row cap contentDescription `Use <H1 name>`, status text "Connected · Wi-Fi", badge `WI-FI` (lit). Buttons "Add a Mac", link "More options", button "Set up Bluetooth" (gate NEEDS_PERMISSION; "Turn on Bluetooth" if BT off; "Pair new Bluetooth device" only when READY, `HostsUi.kt:312-327`) | real |
| S5 | phone user | Dismiss sheet (swipe down / back) | Sheet gone; `Net.discovery.stop()` | real |

## Postconditions
- Phone: `selection = ws:<H1>` unchanged; `SavedHost.lan` refreshed by `onReady` (`Net.kt:205-208`). Helper: one authed session for the phone.

## Branches
### `daily.asleep`: Mac asleep / out of range (from S2; artboard step jE-b1s)
Preconditions delta: H1's saved addresses don't answer and don't refuse (TCP timeout). Faked: needs a new seam to seed a SavedHost whose `lan` is a non-answering address (no way to sleep the dev Mac; SIGSTOP of the helper still completes TCP handshakes → Reach.OPEN).
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | system | Launch; WsTransport loops (backoff 0.5 s → 8 s, jittered, `WsTransport.kt:140-142`) | Pill LED linkWait pulsing (WsStatus.Connecting), contentDescription `Device: <H1 name> via Wi-Fi. Tap to choose`; Media caps dimmed and rejecting (Cap `needsLink`, haptic REJECT) — taps produce no helper line | faked: needs SavedHost seeding seam |
| B2 | phone user | Tap the pill | Row status "Connecting…"; after ≥ 1.5 s + probe (≤ 1.5 s per address, `HostsUi.kt:264-277`): danger text `<H1 name> seems asleep or away. Wake it (open the lid or press a key) and keep it on this Wi-Fi or Tailscale.` | faked: same |
| B3 | system | Address answers again (seam restores the real `lan`) | Within ≤ 8 s + jitter (or at once on `ConnectivityManager` onAvailable / app foreground `kick()`): S2–S3 | faked: same |
Postconditions: as main path.

### `daily.nohelper`: helper not running (from S2; artboard jE-b2s)
Preconditions delta: H1 process stopped (SIGTERM) after P1 setup. Note: memory store, so a restarted H1 no longer trusts the phone (that recovery is the `recovery` journey); B3 restarts nothing.
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | system | Launch; connect attempts fail | Pill LED linkWait pulsing; no helper line | real |
| B2 | phone user | Tap the pill | Row status "Connecting…" (not "Searching…"); after ~1.5 s, if the probe gets REFUSED: danger text `Mote Helper isn't running on <H1 name>. Open it from Applications on the Mac.`; if TIMEOUT: the daily.asleep copy | real; which Reach the emulator NAT reports for a closed host port is UNDEFINED (verify once) |
Postconditions: selection unchanged; WsTransport keeps retrying every ≤ 8 s while foreground.

### `daily.paused`: input paused on the Mac (from S3; artboard jE-b3s)
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | Mac user | Menu › Pause input | Helper stderr `[mote] input paused`; phone gets `{"t":"state","paused":true,"ax":true}` | faked: stdin-control `pause` (menu is headless-unobservable: title "Mote Helper: Paused: input is ignored") |
| B2 | system | — | Pill LED linkWait (not pulsing); Media caps stay enabled (still linked) | real |
| B3 | phone user | Tap the pill | Row status "Paused on the Mac", badge `WI-FI` | real |
| B4 | phone user | Dismiss; tap cap "Play or pause" | Phone sends `{"t":"consumer","key":"play"}`; helper replies `{"t":"err","code":"paused","msg":"input is paused on the Mac","ref":"consumer"}`; NO `[mote] DRY consumer play` line; phone shows nothing (err ignored, `WsTransport.kt:120-121`) | real |
| B5 | Mac user | Menu › Pause input (uncheck) | `[mote] input resumed`; `state paused:false`; pill LED linkOk; row "Connected · Wi-Fi". Nothing from B4 is replayed | faked: stdin-control `resume` |

### `daily.switch`: switch Macs (from S4; artboard jE-s3, jE-s4)
Preconditions delta: second helper H2 `MoteHelper --dry-run --headless --no-ble --memory-store --port N2` also trusted by the phone (second `wifi-helper` run). Both helpers report the dev Mac's computer name, so rows can't be "MacBook Pro" / "Mac mini": faked: needs new flag `--fake-host-name NAME`. Without it the rows have the same name and differ only by hostId order (saved order).
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | phone user | Tap the pill | Two rows: `Use <H1>` "Connected · Wi-Fi"; `Use <H2>` "Paired" (or "Nearby" if Bonjour found it) | faked: names need `--fake-host-name` |
| B2 | phone user | Tap row `Use <H2>` | Sheet closes; `selection = ws:<H2>`; H1 socket closed (no H1 log line exists for a close); H2 stderr `[mote] authed <phone> via Wi-Fi from <peer>` | real |
| B3 | system | — | Pill `Device: <H2 name> via Wi-Fi. Tap to choose`, LED linkOk. H1 row status "Paired" | real |

### `daily.background`: app leaves the screen (from S3; artboard jE-s2 caption)
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | phone user | Press Home | `Net.foreground = false`; after 30 s `ws.stop()` closes the socket (`Net.kt:161-169`); Wi-Fi low-latency lock released | real (no helper log for a close; assert via helper session count: UNDEFINED headless, needs a log or stdin `status` command) |
| B2 | phone user | Reopen Mote within 30 s | Same socket kept; no new `authed` line | real |
| B3 | phone user | Reopen Mote after > 30 s | New `[mote] authed <phone> via Wi-Fi from <peer>` | real |

### `daily.tailnet`: off Wi-Fi, tailnet route (from S1; artboard jE-s2 TAILSCALE badge)
Preconditions delta: H1 saved with a tailnet address; phone Wi-Fi off and Tailscale VPN up. Emulator has no Tailscale: faked: needs fake Tailscale-VPN state (`Net.tailnetUpFlow`, `onWifiFlow`) + a tailnet-routable address for the helper + `--allow-tailscale`.
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | phone user | Launch | Before Ready: pill badge `TAILSCALE` (`!onWifiNow && TAILNET in routes`); after Ready: badge from the answering address | faked: as above |
| B2 | system | VPN off as well | Row tap shows the "Tailscale is off" sheet (`tailscale` journey) | faked |

### `daily.notif`: link notification (from S1; artboard jE-s1 "Notification: Searching for MacBook Pro…")
Preconditions delta: `BLUETOOTH_CONNECT` granted (else `HidService.start` is a no-op and there is no notification at all).
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | system | Launch (helper host selected, no BT link) | Ongoing notification, channel "Bluetooth link", title "Mote", text "Ready to pair" (Hid has no selected host), actions "Play/Pause", "Stop" — even while Wi-Fi is Connected | real (UiAutomator notification shade) |
| B2 | system | Variant: a Bluetooth host selected (FakeTransport + `TestSeam.adapter` bonded host "MacBook Pro") | text "Searching for MacBook Pro…"; when the BT host connects: "Connected to MacBook Pro" | faked: BT HID (FakeTransport; Hid never reports connected under the seam, so "Connected to …" is manual: real BT HID bond) |

## Disagreements → DEFECTS.md
- `daily-D1` (daily.notif): notification reflects only Bluetooth HID; with a Wi-Fi Mac it says "Ready to pair" or doesn't exist.
- `daily-D2` (daily.asleep/nohelper): code shows Mac-asleep / helper-not-running copy; artboard jE-b1s says "no Mac-asleep copy", design-language "Not in code" list says the same.
- `daily-D3` (daily.nohelper B2): artboard jE-b2s shows row "Searching…" and "The Mac didn't answer. Is Mote Helper running?"; code shows "Connecting…" and the reachNote copy.
- `daily-D4` (daily.asleep): artboard jE-b1s "Searching card" (copy "Mote reconnects on its own while it's open. Wake MacBook Pro or bring it in range. Nothing you press is sent until then." + "Choose device") doesn't exist in code.
- `daily-D5` (S4, jE-s3): Devices sheet buttons "Add Mac over Wi-Fi" / "Enter address" vs code "Add a Mac" / "More options" (→ "Pair with code", "Enter address") + "Connected over Bluetooth but buttons do nothing?".
- `daily-D6` (S2, jE-s2): route order caption "Bonjour › LAN › name.local › tailnet › manual" is only the code-paired order; trusted hosts (0.6.0 default) put a manual LAN address before tailnet and add BLE-info addresses; doc pairing.md adds a 4 s sweep and a 15 s Bluetooth fallback the artboard doesn't show.

## UNDEFINED
- S2/B2 `<peer>` text: which source address the Mac sees for emulator traffic to 10.0.2.2.
- daily.nohelper B2: whether the emulator NAT reports REFUSED or TIMEOUT for a closed host port.
- daily.background B1: no helper log marks a session close; socket close is only observable phone-side (WsStatus Idle) or by a new helper hook.
- LED colours (linkOk / linkWait / linkBad) have no semantics; screenshot comparison only.
