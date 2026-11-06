# mote design language v0.1

Android BT-HID remote for a Mac. One hand, in the dark, eyes on the Mac. **Touch and haptics are the UI; pixels are the confirmation.**

## 0. Research: take / reject

| Source | Take | Reject (why) |
|---|---|---|
| Material 3 Expressive (2025) | Spring-based motion (spatial/effects specs), shape morphing on press, bigger/bolder type, larger "extra-large" button sizes, button groups, haptic-on-state-change guidance | Dynamic color as the primary palette (state colors must be fixed and learnable in the dark; wallpaper hues break "orange = active"). Playful morph on every element (distracting, costs frames). FAB/top-app-bar chrome (wastes reach zone) |
| M3 baseline | 48dp min target, tonal surface levels (no shadow reliance in dark), state layers (8/10/16% hover/focus/press), `Slider`/segmented patterns | Elevation shadows (invisible in dark); standard 40dp buttons (too small) |
| iOS 26 Liquid Glass / HIG | Concentric corner radii (inner = outer - padding), floating bottom controls that keep content edge-to-edge, 44pt min target, haptics as semantic vocabulary (success/warning/selection), tap-and-hold affordance | Translucent blur/refraction (battery, low contrast over variable content, unreadable at night, non-native on Android). Tiny tab-bar labels |
| Apple TV Remote (Control Center) | Clickpad with swipe-to-navigate + tap-to-select + edge-click-as-D-pad; one big touch surface instead of many buttons; volume as hardware keys | Hiding transport in sub-screens |
| Google TV remote app | D-pad/touchpad toggle in one surface; persistent bottom row for Back/Home; voice entry | Sparse, low-contrast gray-on-gray controls |
| Unified Remote | Many "remotes" as modules; custom remotes (our macros) | Dense desktop-style toolbars, small keys, cluttered nav drawer |
| Steam Link / Steam Deck | Haptics on trackpad edges/clicks; radial/ring gestures; chorded button hints | Gamepad-complexity layouts (we are not a gamepad) |
| Nothing OS | Mono/dot-matrix type for status, near-black + one accent (red), restrained iconography, haptics as brand | Pure-decorative glyph effects |
| Teenage Engineering (OP-1/EP) | Few colors, each with fixed meaning; physical-feeling keys with obvious state (lit/unlit); orange accent; rounded-rect keys on a grid; labels in small caps mono | Cryptic iconography requiring a manual |

## 1. Principles

1. **Eyes-free first.** Every primary action has a unique haptic signature and a location that never moves. Rule: no layout reflow after first draw; primary controls are anchored to screen edges/thirds, not content.
2. **Big beats pretty.** Primary targets >= 56dp (media, D-pad, mode dial), secondary >= 48dp, never < 44dp even for dense keyboard (keycap visual may be smaller; hit area is not). Gaps >= 8dp between adjacent targets.
3. **Confirm in the felt channel, then the visual.** Press = haptic within one frame (<16ms), pixel change second. Visual feedback must be visible peripherally: state is conveyed by fill (lit/unlit), never by thin outlines or color alone.
4. **One accent, fixed semantics.** Orange = primary/active action, yellow = modifier armed, green/amber/red = link state only. Colors never change meaning per screen.
5. **Dark is the default; dim is the law.** Max luminance of any large area <= #1C1C21; white-ish only for text and glyphs. Light theme exists, is auto-selected only by explicit user choice.
6. **Modes are cheap and non-destructive.** Switching mode is one thumb gesture, preserves state (sticky modifiers excepted: they clear on mode switch only if user enables it), and never moves the mode switcher.
7. **Fail loud on disconnect.** Controls that cannot do anything are visibly dead (dimmed, haptic "reject" on tap) and a connection pill is always on screen. Never silently queue keystrokes for a lost Mac.
8. **Less system, more tokens.** Complexity is debt: every component is built from the tokens below; no per-screen one-off colors, radii, or durations.

## 2. Color

Dark-first, warm-neutral near-black (OLED-friendly, less blue light than cool grays). All hex are sRGB. Contrast computed with WCAG 2.x relative luminance.

### 2.1 Dark theme (default)

| Token | Hex | Use | Contrast (vs) |
|---|---|---|---|
| `bg` | `#0B0B0D` | screen background | on-surface 15.8:1 |
| `surface-1` | `#131316` | pager page, bars | |
| `surface-2` | `#1C1C21` | keycaps, tiles, touchpad | on-surface 13.6:1 |
| `surface-3` | `#2A2A31` | pressed keycap, raised/hover, dividers | accent 5.5:1 |
| `surface-4` | `#35353D` | selected segment, toggled-off chip | |
| `on-surface` | `#E8E6E1` | primary text/glyphs | 15.8 on bg |
| `on-surface-muted` | `#A3A19B` | secondary labels | 7.6 bg / 6.6 surface-2 |
| `on-surface-subtle` | `#8A8780` | hints, disabled labels (disabled exempt from WCAG, still >5:1 on bg) | 5.5 bg |
| `outline` | `#3A3A42` | 1dp hairlines, focus ring base | decorative |
| `accent` | `#FF7A1A` | primary fill, active toggles, focus ring, touchpad ripple | 7.5 bg / 6.5 surface-2 |
| `on-accent` | `#1A0E00` | glyph on accent fill | 7.3 on accent |
| `accent-pressed` | `#CC5F0E` | accent fill while pressed (also `accent` at 85% brightness) | on-accent >= 4.5 verify at build |
| `accent-container` | `#3A1E08` | tinted bg for active-but-passive (e.g. mute on) | accent on it >= 5 |
| `modifier-armed` | `#FFD60A` | sticky modifier: one-shot armed | 13.9 bg |
| `on-modifier` | `#1A1400` | glyph on armed fill | 13.0 |
| `modifier-locked` | `#FFD60A` fill + 3dp `#0B0B0D` inner gap + 2dp `#FFD60A` ring | caps-lock style latch (shape differs, not just color) | |
| `danger` | `#FF5C5C` | destructive (delete macro), error | 6.5 bg / 5.6 surface-2 |
| `danger-container` | `#3A1416` | danger tile bg | |
| `link-connected` | `#3DDC84` | pill dot/text when HID connected | 11.0 bg |
| `link-connecting` | `#FFB020` | pairing / reconnecting (pulsing) | >=9 bg |
| `link-disconnected` | `#FF5C5C` | lost link (same as danger, intentional) | 6.5 bg |
| `scrim` | `#000000` @ 60% | sheets/dialogs | |

