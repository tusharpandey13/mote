# recovery: F. Recovery and management

Artboard: `design/mote-v1/journey-recovery.html` (Shipped). Code: helper `Hub.swift` (revokeAll 86-94, paused 25-33, input gate 98-100, Session.revoke 213-217), `InstantPairing.swift` (hello trust order 151-191, accept 198-212, block/unblock 215-240, revokeAll 341-344, TrustStore.revokeAll 476-480), `MenuBar.swift` (status 68-70, Trusted phones 113-138, Pause / Revoke items 159-160, revokeAll alert 208-218, showNewDevice 236-284), `main.swift:56-80` (stdin-control); Android `net/WsTransport.kt` (revoked / auth → Failed, 115-131), `pairing/InstantPairing.kt` (reconnect 162-167, watchForFallback 170-186, probeBack 189-201), `pairing/Handshake.kt:60-70`, `ui/HostsUi.kt` (HostPill 96-109, LIST note 257-263, HostRowCap status 424-439, failedText 485-490), `pairing/ConnectUi.kt` (FindMacScreen 64-84, FindMacPanel `fresh` 104, pairFailure 204-218), `pairing/FirstRun.kt`, `ui/App.kt` (BtGateScreen 334-362, startScreen 192-201), `hid/Bt.kt` (gateOf). Docs: `docs/pairing.md` §4 (line 101), §5 (line 118), §6 "Lost phone", `docs/protocol.md` (error codes line 49, security line 201), `docs/design/design-language.md` §User journeys.
Test: `e2e/journeys` → `dev.mote.remote.journeys.RecoveryJourney` (one @Test per path below).

## Preconditions
- P1. App: debug `dev.mote.remote.dev`, one saved key-trusted Mac "H1" (created by the `wifi-helper` main path against H1 with `TestSeam.proximity`), `selection = ws:<H1 hostId>`, `wifi_chosen = true`, trust record in DataStore `labs_pairing`, `SavedHost.btAddress = null`. `android.permission.BLUETOOTH_*` not granted unless a branch says so.
- P2. Phone: Wi-Fi on ("AndroidWifi", 10.0.2.17/24), no Tailscale VPN; Bluetooth adapter real (unused) unless a branch fakes it.
- P3. Mac / helper: H1 = `MoteHelper --dry-run --headless --no-ble --memory-store --port N1 --stdin-control --fake-proximity`, instant pairing on (default), Require approval off, not paused, phone trusted (`via nearby`). Kept running for the whole test (memory store).
- P4. Network: same LAN (phone → 10.0.2.2:N1); `TestSeam.noBonjour = true`, `TestSeam.sweepPort = N1`.

## Main path `recovery`: lost phone → Revoke all → pair again (artboard jF-s1, jF-s2)
| # | Actor | Action (exact) | Expected observable (exact) | Seam |
|---|---|---|---|---|
| S1 | phone user | Launch Mote | Helper stderr `[mote] labs: trusted <Phone> via trusted`, `[mote] authed <Phone> via Wi-Fi from <peer>`. Pill label `<H1 name>`, badge `WI-FI`, LED linkOk | real |
| S2 | Mac user | Menu "Revoke all devices…" → alert "Revoke all devices?" / "Every paired phone and browser is disconnected and has to pair again. Blocked phones are unblocked." → "Revoke All" | stdin-control `revoke` stands in. Helper stderr `[mote] all devices revoked`. Phone receives `{"t":"err","code":"revoked","msg":"This device was revoked on the Mac"}`, socket closed | faked: stdin `revoke` (alert is headless-unobservable; recovery-D1 for its copy) |
| S3 | system | `WsTransport` → `WsStatus.Failed("revoked", …)`; the loop stops (no retry while foreground) | Pill label `<H1 name>` (not "Not paired: pair again"), badge `WI-FI`, LED linkBad, no pulse; contentDescription `Device: <H1 name> via Wi-Fi. Tap to choose`. Media caps disabled (recovery-D2) | real |
| S4 | phone user | Tap the pill | LIST note (body) "The Mac no longer trusts this phone. Long-press it to forget, then Add a Mac again."; row `Use <H1 name>` status the same sentence; badge `WI-FI`; button "Add a Mac" (recovery-D3) | real |
| S5 | phone user | Long-press row `Use <H1 name>`, tap "Forget" | Row gone; pill "Choose a device", LED linkBad. Trust record for H1 stays on the phone | real |
| S6 | phone user | Tap the pill, tap "Add a Mac" | Header "Add a Mac"; FindMacPanel "Looking for your Mac…" / "Searching this Wi-Fi, Bluetooth and Tailscale."; then the sweep finds H1: Glyph, title `<H1 name>`, button "Connect to <H1 name>" | real (sweep on `sweepPort`) |
| S7 | phone user | Tap "Connect to <H1 name>" | "Connecting…"; phone sends `labs.pair.hello` with `prox`; helper stderr `[mote] labs: trusted <Phone> via nearby`, `[mote] labs: new device <Phone> via nearby (k:<fp>)`, stdout `NEW DEVICE: {"id":"k:<fp>","name":"<Phone>","via":"nearby"}`, `[mote] authed <Phone> via Wi-Fi from <peer>`; card "Connected over Wi-Fi" + BtStep offer when `Bt.snapshot().supported`, else "Connected" then the sheet closes after 900 ms | faked: `TestSeam.proximity` (helper `labs.pair.prox`) |
| S8 | system | — | Pill `<H1 name>`, badge `WI-FI`, linkOk | real |

