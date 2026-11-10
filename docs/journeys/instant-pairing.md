# instant-pairing: 8. Instant pairing

Artboard: `design/mote-v1/journey-instant-pairing.html` (Preview (Labs); the code ships it as the default since 0.6). Code: `android/.../pairing/BleScan.kt`, `pairing/Finder.kt`, `pairing/Sweep.kt` (mergeFound), `pairing/ConnectUi.kt` (ConnectCard), `pairing/InstantPairing.kt` (connect, target, reconnect, watchForFallback, probeBack, rediscover), `pairing/Handshake.kt`, `pairing/Paths.kt` (selectPaths), `net/Net.kt`, `net/WsTransport.kt`, `ui/HostsUi.kt` (HostPill, failedText), `helper/.../InstantPairing.swift` (ask/showAlert, trust order), `Proximity.swift`, `BLE.swift`. Docs: `docs/pairing.md` §2-§5, §7.
Test: `e2e/journeys` → `dev.mote.remote.journeys.InstantPairingJourney` (one @Test per path below).

Scope split: first-connect failure branches are in `connect-next.md`; this journey covers the BLE-found card, the "Mac asks once" variant the artboard draws (in code: only with Require approval), the Tailscale-owner variant and the silent reconnects (artboard j8-s5). Notation as in `wifi-helper.md`.

