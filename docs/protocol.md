# Mote helper protocol, v1.1

One JSON message protocol shared by both transports of the Mote Helper (macOS menu-bar app):

- **Wi-Fi** (LAN, or a tailnet when allowed, see [Tailscale](#tailscale)): WebSocket at `ws://<host>:8790/ws` (same origin as the web remote served at `http://<host>:8790/`). One JSON message per text frame. Bonjour type `_mote._tcp`, TXT record `id=<hostId>`, `v=1`.
- **BLE**: GATT service on the Mac (the helper is the peripheral, the phone is the central). Same JSON, fragmented (see [BLE framing](#ble-framing)).

All messages are JSON objects with a type field `t`. Unknown fields are ignored. Unknown `t` gets an `err`. The protocol version is announced in `hello` (`"v": 1`); a peer that sees a different major version closes the connection. Minor versions only add optional fields, so `v` stays 1.

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

Error codes: `auth` (bad/unknown token), `code` (wrong pairing code), `pairing-closed`, `rate` (too many attempts, see `retryAfter`), `unauth` (input before auth), `paused`, `ax` (Accessibility not granted), `bad` (malformed), `unknown` (unknown `t`), `revoked`, `unsupported` (e.g. no way to set brightness).

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

`key`: `play` (play/pause), `next`, `prev`, `volup`, `voldown`, `mute`, `brightup`, `brightdown`. Posted as `NSEvent` `systemDefined` subtype 8 `NX_KEYTYPE_*` key down/up (shows the system HUD). Brightness falls back to the private `DisplayServices` framework for the built-in display if the NX key didn't change it (checked 150 ms later); if neither is available, `err` code `unsupported`.

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
| Resource exhaustion | ≤ 32 concurrent connections (8 per address), 10 s to send the HTTP request, 5 min to authenticate, 8 KiB HTTP headers, 64 KiB messages, macro limits above, static files only (no directory listing, no path traversal). |

### Tailscale

Menu > "Allow Tailscale connections" (persisted, default off; `--allow-tailscale` forces it on for one run). When on:

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

`--dry-run` logs every input (`DRY key usage=80 mods=1 ...`) instead of posting events, so tests never type into the session. `--memory-store` keeps paired devices out of the Keychain; `--keychain-service NAME` uses a separate Keychain item.
