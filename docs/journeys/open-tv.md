# open-tv: 10. Open on Mac and TV mode

Artboard: `design/mote-v1/journey-open-tv.html` (Preview (Labs)). Code: `android/.../labs/open/OpenUi.kt`, `OpenTv.kt`, `OpenUrl.kt`, `Sites.kt`, `labs/Labs.kt`, `labs/LabsUi.kt`, `ui/App.kt:165,259,276,313`, `MainActivity.kt:29`, `AndroidManifest.xml:62-74` (ShareTarget alias), `net/WsTransport.kt:121-122,282`; helper `Labs/TvMode.swift`, `Hub.swift:97-106,249`, `MenuBar.swift:144-146,185-186`, `Injector.swift` (DRY lines). Docs: `docs/labs/open-tv.md`, `docs/design/design-language.md` (User journeys).
Test: `e2e/journeys` → `dev.mote.remote.journeys.OpenTvJourney` (one @Test per path below).

## Preconditions
- P1. App: fresh data, connected to the dry-run helper over Wi-Fi by the `wifi-helper` main path (FindMacScreen "Connect to <Mac>" with `TestSeam.fakeProximity`); next-0.6 keeps setup on the Bluetooth offer, so the precondition taps "Skip" until the pill `Device: <Mac> via Wi-Fi. Tap to choose` shows Debug build `dev.mote.remote.dev`; `android.permission.BLUETOOTH_CONNECT` / Nearby devices not required for this journey. Settings › Labs › switch contentDescription `Open & TV mode (preview)` = On (DataStore `labs`, key `open_tv`). Open… store `labs_open` empty (default pins, no recents). Mode rail on Media (default `Mode.MEDIA`, `App.kt:141`).
- P2. Phone: Wi-Fi on ("AndroidWifi"), Bluetooth irrelevant, no Tailscale VPN.
- P3. Mac / helper: `MoteHelper --dry-run --headless --no-ble --memory-store --port N --stdin-control --labs-tv` (`--labs-tv` = Labs › TV mode (preview) forced on; `TvMode.swift:110`). Not paused. Clipboard of the emulator holds whatever the step says.
- P4. Network: same LAN (phone 10.0.2.17 → helper 10.0.2.2:N).

## Main path `open-tv`
| # | Actor | Action (exact) | Expected observable (exact) | Seam |
|---|---|---|---|---|
| S1 | system | (app linked, P1) | Top bar shows a 44 dp box, contentDescription "Open on Mac" (search icon) between the wordmark and "Menu"; phone sends `{"t":"labs.tv.get"}`, helper replies `{"t":"labs.tv.state","enabled":true,"on":false,"page":"home"}` | real (`OpenUi.kt:85,95-101`, `Hub.swift:249`) |
| S2 | phone user | Tap "Open on Mac" | Bottom sheet: title "Open on Mac", badge "PREVIEW", field placeholder "Search YouTube or paste a link" (field contentDescription same), button "Paste", hint "Links open on the Mac; YouTube opens in TV mode. Tap the keyboard mic to speak.", section "QUICK ACTIONS" (SectionHead renders uppercase) with caps "Close this tab", "Exit fullscreen", "Pause everything", cap "Open TV mode on the Mac" (contentDescription "Open TV mode"), section "PINNED" with "Edit" and six caps contentDescription "Open YouTube", "Open Netflix", "Open Prime Video", "Open Disney+", "Open Twitch", "Open Spotify"; no "RECENT" section. No test tags. | real |
| S3 | phone user | Type `lofi beats` into the field | "Paste" is replaced by primary cap "Open" | real |
| S4 | phone user | Tap "Open" (or IME Go) | Sheet closes. Phone sends `{"t":"labs.open.url","url":"https://www.youtube.com/results?search_query=lofi%20beats","mode":"tv"}`. Helper stderr `[mote] DRY labs.tv open page=youtube url=https://www.youtube.com/tv#/search?q=lofi%20beats`; every authed session gets `{"t":"labs.tv.state","enabled":true,"on":true,"page":"youtube","url":"https://www.youtube.com/tv#/search?q=lofi%20beats"}` | real (dry-run: no window, `TvMode.swift:236-239`) |
| S5 | system | (state arrives) | Media is replaced by TvRemotePanel: "TV MODE", "PREVIEW", "Exit"; subtitle "YouTube"; contentDescription "TV direction pad" with caps "Up", "Down", "Left", "Right", "OK"; caps "Back", "Home", "Search"; "Volume down", "Play or pause", "Volume up" | real |
| S6 | phone user | Tap "Down", then "OK" | Helper `[mote] DRY labs.tv key down`, `[mote] DRY labs.tv key ok` | real |
| S7 | phone user | Tap "Back"; tap "Play or pause" | `[mote] DRY labs.tv key back`; `[mote] DRY labs.tv key playpause` | real |
| S8 | phone user | Tap "Volume up" (short tap) | `[mote] DRY consumer volup` (Remote.consumer, not a TV key) | real |
| S9 | phone user | Tap "Home" | `[mote] DRY labs.tv key home`. In dry run the state is NOT changed (page stays `youtube`, subtitle stays "YouTube"); real helper would open the launcher (page `home`) | real (dry-run divergence, see open-tv-D5) |
| S10 | Mac (helper) | — | Real-mode launcher "MOTE", "TV mode · Preview", footer "Arrows move · OK opens · Back on YouTube goes back · Exit TV mode from the phone or the helper menu" | manual: needs a real WKWebView window (not headless) |
| S11 | phone user | Tap "Exit" | Phone sends `{"t":"labs.tv.exit"}`; helper `[mote] DRY labs.tv exit`; state `{"t":"labs.tv.state","enabled":true,"on":false,"page":"home"}`; Media returns (normal MediaScreen) | real |