State layers on filled surfaces: pressed = overlay `#FFFFFF` @ 10% (dark neutral) or the explicit `*-pressed` token above for accent. Focus (hardware keyboard / TalkBack) = 2dp `accent` ring offset 2dp.

Pure black `#000` is reserved for OLED "blackout" mode (optional setting: bg = `#000000`, surfaces unchanged).

### 2.2 Light theme mapping (opt-in)

| Dark token | Light hex | Note / contrast |
|---|---|---|
| `bg` | `#F6F4EF` | |
| `surface-1` | `#EFECE5` | |
| `surface-2` | `#E9E6DF` | on-surface 13.9:1 |
| `surface-3` | `#DCD9D1` | |
| `surface-4` | `#CFCBC2` | |
| `on-surface` | `#1A1A1D` | 15.8 on bg |
| `on-surface-muted` | `#5E5C57` | 6.1 bg / 5.4 surface-2 |
| `on-surface-subtle` | `#6B6963` | 5.0 bg / 4.4 surface-2 (use for large/decorative only) |
| `accent` | `#B34700` | 5.0 on bg; fill with `#FFFFFF` glyph 5.5 |
| `accent-pressed` | `#8F3A00` | |
| `modifier-armed` | `#FFD60A` fill, `#1A1A1D` glyph (12.3) | stays bright: it must pop |
| `danger` | `#B3261E` | 5.95 bg |
| `link-connected` | `#1F7A45` | 4.86 bg (text ok; white on it 5.35) |
| `link-connecting` | `#8A6A00` | 4.61 bg |

### 2.3 Dynamic color stance (Material You)

**Off by default, no wallpaper-derived palette.** State semantics (orange active / yellow modifier / link colors) are the product; wallpaper tint would collide with them (e.g. a green wallpaper makes "armed" ambiguous). Allowed: an opt-in "Match system" that swaps only `surface-*` neutrals tone from the system neutral palette; accent and state tokens stay fixed. Android 12+ `dynamicDarkColorScheme` is not used for roles. Do follow system dark/light and respect system font scale.

## 3. Typography

Free OFL fonts, bundled as variable TTF (small, ~100-300KB each). System fallback `sans-serif` / `monospace` (Roboto / Roboto Mono, always present on Android).

- **UI / labels: Inter** (OFL; variable; wide x-height, tabular figures via `tnum`). Fallback Roboto Flex/Roboto.
- **Keycaps, status, macro names, connection pill: JetBrains Mono** (OFL) for the Teenage-Engineering/Nothing hardware feel and fixed-width legibility of shortcuts (`⌘⇧4`). Fallback Roboto Mono. Use `⌘ ⌥ ⌃ ⇧ fn ⎋ ⇥ ⏎ ⌫` glyphs; verify coverage, else draw them as vector icons.
- Two families max. Small caps look achieved by uppercase + `letterSpacing +0.08em` on labels (no real small-caps).

| Role | Font | Size / line | Weight | Tracking | Use |
|---|---|---|---|---|---|
| `display` | Inter | 36/44 sp | 600 | -0.5 | volume % readout, connection hero |
| `headline` | Inter | 24/32 | 600 | 0 | sheet titles |
| `title` | Inter | 18/24 | 600 | 0 | section titles, macro name in editor |
| `body` | Inter | 16/24 | 400 | 0 | descriptions, settings |
| `label-l` | Inter | 14/20 | 600 | +0.1 | buttons, tab labels |
| `key-l` | JetBrains Mono | 20/24 | 600 | 0 | keycap legend (letters) |
| `key-s` | JetBrains Mono | 12/16 | 500 | +0.5 | modifier keys, secondary legends, macro chord |
| `caps` | JetBrains Mono | 11/16 | 500 | +0.9 UPPER | pill state text, section overlines |

Floors: nothing below 11sp; anything the user must read while holding the phone at arm's length >= 14sp. Support font scale to 1.5x without clipping (keycaps scale glyphs up to 1.3x, then ellipsize labels, never change key size).

## 4. Shape, spacing, elevation

- **Grid:** 4dp base, 8dp layout rhythm. Screen margin 16dp (portrait) / 24dp (landscape, plus safe insets). Gutter between keys 6dp (keyboard) / 8dp (everything else) / 12dp (media & D-pad).
- **Radii:** `r-xs` 6, `r-s` 12 (keycap, chip), `r-m` 20 (tile, button), `r-l` 28 (touchpad, sheet top, D-pad outer), `r-full` 999 (pill, round media, dial). Nesting rule (from Liquid Glass concentric): inner radius = outer - padding, min 6.
- **Touch targets:** primary >= 56dp (play/pause 72dp, volume +/- 64dp, D-pad center 72dp, D-pad arms 64x56 minimum). Secondary >= 48dp. Keyboard keycap hit area >= 44dp tall x 34dp wide in portrait (absolute floor; nudge to >=40 wide only on 5-col-wide function row), extended by invisible slop to eliminate dead gutter.
- **Size scale:** 4, 8, 12, 16, 24, 32, 48, 56, 64, 72 dp.
- **Elevation: none via shadow.** Depth = tonal surface level (`bg` < `surface-1` < `surface-2` < `surface-3`). Raised keycap look = 1dp top inner highlight `#FFFFFF`@6% and 1dp bottom inner shade `#000000`@40%. Floating bar/sheet: `surface-1` + `outline` hairline. No blur, no glass.
- **Icons:** Material Symbols Rounded, weight 500, optical size 24, filled when active. 28dp icons in primary buttons, 24dp elsewhere.

## 5. Interactivity

### 5.1 Press states
- **Down (immediately, no delay):** haptic fires, fill goes to pressed token, scale 0.96 (keys) / 0.94 (round media) via spring `press`.
- **Up inside:** action commits (keys send key-up on release; media sends on down for volume/brightness for immediacy, on up for transport toggles to allow cancel-by-slide-off).
- **Slide off target (> 12dp outside):** cancel, `REJECT`-light haptic is NOT played (silence = cancelled); fill springs back.
- **Toggled-on:** persistent fill (`accent` or `accent-container` + accent glyph). **Disabled:** 38% content alpha, tap plays `REJECT`.
- Ripple is replaced by fill+scale (cheaper, visible in peripheral vision). No ripple on touchpad except a 160ms touch-point glow (`accent`@25%, 56dp circle) to confirm contact.

