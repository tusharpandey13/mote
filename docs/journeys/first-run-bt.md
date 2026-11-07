# first-run-bt: 1. First run (Wi-Fi + Bluetooth)

Artboard: `design/mote-v1/journey-first-run-bt.html` (Next (0.6.0)). Code: `android/.../ui/App.kt` (Root, `startScreen` call, BtGateScreen), `pairing/FirstRun.kt`, `pairing/ConnectUi.kt` (FindMacScreen, FindMacPanel, ConnectCard, BtStep), `pairing/BtSetup.kt`, `pairing/InstantPairing.kt` (`connect`, `adopt`), `net/Net.kt` (`adoptTrusted`, `linkBt`), `ui/HostsUi.kt` (HostPill, HostSheet Page.FIND), helper `BtPair.swift`, `InstantPairing.swift` (`accept`), `MenuBar.swift` (`showNewDevice`). Docs: `docs/pairing.md` §5 + "Bluetooth HID setup", `docs/design/design-language.md` (coverage rows 1-2).
Test: `e2e/journeys` → `dev.mote.remote.journeys.FirstRunBtJourney` (one @Test per path below). Connect-step failures (far, denied, blocked, Tailscale, Wi-Fi off, not found…) are journey `connect-next`, not repeated here.

## Preconditions
- P1. App: debug `dev.mote.remote.dev`, fresh (`pm clear`): no saved Macs, no trust records, no `BT_CHOSEN`/`WIFI_CHOSEN`; no runtime permission granted (`BLUETOOTH_CONNECT`, `BLUETOOTH_ADVERTISE`, `BLUETOOTH_SCAN`, `POST_NOTIFICATIONS`). `TestSeam.noBonjour = true`, `TestSeam.sweepPort = N`, `TestSeam.transport = FakeTransport`, `TestSeam.adapter = Adapter(supported = true, enabled = true)`.
- P2. Phone: emulator API 35 on "AndroidWifi" (10.0.2.17/24), no Tailscale VPN.
- P3. Mac / helper: `MoteHelper --dry-run --headless --no-ble --memory-store --port N --fake-proximity --fake-bt-pair` (instant pairing on, Require approval off, not paused, trusted list empty). `<Mac>` below = the helper's `hello.host` (its computer name, also `n` in `LABS PAIR INFO:`).
- P4. Network: same LAN (helper reachable at 10.0.2.2:N, found by the sweep of 10.0.2.0/24).

## Main path `first-run-bt`
What the code does today: the first connect switches the app to the remote at once, so the Bluetooth offer of artboard steps j1-s4..s6 is not shown on first run (see D1). The offer is reached via branch `first-run-bt.add-mac-bt`.
| # | Actor | Action (exact) | Expected observable (exact) | Seam |
|---|---|---|---|---|
| S1 | phone user | Launch Mote | FindMacScreen: "Connect to your Mac", "Over Wi-Fi or Tailscale, through the free Mote Helper on the Mac.", "New phone, or reinstalled Mote? Let's reconnect: tap Connect, nothing changes on the Mac.", "Use Bluetooth instead (no helper)". No permission dialog | real |
| S2 | system | (wait ≤ 5 s) | "Looking for your Mac…" / "Searching this Wi-Fi, Bluetooth and Tailscale." then ConnectCard: title "<Mac>", button "Connect to <Mac>" (contentDescription = label; no test tag) | faked: discovery = LAN sweep of 10.0.2.0/24 on port N (Bonjour off, no BLE peripheral) |
| S3 | phone user | Tap "Connect to <Mac>" | "Connecting…" | real |
| S4 | system | handshake with proximity token | helper stderr `[mote] labs: trusted <phone> via nearby` and `[mote] labs: new device <phone> via nearby (k:<fp>)`; stdout `NEW DEVICE: {"id":"k:<fp>","name":"<phone>","via":"nearby"}`; phone receives `{"t":"labs.pair.ok","via":"nearby","id":"k:<fp>","name":"<phone>"}` | faked: `TestSeam.proximity` (token from `{"t":"labs.pair.prox"}` on the dry-run helper, `--fake-proximity`) |
| S5 | system | `InstantPairing.adopt` → `Net.adoptTrusted` | FindMacScreen disappears immediately; remote (content description "Play or pause"); pill contentDescription "Device: <Mac> via Wi-Fi. Tap to choose", badge "WI-FI"; no "Also pair Bluetooth…" and no "Connected" text is shown | real |
| S6 | Mac (helper) | — | non-headless only: floating panel "<phone> connected to Mote." / "via Bluetooth proximity" / "Block", gone after 15 s | needs user: window (headless: S4 log/stdout stand in) |

## Postconditions
- Phone: saved host `<Mac>` (hostId from helper, address 10.0.2.2, port N, `btAddress` null), trust record (helper fp, via `nearby`), `WIFI_CHOSEN=true`, selection `Helper(<hostId>)`. No Bluetooth permission granted.
- Helper: trusted list = [<phone> via nearby]; `Trusted phones: 1` (menu, headless-unobservable).

