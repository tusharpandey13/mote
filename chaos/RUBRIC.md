# Chaos rubric

The single definition of "chaos-pass" for Mote. Both runners score against it:
`node chaos/helper/run.mjs` (Mac helper, dry-run) and `chaos/android/run.sh` (Android app, emulator).
Each failing check is a **finding** with a severity (section 1) and, where it applies, the
**invariant** it breaks (section 2). Budgets (section 3) turn latency, reconnect, memory and frame
numbers into pass/fail.

**A run is CHAOS-PASS when:** every invariant that was exercised passes, every budget is within its
limit, there are no S1 or S2 findings, and every S3/S4 finding left open is either reproduced and
filed or attributed with evidence (section 3.5). PENDING items (features not built yet, detected at
run time) are listed but are not failures.

## 1. Severity

| | Meaning | Examples found here |
|---|---|---|
| **S1** | Security, data loss, a crash loop, or the user's device left changed. Fix before anything else ships. | **Pre-auth crash**: `{"t":"ping","n":-1e400}` from any LAN host killed the helper (one unauthenticated frame). **fd leak**: connect, send, RST leaked ~20 fds/s and dodged the connection cap until the helper stopped accepting (unauthenticated DoS). **Unbounded memory**: a peer that floods `ping` and never reads grew the helper ~560 MB in 4 s. **Corrupt-DataStore crash loop**: one bad file crashed the app on every launch, fixable only by clearing data. **Wi-Fi left off**: a chaos run once left the user's Wi-Fi off. |
| **S2** | A feature broken or doing the wrong thing, wrong input, a non-looping crash, a lost user change. | **Mistyped prefs crash loop** (a value stored with another type; S2 because it needs a schema change or a hand edit to reach, S1 if a normal upgrade can). **RTL mirroring**: in Arabic/Hebrew the remote swapped L/R mouse buttons, next/prev and volume +/-. **Lost settings update**: a quick second settings change wrote the first one's old value back. **goto overshoot** (this pass): a Spaces goto during the previous slide sent both key runs. |
| **S3** | Degraded or racy behaviour with a workaround; timing-dependent; a stale indicator that heals itself. | **Double-tap on load** (web remote): a first tap within 300 ms of page load was sent as a double click. **Hold-to-seek clock race**: hold-to-seek re-read the clock when its timer fired and, under a clock running ahead, never started. **Stale Spaces state** (this pass): a change picked up by `goto` was never pushed. **Unbounded now-playing line** (this pass, local process only). |
| **S4** | Cosmetic. | Font scale 2.0 clipped fixed-height rows. |

When unsure, pick the higher severity. A finding reachable before authentication is at least S1 if it
crashes, hangs or exhausts the helper, whatever its effect when authenticated.

## 2. Invariants

These must hold under every fault, on every transport. A check names the invariant it guards
(`chk.ok(cond, msg, detail, 'S1 I1')` in the helper suite; the `inv` column in `chaos/android/out/*/results.tsv`).

