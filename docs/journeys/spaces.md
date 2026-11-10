# spaces: 5. Spaces

Artboard: `design/mote-v1/journey-spaces.html` (Shipped). Code: `android/.../ui/Spaces.kt` (BtSpaces, helperSpaces, DisplayPick, DisplayIndicator, SpacesCarousel, SpaceCard, SpacesSheet, SpacesHint), `ui/MediaScreen.kt:69-95`, `ui/MacrosScreen.kt:105,131,186-198`, `net/WsTransport.kt:114-185`, `net/Spaces.kt`, `net/Proto.kt` (spaces*), `data/UiSettings.kt` (desktopCount, ctrlNumberShortcuts, spacesHintDone); helper `Spaces.swift` (fake spaces, goto, capture), `Hub.swift` (spaces.sub/thumb, input gate), `main.swift` (`display N`). Docs: `docs/protocol.md` §spaces (1.3/1.4) and §test flags (`--fake-spaces`), `docs/design/design-language.md` §User journeys.
Test: `e2e/journeys` → `dev.mote.remote.journeys.SpacesJourney` (one @Test per path below).

## Preconditions
- P1. App: debug `dev.mote.remote.dev`. Default macros (Spaces starred → Media shows the carousel). Default settings: `desktopCount = 4`, `ctrlNumberShortcuts = false`, `spacesHintDone = false`. `BtSpaces.current = 1` (process-global, not persisted: start each test in a fresh process).
  - bt paths: `TestSeam.transport = FakeTransport(Caps.HID)`, bonded host selected, BT permissions granted.
  - helper paths: linked to the dry-run helper (fixture `wifi-helper` main path).
- P2. Phone: Wi-Fi on (helper paths). No Tailscale.
- P3. Mac / helper (helper paths): `MoteHelper --dry-run --headless --no-ble --memory-store --port N --stdin-control --fake-spaces 6` (one display `fake-1` "Fake Display 1", 6 desktops, current 1, capture `on`, solid-colour thumbnails). Two-display branch: `--fake-spaces 6,3`.
- P4. Network: same LAN (helper paths).

## Main path `spaces` (Bluetooth HID, numbered cards)
| # | Actor | Action (exact) | Expected observable (exact) | Seam |
|---|---|---|---|---|
| S1 | phone user | Look at Media | Caption "Spaces" / "swipe or tap a desktop"; 84 dp well with 4 cards: caps "Desktop 1, current", "Desktop 2", "Desktop 3", "Desktop 4" (texts "1"…"4" + "Desktop N"); lit card = 1 | faked: BT HID transport (FakeTransport) |
| S2 | phone user | Tap "Desktop 3" | Lit card moves to 3 at once ("Desktop 3, current"); FakeTransport `Tap("right", 1)`, then 300 ms later `Tap("right", 1)` (2 steps, `BtSpaces.STEP_MS`) | faked: BT HID transport (FakeTransport) |
| S3 | phone user | Tap "Desktop 1" | `Tap("left", 1)` ×2, 300 ms apart; lit card 1 | faked: BT HID transport (FakeTransport) |
| S4 | phone user | Tap mode rail "Macros"; tap cap "Spaces: choose a desktop" | Sheet: "Spaces" (headline), button "Close"; "Swipe the row or tap a desktop to jump to it. The lit card is the desktop you are on."; captions "OVER BLUETOOTH" / "NUMBERED"; carousel (112 dp cards); hint: "Jump straight to a desktop. On the Mac, turn on Switch to Desktop 1-9 in System Settings, Keyboard Shortcuts, Mission Control. Until then Mote steps with ⌃← and ⌃→." + cap "Done" + text "Remind me later" | faked: BT HID transport (FakeTransport) |
| S5 | phone user | Tap "Remind me later" | Hint gone for this sheet only; close ("Close") and reopen → hint shown again | faked: BT HID transport (FakeTransport) |
| S6 | phone user | Tap "Done" | Hint gone; `spacesHintDone = true` persisted (DataStore `mote_settings`, `spaces_hint_done`); never shown again | faked: BT HID transport (FakeTransport) |

## Postconditions
- `BtSpaces.current` = last tapped (memory only). `spacesHintDone` per S5/S6. No helper involved.

## Branches
### `spaces.ctrln`: ⌃N shortcuts on (from S2; artboard j5-s1 caption "⌃N")
Preconditions delta: Settings "Use ⌃1–⌃9 shortcuts" on (switch contentDescription "Use control 1 to 9 shortcuts").
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | phone user | Tap "Desktop 3" | One `Tap("3", 1)` | faked: BT HID transport (FakeTransport) |
| B2 | phone user | Open the Spaces sheet | No hint (shown only while the switch is off) | faked: BT HID transport (FakeTransport) |
| B3 | phone user | Settings › Desktops = 12, tap "Desktop 12" from 3 | `Tap("right", 1)` ×9 (⌃N only for N ≤ 9) | faked: BT HID transport (FakeTransport) |

