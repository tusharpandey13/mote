# mote: technical architecture research

Scope: HOW to build an Android Bluetooth remote for macOS.
Evidence note: written from API/spec knowledge (Android `BluetoothHidDevice` docs, USB HID Usage Tables 1.4, Apple IOKit/CGEvent behavior, community reports from Karabiner/ESP32-BLE/Keychron/Hid-device-sample issues). No live web verification was done in this pass. Claims tagged **[V]** are uncertain and MUST be checked in the spike (section 9). Everything else is high-confidence.

## 1. Options at a glance

| | A. Pure BT HID | B. Companion agent + transport | C. Hybrid (HID base + optional agent) |
|---|---|---|---|
| Mac-side install | None | Swift menu-bar app, Accessibility perm, notarization | None for baseline; agent optional |
| Works at lock screen / login / FileVault prompt | Yes (HID is OS-level) [V pre-boot] | No (agent not running / no event tap target) | Baseline yes |
| Media + volume + mute | Yes (Consumer page) | Yes (NX system-defined events) | Yes |
| Brightness | Maybe via Consumer 0x6F/0x70 [V] | Yes (NX key 2/3; DisplayServices private API) | HID attempt, agent fallback |
| Mouse/scroll | Relative + wheel + AC Pan | Anything CGEvent can do | HID |
| True trackpad gestures | No (emulate via shortcuts) | Partly (private/fragile) | Shortcut emulation |
| Fn key | No (Apple Fn is vendor usage) | Partial (flag only) | Agent only |
| Non-US text | Weak (physical keys) | Strong (Unicode string events) | Agent for text |
| App-aware macros | No | Yes (frontmost app, AppleScript, shell) | Agent only |
| Android reliability risk | High: OEM HID-device profile gaps | Low (plain BLE/Wi-Fi) | Medium |
| Mac reliability risk | Low | Medium (permissions, signing, updates) | Low |
| Build cost | ~2-3 wks | ~4-6 wks (two codebases) | A + ~2-3 wks incremental |
| Cost if wrong | Android OEM gap kills product on that phone | Mac app maintenance burden forever | Bounded: agent is optional |

Asymmetric costs (explicit):
- A's downside is concentrated in ONE place: whether the user's phone supports `BluetoothHidDevice` at all. Spike answers that in a day. Upside is huge: zero Mac install, works pre-login, no network.
- B's downside is permanent: signed/notarized Mac app, TCC permission resets on update, private API breakage across macOS releases, two release pipelines. Upside (Unicode text, brightness certainty, app-aware macros) is mostly "nice", not core.
- C defers B's cost until the HID baseline proves which gaps actually hurt. Reversible; B work is additive and never blocks A.

## 2. Option A: Pure Bluetooth HID (`BluetoothHidDevice`)

### API shape
- `BluetoothAdapter.getProfileProxy(ctx, listener, BluetoothProfile.HID_DEVICE)` -> `BluetoothHidDevice` (API 28).
- `registerApp(BluetoothHidDeviceAppSqosSettings? , BluetoothHidDeviceAppQosSettings in/out, sdp: BluetoothHidDeviceAppSdpSettings(name, desc, provider, subclass, descriptorBytes), executor, callback)`.
- `sendReport(device, reportId, bytes)`; `connect(device)` / `disconnect`; callbacks `onConnectionStateChanged`, `onGetReport`, `onSetReport`, `onInterruptData`.
- Only one app can be registered as HID device at a time system-wide. If another app (or a stale registration after crash) holds it, `registerApp` fails; always `unregisterApp` on teardown.
- Classic Bluetooth (BR/EDR) HID, not HID-over-GATT. Mac sees a classic keyboard/mouse.
- Pairing: phone must be discoverable (`ACTION_REQUEST_DISCOVERABLE`, 120s typical) and Mac pairs from System Settings > Bluetooth. Android-initiated pairing also works. After bonding, either side can reconnect.