### 5.2 Haptic vocabulary
Use `View.performHapticFeedback` for standard cases (no permission needed, respects user setting); `VibrationEffect.Composition` primitives (API 30+, check `areAllPrimitivesSupported`) for the custom ones; fall back down the list. Amplitude is user-scaled (Off / Light / Standard / Strong; default Standard).

| Semantic | When | Primary (API 30+) | Fallback |
|---|---|---|---|
| `TICK` | key down, D-pad arm, repeat step, touchpad scroll detent, slider step | `PRIMITIVE_TICK` (scale 0.6) / `CLOCK_TICK` | `KEYBOARD_TAP` / `CLOCK_TICK` |
| `CLICK` | primary press: play/pause, mute, D-pad center, macro fire, left click | `PRIMITIVE_CLICK` (0.8) | `VIRTUAL_KEY` / `EFFECT_CLICK` |
| `HEAVY` | long-press recognized, drag start, right-click, mode switch commit | `PRIMITIVE_THUD` (0.7) | `LONG_PRESS` / `EFFECT_HEAVY_CLICK` |
| `ARM` | sticky modifier armed (one-shot) | `PRIMITIVE_CLICK` 0.5 | `CONTEXT_CLICK` |
| `LOCK` | modifier locked (double-tap) | `PRIMITIVE_CLICK` 0.8 + `PRIMITIVE_THUD` 0.5 @ +40ms | `CONFIRM` |
| `RELEASE` | modifiers auto-cleared after chord | `PRIMITIVE_LOW_TICK` 0.4 | `CLOCK_TICK` |
| `CONNECT` | link established | rising `PRIMITIVE_QUICK_RISE` 0.7 | `CONFIRM` |
| `DISCONNECT` | link lost | falling `PRIMITIVE_SLOW_RISE`->`QUICK_FALL` double | `REJECT` |
| `REJECT` | disabled/blocked tap, error | 2x `PRIMITIVE_TICK` 0.9 @ 60ms | `REJECT` |
| `EDGE` | touchpad/D-pad hits a boundary (scroll limit, volume 0/100) | `PRIMITIVE_LOW_TICK` 1.0 | `CLOCK_TICK` |

Rules: never more than one haptic per 25ms (coalesce); repeat-ticks cap at 12/s; haptics are the primary feedback, so respect "Touch feedback" system toggle but warn once in onboarding if disabled. Optional matching sound: none (dark, presentations).

### 5.3 Motion tokens
Springs preferred (M3 Expressive); durations for opacity/color only. Respect `ANIMATOR_DURATION_SCALE` = 0 (instant).

| Token | Spec | Use |
|---|---|---|
| `press` | spring stiffness 1500, damping 0.8 (Compose `Spring.StiffnessHigh`) | scale on press/release |
| `spatial-default` | spring stiffness 380, damping 0.8 | mode pager settle, sheet move |
| `spatial-fast` | spring stiffness 800, damping 0.9 | chip/state toggles, dial detent |
| `effect-fast` | 100ms, `cubic-bezier(0.2, 0, 0, 1)` | color/opacity state change |
| `effect-med` | 200ms, `cubic-bezier(0.2, 0, 0, 1)` | pill state change, dimming |
| `exit` | 120ms, `cubic-bezier(0.3, 0, 1, 1)` | dismissals |
| `pulse` | 1200ms ease-in-out loop, alpha 0.4-1 | `link-connecting` only |

No motion over 300ms. No parallax, shimmer, or idle animation (other than `pulse`) so the screen stays still and low-power.

### 5.4 Touchpad gesture map
Surface: full-area `r-l`, `surface-2`, subtle dot-grid texture (4% white, 24dp pitch) to show it is live. Pointer motion uses an acceleration curve (user speed 1-10; default gain 1.8x, quadratic above 600dp/s).

| Gesture | Mac action |
|---|---|
| 1-finger drag | move pointer |
| 1-finger tap | left click (`CLICK`) |
| double-tap | double click |
| tap, then drag within 300ms (tap-hold-drag) | click-drag (`HEAVY` on lock-in, `TICK` on release) |
| long-press 400ms (no move) | right click (`HEAVY`) |
| 2-finger tap | right click (alt; user can swap with long-press) |
| 2-finger drag (vertical/horizontal) | scroll (natural scrolling default, `TICK` per ~48dp detent, `EDGE` not applicable) |
| 2-finger pinch | zoom (ctrl+scroll / pinch HID if available) |
| 3-finger swipe left/right | switch Space (ctrl+arrow) |
| 3-finger swipe up | Mission Control; down: App Exposé |
| 3-finger tap | Look Up / Spotlight (user remappable) |
| edge swipe from left/right of phone | system back (OS-owned; touchpad insets gestures with 24dp edge margin) |

Palm/one-hand rule: thumb pad area uses a 12dp dead-edge on the bottom edge to ignore palm.

### 5.5 Repeat and long-press
- **Volume, brightness +/-:** tap = 1 step (Mac 1/16 step). Hold: after 350ms start repeating at 8/s, accelerating to 16/s after 1.2s; `TICK` per step; `EDGE` at min/max. Release stops instantly. Never repeat mute/play/next.
- **Next/prev (media):** tap = track skip; hold 500ms = seek (repeat 4/s) with `HEAVY` at start.
- **D-pad arms:** tap = 1 key; hold 300ms -> repeat at 10/s (matches macOS key repeat), `TICK` per repeat. Center: tap = Return/Select; hold 500ms = Space (QuickLook) as default, configurable.
- **Keyboard keys:** hold = key repeat (macOS-like: 400ms delay, 25/s), no long-press accents (breaks speed); modifiers see 5.6.
- **Macro tile:** tap fires; long-press 500ms opens edit sheet (`HEAVY`). Destructive/slow macros set "confirm": first tap arms (tile turns `danger-container`, text "Tap again", `ARM`), second within 2s fires.

### 5.6 Sticky modifiers (Cmd / Opt / Ctrl / Shift / Fn)
Three states, shown by shape+color, not color alone:
1. **Idle:** `surface-3` fill, `on-surface` glyph.
2. **Armed (one-shot):** tap once. `modifier-armed` fill, `on-modifier` glyph. Auto-clears after the next non-modifier key (or macro) is sent; `ARM` on enter, `RELEASE` on clear.
3. **Locked:** double-tap (or long-press 350ms). Armed fill plus 2dp inner ring and a small lock-dot top-right; stays until tapped. `LOCK` haptic.
- Tap a held modifier again to clear. Multiple modifiers combine. Armed/locked state is mirrored in a one-line "chord preview" strip above the keyboard (`⌘⇧` + next key) in `key-s`.
- Modifiers are sent as real HID modifier down (on arm) so mouse clicks on touchpad become cmd-click/shift-click; they are released on clear. Safety: all modifiers release on disconnect, app background, or after 30s of inactivity (with `RELEASE`).

