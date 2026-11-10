# Mote helper protocol, v1.5

One JSON message protocol shared by both transports of the Mote Helper (macOS menu-bar app):

- **Wi-Fi** (LAN, or a tailnet when allowed, see [Tailscale](#tailscale)): WebSocket at `ws://<host>:8790/ws` (same origin as the web remote served at `http://<host>:8790/`). One JSON message per text frame. Bonjour type `_mote._tcp`, TXT record `id=<hostId>`, `v=1`.
- **BLE**: GATT service on the Mac (the helper is the peripheral, the phone is the central). Same JSON, fragmented (see [BLE framing](#ble-framing)).

All messages are JSON objects with a type field `t`. Unknown fields are ignored. Unknown `t` gets an `err`. The protocol version is announced in `hello` (`"v": 1`); a peer that sees a different major version closes the connection. Minor versions only add optional fields, so `v` stays 1.

Changes in 1.5 (backward compatible): diagnostics, see [2.1](#21-diagnostics-v15). New `diag` (request, returns a snapshot of permissions, signing, transports and features with a `state` / `blockedBy` / `fix` each), `diag.subscribe` / `diag.events` (live event stream with replay), the envelope fields `corr` (echoed on replies, stamped on events) and `step`, and an optional `client {app, proto}`. A 1.4 helper answers the new messages with `err unknown`.

Changes in 1.4 (backward compatible): Spaces per display. `spaces` gains `displays` (each display's Space list), `display` (the active display) and `shared`; the 1.3 `count` / `current` / `thumbs` now follow the active display instead of the main display. `spaces.thumb` and `spaces.goto` take an optional `display`; the `spaces.thumb` reply carries it. `spaces` is also pushed when the active display changes. A 1.3 helper ignores `display` (it means the main display there).

Changes in 1.3 (backward compatible): Spaces: `spaces.sub` / `spaces` state, `spaces.thumb` thumbnails and `spaces.goto` (section 2, spaces). Error code `notfound`. A 1.2 helper answers `spaces.*` with `err` `unknown`; clients treat that as "no Spaces support".

Changes in 1.2 (backward compatible): `consumer` gains `action` (`down` / `up`) and the keys `fast` / `rewind`, for hold-to-seek (section 2, consumer).

Instant pairing (0.6, default on the phone): key-based trust with no code, under `labs.pair.*` messages. See [`pairing.md`](pairing.md); nothing in this document changes.

Changes in 1.1 (backward compatible): `hello.hostId` and `hello.local`, Bonjour TXT record, extra fields in the pairing link (section 1.1), and the opt-in [Tailscale](#tailscale) access rules (section 4).

## 1. Handshake and auth

```
client                                   helper
  | -------- (connect) ------------------> |
  | <------- hello {v, host, nonce, ...} --|   nonce: 32 hex chars, new per connection
  |                                        |
  |  known device:                         |
  | -- auth {id, mac} -------------------> |   mac = hex(HMAC-SHA256(key = token bytes, msg = nonce ascii))
  |                                        |
  |  new device (pairing window open):     |
  | -- pair {code, name} ----------------> |
  | <------- paired {id, token} ---------- |   token: 64 hex chars (32 random bytes), shown ONCE
  | -- auth {id, mac} -------------------> |
  |                                        |
  | <------- authed {id, name, paused, ax} |   or err {code: "auth", ...}
```

| Message | Direction | Fields |
|---|---|---|
| `hello` | helper -> client | `v` (1), `app` ("mote-helper"), `host` (Mac name), `hostId` (v1.1: 16 hex chars, stable per Mac), `local` (v1.1: Bonjour host name, reachable as `<local>.local`), `nonce`, `pairing` (bool: pairing window open) |
| `pair` | client -> helper | `code` ("123456"), `name` (device label, ≤ 64 chars) |
| `paired` | helper -> client | `id` (device id, uuid), `token` (64 hex) |
| `auth` | client -> helper | `id`, `mac` |
| `authed` | helper -> client | `id`, `name`, `paused` (bool), `ax` (bool: Accessibility granted) |
| `state` | helper -> client | `paused`, `ax` (sent when either changes) |
| `err` | helper -> client | `code`, `msg`, optional `retryAfter` (seconds), optional `ref` (the `t` that failed) |
| `ping` / `pong` | both | optional `n` (echoed) |

Error codes: `auth` (bad/unknown token), `code` (wrong pairing code), `pairing-closed`, `rate` (too many attempts, see `retryAfter`), `unauth` (input before auth), `paused`, `ax` (Accessibility not granted), `bad` (malformed), `unknown` (unknown `t`), `revoked`, `unsupported` (e.g. no way to set brightness), `notfound` (1.3: no thumbnail for that Space), `keychain-unavailable`, `keychain-reading`.

`keychain-unavailable` (with `ephemeral: true`) answers an `auth` while the helper cannot read its saved pairings (Keychain unreadable, docs/pairing.md section 7): the pairing may well exist. The client MUST keep its token, must not offer re-pairing as the fix, and retries later (the next connect, or once the Mac user chose "Try reading Keychain again"). It does not count towards the `rate` limit. A plain `auth` error still means the token is unknown.

`keychain-reading` (with `retryAfter: 2` and `ephemeral: true`) answers a new pairing (`pair` with a code, or `labs.pair.hello`) while the helper is still doing its one Keychain read at launch: the trust list and this Mac's key are not loaded yet, so nothing is decided and no `labs.pair.hi` is sent. The client MUST keep whatever pairing / token / pinned key it has, show "the Mac is still starting", and retry after `retryAfter` seconds. It does not count towards the `rate` limit.

The client keeps `{id, token}` (web remote: `localStorage`, per origin; Android app: keyed by `hostId`, token in the Android Keystore). Reconnects use `auth` only; the token never crosses the wire again after `paired`.

`hostId` is random per Mac, generated once and kept in the helper's preferences. It is an identifier, not a secret: it lets a client recognise the same Mac at a LAN IP, `<name>.local` and a tailnet address, and match a Bonjour result to a saved host before connecting. Clients must still authenticate; a matching `hostId` proves nothing.

### 1.1 Pairing link (QR)

The helper's pairing window shows the code and a QR of:

```
http://<lan-ip or name.local>:8790/#code=123456&id=<hostId>[&ts=<tailnet host>]
```

The web remote reads `code` from the fragment and ignores the rest. On Android it doesn't auto-submit the code; it offers "Open in the Mote app", a `mote://pair?h=<host:port>&code=…&id=…[&ts=…]` link (Android 12+ opens unverified `http` links only in the browser, so the app can't claim the `http` URL itself). The Android app accepts that link, or the pasted `http` link, and also reads `id` and `ts` (MagicDNS name such as `mac.tailnet.ts.net`, or the `100.x` IP; only present while Tailscale connections are allowed), so a phone paired on the LAN already knows the tailnet address.

## 2. Input messages (only after `authed`)

### key

```json
{"t": "key", "usage": 80, "mods": 1, "action": "tap"}
```

- `usage`: USB HID Keyboard page (0x07) usage id, e.g. 4 = A, 40 = Return, 41 = Escape, 42 = Backspace, 43 = Tab, 44 = Space, 58-69 = F1-F12, 74 Home, 75 PageUp, 76 ForwardDelete, 77 End, 78 PageDown, 79 Right, 80 Left, 81 Down, 82 Up, 104-111 = F13-F20. Same numbers as the Android HID path, so macros are portable.
- `mods`: bitmask, HID modifier byte layout: 1 control, 2 shift, 4 option, 8 command. 256 = fn (helper only; plain HID can't send it).
- `action`: `tap` (default: modifiers down, key down, key up, modifiers up), `down`, `up`.

Keys are positional (the key at that place on a US layout). For characters, use `text`.

### text

```json
{"t": "text", "s": "Grüße, 東京 👋\n"}
```

Typed with `CGEventKeyboardSetUnicodeString`, so it is layout-independent and handles any Unicode. `\n` is sent as Return and `\t` as Tab. Max 2000 UTF-16 units per message.

### mouse

```json
{"t": "move", "dx": 12, "dy": -3}
{"t": "button", "b": "left", "action": "click", "count": 1}
{"t": "scroll", "dx": 0, "dy": 40}
{"t": "clickAt", "x": 0.5, "y": 0.5, "unit": "norm", "display": 0, "b": "left", "count": 1}
```

- `move`: relative, in points. Clamped to the union of displays. While a button is held (`button down`), moves are posted as drags.
- `button`: `b` is `left` | `right` | `middle`; `action` is `click` (default), `down`, `up`; `count` (1-3) sets the click state for double/triple clicks.
- `scroll`: pixels. `dy > 0` moves the view down (like Page Down), `dx > 0` moves it right. The client decides "natural" direction.
- `clickAt`: absolute and precise. Warps the cursor (`CGWarpMouseCursorPosition`) then clicks. `unit: "norm"` (default) means `x`, `y` in 0..1 of display `display` (0 = main display); `unit: "pt"` means global display points (origin top-left of the main display). `b` and `count` as for `button`; `count: 0` only moves.

### consumer

```json
{"t": "consumer", "key": "play"}
```

`key`: `play` (play/pause), `next`, `prev`, `volup`, `voldown`, `mute`, `brightup`, `brightdown`, and (protocol 1.2) `fast`, `rewind`. Posted as `NSEvent` `systemDefined` subtype 8 `NX_KEYTYPE_*` key down/up (shows the system HUD). Brightness falls back to the private `DisplayServices` framework for the built-in display if the NX key didn't change it (checked 150 ms later); if neither is available, `err` code `unsupported`.

`action` (protocol 1.2, optional): `tap` (default: key down then up), `down` (press and hold; the helper re-posts it with the key-repeat bit every 100 ms after 300 ms, like a held media key), `up` (release the held key). Only one key is held at a time; a new `down` releases the previous one, and pausing or the last session closing releases it. The app sends `fast` / `rewind` `down` when prev/next is held past 400 ms (hold-to-seek) and `up` on lift. Older helpers reject `fast` / `rewind` with `err` `bad`; taps are unchanged.

### macro

```json
{"t": "macro", "steps": [
  {"t": "key", "usage": 44, "mods": 8},
  {"t": "wait", "ms": 300},
  {"t": "text", "s": "Safari"},
  {"t": "key", "usage": 40}
]}
{"t": "cancel"}
```

`steps` is any list of the input messages above plus `wait` (`ms`, ≤ 5000). Limits: ≤ 64 steps, ≤ 10 s of total wait. Steps run in order on the helper with a minimum 10 ms gap. `cancel` stops a running macro. Only one macro runs per helper at a time; a new one cancels the old one. Pausing input also cancels.

Gesture tiles (the Spaces "hold & slide") are client-side: each slide step sends `key` ⌃← / ⌃→, a tap sends ⌃↑.

### spaces (protocol 1.3, per display since 1.4)

macOS Spaces ("desktops") per display, for the app's Spaces carousel. All four messages need an authenticated session (`err` `unauth` otherwise).

```json
{"t": "spaces.sub", "on": true}
{"t": "spaces", "count": 4, "current": 2, "thumbs": [3, 0, 7, 0], "capture": "on",
 "display": "37D8832A-2D66-02CA-B9F7-8F30A301B230", "shared": false,
 "displays": [
   {"id": "37D8832A-2D66-02CA-B9F7-8F30A301B230", "name": "Built-in Retina Display", "count": 4, "current": 2, "thumbs": [3, 0, 7, 0]},
   {"id": "9C1B5E2F-0A4D-4F55-8E61-2B7A1D0C9E33", "name": "DELL U2720Q", "count": 2, "current": 1, "thumbs": [8, 0]}]}
{"t": "spaces.thumb", "i": 1, "display": "9C1B5E2F-0A4D-4F55-8E61-2B7A1D0C9E33"}
{"t": "spaces.thumb", "i": 1, "display": "9C1B5E2F-0A4D-4F55-8E61-2B7A1D0C9E33", "v": 8, "w": 320, "h": 180, "mime": "image/jpeg", "data": "/9j/4AAQ..."}
{"t": "spaces.goto", "i": 2, "display": "9C1B5E2F-0A4D-4F55-8E61-2B7A1D0C9E33"}
```

| Message | Direction | Fields |
|---|---|---|
| `spaces.sub` | client -> helper | `on` (bool, default true). `true`: the helper replies with `spaces` now and sends it again on every change while subscribed. `false`: stop (no reply). Closing the session unsubscribes. |
| `spaces` | helper -> client | `count`: desktops on the active display (0 = unknown; 1.3: main display). `current`: 1-based index of its current desktop (0 = unknown, or a full-screen app's Space is showing). `thumbs`: `count` integers, the version of the cached thumbnail per desktop (index 1 first; 0 = none yet, "not visited"). `capture`: `on`, `off` (the user turned "Share Space thumbnails" off), `denied` (no Screen Recording permission yet), `unavailable` (macOS < 14 or no capture API). 1.4: `displays`: one entry per display in macOS order (at most 8), each with `id` (display UUID, or `Main` when shared), `name` (`NSScreen.localizedName`; `All displays` when shared), `count`, `current`, `thumbs` (as above, per display, at most 16 desktops). `display`: `id` of the active display (absent when unknown). `shared`: true when "Displays have separate Spaces" is off (one entry, `Main`, for all screens). |
| `spaces.thumb` | client -> helper | `i`: 1-based desktop index. `display` (1.4, optional): display `id`; default the active display. |
| `spaces.thumb` | helper -> client | `i`, `display` (1.4), `v` (version, as in `thumbs`), `w`, `h` (pixels), `mime` (`image/jpeg`), `data` (base64). `err` `notfound` if there is none for `i` or the display is unknown, `bad` for a malformed `i` or a non-string `display`, `unsupported` over BLE. |
| `spaces.goto` | client -> helper | `i`: 1-based desktop index. `display` (1.4, optional): display `id`; default the active display. No reply on success. Input rules apply (`paused`, `ax`). `err` `bad` if `i` is outside 1..`count` of that display (1..16 when the count is unknown) or `display` isn't a string, `notfound` for an unknown display, `unsupported` if the current Space is unknown and there is no Switch-to-Desktop shortcut for it. |

`spaces` is sent when a client subscribes, when the Space changes, when the active display changes, when a display or desktop is added or removed, when a thumbnail is captured, and when the toggle, permission or cache changes. Only full desktops count: full-screen app Spaces are skipped in `count` and `current`. A client fetches a thumbnail when its `thumbs` version differs from the one it has; versions are unique across displays, only increase and are only meaningful within one helper run, so clients drop their cache when the connection ends (a 1.3 client keyed by index alone therefore refetches correctly after the active display changes).

**Active display** (1.4): the display holding the frontmost app's frontmost window (the one with keyboard focus; window bounds from `CGWindowListCopyWindowInfo`, which needs no Screen Recording), else the display under the mouse cursor, else the main display. The helper re-reads it on `NSWorkspace.activeSpaceDidChangeNotification`, `didActivateApplicationNotification` and `NSApplication.didChangeScreenParametersNotification` (debounced 150 ms), and polls once a second while a client is subscribed (clicking another window of the same app, or moving the cursor to an empty desktop, posts no notification). With "Displays have separate Spaces" off there is one shared list and `display` is `Main`.

**Index and count** come from private CoreGraphics (SkyLight) functions, `CGSCopyManagedDisplaySpaces` (one entry per display with its "Current Space") and `CGSGetActiveSpace`, looked up with `dlsym` at run time. If macOS removes them, the helper reports `count: 0`, `current: 0` and keeps working (`goto` then needs the ⌃N shortcuts).

**Thumbnails** are captured with ScreenCaptureKit (`SCScreenshotManager`, macOS 14+) per display (the `SCDisplay` of that display), about 600 ms after a display's Space changes (after the slide animation), and once per display when a client subscribes. JPEG, 320 px wide, quality 0.6; if a frame would make the message larger than 60 KiB the helper re-encodes it at quality 0.4, then 0.25, so a `spaces.thumb` message always fits the existing 64 KiB message cap (no cap was raised). Capturing starts after the first `spaces.sub` of a helper run and covers every Space visited from then on. Over BLE the `spaces` state works but `spaces.thumb` returns `unsupported` (too slow, and BLE writes aren't encrypted).

**Screen Recording permission** is requested lazily: the first `spaces.sub` (not launch) triggers the macOS prompt. Until it is granted `capture` is `denied`; macOS may need the helper restarted after granting. The menu shows "Space thumbnails: allowed" or "Space thumbnails: grant…" (opens System Settings), a "Share Space thumbnails" toggle (persisted, default on) and "Clear Space thumbnails".

**`goto`**: "Switch to Desktop N" numbers desktops across displays in macOS order (display 1's desktops, then display 2's, ...), so for desktop `i` of a display the helper computes N = desktops on the displays before it + `i`. If that shortcut (`com.apple.symbolichotkeys`, keys 118-126 for N = 1-9) is enabled, the helper sends it (as configured, ⌃N by default). Otherwise it steps with ⌃← / ⌃→ from that display's current index, |target − current| times, 80 ms apart. ⌃← / ⌃→ act on the display under the cursor, so if the cursor is on another display the helper first moves it to the centre of the target display with `CGWarpMouseCursorPosition` (no click, no focus change) and starts stepping 100 ms later; the cursor stays there. Limitation: this relies on macOS stepping the cursor's display; if a macOS version only follows keyboard focus, stepping a non-focused display needs a click on it, which the helper deliberately never sends (enable the ⌃N shortcuts instead). In `--dry-run` it logs `DRY spaces goto=N from=M via=shortcut|step display=ID` (and `DRY spaces warp display=ID` instead of moving the cursor) and the `DRY key` lines.

**Privacy.** Thumbnails are pictures of the screen: anything on the desktop (mail, messages, passwords in view) can be in them.

- They travel like every other message: **unencrypted on Wi-Fi/LAN** (anyone who can sniff the network can see them) and WireGuard-encrypted over Tailscale. Only authenticated sessions get them.
- They are kept **in memory only**, never written to disk (no cache file; a persisted cache wasn't needed because the helper recaptures on the next visit). Quitting the helper, "Clear Space thumbnails", turning "Share Space thumbnails" off, and "Revoke all devices" drop them.
- Turn "Share Space thumbnails" off (or deny Screen Recording) to get the numbered-card carousel without pictures.

## 2.1 Diagnostics (v1.5)

Three additions, all optional and backward compatible. A helper older than 1.5 answers `diag` / `diag.subscribe` with `err {code: "unknown"}`; a client treats that as "no diagnostics" and must not retry. Both new messages need `authed` (before that: `err unauth`).

### Envelope: `corr`, `step`, `client`

Any client message may carry `corr` (string, 1-64 printable ASCII chars; anything else is ignored). The helper (a) echoes it as `corr` on every direct reply to that message (including `err`), and (b) stamps it on every event it emits while handling that message. Pushed messages not caused by a client message carry no `corr`. `step` (same rules) is the client's journey step name; the helper stamps it on events as `journeyStep`. Clients use one `corr` per user action to join the Android and helper logs.

`auth` and `diag` / `diag.subscribe` may carry `client: {app, proto}` (`app` ≤ 64 printable ASCII chars such as `"mote-android"`, `proto` ≤ 16 chars such as `"1.5"`). The helper only shows them in `sessions[]` of the snapshot.

### `diag` (client -> helper)

`{"t":"diag","corr":"c1","client":{"app":"mote-android","proto":"1.5"}}`. Reply: the snapshot, `{"t":"diag","v":1,"corr":"c1", ...}`. `MoteHelper --diag` prints the same object (see below; `source` is `"running"` with `pid` and `writtenAt` when it relays the running helper's snapshot file, else `"cli"` with a `note`) and the menu item "Diagnostics…" shows it.

Rules for reading it: every value is a fact the helper read without UI, or a conclusion drawn from facts. Never a secret. `null` = not applicable / not known. Unknown keys must be ignored. Never show a Grant / Open Settings button for state `unknown`; show "Can't check: macOS doesn't report this".

```
{
  "t": "diag", "v": 1, "at": <unix ms>, "source": "ws" | "cli" | "menu" | "setup" | "running",
  "running": bool,            // false only for `MoteHelper --diag` falling back to its own probe (a CLI process, not the running helper): runtime sections are "unknown", and a `note` explains why the running helper's file was not used. `source: "running"` adds `pid` and `writtenAt` (unix ms): the running helper writes this snapshot to `~/Library/Application Support/dev.mote.helper/diag.json` (0600) at launch, on change (debounced 1 s) and every 30 s, and `--diag` relays it if the pid is alive and the file is < 60 s old; TCC answers are per process, so this is the only way to see the running app's view from a shell
  "helper": { "version", "protocol": "1.5", "pid", "uptimeS", "dryRun", "headless", "memoryStore", "port", "hostId", "macOS": "15.1.0", "arch": "arm64"|"x86_64" },
  "features": { "<name>": { "state": "ok"|"blocked"|"off"|"unknown", "blockedBy": code|null, "fix": code|null, "detail": string|null } },
  "signing": { "identity": "developer-id"|"apple-development"|"mote-local"|"self-signed"|"adhoc"|"other"|"unknown",
               "adhoc", "hardenedRuntime", "identifier", "teamId", "cdhash", "designatedRequirement", "authorities": [string],
               "bundlePath", "executablePath", "quarantined", "translocated", "expectedKeychainPartition": "teamid:<T>"|"cdhash:<h>"|null, "error"?: OSStatus },
  "permissions": { "<perm>": { "state","blockedBy","fix","detail" /* as a feature */,
                     "api": string, "raw": string, "apiState": "granted"|"denied"|"notDetermined"|"unknown",
                     "checkedAt": unix ms, "ms": number, "detail": {string: string},
                     "inferred"?: true, "inferredFrom"?: string, "applies"?: bool } },
  "keychain": { "service", "account" /* this build's secrets.v4.<partition> */, "otherAccounts": [string] /* other builds' vault items, never touched */, "memoryOnly", "readStatus": OSStatus|null, "readResult": "found"|"notfound"|"error"|"unreadable"|"memory",
                "writeStatus": OSStatus|null, "itemCount": int|null, "partitionAtWrite": string|null, "expectedPartition": string|null,
                "partitionMatchesSigning": bool|null,
                "ephemeral": bool, "ephemeralReason": "reading"|"denied"|"error"|"corrupt"|"write-failed"|"no-identity"|"dry-run"|null,
                "interactiveRead": "none"|"skipped"|"n/a"|"ok"|"denied"|"error" },
  "transports": { "wifi": {"listening": bool|null, "state", "port", "error", "connections", "accepted", "refused"},
                  "bonjour": {"state", "errorCode", "type": "_mote._tcp", "disabled"},
                  "ble": {"enabled", "status", "advertising"},
                  "tailscale": {"allowed", "name", "address"} },
  "sessions": [ { "deviceName", "transport": "Wi-Fi"|"Bluetooth", "authed", "connectedS",
                  "protocol": {"helper": "1.5", "client": string|null, "clientApp": string|null},
                  "subscriptions": {"spaces","context","nowPlaying","bridge","diag"}, "liveScreenViewer": bool } ],
  "labs": { "liveScreen": {"enabled","streaming","viewer"}, "context": {"enabled"}, "nowPlaying": {"enabled","source"} /* core, on by default since 0.7; menu "Share now playing" */,
            "tvMode": {"enabled","showing"}, "bridge": {"enabled","status","connected"}, "spaceThumbnails": {"enabled"}, "instantPairing": {"enabled"} },
  "update": { "enabled": bool, "current": string, "latest": string|null, "available": bool, "checkedAt": unix ms|null, "last": "never"|"ok"|"error: <reason>" } | null,
  "events": { "head": <last seq>, "buffered": n, "capacity": 5000 }
}
```

`<perm>` is one of `accessibility`, `bluetooth`, `localNetwork`, `screenRecording`, `automation`. `features` keys: `input`, `keychain`, `wifi`, `bonjour`, `bluetooth`, `tailscale`, `spaceThumbnails`, `labs.liveScreen`, `labs.context`, `labs.nowPlaying`, `labs.tvMode`, `labs.bridge`. `state`: `ok` works; `blocked` wanted but something stops it (`blockedBy` and `fix` set); `off` the user (or a flag) turned it off, or nothing uses it; `unknown` the helper cannot tell.

`update` (helper 0.7+) is the daily release check: the helper asks GitHub `releases/latest` at most once a day (no auth, nothing downloaded), shows "Mote Helper X.Y.Z is available" in the menu when `latest` is newer than `current`, and the user can turn it off under Advanced. Failures are silent: the `lifecycle/update.check` event carries `result: error` and `reason`. Test flags (need `--dry-run`): `--update-url URL`, `--update-check-now`; `--fake-ax-denied` makes Accessibility read as not granted (`state.ax` false, `key` -> `err ax`). The Setup window labels `localNetwork` "Probably allowed" / "Unknown" (never "Allowed"), because macOS lets no app read it.

`localNetwork` is the one permission with no macOS API: its `state` is inferred from the Bonjour registration of the helper's own listener (registered = `ok`; `PolicyDenied` -65570 = `blocked`; anything else `unknown`), and `inferred` is true. `api`/`raw` are the unmodified OS answers (e.g. `CGPreflightScreenCaptureAccess` / `"false"`), so a "granted but the app says no" case can be diagnosed from the snapshot alone.

`blockedBy` codes: `accessibility-not-granted`, `bluetooth-not-granted`, `bluetooth-denied`, `bluetooth-off`, `local-network-denied`, `screen-recording-not-granted`, `automation-not-granted`, `input-paused`, `keychain-unreadable`, `keychain-corrupt`, `listener-waiting`, `listener-failed`, `bonjour-failed`.

`keychain` while the vault is unreadable: `blocked`, `blockedBy: keychain-unreadable` (list or read error, denied or failed interactive read; `keychain-corrupt` for an unparseable item; `keychain-write-failed` when writing this build's account failed), `fix: repair-keychain` ("Keychain: Repair" on the Mac: delete this build's own account and recreate it, phones pair again; other builds' accounts are left alone), `detail` starts with "Pairings aren't being saved: Keychain unavailable". `ephemeral: true` means: secrets are in memory only, the Keychain is never written, no trust is automatic (every phone needs an explicit Allow on the Mac). `interactiveRead`: after a no-UI read of another build's account fails because the partition does not match the signing identity, the helper asks macOS once per identity and item modification date (`ok` = read and copied into this build's account, `denied` / `error` = ephemeral and not asked again for this item version, `skipped` = already asked for this identity and item version, `n/a` = the failure is not one a prompt can fix, `none` = not needed). `fix` codes (what a UI may offer): `grant-accessibility`, `grant-bluetooth`, `grant-screen-recording`, `grant-automation` (macOS shows its own prompt), `open-bluetooth-settings`, `open-local-network-settings`, `open-automation-settings` (open the System Settings pane), `turn-bluetooth-on`, `resume-input`, `enable-lab`, `enable-tailscale-setting`, `repair-keychain`, `free-port`, `restart-helper`.

### `diag.subscribe` / `diag.events` (event stream)

`{"t":"diag.subscribe","on":true,"since":0,"corr":"c2"}` starts the stream (`on:false` stops it; closing the connection also stops it). `since` (optional): replay buffered events with `seq > since` first (at most the newest 1000); without it only new events are sent. The helper acknowledges with a `diag.events` message that holds the replay (possibly empty) and `"subscribed": true`, `head` (last `seq` at that moment), `capacity`. After that, events arrive as `diag.events` batches (flushed every 100 ms, at most 100 events per message, oldest first):

`{"t":"diag.events","events":[<event>,...],"head":N,"subscribed"?:bool,"capacity"?:5000,"dropped"?:n}`

`dropped` is present when the helper had to discard `n` events for this client because it fell behind (the 1000-event outbox overflowed). `seq` is strictly increasing with no reuse; a gap in `seq` means events were dropped or coalesced. `diag.events` messages are never themselves logged as events.

Event object (the same JSON is written one per line to `--events <file>`):

```
{ "t": <unix ms>, "seq": <int>, "side": "helper", "area": <area>, "event": <name>,
  "journeyStep"?: string, "corr": string|null, "fields": { ... } }
```

`fields` carry sizes, counts, status codes and names; never secrets (tokens, keys, nonces, pairing codes, typed keys, message payloads). Events go to a 5000-event ring buffer, to `os_log` (subsystem `dev.mote.helper`, category = area; `log stream --predicate 'subsystem == "dev.mote.helper"'`) and to subscribers. High-rate events (`protocol.in` / `protocol.out`, `labs.push`, `labs.stream`) are coalesced to at most one per second per key; the next one carries `fields.skipped`. Input is never logged per event: `input.summary` every 5 s with `fields.counts`.

Areas and events (a unit test keeps this table equal to the code):

| Area | Events |
|---|---|
| `lifecycle` | `start`, `ready`, `terminate`, `setting`, `diag.cli`, `login.item`, `update.check` |
| `signing` | `identity`, `error` |
| `permission` | `check`, `change`, `request` |
| `keychain` | `read`, `write`, `list`, `accounts`, `partition`, `interactive`, `copy`, `ephemeral.enter`, `ephemeral.leave`, `repair`, `reread` |
| `discovery` | `bonjour.attempt`, `bonjour.registered`, `bonjour.failed`, `ble.state`, `ble.advertising`, `ble.failed`, `tailnet.detect` |
| `transport` | `listen.state`, `conn.accepted`, `conn.refused`, `conn.closed`, `ws.upgrade`, `ws.refused`, `ble.central`, `http.response` |
| `session` | `open`, `authed`, `auth.failed`, `paired`, `pair.failed`, `closed`, `revoked` |
| `protocol` | `in`, `out`, `error`, `bad`, `diag.subscribe` |
| `input` | `summary`, `blocked`, `macro` |
| `labs` | `toggle`, `subscribe`, `push`, `stream` |
| `ui` | `setup.shown`, `setup.closed`, `setup.button`, `menu.opened`, `menu.item`, `diagnostics.shown`, `diagnostics.copied`, `pairing.shown`, `newdevice.shown` |

The Android app emits the same event shape with `side: "app"` into its own log (areas and names are its own); the shared `corr` joins both sides.

## 3. BLE transport

| | UUID |
|---|---|
| Service | `3abad8f4-b472-41fc-bfc5-6f645f33564b` |
| RX characteristic (client writes; write and write-without-response) | `0b43b8f5-113a-4034-8055-9f5b26e475e8` |
| TX characteristic (helper notifies) | `b0904884-8e1d-4296-86a3-40fff3fafb55` |

The helper advertises the service UUID with local name `Mote`. Each central (`CBCentral.identifier`) is its own session; `hello` is sent when the central subscribes to TX. Unsubscribing ends the session.

### BLE framing

Every GATT write / notification is one fragment: byte 0 is a flags byte, the rest is a slice of the UTF-8 JSON.

- flags bit 0 (`0x01`): more fragments follow. A fragment without it ends the message.
- other bits: reserved, must be 0.

Writers keep fragments ≤ `MTU - 3` bytes (the web remote uses 180 B; the helper uses `maximumUpdateValueLength`). A reassembled message over 64 KiB is dropped and the session is reset.

## 4. Security and threat model

The helper turns network messages into keystrokes and clicks on the Mac, so an unauthenticated message must never reach the injector.

### What it defends against

| Threat | Defense |
|---|---|
| Anyone else on the LAN (or in BLE range) sending input | Every input message is rejected until the session is `authed`. Auth needs a token issued only through pairing. |
| Pairing by someone who isn't at the Mac | Pairing is only accepted while the pairing window is open (opened from the menu, closes after 5 minutes or after one device pairs). The 6-digit code is random per window (`SecRandomCopyBytes`) and shown only on the Mac's screen (text + QR). A successful pairing rotates it. |
| Brute-forcing the 6-digit code | Per peer (IP address, or BLE central id): 5 failed `pair`/`auth` attempts per minute, then `err rate` with `retryAfter`. Globally: 10 failed pairing attempts close the pairing window and invalidate the code, so a fresh code needs a human at the Mac. Expected guesses per window ≤ 10 out of 10^6. |
| Token theft by sniffing reconnects | The token is sent once (in `paired`). Reconnects prove possession with HMAC-SHA256 over a fresh per-connection nonce, so a captured `auth` can't be replayed. |
| Tokens at rest on the Mac | Device tokens (the HMAC keys) and names are stored in the login Keychain (generic password, service `dev.mote.helper`), not in files. |
| A lost or untrusted phone | Menu > "Revoke all devices" deletes all tokens and drops all sessions. |
| "Something is typing on my Mac" | Menu > "Pause input" (kill switch) drops all input at once and cancels a running macro. The menu lists connected clients. |
| Exposure beyond the LAN | The listener refuses cellular interfaces, and every connection whose remote address isn't loopback, RFC 1918, link-local or IPv6 ULA/link-local is closed before any byte is read. Tailscale's ranges (100.64/10, which carriers also use for CGNAT, and `fd7a:115c:a1e0::/48`) are refused unless "Allow Tailscale connections" is on. Nothing is forwarded or exposed via UPnP. |
| A web page in the phone's (or Mac's) browser driving the helper (cross-site WebSocket hijacking, DNS rebinding) | The WebSocket upgrade needs an `Origin` equal to the page's own `http://<Host>` (native clients send none) and a `Host` that is `<name>.local`, an IP literal or `localhost`; with Tailscale allowed, also `*.ts.net`. Tailnet IP literals in `Host` are refused while Tailscale is off. A foreign page has neither the token (stored per origin) nor the code. |
| Screen contents leaking | Space thumbnails (1.3) go only to authenticated sessions, need Screen Recording permission requested on first use, can be switched off in the menu, and live in memory only (see section 2, spaces). |
| Resource exhaustion | ≤ 32 concurrent connections (8 per address), 10 s to send the HTTP request, 5 min to authenticate, 8 KiB HTTP headers, 64 KiB messages, 1 MiB of unread replies (a peer that doesn't read is dropped), macro limits above, static files only (no directory listing, no path traversal). |

### Tailscale

Menu > Advanced > "Allow Tailscale connections" (persisted, default off when never set, which since 0.7 also covers an upgrader who never chose;  `--allow-tailscale` forces it on for one run). When on:

- peers in 100.64/10 and `fd7a:115c:a1e0::/48` are accepted, and `Host` may be a MagicDNS name (`*.ts.net`) or a tailnet IP literal;
- the menu shows `http://<tailnet host>:8790` when it can detect it (MagicDNS name from the Tailscale CLI, else the `utun` 100.x address), and the pairing link carries `ts=`.

Turning it off drops connected tailnet peers. Bonjour doesn't cross a tailnet, so clients enter the address or learn it from the pairing link. Plain `ws` is acceptable here: tailnet traffic is WireGuard-encrypted end to end. Every device in the tailnet (including shared-in ones, under default ACLs) can reach the port, so app-level auth and pairing limits still apply; restrict port 8790 with Tailscale ACLs if the tailnet is shared.

### What it does not defend against (known limits)

- **No transport encryption on Wi-Fi.** It's plain `http`/`ws` (a LAN page can't get a trusted TLS certificate). An attacker on the same network who captures the pairing exchange gets the token. Someone who can tamper with traffic (ARP spoofing) can inject into an authenticated session. Pair on a network you trust. Prefer BLE or a trusted home network over cafés and hotels.
- **BLE writes aren't link-encrypted** (the GATT characteristics don't require bonding, so Web Bluetooth works without an OS pairing dialog). Same app-level auth as Wi-Fi, same limits.
- **A paired phone has full keyboard and mouse control**, the same as a USB keyboard. Revoke it if it's lost.
- **Ad-hoc code signature.** The helper isn't notarized. macOS ties the Accessibility grant (and the Keychain item ACL) to the code signature, and every update has a new ad-hoc signature, so after an update you may need to re-grant Accessibility (System Settings > Privacy & Security > Accessibility: remove MoteHelper, then add it again) and click "Always Allow" on a Keychain prompt.
- The helper doesn't run at the login window or the lock screen. Use the Android HID mode for that.

## 5. Building and testing the helper

```sh
helper/build.sh                     # -> helper/build/MoteHelper.app, MoteHelper-macos-{arm64,x86_64}.zip + .sha256
B=helper/build/MoteHelper.app/Contents/MacOS/MoteHelper
$B --dry-run --headless --no-ble --memory-store --open-pairing --port 8791   # prints PAIRING CODE: nnnnnn
node helper/tests/smoke.mjs 8791 <code>                                       # auth, guards, Host rules, hostId, rate limit, dry-run input
# Tailscale rules on: start the helper with --allow-tailscale, then
MOTE_TAILNET=1 node helper/tests/smoke.mjs 8791 <code>
```

Test-only flags: `--port 0` binds any free port (the log line `wifi: listening on :N` has the real one); `--stdin-control` (honoured only with `--dry-run`) reads `pair` (open a pairing window, print `PAIRING CODE: nnnnnn`), `revoke`, `pause`, `resume`, `display N` (see `--fake-spaces`) from stdin, standing in for the menu in headless runs. `e2e/web/` uses both.

`--fake-spaces N[,M...]` (honoured only with `--dry-run`) simulates one display per number (ids `fake-1`, `fake-2`, ..., names `Fake Display 1`, ...) with that many desktops (current 1, active display `fake-1`), generated solid-colour thumbnails and no Screen Recording permission, so tests never capture the real screen; `spaces.goto` then also switches the fake current desktop (and, on another display, makes it active, as the cursor warp would). With `--stdin-control`, `display N` makes fake display N active and pushes `spaces`.

Chaos-only seams (dry-run): `--no-bonjour` (no `_mote._tcp` advertisement, stands in for a network that blocks multicast), stdin `tailscale on|off` (the menu toggle) and `displays N,M,...` (a simulated display hot-plug for `--fake-spaces`). See `chaos/RUBRIC.md`.

`--dry-run` logs every input (`DRY key usage=80 mods=1 ...`) instead of posting events, so tests never type into the session. `--memory-store` keeps paired devices out of the Keychain; `--keychain-service NAME` uses a separate Keychain item.
