# media: 7. Media controls

Artboard: `design/mote-v1/journey-media.html` (Shipped). Code: `android/.../ui/MediaScreen.kt` (layout, SeekCap, FavouriteStrip), `ui/SeekHold.kt`, `ui/Joystick.kt` (JoystickPad, Thumbstick), `ui/StickLogic.kt` (StickCurve, StickDriver, StickPress), `ui/Controls.kt:239-365` (MachinedSlider, EndZone), `ui/Components.kt:98-187` (Repeat, Cap), `net/WsTransport.kt` (send pipe), `net/Proto.kt`; helper `Injector.swift` (DRY lines), `Hub.swift:97-106`. Docs: `docs/protocol.md` §move/button/scroll/consumer (1.2 hold-to-seek), `docs/design/design-language.md` §User journeys.
Test: `e2e/journeys` → `dev.mote.remote.journeys.MediaJourney` (one @Test per path below). The main path runs over the helper (assert `[mote] DRY …` lines); `media.bt` repeats it over Bluetooth HID (FakeTransport `Caps.HID`, assert `Call`s). Hold durations are Compose-clock time (the test pauses the clock and advances it).

## Preconditions
- P1. App: debug `dev.mote.remote.dev`, on Media (default mode). Helper paths: linked to the dry-run helper by one-tap connect (fixture: `wifi-helper` main path with `--fake-proximity`; the optional Bluetooth step is skipped with "Skip"). Bluetooth paths: `TestSeam.transport = FakeTransport(Caps.HID)`, `TestSeam.adapter` with one bonded host, `BLUETOOTH_CONNECT` + `BLUETOOTH_ADVERTISE` + `BLUETOOTH_SCAN` + `POST_NOTIFICATIONS` granted (opens into the remote). Default settings (`pointerSpeed 1.0`). Default macros (favourites: Spaces, Return).
- P2. Phone: Wi-Fi on (helper paths). No Tailscale.
- P3. Mac / helper: `MoteHelper --dry-run --headless --memory-store --no-ble --no-bonjour --stdin-control --fake-proximity` (main path: `+ --fake-spaces 6`, so the Spaces row has one display and shows the hint, not the dev Mac's real displays), not paused.
- P4. Network: same LAN (helper paths); none (Bluetooth paths).

## Main path `media`
| # | Actor | Action (exact) | Expected observable (exact) | Seam |
|---|---|---|---|---|
| S1 | phone user | Look at Media | Texts "FAVOURITES", "Spaces", "swipe or tap a desktop", "Add favourite", engraved "SCROLL", "VOLUME"; content descriptions "Edit", "Return", "Brightness down", "Brightness up", "Scroll up one notch", "Scroll lever", "Scroll down one notch", "Direction pad", "Pointer stick. Drag to move the pointer, tap to click" (custom action "Right click"), "Volume up", "Volume lever", "Volume down", "Mute", "Previous track, hold to rewind", "Play or pause", "Next track, hold to fast-forward". No test tags | real |
| S2 | phone user | Drag from the stick centre 30 dp to the right (past the 20 dp travel) and hold 1 s, then lift | Pointer velocity = 1800 px/s × ((d−0.12)/0.88)^1.8 at speed 1.0 (120 Hz ticks). Helper `[mote] DRY move dx=<n>.0 dy=0.0` lines (summed per ≤ 16 ms frame), total dx 1500–2000; no `DRY button` line (moved past the 4 dp slop) | real |
| S3 | phone user | Tap the stick (< 200 ms, < 4 dp) | `[mote] DRY button left click count=1` | real |
| S4 | phone user | Press the stick and hold 600 ms without moving, lift | At 450 ms `[mote] DRY button right click count=1`; no left click on lift | real |
| S5 | phone user | Press the top of the ring (82 % of the radius, up sector) for 1 s (real time), lift | `[mote] DRY key usage=82 mods=0 action=down`, repeated `action=down` (WsTransport repeats after 400 ms every 60 ms), then `[mote] DRY key usage=82 mods=0 action=up`. Right/down/left: usage 79 / 81 / 80 | real |
| S6 | phone user | Tap "Next track, hold to fast-forward" (< 400 ms) | `[mote] DRY consumer next` | real |
| S7 | phone user | Hold "Next track, hold to fast-forward" 600 ms, lift; same on "Previous track, hold to rewind"; then tap "Previous track, hold to rewind" | At 400 ms (cap lit) `[mote] DRY consumer fast down`, on lift `[mote] DRY consumer fast up`, no `next`; `rewind down` / `rewind up`, no `prev`; the tap: `[mote] DRY consumer prev` | real |
| S8 | phone user | Tap "Volume up" end zone; tap "Volume down" | `[mote] DRY consumer volup`; `[mote] DRY consumer voldown` | real |
| S9 | phone user | Drag "Volume lever" up to full travel, hold 1 s, lift | Bands by displacement (6 dp deadzone, 4 bands, 3 / 5 / 8 / 12 per s): 10–14 `[mote] DRY consumer volup` lines, no `voldown`; nothing after the lift | real |
| S10 | phone user | Tap "Scroll up one notch"; tap "Scroll down one notch" | `[mote] DRY scroll dx=0 dy=-24`; `[mote] DRY scroll dx=0 dy=24`. Lever drag: 4 / 8 / 16 / 30 notches per s | real |
| S11 | phone user | Tap "Mute"; tap "Play or pause" | `[mote] DRY consumer mute`; `[mote] DRY consumer play` | real |
| S12 | phone user | Hold "Brightness up" 2 s; tap "Brightness down" | One `brightup` on touch-down, repeats after 350 ms every 125 ms, every 62 ms after 1.2 s more: 14–20 `[mote] DRY consumer brightup` lines; then `[mote] DRY consumer brightdown` | real |

## Postconditions
- No state persisted. No held key / consumer left down (helper `heldNX` released by `up`).

## Branches
### `media.bt`: the same controls over Bluetooth HID (from S1; artboard: not drawn per transport)
Preconditions delta: Bluetooth fixture (P1), FakeTransport linked.
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | phone user | S2 drag | `Move(dx > 0, 0)` calls only, total 1500–2000 | faked: BT HID transport (FakeTransport) |
| B2 | phone user | S3 tap | `[Click(1)]` | faked: BT HID transport (FakeTransport) |
| B3 | phone user | S4 hold | `[Click(2)]` only | faked: BT HID transport (FakeTransport) |
| B4 | phone user | Hold the ring's up sector 1 s | `[KeyDown("up"), KeyUp("up")]` (the HID host repeats) | faked: BT HID transport (FakeTransport) |
| B5 | phone user | Tap next; hold next 600 ms | `[Consumer(0xB5)]`; `[ConsumerHold(0xB3, true), ConsumerHold(0xB3, false)]` | faked: BT HID transport (FakeTransport) |
| B6 | phone user | Tap "Volume up", "Volume down", "Scroll up one notch", "Scroll down one notch", "Mute", "Play or pause", "Brightness up", "Brightness down" | One call each: `Consumer(0xE9)`, `Consumer(0xEA)`, `Scroll(-1, 0)`, `Scroll(1, 0)`, `Consumer(0xE2)`, `Consumer(0xCD)`, `Consumer(0x6F)`, `Consumer(0x70)` | faked: BT HID transport (FakeTransport) |

### `media.offline`: not linked (from S1; artboard: not drawn)
Preconditions delta: Bluetooth fixture with `FakeTransport.linked.value = false` (a helper host that isn't Ready behaves the same: `LocalLinked` = false).
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | phone user | Tap "Play or pause", "Brightness up", "Mute", "Volume up", "Scroll up one notch", "Next track, hold to fast-forward", "Direction pad" | No `Call`; haptic REJECT per press; caps drawn at alpha 0.38 (no semantics) | faked: BT HID transport (FakeTransport) (`FakeTransport.linked = false`) |

### `media.seekcancel`: seek interrupted (from S7; not drawn)
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | phone user | Hold "Next track…" 600 ms, then switch mode (rail "Macros") before lifting | `fast down` then, on dispose, `fast up` (`MediaScreen.kt:164-166`); no `next` | real |
| B2 | phone user | Hold "Next track…" for 300 ms, drag off the cap, lift | Cancel: no `next`, no `fast` (`SeekHold.cancel`) | real |

### `media.midhold`: stick press between 200 and 450 ms (from S3; not drawn)
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | phone user | Press the stick 300 ms without moving, lift | Nothing sent (StickPress.up → NONE) | real |

### `media.paused`: input paused (from S1; `daily.paused` covers the UI)
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | Mac user | Menu › Pause input | `[mote] input paused` | faked: stdin-control `pause` |
| B2 | phone user | S6 | Helper replies `{"t":"err","code":"paused","msg":"input is paused on the Mac","ref":"consumer"}`; no `DRY consumer next` | real |

### `media.a11y`: accessibility actions (from S1; not drawn)
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | phone user | Accessibility click on "Pointer stick…"; custom action "Right click" | `DRY button left click count=1`; `DRY button right click count=1` | real |
| B2 | phone user | Accessibility click on "Next track, hold to fast-forward" | `DRY consumer next` (a11yClick), never `fast` | real |

## Disagreements → DEFECTS.md
- None: captions of j7-s1…s4 match code (deadzone + curve, 450 ms right click, ring = arrows, 400 ms seek, lever bands, end-tap notch). Mac-side art in j7-s2…s4 (Finder menu, "Music", "Fast-forwarding ▶▶", volume HUD) is illustrative macOS UI. Settings scope gaps for the levers are in `settings-D5`.

## UNDEFINED
- Mac-side effect of `fast`/`rewind` NX keys (which app seeks): macOS behaviour, not in code.
- Haptics (TICK, CLICK, HEAVY, EDGE, DETENT, REJECT): vibration not observable on the emulator (`manual`).
