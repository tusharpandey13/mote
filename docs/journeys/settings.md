# settings: G. Settings and customisation

Artboard: `design/mote-v1/journey-settings.html` (Shipped). Code: `android/.../ui/MenuScreens.kt` (MenuSheet, SettingsPage, HapticsPage, AboutPage), `ui/App.kt:220-237,246-249,296-311`, `data/UiSettings.kt`, `data/Prefs.kt` (theme), `ui/Controls.kt` (Segmented, Switch, MetalSlider), `ui/Haptics.kt`, `ui/MouseScreen.kt:110-175`, `ui/Joystick.kt:159`, `ui/KeysScreen.kt:202-281`, `ui/Spaces.kt:89-117`, `labs/Labs.kt`, `labs/LabsUi.kt`; helper `MenuBar.swift:143-154`, `Labs/Context.swift:416`, `Labs/Bridge.swift:487`, `Config.swift` (`--labs-tv`, `--fake-screen`). Docs: `docs/design/design-language.md` §User journeys, `docs/pairing.md` (instant pairing out of Labs), `docs/labs/*.md`.
Test: `e2e/journeys` → `dev.mote.remote.journeys.SettingsJourney` (one @Test per path below).

## Preconditions
- P1. App: debug `dev.mote.remote.dev` (versionName `0.6.0-dev`), fresh DataStores `mote`, `mote_settings`, `labs` (defaults: theme LIGHT; pointer/scroll 1.0; natural scroll on; right-click Hold; clear-mods-on-switch off; release-mods-idle on; keep-screen-on on; extra dim off; haptics Standard, all three event switches on; desktops 4; ⌃N off). Remote reachable (any linked transport; for effect checks: FakeTransport `Caps.HID` or the dry-run helper).
- P2. Phone: any network. Bonded BT hosts per branch (menu summary).
- P3. Mac / helper: `MoteHelper --dry-run --headless --no-ble --memory-store --port N --stdin-control` for effect checks.
- P4. Network: same LAN when the helper is used.

