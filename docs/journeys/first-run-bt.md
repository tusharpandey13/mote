# first-run-bt: 1. First run (Wi-Fi + Bluetooth)

Artboard: `design/mote-v1/journey-first-run-bt.html` (Next (0.6.0)). Code (next-0.6): `android/.../ui/App.kt` (Root `setup` flag, `startScreen`, BtGateScreen), `pairing/FirstRun.kt`, `pairing/ConnectUi.kt` (FindMacScreen, FindMacPanel `savedBefore`, ConnectCard, BtStep), `pairing/BtSetup.kt`, `pairing/InstantPairing.kt`, `net/Net.kt` (`adoptTrusted`, `linkBt`), `ui/HostsUi.kt` (HostPill, HostSheet), helper `BtPair.swift`, `InstantPairing.swift` (`accept`). Docs: `docs/pairing.md` §5 + "Bluetooth HID setup", `docs/design/design-language.md` (coverage rows 1-2).
Test: `e2e/journeys` → `dev.mote.remote.journeys.FirstRunBtJourney` (one @Test per path below). Connect-step failures are journey `connect-next`.

## Preconditions
- P1. App: debug `dev.mote.remote.dev`, cleared data; no runtime permission granted. Seams: `TestSeam.noBonjour`, `sweepPort` = helper port, `fakeProximity`, `transport = FakeTransport(Caps.HID, linked = false)`, `adapter = Adapter(supported = true, enabled = true)`.
- P2. Phone: emulator API 35 "AndroidWifi" (10.0.2.17/24), real Bluetooth adapter on (its name is what the phone sends in `bt.pair`), no VPN.
- P3. Mac / helper: `--dry-run --headless --memory-store --no-ble --no-bonjour --stdin-control --port N --fake-proximity --fake-bt-pair` (instant pairing on, no approval). `<Mac>` = `n` of `LABS PAIR INFO:`; `<phone>` = `Settings.Global.DEVICE_NAME` / `Build.MODEL`; `<bt>` = the phone adapter name.
- P4. Network: same LAN (helper at 10.0.2.2:N, found by the sweep).

## Main path `first-run-bt`
| # | Actor | Action (exact) | Expected observable (exact) | Seam |
|---|---|---|---|---|
| S1 | phone user | Launch Mote | FindMacScreen: "Connect to your Mac", "Over Wi-Fi or Tailscale, through the free Mote Helper on the Mac.", "Use Bluetooth instead (no helper)"; no permission dialog | real |
| S2 | system | (wait ≤ 8 s) | ConnectCard title "<Mac>", button "Connect to <Mac>" | faked: discovery = LAN sweep of 10.0.2.0/24 on the helper port (Bonjour off) |
| S3 | phone user | Tap "Connect to <Mac>" | helper `labs: trusted <phone> via nearby`, `labs: new device <phone> via nearby (k:<fp>)`, stdout `NEW DEVICE: {"id":"k:<fp>","name":"<phone>","via":"nearby"}` | faked: proximity token read over the WebSocket from a --fake-proximity helper (TestSeam.fakeProximity) |
| S4 | system | connect done, Mac saved; setup stays on screen | "Connected over Wi-Fi"; checkbox row "Also pair Bluetooth for login screen & backup (recommended)" (checked); "Android asks twice: make the phone visible, then Pair. Nothing to do on the Mac."; "Continue"; "Skip" | real (`btOffer` = adapter supported, `btAddress` null) |
| S5 | phone user | Tap "Continue" | Android Nearby devices permission dialog (`BLUETOOTH_CONNECT`/`ADVERTISE`/`SCAN`; no Notifications) | real (system dialog) |
| S6 | phone user | Allow | `ACTION_REQUEST_DISCOVERABLE` (120 s) system dialog | real (emulator Bluetooth) |
| S7 | phone user | Allow | phone sends `bt.pair` on a second, silent session: helper `labs: trusted <phone> via trusted`, `bt: pair '<bt>' for <phone>`, `bt: paired 02:00:00:00:00:01` | faked: --fake-bt-pair (no IOBluetooth, no Android pairing dialog) |
| S8 | system | `bt.info` → `Net.linkBt`, `Hid.select` | "Wi-Fi and Bluetooth are both set up." | faked: --fake-bt-pair (`bt.info` address 02:00:00:00:00:00) |
| S9 | system | 1.5 s later `onDone` | setup closes; remote shown ("Play or pause"); pill content description "Device: <Mac> via Wi-Fi. Tap to choose" | real |
| S10 | Mac (helper) | — | no notice and no prompt for the Bluetooth pair: no new `NEW DEVICE:` line | real |