| ID | Invariant | How it is checked |
|---|---|---|
| **I1** | **No input is injected before auth.** Not by a pre-auth message, a forged or replayed handshake, a bridge client, a denied or revoked device. | `--dry-run` logs every input as a `DRY` line; markers (`MK...`) sent before auth, after revoke or from the bridge must never appear (`auth`, `fuzz`, `trust`, `labs`). |
| **I2** | **No duplicate execution.** Every acknowledged input runs exactly once (reconnects, floods, kills, retries); a goto's keys add up to its target. | Marker counts == 1; DRY arrow keys sum to the last goto (`load`, `net`, `process`, `fuzz`, `displays`). |
| **I3** | **Bounded memory, fds and queues.** Per-connection state is freed; unread replies are capped (1 MiB); frames are dropped, never queued; reader lines are capped. | `footprint` (counts compressed pages, unlike RSS), `lsof` fds back to baseline +2, sockets drain to the listeners, `heap` timers (every helper scenario); PSS growth and Activity counts (Android monkey). |
| **I4** | **Streams stop on disconnect within budget** (section 3.3): live screen, Space thumbnails, bridge state, now-playing reader, tailnet sessions after "Allow Tailscale" goes off, sessions after revoke/Block. | Log lines `screen: stopped (...)`, socket close times (`screen`, `labs`, `trust`, `discovery`). |
| **I5** | **No secrets or screen frames are written to disk.** Tokens and keys live in the Keychain / Android Keystore only; frames and thumbnails in memory only. | `disk`: no regular file held open for writing; no token or frame bytes in any file written during the run (TMPDIR, /tmp, ~/Library caches, prefs, app support, saved state). Android: app files after a run (planned, see matrix). |
| **I6** | **Auto-trust only per the trust rules** (docs/pairing.md section 4): a known key; the same Tailscale owner per the local tailscaled (never tagged nodes, never on a CLI failure); a fresh, unreplayed proximity token within ±1 window (after the one-tap rework); or the user's Allow. Nothing else. Revoke and Block take effect at once. | `trust` (forged, replayed, cross-host, malformed, deny cool-down, whois other/timeout, spoofed Mac, proximity cases, Block), `discovery` (tailnet refused while off). |
| **I7** | **The app never crash-loops on any persisted state** (corrupt, truncated, wrong type, hostile but valid, deleted). | Android `storage`: two launches per corruption must come up with defaults. Helper: n/a (UserDefaults + Keychain, `--keychain` kills during writes). |
| **I8** | **The UI is never silent on a failure.** Every failure maps to a visible state: an error, a "waiting"/"unavailable" state, a gate, a banner. A hang without a message is a failure. | Android `network`, `resource` (gate after a permission revoke), `connect`; helper protocol states the UI renders (`err auth` after a restart, `labs.video.state waiting`, `labs.np source=unavailable`, `tv-off`, `labs.pair.denied`). |
| **I9** | **The user's device state is restored after any test**: Wi-Fi, Bluetooth, data, VPN, airplane mode, locale (system and app), font scale, density, rotation, night mode, animation scales, stay-awake, Doze, battery saver, standby bucket. | Android: snapshot at run start, diff after every scenario and at exit; any difference fails the run (S1). |
| **I9a** | **The harness never touches a device it doesn't own.** Android runs target one emulator by serial (`-s emulator-…`) on the default adb server. The harness must not start its own adb server; if any adb server it starts or uses for isolation lists a non-emulator (USB) device, the run fails (S1) before any command. A private server on another port once claimed the user's USB phone so their own adb couldn't see it. | `chaos/android/run.sh` guard (serial + `ro.kernel.qemu`, refuses physical devices without `--allow-physical`, refuses a private adb server that enumerates USB). |
| **I10** | **Liveness**: no crash, ANR or hang; every complete message gets an answer or a close; a fresh client always gets `hello` and `pong`. | Shared post-scenario checks (helper); crash/ANR harvest from logcat and dropbox (Android). |

## 3. Pass/fail thresholds

### 3.1 Crashes and ANRs

| Suite | Budget |
|---|---|
| Helper, any scenario | 0 crashes. A crash from unauthenticated input is S1. A fuzz crash restarts the helper and records the batch for replay. |
| Android, per scenario | 0 `FATAL EXCEPTION` / dropbox crashes. 0 ANRs outside `monkey`. |
| Android `monkey` | ≤ 2 ANRs per 50k events, and only if each is attributed (the trace has no app frames, e.g. the `-gpu guest` render stall); otherwise fail. 0 crashes. |

### 3.2 Latency (p95 unless stated)

| Transport | Budget | Measured by |
|---|---|---|
| LAN WebSocket, unloaded | ping p95 ≤ 50 ms; `move` queue p99 < 500 ms at 1k msg/s | `load`, `discovery` |
| LAN under faults (other sessions) | observer ping p99 < 500 ms; no lost ping | `net`, `screen`, `labs`, `discovery` (toggle storm) |
| Tailnet WebSocket | ping p95 ≤ 150 ms direct, ≤ 400 ms via DERP | `discovery` (own tailnet address; loopback-fast, so this only bounds helper overhead) |
| BLE GATT (helper) | write → `DRY` p95 ≤ 150 ms | not covered (chaos runs `--no-ble`); gap |
| Bluetooth HID (phone → host) | report p95 ≤ 30 ms | `physical.sh` only (needs a real phone) |
| Spaces under a switch storm | ping p95 ≤ 250 ms | `displays` |

### 3.3 Reconnect and stop times

| What | Budget |
|---|---|
| Helper restart → `hello` on the same port | ≤ 2.5 s |
| Known key reconnect → `labs.pair.ok via trusted` | ≤ 500 ms |
| Phone: helper restart → re-authenticated on LAN | ≤ 5 s |
| Phone: LAN gone → tailnet | ≤ 15 s (pairing.md section 5 probes for 15 s before falling back) |
| Phone: no helper → Bluetooth HID fallback | ≤ 20 s |
| Phone: LAN back → switch back from BT/tailnet | ≤ 35 s (30 s probe interval + 5 s) |
| Live screen stops after the viewer's socket closes | ≤ 3 s (2 s lease + 1 s); half-open viewer ≤ 3.5 s |
| "Allow Tailscale" off → tailnet sessions dropped | ≤ 1 s |
| Revoke / Block → session dropped | ≤ 1.5 s |
| Now-playing reader crash loop → `source: unavailable` | ≤ 12 s (backoff 1+2+3 s) |
| Bridge extension gone → phone sees `waiting` | ≤ 3 s |

