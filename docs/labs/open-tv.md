# Labs: Open… and TV mode (preview)

Switching what's playing from the couch means pausing, leaving fullscreen, opening a tab and
steering YouTube with a mouse. This preview lets the phone drive the Mac directly:

- **Open… sheet** (phone): search YouTube (results open in TV mode), paste or share a link, recents,
  editable pinned sites (YouTube, Netflix, Prime Video, Disney+, Twitch, Spotify web), and the quick
  actions *Close this tab*, *Exit fullscreen* and *Pause everything*. The field uses the normal text
  keyboard, so its mic gives voice search.
- **TV mode** (helper): a fullscreen WKWebView window on the active display, owned by the helper,
  showing YouTube's leanback UI (`youtube.com/tv`) or a 10-foot launcher with big tiles for the pins.
- **TV remote** (phone): while TV mode is up, Media shows a large D-pad, OK, Back, Home, Search,
  play/pause and volume, labelled *Preview*.

Everything is off by default, on both sides:

| Where | Toggle |
|---|---|
| Android | Settings › Labs › *Open & TV mode (preview)*. It also enables the "Open on Mac" share target. |
| Helper | Menu bar › Labs › *TV mode (preview)* (`--labs-tv` forces it on for one run, for tests). |

These messages are a Labs extension. They are **not** part of [docs/protocol.md](../protocol.md)
and do not change its version. A helper without this preview answers them with `err` `unknown`.

## Spike result: does youtube.com/tv work in a WKWebView? (2026-11-07, macOS 26)

Yes, with a Cobalt (YouTube's TV app runtime) user agent:

| User agent | Result |
|---|---|
| Desktop Safari | `youtube.com/tv` shows "You are being directed to youtube.com" and redirects to `youtube.com/?app=desktop`. |
| Cobalt (`… Cobalt/25.lts.30.1034943-gold (unlike Gecko), Unknown_TV_Unknown_0/Unknown …`) | Title "YouTube on TV", leanback app (`ytlr-app`): account picker with *Watch as guest* and sign in by code (`yt.be/activate`). `#/watch?v=ID` opens the watch page, `#/search?q=…` shows results. |

Native key events sent with `NSWindow.sendEvent` arrive in the page as trusted `keydown`
(`keyCode 40 ArrowDown, isTrusted true`), even when the window isn't key, so the D-pad drives
leanback directly without global injection. Not verified headless: actual video playback (snapshots of
the video layer are black) and the order of the first-run account picker on a fresh profile. TV mode
uses the default (persistent) data store, so the guest choice or sign-in sticks.

**Fallback (built in, not needed today):** if YouTube redirects the TV window to the desktop site,
the helper sets the page zoom to 175 % and reports `page: "youtube-desktop"`; arrows then scroll and
move focus as on any page.

**Terms of Service caveat.** Sending a TV user agent from a desktop app is not a supported YouTube
client. Google can block it at any time, and it may conflict with YouTube's Terms of Service and API
policies. That is one reason this stays a Labs preview, off by default. Nothing is scraped or
modified, and ads and sign-in work as on a TV.

DRM sites (Netflix, Prime Video, Disney+) are not expected to play in a third-party WKWebView, so
their launcher tiles and the phone's pins open them in the **default browser**, fullscreen, instead.

## Messages

All of these come from the client after `authed`, and pause and Accessibility apply to them like
input. Apart from `labs.tv.get`, each one is refused with `err` `disabled` while the helper toggle
is off.

| Message | Fields | Effect |
|---|---|---|
| `labs.open.url` | `url`, `mode`: `browser` \| `tv`, optional `fullscreen` (browser only) | `browser`: opens in the default browser (NSWorkspace), then sets `AXFullScreen` on its focused window if asked. `tv`: opens TV mode; YouTube links are mapped to leanback (watch, shorts, live, embed, youtu.be: `#/watch?v=`; `results?search_query=`: `#/search?q=`). |
| `labs.open.action` | `a`: `closeTab` \| `exitFullscreen` \| `pauseAll` | `closeTab`: ⌘W. `exitFullscreen`: clears `AXFullScreen` on the frontmost app's focused window, else Esc (leaves HTML5 video fullscreen). If TV mode is frontmost, both close TV mode. `pauseAll`: pauses every `<video>`/`<audio>` in TV mode, else sends one play/pause media key (a toggle: if nothing was playing it may resume something). |
| `labs.tv.open` | optional `url`, optional `pins` (≤ 12 `{title, url}`) | Opens TV mode at `url`, or at the launcher. `pins` replaces the launcher tiles. |
| `labs.tv.key` | `k`: `up` `down` `left` `right` `ok` `back` `home` `playpause` `search` | Goes to the TV window as native key events (arrows, Return, Esc = Back). `home` = the launcher, `search` = leanback search, `playpause` toggles the page's video. `err` `tv-off` when TV mode is closed. |
| `labs.tv.exit` | | Closes TV mode. |
| `labs.tv.get` | | Replies with `labs.tv.state`. It is a read, so it works while disabled. |
| `labs.tv.state` (helper → client) | `enabled`, `on`, `page` (`home` \| `youtube` \| `youtube-desktop` \| `web`), `url` (when on) | Pushed to every authed session when anything changes, and sent in reply to `labs.tv.get`. |

While TV mode is **frontmost**, plain `key` taps without modifiers for arrows, Return and Esc are
delivered to the TV window instead of being injected globally, so the normal Media D-pad works too.

Errors: `disabled` (helper toggle off), `bad` (bad URL, mode, key, action or pins), `tv-off`, plus the
usual `paused` / `ax` / `unauth`.

## Security

- The phone and the helper each accept only `http`/`https` URLs with a host, at most 2048 bytes, with
  no `user:pass@`, whitespace or control characters. `javascript:`, `file:`, `data:`, `intent:`,
  `content:`, custom schemes and the rest are refused. The Android tests (`OpenUrlTest`) and the
  helper smoke test (`helper/tests/labs.mjs`) cover this.
- The TV window only navigates to http(s), about and blob URLs (data: only in subframes).
  `window.open` / `target=_blank` loads in place after the same URL check. The launcher is a
  self-contained HTML string with a CSP of `default-src 'none'`, and its tile messages are accepted
  only while the launcher itself is showing.
- A shared link (ACTION_SEND) only pre-fills the Open… sheet. Nothing is sent until the user taps
  Open. The share target is a disabled `activity-alias` that the app enables only while the Labs
  flag is on.
- An authenticated phone could already type any URL into the Mac, so this adds convenience, not a
  new capability. It is still opt-in on the Mac.

## Testing

- `--dry-run` never opens a window or a browser and never injects. It logs `DRY labs.open.url …`,
  `DRY labs.tv open …`, `DRY labs.tv key …`, `DRY labs.open.action …` and `DRY labs.tv exit`, and
  keeps the TV state so `labs.tv.state` can be checked.
- `node helper/tests/smoke.mjs …` runs `helper/tests/labs.mjs`, which starts its own helpers
  (`--dry-run`, with and without `--labs-tv`).
- Android: `OpenUrlTest`, `SitesTest`, and `OpenTvIntegrationTest` (real helper, `--dry-run --labs-tv`).