## Postconditions
- Phone: saved host `<Mac>` with `btAddress = 02:00:00:00:00:00`, trust record via `nearby`, `WIFI_CHOSEN=true`, selection `Helper`, Hid selected host 02:00:00:00:00:00; Bluetooth permissions granted.
- Helper: trusted list = [<phone> via nearby].

## Branches
### `first-run-bt.skip-bt`: Skip Bluetooth (from S4; artboard j1-b2s)
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | phone user | Tap "Skip" | remote shown; pill "Device: <Mac> via Wi-Fi. Tap to choose"; no `bt: pair` helper line; no permission dialog | real |

### `first-run-bt.untick`: untick the Bluetooth box (from S4; not drawn)
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | phone user | Tap the checkbox row | button label "Done"; "Skip" gone | real |
| B2 | phone user | Tap "Done" | remote shown; no `bt: pair` line | real |

### `first-run-bt.later`: add Bluetooth later for the saved Mac (from skip-bt; artboard j1-b2s "Add it later: Devices › Pair Bluetooth")
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | phone user | Pill → Devices | no helper-assisted "Pair Bluetooth" entry for `<Mac>`; Bluetooth gate not ready → button "Set up Bluetooth" (permissions not granted) | real |
| B2 | phone user | "Add a Mac" | the saved Mac is not offered: "Mote Helper not found on this network" within 10 s, no "Connect to <Mac>" | faked: sweep port |

### `first-run-bt.bt-visible-denied`: phone visibility refused (from S6; not drawn)
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | phone user | Deny the discoverable dialog | "The phone has to be visible for a moment so <Mac> can find it." + "Try again" + "Skip for now"; no `bt: pair` line | real |

### `first-run-bt.bt-perm-denied`: Nearby devices denied in BtStep (from S5; not drawn)
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | phone user | Deny the permission dialog | "Mote needs Nearby devices to pair over Bluetooth." + "Try again" + "Skip for now" | real |