### Device/OEM support [V on every row]
| Vendor | Expectation |
|---|---|
| Pixel / AOSP-like | Works; reference behavior. Use as dev baseline |
| Samsung | Historically worked on One UI 2+ after early Android 9 breakage; reports of HID profile disappearing/reconnect flakiness. Must test on a real Samsung |
| Xiaomi/Oppo/Vivo/OnePlus | Mixed; some ROMs strip or break the HID_DEVICE profile (`getProfileProxy` never calls `onServiceConnected`, or `registerApp` returns false) |
| Huawei (no GMS) | Frequently unsupported |
| Android emulator | Unsupported; need real hardware |
Mitigation: runtime capability check on first launch (proxy connects AND registerApp succeeds) with a clear "your phone does not support HID device mode" screen; this is exactly where option C's agent transport becomes the fallback for unsupported phones.

### macOS acceptance
- Generic composite HID keyboard+mouse pairs fine; this is what ESP32-BLE-Keyboard-class and Android HID apps (e.g. "Bluetooth Keyboard & Mouse" style apps) rely on.
- First connect of an unknown keyboard may trigger **Keyboard Setup Assistant** (asks user to press keys to pick ANSI/ISO/JIS). One-time; dismissable. Layout detected there affects which physical key = which character. [V]
- Reconnect: macOS keeps the bond and will try to reconnect; Android side needs the app/service alive and registered (HID device is lost when the app process dies or Bluetooth toggles). Expect: Mac initiates -> Android must have app registered; or app calls `connect(macDevice)` from a bonded-devices list. This is the single biggest UX-quality item. Test sleep/wake of Mac and phone Doze.
- Lock screen / login window: HID keyboard and mouse work at the login window and lock screen (OS-level). Pre-boot FileVault unlock with a third-party BT device is unreliable on Intel and generally keyboards must be wired/Apple on Apple Silicon recovery [V]. Do not promise it.
- Latency: classic BT HID sniff mode can add 10-30 ms jitter. Android app should request low-latency QoS in `registerApp` (token rate/latency) and rate-limit mouse reports to ~100-125 Hz with coalescing of deltas. Perceptually fine for remote use, not gaming.

### What macOS honors from generic HID
Keyboard (usage page 0x07): all letters/digits/F1-F24/arrows/Home/End/PgUp/PgDn/modifiers. GUI left/right (0xE3/0xE7) = Command, Alt (0xE2/0xE6) = Option, Control, Shift. Caps Lock works.
Consumer (page 0x0C) [high confidence unless marked]:
- Play/Pause 0xCD, Scan Next 0xB5, Scan Prev 0xB6, Stop 0xB7, Mute 0xE2, Vol+ 0xE9, Vol- 0xEA: work, volume HUD appears.
- Brightness: Display Brightness Increment/Decrement 0x6F / 0x70 are what macOS exposes for third-party keyboards in Karabiner's consumer key map and many non-Apple keyboards; I believe they change built-in-display brightness [V]. If not, fallback is Apple vendor top-case usages (page 0xFF01 / 0x00FF) which a generic device cannot use without spoofing Apple VID/PID (do not spoof) or fn+F1/F2 which requires Fn (not sendable).
- Eject 0xB8, Keyboard-backlight usages: unreliable [V]. Launchpad/Mission Control/Spotlight dedicated keys are Apple-vendor only; use shortcuts instead (Ctrl+Up, Cmd+Space).
- F1/F2 as brightness: with "Use F1, F2 as standard function keys" off, plain F1/F2 from a third-party keyboard do NOT map to brightness (that is an Apple-keyboard + Fn behavior), so rely on 0x6F/0x70.
Mouse (page 0x01/0x09 + 0x0C AC Pan 0x238): relative X/Y, 3-5 buttons, vertical wheel, horizontal pan. macOS applies pointer acceleration and "natural scrolling" settings to it as with any mouse.
No Apple Magic Trackpad emulation: the multi-touch protocol is Apple-proprietary and generic Windows PTP digitizer descriptors are not turned into a trackpad by macOS (absolute digitizers are largely ignored/treated as tablet) [V]. Plan on mouse semantics.

