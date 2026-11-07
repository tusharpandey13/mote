# Labs: Mote Video Bridge (preview)

Control the main HTML5 `<video>` of the active browser tab from the phone: play/pause, seek to a position, ±10 s, speed, volume, fullscreen, next/previous (where the site has them), captions and the site's skip-ad / skip-intro buttons. No per-site keyboard shortcuts.

Status: **preview**, off by default on both ends. It is not part of [the main protocol](../protocol.md) and doesn't change its version; every message is namespaced `labs.video.*`, and a helper without the bridge answers them with `err` `unknown` (clients treat that as "not available").

```
 phone (Mote app)          Mote Helper (Mac)                       browser
 ───────────────  main WS  ───────────────────  bridge WS (loopback)  ──────────────────────────
 labs.video.sub  ───────►  relays the active  ◄──────────────────────  extension service worker
 labs.video.cmd  ───────►  bridge's state,    ──────────────────────►  └─ content script per frame
 ◄─────── labs.video.state routes commands                                 (finds the main <video>)
```

- **Phone ↔ helper**: the normal authenticated session (Wi-Fi, Tailscale; BLE works too but is slow). Same `paused` kill switch as input.
- **Helper ↔ extension**: a second, loopback-only WebSocket, `ws://127.0.0.1:8792/bridge`, only while Menu › Labs › "Browser extension bridge" is on.
- Code: `extension/` (Manifest V3), `helper/Sources/MoteHelper/Labs/Bridge.swift`, `android/.../labs/video/`.

## Enabling

| Where | Toggle | Default |
|---|---|---|
| Mote app | Settings › Labs › "Browser video control (preview)" | off |
| Mote Helper | Menu › Labs › "Browser extension bridge" (persisted; `--labs-bridge` forces it on for one run) | off |
| Browser | Load `extension/` unpacked (see `extension/README.md`), pair once with the code from Menu › Labs › "Pair browser extension…" | — |

With the helper toggle off, the bridge port isn't open at all.

## 1. Phone ↔ helper (main session, after `authed`)

| Message | Direction | Fields |
|---|---|---|
| `labs.video.sub` | phone -> helper | `on` (bool, default true). `true`: reply with `labs.video.state` now and on every change. `false`: stop (no reply). |
| `labs.video.state` | helper -> phone | `bridge`: `off` (toggle off) / `waiting` (no extension connected) / `connected`. `active` (bool): a tab reported a main video. When `active`, the video fields below. |
| `labs.video.cmd` | phone -> helper | `cmd` and optional `value` (below). No reply on success: the next `labs.video.state` shows the effect. `err` `unsupported` (bridge off), `notfound` (no extension or no active video), `paused` (input paused on the Mac), `bad` (unknown `cmd` / bad `value`). |

Video fields (all optional, unknown ones are ignored; strings are capped by the helper):

| Field | Type | Meaning |
|---|---|---|
| `title` | string ≤ 200 | Page or video title |
| `host` | string ≤ 100 | URL host only (never the full URL) |
| `site` | string | Adapter: `youtube`, `netflix`, `prime`, `twitch`, `generic` |
| `duration` | number \| null | Seconds; `null` for live or unknown (`Infinity` in the page) |
| `time` | number | `currentTime` in seconds, when sent. Clients extrapolate `time + elapsed × rate` while `!paused` |
| `paused`, `muted`, `fullscreen`, `live` | bool | |
| `rate` | number | `playbackRate` (0.25 to 4) |
| `volume` | number | 0 to 1 |
| `seekable` | bool | Seeking works (false on Netflix without the page-world API, and on most live streams) |
| `hasNext`, `hasPrev` | bool | The site shows a next / previous control |
| `captions` | `on` / `off` / `none` | `none`: no caption track or button |
| `skips` | array of `ad` / `intro` / `recap` / `credits` | Skip buttons visible right now |

`cmd` values:

