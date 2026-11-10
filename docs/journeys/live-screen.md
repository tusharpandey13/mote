# live-screen: 12. Live screen

Artboard: `design/mote-v1/journey-live-screen.html` (Preview (Labs)). Code: `android/.../labs/screen/LiveScreenPage.kt`, `ScreenProto.kt`, `labs/Labs.kt`, `ui/MenuScreens.kt:40,81-83`, `ui/App.kt:249`, `net/WsTransport.kt:121-122,282`; helper `Labs/LiveScreen.swift`, `Hub.swift:248,276-285`, `Config.swift` (`--fake-screen`, `labsLiveScreen`), `main.swift:69-72`, `MenuBar.swift:50-53,72-74,149-151,189-194`, `Injector.swift:202`. Docs: `docs/labs/live-screen.md`, `docs/design/design-language.md` (User journeys).
Test: `e2e/journeys` → `dev.mote.remote.journeys.LiveScreenJourney` (one @Test per path below).

## Preconditions
- P1. App: fresh data, connected to the dry-run helper over Wi-Fi by the `wifi-helper` main path (FindMacScreen "Connect to <Mac>" with `TestSeam.fakeProximity`); next-0.6 keeps setup on the Bluetooth offer, so the precondition taps "Skip" until the pill `Device: <Mac> via Wi-Fi. Tap to choose` shows Debug build `dev.mote.remote.dev` (debug = stats badge shown). Settings › Labs › switch contentDescription `Live screen (preview)` = On (DataStore `labs`, key `live_screen`). DataStore `labs_screen` empty (`lan_warning_seen` unset). No permissions beyond what linking needs.
- P2. Phone: Wi-Fi on ("AndroidWifi"), no Tailscale VPN (so the route is LAN, not TAILNET).
- P3. Mac / helper: `MoteHelper --dry-run --headless --no-ble --memory-store --port N --stdin-control --fake-screen` (`--fake-screen` forces Labs › Live screen on and streams synthetic frames of a 1512x982 pt display at (−1512, −200), scale 2). Startup log `[mote] fake screen: fake-screen 1512x982 pt at (-1512.0, -200.0) scale 2.0`. Input not paused, sharing not paused.
- P4. Network: same LAN (phone 10.0.2.17 → helper 10.0.2.2:N; helper sees transport "Wi-Fi").

## Main path `live-screen`
| # | Actor | Action (exact) | Expected observable (exact) | Seam |
|---|---|---|---|---|
| S1 | phone user | Tap top-bar "Menu" | Menu sheet "Mote" with row "Screen (preview)" / "See the Mac's screen, tap to click" (clickLabel "Screen (preview)") between "Settings" and "About Mote" | real |
| S2 | phone user | Tap "Screen (preview)" | Full page (not a sheet): Back (contentDescription "Back"), title "Screen", icon contentDescription "Pause sharing"; notice "Your screen travels unencrypted" / "On plain Wi-Fi, anyone on this network who can capture traffic could see the Mac's screen. Tailscale encrypts it. Frames are never saved." / button "Show the screen". No test tag. | real (see live-screen-D1, -D2) |
| S3 | phone user | Tap "Show the screen" | DataStore `labs_screen.lan_warning_seen=true`. Phone sends `{"t":"labs.screen.start","w":960,"fps":12,"credits":2}`; helper replies `labs.screen.started` (`"display":"fake-screen","w":960,"h":623,"fps":8,"credits":2,"encrypted":false,"fake":true`); helper `[mote] screen: streaming to <device name> via Wi-Fi` | real |
| S4 | system | (≤ 1 s) | Viewer box contentDescription "Mac screen. Tap to click, hold to right-click, pinch to zoom." shows "Waiting for the first frame…" until the first `labs.screen.frame`, then the synthetic frame (gradient, bar, "#n") with a white cursor ring; debug badge contentDescription "Stream stats", text `<n> fps · <n> ms · <n> KiB`. Phone sends `labs.screen.ack` per frame and every 500 ms | real |
| S5 | Mac (helper) | — | Menu-bar title " ● Sharing screen", tooltip "Mote Helper: sharing the screen with <device name>"; menu items "● Sharing screen with <device name>", "Stop sharing screen" | faked: headless, menu unobservable; stand-in = log line of S3 and `LiveScreen.streaming` |
| S6 | phone user | Tap the centre of the viewer box | `{"t":"labs.screen.click","x":0.5,"y":0.5,"button":"left","count":1,"display":"fake-screen"}` (±1 px rounding); helper `[mote] DRY clickAt (-756.0, 291.0) b=left count=1`; accent tap ripple ≤ 400 ms | real |
| S7 | phone user | Tap the same point again within 300 ms | second click `count:2` at the first point: `[mote] DRY clickAt (-756.0, 291.0) b=left count=2` | real |
| S8 | phone user | Long-press the centre | `[mote] DRY clickAt (-756.0, 291.0) b=right count=1` | real |
| S9 | phone user | Two-finger pinch out on the viewer | Picture zooms (≤ 6x) and pans; NO click is sent (no DRY clickAt); no zoom readout text | real (artboard j12-s2 "Pinch to zoom · 2.0×" not in code, live-screen-D3) |
| S10 | phone user | Tap "Back" (or system Back) | Page closes to Media; phone sends `{"t":"labs.screen.stop"}`; helper `[mote] screen: stopped (stop) after <n> frames, <k> KiB, <d> dropped`; `LiveScreen.streaming == false` (menu-bar title "") | real |