### `first-run-bt.bt-off`: adapter off in BtStep (from S5; not drawn)
Preconditions delta: `TestSeam.adapter(enabled = false)`.
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | phone user | Tap "Continue", Allow, then Back on any Bluetooth-enable dialog | the discoverable dialog still follows (the enable result is ignored, `ConnectUi.kt:325`) | faked: adapter off (the emulator's real adapter stays on) |

### `first-run-bt.no-bt-name`: adapter name unavailable (from S7; not drawn)
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | system | adapter name null/blank | "Turn on Bluetooth on this phone, then try again." | faked: needs new seam `TestSeam.adapter.name` (name is read from the real BluetoothManager) |

### `first-run-bt.bt-fail-notfound`: Mac can't see the phone (from S7; artboard j1-b1s)
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | system | helper `--fake-bt-pair-fail notfound` | helper `bt: failed (notfound)`; "<Mac> couldn't see this phone. Keep Bluetooth on and the phone visible, then try again." + "Try again" + "Skip for now" | faked: --fake-bt-pair-fail notfound |

### `first-run-bt.bt-fail-timeout`: pairing took too long (from S7)
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | system | helper `--fake-bt-pair-fail timeout` | `bt: failed (timeout)`; "Bluetooth pairing took too long. Try again." | faked: --fake-bt-pair-fail timeout |

### `first-run-bt.bt-fail-refused`: pairing refused (from S7)
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | system | helper `--fake-bt-pair-fail refused` | `bt: failed (refused)`; "Bluetooth pairing was refused. Try again, or pair from the Mac's Bluetooth settings." | faked: --fake-bt-pair-fail refused |

### `first-run-bt.bt-fail-unavailable`: Mac Bluetooth off / not allowed (from S7)
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | system | helper `--fake-bt-pair-fail unavailable` | `bt: failed (unavailable)`; "Bluetooth is off or not allowed for Mote Helper on <Mac>." | faked: --fake-bt-pair-fail unavailable |
| B2 | phone user | Tap "Skip for now" | remote shown | real |

### `first-run-bt.bt-fail-other`: unknown failure reason (from S7)
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | system | helper `--fake-bt-pair-fail weird` | `bt: failed (weird)`; "Bluetooth pairing failed. Try again." | faked: --fake-bt-pair-fail weird |

### `first-run-bt.bt-nofake`: dry-run helper without a fake (from S7)
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | system | helper without `--fake-bt-pair` (dry-run + headless = Bluetooth disabled) | `bt: failed (unavailable)`; "Bluetooth is off or not allowed for Mote Helper on <Mac>." | real (dry-run behaviour, `BtPair.swift:26,45`) |

### `first-run-bt.bt-retry`: Try again after a failure (from bt-fail-*)
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | phone user | after a failure, tap "Try again", Allow the discoverable dialog | a second `bt: pair '<bt>' for <phone>` line | faked: --fake-bt-pair-fail refused |

### `first-run-bt.bt-busy`: another pairing running (from S7; not drawn)
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | system | 2nd `bt.pair` while one runs | `{"t":"err","code":"busy",…}` → "Bluetooth pairing failed. Try again." | faked: needs a second WebSocket client sending `bt.pair` first |

### `first-run-bt.bt-only`: Use Bluetooth instead (no helper) (from S1; artboard j1-b3s1..s3)
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | phone user | Tap "Use Bluetooth instead (no helper)" | "How do you want to connect?", "Connect with Bluetooth", "Connect to your Mac instead" | real |
| B2 | phone user | Tap "Connect with Bluetooth", Allow all | remote; pill "Device: Choose a device. Tap to choose" | faked: adapter on, FakeTransport |
| B3 | phone user | Pill → "Pair new Bluetooth device" | text "Makes this phone visible for 2 minutes. Pair it from the computer's Bluetooth settings; Android asks you to confirm the code."; discoverable system dialog | real |
| B4 | Mac user | Mac Bluetooth settings › Connect, confirm | pill shows the Mac + "BT" | manual: real Bluetooth HID bond |
| B5 | phone user | (upgrade) Pill → "Add a Mac" → "Connect to <Mac>" | "Connected over Wi-Fi" + "Also pair Bluetooth for login screen & backup (recommended)" in the sheet | faked: sweep port + proximity |

## Disagreements → DEFECTS.md
- `first-run-bt-D1`: FIXED in next-0.6 for first run (App.kt `setup` keeps FindMacScreen until `onDone`). Remaining (path `later`): no entry to the helper-assisted Bluetooth step for an already-saved Mac.
- `first-run-bt-D2` (S1/S2): artboard "Set up Mote" screen / "Use Bluetooth only (limited)" / Connect sheet with "Not now" ≠ code.
- `first-run-bt-D3` (S4): BtStep copy ≠ artboard j1-s4.
- `first-run-bt-D4` (S10): no Mac-side notice for the Bluetooth pair (artboard j1-s5).
- `first-run-bt-D5` (bt-fail-*): artboard "One macOS Bluetooth confirm" fallback not in code.
- `first-run-bt-D6` (bt-only B3): artboard makes the phone visible right after the gate; code needs Devices.
- `first-run-bt-D7` (bt-off): enable result ignored.
- `first-run-bt-D8` (S2): artboard source list not shown.

## UNDEFINED
- S5/S6: Android dialog copy (`Allow` / `Deny` button labels used by the test are Android's).
- S8/S9: whether HID ever connects to 02:00:00:00:00:00 (no BT badge expected on the emulator).
- S3: `<phone>` is read at test time.
