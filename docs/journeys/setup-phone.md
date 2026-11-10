# J3 setup-phone: phone first-run setup

Phase 2 spec (format: [README.md](README.md)). Derived from `android/app/src/main/AndroidManifest.xml`, `ui/App.kt`, `pairing/{FirstRun,ConnectUi,BleScan,Finder}.kt`, `hid/Bt.kt`, `diag/Perms.kt`. Replaces the phase-1 draft (setup-phone-D1..D6 carried). Folds in `first-run-bt.md` (Bluetooth-only path) at the level of entry points; its HID pairing branches stay in that draft until phase 3.

## UNDEFINED
- U4 When the Wi-Fi/helper path asks for POST_NOTIFICATIONS (now: never).
- U13 Route to Settings when Nearby devices is permanently denied on the connect card.

## Goal
A user installs Mote, opens it, and within 5 s either sees their Mac ("Connect to <name>") or a clear next step (install the helper, turn on Wi-Fi, enter an address, or use Bluetooth), without being asked for permissions they do not need yet.

## Preconditions
| Dimension | Values covered |
|---|---|
| Install | fresh / upgrade from <= 0.5 (Bluetooth host saved) / upgrade with a helper host saved / reinstall (data cleared) |
| Android | 9-11 (legacy BT perms granted at install) / 12 (Nearby) / 13+ (Nearby + notifications) |
| Nearby devices | not asked / granted / denied once / denied permanently / revoked in Settings |
| Notifications | not asked / granted / denied / channel off |
| Wi-Fi | on, same LAN as Mac / on, other subnet or AP isolation / off / cellular only |
| Multicast (mDNS) | passes / blocked by router or AP |
| Tailscale | not installed / installed, Mac on same tailnet |
| Bluetooth adapter | on / off / absent |

## Success criteria
- First screen is FindMacScreen; no permission dialog at launch (`ui/screen` FIND_MAC, no `permission/request`).
- Helper on the same /24 is found with or without mDNS (`discovery/nsd.found` or `discovery/sweep.open`) in <= 5 s.
- Never an open-ended wait: `FindUi` reaches Found, WifiOff or NotFound.
- Bluetooth-only path asks Nearby (+ notifications on 13+) once, and every denial has a working next step.