## Postconditions
- Phone: `labs_screen.lan_warning_seen = true` (warning never shown again on any LAN address); no frames persisted.
- Helper: no viewer; capture stopped; trusted list unchanged.

## Branches
### `live-screen.second-open`: warning already acknowledged (from S2)
Preconditions delta: `lan_warning_seen = true`.
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | phone user | Menu › "Screen (preview)" | No notice; stream starts at once (S3 messages and log), then S4 | real |

### `live-screen.warning-race`: first open sends start before the warning (from S2)
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | phone user | Fresh `labs_screen`; Menu › "Screen (preview)"; do not tap anything | Spec per code: `warned` starts as `true` (`collectAsState(initial = true)`), so if the socket is Ready on first composition the helper may log `[mote] screen: streaming to …` then `[mote] screen: stopped (stop) after 0 frames …` before the notice settles | real (timing-dependent; live-screen-D4) |

### `live-screen.phone-pause`: Pause sharing on the phone (from S4)
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | phone user | Tap "Pause sharing" | `labs.screen.stop` sent; helper `[mote] screen: stopped (stop) …`; last frame dimmed (alpha 0.4), overlay "Sharing paused"; icon contentDescription "Resume sharing"; taps send nothing | real |
| B2 | phone user | Tap "Resume sharing" | new `labs.screen.start`; helper `[mote] screen: streaming to <device name> via Wi-Fi`; frames resume | real |

### `live-screen.background`: app to background (from S4)
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | system | `adb shell input keyevent KEYCODE_HOME` | lifecycle < STARTED → `labs.screen.stop`; helper `[mote] screen: stopped (stop) …` | real |
| B2 | system | bring Mote back (`am start` launcher) | Page still open (rememberSaveable), new start, `streaming to …` again | real |

### `live-screen.mac-stop`: Mac user Stop sharing screen (from S5; artboard j12-s3 "Stop sharing")
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | Mac user | Menu › "Stop sharing screen" | helper sends `{"t":"labs.screen.stopped","reason":"stop"}`; `[mote] screen: stopped (stop) …`; phone: last frame stays frozen, NO message (`explain("stop") = null`), no restart | faked: needs new stdin-control command `screen-stop` (live-screen-D5) |

### `live-screen.mac-pause`: Pause screen sharing on the Mac (from S4)
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | system | helper stdin `screen-pause` | `[mote] screen: sharing paused`, `[mote] screen: stopped (paused) …`; phone overlay "Screen sharing is paused on the Mac (Mote Helper menu › Labs)." | real |
| B2 | system | helper stdin `screen-resume` | `[mote] screen: sharing resumed`; phone keeps the message, stream does NOT restart by itself | real (live-screen-D6) |
| B3 | phone user | Tap "Pause sharing", then "Resume sharing" | new start → frames resume, message cleared on `labs.screen.started` | real |

### `live-screen.mac-paused-at-start`: sharing paused before opening (from S3)
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | system | `screen-pause`, then open the page | `err` `sharing-paused` (ref `labs.screen.start`); overlay "Screen sharing is paused on the Mac (Mote Helper menu › Labs)."; no "Waiting for the first frame…" | real |

### `live-screen.lab-off-mid`: helper lab turned off while streaming (from S4)
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | system | helper stdin `screen-lab-off` | `[mote] labs: live screen off`, `[mote] screen: stopped (off) …`; phone overlay "Turn on Labs › Live screen in the Mote Helper menu on the Mac." | real |
| B2 | system | stdin `screen-lab-on` | `[mote] labs: live screen on`; phone does not restart by itself (live-screen-D6) | real |

### `live-screen.lab-off-at-start`: helper lab off (from S3)
Preconditions delta: stdin `screen-lab-off` before opening.
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | phone user | Open the page (warning acknowledged) | `err` `labs-off`; overlay "Turn on Labs › Live screen in the Mote Helper menu on the Mac." | real |

### `live-screen.dry-run-no-fake`: plain dry run (from S3)
Preconditions delta: helper without `--fake-screen`; stdin `screen-lab-on`.
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | phone user | Open the page | `err` `unavailable`; overlay shows the helper msg "Live screen is off in a dry run (use --fake-screen)" | real |

### `live-screen.denied`: no Screen Recording permission (from S3)
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | phone user | Open the page | helper `err` `denied` "Allow Screen Recording in Mote Helper › Setup on the Mac" (next-0.6: no macOS prompt from a phone message); phone overlay ignores that msg: "Allow Screen Recording for Mote Helper on the Mac (System Settings › Privacy & Security)." (live-screen-D8) | needs user: real TCC; or faked: needs new flag `--fake-screen-denied` |

