# video-bridge: 11. Browser video bridge

Artboard: `design/mote-v1/journey-video-bridge.html` (Preview (Labs)). Code: `android/.../labs/video/{VideoBridge,VideoState,WebVideoPanel}.kt`, `labs/Labs.kt`, `ui/App.kt:150-151,278`, `ui/MediaScreen.kt:71-76`; helper `Labs/Bridge.swift`, `Hub.swift:90,250`, `main.swift:68`; `extension/{manifest.json,background.js,content.js,popup.html,popup.js}`. Docs: `docs/labs/video-bridge.md`, `extension/README.md`.
Test: `e2e/journeys` → `dev.mote.remote.journeys.VideoBridgeJourney` (one @Test per path below).

## Preconditions
- P1. App: fresh data, connected to the dry-run helper over Wi-Fi by the `wifi-helper` main path (FindMacScreen "Connect to <Mac>" with `TestSeam.fakeProximity`); next-0.6 keeps setup on the Bluetooth offer, so the precondition taps "Skip" until the pill `Device: <Mac> via Wi-Fi. Tap to choose` shows. Settings › Labs › "Browser video control (preview)" **on** (DataStore `labs`, key `labs_video_bridge`; Switch contentDescription "Browser video control (preview)"). Mode = Media.
- P2. Phone: Wi-Fi on ("AndroidWifi"), no Tailscale VPN; Bluetooth irrelevant.
- P3. Mac / helper: `MoteHelper --dry-run --headless --no-ble --memory-store --no-bonjour --port N --stdin-control --fake-proximity`. The journey controller doesn't allow `--labs-bridge` / `--bridge-port`, so the bridge is turned on with stdin `bridge on` and listens on its default port 8792 (`Bridge.swift:13`); stdin `bridge on|off|pair|forget` stand in for the menu (`Bridge.swift:273`).
- P4. Network: same LAN for the phone; the extension side is loopback-only on the Mac (`127.0.0.1:<bridge port>`).
- P5. Browser: no real browser window. The extension is either (a) `extension/` loaded into headless Playwright Chromium (`--load-extension`, as `e2e/web/tests/video-bridge.spec.ts`) on a local test page with a `<video>`, or (b) a scripted bridge client (raw WebSocket, `Origin: chrome-extension://<id>`, as `helper/tests/bridge.mjs`).