## Postconditions
- Phone: `labs_open` recents = [`YouTube: lofi beats` → `https://www.youtube.com/results?search_query=lofi%20beats`]; pins unchanged (defaults). `OpenTv.state.on == false`.
- Helper: TV mode off; no trusted-list change.

## Branches
### `open-tv.pin-browser`: open a non-YouTube pin (from S2; not drawn)
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | phone user | Tap "Open Netflix" | Sheet closes; `{"t":"labs.open.url","url":"https://www.netflix.com/browse","mode":"browser","fullscreen":true}`; helper `[mote] DRY labs.open.url mode=browser fullscreen=true url=https://www.netflix.com/browse`; TV state unchanged (no panel) | real |
Postconditions: recents first entry "Netflix" / `https://www.netflix.com/browse`.

### `open-tv.pin-youtube`: YouTube pin (from S2; artboard j10-s1 pin)
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | phone user | Tap "Open YouTube" | `{"t":"labs.open.url","url":"https://www.youtube.com/tv","mode":"tv"}`; `[mote] DRY labs.tv open page=youtube url=https://www.youtube.com/tv`; TvRemotePanel subtitle "YouTube" | real |

### `open-tv.tv-launcher`: Open TV mode on the Mac (from S2; artboard j10-s4)
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | phone user | Tap "Open TV mode" | Sheet closes; `{"t":"labs.tv.open","pins":[6 default {title,url}]}`; `[mote] DRY labs.tv open page=home url=home`; TvRemotePanel subtitle "Home" | real |
| B2 | Mac (helper) | — | Launcher tiles; a tile for Netflix/Prime/Disney+/Twitch/Spotify opens the default browser fullscreen, YouTube opens leanback | manual: real WKWebView + JS message handler, not in dry run |

### `open-tv.paste-link`: Paste a link (from S2)
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | system | Set emulator clipboard to `Watch this: https://youtu.be/dQw4w9WgXcQ.` | — | real (adb `cmd clipboard` / test ClipboardManager) |
| B2 | phone user | Tap "Paste" | Field = `https://youtu.be/dQw4w9WgXcQ` (OpenUrl.fromShare strips text and trailing `.`); "Open" shown | real |
| B3 | phone user | Tap "Open" | `{"t":"labs.open.url","url":"https://youtu.be/dQw4w9WgXcQ","mode":"tv"}`; `[mote] DRY labs.tv open page=youtube url=https://www.youtube.com/tv#/watch?v=dQw4w9WgXcQ` | real |

### `open-tv.share`: Share a link from another app (from S1; artboard j10-s5)
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | system | `adb shell am start -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT "https://www.youtube.com/watch?v=dQw4w9WgXcQ" -n dev.mote.remote.dev/dev.mote.remote.labs.open.ShareTarget` | Share chooser label of the alias is "Open on Mac" (`labs_share_label`); activity comes to front | faked: `am start` stands in for another app's share sheet (chooser UI is system, UNDEFINED) |
| B2 | system | — | Open on Mac sheet opens by itself with the field pre-filled `https://www.youtube.com/watch?v=dQw4w9WgXcQ`, "Open" shown; nothing sent to the helper (no DRY line) | real |
| B3 | phone user | Tap "Open" | `[mote] DRY labs.tv open page=youtube url=https://www.youtube.com/tv#/watch?v=dQw4w9WgXcQ`; recents first entry title "youtube.com" | real |

### `open-tv.share-dismiss`: dismiss a pre-filled share (from share B2)
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | phone user | Tap "Close" | Sheet closes; no message sent; reopening via "Open on Mac" shows an empty field | real |

