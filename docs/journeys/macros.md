# macros: 6. Macros

Artboard: `design/mote-v1/journey-macros.html` (Shipped). Code: `android/.../ui/MacrosScreen.kt` (MacrosScreen, MacroTile, SpacesTile, MacroEditor, PointPicker), `data/Macro.kt` (defaults, migrate, run), `hid/Keys.kt` (Mod, Key), `ui/App.kt:211-218,279,281-286`, `ui/MediaScreen.kt:201-224` (FavouriteStrip), `ui/MouseScreen.kt:54-72` (pointer banner), `net/WsTransport.kt:249,277`, `net/Transport.kt` (Caps); helper `Injector.swift` (DRY key / clickAt). Docs: `docs/protocol.md` §key, §clickAt, `docs/design/design-language.md` §User journeys + checklist row "Macros grid…".
Test: `e2e/journeys` → `dev.mote.remote.journeys.MacrosJourney` (one @Test per path below).

## Preconditions
- P1. App: debug `dev.mote.remote.dev`, fresh macros (DataStore `mote` has no `macros_json` → `Macros.defaults`: Spaces★, Return★, Spotlight, Escape, Mission Control, Screenshot area, Open Safari, Lock (confirm), Force quit, Hide others). Linked to the dry-run helper (fixture `wifi-helper` main path) unless a branch says bt.
- P2. Phone: Wi-Fi on. No Tailscale.
- P3. Mac / helper: `MoteHelper --dry-run --headless --no-ble --memory-store --port N --stdin-control`. Not paused.
- P4. Network: same LAN.

## Main path `macros` (record a shortcut, star it, run it from Media)
| # | Actor | Action (exact) | Expected observable (exact) | Seam |
|---|---|---|---|---|
| S1 | phone user | Tap mode rail "Macros" (click label "Switch to Macros") | MacrosScreen: "Macros", cap "Edit"; "Tap to run. Spaces opens a desktop carousel. Use Edit to change or star."; 3-column grid: "Spaces: choose a desktop" (2 columns, text "swipe or tap a desktop"), then caps by name "Return" (hint "⏎"), "Spotlight" ("⌘space"), "Escape" ("esc"), "Mission Control" ("⌃↑"), "Screenshot area" ("⇧⌘4"), "Open Safari" ("5 steps"), "Lock" ("⌃⌘Q"), "Force quit" ("⌥⌘esc"), "Hide others" ("⌥⌘H"), then "New macro". Star icons contentDescription "Favourite" (Spaces, Return) / "Not favourite" | real |
| S2 | phone user | Tap "New macro" | Sheet "New macro": field "Name"; icon caps "Icon <name>" (selected `apps`); "Action" with toggles "Shortcut" (on) / "Click at point"; "Shortcut" well text " + key"; toggles "⌃ ctrl", "⌥ opt", "⇧ shift", "⌘ cmd"; field "Type a key (letter, digit, symbol)"; special-key toggles "⏎", "esc", "⇥", "space", "⌫", "del", "↑", "↓", "←", "→", "home", "end", "pg up", "pg dn", "F1"…"F12"; toggles "★ Favourite", "Confirm before run"; primary "Add a name and a key" | real |
| S3 | phone user | Type `Area shot` into "Name"; tap "⌘ cmd", "⇧ shift"; type `4` into "Type a key (letter, digit, symbol)" | Well text "⇧⌘4"; primary label "Save" | real |
| S4 | phone user | Tap "★ Favourite"; tap "Save" | Sheet closes; tile "Area shot" (hint "⇧⌘4", star "Favourite") appended after "Hide others"; persisted `macros_json` v3 with `{"name":"Area shot","icon":"apps","star":true,"confirm":false,"pointer":false,"kind":"steps","steps":[{"t":"chord","mods":["shift","cmd"],"key":"4"}]}` | real |
| S5 | phone user | Tap mode rail "Media" | Favourites strip: "Return", "Area shot", dashed "Add favourite" (2 free slots → icon + text) | real |
| S6 | phone user | Tap cap "Area shot" | Phone sends `{"t":"key","usage":33,"mods":10}`; helper `[mote] DRY key usage=33 mods=10 action=tap`. Stays on Media (`pointer` false) | real (dry-run) |

## Postconditions
- `macros_json` contains the new macro; Media strip shows it (first 4 starred non-Spaces macros in list order).

## Branches
### `macros.confirm`: Lock with confirm (from S1; artboard j6-s5)
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | phone user | Tap "Lock" | Armed: tile tone DANGER, name text "Tap again" (contentDescription stays "Lock"); nothing sent | real |
| B2 | phone user | Tap "Lock" again within 2 s | `[mote] DRY key usage=20 mods=9 action=tap`; tile back to "Lock" | real |
| B3 | phone user | Tap "Lock", wait > 2 s | Label back to "Lock", nothing sent; next tap arms again | real |

### `macros.clickat`: click at point (from S2; artboard j6-s3)
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | phone user | "New macro"; Name `Join call`; tap toggle "Click at point" | PointPicker (contentDescription "Click point 50% across, 50% down") + "Tap or drag where to click on the main display: 50% across, 50% down." | real |
| B2 | phone user | Tap the picker at 72.5 % / 18.5 % of its bounds | "…: 72% across, 18% down." (int truncation) | real |
| B3 | phone user | "★ Favourite", "Save"; then tap tile "Join call" (hint "click 72%, 18%") | Phone sends `{"t":"clickAt","x":0.725…,"y":0.185…,"unit":"norm","display":0,"b":"left","count":1}`; helper `[mote] DRY clickAt (<x>, <y>) b=left count=1` with x = minX + 0.725 × (width − 1), y likewise, of the dev Mac's main display | real (point values depend on the dev Mac's display: compute from `CGDisplayBounds`) |