## Postconditions
- Phone: `hosts` = [H1 (new deviceId `k:<fp>`, `wrapped = null`)], `selection = ws:<H1>`, trust record for H1 rewritten. Helper: trusted list = [<Phone> · nearby], blocked list empty, code-paired store empty.

## Branches
### `recovery.revoked-relaunch`: revoked phone reopens the app without forgetting (from S3)
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | phone user | Force-stop, relaunch | `WsTransport` retries once per start: helper `[mote] labs: denied (far) <peer>`; phone maps `far` to `auth` (`InstantPairing.kt:166`) → Failed("auth"); same pill / note as S3-S4 | real |
| B2 | phone user | Tap pill › "Add a Mac" without forgetting first | FindMacPanel filters out saved Macs (`fresh`, `ConnectUi.kt:104`): "Looking for your Mac…" then after 5 s "Mote Helper not found on this network" / "Install it on the Mac (Terminal), keep it running, and use the same Wi-Fi:" (recovery-D4) | real |
| B3 | system | (H1 run with `--fake-tailscale-owner self`) relaunch | The revoked phone is silently re-trusted: `[mote] labs: fake whois <peer> owner=self`, `labs: trusted <Phone> via tailscale`, `NEW DEVICE: …"via":"tailscale"` | faked: `--fake-tailscale-owner self` (by design, docs/pairing.md §4) |

### `recovery.revoked-fallback`: revoked phone with a linked Bluetooth host (from S3; not drawn)
Preconditions delta: `SavedHost.btAddress` set (Bluetooth adapter faked with a host named `<H1 name>`, `BLUETOOTH_CONNECT/ADVERTISE/SCAN` granted, `TestSeam.transport = FakeTransport(Caps.HID)`).
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | system | 15 s after Failed (`watchForFallback` treats Failed like unreachable) | `Net.select(Selection.Bt(<bt>))`: pill `<H1 name>` badge `BT`; with a real bond the lost phone keeps controlling the Mac over Bluetooth HID | faked: adapter + FakeTransport |
| B2 | system | 30 s probe (`probeBack`: only checks `hello.hostId`) | Switches back to `ws:<H1>` → Failed again → 15 s → BT: an endless ~45 s cycle; helper logs `[mote] labs: denied (far) <peer>` each cycle (recovery-D5) | faked: same |