### `live-screen.taken`: another device takes over (from S4)
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | system | A second authenticated client sends `labs.screen.start` | phone gets `stopped` `taken`; overlay "Another device is viewing the screen."; helper streams to the second session | faked: second WebSocket client (node, like `helper/tests/screen.mjs`) paired to the same helper |

### `live-screen.input-paused-click`: input paused (from S6)
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | system | helper stdin `pause` | `[mote] input paused`; frames keep streaming | real |
| B2 | phone user | Tap the viewer | `err` `paused` (ref `labs.screen.click`); overlay "Screen sharing is paused on the Mac (Mote Helper menu › Labs)." (wrong: it is input pause; stays until the next `started`) ; no DRY clickAt | real (live-screen-D7) |

### `live-screen.ax`: no Accessibility (from S6)
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | phone user | Tap the viewer | `err` `ax`; overlay "Accessibility permission not granted to Mote Helper" (raw helper msg; `explain` has no `ax` case) | faked: needs new flag `--fake-no-ax` |

### `live-screen.letterbox-tap`: tap outside the picture (from S6)
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | phone user | Tap the black bar above/below the fitted image | nothing sent (`ViewMath.toNorm` → null) | real |

### `live-screen.link-drop`: helper/socket gone (from S4)
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | system | Kill the helper process (SIGTERM) | phone: socket not Ready → notice "Connecting to the Mac…" / "The screen shows once Mote Helper answers." | real |
| B2 | system | `adb shell svc wifi disable` instead | helper `[mote] screen: stopped (timeout) …` within ~2 s (lease) or `stopped (gone)` when the socket closes; phone notice "Connecting to the Mac…" | real |

### `live-screen.bluetooth`: Bluetooth link, no helper (from S2)
Preconditions delta: active transport is Bluetooth HID (`TestSeam.transport` FakeTransport with HID caps).
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | phone user | Menu › "Screen (preview)" | header shows the wordmark instead of the pause icon; notice "Live screen needs Mote Helper" / "Connect to your Mac over Wi-Fi or Tailscale with Mote Helper. Bluetooth can't carry the picture." | faked: TestSeam.transport |

### `live-screen.tailscale`: Tailscale route skips the warning (from S2)
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | phone user | Open the page while linked via a tailnet address (100.64/10) | no LAN warning; `started` `encrypted:true` | faked: needs a tailnet route on the emulator (fake Tailscale-VPN state + helper `--allow-tailscale` + tailnet peer); none exists |

### `live-screen.lab-off-phone`: Android Labs flag off (from S1)
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | phone user | Settings › Labs › `Live screen (preview)` Off; open Menu | no "Screen (preview)" row | real |

### `live-screen.old-helper`: helper without the preview
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | phone user | Open the page | overlay "Update Mote Helper on the Mac to use Live screen." (`err` `unknown`) | faked: needs an old helper build or stub server |

## Disagreements → DEFECTS.md
- `live-screen-D1` (S2): artboard j12-s1 draws a dialog "Show the Mac’s screen?" with Cancel / Start and different body; code shows an in-page notice "Your screen travels unencrypted" with one button "Show the screen", only on non-Tailscale routes.
- `live-screen-D2` (S2/S10): artboard draws the viewer as an overlay on Media/Mouse with a ✕ close and "Tap to click" label; code is a full page from ⋯ › "Screen (preview)" with Back and Pause sharing.
- `live-screen-D3` (S9): artboard "Pinch to zoom · 2.0×" zoom readout does not exist in code.
- `live-screen-D4` (warning-race): `warned` defaults to `true` before DataStore loads, so the first open can start a stream before the LAN warning is acknowledged.
- `live-screen-D5` (mac-stop): after the Mac's "Stop sharing screen" the phone shows a frozen frame with no message and no restart; artboard/menu copy "Stop sharing" vs code "Stop sharing screen".
- `live-screen-D6` (mac-pause B2, lab-off-mid B2): no automatic restart after the Mac resumes sharing or turns the lab back on.
- `live-screen-D8` (denied): phone copy points to System Settings, the next-0.6 helper to its Setup window; doc still says the start asks macOS.
- `live-screen-D7` (input-paused-click): an input-pause `paused` error on a click shows the "Screen sharing is paused…" copy; `ax` falls back to raw helper text.

## UNDEFINED
- S4: frame cadence (helper adapts fps 2-15 from 8; ScreenCaptureKit not used with `--fake-screen`): exact fps/KiB numbers in the badge.
- S3/S6: `<device name>` = the name the phone paired with (helper `Session.deviceName`), set by the pairing fixture.
- S5: macOS menu-bar rendering of " ● Sharing screen" (headless, and the artboard's red dot vs a text bullet can't be checked without a real menu).
- warning-race B1: whether a frame is actually sent depends on DataStore first-read latency vs the 125 ms first tick.
