# Chaos and robustness tests

`chaos/helper/` attacks the Mac helper (`helper/`) over its Wi-Fi WebSocket transport: network faults, protocol fuzzing, load storms and process kills. Plain Node (>= 20), no dependencies; the fault proxy is a small TCP proxy in `lib/proxy.mjs` (no toxiproxy).

Every helper it starts runs with `--dry-run --headless --no-ble --memory-store` on a random port (20000-45000). `--dry-run` logs each input as a `DRY ...` line instead of posting events, so nothing is ever typed or clicked. The harness counts those lines to check auth and exactly-once invariants.

## Run

```sh
ARCHS=arm64 helper/build.sh                         # builds helper/build/MoteHelper.app
node chaos/helper/run.mjs --budget 10m --seed 42    # all scenarios, summary table, exit 1 on failure
node chaos/helper/run.mjs --budget 90s --only fuzz,auth
node chaos/helper/run.mjs --keychain                # also SIGKILL during Keychain writes (see below)
```

- `--budget` (`90s`, `10m`, `1h`) is split across scenarios by weight: fuzz 40%, net 25%, load 15%, process 15%, spaces 8%, auth 5% (relative weights). `spaces` fuzzes the protocol 1.3/1.4 Spaces messages (including the `display` field) against a two-display `--fake-spaces 4,3` helper whose active display it flips over `--stdin-control`. Fixed-size checks run even with a tiny budget. Time-boxed loops (fuzz cases, proxy rounds) scale with it.
- `--seed N` makes every random choice reproducible: fault profiles, fuzz cases (per worker stream), kill timings. The seed is printed at the start and the end. Timing-dependent outcomes (which frame a 20% drop hits) can still vary between runs.
- `MOTE_HELPER_BIN=/path/to/MoteHelper` tests another build. `CHAOS_VERBOSE=1` prints fuzz crashes as they happen.
- The runner kills every helper it started on exit or Ctrl-C.

## Scenarios

| Scenario | What it does | Invariants checked |
|---|---|---|
| `auth` | Pre-auth input, replayed HMAC, MAC over a stale nonce, valid MAC with an unknown id, second `auth` on an authed session, 600 nonces, `ping` with `-1e400` | `err unauth`/`auth` replies, nothing injected before auth, 32-hex distinct nonces, helper alive |
| `load` | 200 concurrent connects; wrong pairing codes from 24 sockets on 3 peer addresses at once; wrong MACs from 24 sockets; reconnect storm (18 loops, FIN/RST/close); 1k msg/s `move` flood for 5 s + a 20k burst | <= 8 per peer and <= 32 accepted, refusals closed cleanly; exactly 10 wrong codes evaluated, then the window closes; <= 5 failures per peer; no auth bypass from a new socket; nothing executed twice; flood p99 queue latency < 500 ms on the same and on another session; every move executed once |
| `net` | Rounds of 4 clients through the fault proxy: latency 0-800 ms + jitter, 1-30% WebSocket frame drop, 2 KB/s-200 KB/s caps, ending with FIN, RST mid-frame or half-open. Then 300 RST-after-message connections, HTTP slowloris (8 drippers), WS slow drip (1 B / 15 ms), unauth WS drip, slow readers (JSON ping and WS ping frames), 4000 pipelined HTTP GETs without reading | Observer on another address keeps p99 < 500 ms and loses no ping; no pre-auth or partial-frame marker ever executes; no marker executes twice; slowloris dropped within ~10 s while other peers still authenticate; slow readers are dropped and memory stays bounded; sockets/fds drain to the listener |
| `process` | SIGSTOP 2.5 s mid-session then SIGCONT; 3 rounds of SIGKILL while streaming input, restart on the same port; with `--keychain`, up to 8 rounds of SIGKILL during a storm of auths (each rewrites the Keychain item), then restart | Nothing answered while stopped, everything queued answered and executed exactly once after; client sees the close, reconnects to the restarted helper; with `--keychain` the stored token still authenticates and there are no Keychain read/write errors |
| `fuzz` | 3 workers (one per loopback address) send grammar-based and mutated v1 messages: wrong types, huge numbers, `1e400`, `NaN`, nesting up to 30k levels, 70 KB strings, lone surrogates, invalid UTF-8, BOM, duplicate keys, unknown ops, macros at the limits. Framing: text, binary, fragmented with interleaved pings, continuation-first, unmasked, reserved opcodes, 2^53 length, > 64 KiB frames and fragment sums, RSV bits, close. Phase 1 has no paired device, phase 2 is authenticated. | Never crashes (a crash restarts the helper and records the batch), never hangs (a sentinel `ping` after each batch must return within 5 s), every complete unauthenticated message gets an `err`/`pong` or a close, 0 `DRY` events in the unauthenticated phase, every acknowledged marker executed exactly once, memory stays under +192 MB (footprint series printed every 5 s) |