## Main path `settings`
| # | Actor | Action (exact) | Expected observable (exact) | Seam |
|---|---|---|---|---|
| S1 | phone user | Tap "Menu" (top-right, contentDescription "Menu") | Sheet: "Mote", button "Close"; row "Theme" with segments "Dark", "Light" (selected), "System"; rows (click labels = titles) "Manage devices" / `<summary>`, "Haptics" / "Standard", "Settings" / "Pointer speed, scrolling, keyboard", "About Mote" / "Version 0.6.0-dev". `<summary>`: no selected/connected BT host → "No paired devices" or "<n> paired"; else "<name>" or "<name> and <k> other(s)" over bonded BT hosts only | real (`TestSeam.adapter` for BT hosts) |
| S2 | phone user | Tap "Dark" | Theme Titanium at once; `theme = DARK` persisted; relaunch keeps it | real (colours: screenshot) |
| S3 | phone user | Tap "Settings" | Sheet closes; SettingsPage: back "Back", title "Settings"; overline "POINTER": "Pointer speed" / "1x (default). Higher moves farther per swipe." + slider "Pointer speed" (stateDescription "1.00x"); "Scroll speed" / "1x (default). Two-finger scrolling on the touchpad." + slider; "Natural scrolling" / "Content follows your fingers" (switch On); "Right-click" segments "Hold" (selected) / "2-finger tap"; "SPACES": "Desktops" / "4 on the Mac. Sets the cards in the Spaces carousel over Bluetooth." + slider "Desktops"; "Use ⌃1–⌃9 shortcuts" / "Turn on Switch to Desktop 1-9 on the Mac (System Settings, Keyboard Shortcuts, Mission Control). Off: Mote steps with ⌃← and ⌃→." (switch "Use control 1 to 9 shortcuts", Off); "KEYBOARD": "Clear modifiers on mode switch" (Off), "Release modifiers after 30 s idle" (On); "SCREEN": "Keep screen on while connected" (On), "Extra dim" / "For dark rooms and presentations" (Off); "LABS" (see settings.labs) | real |
| S4 | phone user | Drag slider "Pointer speed" to the right end | Subtitle "2.0x. Higher moves farther per swipe." (8 steps 0.25…2.0, Java `%.2g` in the default locale: "0.25x", "0.50x", "0.75x", "1x (default)", "1.3x", "1.5x", "1.8x", "2.0x"); slider stateDescription "2.00x"; persisted `pointer_speed = 2.0` | real |
| S5 | phone user | Back; Media; full stick deflection 500 ms (media S2) | Total `move` ≈ 2× media S2 (StickDriver speed 2.0); touchpad mapper also scaled | real / faked: BT HID |
| S6 | phone user | Settings › slider "Desktops" to step 6 (fraction 5/15) | Subtitle "6 on the Mac. …"; Media carousel (BT) shows 6 cards | faked: BT HID |
| S7 | phone user | Toggle "Use control 1 to 9 shortcuts" On | stateDescription "On"; Spaces BT taps send `Tap("N", 1)` (spaces.ctrln) | faked: BT HID |
| S8 | Mac user | System Settings › Keyboard › Keyboard Shortcuts › Mission Control › Switch to Desktop 1-9 | Nothing in Mote observes it (artboard jG-s4 is macOS UI) | needs user: macOS System Settings (and the HID path can't verify it) |
| S9 | phone user | Tap "Back" twice (or system back) | Remote shown; settings kept | real |

## Postconditions
- DataStore `mote_settings` holds the changed keys (`pointer_speed`, `desktop_count`, `ctrl_number_shortcuts`); `mote` holds `theme = DARK`.

## Branches
### `settings.haptics`: Haptics page (from S1; artboard jG-s5)
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | phone user | Menu › "Haptics" | Page "Haptics": "STRENGTH" segments "Off", "Light", "Standard" (selected), "Strong"; "TRY THEM" caps "Try Tick", "Try Click", "Try Heavy", "Try Lock", "Try Reject"; "Key presses" / "Tick on every key and D-pad step", "Slider detents" / "Ticks as scroll and volume speed change", "Connect and disconnect" / "Distinct pulses when the link changes" (all On); info "Haptics follow Android's touch feedback setting. If you feel nothing, turn on Settings, then Sound & vibration, then Touch feedback." | real |
| B2 | phone user | Tap "Off" | Persisted `haptic_strength = OFF`; menu row subtitle "Off" | real |
| B3 | phone user | Press "Try Heavy" | Vibration | manual: vibration not observable on the emulator |

### `settings.screen`: keep screen on / extra dim (from S3)
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | system | Linked, "Keep screen on while connected" On | ComposeView `keepScreenOn = true`; false when the link drops or the switch is Off | real (instrumentation reads the view flag) |
| B2 | phone user | Toggle "Extra dim" On | Activity window `screenBrightness = 0.02`; Off → `-1` | real (instrumentation) |

### `settings.keyboard`: modifier options (from S3)
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | phone user | Keys: tap "command, off" | Cap "command, armed"; chord strip "+" "next key" + "Clear modifiers" | real |
| B2 | system | Wait 30 s ("Release modifiers after 30 s idle" On) | Cap "command, off"; strip "Modifiers: tap once to arm, twice to lock" | real |
| B3 | phone user | Switch "Clear modifiers on mode switch" On; arm ⌘; tap "Switch to Media" | Modifiers cleared (`setMods(Mods())`; FakeTransport `SetMods(0,0)`) | real / faked: BT HID |

### `settings.pointer-mouse`: scroll and right-click options on the touchpad (from S3)
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | phone user | Mouse: two-finger drag up 40 dp ("Natural scrolling" On, Scroll speed 1x) | 2 wheel notches: helper `[mote] DRY scroll dx=0 dy=…` lines summing to `dy=48`; bt `Scroll(w,0)` calls with w > 0 summing to 2 | real / faked: BT HID |
| B2 | phone user | Natural scrolling Off; repeat | Opposite sign (sum `dy=-48`; `Scroll` wheel sum −2) | real / faked: BT HID |
| B3 | phone user | Right-click "Hold": one finger hold ≥ 500 ms, < 8 dp | `[mote] DRY button right click count=1` on lift | real |
| B4 | phone user | Right-click "2-finger tap": one-finger hold 600 ms | Nothing; two-finger tap (< 250 ms) right-clicks in both settings | real |

### `settings.labs`: Labs, both sides (from S3; artboard jG-s6)
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | phone user | Scroll to "LABS" | Four switches (contentDescription = title), all Off, in order: "Open & TV mode (preview)" / "An Open… sheet that drives the Mac, and a TV remote for YouTube. Needs Mote Helper with Labs › TV mode on."; "Smart remote (preview)" / "Adapts Media to what is on the Mac: now playing with a scrubber, app buttons, keyboard on text fields. Needs Mote Helper with Labs › Context & now playing on."; "Browser video control (preview)" / "Control the video in the Mac's browser tab with the Mote Video Bridge extension. Needs Mote Helper with Labs › Browser extension bridge on."; "Live screen (preview)" / "See the Mac's screen and tap to click. Needs Mote Helper over Wi-Fi or Tailscale with Labs › Live screen on." No "Instant pairing" switch | real |
| B2 | phone user | Toggle "Live screen (preview)" On | Persisted `labs.live_screen = true`; Menu gains row "Screen (preview)" / "See the Mac's screen, tap to click" only while the lab is usable (`liveScreenOn()`) | real (see `live-screen` journey) |
| B3 | Mac user | Helper menu › Labs › item | Submenu items in order: "TV mode (preview)", ("Exit TV mode" while on), "Context & now playing (preview)" (+ sub-items when on), separator, "Browser extension bridge" (+ items when on), "Live screen (preview)", "Pause screen sharing". No "Instant pairing (preview)" | needs user: menu bar is headless-unobservable; faked for state only: `--labs-tv` (TV mode on), `--fake-screen` (Live screen on); no flag for Context / Bridge toggles (bridge: stdin-control `bridge …`) |

### `settings.about`: About (from S1; not drawn, checklist "About gap")
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | phone user | Menu › "About Mote" | Page "About Mote": "VERSION" "Mote 0.6.0-dev" / "Keyboard, mouse and media remote over Bluetooth HID"; "OPEN-SOURCE LICENCES": "Unbounded", "Inter", "AndroidX and Jetpack Compose"; "SOURCE": "github.com/tusharpandey13/mote" / "Issues and releases" | real |
| B2 | phone user | Tap the source row | ACTION_VIEW `https://github.com/tusharpandey13/mote` | real (intent capture) |

### `settings.devices`: Manage devices (from S1; artboard jG-s1 "MacBook Pro and 3 others")
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | phone user | Tap "Manage devices" | Menu closes; Devices sheet (daily S4) | real |
| B2 | system | Helper Mac selected, 2 bonded BT hosts | Summary "2 paired" (helper hosts not counted, settings-D4) | faked: BT adapter |

## Disagreements → DEFECTS.md
- `settings-D1` (settings.labs B1/B3, jG-s6): artboard shows an "Instant pairing (preview)" switch (phone) and menu item (helper), and a different order/copy; code has neither (graduated, docs/pairing.md) and lists Open & TV, Smart remote, Browser video, Live screen with "Needs Mote Helper…" suffixes; helper menu adds "Pause screen sharing".
- `settings-D2` (S1, jG-s1): "About Mote / Version 0.1" vs code version (0.6.0 / 0.6.0-dev).
- `settings-D3` (S6): slider "Desktops" a11y stateDescription is "%.2fx" (e.g. "6.00x"), a speed format.
- `settings-D4` (settings.devices B2): Manage devices summary counts only bonded Bluetooth hosts; a Wi-Fi Mac user sees "No paired devices".
- `settings-D5` (settings.pointer-mouse B4, S3): "Right-click: 2-finger tap" only disables hold; 2-finger tap always right-clicks; scroll speed / natural scrolling don't apply to the Media scroll lever; the stick's 450 ms right-click ignores the Right-click setting.

## UNDEFINED
- S8: macOS System Settings layout (jG-s4) is macOS UI; Mote can't detect whether ⌃N is enabled over Bluetooth.
- settings.haptics B3, all haptic switches: vibration output (manual).
- Theme colours / extra-dim brightness are visual (screenshot / window attribute only).
