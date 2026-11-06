# Device vs design (Mote 0.1.2 on Pixel, 2026-11-06)

**Sources:**
- **Device:** screenshots from adb serial b7e39c2f (1080x2412, about 2.625 px/dp), in `docs/design/device-shots/` (originals in `/tmp/mote-device/`). Files are `<screen>-light.png` and `<screen>-dark.png` for media, mouse, keys, macros and menu.
- **Design:** `design/mote-v1/` revision 4. Light is `media-light`, dark is `media`. Tokens are in `mote.css`; rules are in `design-language.md`, Revision 1-4 decisions.
- **Capture method:** only rail and ⋯ menu taps. The theme was switched to Dark for the dark shots and then restored to Light.

The device build matches roughly revision 2 of the design. Rows marked ★ were flagged by the user.

## Global / material

| Element | Device now | Design (rev 4) | Fix |
|---|---|---|---|
| Backplate ★#47 #54 | Flat vertical gradient. No visible grain, brushed lines or vignette, especially in dark | Gradient, plus 1px/3px brushed hairline at 2%, plus a noise bitmap at 30% (light) / 42% (dark) in overlay blend, plus a radial vignette (edges +14% / +45% dark) | Add a tiled noise `ImageBitmap` + `ShaderBrush`, a hairline `drawBehind`, and a radial-gradient vignette layer |
| Tonal separation (dark) ★#47 | Caps, backplate and wells are nearly the same grey | Backplate `#26282D`->`#17181B`, well `#0B0C0E`, plastic cap `#474A52`->`#363940`, rubber `#2B2D32`->`#232529`, metal and glass distinct | Update dark tokens |
| Cap grain | None | Noise blended soft-light into caps and sheets | Draw the same noise bitmap at about 12% over cap fills |
| Wordmark | Matches (debossed caps) | Unbounded Black 19sp, tracking 2.5 | OK; check the font is bundled (looks right) |
| Device pill | Matches; real host name shown | Same | OK |
| Mode rail | Matches | Same | OK |

## Media

| Element | Device now | Design (rev 4) | Fix |
|---|---|---|---|
| Favourites | Spaces, Spotlight, Lock | Spaces (2 columns) plus a dashed "+ Add favourite" slot (2 columns) | Default favourites = [Spaces] |
| Favourites Edit | Small "EDIT" text link | Raised cap, 40dp tall, 48dp hit area, edit icon + 14sp "Edit" | Replace with the cap |
| esc ★#49 | Cap at the D-pad's lower left | Removed from Media; becomes the default Macros tile "Escape" | Remove; add the macro |
| Brightness ★#52 | Sun icons only, no arrows | Sun 24dp + arrow 20dp (stroke 1.75, ink colour), ↓ on dim, ↑ on bright, in one centred row | Add arrow vectors beside the suns |
| D-pad ★#54 | Flat disc with only a top inner shadow; thin ring housing | Matte rubber disc `rub-top`->`rub-bot`. Full-circle bevel stroke (white 90% at top -> clear by 35%, black 22% at bottom). 3dp side wall (`rub-wall` offset down). Blurred contact shadow (6dp blur, offset 6dp). Same treatment on the OK cap. Housing is a recessed well | Rebuild in `drawBehind` as layers (see doc recipes) |
| Scroll lever ★#50 | Right of the D-pad, no markings | **Left** of the D-pad. 56x232 machined slot. Tick marks every 14dp on both edges plus a centre line. ▲/▼ ends that step one notch on tap. Brushed-metal thumb. "SCROLL" engraved below | Move and add markings |
| Volume ★#50 | Bottom row: − / mute / + | **Vertical VOLUME slider right of the D-pad**, same component as scroll. + top / − bottom (tap = one step); drag = rate by displacement. Speaker glyph engraved on the metal thumb. "VOLUME" engraved below. The bottom −/+ row is **removed** | Replace the row with the slider |
| Mute ★#48 | Middle of the volume row | Round 48dp plastic cap at the D-pad's lower right, next to the volume slider's − end | Move |
| Transport ★#51 | Prev/next are white plastic caps; play is a dark anodised cap in its own bezel (two styles) | One recessed channel holding three acrylic/glass caps: prev 72x72, play (fills the middle) x96, next 72x72. Same material: gloss band, inner LED glow at the bottom, 1px top specular. Dark: smoked acrylic with LED-lit legends | Unify into one `GlassCap` composable |

## Menu ★#53

| Element | Device now | Design | Fix |
|---|---|---|---|
| Menu sheet | Theme segmented control + "Manage devices" button + version line | Theme (segmented) / Manage devices / Haptics / Settings / About Mote, as rows with chevrons | Add the rows |
| Manage devices | Exists (device screenshot pending) | `device-picker` artboard: selected device raised, others flat with a hairline | Check the selection language |
| Settings | Missing | `settings` artboard. Pointer: speed slider 1-10, natural scrolling, right-click = Hold or 2-finger tap. Keyboard: clear modifiers on mode switch, release modifiers after 30 s idle. Screen: keep screen on while connected, extra dim | Build |
| Haptics | Missing | `haptics` artboard: strength Off/Light/Standard/Strong, preview chips (Tick/Click/Heavy/Lock/Reject), toggles for key presses / slider detents / connect-disconnect, and a note about Android's touch-feedback setting | Build |
| About | Version line only | Row leading to version, licences (OFL fonts), source link | Build (simple list) |

## Keys / Mouse / Macros

| Element | Device now | Design | Fix |
|---|---|---|---|
| Keys: text field | "Type text to send…" + Send at the top | Not in the design | Keep it (useful). The design will add it next revision; place it above the F-keys |
| Keys: chord strip | Static instruction text | Live preview: modifier chips + dashed slot + "Clear" | Implement the preview |
| Keys: fn | Plain text, no cap | Disabled cap at 38% | Use the cap |
| Mouse: strip | L / Drag lock / R as text | Left / lock icon / Right | Use the lock icon; show the drag-lock on-state as an LED ring |
| Mouse: pad overlays | No hints, no last-gesture chip | First-run hints and a "Click" chip | Optional |
| Macros: header | "MACROS" caps + EDIT text | "Macros" headline + raised Edit cap + hint line | Match |
| Macros: defaults | Spaces, Spotlight★, Lock★, Mission Control, Screenshot | Spaces★, Spotlight, Escape, Mission Control, Screenshot, Open Safari, Lock (confirm), Force quit, Hide others | Update the defaults; only Spaces starred |
| Macros: Mission Control icon | "A in a box" (apps glyph) | Four-window grid | Swap the icon |
| Macros: tint marker | None | 24x3dp coloured stripe on the tile's top edge | Add |
