# smart-remote: 9. Smart remote

Artboard: `design/mote-v1/journey-smart-remote.html` (Preview (Labs)). Code: `android/.../labs/context/{SmartRemote,SmartPanel,Rules,NowPlaying}.kt`, `labs/Labs.kt`, `labs/LabsBus.kt`, `ui/App.kt:239,278,285`, `ui/KeysScreen.kt:145`, `net/WsTransport.kt:121,282`; helper `Labs/Context.swift`, `Hub.swift:254`, `Injector.swift:69`. Docs: `docs/labs/context.md`.
Test: `e2e/journeys` → `dev.mote.remote.journeys.SmartRemoteJourney` (one @Test per path below).

## Preconditions
- P1. App: fresh data, connected to the dry-run helper over Wi-Fi by the `wifi-helper` main path (FindMacScreen "Connect to <Mac>" with `TestSeam.fakeProximity`); next-0.6 keeps setup on the Bluetooth offer, so the precondition taps "Skip" until the pill `Device: <Mac> via Wi-Fi. Tap to choose` shows. Runtime permissions as left by that journey (none needed by this lab). Settings › Labs › "Smart remote (preview)" **on** (DataStore `labs`, key `smart_remote`; set by tapping the Switch whose contentDescription is "Smart remote (preview)", stateDescription "On"). Labs key `smart_remote.auto_keyboard` absent (default true). Mode = Media.
- P2. Phone: Wi-Fi on ("AndroidWifi", 10.0.2.17), Bluetooth irrelevant, no Tailscale VPN.
- P3. Mac / helper: `MoteHelper --dry-run --headless --no-ble --memory-store --no-bonjour --port N --stdin-control --fake-proximity --fake-context /tmp/mote-journeys/files/ctx-<path>.json --fake-nowplaying` (file written by the controller `POST /file`; rewriting it changes the context). With either fake flag the helper forces the lab on (`Context.swift:162`); there is no other headless way to turn "Context & now playing (preview)" on. The file starts as `{"app":"Netflix","bundle":"com.netflix.Netflix"}`.
- P4. Network: same LAN (emulator → 10.0.2.2:N).

## Main path `smart-remote`
| # | Actor | Action (exact) | Expected observable (exact) | Seam |
|---|---|---|---|---|
| S1 | system | helper starts | stderr `[mote] labs: context & now playing on (fake context now-playing)` | real (dry-run) |
| S2 | system | phone links to the helper | phone sends `{"t":"labs.ctx.sub","on":true}` then `{"t":"labs.np.sub","on":true}` (`SmartRemote.kt:44`); helper replies `labs.ctx` (`enabled:true, app:"Netflix", bundle:"com.netflix.Netflix", browser:false, text:false, secure:false, automation:"n/a", full:false`) and `labs.np` (`source:"fake"`, `seek:["fake","keys"]`, `np.title:"Fake Song"`, `duration:300`, `elapsed:42`, `rate:1`) | real |
| S3 | phone user | (on Media, no tap) | SmartPanel above MediaScreen: tag "PREVIEW", header "NETFLIX"; card: artwork placeholder "♪", title "Fake Song", sub "Mote Labs · Music", "PLAYING"; buttons desc "Back 10 seconds" (text "−10") / "Forward 10 seconds" (text "+10"); scrubber contentDescription "Playback position", stateDescription "0:42 of 5:00" (±1 s, it advances); time labels "0:42" and "5:00"; quick actions (Caps, text = label, contentDescription in brackets): "Skip intro" ("Skip intro or recap (S)"), "Fullscreen" ("Toggle fullscreen (F)"), "Exit full" ("Leave fullscreen (Esc)"), "Mute" ("Mute (M)"). Favourites + joystick remain below. No test tag. | real |
| S4 | phone user | tap Cap "Skip intro or recap (S)" | helper stderr `[mote] DRY key usage=22 mods=0 action=tap` | real |
| S5 | phone user | tap "Toggle fullscreen (F)" | `DRY key usage=9 mods=0 action=tap` | real |
| S6 | phone user | drag scrubber to ~50 % and release (or a11y setProgress 0.5) | phone sends `{"t":"labs.np.seek","to":≈150}`; helper stderr `[mote] DRY labs seek via=fake to=≈150.0` (one decimal); reply `{"t":"labs.np.seek","via":"fake","to":≈150}` + a fresh `labs.np`; stateDescription "2:30 of 5:00" | real |
| S7 | phone user | tap "Forward 10 seconds" | absolute seek: `labs.np.seek` `to` = shown position + 10 (`SmartRemote.kt:73`); helper `DRY labs seek via=fake to=<p+10>` | real |
| S8 | system (Mac) | overwrite the context file = `{"app":"Google Chrome","bundle":"com.google.Chrome","url":"https://www.youtube.com/watch?v=x","title":"t","browser":true}` | after ≥ 250 ms debounce: `labs.ctx` with `host:"www.youtube.com"`, no `url`/`title` (privacy default, `full:false`), `automation:"granted"`. Header "YOUTUBE · GOOGLE CHROME"; actions "Fullscreen", "Theater", "Next", "Captions", "Slower", "Faster" (descriptions "Toggle fullscreen (F)", "Theater mode (T)", "Next video (shift N)", "Captions on or off (C)", "Slower (shift comma)", "Faster (shift period)"); card unchanged | faked: front app = `--fake-context` file |
| S9 | phone user | tap "Next video (shift N)" | `DRY key usage=17 mods=2 action=tap` | real |
| S10 | system (Mac) | overwrite file with `{"app":"Safari","bundle":"com.apple.Safari","url":"https://www.google.com/","browser":true,"text":true}` | phone switches to Keys (mode rail "Keys" selected), AutoKeyboardChip: "PREVIEW", "Text field on the Mac · Safari", Cap "Stop auto-keyboard"; the type row takes focus and the Android IME is shown (`KeysScreen.kt:147`, 150 ms delay) | faked: focus = `text:true` in file |
| S11 | phone user | tap "Stop auto-keyboard" | chip disappears; Labs key `smart_remote.auto_keyboard` = false; phone stays on Keys | real |