### Gesture emulation without a real trackpad
Compute touchpad gestures on the phone, emit as HID:
| Gesture | Emulation |
|---|---|
| Move | relative X/Y with phone-side acceleration curve |
| Tap / double-tap / drag | button 1 down/up; tap-and-hold-drag = button held |
| Two-finger tap (right click) | button 2 |
| Two-finger scroll | wheel + AC Pan (use small ticks, resolution multiplier optional) |
| 3-finger swipe up/left/right | Ctrl+Up (Mission Control), Ctrl+Left/Right (Spaces); needs those shortcuts enabled (default on) |
| Pinch zoom | Cmd+ / Cmd- (per-app), or Ctrl+wheel if Accessibility zoom is on |
| Smart zoom, force click | not possible |

### Composite descriptor sketch (report IDs)
```
Keyboard  ID 1: [mods:8 bits][reserved:1B][keys:6B]        -> 8 bytes payload (boot-compatible layout)
  (alt: NKRO 1 byte mods + 16-byte bitmap for usages 0x00-0x7F, avoids 6KRO limit for chords)
Mouse     ID 2: [buttons:5 bits + 3 pad][X:int16][Y:int16][wheel:int8][pan:int8]  -> 7 bytes
Consumer  ID 3: [usage:uint16] (selector 0x0000-0x03FF, 0 = release)
```
Descriptor bytes, abridged:
```
05 01 09 06 A1 01 85 01 05 07 19 E0 29 E7 15 00 25 01 75 01 95 08 81 02
  75 08 95 01 81 01  95 06 75 08 15 00 25 65 05 07 19 00 29 65 81 00  C0   ; keyboard
05 01 09 02 A1 01 85 02 09 01 A1 00 05 09 19 01 29 05 15 00 25 01 95 05 75 01 81 02
  95 01 75 03 81 01  05 01 09 30 09 31 16 01 F8 26 FF 07 75 10 95 02 81 06
  09 38 15 81 25 7F 75 08 95 01 81 06  05 0C 0A 38 02 95 01 81 06  C0 C0   ; mouse
05 0C 09 01 A1 01 85 03 19 00 2A FF 03 15 00 26 FF 03 75 10 95 01 81 00 C0     ; consumer
```
(Validate with a descriptor linter / `hidrd-convert`; keep the final descriptor in a Kotlin const with comments.) Press = send usage, release = send 0. Chords: set modifier byte + key, then release. Add LED output report only if needed (skip for MVP).
SDP: subclass `SUBCLASS1_COMBO` (0xC0), name "mote". Do not set Apple VID/PID.

### Option A verdict
Best baseline. Risks concentrated in: OEM support, reconnect-after-sleep, brightness usage, non-US text. All spikeable in ~2 days.

## 3. Option B: Companion macOS agent + transport

### Transport comparison
| Transport | Mac side | Android side | Verdict |
|---|---|---|---|
| LAN WebSocket + Bonjour (`_mote._tcp`) | `Network.framework` listener, easy | OkHttp + `NsdManager` | Simplest, lowest latency (~5 ms). Requires same Wi-Fi; no use away from LAN; client isolation on guest/hotel Wi-Fi breaks it |
| BLE GATT | Mac `CBPeripheralManager` (GATT server), Android is central | `BluetoothGatt` write-without-response | Works without Wi-Fi; MTU ~185-512; pairing/encryption for security; moderate work; works at login window? No (agent not running) |
| Classic RFCOMM | `IOBluetoothRFCOMMChannel` | `createRfcommSocketToServiceRecord` | IOBluetooth is not formally removed but is legacy, lightly maintained, flaky on Apple Silicon/Sonoma+ [V]. Avoid |
| Wi-Fi Direct / USB (adb) | n/a | n/a | Over-scoped |