### 3.4 Live screen frames

- Frames are **dropped, never queued**: a stalled reader that acks blindly never reaches the 1 MiB unread cap, and the helper reports dropped frames.
- A 60 KB/s link keeps the stream alive (> 5 frames in 12 s) and the helper lowers fps.
- One viewer at a time: after a take-over storm the non-owner receives 0 frames.
- With every Labs panel active at once, frames keep arriving at ≥ 3 fps (interval ≤ 333 ms) on loopback.
- Memory: footprint growth < 96 MB per scenario (< 192 MB for `fuzz`); fds back to baseline + 2.
- Android PSS: fail at > 30 % **and** > 20 MB growth (first vs last quarter of monkey).

### 3.5 Flakes and quarantine

- **A failure counts until it is explained.** Rerun with the same `--seed` (at most twice). If it reproduces, it is a finding: fix it or file it with its severity. If it does not, it still fails the run unless it is **attributed with evidence**: a log line, trace or measurement showing a cause outside the product (e.g. the monkey ANR traces with no app frames on `-gpu guest`, a whole-machine stall visible in the observer RTTs). Write the attribution next to the finding (README "Bugs found" table).
- **Quarantine** is for S3/S4 only, with an issue link and an expiry of at most 14 days. A quarantined check still runs and prints; it just doesn't fail the run. S1/S2 checks are never quarantined.
- Timing checks retry once inside the scenario when the machine is loaded (e.g. the half-open lease), and say so in a `note`.

### 3.6 Required seed and budget

| Where | Helper | Android |
|---|---|---|
| Local, before a PR touching `helper/`, `android/.../net/` or `site/remote/` | `--budget 3m --seed 42` | `--budget 10m --seed 7` on an emulator |
| CI / nightly | `--budget 10m --seed <run number>`; print the seed | `--budget 20m --seed <run number>` |
| Before a release | `--budget 30m` with 3 seeds, plus `--keychain` once | `--budget 1h`; `physical.sh` by hand (real HID) |

Use the seed printed by a failing run to reproduce it. Fixed-size checks run even with a tiny budget;
loops (fuzz cases, proxy rounds, storms) scale with it.

## 4. Scenarios added for this rubric

Helper (`chaos/helper/scenarios/`):

| Scenario | Covers | Pending until (feature detection) |
|---|---|---|
| `discovery` | Bonjour advertised vs `--no-bonjour` (multicast blocked) and a direct connect still works; a LAN sweep field of 28 hostile listeners (silent/half-open, slow, drip, garbage, RST, plain HTTP, wrong app) around one helper; 60 sweep probes don't lock the phone out; two phones pairing at once; SIGKILL mid-pairing then restart; Tailscale toggle storm over this Mac's own 100.x address | Android sweeper vs the field: `android/.../pairing/Sweep.kt`. Tailnet storm: a local 100.64/10 address. Failover timing and phone Wi-Fi off: Android suite. |
| `trust` | Forged, replayed, cross-host and malformed `labs.pair.hello`; limiter; Allow, known-key reconnect, busy; revoke mid-session; spoofed Mac (other key, same role); Deny + cool-down; fake whois same owner / other owner / CLI timeout | Proximity tokens (fresh, replayed, expired, future, forged, malformed, none; skew ±1 window) and notification Block mid-session: helper flag `--fake-proximity` (one-tap rework). In that mode the prompt path runs with `--require-approval`. |
| `displays` | Active-display switch storm with gotos on both displays; display hot-plug (`displays N,M` on stdin); goto bursts during the slide: last goto wins, keys add up | `displays` stdin command |
| `labs` | Bridge: origin/Host/LAN abuse, pinned origin, input smuggling, 150-cycle reconnect storm, routing, `waiting` after loss. Live screen: close during a frame, take-over storm. Now playing: reader crash loop → `unavailable`, garbage and a 4 MiB line. TV: open/exit/key/open.url churn incl. `javascript:`/`file:`. All panels at once | `--fake-nowplaying-reader`. Phone panel priority (TV > web video > now playing) is inline in `App.kt`: needs a pure selector and a unit test. |
| `disk` | I5 over a session with a token, frames and thumbnails | |