## Main path
| ID | Actor | Step | Expected | Failure mode | Detected by | User sees | Recovery | Level | Test | Status |
|---|---|---|---|---|---|---|---|---|---|---|
| SP-1 | User | Installs `mote-vX.Y.Z.apk` (allow unknown apps) | package installed | blocked by Play Protect / unknown sources off | none | system dialog | allow in Settings | L4 | MANUAL-12 | untested |
| SP-2 | App | First launch | `startScreen` FIND_MAC: "Connect to your Mac", "Use Bluetooth instead (no helper)"; no permission dialog | Android 9-11: skips Wi-Fi-first (setup-phone-D4) | `lifecycle/init`, `ui/screen{screen:FIND_MAC}` | FindMacScreen | n/a | L0 | android:FirstRunTest#freshInstallOpensIntoFindingTheMacWithoutAskingForBluetooth; android:FirstRunTest#opensIntoFindingTheMacThenBluetoothIsSecondary | pass |
| SP-3 | App | Discovery: NSD `_mote._tcp`, known-host probe, LAN /24 sweep; BLE scan only if Nearby already granted | "Looking for your Mac…" then Found | n/a | `discovery/nsd.start`, `nsd.found`, `nsd.resolved`, `sweep.start`, `sweep.open`, `sweep.done`, `known.probe`, `ble.start` | "Connect to <name>" | n/a | L2 | android:ConnectNextJourney#main; android:SweepTest#sweepCoversThePhonesSlash24 | pass |
| SP-4 | Android | mDNS blocked (router/AP drops multicast) | sweep finds the helper on the phone's /24 | Mac on another subnet | no `nsd.found`; `discovery/sweep.open` | "Connect to <name>" (slightly later) | n/a | L2 | android:FirstRunTest#sweepFindsTheHelperWithoutBonjour; android:SweepTest#realTcpSweepFindsAListenerFast | pass |
| SP-5 | Android | mDNS blocked on a real router | as SP-4 | sweep blocked too (client isolation) | `discovery/sweep.done{open:0}` | NotFound (SP-6) | Enter address / Tailscale | L4 | MANUAL-13 | untested |
| SP-6 | App | Nothing found in `NOT_FOUND_MS` = 5 s (or all sources done after 1 s) | NotFound: "Mote Helper not found on this network", install command, Retry, "Enter address" | n/a | `FindUi.NotFound`; `discovery/sweep.done` | not-found card | install helper (J1) / Enter address | L0 | android:FirstRunTest#discoveryNeverWaitsForever; android:ConnectNextJourney#notFound | pass |
| SP-7 | App | Phone not on Wi-Fi | WifiOff "Turn on Wi-Fi" (tailnet-only Macs still Found) | cellular only and no Tailscale | `discovery/network` | "Turn on Wi-Fi" | Wi-Fi on | L2 | android:ConnectNextJourney#wifiOff | pass |
| SP-8 | User | "Enter address" (IP, `name.local`, tailnet name, pairing link) | address parsed into the right slot, connect starts | malformed | `transport/connect.try` | inline error | retype | L0 | android:HostsTest#parsesTypedAddresses; android:HostsTest#parsesPairingLinks; android:ConnectNextJourney#enterAddress | pass |
| SP-9 | App | Tailscale installed, Mac on the same tailnet, phone off LAN | Mac Found via tailnet | Tailscale VPN off | `transport/tailscale.prompt` | prompt to open Tailscale | open Tailscale | L2 | android:WifiHelperJourney#tailscaleOwner (second pass: tailscale.md) | pass |
| SP-10 | User | "Use Bluetooth instead (no helper)" | BtGateScreen | n/a | `ui/screen{screen:BT_GATE}` | gate | "back to Wi-Fi" | L2 | android:SetupPhoneJourney#main; android:SetupPhoneJourney#backToWifi | pass |
| SP-11 | User | Gate: "Connect with Bluetooth" | dialogs: Nearby devices; Notifications (13+); all granted → REMOTE | n/a | `permission/request`, `permission/result{ctx:gate}` | remote, pill "Choose a device" | n/a | L2 | android:SetupPhoneJourney#main; android:FirstRunGateTest#deniedKeepsGateThenGrantResolvesIt | pass |
| SP-12 | User | Denies Nearby once | gate stays, button asks again | n/a | `permission/result{granted:false}` | gate copy unchanged | tap again | L2 | android:SetupPhoneJourney#deniedOnce | pass |
| SP-13 | User | Denies Nearby twice (permanent) | button opens app details Settings | `openSettings` flag not saved across recreation (setup-phone-D5) | `permission/result`; shouldShowRationale false | "Open Settings" | grant in Settings, return (refresh on resume) | L2 | android:SetupPhoneJourney#deniedTwice | pass |
| SP-14 | User | Denies notifications, allows Nearby | remote works; no link notification | n/a | `diag` Perms `notifications=false` | no notification | Settings | L2 | android:SetupPhoneJourney#notifDenied | pass |
| SP-15 | Android | Bluetooth off | gate asks to enable | n/a | `transport/bt.state` | "Turn on Bluetooth" | enable | L2 | android:SetupPhoneJourney#btOff; android:BtGateTest#permittedButOffAsksToEnable; android:FirstRunGateTest#bluetoothOffShowsGateUntilAdapterTurnsOn | pass |
| SP-16 | Android | No Bluetooth adapter | Unsupported; Wi-Fi path still offered | n/a | BtGate UNSUPPORTED | unsupported copy | helper path | L2 | android:SetupPhoneJourney#unsupported; android:FirstRunTest#noBluetoothAdapterStillOffersTheMac | pass |
| SP-17 | App | Upgrade from <= 0.5 with a Bluetooth host, or legacy grants | keeps the Bluetooth path, no FindMacScreen | n/a | `ui/screen{screen:REMOTE}` | remote | n/a | L0 | android:FirstRunTest#existingUsersKeepTheirPath; android:SetupPhoneJourney#legacyPermitted | pass |
| SP-18 | App | Android 9-11 fresh install | Wi-Fi-first like 12+ | skips FindMacScreen (setup-phone-D4) | `ui/screen` | gate first | n/a | L2 | android:SetupPhoneJourney#api28to30 | fail (D-SP-4) |
| SP-19 | App | Helper path asks POST_NOTIFICATIONS | asked once when a notification would first show (B4 prerequisite) | never asked on the helper path | Perms `notifications=false` on 13+; phase 1b `notification/blocked` | no media notification (J6 DU-14) | Settings › Notifications | L2 | TODO-SP-19 | fail (D-SP-3, U4) |
| SP-20 | App | Bluetooth path on a real phone and Mac | gate, dialogs, HID pairing per `first-run-bt.md` | OEM dialog differences | `permission/result` | per row | per row | L4 | MANUAL-14 | untested |

## Defects
- D-SP-1 (setup-phone-D1, D2, D6) artboards: gate as first screen, old gate copy, APK v0.4.0.
- D-SP-2 Connect card NeedBluetooth: when Nearby is permanently denied, `perms.launch` returns denied silently; no Settings route (ConnectUi.kt NeedBluetooth). See J4 FC-6.
- D-SP-3 (setup-phone-D3) POST_NOTIFICATIONS is requested only from the Bluetooth gate (`ui/App.kt`); B4's notification needs it on the helper path.
- D-SP-4 (setup-phone-D4) Android 9-11 skip the Wi-Fi-first first run.
- D-SP-5 (setup-phone-D5) permanent-denial `openSettings` flag lost on activity recreation.
