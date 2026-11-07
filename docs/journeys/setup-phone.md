# setup-phone: B. Phone setup

Artboard: `design/mote-v1/journey-setup-phone.html` (Shipped). Code: `android/.../ui/App.kt` (Root, `requestedPermissions`, `BtGateScreen`), `pairing/FirstRun.kt` (`startScreen`), `pairing/ConnectUi.kt` (`FindMacScreen`), `hid/Bt.kt` (`BtGate`, `gateOf`, `Bt.permissions`), `ui/HostsUi.kt` (`WifiGateOption`), `AndroidManifest.xml`. Docs: `docs/design/design-language.md` (coverage "Bluetooth gate…", "Notifications permission"), `docs/pairing.md` §5 "First run", `android/README.md`.
Test: `e2e/journeys` → `dev.mote.remote.journeys.SetupPhoneJourney` (one @Test per path below).

## Preconditions
- P1. App: debug `dev.mote.remote.dev` 0.6.0-dev freshly installed (`pm clear`), no saved Macs, no `BT_CHOSEN`/`WIFI_CHOSEN`, runtime permissions NOT granted: `android.permission.BLUETOOTH_CONNECT`, `BLUETOOTH_ADVERTISE`, `BLUETOOTH_SCAN`, `POST_NOTIFICATIONS` (API 35). No `mote.demo` extra.
- P2. Phone: emulator API 35, Wi-Fi on ("AndroidWifi"). Bluetooth adapter: `TestSeam.adapter = Adapter(supported = true, enabled = <per path>)`.
- P3. Mac / helper: none needed (no helper running, so FindMacScreen ends in "not found").
- P4. Network: LAN only.

## Main path `setup-phone`
| # | Actor | Action (exact) | Expected observable (exact) | Seam |
|---|---|---|---|---|
| S1 | phone user | Download `mote-vX.Y.Z.apk` from GitHub Releases, open it, allow "Install unknown apps", Install | Android package installer | faked: `adb install -r app-debug.apk` (system UI; artboard jB-s1..s4) |
| S2 | phone user | Launch Mote | while `Net.loaded` is false: blank chassis; then FindMacScreen: "Connect to your Mac", "Over Wi-Fi or Tailscale, through the free Mote Helper on the Mac.", "New phone, or reinstalled Mote? Let's reconnect: tap Connect, nothing changes on the Mac."; secondary button "Use Bluetooth instead (no helper)" (contentDescription = label). No permission dialog | real (`startScreen` → FIND_MAC) |
| S3 | system | (wait) | panel "Looking for your Mac…" / "Searching this Wi-Fi, Bluetooth and Tailscale." then, ≤ 5 s after the finder started (`NOT_FOUND_MS`), "Mote Helper not found on this network" + "Install it on the Mac (Terminal), keep it running, and use the same Wi-Fi:" + `curl -fsSL https://tusharpandey13.github.io/mote/install.sh \| bash` + "or download it from tusharpandey13.github.io/mote" + "Retry" + "Enter address" | real (no helper; `TestSeam.noBonjour = true`) |
| S4 | phone user | Tap "Use Bluetooth instead (no helper)" | `BT_CHOSEN=true`, `WIFI_CHOSEN=false` persisted; BtGateScreen: "How do you want to connect?", "Mote works as a keyboard, mouse and media remote for your computer. Allow Nearby devices so it can pair and connect, then turn Bluetooth on.", primary "Connect with Bluetooth", secondary "Connect to your Mac instead", small "Over Wi-Fi or Tailscale with the free Mote Helper on the Mac (tusharpandey13.github.io/mote). Adds Unicode typing, precise brightness and click-at-point." | real |
| S5 | phone user | Tap "Connect with Bluetooth" | Android permission dialog(s) for `BLUETOOTH_CONNECT`+`BLUETOOTH_ADVERTISE`+`BLUETOOTH_SCAN` (Nearby devices group) and `POST_NOTIFICATIONS` | real (system dialogs; tap `com.android.permissioncontroller:id/permission_allow_button`) |
| S6 | phone user | Allow every dialog | `Bt.permitted` true; adapter enabled (fake) → gate READY → remote shown (content description "Play or pause" exists); `HidService.start`; pill "Choose a device" (contentDescription "Device: Choose a device. Tap to choose") | real + faked: `TestSeam.adapter(enabled = true)` |

## Postconditions
- DataStore: `BT_CHOSEN=true`, `WIFI_CHOSEN=false`, no saved hosts. All four permissions granted. HidService running. Relaunch opens straight into the remote (`btChosen` + ready).

## Branches
### `setup-phone.bt-off`: Bluetooth off (from S6; artboard jB-s6)
Preconditions delta: `TestSeam.adapter(enabled = false)`.
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | system | after S6 Allow | permission callback sees permitted + disabled → launches `BluetoothAdapter.ACTION_REQUEST_ENABLE` (system dialog) | real dialog on emulator; adapter state faked |
| B2 | phone user | Dismiss the enable dialog | gate BT_OFF: "Mote works as a keyboard, mouse and media remote for your computer. Bluetooth is off; turn it on to continue.", primary "Turn on Bluetooth", secondary "Connect to your Mac instead" | faked: adapter |
| B3 | phone user | Tap "Turn on Bluetooth" | `ACTION_REQUEST_ENABLE` again | real dialog |
| B4 | system | adapter turns on | remote shown | faked: `rule.adapter.update { it.copy(enabled = true) }` |
| B5 | system | adapter turns off while in the remote | gate BT_OFF again ("Turn on Bluetooth") | faked: adapter |