## Branches
### `first-run-bt.add-mac-bt`: Also pair Bluetooth (from S5 via Devices; artboard j1-s4, j1-s5, j1-s6)
Preconditions delta: the Mac must not be saved yet (FindMacPanel hides saved Macs, `ConnectUi.kt:104`). Reach it by: main path S1-S5, then pill → Devices → long-press `<Mac>` row → "Forget" (or start from `first-run-bt.bt-only` B4). Bluetooth permissions granted.
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | phone user | Tap the pill, then "Add a Mac" | sheet header "Add a Mac"; FindMacPanel → ConnectCard "Connect to <Mac>" | faked: sweep port |
| B2 | phone user | Tap "Connect to <Mac>" | "Connecting…" then "Connected over Wi-Fi" + BtStep Offer: checkbox (checked) "Also pair Bluetooth for login screen & backup (recommended)", "Android asks twice: make the phone visible, then Pair. Nothing to do on the Mac.", primary "Continue", small "Skip" | real (`btOffer` = adapter supported; `btAddress` null). Helper: `labs: trusted <phone> via trusted` (key still trusted; no `NEW DEVICE:`) or `via nearby` if it was blocked/revoked |
| B3 | phone user | Tap "Continue" | permitted + enabled → `ACTION_REQUEST_DISCOVERABLE` (120 s) system dialog | real dialog if the emulator has a BT adapter, else `startActivity` fails and the code goes straight to B5 |
| B4 | phone user | Allow | `HidService.start`, `Hid.pausePairing(120000)`; "Asking <Mac> to pair…" | faked: adapter name read from the REAL `BluetoothManager.adapter.name` (`ConnectUi.kt:303`): needs new seam `TestSeam.adapter.name` |
| B5 | system | phone opens a 2nd WebSocket, silent handshake, sends `{"t":"bt.pair","name":"<phone BT name>"}` | helper `[mote] bt: pair '<phone BT name>' for <phone>`; status `{"t":"bt.pair.status","state":"searching"}` → "<Mac> is looking for this phone over Bluetooth…"; `{"t":"bt.pair.status","state":"pairing","address":"02:00:00:00:00:01"}` → "Tap Pair on this phone when Android asks. Nothing to do on the Mac."; `{"t":"bt.pair.status","state":"paired","address":"02:00:00:00:00:01"}` + log `[mote] bt: paired 02:00:00:00:00:01` | faked: `--fake-bt-pair` (no IOBluetooth, no Android pairing dialog) |
| B6 | system | `{"t":"bt.info"}` | reply `{"t":"bt.info","address":"02:00:00:00:00:00","name":"<computer name>","on":true}`; `Net.linkBt(hostId,"02:00:00:00:00:00")`; `Hid.select(02:00:00:00:00:00)`; text "Wi-Fi and Bluetooth are both set up." | faked: `--fake-bt-pair` |
| B7 | system | 1.5 s later | `onDone` = sheet closes; pill shows "WI-FI" + "BT" badges only once Hid reports `connected.address == 02:00:00:00:00:00` | faked: FakeTransport must report the HID host connected |
| B8 | Mac (helper) | — | no notice and no prompt on the Mac for the Bluetooth pair (code has none) | real (assert no new stdout line beyond B5 logs) |
Postconditions: saved host `btAddress = 02:00:00:00:00:00`; Hid selected host = that address; helper trusted list unchanged.

### `first-run-bt.skip-bt`: Skip Bluetooth (from B2; artboard j1-b2s)
| B1 | phone user | Tap "Skip" (or untick the checkbox → button becomes "Done", tap it) | sheet closes; `btAddress` stays null; pill "WI-FI" only | real |

### `first-run-bt.bt-visible-denied`: phone visibility refused (from B3; not drawn)
| B1 | phone user | Deny the discoverable dialog | "The phone has to be visible for a moment so <Mac> can find it." (danger colour) + "Try again" + "Skip for now" | real dialog (needs emulator BT) |

### `first-run-bt.bt-perm-denied`: Nearby devices denied in BtStep (from B3; not drawn)
Preconditions delta: Bluetooth permissions not granted.
| B1 | phone user | Tap "Continue", Deny the permission dialog | "Mote needs Nearby devices to pair over Bluetooth." + "Try again" + "Skip for now" | real |

### `first-run-bt.bt-off`: adapter off in BtStep (from B3; not drawn)
Preconditions delta: `TestSeam.adapter(enabled = false)`.
| B1 | phone user | Tap "Continue" | `ACTION_REQUEST_ENABLE` dialog; its result is ignored: the discoverable request follows whether or not Bluetooth was turned on | real dialog + faked adapter |

### `first-run-bt.no-bt-name`: adapter name unavailable (from B4; not drawn)
| B1 | system | adapter name null/blank | "Turn on Bluetooth on this phone, then try again." + "Try again" + "Skip for now" | faked: needs `TestSeam.adapter.name = ""` |