## 6. Layout & navigation

### 6.1 Mode switching: decision
Candidates: bottom nav bar, swipe pager, mode dial. **Chosen: persistent bottom "mode rail" (4 segments, thumb zone) + horizontal swipe on the rail only, not on page content.**
- Reason: swipe-pager over content collides with the touchpad, D-pad and keyboard (all gesture-rich). Bottom bar is eyes-free-able: positions are fixed, big (>= 64dp tall, full width / 4), and each segment has a unique `CLICK`-style haptic pitch (`TICK` count 1-4). Mode dial (rotary) is novel but slow, imprecise one-handed, and non-standard for Android: rejected.
- Modes (left to right): **Media** (includes D-pad), **Pad** (touchpad), **Keys**, **Macros**. Order stable; default mode on connect = last used.
- Swipe along the rail (or 2-finger horizontal swipe on a non-touchpad page) also switches; settle with `spatial-default`.
- Rail is `surface-1`, icon + `caps` label; selected = `accent` indicator pill (r-full, 56x32) behind a filled icon, rail hides **never**. Gesture insets: leave 16dp bottom for system nav pill.
- Settings/pairing live behind the connection pill (tap), not in the rail.

### 6.2 Screen skeleton (portrait)
```
+--------------------------+  y 0
| [ connection pill ]  [⋯] |  top 56dp: status zone (glance, not touched often)
|                          |
|        MODE CONTENT      |  content: top 2/3 = "read" zone (rarely tapped)
|                          |
|   ----- thumb zone ----- |  bottom ~45% = primary controls
| [Media][Pad][Keys][Macro]|  rail 72dp
+--------------------------+
```
- **Reach rule:** frequent actions in bottom 45% and biased to the dominant-hand side (setting: left/right/centered; default centered, symmetrical D-pad). Top zone holds only status and low-frequency actions (menu, hand-swap).
- Media mode (bottom-up): row of volume -/+ + mute; D-pad (circular, 208dp) above; transport (prev / play-pause 72dp / next) in the middle; brightness secondary row at top of content. Everything fits one screen without scroll.
- Pad mode: touchpad fills content; a 56dp strip beneath it holds L/R buttons and a "scroll/drag lock" toggle, optional; gestures suffice.
- Keys mode: chord-preview strip + modifier row (sticky, 56dp tall) + a QWERTY (key 44dp min height, 5 rows) + function row collapsible; modifiers are on the thumb edge. Landscape gives a true full keyboard.
- Macros: 3-col grid portrait (4 landscape), tiles >= 96x72dp; "+" tile last; edit mode via long-press with drag-reorder.

### 6.3 Landscape
Rail moves to the **left edge vertical** (72dp wide, same order); content gets full height. Media: D-pad left of transport. Keys: full 6-row keyboard, modifiers in row positions like a Mac. Touchpad fills remaining width. Honour cutout/insets; screen stays on while connected (configurable), no auto-rotate lock override.

### 6.4 Presentation / glance details
- Wake lock while connected and app foreground (optional); brightness: app uses `bg` only, with optional "extra dim" window brightness of 0.05-0.2.
- Lock-screen/quick-tile entry for Media mode is out of scope for v0.

## 7. Components

| Component | Spec |
|---|---|
| **Key cap** | 44-48h x flexible w; `surface-2` fill, `r-s` 12, 1dp top highlight / bottom shade; legend `key-l` centered (secondary legend `key-s` top-left in `on-surface-muted`). Down: `surface-3`, scale .96, `TICK`. Return/space bar `surface-3` tint; modifier keys use 5.6 states. Hit area expanded to full gutter. |
| **Media button** | Round (`r-full`) 64dp (secondary), 72dp (play/pause, `accent` fill, `on-accent` glyph 28dp). Secondary: `surface-2` fill, `on-surface` glyph. Toggle on (mute): `accent-container` fill + `accent` glyph. Press: scale .94, `CLICK`. Volume +/- are a paired "button group" (shared r-l pill 64x136 stacked or 136x64 side by side) with the `display` readout in between when changing (fades 1.5s). |
| **D-pad** | 208dp circle, `surface-2`, 4 arms as sectors (no visible gaps between arms; 8dp visible divider lines in `outline`), center disc 72dp `surface-3` (`accent` ring 2dp on press). Sector hit-test by angle (45deg boundaries, 25dp dead-zone ring around center). Arm press fills sector with `surface-3` (+ `accent` triangle glyph). Arrow glyph 24dp. Optional swipe-on-D-pad = repeat nav. Haptics: arm `TICK`, center `CLICK`, hold-repeat `TICK`. |
| **Touchpad surface** | Fills content, `surface-2`, `r-l` 28, dot-grid texture 4%. Touch glow 56dp `accent`@25%. Corner chip top-left shows last gesture as `caps` text for 800ms (debug/learn, optional). Locked states (drag lock, scroll lock) show 2dp `accent` border. Disabled (no link) = `surface-1` + "Not connected" `title`. |
| **Modifier chip** | 56h x min 64w (⌘ ⌥ ⌃ ⇧ fn), `r-s` 12, `key-s` legend with glyph above name (e.g. `⌘` 20sp + `cmd` 11sp). States: idle / armed / locked per 5.6. Always rendered in the same left-to-right order as Mac bottom-left of keyboard: `fn ⌃ ⌥ ⌘`, and `⇧` on the left of Z row. |
| **Macro tile** | >= 96x72dp, `surface-2`, `r-m` 20, 12dp padding. Top-left: emoji/icon 24dp or user-chosen glyph; bottom: name (`label-l`, 1-2 lines) and chord in `key-s` `on-surface-muted`. User tint from 8 preset hues at 24% over `surface-2` (never full-saturation). Fired: flash `accent`@40% 160ms + `CLICK`. Confirm-macro: see 5.5. Editor: bottom sheet (`r-l` top), fields: name, icon, action type (keystroke chord recorder / text / app launch / sequence w/ delays), "Confirm before run" toggle; record chord field uses live key capture with modifiers rendered as chips; Save is full-width 56dp `accent`. |
| **Connection pill** | 36dp tall, `r-full`, `surface-1` fill + 1dp `outline`; left 10dp status dot + Mac name (`caps` 11sp, truncated 16ch) + latency glyph optional. States: connected (green dot, steady), connecting (amber dot `pulse`), disconnected (red dot, pill border `danger`, label "TAP TO RECONNECT"), pairing (amber, shows 6-digit code in `display`). Tap = connection sheet (device list, forget, re-pair). Trigger haptics `CONNECT`/`DISCONNECT`. Always >= 48dp touch height via padding. |
| **Mode rail** | See 6.1. |
| **Bottom sheet** | `surface-1`, `r-l` top, drag handle 4x32dp `outline`; max 70% height; scrim 60%. |
| **Toggle / slider** | Switch 52x32 track `surface-4` -> `accent`; slider track 8dp, thumb 28dp circle (hit 56dp), `TICK` each step. |