### `setup-phone.denied-once`: Nearby devices denied once (from S5; not drawn)
| B1 | phone user | Deny every dialog | gate unchanged: "…Allow Nearby devices so it can pair and connect, then turn Bluetooth on." + "Connect with Bluetooth" (rationale now true → `openSettings=false`) | real |
| B2 | phone user | Tap "Connect with Bluetooth", Allow | as S6 | real |

### `setup-phone.denied-twice`: Nearby devices denied twice (from S5; artboard jB-b1s)
| B1 | phone user | Deny, tap "Connect with Bluetooth", Deny again | `openSettings=true`: "Mote works as a keyboard, mouse and media remote for your computer. Allow Nearby devices in Settings, then come back." + primary "Open settings" | real |
| B2 | phone user | Tap "Open settings" | `Settings.ACTION_APPLICATION_DETAILS_SETTINGS` for `package:dev.mote.remote.dev` | real (system Settings) |
| B3 | phone user | Grant Nearby devices there, press Back | on resume `Bt.observe` re-snapshots → READY → remote (or BT_OFF gate if adapter off) | real (`pm grant` acceptable as stand-in for the Settings toggle) |

### `setup-phone.notif-denied`: notifications denied, Nearby allowed (from S5; artboard jB-s6)
| B1 | phone user | Allow Nearby devices, Deny notifications | remote shown (callback checks only `Bt.permitted`); `POST_NOTIFICATIONS` denied; no retry later | real |

### `setup-phone.unsupported`: no usable Bluetooth (from S4; artboard jB-b2s)
Preconditions delta: `TestSeam.adapter(supported = false)`.
| B1 | phone user | Tap "Use Bluetooth instead (no helper)" | gate UNSUPPORTED: "How do you want to connect?", "This phone has no Bluetooth adapter Mote can use.", no "Turn on Bluetooth"/"Connect with Bluetooth"; primary "Connect to your Mac" | faked: adapter |
| B2 | phone user | Tap "Connect to your Mac" | `WIFI_CHOSEN=true`; FindMacScreen | real |

### `setup-phone.back-to-wifi`: from the gate back to Wi-Fi (from S4; artboard jB-s5 "Use Wi-Fi with Mote Helper")
| B1 | phone user | Tap "Connect to your Mac instead" | `WIFI_CHOSEN=true`, `BT_CHOSEN=false`; FindMacScreen (no permission asked) | real |

### `setup-phone.legacy-permitted`: Bluetooth permissions granted before 0.6 (from S2; not drawn)
Preconditions delta: the three Bluetooth permissions pre-granted (`pm grant`), no choice persisted.
| B1 | phone user | Launch | adapter on: remote directly ("Choose a device" pill); adapter off: BT_OFF gate. FindMacScreen not shown | real + faked adapter |

### `setup-phone.api-28-30`: Android 9-11 fresh install (from S2; not drawn)
Preconditions delta: API 28-30 emulator (`Bt.permissions` is empty → `Bt.permitted` always true).
| B1 | phone user | Launch | never FindMacScreen: adapter on → remote; off → BT_OFF gate | real on an API 30 image (not the API 35 harness) |

## Disagreements → DEFECTS.md
- `setup-phone-D1` (S2): artboard (Shipped) says "the Bluetooth gate is the first screen"; code opens FindMacScreen and reaches the gate only via "Use Bluetooth instead (no helper)".
- `setup-phone-D2` (S4): artboard gate copy "Use Wi-Fi with Mote Helper" / "Needs the free Mote Helper app on the Mac (…)" and header "Choose a device" ≠ code "Connect to your Mac instead" / "Over Wi-Fi or Tailscale with the free Mote Helper on the Mac (…)", no header.
- `setup-phone-D3` (notif-denied / Wi-Fi path): `POST_NOTIFICATIONS` is only requested from the Bluetooth gate; Wi-Fi-only users on Android 13+ are never asked.
- `setup-phone-D4` (api-28-30): on API 28-30 `Bt.permitted` is vacuously true, so `startScreen` skips the Wi-Fi-first first run.
- `setup-phone-D5` (denied-twice): `openSettings` is `remember` (not saved): after recreation the button says "Connect with Bluetooth" again and the tap silently does nothing visible until the callback flips it.
- `setup-phone-D6` (S1): artboard shows `mote-v0.4.0.apk` / "v0.4.0 · Latest"; current is 0.6.0 (cosmetic).

## UNDEFINED
- S1, S5, B1 (enable / Settings): exact Android dialog copy and app label ("Mote" vs debug label) — system UI.
- S5: whether Android shows the Nearby devices and Notifications dialogs as one or two dialogs, and their order — system behaviour of `RequestMultiplePermissions`.
- denied-twice: Android's exact rule for "don't ask again" (a dismissed dialog may count as a denial), so the number of denials before "Open settings" is OS-defined.