Every scenario ends with the shared checks: helper alive, a fresh client gets `hello` and `pong`, TCP sockets drain back to the listener only, fds return to the baseline, and the footprint stays within bounds. Memory is measured with `footprint`, which counts compressed pages. RSS hides them: an unbounded queue of compressible data looked flat in RSS.

The "peers" are `127.0.0.1`, `::1` and `fe80::1%lo0`. The helper sees them as three different addresses, which is how the suite exercises the per-address rate limit and connection caps from one machine.

## Bugs found (fixed in `helper/`, with regression checks)

1. **Pre-auth crash, security-relevant (high).** `{"t":"ping","n":-1e400}` from any LAN host killed the helper (SIGABRT). Foundation parses `-1e400` as `-inf`, and writing it back in the `pong` raises an Objective-C exception that `try?` doesn't catch. `ping` is allowed before auth, so one unauthenticated frame was enough (BLE too). Fix: `Session.send` checks `JSONSerialization.isValidJSONObject` first, and `ping` echoes `n` only if it is writable. Regression: `auth` (`pre-auth ping n=-1e400`), plus `fuzz`.
2. **Socket/fd leak on RST, security-relevant (high, DoS).** A connection that failed (peer RST) was removed from the server's table but never `cancel()`ed, so Network.framework kept its socket (lsof: `CLOSED`). An unauthenticated client looping "connect, send, RST" leaked about 20 fds/s and dodged the 32-connection cap. At the default 256-fd soft limit the helper stops accepting connections. Fix: cancel on `.failed`, and finish on `.cancelled`. Regression: `net` ("RST after a message") and `load` (reconnect storm drain).
3. **Unbounded send queue, security-relevant (high, DoS).** A peer that floods `ping` (or WebSocket ping frames) and never reads makes the helper queue every reply. It grew by about 560 MB in 4 s and is unauthenticated. Fix: a connection with more than 1 MiB of unsent WebSocket data is closed. Regression: `net` ("slow reader", both variants).
4. **Per-connection timers lived for 5 min (medium, memory).** Each session scheduled its 5-minute auth deadline with `asyncAfter`, which can't be cancelled. Every closed connection therefore kept a dispatch timer and closure, and Network.framework kept its per-connection state, for 5 minutes. Memory grew with the connection rate: about +220 MB at ~575 connections/s during the 10-minute fuzz run, then freed. Fix: an invalidatable `Timer`, cancelled on auth or close. Regression: `load` ("no per-connection timers left behind", via `heap`), plus the fuzz footprint bound.

## Known limits (documented, not changed)

- **Half-open connections.** An authenticated session whose peer silently disappears (Wi-Fi drop, no FIN/RST) is held until the helper next writes to it and TCP gives up. There is no idle timeout and no keepalive. Unauthenticated sessions are dropped after 5 min. Each held socket counts toward the per-address cap of 8, so a phone that drops off the network 8 times without a clean close can be locked out for a while. `net` reports `halfOpenEstablished`. A possible fix is TCP keepalive on the listener or an app-level `ping` with an idle timeout.
- **Unauthenticated WebSocket slot holding.** After the HTTP upgrade, an unauthenticated session may stay idle for the 5-minute auth window. 4 LAN addresses x 8 connections fill the 32 global slots, as the threat model's resource limits allow.
- **The rate limit is per address.** A rate-limited address is refused even with a valid MAC for up to 60 s, so a NAT'd or shared address can lock out a legitimate device. `load` asserts this behavior.
- **No coalescing of `move`.** Every move is executed in FIFO order. Latency stays bounded because each one is cheap: about 0.5 ms p50 at 1k msg/s and about 150-300 ms to drain a 20k burst.
- **Clock skew.** Not applicable. The HMAC challenge is a random per-connection nonce with no timestamp. The rate limiter's 60 s window uses wall-clock `Date`, so a clock step can shorten or lengthen a block. That isn't tested because changing the clock needs root.
- **Token persistence.** There is no file store. Tokens live in the login Keychain, or in memory with `--memory-store`, which loses them on restart (`process` asserts the clean `err auth`). `--keychain` tests the real Keychain path with a throwaway service name (`dev.mote.helper.chaos.<seed>`) and deletes the item afterwards. It is opt-in because it touches the login Keychain.
- **BLE is not covered.** These tests use `--no-ble`. BLE shares `Session`, so bug 1 also applied there.