### `spaces.helper`: with the helper, thumbnails (from S1; artboard j5-s2, j5-s4)
Preconditions delta: helper paths P1/P3.
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | system | Link up: phone sends `{"t":"spaces.sub","on":true}` | Helper replies `{"t":"spaces","count":6,"current":1,"thumbs":[0,0,0,0,0,0],"capture":"on","displays":[{"id":"fake-1","name":"Fake Display 1","count":6,"current":1,"thumbs":[…]}],"shared":false,"display":"fake-1"}`; ~0.1 s later desktop 1 is captured → new `spaces` with `thumbs[0] = v>0`; phone sends `{"t":"spaces.thumb","i":1,"display":"fake-1"}` and gets the JPEG | real (dry-run, `--fake-spaces`) |
| B2 | phone user | Look at Media | 6 cards. Card 1: Cap "Desktop 1, current" with the solid-colour thumbnail and white label "Desktop 1". Cards 2–6: dashed placeholders with texts "Desktop N" + "Not visited yet", NO click action, no contentDescription | real |
| B3 | phone user | Tap "Desktop 1" | Phone sends `{"t":"spaces.goto","i":1,"display":"fake-1"}`; helper `[mote] DRY spaces goto=1 from=1 via=step display=fake-1`, no DRY key line | real |
| B4 | phone user | Tap the "Desktop 3" placeholder | Nothing sent (not clickable: `Spaces.kt:285-294`). See spaces-D1 | real |
| B5 | system | (only if a desktop could be reached) goto 3 from 1 | Helper `[mote] DRY spaces goto=3 from=1 via=step display=fake-1`, then `[mote] DRY key usage=79 mods=1 action=tap` ×2, 80 ms apart; ~0.16 s later (80 ms × 2 steps) `spaces` with `current:3`, lit card follows; ~0.6 s after that another `spaces` with a thumb version for desktop 3 | faked: needs a way to visit desktop 3 (new stdin-control `goto N` or `--fake-spaces-visited`), since B4 can't |
| B6 | phone user | Open the Spaces sheet | Captions "WITH THE MOTE HELPER" / "THUMBNAILS"; carousel; text "The helper saves a picture of each desktop when you visit it. It needs Screen Recording permission on the Mac." No ⌃N hint | real |

### `spaces.displays`: two displays, the chip (from spaces.helper; artboard j5-s5)
Preconditions delta: helper `--fake-spaces 6,3`.
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | phone user | Look at Media | Caption "Spaces" with, instead of the hint, dots (merged contentDescription "Display 1 of 2") + chip "Display: Fake Display 1. Choose display" | real |
| B2 | phone user | Tap the chip | Dropdown items "Fake Display 1 (active)" (trailing check) and "Fake Display 2" | real |
| B3 | phone user | Tap "Fake Display 2" | Cards fade out 120 ms, row scrolls to that display's current card, fade in 200 ms; 3 cards, all dashed "Not visited yet" (thumbs are shown only for the active display); dots "Display 2 of 2" | real |
| B4 | Mac user | Focus moves to display 2 | Helper pushes `spaces` with `display:"fake-2"`; pick cleared (active changed); carousel shows fake-2 | faked: stdin-control `display 2` (log `[mote] spaces: fake active display 2`) |
| B5 | phone user | Open the Spaces sheet | Text "Each display has its own desktops. The row follows the display you're using; tap the chip to pick another. The helper saves a picture of each desktop when you visit it (needs Screen Recording permission)." | real |

### `spaces.nocapture`: thumbnails unavailable (from spaces.helper; not drawn)
Preconditions delta: helper `--dry-run` without `--fake-spaces` (reads the dev Mac's real Spaces via CGS, read-only; `capture` = `unavailable` in dry run).
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | phone user | Look at Media | All cards dashed "Not visited yet", none tappable (spaces-D1) | real (dry-run never captures) |
| B2 | system | Helper reports `count 0` (CGS unavailable) | `helperSpaces()` = null → numbered BT-style cards; taps send `{"t":"key","usage":79,"mods":1}` steps via the helper (`BtSpaces` over WsTransport): `[mote] DRY key usage=79 mods=1 action=tap` | UNDEFINED: whether CGS read works headless on the dev Mac |

### `spaces.paused`: input paused (from spaces.helper B3; not drawn)
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | Mac user | Pause input | `[mote] input paused` | faked: stdin-control `pause` |
| B2 | phone user | Tap "Desktop 1" | Helper `{"t":"err","code":"paused","msg":"input is paused on the Mac","ref":"spaces.goto"}`; no DRY line; `spaces.thumb` still answered (reads are not gated) | real |

## Disagreements → DEFECTS.md
- `spaces-D1` (spaces.helper B4, nocapture B1, displays B3): unvisited / non-active-display cards are not tappable, so with the helper you can't jump to a desktop you haven't visited.
- `spaces-D2` (S4, j5-s3…s5): artboard sheet shows the "Over Bluetooth" block, the ⌃N hint and the "With the Mote helper" block together; code shows exactly one block (helper or Bluetooth) and the hint only over Bluetooth.
- `spaces-D3` (S1): code doc comment on `Macro.Kind.SPACES` ("hold + slide = ⌃←/⌃→, tap = ⌃↑") describes a removed gesture; the tile opens the sheet.

## UNDEFINED
- B5 timing: the fake re-read fires `lead + 80 ms × steps` after goto (`Spaces.swift:379`), capture `captureDelay` (0.6 s) later; exact push times depend on main-queue load.
- spaces.nocapture B2: headless CGS availability on the dev Mac.
- Real macOS slide animation / desktop switch (artboard j5-s2 "Fri 9:41"): macOS behaviour, not in code.