## 8. Accessibility & misc
- Every control has `contentDescription`; actions (not "button") phrased as verbs. TalkBack is a secondary path; touchpad exposes custom actions (click, right click, scroll).
- Targets never rely on color alone (armed vs locked shape differs, link state has text).
- Minimum text contrast 4.5:1 (7:1 for `caps` 11sp text on dark), non-text 3:1 (all fills listed above pass vs adjacent surface except `surface-2` on `bg`, 1.2:1 - separated by the `outline` hairline and tone, which is acceptable as decoration; interactive zones are also identified by glyph).

## Sources (brief)
Material 3 Expressive & M3 specs (m3.material.io, Google I/O/Android Developers blog 2025, motion springs, button groups, 48dp targets); Android `HapticFeedbackConstants`, `VibrationEffect.Composition` primitives (developer.android.com/develop/ui/views/haptics); Apple HIG (Liquid Glass, Materials, Haptics, Layout, 44pt targets, iOS 26 2025); Apple TV Remote (Control Center) and Google TV app UX; Unified Remote, Steam Link/Steam Deck (trackpad haptics) public docs; Nothing OS and Teenage Engineering OP-1/EP-133 interface conventions; WCAG 2.2 SC 1.4.3/1.4.11/2.5.8. Contrast ratios computed locally from the hex values in this file. Treat `accent-pressed` and `modifier-locked` shape as to-verify at implementation.

## Revision 1 decisions (2026-11, from canvas review of design/mote-v1)

These override the sections above where they conflict. `design/mote-v1/mote.css` holds the live token values.

- **Scope: any Bluetooth host, not only Macs.** All copy is device-agnostic and names the selected host ("Searching for MacBook Pro…"). The connection pill becomes a **device selector** (LED, host name, chevron). Tapping it opens the device list sheet. While the app is in the foreground it keeps searching for the selected host on its own (an amber pulse shows this). There is no manual Reconnect button.
- **Palette "Graphite" (dark, default)** replaces flat near-black: chassis gradient `#1C1E23`->`#121317`, `bg #15161A`, `surface-1 #1B1D22`, recessed `well #0D0E11`, key caps gradient `key-top #383B43`->`key-bot #2A2C32` with `key-edge #1A1B1F`. Text: `on-surface #ECEAE6`, `muted #A9A7A2`, `subtle #8D8B86` (all >= 4.5:1 on bg and on caps). Accent is a copper signal: `accent #FF8A3D` (cap gradient `#FFA463`->`#F06A1C`, `on-accent #2A1200`). `danger #FF6B66`. Semantics are unchanged: copper = primary/active, yellow = modifier, green/amber/red = link.
- **Palette "Putty" (light)** keeps the 2.2 mapping, adding caps `#FFFFFF`->`#ECE9E3`, `well #DEDAD2`, `outline #D2CEC5`, `accent-container #F6DCC6`, `danger-container #F6D9D6`, and cap gradient `#D2600F`->`#A84200` around `accent #B34700`.
- **Material (skeuomorphic, tasteful).** This replaces "Elevation: none via shadow".
  - **Two primitives.** *Raised cap*: vertical gradient, 1dp top bevel highlight, 1dp bottom bevel, 1dp edge, and a soft drop shadow. *Recessed well*: darker fill with an inner shadow. Every control is a raised cap. Every container that holds controls is a well: the touchpad, keyboard deck, rail track, button pairs, the play bezel and the D-pad housing.
  - **Pressed** inverts the cap gradient, swaps the drop shadow for an inner shadow, and moves the cap down 1dp. **Locked** modifiers stay pressed-in, with a ring.
  - **Texture.** One hairline vertical "brushed" grain at 2-3% on the chassis only. No glass, blur or photoreal textures.
  - **The accent cap is the only colored cap**: a convex copper disc in a recessed bezel. Use it for play/pause and primary actions.
- **Typography: mono dropped.** JetBrains Mono is gone. Everything is set in the UI sans (Inter, falling back to Roboto), keycaps included: `key` 18/22 w500, `key-s` 13/16 w500. Uppercase `caps` (11/16 w600, +0.7 tracking) is used only for small section overlines.
- **Mode rail:** a recessed track holding 4 segments. The selected segment is a raised cap with a copper icon. Labels are sentence case, 12sp. **Modes renamed: Media / Mouse / Keys / Macros** ("Pad" became "Mouse", with a pointer icon). In landscape the rail sits on the left and the device selector is a compact cap at its top. There is no top bar in landscape.
- **Icons:** our own inline set on a 24 grid with 1.75 stroke, round caps and joins. Fills are reserved for transport glyphs and on-states. This replaces Material Symbols for v1 drafts.
- **Media layout (top to bottom):**
  - Favourites strip: starred macros, 4 caps.
  - Navigation row: esc/space column, D-pad, scroll lever. This sits in the upper zone because it isn't the focus.
  - Brightness pair plus mute.
  - Transport, which is the focus and sits in the prime thumb zone: a 96dp copper play/pause in a 112dp bezel, with equal 64dp prev/next.
  - Volume: two caps, 50/50, with no readout. HID is one-way, so we can't know the host's volume level anyway.
  - **D-pad center** is a plain concentric "OK" cap.
- **Scroll lever** (new component): a 56x208 well with a spring-loaded thumb.
  - Drag up or down to scroll, with speed proportional to displacement after a 6dp deadzone.
  - `TICK` haptic each time the speed band changes. The thumb springs back to center (`spatial-fast`) on release.
  - Tapping the top or bottom chevron sends one notch.
