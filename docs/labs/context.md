# Labs: Smart remote (context and now playing), preview

The phone adapts to what is on the Mac. Netflix gets skip intro and fullscreen. YouTube and other web video get fullscreen, theater, next, captions and speed. A focused text field opens the keyboard. A now-playing card has a scrubber you drag to seek.

Since 0.7 the two halves are separate on the helper:

- **Now playing** (`labs.np.*`) is a core feature, **on by default**. Menu › "Share now playing" turns it off (UserDefaults `nowPlaying.enabled`, default true; event `lifecycle/setting{key:"nowPlaying"}`). Off: `labs.np` answers `enabled: false`, `labs.np.seek` answers `err unsupported`, and the reader is stopped. The wire format is unchanged. (Android gating on the Labs switch is the app's concern.)
- **Context** (`labs.ctx.*`, the front app / browser tab) is still a preview and **off by default**: menu › "Context (frontmost app, preview)" (`labs.context.enabled`); Android: Settings › Labs › "Smart remote (preview)" (`Labs.SMART_REMOTE`, key `smart_remote`). The `js` seek path needs context; `mediaremote` and `keys` do not.

The messages below are not part of [protocol.md](../protocol.md) and do not change its version. A helper without the lab answers `labs.ctx.*` / `labs.np.*` with `err` `unknown`. A helper with the feature turned off answers with `enabled: false`.

## Messages

Every message needs an authenticated session. `*.sub` messages are reads, so pause and Accessibility don't apply to them. `labs.np.seek` is input: it is refused with `paused` / `ax` like `key`.

| Message | Direction | Fields |
|---|---|---|
| `labs.ctx.sub` | client -> helper | `on` (bool, default true). The helper replies with `labs.ctx` now, and again on every change while subscribed. |
| `labs.ctx` | helper -> client | `enabled` (false: the lab is off, no other fields). `app` (name), `bundle` (bundle id), `browser` (bool), `host` (active tab host, browsers only), `url` and `title` (only when `full` is true), `text` (the focused element takes text), `secure` (password field), `automation` (`granted` / `ask` / `denied` / `n/a`: Apple Events permission for that browser), `full` (the privacy toggle). |
| `labs.np.sub` | client -> helper | `on` (bool, default true). The helper replies with `labs.np` now, and again on every change. |
| `labs.np` | helper -> client | `enabled`, `source` (`mediaremote` / `fake` / `starting` / `unavailable` / `off`), `now` (Mac clock, ms), `seek` (the seek paths that could work now, best first: `mediaremote` or `fake`, then `js`, then `keys`). `np` is null when nothing plays; otherwise it has `title`, `artist`, `album`, `app`, `bundle`, `duration` (s), `elapsed` (s, true at `at`), `rate` (0 = paused), `playing`, `at` (Mac clock, ms) and `art` (base64 JPEG, 192 px or less, under 40 KB). |
| `labs.np.seek` | client -> helper | `to` (absolute seconds) or `by` (relative seconds, at most 3600). |
| `labs.np.seek` | helper -> client | `via` (`mediaremote` / `fake` / `js` / `keys`), `to` (the target, when known), `note` (a hint, e.g. how to allow JavaScript). Errors: `bad`, `paused`, `ax`, `unsupported` (Share now playing off). |

**Clocks.** The client extrapolates the position as `elapsed + (now - at) / 1000 * rate` when the message arrives, then follows its own monotonic clock. Only the Mac's clock is used, so the phone's clock may be wrong.

**Debounce.** A context has to stay the same for 250 ms before it is pushed. Only changes are pushed. The helper checks focus and the front app every 400 ms while a client is subscribed, and right away on `didActivateApplication`. It reads the browser tab at most once a second, on a background queue.

## Now-playing notification (Android)

This is a core feature, on by default (Settings › Media notification turns it off; no Labs gate). While the phone is linked to a helper (Wi-Fi / Tailscale) that reports now playing, the app posts a media notification (`MediaSession` + `MediaStyle`, `VISIBILITY_PUBLIC` so the lock screen shows it): title, artist, art, previous / play-pause / next and, on Android 13+, a seek bar. Its buttons send the same `consumer` and `labs.np.seek` messages as the Media screen. It is held by `NpService`, a foreground service of type `mediaPlayback` (manifest permission `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, required on Android 14+), which also keeps the helper socket open while the app is in the background (`Net.holdOpen`; without it Net closes the socket 30 s after the app leaves the screen). The service and notification stop when nothing plays, the Mac disconnects, the setting is turned off, or playback has been paused for 10 minutes (`NpNotifPolicy.PAUSED_STOP_MS`).

- **Bluetooth only (no helper):** a transport-only notification "Mote · Bluetooth" with previous, play/pause (a toggle) and next as HID consumer keys. No metadata, no state, no seek. It is posted by the existing link service (`HidService`), not `NpService`.
- **Android 13+ notification permission:** asked once in first-run setup (a card with a sentence saying why; "Not now" is remembered), and again in context from a row under the now-playing card. Denied: no notification, and Settings › Diagnostics shows the permission and "Now-playing notification: blocked: notifications are not allowed for Mote".
- **Helper side off:** if the helper answers `labs.np` with `enabled:false`, the plan is `helper-np-off` (Diagnostics says so). If neither `labs.ctx` nor `labs.np` arrives within 2 s of subscribing, the Media screen shows the blocker `no-reply` (U19).
- Events (`notification`: `show`, `update`, `hide` with `reason`, `blocked`, `action`): see the Events catalog.
- Tests: `NpNotifPolicyTest` (L0), `NpNotifIntegrationTest` (dry-run helper with a fake reader), `NpNotificationJourney` (emulator: `dumpsys notification` / `media_session`, MediaController callbacks, the helper's `DRY consumer` / `DRY labs seek` lines). Lock screen, Bluetooth headset buttons and tapping the shade's buttons are L4 (manual).

## How the helper reads context

- **Front app:** `NSWorkspace.frontmostApplication`.
- **Text input (Accessibility):** the system-wide `AXFocusedUIElement` has role `AXTextField` / `AXTextArea` / `AXComboBox` / `AXSearchField` (subroles `AXSearchField` / `AXSecureTextField`), or it has an `AXEditableAncestor`, or it is an `AXWebArea` / `AXGroup` with a settable `AXValue` (contenteditable). Chromium browsers only build their web accessibility tree on request, so the helper sets `AXManualAccessibility` on them once per process. Messaging timeout is 0.3 s.
- **Browser tab (AppleScript):**
  - Safari: `URL` / `name` of `current tab`.
  - Chrome, Arc, Edge, Brave, Vivaldi: `URL` / `title` of `active tab`.
  - Firefox has no tab scripting, so only the window title is read.
- **Automation permission is asked lazily.** `AEDeterminePermissionToAutomateTarget` runs without asking first. The first time a scriptable browser is in front while a phone is subscribed, the helper asks once and macOS shows its prompt. The menu shows "<Browser> tabs: allowed / asks on first use / denied, open Settings…". The app's hardened runtime needs the `com.apple.security.automation.apple-events` entitlement (`helper/MoteHelper.entitlements`) and `NSAppleEventsUsageDescription`.
- **Privacy:** the menu toggle "Share full URLs and window titles" is off by default. While it is off, only the host is sent (`www.youtube.com`), with no path, query or titles. Context travels like every other message: unencrypted on the LAN, WireGuard over Tailscale.

## Now playing: the spike (macOS 15.4+)

Tested on **macOS 26.6 (25G72), Apple silicon**, with Arc playing a track:

| Path | Result |
|---|---|
| `MRMediaRemoteGetNowPlayingInfo` / `...ApplicationPID` from an ad-hoc signed binary (the helper's case), looked up with `dlsym` | **Blocked.** The callback gets `nil` info and pid 0 while music plays. Since 15.4, `mediaremoted` only answers clients whose bundle id starts with `com.apple.`. |
| The same calls from a dylib loaded into `/usr/bin/perl` (reported as `com.apple.perl`) through `DynaLoader` | **Works.** Full info came back: title, artist, album, duration, elapsed, rate, timestamp, artwork, and the now-playing app (Arc, `company.thebrowser.Browser`). `MRMediaRemoteSetElapsedTime` and `MRMediaRemoteSendCommand` resolve. |

The workaround is the one [ungive/mediaremote-adapter](https://github.com/ungive/mediaremote-adapter) uses. Its licence is BSD-3-Clause; Mote borrows the idea only, and no code was copied. Mote's version is `helper/LabsNowPlaying/`:

- `MoteNowPlaying.m` (about 150 lines) is compiled by `build.sh` into `Contents/Resources/labs/MoteNowPlaying.dylib` (ad-hoc signed).
- `mote-nowplaying.pl` loads it and calls `mote_np_main`.
- The helper runs `/usr/bin/perl mote-nowplaying.pl <dylib>` only while the lab is on and a phone subscribes. The reader prints one JSON line per change; the helper writes `seek <s>` to its stdin.
- If the reader exits 4 times within a minute, the helper reports `unavailable`.

Risks:

- Apple can close the perl loophole in any update, for example by library validation for `/usr/bin/perl` or a stricter `mediaremoted` check. The panel then shows no now-playing card, while quick actions and `keys` seek keep working.
- The dylib is ad-hoc signed and loaded into a system binary. Some endpoint-security tools may flag that.
- Seeking through MediaRemote was not run against real playback during the spike: it would have changed the user's media, and tests never inject. The call resolves, and the path is covered with the fake session.

## Seek paths

1. **MediaRemote** (`labs.np.seek` -> `MRMediaRemoteSetElapsedTime`), when something plays and reports a duration. Browsers with Media Session support (YouTube in Chrome / Arc / Safari) accept it.
2. **Browser JavaScript.** When MediaRemote has no position and a scriptable browser is in front, the helper sets `currentTime` on the largest `<video>` with AppleScript (`execute javascript` / `do JavaScript`).
   - Chrome-family browsers need View › Developer › "Allow JavaScript from Apple Events".
   - Safari needs Develop › "Allow JavaScript from Apple Events" (turn on Settings › Advanced › "Show features for web developers" first).
   - When the browser refuses, the reply's `note` explains this, and the helper falls back to keys.
   - Netflix is skipped, because its player rejects `currentTime` writes.
3. **Arrow keys:** `round(|delta| / 5)` taps of Right or Left (at most 24, 40 ms apart). Most web players step 5 s per press.

## Android

Code is in `android/.../labs/context/`:

- `Rules.kt`: the rules table `ContextRules.match(ctx, nowPlayingBundle)` and the `AutoKeyboard` edge logic.
- `NowPlaying.kt`: `NpReducer`, the now-playing state reduction (anchoring, pending seek, artwork reuse).
- `SmartRemote.kt`: the subscription, over the shared `LabsBus` / `Remote.sendLabs`.
- `SmartPanel.kt`: the UI.

Hooks into shared screens:

- `SmartPanel()` sits above `MediaScreen` in `App.kt`.
- `SmartRemoteEffects` runs the subscription and the auto-keyboard.
- `AutoKeyboardChip()` sits above Keys.
- A `FocusRequester` on the Keys type row brings up the soft keyboard.

Profiles (first match wins):

| Match | Quick actions |
|---|---|
| `netflix.com` in any browser, or the Netflix app | Skip intro (S), Fullscreen (F), Exit full (Esc), Mute (M). Netflix on the web has no subtitles shortcut. |
| `youtube.com`, `youtu.be` | Fullscreen (F), Theater (T), Next (⇧N), Captions (C), Slower / Faster (⇧, / ⇧.) |
| Video sites (Vimeo, Twitch, Prime Video, Disney+, Hulu, Max, Apple TV web, …), VLC | Fullscreen (F), Play (space), −5 s / +5 s (arrows) |
| QuickTime, IINA, TV | Fullscreen (⌃⌘F), Play, −5 s / +5 s |
| Any other browser tab that is the now-playing source | Same as video sites |
| Anything else (Finder, …) | Nothing: the default Media screen, plus the now-playing card if something plays |

**Auto-keyboard.** When `text` turns true, the app switches to Keys and focuses the type row. When `text` turns false, it goes back to the previous mode, but only if it opened Keys itself. "Stop auto-keyboard" turns it off (Labs store key `smart_remote.auto_keyboard`); the panel header offers "turn on" again.

## Tests

- `node helper/tests/labs-context.mjs [port]` starts its own `--dry-run` helpers. It covers:
  - fake context and fake now playing, and the privacy default;
  - seek via fake, JS (logged only) and keys (`DRY key`);
  - the 250 ms debounce and coalescing;
  - unauthenticated sessions and the lab being off by default.
- `--fake-context FILE` (dry-run only) reads `{app, bundle, url, title, text, secure, browser?, automation?}` from FILE and re-reads it when it changes. `--fake-nowplaying` (dry-run only) is a 300 s "Fake Song" that plays. In dry run without `--fake-nowplaying`, the real reader never starts, so tests can't touch real media. `--fake-nowplaying-reader EXE` (dry-run only, chaos) runs EXE in its place to test crash loops and garbage output; reader lines over 256 KiB are dropped.
- Android: `RulesTest`, `NowPlayingReducerTest`, and `LabsContextIntegrationTest` (real dry-run helper, app codecs).