### `first-run-bt.bt-fail-<reason>`: helper can't pair (from B5; artboard j1-b1s)
Preconditions delta: helper `--fake-bt-pair-fail <reason>` (each reason its own @Test).
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | system | `bt.pair` | `{"t":"bt.pair.status","state":"searching"}` then `{"t":"bt.pair.status","state":"failed","reason":"<reason>"}`; log `[mote] bt: failed (<reason>)` | faked: `--fake-bt-pair-fail` |
| B2 | phone | — | `notfound`: "<Mac> couldn't see this phone. Keep Bluetooth on and the phone visible, then try again." · `timeout`: "Bluetooth pairing took too long. Try again." · `refused`: "Bluetooth pairing was refused. Try again, or pair from the Mac's Bluetooth settings." · `unavailable`: "Bluetooth is off or not allowed for Mote Helper on <Mac>." · any other: "Bluetooth pairing failed. Try again."; plus "Try again" / "Skip for now" | faked |
| B3 | phone user | "Try again" | BtStep restarts at the discoverable request | real |
Also: helper started WITHOUT `--fake-bt-pair` (dry-run + headless) → `unavailable` (`BtPair.swift:26,45`).

### `first-run-bt.bt-busy`: another pairing running (from B5; not drawn)
| B1 | system | 2nd `bt.pair` while one runs | `{"t":"err","code":"busy","msg":"another Bluetooth pairing is running","ref":"bt.pair"}` → "Bluetooth pairing failed. Try again." | faked: needs a second client sending `bt.pair` first (e.g. a node WS script) |

### `first-run-bt.bt-only`: Use Bluetooth instead (no helper) (from S1; artboard j1-b3s1..s3)
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | phone user | Tap "Use Bluetooth instead (no helper)" | BtGateScreen "How do you want to connect?" + "…Allow Nearby devices so it can pair and connect, then turn Bluetooth on." + "Connect with Bluetooth" + "Connect to your Mac instead" | real |
| B2 | phone user | Tap "Connect with Bluetooth", Allow all | remote; pill "Device: Choose a device. Tap to choose" | real + faked adapter |
| B3 | phone user | Pill → "Pair new Bluetooth device" | `ACTION_REQUEST_DISCOVERABLE` (2 min); text "Makes this phone visible for 2 minutes. Pair it from the computer's Bluetooth settings; Android asks you to confirm the code." | real dialog |
| B4 | Mac user | Mac Bluetooth settings › Connect, confirm the code | pill "<Mac BT name>" + badge "BT" | manual: real BT HID bond (faked by FakeTransport for UI only) |
| B5 | phone user | (upgrade) Pill → "Add a Mac" → Connect | continues as `add-mac-bt` B1 | faked: sweep port |
Postconditions: `BT_CHOSEN=true`; no saved helper host until B5.

## Disagreements → DEFECTS.md
- `first-run-bt-D1` (S5): first connect from FindMacScreen flips `startScreen` to REMOTE (`hasHelperHost`), unmounting ConnectCard: the "Also pair Bluetooth" step never shows on first run, and there is no later entry point for an already-saved Mac.
- `first-run-bt-D2` (S1/S3): artboard "Set up Mote" start screen + "Use Bluetooth only (limited)" + "No helper yet?…" and a separate "Connect to MacBook Pro" sheet with "Nearby · Wi-Fi" / "Not now" / "Connect" ≠ code FindMacScreen "Connect to your Mac" (auto-search), "Use Bluetooth instead (no helper)", inline card "Connect to <Mac>" with no "Not now".
- `first-run-bt-D3` (B2): artboard BtStep copy ("Also pair Bluetooth" / "Recommended. Works at the Mac’s login screen…" / "Pair Bluetooth too" / "Skip" / "Continue") ≠ code ("Also pair Bluetooth for login screen & backup (recommended)", "Android asks twice…", "Continue", "Skip").
- `first-run-bt-D4` (B8): artboard j1-s5 shows a Mac notice "Pixel 8 paired over Bluetooth. Accepted by Mote Helper · Block"; helper shows nothing for a Bluetooth pair.
- `first-run-bt-D5` (bt-fail): artboard j1-b1s fallback "One macOS Bluetooth confirm… Mote says so on the phone"; code has no such state (phone says "Nothing to do on the Mac"; failure copy only).
- `first-run-bt-D6` (bt-only B3): artboard j1-b3s2 makes the phone visible straight after the gate; code needs Devices › "Pair new Bluetooth device".
- `first-run-bt-D7` (bt-off): BtStep ignores the result of the Bluetooth enable request.
- `first-run-bt-D8` (j1-s2): artboard lists the sources "Known Macs / Bluetooth LE nearby / This Wi-Fi (sweep) / Bonjour"; code shows only the two lines of text.

## UNDEFINED
- B3/B5: Android "make visible" and "Pair with <Mac>?" dialog copy and whether the emulator has a Bluetooth adapter; real IOBluetooth inquiry/pairing (12 s inquiry, numeric comparison) can't run in tests.
- S4: `<phone>` = `Settings.Global.DEVICE_NAME` or `Build.MODEL` of the emulator; read it at test time.
- B7: when Hid reports the HID host connected (real HID only).
- S6: notice panel placement/timing beyond the 15 s in code.