## Preconditions
- P1. App: fresh install `dev.mote.remote.dev`; `BLUETOOTH_SCAN` + `BLUETOOTH_CONNECT` granted (BLE discovery only runs when already permitted, `BleScan.permitted()`); seams `TestSeam.noBonjour = true`, `TestSeam.sweepPort = $P`, `TestSeam.fakeProximity = true`, and `TestSeam.ble` (to be added: injects a `NearbyMac` built from the helper's stdout `LABS PAIR INFO: {...}` into `BleScan.found`, since the emulator has no Mote peripheral).
- P2. Phone: Wi-Fi on; Bluetooth adapter on (API ≥ 31).
- P3. Mac / helper: `MoteHelper --dry-run --headless --no-ble --memory-store --port $P --stdin-control --fake-proximity --require-approval --fake-approve`.
- P4. Network: same LAN (10.0.2.2:$P).

## Main path `instant-pairing`
| # | Actor | Action (exact) | Expected observable (exact) | Seam |
|---|---|---|---|---|
| S1 | phone user | Launch Mote near the Mac | `FindMacScreen`; `ConnectCard` title "<Mac>" (BLE info `n`), button "Connect to <Mac>"; `FoundMac.sources` contains BLE (and LAN_SCAN) | faked: `TestSeam.ble` |
| S2 | phone user | Tap "Connect to <Mac>" | phone: "Click Allow on <Mac>. It asks before trusting new devices." | real |
| S3 | Mac user | Alert "Allow “<phone>” to control this Mac?" (copy: connect-next require-approval-allow B3) → Allow | stderr `[mote] labs: prompt <phone> (Wi-Fi 127.0.0.1)`, `[mote] labs: trusted <phone> via approved`, `[mote] authed <phone> via Wi-Fi from 127.0.0.1`, `[mote] labs: new device <phone> via approved (k:<fp>)`; stdout `NEW DEVICE: {"id":"k:<fp>","name":"<phone>","via":"approved"}` | faked: `--fake-approve` |
| S4 | system (phone) | — | setup stays (App.kt `setup`): "Connected over Wi-Fi" + Bluetooth offer; after "Skip": remote, pill "Device: <Mac> via Wi-Fi. Tap to choose", badge "WI-FI" | real |

## Postconditions
- Phone: trust record `via="approved"`, `info` = the BLE info; saved host `lan="10.0.2.2"` (+ `tailnet` = info `ts` when the helper allows Tailscale and has a MagicDNS name).
- Helper: key `via:"approved"`.

## Branches
### `instant-pairing.default-nearby`: no approval (the 0.6 default; artboard draws a prompt instead)
Preconditions delta: helper without `--require-approval --fake-approve`.
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | phone user | Tap "Connect to <Mac>" | no prompt (no `labs: prompt` line); helper `labs: trusted <phone> via nearby`, `NEW DEVICE: {…,"via":"nearby"}`; phone "Connected over Wi-Fi" + Bluetooth offer | faked: TestSeam.fakeProximity + --fake-proximity; card found by the LAN sweep (no BLE seam) |

### `instant-pairing.tailscale-owner` (artboard j8-s4)
Preconditions delta: helper `--fake-tailscale-owner self`, no `--require-approval`, no proximity seam.
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | phone user | Tap "Connect to <Mac>" | helper `labs: fake whois <peer> owner=self`, `labs: trusted <phone> via tailscale`, `NEW DEVICE: {…,"via":"tailscale"}`; phone (adapter present): "Connected over Tailscale" + Bluetooth offer (connect-next-D4) | faked: --fake-tailscale-owner self over the LAN |
| B2 | (variant) | same on first run with `Bt.snapshot().supported == false` | card text "Connected. Trusted via Tailscale" for 900 ms, then the remote: pill "Device: <Mac> via Wi-Fi. Tap to choose" | faked: TestSeam.adapter(supported=false) (no Bluetooth offer) |

### `instant-pairing.reconnect-lan`: later launches (artboard j8-s5 route 1)
Preconditions delta: main path done; force-stop and relaunch the app, helper still running.
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | phone user | Press Home, wait 32 s (the socket closes 30 s after onStop), return to Mote (a force-stop would end the instrumentation; the process stays) | remote screen directly; helper `labs: trusted <phone> via trusted`, `authed <phone> via Wi-Fi from …`; no `NEW DEVICE`, no prompt even with `--require-approval` (known key is checked first) | real (Home + return after the socket's 30 s idle close; the process stays) |

### `instant-pairing.reconnect-new-ip`: Mac's LAN address changed (not drawn)
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | system | Saved `lan` no longer answers while a helper answers elsewhere on the /24 | after 4 s of not-Ready (`REDISCOVER_MS`), one LAN sweep; the hit with the same `hostId` is added (`Net.addAddress`) and the socket is kicked → Ready | faked: needs the helper to move address; on the emulator the trust record's `info.lan` (the Mac's real LAN IPs) stays reachable, so the reconnect never fails (missing fake) |

### `instant-pairing.reconnect-tailnet`: away from home (artboard j8-s5 route 2)
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | system | LAN candidates fail, MagicDNS / 100.x answers | silent `labsHandshake` (allowPrompt=false) on the tailnet address; pill badge "TAILSCALE" | manual: real tailnet; faked: needs the tailnet resolver seam (tailscale.md P2) |

### `instant-pairing.bt-fallback`: no network, Bluetooth HID backup (artboard j8-s5 route 3)
Preconditions delta: saved host has `btAddress` (linked: a bonded host with the same name as the Mac when trust was adopted, `TestSeam.adapter(hosts=[Host(<addr>, "<Mac>")])`), `TestSeam.transport` fake; helper reached through the chaos proxy (`chaos/helper/lib/proxy.mjs`) on $P.
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | system | Proxy cuts the link | `WsStatus` Connecting; after 15 s (`FALLBACK_MS`, app in foreground) selection becomes `bt:<addr>`, `HidService` started, `Hid.select`; pill badge "BT" | faked: proxy + adapter/transport seams |
| B2 | system | Proxy restores the link | within 30 s (`PROBE_MS`) a probe sees the same `hostId` → selection back to `ws:<hostId>`; badge "WI-FI" | faked |

### `instant-pairing.reconnect-refused`: the Mac no longer trusts the phone (not drawn)
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | Mac user | stdin `revoke` (Revoke all) | phone session `err revoked`; pill LED red; Devices note "The Mac no longer trusts this phone. Long-press it to forget, then Add a Mac again." | faked: stdin `revoke` |
| B2 | phone user | Home, then back to Mote | silent handshake gets `labs.pair.denied far` (or `labs.pair.pending` with Require approval → `revoked`) → `WsStatus.Failed("auth")`; same note; never a prompt from a reconnect | real |

### `instant-pairing.same-window-second-phone` (docs/pairing.md §2; not drawn)
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | system | A second new key presents a valid token in the same 30 s window | helper `labs: proximity token replayed by 127.0.0.1`, `labs: denied (stale) 127.0.0.1` (twice: the phone re-reads once in the same window); phone: "The nearby check expired. Try again next to the Mac." | faked: `pm clear` the app after the main path (new key) and reconnect within the window via `TestSeam.fakeProximity` |

## Disagreements → DEFECTS.md
- `instant-pairing-D1` (S1-S3, badge): artboard is a Labs preview where the Mac always asks; code ships it by default and prompts only with Require approval; card and alert copy differ.
- `instant-pairing-D2` (tailscale-owner): artboard success copy differs from code.
- `instant-pairing-D3` (same-window): the automatic stale retry can't succeed inside the same window, so a second phone sees a misleading "expired" error.
- `connect-next-D2` (tailscale-owner B1): first-run success text never shows.

## UNDEFINED
- S1: real BLE timing (scan, 8 s GATT session) and RSSI ordering; emulator can't exercise `BleScan`.
- S3: AppKit alert appearance; `NSApp.activate` focus behaviour.
- bt-fallback: real Bluetooth HID connection behaviour (needs a real phone and Mac).
