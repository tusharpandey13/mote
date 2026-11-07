# media: 7. Media controls

Artboard: `design/mote-v1/journey-media.html` (Shipped). Code: `android/.../ui/MediaScreen.kt` (layout, SeekCap, FavouriteStrip), `ui/SeekHold.kt`, `ui/Joystick.kt` (JoystickPad, Thumbstick), `ui/StickLogic.kt` (StickCurve, StickDriver, StickPress), `ui/Controls.kt:239-365` (MachinedSlider, EndZone), `ui/Components.kt:98-187` (Repeat, Cap), `net/WsTransport.kt` (send pipe), `net/Proto.kt`; helper `Injector.swift` (DRY lines), `Hub.swift:97-106`. Docs: `docs/protocol.md` §move/button/scroll/consumer (1.2 hold-to-seek), `docs/design/design-language.md` §User journeys.
Test: `e2e/journeys` → `dev.mote.remote.journeys.MediaJourney` (one @Test per path below). Each step is run twice: transport `helper` (dry-run WebSocket, assert `[mote] DRY …` lines) and transport `bt` (FakeTransport `Caps.HID`, assert `Call`s).

## Preconditions
- P1. App: debug `dev.mote.remote.dev`, on Media (default mode). helper variant: linked to the dry-run helper (fixture: `wifi-helper` main path). bt variant: `TestSeam.transport = FakeTransport(Caps.HID)`, `TestSeam.adapter` with one bonded host, `BLUETOOTH_CONNECT` + `BLUETOOTH_ADVERTISE` + `BLUETOOTH_SCAN` granted, BT host selected. Default settings (`pointerSpeed 1.0`, `stickIntroDone` any). Default macros (favourites: Spaces, Return).
- P2. Phone: Wi-Fi on (helper variant); Bluetooth adapter faked (bt variant). No Tailscale.
- P3. Mac / helper: `MoteHelper --dry-run --headless --no-ble --memory-store --port N --stdin-control`, not paused.
- P4. Network: same LAN (helper variant); none needed (bt variant).