- **Macros:**
  - Tiles get a star; starred macros also show on Media.
  - Tint becomes a small colored stripe on the cap's top edge plus a tinted icon, not a wash.
  - Action types: Shortcut / Text / Sequence / **Click at point**. App launch is a Sequence preset.
  - Click at point stores X,Y. It is set by capturing the current pointer position or by typing numbers. It runs by slamming the pointer to the top-left corner, then moving by the offset. That is approximate under pointer acceleration, and the UI says so. The optional Mac helper makes it exact.
  - Macros that need pointer input (e.g. Screenshot area) switch to Mouse automatically and show a banner ("Drag to select, tap to capture", with Esc). They return to Macros when done.
- **Pairing** only makes the phone discoverable (with a countdown) and gives instructions. Android's own system prompt confirms the passkey, so mote no longer shows a code.
- **Overflow menu (⋯)** opens a sheet with Theme, Manage devices, Haptics, Settings and About.
- **Keys:**
  - The main keyboard is a laptop-style deck: an edge-to-edge well, 4dp gutters, 50dp keys, no keypad gaps.
  - The function/navigation section uses 58dp keys and an inverted-T arrow cluster.
  - Modifiers are labelled control/option/command. fn is shown disabled ("not sendable").

## Revision 2 decisions (2026-11, second canvas review)

- **Brand casing:** "Mote" with a capital M in all copy and titles.
  - **Wordmark:** "Mote" set in **Michroma** (OFL, from google/fonts `ofl/michroma`), subset to woff2 at `design/mote-v1/fonts/`. Michroma's wide, square-shouldered letters read like a label machined into a hardware chassis.
  - **Placement:** the wordmark sits at the right of the top bar, debossed into the material. Its fill is a little darker than the chassis, with a layered text-shadow: a dark 1px edge above and a light 1px highlight below, matching the light from the top.
  - **Menu:** the ⋯ menu is a small engraved glyph beside the wordmark.
  - **Font scope:** Michroma is used only for the wordmark. All UI text stays in the UI sans.
- **Light is the default theme.** It is not opt-in any more; dark is the user's choice (or follows the system setting). Principle 5 ("Dark is the default") is replaced: *quiet is the law*. No large bright accent areas in either theme.
- **One material family, two finishes.** These replace the Graphite/Putty palettes and the copper accent:
  - **Silver (light, default):** bead-blasted aluminium chassis `#EEEFF1`->`#D9DBDF`; soft-touch caps `#FBFBFC`->`#E6E7EA`, edge `#B4B8BE`; well `#CBCED3`; printed ink `#1C1D20` / muted `#50535A` / subtle `#62656C`.
  - **Titanium (dark):** anodised chassis `#2C2E33`->`#1C1D21`; rubber caps `#3E4148`->`#31343A`; well `#141518`; ink `#E9EAEC` / `#A9ACB2` / `#8F939A`.
  - **Texture:** fractal noise from an inline SVG `feTurbulence` data URI. It sits at 10% (light) or 16% (dark) over the chassis, blended soft-light into caps and sheets, together with a 2% brushed hairline. No external images.
  - **Primary cap** (play/pause, Save, Pair new device): dark anodised aluminium in light, smoked glass in dark. Its legend is printed white in light and LED-backlit in dark (`#8FD3FF` with glow).
  - **Accent comes from the material.** It is a backlit-LED "glacier" tint: `#1D6FB8` light, `#6CC4FF` dark. It marks active/selected/focus states (rail icon, stars, radios, drag lock, touch glow). Orange is retired. Yellow (armed modifier) and green/amber/red (link state) are unchanged.
  - **Legends are monochrome ink, printed on the cap.** Macro tints survive only as a thin top-edge marker.
- **Play/pause** is a rounded rectangle (156x84, r22) inside a machined recess. The recess is 8dp all round, with outer radius 30 = 22 + 8, so the cap sits concentric. The recess is darkest at the top lip, lighter at the bottom, with a 1px highlight on the bottom edge (top light). Prev/next are 64dp rounded-square caps.
- **Volume cluster:** − | mute | + in one well (1fr / 76dp / 1fr). Brightness is a separate full-width pair: a small dim sun with short rays vs a large filled sun with long rays.
- **esc demoted:** a small 52x40 cap at the D-pad's lower left. **space dropped from Media**: a long-press on the D-pad center already sends Space, and the Keys screen has a space bar.
- **Gesture tile: Spaces.** One tile replaces Prev/Next Space.
  - **Hold and slide:** sends ⌃← or ⌃→, one step per slide past a 32dp threshold, with `TICK` on each step. Holding at the edge auto-repeats at 3/s.
  - **Tap:** opens Mission Control (⌃↑).
  - **Affordance:** ‹ › chevrons and a "hold & slide" label.
  - **Placement:** spans 2 columns on Media favourites and in the Macros grid.
  - **Editing moves to the Edit button:** holding a tile no longer opens the editor, because hold is a gesture on gesture tiles.
- **Default favourites:** Spaces, Spotlight, Lock (Lock still requires confirmation). Mission Control is reachable by tapping Spaces, so it isn't a separate default.

## Revision 3 decisions (2026-11, third canvas review)

- **Wordmark:** "MOTE" in all caps, set in **Unbounded Black** (OFL, from google/fonts `ofl/unbounded`). The variable font is pinned to wght 900 and subset to woff2. Size 19sp, tracking +2.5. Michroma is dropped because it has only one, light weight. Syncopate was rejected because google/fonts ships it under Apache 2.0, not the OFL. The deboss is retuned for the heavy weight: fill a touch darker than the chassis, a 1px dark top edge plus a 2px soft inner shade, and a 1px light bottom edge. Brand casing in running copy stays "Mote".
- **Grain is part of the material.** The noise layer is stronger in both themes: feTurbulence baseFrequency 1.1, alpha 0.9, chassis opacity 26% (light) / 34% (dark), blended soft-light into caps and sheets. It should be visible at 1x without looking gritty. Text contrast is unaffected: the noise shifts luminance by about 2% at most.
- **Bevels, not arcs.** Round caps (D-pad disc, OK cap) get a full-circle 1.5px bevel stroke with a vertical gradient: white at 90% at the top, fading to clear by 35%, then black at 22% at the bottom. This is the specular edge for light from the top. The old floating highlight arc is removed.
- **Directional legends:** brightness caps show the sun plus a small arrow (↓ dim, ↑ bright), because the two sun shapes alone weren't distinct enough.
- **Favourites default to Spaces only.** The rest of the strip is an empty dashed "+ Add favourite" slot, so the strip never looks broken. Lock is no longer a favourite, which settles the confirm-on-favourite question. The strip's Edit is a real control: a 40dp raised cap with an icon and a 14sp label, with a 48dp hit area.
- **Selection language (one rule everywhere):** the selected item is a **raised cap**. Unselected items are **flat**, with a hairline outline and no shadow. **Recessed** is only for containers (tracks, wells, fields), never for an unselected item. This applies to the rail, device picker rows and segmented controls.