Recommendation inside B: LAN WebSocket first (cheapest), BLE GATT only if "no Wi-Fi" matters. Authenticate with a pairing code and a shared key (e.g. QR on Mac menu bar shown once; HMAC each message) to avoid anyone on the LAN driving the Mac.

### Mac injection mechanics
- Keys/mouse/scroll: `CGEvent(keyboardEventSource:virtualKey:keyDown:)`, `.flags` for modifiers, `.post(tap: .cghidEventTap)`. Needs Accessibility (TCC) permission; prompt via `AXIsProcessTrustedWithOptions`.
- Unicode text: `CGEvent.keyboardSetUnicodeString` solves non-US layouts entirely.
- Media keys: `NSEvent.otherEvent(with: .systemDefined, subtype: 8, data1: (keyType << 16) | (0xA00 or 0xB00 <<8))`, post `cgEvent` to `.cghidEventTap`. Key types (NX_KEYTYPE_*): SOUND_UP 0, SOUND_DOWN 1, BRIGHTNESS_UP 2, BRIGHTNESS_DOWN 3, MUTE 7, PLAY 16, NEXT 17, PREVIOUS 18. Works without extra entitlement beyond Accessibility. Brightness NX events work for built-in and Apple displays [V on newest macOS].
- Brightness alternative: private `DisplayServices.framework` (`DisplayServicesSetBrightness`), as used by MonitorControl/Lunar; external monitors need DDC/CI (IOAVService on Apple Silicon). Private APIs = breakage risk.
- Gestures: real gesture events (`CGEventCreate` with undocumented gesture types) are private and version-fragile (BetterTouchTool/Jitouch territory). Do not depend on them; shortcut emulation as in section 2.
- Extras: frontmost-app detection (`NSWorkspace`), AppleScript/`osascript`, URL schemes, shell, Shortcuts.app via `shortcuts run`.
- Distribution: Developer ID signing + notarization; not App Store (sandbox blocks CGEvent posting). Login item via `SMAppService`.
- Does not work: lock screen, login window, before user session (event posting from user context). This is the core functional gap vs A.

## 4. Option C: Hybrid (recommended)
- HID is the product's core path and the only one needed for MVP.
- Agent optional ("mote helper"): same phone app, a second `Transport` implementation behind one interface.
- Capability routing per action: `Action -> preferred transport`. E.g. media/mouse/keys -> HID; Unicode text, app-aware macros, brightness fallback, "run Shortcut" -> agent when connected; if agent absent, degrade to HID equivalents (US-mapped typing, 0x6F/0x70).
- Agent gives a fallback for phones whose OEM lacks `BluetoothHidDevice`, converting the biggest risk of A into a degraded-but-working mode.

## 5. Android stack
- Kotlin, Jetpack Compose (Material 3), single-activity, MVVM + `StateFlow`; Hilt optional (skip for small app: manual DI = less complexity).
- minSdk 28 (HID device API floor; ~99% of devices). targetSdk = latest (35/36). compileSdk latest.
- Persistence: DataStore (settings, last-connected host) + Room or plain JSON files for macros (JSON files = exportable/shareable, simplest) with kotlinx.serialization.
- Coroutines: a single `HidController` actor with a conflated channel for mouse deltas and an ordered channel for key/consumer reports.
- Touch: `pointerInput` with raw multi-pointer handling (`awaitPointerEventScope`) for the touchpad; do not use gesture detectors for 2-finger logic.
- Hardware buttons: optionally map phone volume keys to Mac volume via `onKeyDown` in foreground (requires screen on).
- Testing: unit-test the HID encoder (pure functions, bytes in/out); real-device tests for connection.

