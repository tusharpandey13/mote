# setup-phone: B. Phone setup

Artboard: `design/mote-v1/journey-setup-phone.html` (Shipped). Code (next-0.6): `android/.../ui/App.kt` (Root, `setup`, `requestedPermissions`, `BtGateScreen`), `pairing/FirstRun.kt` (`startScreen`), `pairing/ConnectUi.kt` (`FindMacScreen`), `hid/Bt.kt` (`BtGate`, `gateOf`, `Bt.permissions`), `ui/HostsUi.kt` (`WifiGateOption`, `HostPill`), `AndroidManifest.xml`. Docs: `docs/design/design-language.md` (coverage "Bluetooth gate…", "Notifications permission"), `docs/pairing.md` §5 "First run", `android/README.md`.
Test: `e2e/journeys` → `dev.mote.remote.journeys.SetupPhoneJourney` (one @Test per path below).

## Preconditions
- P1. App: debug `dev.mote.remote.dev` 0.6.0-dev, cleared data (orchestrator `clearPackageData`): no saved Macs, no `BT_CHOSEN`/`WIFI_CHOSEN`; runtime permissions not granted: `android.permission.BLUETOOTH_CONNECT`, `BLUETOOTH_ADVERTISE`, `BLUETOOTH_SCAN`, `POST_NOTIFICATIONS`.
- P2. Phone: emulator API 35, Wi-Fi on ("AndroidWifi"). Adapter: `TestSeam.adapter = Adapter(supported = true, enabled = <per path>)`; `TestSeam.transport = FakeTransport(Caps.HID)` (no real HID registration).
- P3. Mac / helper: none (FindMacScreen ends in "not found").
- P4. Network: LAN only, `TestSeam.noBonjour`.

## Main path `setup-phone`
| # | Actor | Action (exact) | Expected observable (exact) | Seam |
|---|---|---|---|---|
| S1 | phone user | Download `mote-vX.Y.Z.apk`, allow "Install unknown apps", Install | package `dev.mote.remote.dev` installed | faked: the harness installs the debug APK (gradle connectedDevAndroidTest) instead of Chrome + package installer |
| S2 | phone user | Launch Mote | FindMacScreen: "Connect to your Mac", "Over Wi-Fi or Tailscale, through the free Mote Helper on the Mac.", "New phone, or reinstalled Mote? Let's reconnect: tap Connect, nothing changes on the Mac.", button "Use Bluetooth instead (no helper)"; no permission dialog | real |
| S3 | system | (wait ≤ 10 s) | "Mote Helper not found on this network", "Install it on the Mac (Terminal), keep it running, and use the same Wi-Fi:", `curl -fsSL https://tusharpandey13.github.io/mote/install.sh \| bash`, "or download it from tusharpandey13.github.io/mote", "Retry", "Enter address" | faked: no helper on the swept port, Bonjour off (TestSeam.noBonjour) |
| S4 | phone user | Tap "Use Bluetooth instead (no helper)" | BtGateScreen: "How do you want to connect?", "Mote works as a keyboard, mouse and media remote for your computer. Allow Nearby devices so it can pair and connect, then turn Bluetooth on.", "Connect with Bluetooth", "Connect to your Mac instead", "Over Wi-Fi or Tailscale with the free Mote Helper on the Mac (tusharpandey13.github.io/mote). Adds Unicode typing, precise brightness and click-at-point." | real |
| S5 | phone user | Tap "Connect with Bluetooth" | Android permission dialog(s) (Nearby devices; Notifications) | real (system dialogs, `com.android.permissioncontroller`) |
| S6 | phone user | Allow every dialog | all four permissions granted; remote shown (content description "Play or pause"); pill content description "Device: Choose a device. Tap to choose" | faked: adapter on (TestSeam.adapter), FakeTransport for HID |

## Postconditions
- `BT_CHOSEN=true`, no saved hosts, four permissions granted, HidService started.

