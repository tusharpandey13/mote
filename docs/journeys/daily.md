# J6 daily: daily use

Phase 2 spec (format: [README.md](README.md)). Derived from `android/.../net/{Net,WsTransport,Proto}.kt`, `ui/HostsUi.kt`, `labs/Blockers.kt`, `helper/Sources/MoteHelper/{Hub,MenuBar,Diagnostics}.swift`, and the phase-1b work in progress (`labs/context/NpNotif.kt`, uncommitted on branch agent-ac9551ef702e6068e at the time of writing). Replaces the phase-1 draft (daily-D1..D6 carried); folds in `media.md`, `macros.md`, `spaces.md`, `settings.md` at the level of entry points (their per-control rows stay in those drafts until phase 3). Labs features other than now-playing: second pass.

## UNDEFINED
- U1 Media notification over a Bluetooth-only link (now: none).
- U2 Media notification after the app is backgrounded (socket closes after 30 s; no foreground service).
- U3 Media notification gated on Labs › Smart remote vs a core feature.
- U4 When the helper path asks for POST_NOTIFICATIONS.
- U19 B3: what the phone must show, and how fast, when Labs is on both sides and a video plays.
- U20 Lock-screen visibility of the media notification.

## Goal
With a connected Mac the user controls media, pointer, keys, macros and Spaces from the phone; every control either works or explains why not; while something plays on the Mac the phone shows it in a media notification (B4).

## Preconditions
| Dimension | Values covered |
|---|---|
| Link | Wi-Fi helper / tailnet helper / Bluetooth HID only |
| Mac input | Ready / Paused (menu ⌘P) / Accessibility missing or stale / `--dry-run` |
| Labs | off both sides / phone only / Mac only (`helper-lab-off`) / both |
| Now playing on Mac | nothing / Music / Spotify / browser video (YouTube) with Automation granted, ask, denied |
| Android notifications | granted / denied / never asked (helper path) / "nowplaying" channel off |
| Phone | foreground / background < 30 s / background > 30 s / locked |
| Helper version | current / older than protocol 1.5 (`helper-too-old`) |

## Success criteria
- Input reaches the Mac (`input/summary` counts) with no error; paused/ax states shown on the phone within one Hub poll (2 s).
- Every disabled Labs surface has a `labs/blocked{code}` reason visible on screen.
- Media notification shown while connected to a helper with now-playing, updated on change, hidden with a logged reason.