## Main path `video-bridge`
| # | Actor | Action (exact) | Expected observable (exact) | Seam |
|---|---|---|---|---|
| S1 | Mac (stdin) | `bridge on` (stands in for Labs › Browser extension bridge) | stderr `[mote] bridge: on`, `[mote] bridge: listening on 127.0.0.1:8792` | real (dry-run) |
| S2 | Mac user | install the extension (chrome://extensions › Developer mode › Load unpacked) | extension "Mote Video Bridge (preview)" loaded | faked: `--load-extension` in headless Chromium (P5a); manual otherwise |
| S3 | Mac (stdin) | `bridge pair` | stdout `BRIDGE CODE: <6 digits>`; (menu "   Extension code: ddd ddd", headless-unobservable) | real (stdin-control) |
| S4 | Mac user | extension popup: type the code in "Pairing code" (placeholder "123 456"), press "Pair" | helper `[mote] bridge: paired chrome-extension://<id>`, then `[mote] bridge: authed chrome-extension://<id>`; popup status "Connected to Mote Helper" | faked: P5a drives the popup page, or P5b sends `labs.video.pair`/`labs.video.auth` |
| S5 | system | phone (lab on) is linked | phone sends `{"t":"labs.video.sub","on":true}`; helper replies `labs.video.state` `bridge:"connected"` (`"waiting"` before S4), `active:false` → no panel, Media shows Favourites | real |
| S6 | system (browser) | the tab plays a 12:00 video on `www.youtube.com` (state: `active:true,title:"How a lathe works, in 12 minutes",site:"youtube",duration:720,time:245,paused:false,rate:1,seekable:true,captions:"off",skips:["ad"]`) | phone Media: WebVideoPanel (replaces Favourites + brightness): "PREVIEW", title "How a lathe works, in 12 minutes", host "youtube.com" (`www.` stripped); clock "4:05" … "12:00"; scrubber desc "Video position", state "4:05 of 12:00"; row: "Back 10 seconds" ("−10"), primary desc "Pause video", "Forward 10 seconds" ("+10"); row: "Playback speed 1×, tap for the next speed" ("1×"), "Captions off, tap to turn on" ("CC"), "Full screen" ("Full"), "Skip ad" (lit). SmartPanel hidden. No test tag. | P5a real page / P5b faked state |
| S7 | phone user | tap "Forward 10 seconds" | phone `{"t":"labs.video.cmd","cmd":"seekBy","value":10}`; helper `[mote] bridge: cmd seekBy 10.0`; clock jumps +10 at once (optimistic) | real |
| S8 | phone user | drag the scrubber and release | `labs.video.cmd` `cmd:"seek"`, `value` = fraction × 720; helper `bridge: cmd seek <v>` | real |
| S9 | phone user | tap "Playback speed 1×, tap for the next speed" | `cmd:"rate","value":1.25`; helper `bridge: cmd rate 1.25`; label becomes "1.3×" (`String.format("%.2g")`, `WebVideoPanel.kt:228`) | real |
| S10 | phone user | tap "Captions off, tap to turn on" | `cmd:"captions"` (no value = toggle); helper `bridge: cmd captions`; CC lit, desc "Captions on, tap to turn off" | real |
| S11 | phone user | tap "Skip ad" | `cmd:"skip","value":"ad"`; helper `bridge: cmd skip ad`; button disappears (optimistic) | real |
| S12 | phone user | tap "Pause video" | `cmd:"toggle"`; helper `bridge: cmd toggle`; primary desc "Play video" | real |
| S13 | system (browser) | page reports the new state | panel follows the next `labs.video.state` (exact seek/rate) | P5a real / P5b faked |

## Postconditions
- Helper: one paired extension in the memory store (`<keychain service>.bridge`, memory-only), pinned to `chrome-extension://<id>`. Phone subscribed.
- Phone: DataStore `labs_video_bridge`=true; Media shows WebVideoPanel while `active:true`.

## Branches
### `video-bridge.helper-off`: bridge toggle off (not drawn)
Preconditions delta: helper without `--labs-bridge`, or stdin `bridge off` mid-session (log `[mote] bridge: off`).
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | system | phone subscribes | `labs.video.state` `{bridge:"off",active:false}`; no panel, no hint anywhere on the phone | real |
| B2 | system (browser) | extension connects | popup status "Mote Helper not reachable. Is Labs › Browser extension bridge on?" | P5a real |

### `video-bridge.waiting`: bridge on, extension not paired/connected (not drawn)
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | system | — | state `bridge:"waiting",active:false`; no panel; popup "Not paired yet" | real |

### `video-bridge.wrong-code` (from S4; not drawn)
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | Mac user | Pair with a wrong code | helper `[mote] bridge: wrong pairing code`; popup "error: Wrong code" | P5a / P5b |
| B2 | Mac user | 5 wrong codes within a minute | popup "error: too many attempts" (`err rate`, `retryAfter`) | P5a / P5b |

### `video-bridge.pairing-closed` (from S4 without S3; not drawn)
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | Mac user | Pair while no pairing window is open | popup "error: Open Labs › Pair browser extension… in the Mote Helper menu" | P5a / P5b |

### `video-bridge.no-video`: tab without a video (from S6)
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | system | state `active:false` | panel disappears; Favourites + brightness return; SmartPanel may show | P5a / P5b |
| B2 | phone user | (race) tap a control just as it goes | helper `err` `notfound` "No video in the browser" (ref `labs.video.cmd`); phone shows nothing | P5b |

### `video-bridge.live`: live stream (not drawn)
Preconditions delta: state `live:true,duration:null,seekable:false`.
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | system | — | right label "LIVE" (accent); scrubber state "Live", disabled; "−10"/"+10" at 38 % alpha, disabled semantics, tap = reject haptic and no message | P5b |

### `video-bridge.options`: optional controls (not drawn)
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | system | `captions:"none"` | no CC button | P5b |
| B2 | system | `hasPrev:true,hasNext:true` | icon Caps "Previous video" / "Next video" → `cmd:"prev"` / `cmd:"next"` | P5b |
| B3 | system | `skips:["ad","intro"]` | only "Skip ad" shown (first entry, helper sorts) | P5b |
| B4 | phone user | tap "Full screen" | `cmd:"fullscreen"`; label "Exit full", desc "Exit full screen" | real |
| B5 | phone user | tap speed at 2× | wraps to 0.5: `cmd:"rate","value":0.5`, label "0.50×" | real |

### `video-bridge.paused`: input paused on the Mac (not drawn)
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | Mac (stdin) | `pause` | — | real |
| B2 | phone user | tap "Pause video" | helper `err` `{code:"paused",msg:"input is paused on the Mac",ref:"labs.video.cmd"}`; phone flips to "Play video" anyway (optimistic), no error copy; corrected only by the next state (≤ 5 s heartbeat) | real |

### `video-bridge.page-refused`: page can't run the command (not drawn)
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | system (browser) | extension answers `labs.video.result {cmd:"next",ok:false,code:"notfound"}` | helper `[mote] bridge: next failed: notfound`, forwards `err` "The page couldn't do next"; phone shows nothing | P5b |

### `video-bridge.forget`: forget / revoke (not drawn)
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | Mac (stdin) | `bridge forget` | helper `[mote] bridge: extension forgotten`; extension gets `err revoked` → popup "Not paired yet"; phone panel disappears | real + P5a/P5b |
| B2 | Mac (stdin) | `revoke` | also forgets the extension (`Hub.swift:90`); the phone session is revoked (see `recovery`) | real |

### `video-bridge.not-helper`: Bluetooth or link down (not drawn)
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | system | active transport Bluetooth HID | panel hidden (`webVideoVisible` needs `caps.clickAt`) | faked: `TestSeam.transport` FakeTransport (HID caps) |
| B2 | system | kill the helper | `Disconnected` → state null → panel gone; on relink the phone re-sends `labs.video.sub` | real |

### `video-bridge.lab-off-phone` (not drawn)
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | phone user | Settings › Labs "Browser video control (preview)" → Off | phone sends `labs.video.sub` `on:false`; state null; no panel | real |

## Disagreements → DEFECTS.md
- `video-bridge-D1` (S3/S4): artboard "Same 6-digit code as phones"; code uses a separate bridge code (`BRIDGE CODE:`, "Pair browser extension…").
- `video-bridge-D2` (S4): artboard in-page card "Pair with Mote Helper / Enter the code from the menu bar"; code: toolbar popup "Mote Video Bridge PREVIEW / Pairing code (Mote Helper menu › Labs › Pair browser extension…)".
- `video-bridge-D3` (S2): artboard extension "Mote video bridge / Lets Mote control videos in this browser"; manifest name/description differ.
- `video-bridge-D4` (S6): artboard header "Web video, Chrome", "-7:55" remaining; code shows title + host and total duration; artboard omits play/±10 row.
- `video-bridge-D5` (S9): speed label "%.2g" shows "1.3×" for 1.25 and "0.50×" for 0.5; artboard "1.25×".
- `video-bridge-D6` (options B3): artboard shows "Skip ad" and "Skip intro" together; code shows only the first skip.
- `video-bridge-D7` (S13): artboard "Controlled by Mote" badge in the page; extension has none.
- `video-bridge-D9` (forget): the helper cancels the extension socket right after queuing `err revoked`; the client never gets it.
- `video-bridge-D8` (paused / page-refused / no-video): helper `err` for `labs.video.cmd` is dropped by the phone; README says "the phone shows an error".

## UNDEFINED
- S2: Chrome's extension page and Developer-mode UI copy (browser UI, illustrative).
- Autoplay-policy refusal (`code:"blocked"`) and real fullscreen behaviour: depend on the browser; manual.
- Netflix / Prime adapters against real sites: tests never use real sites.
