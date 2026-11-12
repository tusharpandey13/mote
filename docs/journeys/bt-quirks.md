# bt-quirks: C2. Bluetooth first connect: macOS quirks

Artboard: `design/mote-v1/journey-bt-quirks.html` (Shipped). Code: `android/.../ui/HostsUi.kt` (HostSheet LIST: "Pair new Bluetooth device", stale-pairing help link, long-press Forget, HostRowCap statuses, requestDiscoverable), `ui/HostsUi.kt` HostPill, `net/Hosts.kt` (hostRows), `net/Net.kt` (forget), `hid/Hid.kt` / `hid/Reconnect.kt` (unpaired), `pairing/ConnectUi.kt` (BtStep), `pairing/BtSetup.kt`, `helper/.../BtPair.swift` (start). Docs: `android/README.md` "First use"; `docs/pairing.md` §5 "Bluetooth HID setup".
Test: `e2e/journeys` → `dev.mote.remote.journeys.BtQuirksJourney` (one @Test per path below).

The quirks themselves (Keyboard Setup Assistant, a stale macOS bond) are macOS behaviour; Mote has no detection for them. What the app does is the help text and the recovery controls, which are testable with the adapter / transport seams. Notation: `<Mac>` = the bonded host's Bluetooth name; `<addr>` its address.

## Preconditions
- P1. App: `dev.mote.remote.dev`, Bluetooth path: `bt_chosen=true`, `BLUETOOTH_CONNECT`, `BLUETOOTH_ADVERTISE`, `BLUETOOTH_SCAN` granted (`POST_NOTIFICATIONS` granted to avoid its dialog); seams `TestSeam.adapter = Adapter(supported=true, enabled=true, hosts=[Host(<addr>, "<Mac>")])`, `TestSeam.transport` = fake BT transport able to report connected.
- P2. Phone: Bluetooth on (faked); Wi-Fi irrelevant.
- P3. Mac / helper: none (Bluetooth HID only). Real-hardware runs: a Mac that has just paired the phone in System Settings › Bluetooth.
- P4. Network: none needed.

## Main path `bt-quirks`
| # | Actor | Action (exact) | Expected observable (exact) | Seam |
|---|---|---|---|---|
| S1 | Mac user | Pair the phone from System Settings › Bluetooth (phone discoverable via "Pair new Bluetooth device") | macOS opens "Keyboard Setup Assistant" ("Your keyboard cannot be identified…", Quit / Continue); Quit is fine | manual: macOS UI, real Bluetooth HID bond; no Mote code involved (UNDEFINED copy) |
| S2 | system (phone) | HID host connects | pill contentDescription "Device: <Mac> via BT. Tap to choose", badge "BT", LED green; Devices row status "Connected · BT" | faked: needs a seam driving `Hid.state.connected` (FakeTransport only fakes the transport; missing fake) |
| S3 | phone user | Tap the pill | `HostSheet` LIST: row "<Mac>" (contentDescription "Use <Mac>"), badge "BT"; button "Add a Mac"; link "More options"; button "Pair new Bluetooth device"; "Makes this phone visible for 2 minutes. Pair it from the computer's Bluetooth settings; Android asks you to confirm the code."; link "Connected over Bluetooth but buttons do nothing?" | faked: adapter (bonded host "Test Mac") + transport seams |

## Postconditions
- Phone: selection `bt:<addr>` (Hid), no helper host. Mac: bond (real runs only).

## Branches
### `bt-quirks.stale-help`: shows Connected but buttons do nothing (from S3; artboard jC2-b1s)
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | phone user | Tap "Connected over Bluetooth but buttons do nothing?" | link replaced by: "The computer kept an old pairing. Fix: on the computer, forget this phone in Bluetooth settings; here, long-press it and forget it too; then Pair new Bluetooth device again." | faked: adapter (bonded host "Test Mac") + transport seams |
| B2 | system | — | no automatic detection of the stale state (nothing in code inspects HID report delivery) | static |

### `bt-quirks.forget-on-phone`: long-press › Forget (from B1; artboard jC2-b1s2)
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | phone user | Long-press the Bluetooth-only row "Use <Mac>" | "Forget <Mac>? Android removes Bluetooth pairings in its own settings." with Open settings (ACTION_BLUETOOTH_SETTINGS) and Cancel; apps cannot remove a bond (bt-quirks-D1 fixed, D-RC-2) | faked: adapter (bonded host "Test Mac") + transport seams |
| B2 | (variant) | Row grouped with a saved helper host (same name / linked `btAddress`), long-press | inline row "Forget <Mac> on this phone?" with "Forget" (red) then "Cancel"; "Forget" runs `Net.forget(hostId)`: removes the helper host and token only; the Android Bluetooth bond stays (bt-quirks-D1) | faked: seeded helper host + adapter |
| B3 | phone user | Select "Use <Mac>" (pill "Device: <Mac> via BT. Tap to choose"), then unpair on the phone (Android Settings › Connected devices › <Mac> › Forget) | bonded list loses <addr>: pill "Device: Not paired: pair again via BT. Tap to choose" | faked: drop the host from TestSeam.adapter.hosts (stands in for Android Settings › Forget) |

### `bt-quirks.forget-on-mac` (artboard jC2-b1s3)
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | Mac user | System Settings › Bluetooth › <phone> ⓘ › Forget This Device… | macOS removes the bond | manual: macOS System Settings (TCC-free but needs real hardware and a window) |

### `bt-quirks.pair-again` (artboard jC2-b1s4)
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | phone user | Tap "Pair new Bluetooth device" | `Hid.pausePairing(120000)`; system dialog for `ACTION_REQUEST_DISCOVERABLE` (120 s) with an "Allow" button | real dialog (emulator adapter); copy UNDEFINED |
| B2 | phone user | Same, `BLUETOOTH_ADVERTISE` not granted | Toast "Allow Nearby devices to pair"; no dialog | real |
| B3 | Mac user | Pair from System Settings; confirm the code on Android | new bond; back to S1 | manual |

### `bt-quirks.helper-bt-step-stale`: 0.6 Bluetooth step with a stale Mac-side bond (not drawn)
Preconditions delta: a helper host trusted by instant pairing; the Mac still lists the phone as paired (stale) while the phone has no bond.
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | phone user | BtStep "Continue" (Devices › Add a Mac flow) | helper `bt: pair '<phone bt name>' for <phone>`; `BtPair.start` finds the name in `IOBluetoothDevice.pairedDevices()` and answers `{"t":"bt.pair.status","state":"paired",...}` without pairing; phone: "Wi-Fi and Bluetooth are both set up." while HID can't work (bt-quirks-D2) | manual: real Mac bond; `--fake-bt-pair` can't model "already paired" |

## Disagreements → DEFECTS.md
- `bt-quirks-D1` (forget-on-phone; fixed by D-RC-2: Forget opens Android Bluetooth settings): "long-press it and forget it" is impossible for a Bluetooth-only host, and Forget never removes the Android bond.
- `bt-quirks-D2` (helper-bt-step-stale): helper reports success when the Mac already lists the phone, reinforcing a stale bond.
- `bt-quirks-D3` (B1 stale-help, B2 forget): artboard/doc say there is no in-app help, the help link isn't drawn, the Forget control is drawn as a dialog with reversed buttons, and README says "Pick ANSI" / "Pair new device".

## UNDEFINED
- S1: Keyboard Setup Assistant appearance and copy, whether it appears at all (macOS version / keyboard type); not in Mote code.
- forget-on-phone B3, forget-on-mac B1: system Settings UI copy.
- Whether a stale bond shows "Connected" on the phone at all depends on the Android HID stack.