### `macros.bt`: Bluetooth host (from S2/S6; artboard j6-s3 caption "Skipped on Bluetooth hosts")
Preconditions delta: `TestSeam.transport = FakeTransport(Caps.HID)`, BT host selected.
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | phone user | "New macro" | Under the Action toggles: "Click at point needs a Mac with Mote Helper (Wi-Fi or Tailscale). On Bluetooth hosts it is skipped." | faked: BT HID |
| B2 | phone user | Tap "Click at point" | No change (toggle ignored while `caps.clickAt` false) | faked: BT HID |
| B3 | phone user | Run "Area shot" | `SetMods(0,0)`, `TapAwait("4", 10)` | faked: BT HID |
| B4 | phone user | Run a click macro saved earlier over the helper | No call; Toast "1 characters skipped (not on US layout)" (see macros-D3) | faked: BT HID; Toast: UNDEFINED (not in the a11y tree reliably) |

### `macros.pointer`: Screenshot area (pointer macro) (from S1; not drawn)
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | phone user | Tap "Screenshot area" | `[mote] DRY key usage=33 mods=10 action=tap`; mode switches to Mouse with banner "Turn on drag lock and move to select; turn it off to capture." + caps "Escape" (text "esc"), "Done" | real |
| B2 | phone user | Tap "Escape" | `[mote] DRY key usage=41 mods=0 action=tap`; mode → Macros | real |
| B3 | phone user | (variant) Tap "Done" | Banner gone; mode → Macros (also when the macro was run from Media, macros-D5) | real |

### `macros.edit`: edit mode, star, delete (from S1; artboard j6-s1 caption "Edit / Done")
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | phone user | Tap "Edit" | Cap label "Done"; text "Tap a tile to edit it; tap a star to add or remove it from Media."; tiles become "Edit <name>" | real |
| B2 | phone user | Tap the star of "Spotlight" | Star "Favourite"; persisted; Media strip gains "Spotlight" | real |
| B3 | phone user | Tap "Edit Open Safari" | Sheet "Edit macro"; "This macro has 5 steps. Only its name and options can be edited here."; "Delete macro" | real |
| B4 | phone user | Tap "Delete macro" | Tile gone; persisted | real |
| B5 | phone user | Tap "Edit Spaces" | Sheet "Edit macro" with Name, icons, "★ Favourite", "Confirm before run", "Save"; no Action, no Delete | real |
| B6 | phone user | Tap "Done" | Back to run mode | real |

### `macros.invalid`: save disabled (from S2; not drawn)
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | phone user | Leave Name empty or no key; tap "Add a name and a key" | Nothing saved, sheet stays | real |

### `macros.media-full`: favourites strip full (from S5; artboard j6-s4 "4 slots")
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | phone user | Star 3 more macros | Strip shows the first 4 starred non-Spaces macros in list order, no "Add favourite"; the 5th starred is not shown | real |
| B2 | phone user | With 3 used | Dashed slot shows only the "+" icon (no text when 1 slot is free) | real |

### `macros.unicode`: Open Safari text step over helper vs BT (from S1; not drawn)
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | phone user | Tap "Open Safari" (helper) | `DRY key usage=44 mods=8`, (250 ms) `[mote] DRY text "Safari"`, (150 ms) `DRY key usage=40 mods=0` (each `action=tap`) | real |
| B2 | phone user | Same over BT | `TapAwait("space", 8)`, `TypeText("Safari")`, `TapAwait("return", 0)`; no Toast (all US-typeable) | faked: BT HID |

## Disagreements → DEFECTS.md
- `macros-D1` (S1): subtitle copy differs; artboard adds "reorder" (not implemented) and "Shortcuts are set for MacBook Pro." (not in code); Spaces tile hint "tap to pick a desktop" vs code "swipe or tap a desktop"; "Open Safari" hint "3 steps" vs code "5 steps" (two Delay steps count).
- `macros-D2` (S2/S3, j6-s2): modifier toggle order and chord glyph order: artboard "⌘ cmd, ⇧ shift, ⌥ opt, ⌃ ctrl" and "⌘⇧4" / "⌘ ⇧ 4"; code "⌃ ctrl, ⌥ opt, ⇧ shift, ⌘ cmd" and "⇧⌘4".
- `macros-D3` (macros.bt B4): skipped clicks on a Bluetooth host are reported as "N characters skipped (not on US layout)".
- `macros-D4` (j6-s1, j6-s5): artboard tile "Lock screen" (s1) vs "Lock" (s5) vs code "Lock"; artboard new macro named "Screenshot area" duplicates the default macro of that name (tests use "Area shot").
- `macros-D5` (macros.pointer B3): the pointer banner's Done/Escape always go to Macros, even when the macro was run from Media favourites.

## UNDEFINED
- macros.bt B4 Toast visibility to the test framework.
- macros.clickat B3 exact `(x, y)`: depends on the dev Mac's main display bounds.
- What ⇧⌘4 / ⌃⌘Q do on the Mac (screenshot, lock): macOS, dry-run never posts.
