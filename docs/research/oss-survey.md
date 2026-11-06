# OSS survey: Android -> macOS Bluetooth remote

Date: 2026-11-06. Method: GitHub API/search, shallow clones of the top candidates (read descriptors, enums, READMEs, changelogs), GitLab API, web search. Star counts and push dates are as of today. Items marked UNVERIFIED were not confirmed by opening code.

Requirements: media (play/pause, next/prev, vol +/-, mute), brightness +/-, D-pad, touchpad mouse, arbitrary keyboard incl. modifiers, macros/hotkeys, modern UI.

## Approach summary

- (a) Phone as BT HID device (`BluetoothHidDevice`, API 28+): no Mac software, pairs as a normal keyboard+mouse. Many small OSS projects.
- (b) Companion server on Mac: can do true brightness/lock/sleep/macros, but needs install, a network, and permissions. Few mature OSS options.

## Comparison table

| # | Project | URL | License | Last push / stars | Approach | Stack | Media | Brightness | D-pad | Touchpad | Full kbd + mods | Macros/hotkeys | UI |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 | **Linkpad** | github.com/Devdas-gupta/linkpad | MIT | 2026-07-02 / 9 | HID | Kotlin + Compose (verified) | Yes (play, next/prev, FF/RW, vol, mute) | **Yes**, Consumer 0x6F/0x70 sent (verified in `ConsumerUsage`) | Yes (TV tab D-pad ring) | Yes (1-finger, 2-finger scroll incl. horizontal, tap-drag, edge strip) | Yes; Cmd/Ctrl/Alt/Shift, F-keys, nav cluster | Yes: user-defined shortcuts, per-OS (Mac/Win) | Custom dark "glassmorphic" theme, not M3; on Play Store; screenshots in README |
| 2 | **GhostHID** | github.com/ShubhamJ010/ghost-hid | MIT | 2026-11-04 / 8 (1 commit in history, so likely AI-assisted bulk drop) | HID | Kotlin + Compose, **Material 3 Expressive** (claimed, README + ADRs) | Yes (Consumer report ID 4: play, vol, mute, nav) | No named brightness; raw-hex injection could send 0x6F/0x70 (UNVERIFIED) | Not a dedicated D-pad (arrows via keyboard) | Yes (tap, 2-finger right click, edge scroll, middle click) | Yes + Combo Studio, mod ordering | **Yes: macro sequence parser** (`{mod+s}{500ms}text{Enter}`), landscape stream deck, snippets, ADB CLI | Best-looking on paper; has docs/ADRs/architecture.md |
| 3 | **android-bt-remote** (jqssun) | github.com/jqssun/android-bt-remote | GPL-3.0 | 2026-06-20 / 130 | HID | Kotlin (repo is a build wrapper; real source = submodule Atharok/BtRemote on GitLab, patched) | Yes | Not found (UNVERIFIED; grep of repo found none) | Yes (derived from Android TV remote app) | Yes (trackpad) | Yes | No | Play Store + F-Droid; demo video; most-used HID option. Explicitly lists macOS as a host |
| 4 | Atharok **BtRemote** (upstream) | gitlab.com/Atharok/BtRemote | not retrieved (GPL implied by downstream) | 2026-11-02 / 148 stars | HID | 100% Kotlin | Yes | UNVERIFIED | Yes (TV focus) | Yes | Yes | No | Android-TV oriented UI |
| 5 | **Kontroller** | github.com/raghavk92/Kontroller | Apache-2.0 | 2025-01-16 / 258 (stale 22 mo) | HID | Kotlin (not Compose) | Partial (`DescriptorCollection.kt` has consumer entries) | No | No | Yes | Yes | No | Dated UI. Worth reading `DescriptorCollection.kt` |
| 6 | **GhostBoard** | github.com/ToxicOrca/ghostboard-android-bluetooth-mouse-keyboard | MIT | 2026-09-26 / 13 | HID | Kotlin | No (no media keys listed) | No | Arrows only | Yes | Yes, sticky modifiers (Ctrl/Alt/Win; "Win" not Cmd) | No | Dark only; targets Windows |
| 7 | **PhonePad** | github.com/Tygb99/phonepad | GPL-3.0 | 2026-10-30 / 10 | HID | Kotlin, API 36, has HID docs (docs/HID.md) | No (out of scope) | No | No | Yes + 3-finger gestures mapped to macOS shortcuts | Limited | No | Korean-first; explicitly targets macOS+Windows; clean scoping docs |
| 8 | NightBoard68 | github.com/elysia395/NightBoard68 | MIT | 2026-09-22 / 27 | HID + LAN mode | Kotlin | n/a | n/a | n/a | Yes | 68-key keyboard w/ sticky mods | No | Chinese-language, keyboard-focused |
| 9 | DeckLink | github.com/carrotunderscore/DeckLink | not stated | 2026-10-25 / 0 | HID | Kotlin | Seek/pause only | No | Yes (D-pad + A/B) | Yes | Basic QWERTY | No | Tied to one Windows app, Swedish |
| 10 | Mousedroid | github.com/darusc/Mousedroid | MIT | 2026-03-13 / 411 | Companion (BT/WiFi/USB) | Kotlin + Win/Linux server | No | No | No | Yes | Yes | No | **No macOS server** (README: Windows/Linux only). Reject |
| 11 | **macremote** | github.com/tejasnafde/macremote | MIT | 2026-10-22 / 0 | Companion (HTTP over Tailscale/LAN, FastAPI + Hammerspoon on Mac) | Kotlin + Compose app + widget | Yes (media keys, vol slider, now-playing) | **Yes, real** (Hammerspoon) | No | No | No | No (sleep timer, lock, sleep) | Closest to "Mac-side power" idea, but no HID input. Network + Hammerspoon dependency |
| 12 | **KDE Connect** (Android) + **Soduto** (Mac) | github.com/KDE/kdeconnect-android ; github.com/soduto/Soduto | GPL-2.0 / GPL-3.0 | 2026-11-06 (very active) / 1499 ; Soduto 2026-02-27 / 430 | Companion (Wi-Fi) | Android: Kotlin/Java, Material; Soduto: Swift | Via MPRIS-style plugin (UNVERIFIED on Mac) | No | No | Yes (remote input plugin; Soduto support UNVERIFIED in detail) | Basic remote keyboard | No | Android app is solid; Mac side is a lagging third-party port; Wi-Fi only, no BT |
| - | Unified Remote | unifiedremote.com | **Proprietary** | n/a | Companion (BT/WiFi) | n/a | Yes | Yes | Yes | Yes | Yes | Yes | Closest feature match but not OSS, not forkable. Listed for completeness |
| - | Others seen, rejected | Fabi019/hid-barcode-scanner (GPL, barcode only), LiangLuDev/HidPeripheral (2022, no license), meromelo/Kontroller (2019 sample), 04Alek04/BLE_HID_EXAMPLE (Java, HID-over-GATT sample), akshit-bansal11/edgepad (HTML/web, 36 stars, MIT), Abdk4Moura/relay-hid, Sagarverse/Rabit-Pro | | | | | | | | | | | |