### `recovery.block`: Block one phone from the connect notice (artboard jF-s3)
Preconditions delta: run the main path S6-S7 first (so the phone is the helper's `lastNew`).
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | Mac user | Floating panel "Mote": "<Phone> connected to Mote." / "via Bluetooth proximity", click "Block" | stdin-control `block` stands in. Helper stderr `[mote] labs: blocked k:<fp>`; phone gets `err revoked` | faked: stdin `block` (panel is headless-unobservable; not shown with `--headless`) |
| B2 | system | Phone | Same as S3-S4 ("The Mac no longer trusts this phone. Long-press it to forget, then Add a Mac again.") | real |
| B3 | phone user | Forget (S5), "Add a Mac", "Connect to <H1 name>" | Helper `[mote] labs: denied (blocked) <peer>`; card danger text "<H1 name> blocked this phone. Unblock it with Revoke all devices in the Mote Helper menu." + "Try again" (recovery-D6) | real + `TestSeam.proximity` |
| B4 | Mac user | Trusted phones › Blocked › "Unblock" | stdin-control `unblock`: `[mote] labs: unblocked k:<fp>` | faked: stdin `unblock` |
| B5 | phone user | Tap "Try again" | Trusted again via nearby (S7 lines) | faked: `TestSeam.proximity` |

### `recovery.revoke-unblocks`: Revoke all clears the blocked list (not drawn)
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | Mac user | After recovery.block B1, Revoke all | stdin `revoke` then `unblock`: `[mote] labs: unblock: nothing blocked` (the blocked list was cleared by `TrustStore.revokeAll`) | faked: stdin |
| B2 | phone user | Forget, Add a Mac, Connect (with proximity) | Trusted again, `NEW DEVICE` printed: the blocked phone is back (recovery-D7) | faked: `TestSeam.proximity` |

### `recovery.code-paired`: a phone paired with a 6-digit code (token) is revoked (not drawn)
Preconditions delta: H1 also `--open-pairing`; phone paired via Devices › More options › "Pair with code" (SavedHost with Vault token).
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | Mac user | stdin `revoke` | `err revoked` → same S3-S4. Relaunch: helper `[mote] auth failed from <peer>`, phone Failed("auth") with the same copy | real |

### `recovery.pause`: Pause input (not drawn on F; coverage table lists "Paused on the Mac" under F)
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | Mac user | Menu "Pause input" (⌘P) | stdin `pause`: `[mote] input paused`; phone gets `{"t":"state","paused":true,"ax":true}`; pill LED linkWait (no pulse); row "Paused on the Mac". Menu status "Mote Helper: Paused: input is ignored" (headless-unobservable) | faked: stdin `pause` |
| B2 | phone user | Tap "Play or pause" | Phone sends `{"t":"consumer",...}`; helper answers `err paused` (no log, no `DRY` line); phone shows nothing (non-Labs errors are dropped) | real |
| B3 | Mac user | Menu "Pause input" again | stdin `resume`: `[mote] input resumed`; pill linkOk; row "Connected · Wi-Fi" | faked: stdin `resume` |

### `recovery.ax-revoked`: Accessibility revoked on the Mac (artboard jF-b3s)
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | Mac user | Remove Mote Helper from Privacy & Security › Accessibility | Within ≤ 2 s (poll) phone gets `{"t":"state","paused":false,"ax":false}`; pill LED linkWait; row "Mote Helper needs Accessibility on the Mac"; LIST note "Mote reconnects to the selected device while the app is open."; input answered with `err ax` and dropped silently. Menu: "Mote Helper: Needs Accessibility permission", "Grant Accessibility…" | faked: needs new flag `--fake-no-ax` (dry-run forces `inputReady = true`, `Hub.swift:62`); TCC itself is needs user |

### `recovery.reinstall`: reinstalled Mote or cleared data (artboard jF-s4)
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | phone user | `adb shell pm clear dev.mote.remote.dev`, launch | `startScreen` → FIND_MAC (not the Bluetooth gate): "Connect to your Mac", "Over Wi-Fi or Tailscale, through the free Mote Helper on the Mac.", "New phone, or reinstalled Mote? Let's reconnect: tap Connect, nothing changes on the Mac.", FindMacPanel, "Use Bluetooth instead (no helper)" (recovery-D8) | real |
| B2 | phone user | "Connect to <H1 name>" | If the Keystore identity key was wiped: helper `[mote] labs: new device <Phone> via nearby (k:<fp2>)` + `NEW DEVICE: …`, old key still in Trusted phones until Revoke all. If it survived: `[mote] labs: trusted <Phone> via trusted`, no NEW DEVICE | faked: `TestSeam.proximity`; which case applies is UNDEFINED |

### `recovery.nearby-revoked`: Nearby devices revoked mid-use (artboard jF-b1s)
Preconditions delta: Bluetooth chosen: `selection = bt:<addr>`, `BLUETOOTH_*` granted, adapter faked enabled with that host.
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | phone user | `adb shell pm revoke dev.mote.remote.dev android.permission.BLUETOOTH_CONNECT` (Android kills the process), relaunch | BtGateScreen: "How do you want to connect?", "Mote works as a keyboard, mouse and media remote for your computer. Allow Nearby devices so it can pair and connect, then turn Bluetooth on.", button "Connect with Bluetooth", button "Connect to your Mac instead", "Over Wi-Fi or Tailscale with the free Mote Helper on the Mac (tusharpandey13.github.io/mote). Adds Unicode typing, precise brightness and click-at-point." (recovery-D8) | real (+ adapter seam) |
| B2 | (variant) | Same with the helper Mac selected (P1) | Remote stays (`hasHelperHost && !btSelected` → REMOTE); helper link unaffected; Devices shows button "Set up Bluetooth" | real |

### `recovery.bt-off`: Bluetooth turned off mid-session (artboard jF-b2s)
Preconditions delta: as nearby-revoked.
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | system | Adapter `enabled = false` | BtGateScreen: "How do you want to connect?", "Mote works as a keyboard, mouse and media remote for your computer. Bluetooth is off; turn it on to continue.", "Turn on Bluetooth", "Connect to your Mac instead" + helper line (recovery-D8) | faked: `TestSeam.adapter` |
| B2 | phone user | Tap "Turn on Bluetooth" | `ACTION_REQUEST_ENABLE` system dialog | faked adapter: dialog acts on the real adapter (UNDEFINED with a fake) |

### `recovery.lost-keystore`: maintainers lose the signing key (artboard jF-s5)
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | phone user | Install an APK signed with another key over the installed one | Android installer refuses (package conflict); nothing in Mote code | manual: platform installer, not app code |

## Disagreements → DEFECTS.md
- `recovery-D1` (S2): Revoke alert copy: artboard "…has to pair again with a new code." vs code "…has to pair again. Blocked phones are unblocked."
- `recovery-D2` (S3): artboard pill "Not paired: pair again" (Bluetooth copy) vs code: Mac name, red LED.
- `recovery-D3` (S4): artboard note "The Mac no longer knows this phone… then pair again." and buttons "Add Mac over Wi-Fi" / "Enter address" vs code copy / "Add a Mac" / "More options".
- `recovery-D4` (revoked-relaunch B2): "Add a Mac" hides a saved-but-revoked Mac and says "Mote Helper not found on this network".
- `recovery-D5` (revoked-fallback): refusal triggers the 15 s Bluetooth fallback and a probe-back loop; a revoked / blocked phone keeps control over Bluetooth HID.
- `recovery-D6` (block B3): "blocked" copy sends the user to Revoke all although Trusted phones › Unblock exists.
- `recovery-D7` (revoke-unblocks): Revoke all also clears the blocked list.
- `recovery-D8` (reinstall, nearby-revoked, bt-off): artboard shows the Bluetooth gate with "Use Wi-Fi with Mote Helper" / "Needs the free Mote Helper app…"; code: FindMacScreen on reinstall and "Connect to your Mac instead" / "Over Wi-Fi or Tailscale…" on the gate (same root as setup-phone-D1/D2).
- `recovery-D9` (ax-revoked): Accessibility-revoked state unreachable in dry-run (no `--fake-no-ax`).

## UNDEFINED
- `<Phone>`, `<peer>`, `<fp>` values (emulator name, NAT source address, phone key fingerprint).
- Whether `pm clear` on API 35 deletes the app's Android Keystore Ed25519 / HMAC keys (decides reinstall B2).
- macOS Accessibility polling latency in practice (code polls every 2 s); Android installer copy for a signature conflict.