### Permissions / service
- API 31+: `BLUETOOTH_CONNECT` (runtime; all HID calls), `BLUETOOTH_ADVERTISE` (making discoverable / being visible for pairing; the discoverable intent may work without but declare it), `BLUETOOTH_SCAN` NOT needed if you only use bonded devices (set `neverForLocation`).
- API 28-30: `BLUETOOTH`, `BLUETOOTH_ADMIN` (`maxSdkVersion="30"`).
- Foreground service type `connectedDevice` (`FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_CONNECTED_DEVICE`, API 34 requires BLUETOOTH_CONNECT held first) + `POST_NOTIFICATIONS` (API 33+). Needed to keep the HID registration alive when app is backgrounded (phone as remote while screen is off, e.g. media control). Ongoing notification can include Play/Pause/Next/Vol quick actions: low-cost high-value feature.
- Battery: radio idle cost is small; avoid wakelocks; the connection itself is the cost. Provide "stop service" in notification. Doze may drop the connection on long idle [V]; do not use a partial wakelock by default.
- Battery-optimization exemptions are a Play policy risk; document instead of requesting.

## 6. Keyboard input approach
Facts: HID sends physical key usages, not characters. macOS maps usage -> character using the current input source. Mismatch if phone assumes US and Mac is AZERTY/QWERTZ/Dvorak.
| Approach | Pros | Cons |
|---|---|---|
| Key grid (on-screen physical-key layout, modifiers sticky) | Exact for shortcuts (Cmd+C etc.), layout-independent semantics ("the C key") | Clunky for prose |
| Text box (Android IME) -> diff -> usages | Great typing UX, autocorrect, voice, swipe | Needs char->usage table per Mac layout; emoji/CJK/accents not representable; composition makes diffing tricky |
| Hardware KeyEvent passthrough (USB/BT keyboard on phone) | Trivial map from `KeyEvent` keycode -> usage | Niche |
| Agent Unicode events | Layout-proof | Needs agent (option B/C) |
MVP: key grid + a "type mode" text field (US ANSI table, plus a user-selected Mac layout table for QWERTZ/AZERTY/UK/Dvorak shipped as data files; shift/altgr combos in the table). Unrepresentable chars get skipped with a toast. Use `TextWatcher`-style delta (commit only, ignore composing region; offer a "send on Enter" mode to sidestep composition). V2: agent Unicode path.
Modifiers: sticky (tap once), locked (double tap), hold. Fn: not sendable over generic HID; offer F-key row and document the limitation (agent can set Fn flag).
Note Cmd+key sequences by Android soft keyboard are impossible; provide chord buttons.

## 7. Macro model (data format)
Plain JSON, versioned, portable. Steps are a small closed set; each compiles to HID report sequences.
```json
{ "v": 1, "id": "uuid", "name": "Screenshot to clipboard", "icon": "camera",
  "scope": { "app": null },
  "steps": [
    { "t": "chord", "mods": ["cmd","ctrl","shift"], "key": "4" },
    { "t": "delay", "ms": 150 },
    { "t": "key", "key": "space", "hold_ms": 40 },
    { "t": "text", "s": "hello", "layout": "us" },
    { "t": "consumer", "usage": "play_pause" },
    { "t": "mouse", "dx": 10, "dy": 0, "btn": "left", "act": "click" },
    { "t": "scroll", "dy": -3 },
    { "t": "repeat", "n": 3, "steps": [ ... ] },
    { "t": "agent", "op": "shortcut", "arg": "My Shortcut" }
  ] }
```
- `key` names = a fixed enum (HID usage names), never layout-dependent characters.
- `chord` = press mods, press key, release in reverse; `hold_ms` default 30-50 (macOS drops zero-length presses occasionally [V]).
- `agent` steps are skipped (with warning) when no helper is connected; the schema is thus forward-compatible with option C.
- Run engine: cancellable coroutine, inter-report minimum gap ~8-10 ms; "stop" button; no loops without a max count/timeout.
- Hotkey buttons are just macros with one `chord` step; keep a single concept.