| `cmd` | `value` | Effect |
|---|---|---|
| `play`, `pause`, `toggle` | – | |
| `seek` | seconds (absolute, clamped to 0..duration) | |
| `seekBy` | seconds (±, e.g. -10 / 10) | |
| `rate` | 0.25 to 4 | |
| `volume` | 0 to 1 | also unmutes |
| `mute` | bool, or absent to toggle | |
| `fullscreen` | bool, or absent to toggle | See [limits](#limits) |
| `next`, `prev` | – | Clicks the site's control; `notfound` from the extension if there is none |
| `captions` | bool, or absent to toggle | Site button (YouTube), else the first `textTracks` entry |
| `skip` | `ad` / `intro` / `recap` / `credits`, or absent = the first visible | Clicks the site's skip button |

Example:

```json
{"t":"labs.video.sub","on":true}
{"t":"labs.video.state","bridge":"connected","active":true,"title":"Big Buck Bunny","host":"www.youtube.com","site":"youtube",
 "duration":596.5,"time":42.1,"paused":false,"rate":1,"muted":false,"volume":0.8,"fullscreen":false,"live":false,
 "seekable":true,"hasNext":true,"hasPrev":false,"captions":"off","skips":["ad"]}
{"t":"labs.video.cmd","cmd":"seekBy","value":10}
```

## 2. Extension ↔ helper (bridge socket)

`ws://127.0.0.1:<port>/bridge`, port 8792 by default (`--bridge-port N`; `0` = any free port, logged as `bridge: listening on :N`). JSON text frames, ≤ 64 KiB.

```
extension                                         helper
  | --- (connect, Origin: chrome-extension://<id>) --> |   refused unless loopback peer, loopback Host, extension Origin
  | <-- hello {v:1, app:"mote-helper", bridge:1,       |
  |            nonce, pairing} ----------------------- |
  | --- labs.video.pair {code} ----------------------> |   only while the bridge pairing window is open
  | <-- labs.video.paired {id, token} ---------------- |   token: 64 hex, stored in chrome.storage.local
  | --- labs.video.auth {id, mac} -------------------> |   mac = hex(HMAC-SHA256(token bytes, nonce)), same as the main protocol
  | <-- labs.video.authed {id} ----------------------- |   or err {code: "auth"}
  | --- labs.video.state {active, ...video fields} --> |   on change; `active:false` when the tab has no video
  | <-- labs.video.cmd {cmd, value} ------------------ |
  | --- labs.video.result {cmd, ok, code?} ----------> |   optional; the helper logs it and forwards errors to the phone as `err`
  | <-> ping / pong                                    |   every 20 s (keeps the MV3 service worker alive)
```

The extension relays the **active tab of the last focused window** if its main video is present, else the tab that played most recently. Several connected browsers: the helper relays the one that sent an `active:true` state most recently.

## 3. Security and threat model

The bridge lets an authenticated phone drive a page's video. It must not let anything else on the Mac drive the phone's view or the browser, and must not widen the main protocol's attack surface.

| Threat | Defense |
|---|---|
| Anything on the network | The listener binds `127.0.0.1` only (`requiredLocalEndpoint`), and closes any connection whose peer isn't loopback before reading a byte. It isn't advertised over Bonjour. |
| A web page in any browser on the Mac (cross-site WebSocket hijacking) | The upgrade needs an `Origin` of `chrome-extension://`, `moz-extension://` or `safari-web-extension://`; pages can't forge `Origin`. After pairing, the origin is pinned: auth succeeds only from the exact extension origin that paired. |
| DNS rebinding | `Host` must be `127.0.0.1:<port>` or `localhost:<port>`. |
| Another extension in the same browser | Has a different origin: can't pass the pinned-origin check, and has neither the token nor the code. Before the first pairing it could pair only with the code shown on the Mac. |
| Other local processes (native code can send any `Origin`) | Need the token (HMAC challenge per connection, never re-sent) or the 6-digit code, which is only valid while the user opened "Pair browser extension…" (5 minutes, one pairing, 5 failures per minute, 10 failures close the window). |
| A bridge client driving the Mac | The bridge only accepts `labs.video.*`. No input message (`key`, `move`, …) is routed from it, and it never sees phone messages other than video commands. |
| Page content reaching the phone | The helper rebuilds every state message from the whitelisted fields above (types checked, strings capped), sends only the URL host, never the full URL. |
| Phone commands while the user paused input | `labs.video.cmd` follows "Pause input" (`err` `paused`). |
| Revocation | Menu › Labs › "Forget browser extension" deletes the bridge token (Keychain item `<service>.bridge`) and drops bridge connections. Turning the toggle off closes the port. "Revoke all devices" also forgets it. |

Known limits: a process running as the same user can read the extension's `chrome.storage.local` from the browser profile on disk and impersonate it. Such a process can already post keystrokes or drive the browser directly, so that is out of scope (same as the main helper's Keychain item). Traffic is plain `ws` on loopback (not observable off-host).

## Limits

- **Fullscreen**: the Fullscreen API needs a user gesture, which a remote command isn't. The extension first tries `requestFullscreen()`, then falls back to making the browser window fullscreen and filling the viewport with the video ("pseudo fullscreen"; Esc or the command again exits). The site's own fullscreen UI stays as it was.
- **Netflix**: setting `currentTime` on Netflix's `<video>` triggers a playback error (M7375). Seeking uses the page's player API (`netflix.appContext.state.playerApp.getAPI().videoPlayer`) from a page-world script (`world: "MAIN"`). It is undocumented and can break at any time; when it isn't found `seekable` is false and `seek` / `seekBy` answer `unsupported`. Play/pause, volume and skip intro/recap/next episode work on the element and buttons.
- **Prime Video**: works on the element; skip buttons by class name (`atvwebplayersdk-skipelement-button`, next-up card); the player is in the top document.
- **Twitch**: live streams report `live: true`, no duration, `seekable: false`; VODs seek normally.
- **DRM / cross-origin frames**: content scripts run in every frame (`all_frames`, `match_about_blank`), so embedded players are covered where the browser allows content scripts; sandboxed frames without script access are not.
- Site adapters are CSS selectors (`extension/adapters.js`) and break when sites change their markup; the generic path (the largest visible `<video>`) keeps working.

## Safari

Not built yet. The extension uses only standard WebExtension APIs plus `chrome.windows`, so `xcrun safari-web-extension-converter extension/ --app-name "Mote Video Bridge"` should produce an Xcode project; the origin is then `safari-web-extension://<uuid>`, which the helper accepts. Safari may block `ws://127.0.0.1` from extension contexts depending on version; untested.

## Testing

- `node helper/tests/bridge.mjs <port> <bridge-port>` against `--dry-run --labs-bridge --stdin-control`: loopback-only, origin check, auth required, pinning, relay.
- `e2e/web/tests/video-bridge.spec.ts`: Playwright with a persistent Chromium context that loads `extension/` unpacked, local test pages (a plain `<video>` page and a mock YouTube DOM, never real sites), a dry-run helper, and a fake phone over WebSocket.
- Android: `VideoStateTest` (reducer).