## Revision 4 decisions (2026-11, first on-device review)

### One material per control family
Every control family has its own material and keeps it in both themes. That gives the eye tonal and specular contrast instead of "everything is the same grey". Each recipe below uses only Compose primitives: `Brush` gradients, layered `drawBehind`/`shadow`, and one tiled noise `ImageBitmap` (generate a 128px fractal-noise PNG once at build time; alpha about 0.9 black).

| Family | Used by | Light | Dark | Compose recipe |
|---|---|---|---|---|
| **Backplate** (machined, textured) | screen background | bead-blasted aluminium `#EEEFF1`->`#D9DBDF` | anodised `#26282D`->`#17181B` | `verticalGradient(top, bot)`, then a 1px/3px vertical hairline pattern at 2% (`drawBehind` lines or a tiled bitmap), then the noise `ShaderBrush(ImageShader(noise, Repeat, Repeat))` at 30% / 42% with `BlendMode.Overlay`, then a vignette `radialGradient(transparent 55% -> #1E2632@14%)` (dark: black@45%) |
| **Plastic** (soft-touch / glossy white) | favourites, brightness, mute, rail cap, keys, tiles, sheets | `#FBFBFC`->`#E6E7EA`, edge `#B4B8BE` | `#474A52`->`#363940`, edge `#101114` | `verticalGradient` fill; noise at ~12% `SoftLight`; inner top line `#FFF@95%` (dark 10%); 1dp bottom edge colour; two shadows: 2dp/3dp blur + 8dp/14dp blur at 16-28% |
| **Rubber** (matte) | D-pad disc and OK cap | `#D3D5D9`->`#C3C6CB`, wall `#9EA2A9` | `#2B2D32`->`#232529`, wall `#0E0F11` | Fill gradient. **Full bevel**: 1.5dp stroke with `verticalGradient(white@90% 0f, clear .35f, clear .7f, black@22% 1f)`. **Side wall**: the same circle drawn 3dp lower in the wall colour, behind the disc. **Contact shadow**: a circle offset 6dp, blurred 6-8dp (`Modifier.blur` on a layer, or `shadow(elevation)` with an ambient colour). No gloss |
| **Metal** (brushed, chamfered) | slider thumbs (scroll, volume, settings sliders) | `#FDFDFE`/`#D4D7DC`/`#BFC3C9`/`#E6E8EB` 4-stop gradient with a hard step at 50% | `#C9CDD3`/`#8C9198`/`#6F747C`/`#A3A8AF` | 4-stop `verticalGradient` (the hard mid step reads as a turned edge); 1px/2px vertical brushed lines at ±8%; 1dp outline `metal-edge`; inner top white@90%; drop shadow. Metal stays light in dark mode on purpose: it's the brightest material |
| **Glass / acrylic** | transport caps (prev, play, next) | frosted white `rgba(255,255,255,.92)`->`rgba(226,232,240,.86)`, ink legend | smoked `rgba(70,78,92,.78)`->`rgba(22,26,32,.92)`, LED legend `#9ED9FF` with glow | Fill gradient. **Specular band**: an overlay `verticalGradient(white@95% 0f, white@35% .38f, clear .40f)` (dark: 28%/8%) with a hard edge at 40%. **Inner glow**: an inner shadow at the bottom, 18dp blur, LED tint at 22% / 35%. 1dp inner rim white@25%. Dark legends get a 6dp LED-tint glow (draw the icon twice, the blurred copy behind) |
| **Well** (recess) | slots, channels, tracks, fields | `#CBCED3` | `#0B0C0E` | Fill, inner shadow at the top (2dp offset, 5-6dp blur, black@26-70%), 1dp bottom highlight |
| **Engraving** | wordmark, SCROLL/VOLUME labels | fill `#C4C7CC` | fill `#4F535B` (labels) / `#1C1D21` (wordmark) | Text drawn three times: dark copy at y-1, light copy at y+1, fill on top (light from the top) |

### Layout and controls
- **Media layout (top to bottom):**
  - Favourites.
  - Brightness pair.
  - Control cluster: **SCROLL slider (left) / D-pad / VOLUME slider (right)**, with a round 48dp mute cap at the D-pad's lower right, beside the volume slider's − end.
  - Transport channel (thumb zone).
  - The separate volume −/+ row is **removed**: the VOLUME slider covers both stepping (tap an end = one step) and rate control (hold and drag = rate by displacement, same deadzone and speed bands as scroll). Volume is no longer at the very bottom, but the slider sits in the lower half within thumb reach.
- **Machined slider (component):**
  - 56x232dp slot (well) with ticks every 14dp on both edges, a centre detent line, and end markers (▲▼ or + −) that step once on tap.
  - A brushed-metal thumb, 36x60, spring-centred.
  - An engraved Unbounded label under the slot (9sp, tracking 2).
  - Thumb glyph: grip lines for scroll, a speaker for volume.
- **Transport:** one family. Prev, play and next are all glass caps in one recessed channel. Play is wider and taller (96dp), not a different material.
- **esc** leaves Media and becomes a default **Escape** macro tile.
- **Brightness legends:** sun 24dp plus an arrow 20dp, stroke 1.75 at ink colour (not muted), so they stay legible at device scale (about 52px at 2.625 px/dp).
- **Menu sheet (confirmed):** Theme (segmented), Manage devices, Haptics, Settings, About Mote.
  - **Settings:** Pointer: speed 1-10 on a metal slider, natural scrolling, right-click = Hold or 2-finger tap. Keyboard: clear modifiers on mode switch, release modifiers after 30 s idle. Screen: keep screen on while connected, extra dim.
  - **Haptics:** strength Off/Light/Standard/Strong; preview chips for Tick/Click/Heavy/Lock/Reject; toggles for key presses, slider detents and connect/disconnect; a note that Android's Touch feedback setting must be on.
  - **About:** version, open-source licences (incl. OFL fonts), source link.