## Main path
| ID | Actor | Step | Expected | Failure mode | Detected by | User sees | Recovery | Level | Test | Status |
|---|---|---|---|---|---|---|---|---|---|---|
| DU-1 | User | Media caps (play/pause, next, volume, D-pad, hold repeat) over Wi-Fi | keys injected (dry-run: logged) | not linked: caps disabled | helper `input/summary{counts}`; `protocol/in` | caps react | n/a | L2 | android:MediaJourney#main; e2e:media.spec.ts "every media control…"; e2e:media.spec.ts "D-pad…" | pass |
| DU-2 | User | Pointer, scroll, keyboard, Unicode text | moves/keys arrive; Unicode over helper, ASCII over BT | n/a | `input/summary` | n/a | n/a | L2 | e2e:mouse.spec.ts; e2e:keys.spec.ts; android:MacrosJourney#unicode | pass |
| DU-3 | User | Macro run (confirm, click-at, unicode) | steps run in order | invalid macro | helper `input/macro` | confirm sheet / error | edit | L2 | android:MacrosJourney#main; android:MacrosJourney#invalid; e2e:macros.spec.ts | pass |
| DU-4 | User | Spaces: thumbnails, go to Space | `spaces` state, goto works; thumbnails need Screen Recording | Screen Recording missing | `diag.features.spaceThumbnails.blockedBy=screen-recording-not-granted` | Spaces without thumbnails | Setup › Screen Recording | L2 | android:SpacesJourney#main; android:SpacesJourney#nocapture; helper:DiagTests#spaceThumbnailsBlockedWithoutScreenRecording; android:HelperIntegrationTest#spacesStateThumbnailsAndGoto | pass |
| DU-5 | User | Pause input on the Mac (⌘P) | helper refuses input `paused`; phone notice | n/a | Hub `state{paused:true}`; `input/blocked{reason:paused}`; phone `session/state{paused}` | "Input paused on the Mac" notice | Resume (menu) | L2 | android:MediaJourney#paused; e2e:media.spec.ts "input paused on the Mac -> notice shown" | pass |
| DU-6 | macOS | Accessibility revoked or stale | input refused `ax` | dry-run cannot simulate (D-SM-2) | Hub `state{ax:false}`; `input/blocked{reason:ax}`; phone `session/state{ax:false}` | "Mote Helper needs Accessibility on the Mac" | re-grant (J2 SM-4) | L3 | MANUAL-18 | untested |
| DU-7 | User | Switch Macs from Devices | `Net.select`, new candidates, old session closed | n/a | `transport/select` | pill name changes | n/a | L2 | android:DailyJourney#switchMacs; android:ReconnectJourney#main | pass |
| DU-8 | App | Link notification | reflects the selected link | Wi-Fi Mac: "Ready to pair" or none (daily-D1) | `lifecycle/service` | notification text | n/a | L2 | android:DailyJourney#notif; android:DailyJourney#notifBt | fail (D-DU-1) |
| DU-9 | App | Helper older than the app (no protocol 1.5 diag) | Diagnostics asks to update the helper | n/a | `HelperDiag` err; `labs/blocked{code:helper-too-old}` | "update Mote Helper" | J7 | L2 | android:DiagnosticsTest#diagnosticsShowsPhoneStateAndAsksToUpdateNoHelper | pass |
| DU-10 | App | Labs Smart remote on, Mac lab off | blocker `helper-lab-off` with where to enable | n/a | `labs/blocked{code:helper-lab-off}` | blocker copy | Mac menu › Labs | L2 | android:BlockersTest#helperLabOffSaysWhere; android:SmartRemoteJourney#helperLabOff | pass |
| DU-11 | App | Labs over Bluetooth only | blocker `bluetooth-only` | n/a | `labs/blocked{code:bluetooth-only}` | blocker copy | connect via helper | L2 | android:BlockersTest#bluetoothSaysWhy; android:DiagnosticsTest#smartRemoteOverBluetoothSaysWhy | pass |
| DU-12 | Helper | Browser video, Automation not yet asked / denied / no signal | blocker `automation-ask` / `automation-denied` / `no-signal` | n/a | `labs/blocked{code}`; `diag.features.labs.context{blockedBy:automation-not-granted,fix:open-automation-settings}` | blocker copy naming the browser | Privacy › Automation | L2 | android:BlockersTest#browserWithoutAutomationIsNotSilent; android:HelperIntegrationTest#smartRemoteBrowserAskedAutomationExplainsItself; android:SmartRemoteJourney#automationAsk; android:SmartRemoteJourney#automationDenied; android:SmartRemoteJourney#browserNoSignal | pass |
| DU-13 | User | B3: Labs on both sides, YouTube playing in a browser | phone switches to the video profile or shows a blocker (DU-12) within 2 s | reported: no phone UI change at all | `labs/subscribe`, `labs/push`; `labs/blocked`; helper `labs/push` count | nothing changes | unknown | L3 | android:BlockersTest#noReplyIsABlockerAfterTheWait; android:NpNotifPolicyTest#helperNowPlayingOffIsAReason | pass (U19: blocker `no-reply` after 2 s; MANUAL-19 for the real browser case) |
| DU-14 | App | B4: helper connected, Smart remote lab on, now-playing present, notifications allowed | MediaStyle notification (channel "nowplaying", low importance, id 2): title, artist, app, art, play state, seek bar if `canSeek` | not built in 0.6 | phase 1b `notification/show{…}` | no notification | n/a | L2 | android:NpNotificationJourney#main | pass (core, no Labs gate; mediaPlayback service) |
| DU-15 | App | Now-playing changes (track, pause, seek) | notification updates; position extrapolated from Mac timestamps | n/a | `notification/update` | updated notification | n/a | L0 | android:NowPlayingReducerTest#extrapolatesFromTheMacsTimestamps; android:NowPlayingReducerTest#pausedDoesNotMove; android:NpNotifPolicyTest#pausedStaysAndStopsMoving | pass |
| DU-16 | User | Notification actions play/pause, next, previous, seek | `Remote.consumer` / `SmartRemote.seekTo` sent to the helper | not linked | `notification/action{name,sent}`; helper `input/summary` | Mac reacts | n/a | L2 | android:NpNotificationJourney#main (N4, N5) | pass |
| DU-17 | App | Hide reasons: lab-off, no-helper, disconnected, nothing-playing, no-permission | notification removed, reason logged | n/a | `notification/hide{reason}`; `notification/blocked` for no-permission | notification gone; Diagnostics row says why | per reason | L0 | android:NpNotifPolicyTest#hiddenReasonsInPriorityOrder; android:NpNotifPolicyTest#helperNowPlayingOffIsAReason; android:NpNotificationJourney#settingOff | pass (reasons now setting-off, disconnected, nothing-playing, helper-np-off, idle-timeout, no-permission) |
| DU-18 | App | Notifications never asked on the helper path | asked when the first notification would show | never asked (D-SP-3) | `notification/blocked{reason:no-permission}` | nothing | Settings | L2 | android:UxGatesTest#notificationAskOnlyOnceOnAndroid13Plus; android:NpNotificationJourney#main; android:NotifyAskTest#firstRunExplainsAndAsksOnce | pass (U4: asked in first-run setup; Diagnostics row if denied) |
| DU-19 | App | Bluetooth-only link with media playing | Bluetooth-only link: media notification "Mote · Bluetooth" with play/pause toggle, previous, next sent as HID consumer keys; no metadata, no state (U1, decided 2026-11-10) | phase 1b: none | `notification/hide{reason:no-helper}` | nothing | n/a | L0 | android:NpNotifPolicyTest#bluetoothOnlyIsTransportOnly; android:NpNotificationJourney#bluetoothOnly | pass (U1: "Mote · Bluetooth", transport buttons, no metadata) |
| DU-20 | Android | App backgrounded > 30 s while playing | Foreground service (connectedDevice / mediaPlayback) while something plays; notification survives backgrounding and the lock screen; service stops when playback stops or after 10 min paused (U2, decided 2026-11-10) | socket closed (Net 30 s) → `disconnected` → notification gone | `lifecycle/foreground{on:false}`; `notification/hide{reason:disconnected}` (`idle-timeout` after the paused timeout) | notification vanishes | reopen app | L2 | android:NpNotificationJourney#backgroundKeepsPlaying; android:NpNotificationJourney#pausedTimeout; android:NpNotifPolicyTest#pausedTimeoutHides | pass (U2: foreground service, stops after 10 min paused) |
| DU-21 | User | Lock screen and Bluetooth headset buttons with the notification | controls on lock screen with VISIBILITY_PUBLIC (title, artist, art); headset buttons route to the MediaSession (U20, decided 2026-11-10) | OEM | `notification/action` | lock-screen controls | n/a | L4 | MANUAL-20 (channel lockscreenVisibility asserted in android:NpNotificationJourney#main N3b) | untested (U20: VISIBILITY_PUBLIC; lock screen itself is manual) |
| DU-22 | User | Daily use on real phone and Mac for a day (owner) | DU-1..7, DU-13..16 | any | phone Diagnostics, `MoteHelper --diag` | per row | per row | L4 | MANUAL-21 | untested |

## Defects
- D-DU-1 (daily-D1) link notification reflects only Bluetooth HID; with a Wi-Fi Mac it says "Ready to pair" or is absent.
- D-DU-2 B4 notification depends on POST_NOTIFICATIONS, which the helper path never asks for (D-SP-3).
- D-DU-3 Phase 1b gates the notification on Labs › Smart remote and has no foreground service: it disappears 30 s after the app backgrounds, which defeats the lock-screen use (U2, U3).
- D-DU-4 (daily-D2..D6) artboard copy for asleep / no-helper / Searching card / Devices buttons / route order differs from code.