## Brightness over HID on macOS: evidence

- Linkpad sends Consumer usages 0x6F (Display Brightness Increment) and 0x70 (Decrement) inside a 12-bit-usage Consumer Control report (Report ID 3), and it markets "brightness" as a feature. Its own `HidDescriptors.kt` says the descriptor matches "the commercial APK byte-for-byte". So there is a shipping app that relies on this, but I found **no test log proving it works on macOS** in the repo.
- Web search: one Keyboardio user reported Consumer 0x6F/0x70 working on macOS Catalina (anecdotal, forum). Nordic DevZone threads show macOS pickiness on consumer reports: each report needs an explicit Report ID, and some generic BLE keyboards had consumer keys ignored. No Apple doc found. Microsoft documents 0x6F/0x70 for Windows (mobile/battery systems only), which is irrelevant to Mac.
- Apple's own keyboards use an Apple vendor-specific path for brightness, so generic-HID brightness is not guaranteed on every Mac model/macOS version.
- **Conclusion: treat as "probably works on built-in displays, must be tested on the user's Mac."** It will not control external monitors. Fallback if it fails: HID keyboard F1/F2 (usage 0x3A/0x3B) only works if macOS "Use F1, F2 as standard function keys" is off, or a tiny Mac helper (companion approach, e.g. macremote's Hammerspoon route). Recommend a 30-minute spike with Linkpad's APK before committing.

## Quality notes on the HID candidates

- Descriptor pattern shared by Linkpad and GhostHID: one composite descriptor with Keyboard (ID 1), Mouse (ID 2), Consumer (ID 3 or 4), optional System Control. Linkpad's mouse report includes AC Pan (horizontal scroll). Separate Report IDs per function are what macOS needs.
- Android caveat common to all HID apps: some OEMs disable the HID Device profile (Kontroller README mentions OnePlus). macOS requires removing old pairings and pairing from the Mac side, per all READMEs.
- Linkpad and GhostHID are young (2026), small star counts, likely AI-assisted (GhostHID has a single commit and `docs/prompts/`). Code quality needs review before trusting; none has a test-on-Mac matrix.
- No project covers the full list in one place. Closest: Linkpad (everything except real macros) and GhostHID (macros and best UI, no brightness constant, no D-pad).

## Verdict

**Build new (thin), borrowing from Linkpad and GhostHID. Do not adopt any project as-is.**

Reasoning:
- Nothing ships all of: brightness + D-pad + touchpad + full keyboard + macros + good UI. Linkpad is ~80% functionally, but its UI is a bespoke theme (not M3), and it is a 9-star MIT project with a fast-moving single author. GhostHID has the best UI and macro engine but 1 commit and no brightness/D-pad.
- Cost of building the HID layer ourselves is low: `BluetoothHidDevice` registration plus a ~100-line descriptor is a solved problem, and both MIT-licensed projects give us a verified working reference. The expensive parts (UX, macros, profiles) are where our requirements differ anyway.
- Fork alternative: **fork Linkpad (MIT)** if speed matters most. It already has the full descriptor, brightness enum, D-pad, touchpad gestures, per-OS shortcut profiles, Compose. Cost: restyle to M3, add a macro engine. This is the cheapest path to a working app, but we inherit someone else's architecture. Pick this if, after reading its ViewModel/service code, structure is reasonable (not yet reviewed in depth: it is 85 files).
- Recommended plan:
  1. Spike (hour-scale): install Linkpad APK / build it, pair with the Mac, test brightness, mute, volume, D-pad arrows, modifier combos. This de-risks the single biggest unknown.
  2. If brightness works: build new Compose M3 app, copy Linkpad's `HidDescriptors.kt` (descriptor + `ConsumerUsage` + key enum) and GhostHID's macro parser grammar (`{mod+s}{500ms}text{Enter}`) as design inspiration. Both are MIT, so copying is allowed with attribution.
  3. If brightness fails: add an optional Mac companion later (macremote's design: tiny HTTP service + Hammerspoon/`brightness` CLI) only for brightness/lock/sleep; keep HID for everything else.
- Avoid: GPL projects (android-bt-remote, PhonePad, KDE Connect/Soduto) as code sources unless we accept GPL; use them only as behavior references. Mousedroid has no Mac support. Unified Remote is not OSS.

## Worth borrowing (priority order)

1. Linkpad `app/src/main/java/com/btremote/app/bluetooth/HidDescriptors.kt` (MIT): composite descriptor, `ConsumerUsage` codes incl. brightness, `HidKey` table, touchpad gesture logic, per-OS shortcut model.
2. GhostHID (MIT): macro parser grammar and sequencing, landscape deck layout, Material 3 Expressive usage, `docs/protocol-spec.md` and ADR-008 on composite descriptor design.
3. Kontroller `DescriptorCollection.kt` (Apache-2.0): alternative descriptors for cross-checking.
4. PhonePad `docs/HID.md` and `DRAG_MODE.md` (GPL): read for macOS/Windows pairing gotchas and drag-mode design; do not copy code.
5. macremote (MIT): reference only if a companion fallback is needed.

## Gaps / not verified

- No on-device test of any app against macOS; brightness claim is evidence-based only.
- Did not review code quality or UI screenshots visually (only READMEs/changelogs). Reddit/HN/alternativeto/F-Droid were not queried directly; discovery was via GitHub search plus README badges (Linkpad: Play Store; android-bt-remote: Play + F-Droid).
- Soduto's remote-input/media support and its current macOS compatibility not confirmed.
- BtRemote upstream license and brightness support not confirmed.