## Main path `media`
| # | Actor | Action (exact) | Expected observable (exact) | Seam |
|---|---|---|---|---|
| S1 | phone user | Look at Media | Nodes: "FAVOURITES" + cap "Edit"; Spaces row "Spaces" / "swipe or tap a desktop" + carousel; strip: cap "Return" + dashed slot (click label "Add favourite", text "Add favourite"); caps "Brightness down", "Brightness up"; "Scroll up one notch", "Scroll lever", "Scroll down one notch"; "Direction pad" with button "Pointer stick. Drag to move the pointer, tap to click" (custom action "Right click"); "Volume up", "Volume lever", "Volume down", "Mute"; "Previous track, hold to rewind", "Play or pause", "Next track, hold to fast-forward". Engraved labels "SCROLL", "VOLUME". No test tags | real |
| S2 | phone user | Drag from the stick centre ≥ 20 dp × k (full travel) to the right and hold 500 ms, then lift | Pointer moves at 120 Hz ticks: velocity = 1800 px/s × ((d−0.12)/0.88)^1.8 at speed 1.0 (d = 1 → 1800 px/s). helper: repeated `[mote] DRY move dx=<n>.0 dy=0.0` (summed per ≤ 16 ms frame), total dx ≈ 900 ±15 %; bt: `Move(dx>0, 0)` calls summing ≈ 900. No click on lift (moved past 4 dp slop) | real (helper) / faked: BT HID |
| S3 | phone user | Tap the stick (< 200 ms, < 4 dp) | helper `[mote] DRY button left click count=1`; bt `Click(1)` | real / faked: BT HID |
| S4 | phone user | Press the stick and hold ≥ 450 ms without moving | At 450 ms (while held) helper `[mote] DRY button right click count=1`; bt `Click(2)`; nothing on lift | real / faked: BT HID |
| S5 | phone user | Press and hold the top of the ring (≥ 82 dp × k from centre, up sector) 1 s, lift | helper `[mote] DRY key usage=82 mods=0 action=down`, repeated `action=down` from 400 ms every 60 ms, then `[mote] DRY key usage=82 mods=0 action=up`; bt `KeyDown("up")` … `KeyUp("up")` (HID host repeats). Right/down/left sectors: usage 79 / 81 / 80, keys "right" / "down" / "left" | real / faked: BT HID |
| S6 | phone user | Tap "Next track, hold to fast-forward" (< 400 ms) | helper `[mote] DRY consumer next`; bt `Consumer(0xB5)` | real / faked: BT HID |
| S7 | phone user | Press "Next track, hold to fast-forward" and hold 1 s, lift | At 400 ms: cap pressed + legend lit; helper `[mote] DRY consumer fast down`; on lift `[mote] DRY consumer fast up`; no `next`. bt `ConsumerHold(0xB3, true)`, `ConsumerHold(0xB3, false)`. Previous: `rewind` / 0xB4, tap `prev` / 0xB6 | real / faked: BT HID |
| S8 | phone user | Tap "Volume up" end zone | helper `[mote] DRY consumer volup`; bt `Consumer(0xE9)`. "Volume down": `voldown` / 0xEA | real / faked: BT HID |
| S9 | phone user | Drag "Volume lever" up to full travel and hold 2 s | Bands by displacement (6 dp deadzone, 4 bands): notches at 3 / 5 / 8 / 12 per s; at full travel ~12 `volup` per s (≈ 24 lines in 2 s ±3); spring back on lift, no more lines | real / faked: BT HID |
| S10 | phone user | Tap "Scroll up one notch" / "Scroll down one notch" | helper `[mote] DRY scroll dx=0 dy=-24` / `dy=24`; bt `Scroll(-1, 0)` / `Scroll(1, 0)`. Lever drag: rates 4 / 8 / 16 / 30 per s | real / faked: BT HID |
| S11 | phone user | Tap "Mute"; tap "Play or pause" | helper `[mote] DRY consumer mute`, `[mote] DRY consumer play`; bt `Consumer(0xE2)`, `Consumer(0xCD)` | real / faked: BT HID |
| S12 | phone user | Press "Brightness up" and hold 2 s | One `brightup` on touch-down, repeats after 350 ms every 125 ms, every 62 ms after 1.2 s more; helper `[mote] DRY consumer brightup` ×N; bt `Consumer(0x6F)` ×N. "Brightness down": `brightdown` / 0x70 | real / faked: BT HID |

## Postconditions
- No state persisted. No held key / consumer left down (helper `heldNX` released by `up`).

## Branches
### `media.offline`: not linked (from S1; artboard: not drawn)
Preconditions delta: helper variant: helper stopped (WsStatus Connecting); bt variant: `FakeTransport.linked.value = false`.
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | phone user | Repeat S2–S12 gestures | No helper line / no `Call`; haptic REJECT per press; caps drawn at alpha 0.38 (no semantics) | real / faked: BT HID (`FakeTransport.linked`) |

### `media.seekcancel`: seek interrupted (from S7; not drawn)
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | phone user | Hold "Next track…" 1 s, then switch mode (tap "Switch to Macros") before lifting | `fast down` then, on dispose, `fast up` (`MediaScreen.kt:164-166`); no `next` | real |
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
| B1 | phone user | performClick on "Pointer stick…" / custom action "Right click" | left / right click as S3 / S4 | real |
| B2 | phone user | performClick on "Next track, hold to fast-forward" | `next` (a11yClick), never seek | real |

## Disagreements → DEFECTS.md
- None: captions of j7-s1…s4 match code (deadzone + curve, 450 ms right click, ring = arrows, 400 ms seek, lever bands, end-tap notch). Mac-side art in j7-s2…s4 (Finder menu, "Music", "Fast-forwarding ▶▶", volume HUD) is illustrative macOS UI. Settings scope gaps for the levers are in `settings-D5`.

## UNDEFINED
- S2/S9 totals: timing tolerance depends on emulator frame pacing (120 Hz ticks via `delay`); only ranges are specified.
- Mac-side effect of `fast`/`rewind` NX keys (which app seeks): macOS behaviour, not in code.
- Haptics (TICK, CLICK, HEAVY, EDGE, DETENT, REJECT): vibration not observable on the emulator (`manual`).