Android (`chaos/android/`): device-state snapshot and restore check after every scenario (I9), an
emulator guard (`ro.kernel.qemu`, refuses a physical serial unless `--allow-physical`), `network`
(Wi-Fi/data/airplane off with a helper host selected: alive and a visible state) and `connect`
(one-tap rework, VPN storm and failover, feature-detected). See `chaos/android/README.md`.

One-tap connect (being built: BLE proximity token, LAN subnet sweep, auto-trust, Wi-Fi first). What
chaos expects once it lands:

- Proximity: a token is accepted once per window, for the current window ±1 only; a replay, an older
  or newer window, a flipped digit or garbage is refused (`stale` / `bad-proximity`), forged ones also
  count towards the per-peer limiter; no token means no auto-trust (`far`) unless the Tailscale rule
  holds. Clock skew between phone and Mac doesn't matter: the phone relays the Mac's own token.
- Sweep: only a host whose `hello.app` is `mote-helper` is offered; each address costs at most its
  per-port timeout (half-open and drip ports can't stall it); probes don't trip the helper's limits.
- Auto-trust: only via a known key, a fresh proximity token, the same Tailscale owner, or Allow.
  Block from the "new device" notice drops the session at once and refuses the key afterwards.
- Wi-Fi first: the phone prefers LAN, then tailnet, then BT, and switches back within section 3.3.

## 5. Coverage matrix

`C` covered, `p` partial, `P` pending (feature-detected, runs once it lands), `G` gap (no test yet), `-` n/a.

Features × transports:

| Feature | LAN WS | Tailnet WS | BLE GATT | BT HID | Loopback bridge |
|---|---|---|---|---|---|
| Code pairing + HMAC auth | C | p | G | - | - |
| Instant pairing / trust | C | p (fake whois) | G | - | - |
| Proximity token | P | - | P | - | - |
| Input (key, text, mouse, macro) | C | p | G | p (physical) | - |
| Consumer / hold-to-seek | p | G | G | p (physical) | - |
| Spaces per display | C | G | G | - | - |
| Live screen | C | G | - (refused) | - | - |
| Video bridge | p (phone side) | G | G | - | C |
| Context / now playing | C | G | G | - | - |
| TV / Open | C | G | G | - | - |
| Discovery / connect | C (Bonjour, sweep field) | p | P | P (fallback) | - |

Features × fault classes:

| Feature | Network (latency, loss, RST, half-open) | Protocol fuzz | Load / flood | Process kill / stop / restart | Persisted state | Device toggles | Concurrency / races | Clock / skew |
|---|---|---|---|---|---|---|---|---|
| Auth / pairing | C | C | C | C | C (`--keychain`, Android storage) | p | C (two phones) | - (nonce, no clock) |
| Trust / instant pairing | G | C | p (limiter) | C (restart mid-pairing) | G | - | C (busy) | P (±1 window) |
| Input | C | C | C | C | - | p | C | - |
| Spaces | G | C | C (thumb hoarder) | G | - | - | C (switch storm, goto bursts, hot-plug) | - |
| Live screen | C (slow link, half-open) | C | C (stalled reader) | p (close during frame) | - | G | C (take-over) | - |
| Video bridge | G | p (abuse) | C (reconnect storm) | G | G | - | p | - |
| Context / now playing | G | G | p (all panels) | C (reader crash loop) | - | - | p | p (Mac clock only) |
| TV / Open | G | C (churn) | p | G | G | - | p | - |
| Discovery / connect | C (sweep field) | - | C (probes) | C | - | C (Tailscale storm; Android Wi-Fi/airplane) | C | - |
| Android UI / stores | - | - | C (monkey) | C (lifecycle) | C (storage) | C (resource, bluetooth, network) | p | G |

Gaps worth closing next: BLE GATT chaos (needs a central; the helper's BLE path shares `Session`, so
fuzz coverage partly carries over), tailnet faults through the proxy, Android I5 (scan app files for
tokens/frames), and the phone's Labs panel priority as a unit test.

## 6. Reading a summary

`run.mjs` ends with: the per-scenario table, **Rubric findings** (count per severity, then each
finding as `S? I? scenario: message`), **Invariants** (PASS / FAIL / n/a per I1-I10 with the scenarios
that exercised it), **Budgets** (actual vs limit), **Pending**, and the verdict line
`CHAOS-PASS` / `CHAOS-FAIL (...)` with the seed. `--json FILE` writes the same data.
`chaos/android/run.sh` writes the same sections to `out/<run>/summary.md`.
