# Labs: Live screen (preview)

The Mac is 6+ ft away and everything on it is small. This preview shows a low-rate live picture of
the Mac's **active display** on the phone, and lets you click on it:

| Gesture on the picture | On the Mac |
|---|---|
| Tap | Left click there (cursor moves there) |
| Second tap within 300 ms, near the first | Double click (a count-2 click at the first tap's point) |
| Long-press | Right click |
| Two-finger pinch / pan | Zooms and pans the *picture* (up to 6x), not the Mac |

The phone draws the Mac's cursor as a ring (the capture itself hides it) and, in debug builds, a
`fps · ms · KiB` badge (frame rate, helper-measured frame round trip, last JPEG size).

Off by default, on both sides:

| Where | Toggle |
|---|---|
| Android | Settings › Labs › *Live screen (preview)*. Adds *Screen (preview)* to the ⋯ menu. |
| Helper | Menu bar › Labs › *Live screen (preview)*, plus *Pause screen sharing*. `--fake-screen` (with `--dry-run`) forces the lab on for one run. |

While a phone is viewing, the menu-bar item reads **● Sharing screen**, the menu shows *Sharing screen
with <device>* and *Stop sharing screen*. Pausing (menu) stops the stream and refuses new ones.

These messages are a Labs extension. They are **not** part of [docs/protocol.md](../protocol.md)
and do not change its version. A helper without this preview answers them with `err` `unknown`.

## Privacy

- Needs Screen Recording (the same permission as Space thumbnails). Starting a stream without it
  asks macOS for it and answers `err` `denied`.
- Streams only to an **authenticated** session over Wi-Fi or Tailscale (BLE: `err` `unsupported`),
  only to **one** viewer at a time, and only while the phone's viewer is open: the phone sends `stop`
  when the page closes, the app goes to the background or the user pauses; the helper also stops when
  the session drops or nothing arrives for 2 s (the lease), so a vanished phone stops it within ~2 s.
- Frames go over plain Wi-Fi **unencrypted** (Tailscale encrypts them). The phone shows a one-time
  warning before the first stream over a non-Tailscale address.
- Frames are kept in memory only (the newest captured image, one in encoding); nothing is written to disk.
- A dry run never captures the real screen; `--fake-screen` streams synthetic frames.

## Messages

Client → helper (authenticated; otherwise `err` `unauth`):

| `t` | Fields | Notes |
|---|---|---|
| `labs.screen.start` | `w` 480-960 (default 800), `fps` 2-15 (default 10), `credits` 1-3 (default 2) | Starts or restarts. A start from another session takes over (the old one gets `stopped` `taken`). Replies `labs.screen.started`. |
| `labs.screen.ack` | `seq` | Returns that frame's credit. Also the heartbeat: the phone sends it every 0.5 s (unknown `seq` = no credit, lease refreshed). |
| `labs.screen.click` | `x`, `y` 0..1 (frame-normalised, top-left origin), `button` `left`/`right`, `count` 1/2, `display` (from the frame) | Input: refused while input is paused (`paused`) or without Accessibility (`ax`). Before a stream: `notstreaming`. |
| `labs.screen.stop` | | Ends the stream; replies `stopped` `stop`. |

Helper → client:

| `t` | Fields |
|---|---|
| `labs.screen.started` | `display`, `w`, `h`, `fps`, `credits`, `encrypted` (Tailscale), `fake` |
| `labs.screen.frame` | `seq`, `display`, `w`, `h`, `data` (base64 JPEG), `q` (JPEG quality), `fps` (current target), `rtt` (ms, EWMA of send → ack), `ts` (capture time, Unix ms), `cx`/`cy` (cursor, normalised; absent when it is on another display) |
| `labs.screen.stopped` | `reason`: `stop` \| `timeout` (lease) \| `taken` \| `paused` (Pause screen sharing) \| `off` (lab turned off) |

Errors (`err`, `ref` = the message): `labs-off`, `sharing-paused`, `denied`, `unavailable` (plain dry
run), `unsupported` (BLE), `notstreaming`, `notfound` (no frame yet), `bad`.

## Flow control and rate

- **Credits**: each frame costs one; `ack` returns it. With no credit the newest image waits and is
  replaced by the next capture, so frames are **dropped, never queued**. The phone acks after decoding,
  so a slow phone paces the stream too.
- **Backlog guard**: a frame is also skipped while more than 384 KiB are unsent on the socket, so a
  client that acks without reading can't push the session to the 1 MiB unread cap. A frame message is
  at most 240 KiB (quality steps down until it fits), so in-flight data stays under ~720 KiB.
- A credit whose ack never comes is reclaimed after 2 s.
- **Adaptive rate** (once a second): any dropped frame or a round trip over 250 ms steps fps down
  (x0.75, to 2), then JPEG quality (0.55 → 0.3), then width (−160 px, to 480). Round trips under
  120 ms with nothing dropped step back up (width first, then fps +2 and quality +0.05).
- **Unchanged screen**: ScreenCaptureKit only delivers *complete* frames when something changed, so a
  still screen sends nothing (the heartbeat keeps the lease). Changed-tile deltas were considered and
  not built: a full 960 px JPEG of a real desktop is 40-120 KiB and encodes in a few ms, the still-screen
  case already costs nothing, and tiles would need a compositor on the phone. Revisit if bandwidth on
  real Wi-Fi turns out to matter.

## Coordinates

Frames always cover the whole display, so a normalised point maps to the display's bounds in global
CoreGraphics **points** (origin top-left of the main display, y down; other displays can have negative
origins): `p = (minX + x·width, minY + y·height)`, clamped inside the display. The backing scale
(Retina 2x) never enters: frame pixels are converted by normalising first. The helper keeps the
geometry of recently streamed displays by id, so a tap on a frame from before an active-display switch
still lands on the display it showed. On the phone, the tap is first mapped back through the zoom /
pan and the letterboxing of the fitted image (`ViewMath.toNorm`); taps on the bars are ignored.

The fake display (`--fake-screen`) is 1512x982 pt at (−1512, −200), scale 2: a Retina display left of
and above the main one, so tests cover both the scale and the offset.

## Tests and numbers

- `helper/tests/unit.sh`: Swift Testing for the mapping (Retina, offsets, clamping), rate control and JPEG budget.
- `node helper/tests/screen.mjs`: lab off by default, auth, frames + acks, exactly `credits` frames
  without acks, lease timeout, stop / disconnect / take-over, pause sharing, lab toggle, click mapping.
- `node chaos/helper/run.mjs --only screen`: slow link (60 KB/s), a stalled reader that acks blindly
  (backlog guard, no 1 MiB close), half-open viewer (lease), fuzzed `labs.screen.*`.
- Android: `ScreenTest` (messages, letterbox / zoom / pan mapping, double taps) and
  `HelperIntegrationTest.liveScreenFramesClicksAndStop` against a real `--fake-screen` helper.

Dry-run numbers (synthetic 960x623 frames, loopback, M-series Mac under load): capture → received
p50 4 ms, p95 17 ms; ~25 KiB per frame; about 2.2 Mbit/s at ~11 fps while ramping to 15 fps. Through a
60 KB/s link the helper settles at 2 fps without dropping the session. Real-screen numbers need a device.