### `open-tv.share-target-off`: Labs flag off hides the share target (not drawn)
Preconditions delta: `open_tv` = Off.
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | system | Labs flag off at launch (`rememberOpenTv` → `OpenTv.syncShareTarget(false)`) | Alias `ShareTarget` is COMPONENT_ENABLED_STATE_DISABLED and no SEND `text/plain` activity of the app resolves (the system share sheet can't offer "Open on Mac"); top bar has no "Open on Mac". A shell `am start -n …ShareTarget` still prints "Starting: …" on API 35 and opens no sheet | real (whether `am start` launches the disabled alias is UNDEFINED) |

### `open-tv.recents`: reopen from Recent, clear (from postconditions of main path)
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | phone user | Tap "Open on Mac" | Section "RECENT" with "Clear" (clickLabel "Clear recents") and row clickLabel "Open YouTube: lofi beats", subtitle "youtube.com" | real |
| B2 | phone user | Tap the row | Same `labs.open.url` as S4 but `mode` is `tv` via modeFor → `[mote] DRY labs.tv open page=youtube url=https://www.youtube.com/tv#/search?q=lofi%20beats` | real |
| B3 | phone user | Reopen, tap "Clear" | "RECENT" section disappears | real |

### `open-tv.pins-edit`: edit pinned sites (from S2)
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | phone user | Tap "Edit" | Label becomes "Done"; caps contentDescription "Remove YouTube" … "Remove Spotify"; a seventh cell contentDescription "Pin the link in the field", text "Pin a link" | real |
| B2 | phone user | Tap "Pin the link in the field" with an empty field | Red text "Type or paste a link above, then tap + to pin it." | real |
| B3 | phone user | Type `example.com/x`, tap the + cell (text "Pin example.com") | Pin "example.com" added; field cleared | real |
| B4 | phone user | Tap "Remove Spotify", tap "Done" | Spotify pin gone; caps back to "Open …" | real |

### `open-tv.bad-scheme`: refused link (from S3)
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | phone user | Type `javascript:alert(1)`, tap "Open" | Red text "Only http and https links can be opened."; sheet stays; nothing sent | real |

### `open-tv.quick-actions`: quick actions over Wi-Fi (from S2; artboard j10-s1)
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | phone user | Tap "Close this tab" | `{"t":"labs.open.action","a":"closeTab"}`; `[mote] DRY labs.open.action a=closeTab tv=false`; sheet stays open | real |
| B2 | phone user | Tap "Exit fullscreen" | `[mote] DRY labs.open.action a=exitFullscreen tv=false` | real |
| B3 | phone user | Tap "Pause everything" | `[mote] DRY labs.open.action a=pauseAll tv=false` | real |

### `open-tv.quick-closes-tv`: Close this tab while TV mode is up (from S5)
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | phone user | Tap "Search" (opens the sheet, see search branch), tap "Close this tab" | `[mote] DRY labs.open.action a=closeTab tv=true`, then `[mote] DRY labs.tv exit`; TvRemotePanel replaced by Media | real (dry run counts as frontmost, `TvMode.swift:117`) |

### `open-tv.tv-search`: Search on the TV remote (from S5; artboard j10-s3)
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | phone user | Tap "Search" | Open on Mac sheet opens over the TV panel; NO `labs.tv.key search` is sent (no DRY line) | real (doc says `search` = leanback search, see open-tv-D4) |

### `open-tv.exit-from-mac`: Labs › Exit TV mode in the helper menu (from S5; artboard j10-s4 caption)
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | Mac user | Menu bar › Labs › "Exit TV mode" | helper `[mote] DRY labs.tv exit`; state `on:false`; phone returns to Media | faked: needs new stdin-control command `tv-exit` (menu is headless-unobservable) |

### `open-tv.lab-off-mid-tv`: helper TV lab turned off while TV is up (from S5)
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | Mac user | Menu bar › Labs › "TV mode (preview)" (uncheck) | helper `[mote] labs: TV mode disabled`, `[mote] DRY labs.tv exit`; state `enabled:false,on:false`; phone returns to Media | faked: needs new stdin-control command `tv-lab-off` (`--labs-tv` only forces on) |

### `open-tv.helper-lab-off`: helper TV lab off (from S4; not drawn)
Preconditions delta: helper without `--labs-tv`.
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | phone user | Type `lofi beats`, tap "Open" | Sheet closes (send succeeded locally); helper replies `{"t":"err","code":"disabled","msg":"Turn on Labs › TV mode in the Mote Helper menu","ref":"labs.open.url"}`; NO error copy is visible anywhere: `OpenTv.error` = "Turn on Labs › TV mode in the Mote Helper menu on the Mac." but the sheet is closed and reopening clears it | real (see open-tv-D1) |

### `open-tv.paused`: input paused on the Mac (from S5)
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | system | helper stdin `pause` | helper `[mote] input paused` | real |
| B2 | phone user | Tap "Up" on the TV pad | `err` `paused` (ref `labs.tv.key`); TvRemotePanel shows red "Input is paused on the Mac." under the subtitle | real |
| B3 | system | helper stdin `resume`, tap "Up" | `[mote] input resumed`; `[mote] DRY labs.tv key up`; red text cleared (next successful send) | real |
Note: the same `paused` error from the sheet (S4) is invisible (open-tv-D1).

### `open-tv.tv-off`: key after TV closed on the Mac (from S5)
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | system | Close TV on the helper without the phone receiving the state | phone red "TV mode is closed on the Mac." on next key | faked: needs a way to drop the pushed `labs.tv.state` (unreachable in normal flow; state is pushed to every session) |

### `open-tv.ax`: no Accessibility (from S4)
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | phone user | Tap a TV key | red "Mote Helper needs Accessibility permission on the Mac." | faked: needs new flag `--fake-no-ax` (dry run treats input as ready, `Hub.swift:61`) |

### `open-tv.old-helper`: helper without the preview (from S1)
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | system | helper answers `labs.tv.get` with `err` `unknown` | `OpenTv.error` = "This Mote Helper has no Open & TV mode yet. Update it." (cleared when the sheet opens, so never seen in the sheet) | faked: needs an old helper build or a stub WebSocket server |

### `open-tv.bad-link`: helper refuses a link (not reachable)
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | system | helper `err` `bad` for `labs.open.url` | "The Mac refused that link: <msg>" | UNDEFINED: the phone's `OpenUrl.normalize` refuses everything the helper's `LabsURL.validate` refuses, so no UI input reaches it; and it would be invisible (open-tv-D1) |

### `open-tv.bluetooth`: Bluetooth HID link, no helper (from S2; not drawn)
Preconditions delta: active transport is Bluetooth HID (`TestSeam.transport` = FakeTransport with HID caps, linked).
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | phone user | Tap "Open on Mac" | Hint "Opening links needs Mote Helper over Wi-Fi or Tailscale. Quick actions work over Bluetooth too."; no "Open TV mode on the Mac" cap | faked: TestSeam.transport (HID caps) |
| B2 | phone user | Type `lofi beats`, tap "Open" | Red "Open & TV mode needs Mote Helper over Wi-Fi or Tailscale."; sheet stays | faked: same |
| B3 | phone user | Tap "Close this tab" / "Exit fullscreen" / "Pause everything" | FakeTransport records ⌘W tap / Esc tap / consumer PLAY_PAUSE (`OpenTv.kt:100-107`) | faked: same |

### `open-tv.unlinked`: not linked (from S2)
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | phone user | Stop the helper, wait until unlinked, open the sheet | Hint "Links open on the Mac; YouTube opens in TV mode. Tap the keyboard mic to speak." (caps stay HELPER while the socket reconnects); quick actions, "Open TV mode on the Mac" and pin caps shown but disabled (`needsLink`); "Paste" enabled | real (stop the helper) |

## Disagreements → DEFECTS.md
- `open-tv-D1` (S4, helper-lab-off, paused): helper errors for sheet actions are never shown (sheet closes on local send success; reopening clears the error).
- `open-tv-D2` (share B2): artboard shows the pre-filled field without scheme (`youtube.com/watch?v=…`); code always pre-fills a normalised `https://…` URL.
- `open-tv-D3` (tv-launcher B2): artboard/doc say Netflix, Prime Video, Disney+ open in the browser; code sends every non-YouTube launcher tile (incl. Twitch, Spotify) to the browser.
- `open-tv-D4` (tv-search): doc/protocol table: phone `search` = leanback search; phone's Search opens the Open… sheet and never sends `labs.tv.key search`.
- `open-tv-D5` (S9): dry run logs `home`/`search` keys without updating TV state, so dry-run tests can't observe the launcher/search page change the doc says "keeps the TV state".
- `open-tv-D6` (share): a share arriving while the sheet is already open is silently dropped (field `remember`ed once).
- `open-tv-D7` (TvState doc): Android `TvState.page` KDoc and doc list `youtube-desktop`; fine, but helper `page` comment (`TvMode.swift:91`) omits it. (S4)

## UNDEFINED
- S10/B2 (launcher): real TV window, focus, leanback rendering and DRM behaviour need a real non-headless helper (forbidden here).
- share B1: Android share chooser copy/layout is system UI.
- Exact `am start` failure text when the alias is disabled.
- Volume caps with `Repeat()`: how many `DRY consumer volup` lines a tap produces depends on press duration (only the first `onDown` is guaranteed).