## Postconditions
- Phone: DataStore `labs`: `smart_remote`=true, `smart_remote.auto_keyboard`=false. Mode = Keys. Subscriptions still on.
- Helper: unchanged trust; context lab on (memory only).

## Branches
### `smart-remote.text-blur`: field loses focus returns to the previous mode (from S10, before S11; not drawn)
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | system (Mac) | rewrite file with `"text":false` | phone goes back to Media (the mode it left); chip gone | faked: file |
Postconditions: auto-keyboard still on.

### `smart-remote.manual-leave`: leaving Keys by hand ends the auto session (from S10; not drawn)
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | phone user | tap rail "Switch to Media" | Media shown; `autoOpened` false (`SmartPanel.kt:99`) | real |
| B2 | system (Mac) | rewrite file `"text":false` | no mode change | faked: file |

### `smart-remote.password`: secure field (from S10; not drawn)
Preconditions delta: file has `"text":true,"secure":true`.
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | system | — | chip text "Password field on the Mac · Safari"; Keys opened as in S10 | faked: file |

### `smart-remote.auto-off-turn-on`: re-enable auto-keyboard (after S11; not drawn)
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | phone user | tap rail "Switch to Media" | SmartPanel header row shows "Auto-keyboard off · turn on" (click label "Turn auto-keyboard on") | real |
| B2 | system (Mac) | file `text:false` then `text:true` | no switch to Keys | faked: file |
| B3 | phone user | tap "Auto-keyboard off · turn on" | label disappears; key = true; next `text:true` opens Keys | real + faked file |

### `smart-remote.no-profile`: app without a profile (from S3; not drawn)
Preconditions delta: file `{"app":"Finder","bundle":"com.apple.finder"}`.
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | system | — | header "FINDER", now-playing card only, no quick-action row | faked: file |
| B2 | — | same, helper started without `--fake-nowplaying` | SmartPanel renders nothing (`SmartPanel.kt:160`); stderr `[mote] labs: now-playing reader off in dry run` | real |

### `smart-remote.no-nowplaying`: profile without now playing (not drawn)
Preconditions delta: helper without `--fake-nowplaying`; file = Netflix (P3).
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | system | — | `labs.np` `source:"unavailable"`, `np:null`; panel shows "PREVIEW", "NETFLIX", the 4 quick actions, no card (no seek controls at all) | real |

### `smart-remote.video-app` / `video-site` / `browser-media`: other profiles (not drawn)
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | system | file bundle `com.apple.QuickTimePlayerX` | header "VIDEO · <app>"; "Fullscreen" (desc "Toggle fullscreen (control command F)") → `DRY key usage=9 mods=9 action=tap`; "Play" → `usage=44`; "−5 s" → `usage=80`; "+5 s" → `usage=79` | faked: file |
| B2 | system | file browser tab host `vimeo.com` | header "VIDEO · <browser>", same 4 actions, Fullscreen = `usage=9 mods=0` | faked: file |
| B3 | system | browser tab with no rule whose bundle == now-playing bundle | header "WEB MEDIA · <app>" | faked: needs new flag (fake now-playing `bundle` is fixed to `com.apple.Music`, `Context.swift:514`) |