## 8. Recommended architecture and phases
Architecture: Android app = UI (Compose) -> `ActionRouter` -> `Transport` interface { `HidTransport` (BluetoothHidDevice, always), `AgentTransport` (later) }. Pure-Kotlin `HidEncoder` (usage tables, descriptor, report builders) with unit tests. One foreground service owns the registration and connection state.

| Phase | Scope | Cost | Exit criterion |
|---|---|---|---|
| 0 Spike (2-3 d) | Register composite descriptor, pair with Mac, send play/vol/mute/brightness/keys/mouse. Test on Pixel + Samsung | Low | Answers section 9 items |
| 1 MVP (~2 wks) | Pairing/reconnect UX, media + volume + brightness buttons, D-pad (arrows/Enter/Esc), touchpad (move/tap/right-click/2-finger scroll/drag), key grid with sticky modifiers, foreground service + notification controls | Med | Daily-usable remote on target phone, reconnects after Mac sleep |
| 2 v2 (~2 wks) | Macro editor/runner (JSON), type-mode text with layout tables, 3-finger/pinch shortcut gestures, home-screen widget/Quick Settings tile, import/export | Med | Macros survive app restarts and chord reliably |
| 3 v3 (optional, ~3 wks) | Swift menu-bar helper over LAN WebSocket (+QR pairing): Unicode text, app-aware macros, brightness fallback, Shortcuts | Higher | Only if spike/usage shows a gap that matters |
Stop condition for Phase 3: do not start unless a concrete user pain (non-US text, missing brightness, unsupported phone) is confirmed.

## 9. Open risks and spike checklist
| # | Risk | Test | Impact if bad | Fallback |
|---|---|---|---|---|
| 1 | Target phone/OEM lacks working HID device profile | `getProfileProxy` + `registerApp` on Pixel, Samsung, one other | Fatal on that phone | Agent transport (Phase 3 becomes required) |
| 2 | macOS ignores Consumer 0x6F/0x70 brightness | Press on built-in + external display, Apple Silicon, current macOS | Lose brightness in HID-only | Agent NX key 2/3; or document |
| 3 | Reconnect after Mac sleep/wake, phone Doze, app kill | Repeat cycles, measure time-to-usable | Core UX failure | Service + explicit "connect" action via `connect()`; CompanionDevice association [V] |
| 4 | Another app holds HID registration / registration lost on BT toggle | Kill/restart, toggle BT | Confusing failures | Detect and re-register on `onAppStatusChanged` |
| 5 | Keyboard Setup Assistant or wrong layout detection | First pair on clean Mac user | Wrong characters | Doc + layout selector |
| 6 | Mouse feel (acceleration, jitter at 100 Hz, wheel granularity) | Subjective + measure report rate | Product feel | Tune phone-side curve; coalesce |
| 7 | Zero-length key presses dropped; chord timing | Send Cmd+Space, Cmd+Tab bursts | Flaky macros | Min hold 30-50 ms, inter-step gaps |
| 8 | Works at login/lock screen | Reboot, lock screen, FileVault | Marketing claim | Limit claims to what is observed |
| 9 | Android version regressions (13/14/15/16) in HID device profile | Test on newest OS image | Support matrix | Document min versions |
| 10 | Play Store policy (foreground service type, BT permissions) | Pre-launch review | Delisting | Sideload/F-Droid distribution |

## 10. Recommendation (one line)
Build C, starting with A only: Kotlin/Compose, minSdk 28, BluetoothHidDevice composite (keyboard + relative mouse w/ wheel+pan + consumer) in a connectedDevice foreground service; emulate gestures with shortcuts; treat the Swift helper as an optional later Transport added only if the spike or real use shows a gap. Run the Phase 0 spike first, since risks 1-3 determine everything else.