- The device-vs-design gap list for the Compose app is in `docs/design/device-vs-design.md`.

## Revision 5 decisions (2026-11, parity pass)

- **The mockup is the target, and the device must match it.** Every visual in `design/mote-v1` must be reproducible 1:1 in Compose. `docs/design/tokens.json` is the machine-readable source for colours (both themes), radii, shadows, textures and per-material layer stacks. Rules for keeping them in sync:
  - The app reads the token values from `tokens.json` instead of retyping them.
  - When a mockup changes, `mote.css` and `tokens.json` change in the same revision.
  - A mockup effect that Compose can't match is swapped for one it can, keeping the look. The swaps are listed in `tokens.json` under `parity.swaps`: no saturate filter, no color-mix, text-shadow drawn as 3 text passes, inset shadows drawn as clipped blurred strokes, icon glow only on API 31+.
- **Grain on every control (#55).** One shared 128px `grain` bitmap (value noise, seed 7, about 1dp cells, contrast-stretched grey) is tiled with `BlendMode.Overlay` over each material. Alpha per material:

  | Material | Light | Dark |
  |---|---|---|
  | backplate | .22 | .30 |
  | plastic | .16 | .22 |
  | rubber and wells | .24 | .30 |
  | metal | .12 | .16 |
  | glass | .08 | .12 |

  It must be visible at 1x on the phone. Check it by zooming a 1:1 screenshot.
- **Mac brushed metal (#56).**
  - **Backplate:** horizontally brushed aluminium in light (the primary look), graphite/titanium in dark.
  - **Brushed texture:** the `brushed` bitmap (512x128, noise frequency X .004 / Y .9, seed 3) is generated at runtime, so there is no large asset. It's drawn Overlay at .45 light / .60 dark.
  - **Sheen:** an anisotropic highlight from a 90° linear gradient with soft vertical bands (peaks of 38% at 30% across and 18% at 74% across in light; 7% and 4% in dark).
  - **Metal parts** reuse the same texture:
    - Slider thumbs: a 4-stop turned-edge gradient, brushed at .60, plus a single sheen band.
    - D-pad: a new **machined bezel ring** (r 99-104) made from an 8-stop `sweepGradient` (radial brushed sheen) around the rubber disc.
    - Transport channel: explicit dark-top bezel stops.
- **Layer order is fixed for every material:** shadows, body gradient, textures (Overlay), sheen/gloss, inner lines. See `tokens.json` → `materials`.

## Revision 6 decisions (2026-11)

All values below are also in `docs/design/tokens.json` → `components`. Every layout was checked for overlap at 412x915dp (Nothing Phone 2 class).

- **Volume stays a spring-back rate control (#65 supersedes #58).** It is the same component as the SCROLL lever: hold and drag to change volume at a rate proportional to displacement, release to spring back, tap an end to step once. There is no absolute level, fill track or sync indicator.
- **Mute lives in the volume slider's base (#62).** It is a 44dp plastic cap inside the bottom of the volume well, below the − end. Nothing overlaps the D-pad any more.
- **Brightness caps are 64dp tall (#59).**
- **Transport: one height (#67).** Prev, play/pause and next are all 96dp tall on one baseline. Prev and next are 76dp wide; play fills the rest.
- **Favourites Edit is a compact chip (#63):** 32dp visual, 48dp touch target via padding, 16dp icon plus 13sp label. It sits in the 32dp favourites header row, clear of the MOTE header and the strip.
- **Spaces carousel (#60)** replaces the hold-and-slide tile.
  - **Placement:** a full-width row of its own at the top of Favourites, with the caption "Spaces / swipe or tap a desktop". Other favourites follow in their own row; it shows a "+ Add favourite" slot when empty.
  - **Row and cards:** the row is an 84dp well holding 104x68dp plastic cards with an 8dp gap. It snaps start-aligned one card per fling and fades 24dp at the trailing edge. In the sheet the cards are 112dp wide.
  - **Current desktop:** a 2dp accent ring with a 12dp LED glow, an accent dot, and an accent label.
  - **Bluetooth fidelity:** numbered cards ("Desktop N"). Tapping or settling on a card sends ⌃N if the user enabled "Switch to Desktop N" in macOS Keyboard Shortcuts › Mission Control. Otherwise Mote steps with ⌃←/⌃→. A one-time hint card explains how to enable the shortcuts, with Done / Remind me later.
  - **Helper fidelity:** cards show cached thumbnails that the helper captures on each Space visit (needs Screen Recording permission). Spaces never visited show a dashed placeholder, "Desktop N / Not visited yet".
  - **Macros grid:** the Spaces tile opens the same carousel in a bottom sheet (`spaces-carousel` artboard, showing both fidelities).
- **MOTE wordmark is an LED (#61, #64).**
  - **Connected:** lit, with fill `#2F8BE0` in light / `#A8DEFF` in dark and three stacked blue glows (4/12/22dp light, 3/10/24dp dark). It is strong enough to read on both themes, and every Media artboard shows it lit.
  - **Disconnected:** unlit grey deboss.
  - **Connecting/searching:** the unlit mark pulses towards a dim blue (1600ms, ease-in-out, reversing).
  - **Transition:** lit ⇄ unlit is a 400ms fade of colour and glow on `cubic-bezier(0.2,0,0,1)`.
- **The legend is part of the cap (#66).** Every cap's legend (text or glyph) is drawn inside the cap's own graphics layer, so the press transform (translate 1-1.5dp, scale 0.96) and the pressed shading apply to it too.
  - **D-pad OK when pressed:** the cap and its "OK" move down 1.5dp and scale to 0.96, the bevel inverts (dark top, light bottom), the side wall shrinks to 1dp, and the legend dims to `onSurfaceMuted`.
  - The `dpad-press` artboard shows rest vs pressed. Tokens: `components.cap.press` and `components.dpad.press` (`legendFollowsCap: true`).
- **Grain:** this revision keeps the halved grain and brushed alphas (plate .11/.15, plastic .08/.11, rubber .12/.15, metal .06/.08, glass .04/.06; brushed .32/.42) merged from the parallel tuning pass.

- **Grain halved (0.3.1, on-device feedback "too much grain").** Grain alpha is half the table above in both themes (plate .11/.15, plastic .08/.11, rubber and wells .12/.15, metal .06/.08, glass .04/.06), and brushed alpha is about 30% lower (plate .32/.42, metal .42/.42). `tokens.json` holds the live values.