### `smart-remote.helper-lab-off`: helper lab off (not drawn)
Preconditions delta: helper started without `--fake-context`/`--fake-nowplaying` (lab defaults off with `--memory-store`).
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | system | phone subscribes | helper replies `{"t":"labs.ctx","enabled":false}`, `{"t":"labs.np","enabled":false}`; panel: "PREVIEW", "SMART REMOTE", hint "Turn on Labs › Context & now playing in the Mote Helper menu on the Mac." | real |
| B2 | Mac user | Menu › Labs › "Context & now playing (preview)" | lab on, broadcast | needs user: menu only; faked: needs new stdin command (e.g. `ctx-lab-on/off`) |

### `smart-remote.no-helper`: Bluetooth or offline (not drawn)
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | system | active transport = Bluetooth HID (`sendLabs` false) | panel: "PREVIEW", "SMART REMOTE", hint "Smart remote needs the Mote Helper over Wi-Fi or Tailscale." | faked: `TestSeam.transport` = FakeTransport with HID caps |
| B2 | system | kill the helper mid-session | link drops → context/now-playing reset → same hint | real |

### `smart-remote.automation-denied`: browser tabs denied (not drawn)
Preconditions delta: file `{"app":"Safari","bundle":"com.apple.Safari","browser":true,"automation":"denied"}`.
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | system | — | hint "Allow Mote Helper to control Safari (System Settings › Privacy › Automation) to see the tab." | faked: file |

### `smart-remote.seek-paused`: seek while input paused (from S6; not drawn)
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | Mac (stdin) | `pause` | — | real (stdin-control) |
| B2 | phone user | release scrubber | helper `err` `{code:"paused",msg:"input is paused on the Mac",ref:"labs.np.seek"}`; card note (under card) "input is paused on the Mac"; scrubber snaps back | real |
| B3 | phone user | tap "Skip intro or recap (S)" | helper `err` `paused` for `key`; no `DRY key` line | real |

### `smart-remote.lab-off-phone`: phone lab off (not drawn)
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | phone user | Settings › Labs switch "Smart remote (preview)" → Off | phone sends `labs.ctx.sub`/`labs.np.sub` `on:false`; SmartPanel and chip render nothing; Media back to the plain layout | real |

### `smart-remote.automation-ask`: browser tabs not decided yet (next-0.6; not drawn)
Preconditions delta: file `{"app":"Safari","bundle":"com.apple.Safari","browser":true,"automation":"ask"}`.
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | system | — | header "SAFARI", the now-playing card, no hint at all: nothing tells the user to click Labs › "Allow Safari tabs…" in the helper menu (the helper no longer asks by itself, `Context.swift:267-275`), so YouTube / video-site profiles never appear in that browser (smart-remote-D9) | faked: file |

## Disagreements → DEFECTS.md
- `smart-remote-D1` (S3): artboard header "Netflix in front"/"YouTube in front" vs code "NETFLIX" / "YOUTUBE · <APP>".
- `smart-remote-D2` (S3): artboard times show remaining "-41:30"; code shows total duration.
- `smart-remote-D3` (S8): artboard YouTube chips "−10 s", "+10 s", "Next", "CC"; code card "−10"/"+10" + actions Fullscreen/Theater/Next/Captions/Slower/Faster.
- `smart-remote-D4` (S3): artboard panel replaces Favourites; code puts it above the full MediaScreen.
- `smart-remote-D5` (S10): artboard chip "Keyboard opened for a text field on the Mac"; code "Text field on the Mac · Safari" + PREVIEW tag; artboard shows Mote keys deck, code also raises the Android IME.
- `smart-remote-D6` (no-nowplaying): doc says quick actions and `keys` seek keep working without the card; the phone has no seek UI without a card, so the `js`/`keys` paths are unreachable from the app.
- `smart-remote-D7` (helper-lab-off): an old helper (`err unknown` to `labs.ctx.sub`) gets the "Turn on Labs › Context & now playing…" hint.
- `smart-remote-D8` (helper-lab-off): no headless way to toggle the helper lab at runtime (fakes force it on at launch).
- `smart-remote-D10` (S11): after "Stop auto-keyboard" the chip comes back at once while the Mac field keeps focus.
- `smart-remote-D9` (automation-ask): next-0.6 no longer asks for Automation when a browser is in front; the phone has no hint for the undecided state and docs/labs/context.md still says it is asked lazily.

## UNDEFINED
- S10: exact IME appearance/timing (Android system keyboard, 150 ms delay in code only).
- `ax` seek error (`Accessibility permission not granted to Mote Helper`): unreachable in dry-run (`inputReady` is always true with `--dry-run`, `Hub.swift:62`); manual: needs a real non-dry-run helper without Accessibility.
- Real context sources (NSWorkspace front app, AX focus, AppleScript tab read, the Automation prompt, MediaRemote via perl): manual: touch TCC / real media; never in tests.