## Branches
### `setup-phone.bt-off`: Bluetooth off (from S6; artboard jB-s6)
Preconditions delta: `TestSeam.adapter(enabled = false)`.
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | phone user | after S5 Allow every dialog (and leave any Bluetooth-enable system dialog with Back) | gate BT_OFF: "Mote works as a keyboard, mouse and media remote for your computer. Bluetooth is off; turn it on to continue.", "Turn on Bluetooth", "Connect to your Mac instead" | faked: adapter off (the emulator's real adapter is on, so Android may skip its enable dialog) |
| B2 | system | adapter turns on | remote shown ("Play or pause") | faked: adapter flow → enabled = true |
| B3 | system | adapter turns off while in the remote | gate "Turn on Bluetooth" again | faked: adapter flow → enabled = false |

### `setup-phone.denied-once`: Nearby devices denied once (from S5; not drawn)
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | phone user | Deny every dialog | gate unchanged: "Mote works as a keyboard, mouse and media remote for your computer. Allow Nearby devices so it can pair and connect, then turn Bluetooth on." + "Connect with Bluetooth"; no "Open settings" | real |
| B2 | phone user | Tap "Connect with Bluetooth", Allow every dialog | remote shown ("Play or pause") | faked: adapter on |

### `setup-phone.denied-twice`: Nearby devices denied twice (from S5; artboard jB-b1s)
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | phone user | Deny, tap "Connect with Bluetooth", Deny again | "Mote works as a keyboard, mouse and media remote for your computer. Allow Nearby devices in Settings, then come back." + "Open settings" | real |
| B2 | phone user | Tap "Open settings" | system Settings app (package `com.android.settings`) shows the app's details | real |
| B3 | phone user | Grant Nearby devices there, press Back | on resume: remote shown ("Play or pause") | faked: `grantRuntimePermission` stands in for the Settings toggle; adapter on |

### `setup-phone.notif-denied`: notifications denied, Nearby allowed (from S5; artboard jB-s6)
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | phone user | Allow the Nearby devices dialog, Deny the Notifications dialog | remote shown ("Play or pause"); `POST_NOTIFICATIONS` denied, three Bluetooth permissions granted | faked: adapter on |

### `setup-phone.unsupported`: no usable Bluetooth (from S4; artboard jB-b2s)
Preconditions delta: `TestSeam.adapter(supported = false)`.
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | phone user | Tap "Use Bluetooth instead (no helper)" | "How do you want to connect?", "This phone has no Bluetooth adapter Mote can use."; no "Connect with Bluetooth", no "Turn on Bluetooth"; primary "Connect to your Mac" | faked: adapter unsupported |
| B2 | phone user | Tap "Connect to your Mac" | FindMacScreen "Connect to your Mac"; no permission dialog | real |

### `setup-phone.back-to-wifi`: from the gate back to Wi-Fi (from S4; artboard jB-s5)
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | phone user | Tap "Connect to your Mac instead" | FindMacScreen "Connect to your Mac"; no permission dialog | real |

### `setup-phone.legacy-permitted`: Bluetooth permissions granted before 0.6 (from S2; not drawn)
Preconditions delta: the three Bluetooth permissions granted before launch, no choice persisted.
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | phone user | Launch (adapter on) | remote directly ("Play or pause", "Device: Choose a device. Tap to choose"); no "Connect to your Mac" | faked: adapter on; permissions pre-granted |
| B2 | system | adapter off | gate "Turn on Bluetooth" | faked: adapter |

### `setup-phone.api-28-30`: Android 9-11 fresh install (from S2; not drawn)
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | phone user | Launch on API 28-30 | never FindMacScreen: adapter on → remote; off → BT_OFF gate (`Bt.permissions` empty → `permitted` true) | manual: needs an API 28-30 emulator image (the harness is API 35) |

## Disagreements → DEFECTS.md
- `setup-phone-D1` (S2): Shipped artboard says the Bluetooth gate is the first screen; code opens FindMacScreen.
- `setup-phone-D2` (S4): artboard gate copy ("Use Wi-Fi with Mote Helper", "Needs the free Mote Helper app…", header "Choose a device") ≠ code.
- `setup-phone-D3` (notif-denied / Wi-Fi path): `POST_NOTIFICATIONS` only requested from the Bluetooth gate.
- `setup-phone-D4` (api-28-30): Android 9-11 skip the Wi-Fi-first first run.
- `setup-phone-D5` (denied-twice): `openSettings` not saved across recreation.
- `setup-phone-D6` (S1): artboard APK v0.4.0.

## UNDEFINED
- S1, S5: exact Android dialog copy and the debug app label — system UI.
- S5: one or two dialogs, order — `RequestMultiplePermissions` behaviour.
- denied-twice: how many denials Android needs before "don't ask again".
