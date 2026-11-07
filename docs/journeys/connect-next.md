# connect-next: D. Connect: every branch (0.6.0)

Artboard: `design/mote-v1/journey-connect-next.html` (Next (0.6.0)). Code: `android/.../pairing/ConnectUi.kt` (FindMacScreen, FindMacPanel, AddressConnect, ConnectCard, pairFailure, BtStep), `pairing/FirstRun.kt` (findUi, NOT_FOUND_MS), `pairing/Finder.kt`, `pairing/Sweep.kt`, `pairing/InstantPairing.kt` (connect), `pairing/Handshake.kt`, `pairing/BleScan.kt`, `net/Net.kt` (promptForTailscale), `ui/App.kt`, `helper/.../InstantPairing.swift`, `Proximity.swift`, `Hub.swift`, `Security.swift` (RateLimiter), `MenuBar.swift`. Docs: `docs/pairing.md` §2-§5, §8.
Test: `e2e/journeys` → `dev.mote.remote.journeys.ConnectNextJourney` (one @Test per path below).

Notation as in `wifi-helper.md`: `<Mac>`, `<phone>`, `<fp>`, `$P`. Base helper `H0` = `MoteHelper --dry-run --headless --no-ble --memory-store --port $P --stdin-control` (+ `--no-bonjour` once it exists). Base phone seams `S0` = `TestSeam.noBonjour = true`, `TestSeam.sweepPort = $P`.

## Preconditions
- P1. App: fresh install `dev.mote.remote.dev`, no app data, no runtime permission granted. Seams `S0` + `TestSeam.proximity` (to be added, see wifi-helper P1).
- P2. Phone: Wi-Fi on (AndroidWifi, 10.0.2.17/24); emulator Bluetooth adapter as provided by the image, no Mote BLE peripheral; no Tailscale.
- P3. Mac / helper: `H0 --fake-proximity`; instant pairing on, Require approval off, not paused, trust list empty.
- P4. Network: same "LAN" (emulator NAT → 10.0.2.2:$P).

## Main path `connect-next`
| # | Actor | Action (exact) | Expected observable (exact) | Seam |
|---|---|---|---|---|
| S1 | phone user | Launch Mote | `FindMacScreen` "Connect to your Mac" (full copy: wifi-helper S3); no permission dialog | real |
| S2 | system | (wait ≤ 5 s) | optionally "Looking for your Mac…" / "Searching this Wi-Fi, Bluetooth and Tailscale."; then `ConnectCard`: title "<Mac>", button "Connect to <Mac>" (contentDescription same) | real (sweep on $P; Bonjour off) |
| S3 | phone user | Tap "Connect to <Mac>" | "Connecting…" | real |
| S4 | system (helper) | — | stderr `[mote] labs: trusted <phone> via nearby`, `[mote] authed <phone> via Wi-Fi from 127.0.0.1`, `[mote] labs: new device <phone> via nearby (k:<fp>)`; stdout `NEW DEVICE: {"id":"k:<fp>","name":"<phone>","via":"nearby"}` | faked: `TestSeam.proximity` + `--fake-proximity` |
| S5 | system (phone) | — | Remote screen; pill contentDescription "Device: <Mac> via Wi-Fi. Tap to choose", badge "WI-FI" | real |
| S6 | Mac user | — | Notice "<phone> connected to Mote." / "via Bluetooth proximity" / "Block", 15 s | faked: stdout `NEW DEVICE` (headless) |

## Postconditions
- Phone: saved host `deviceId="k:<fp>"`, `lan="10.0.2.2"`, port $P; trust record `via="nearby"`; selection `ws:<hostId>`; `wifi_chosen=true`.
- Helper: 1 trusted key `via:"nearby"`.

## Branches
### `connect-next.wifi-off`: Wi-Fi off on the phone (from S1; artboard jD-b1s)
Preconditions delta: `adb shell svc wifi disable` before launch.
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | system | (immediately) | `FindMacPanel` WifiOff: "Turn on Wi-Fi", "Wi-Fi is off. Connect to the same Wi-Fi as your Mac, or turn on Tailscale.", button "Turn on Wi-Fi", link "Enter address" | real |
| B2 | phone user | Tap "Turn on Wi-Fi" | system Wi-Fi panel (`Settings.Panel.ACTION_WIFI`) opens | real (system UI copy UNDEFINED) |
| B3 | system | `adb shell svc wifi enable`, back to Mote | `Net.onWifiFlow` true → finder restarts → `ConnectCard` "Connect to <Mac>" (≤ 5 s); continue at S3 | real |

### `connect-next.not-found`: no helper answers (from S2; artboard jD-b2s)
Preconditions delta: helper not started.
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | system | wait 5 s (`NOT_FOUND_MS`) | "Mote Helper not found on this network", "Install it on the Mac (Terminal), keep it running, and use the same Wi-Fi:", "curl -fsSL https://tusharpandey13.github.io/mote/install.sh \| bash", "or download it from tusharpandey13.github.io/mote", button "Retry", link "Enter address" | real |
| B2 | phone user | Tap the install command text | Clipboard = `curl -fsSL https://tusharpandey13.github.io/mote/install.sh \| bash` (label "Mote Helper install"); Toast "Copied" | real |
| B3 | phone user | Start the helper (P3), tap "Retry" | Searching, then `ConnectCard` "Connect to <Mac>" | real |

### `connect-next.enter-address`: typed address (from not-found or wifi-off "Enter address"; not drawn)
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | phone user | Tap "Enter address" | `AddressConnect`: "Enter the Mac's address", "A LAN IP (192.168.1.2), name.local, or its Tailscale name / 100.x address.", field placeholder/contentDescription "192.168.1.2 or mac.tailnet.ts.net", button "Find", link "Back" | real |
| B2 | phone user | Tap "Find" with field empty or `bad host!` | "Enter an address like 192.168.1.2 or mac.tailnet.ts.net." | real |
| B3 | phone user | Type `10.0.2.3:$P` (nothing listens), tap "Find" | "Checking…", then "No Mote Helper answered at 10.0.2.3:$P." | real |
| B4 | phone user | Type `10.0.2.2:$P`, tap "Find" | `ConnectCard` "Connect to <Mac>" + link "Back"; continue at S3 | real |
| B5 | phone user | Type `mac.example.ts.net`, tap "Find", VPN off | "Tailscale is off" sheet (see tailscale.md); no continuation after the VPN comes up (tailscale-D2) | faked: no Tailscale app → sheet shows "Tailscale isn't installed on this phone." / "Get Tailscale" |

### `connect-next.need-bluetooth`: no token and Bluetooth not permitted (from S3; artboard caption of jD-s2)
Preconditions delta: no `TestSeam.proximity`; helper `H0` (no fake owner). Emulator API ≥ 31.
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | phone user | Tap "Connect to <Mac>" | helper stderr `[mote] labs: denied (far) 127.0.0.1` (once per address tried); phone: "Mote uses Bluetooth once to check this phone is next to your Mac.", button "Allow and connect" | real |
| B2 | phone user | Tap "Allow and connect" | Android permission dialog for Nearby devices (`BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`) | real (dialog copy UNDEFINED) |
| B3a | phone user | Deny | ConnectCard error (red): "<Mac> couldn't confirm this phone is nearby. Bring it next to the Mac with Bluetooth on, or use the same Tailscale account." + button "Try again" | real |
| B3b | phone user | Allow (adapter on) | scan ≤ 5 s finds no Mote peripheral → same `far` error as B3a | real; adapter off → `ACTION_REQUEST_ENABLE` system dialog first (UNDEFINED copy) |

### `connect-next.far`: on this LAN but out of Bluetooth range (from S3; artboard jD-b4s)
Preconditions delta: `BLUETOOTH_SCAN`+`BLUETOOTH_CONNECT` granted, adapter on, no proximity seam; helper `H0`.
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | phone user | Tap "Connect to <Mac>" | helper `[mote] labs: denied (far) 127.0.0.1`; phone: "<Mac> couldn't confirm this phone is nearby. Bring it next to the Mac with Bluetooth on, or use the same Tailscale account." + "Try again" | real (needs the emulator's BT adapter to be on; else UNDEFINED → need-bluetooth branch) |
| B2 | phone user | Tap "Try again" | same `denied (far)` and the same error copy (no state change) | real |

### `connect-next.stale`: expired token (not drawn)
Preconditions delta: `TestSeam.proximity` returns tokens for window `dw=-3` (`{"t":"labs.pair.prox","dw":-3}`) on every read.
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | phone user | Tap "Connect to <Mac>" | helper `labs: denied (stale) 127.0.0.1` twice (first try, then the one automatic re-read, InstantPairing.kt:127); phone: "The nearby check expired. Try again next to the Mac." + "Try again" | faked: `TestSeam.proximity` with `dw` |
| B2 | (variant) | seam returns `dw=-3` once, then `dw=0` | one `denied (stale)`, then `labs: trusted <phone> via nearby`; continue at S5 | faked |

### `connect-next.bad-proximity`: forged token (not drawn)
Preconditions delta: `TestSeam.proximity` returns `1:00000000000000000000000000000000`.
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | phone user | Tap "Connect to <Mac>" | helper `labs: denied (bad-proximity) 127.0.0.1`; phone: "The nearby check failed. Try again next to the Mac." | faked |
| B2 | phone user | Tap "Try again" until the 6th hello within 60 s | helper `err rate` (`retryAfter` ≤ 60); phone: "Too many requests. Try again in 60 s." (always 60: retryAfter dropped, connect-next-D7) | faked |

### `connect-next.tailscale-owner`: same Tailscale owner (from S3; artboard jD-b3s)
Specced in `wifi-helper.md` branch `wifi-helper.tailscale-owner` (helper `--fake-tailscale-owner self`).

### `connect-next.require-approval-allow`: "Require approval for new devices" on, Allow (from S3; artboard jD-b5s, jD-b5s2)
Preconditions delta: helper `H0 --fake-proximity --require-approval --fake-approve`.
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | Mac user | (menu) "Require approval for new devices" checked; "Trusted phones: 0"; "This Mac's key: <4x4 hex> …" | headless-unobservable; stderr at launch `[mote] instant pairing: on, require approval (key <8 hex>)` | faked: `--require-approval` |
| B2 | phone user | Tap "Connect to <Mac>" | wire `{"t":"labs.pair.pending","timeout":60}`; phone: "Click Allow on <Mac>. It asks before trusting new devices." (may be < 100 ms) | real |
| B3 | Mac user | Alert "Allow “<phone>” to control this Mac?" / "It will be able to type, click and move the pointer, like a keyboard and mouse." / "Via Wi-Fi (127.0.0.1)" / "Key <4x4 hex> …" / "Deny if you didn't just tap Connect on this phone." / buttons "Allow", "Deny" → Allow | stderr `[mote] labs: prompt <phone> (Wi-Fi 127.0.0.1)`, then `labs: trusted <phone> via approved`, `authed …`, `labs: new device <phone> via approved (k:<fp>)`; stdout `NEW DEVICE: {…,"via":"approved"}` | faked: `--fake-approve` answers after 100 ms; alert not shown headless |
| B4 | system (phone) | — | Remote screen, pill "via Wi-Fi" | real |
Postconditions: helper key `via:"approved"`; notice text would be "approved on this Mac".

### `connect-next.require-approval-deny` (from B2; not drawn)
Preconditions delta: `--require-approval --fake-approve-deny`.
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | phone user | Tap "Connect to <Mac>" | helper `labs: denied (denied) 127.0.0.1`; phone: "<Mac> didn't allow this phone." + "Try again" | faked |
| B2 | phone user | Tap "Try again" (within 10 min) | immediate `labs: denied (denied)` with no prompt (cool-down, InstantPairing.swift:256); same copy | faked |

### `connect-next.require-approval-timeout` (not drawn)
Preconditions delta: `--require-approval`, no `--fake-approve` (headless: nobody answers).
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | phone user | Tap "Connect to <Mac>" | "Click Allow on <Mac>. It asks before trusting new devices." for 60 s; then helper `labs: denied (timeout) 127.0.0.1`; phone: "<Mac> didn't answer in time." | real (60 s wait) |

### `connect-next.require-approval-busy` (not drawn)
Preconditions delta: `--require-approval`, no fake; a second client (node `ws` script) sends a valid `labs.pair.hello` first and keeps its socket open.
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | phone user | Tap "Connect to <Mac>" | helper `labs: denied (busy) 127.0.0.1`; phone: "<Mac> is answering another phone. Try again in a moment." | faked: second client script |

### `connect-next.require-approval-rate` (not drawn)
Preconditions delta: `--require-approval --fake-approve-deny` is unsuitable (cool-down answers first); use no fake and let 3 prompts time out, or 3 clients with different keys from 127.0.0.1.
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | phone user | 4th Connect from the same peer within 10 min | helper `labs: denied (rate) 127.0.0.1`; phone: "Too many requests. Try again in <retryAfter> s." | faked: extra client keys via script (3 × 60 s timeouts otherwise) |

### `connect-next.blocked` (from S6 Block; not drawn)
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | Mac user | After S5, Block (stdin `block`) | stderr `[mote] labs: blocked k:<fp>`; phone session gets `err revoked`; pill LED red; Devices sheet note "The Mac no longer trusts this phone. Long-press it to forget, then Add a Mac again." | faked: stdin `block` stands in for the notice button |
| B2 | phone user | Pill › long-press "Use <Mac>" › "Forget" › "Add a Mac" › "Connect to <Mac>" | helper `labs: denied (blocked) 127.0.0.1`; phone: "<Mac> blocked this phone. Unblock it with Revoke all devices in the Mote Helper menu." (connect-next-D5) | real |
| B3 | Mac user | stdin `unblock`, phone taps "Try again" | `[mote] labs: unblocked k:<fp>`, then `labs: trusted <phone> via nearby` + `NEW DEVICE` | faked: stdin `unblock` |

### `connect-next.instant-off`: helper with instant pairing off (not drawn)
Preconditions delta: helper `H0 --no-instant-pairing`.
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | system | launch both | helper `[mote] instant pairing: off (key …)`; card "Connect to <Mac>" still appears (sweep `hello`) | real |
| B2 | phone user | Tap "Connect to <Mac>" | phone: "Mote Helper on <Mac> is too old or has instant pairing turned off. Update it, or use Pair with code." | real |

### `connect-next.unreachable`: helper gone between discovery and Connect (not drawn)
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | system | Kill the helper after S2, tap "Connect to <Mac>" | "Couldn't reach <Mac>. Is Mote Helper running, and the phone on the same Wi-Fi or Tailscale?" + "Try again" | real |

### `connect-next.timeout`: helper silent after `hello` (not drawn)
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | phone user | Tap "Connect to <Mac>" | after 8 s: "<Mac> didn't answer in time." | faked: chaos proxy (`chaos/helper/lib/proxy.mjs`) on $P forwards `hello` then black-holes |

### `connect-next.helper-key`: another key / Mac at the address (not drawn)
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | phone user | Connect to a Mac whose stored pin differs | "That isn't the Mac it claims to be (its key doesn't match)." | faked: needs new helper flag to keep `hostId` across runs with a new key (e.g. `--host-id ID`), since `--memory-store` makes both new |

### `connect-next.two-macs` (not drawn)
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | system | Two dry-run helpers on the swept port range | card for the first; below it "Not this one? <Mac2>" (accent link) | faked: second helper needs a second sweep port or another IP; `sweepPort` is one port |
| B2 | phone user | Tap "Not this one? <Mac2>" | card switches to "<Mac2>" | faked |

### `connect-next.bt-offer`: Bluetooth HID offer after connect (Devices › Add a Mac only; not drawn here)
Preconditions delta: a host already saved (so the app is on the remote), `TestSeam.adapter = Adapter(supported=true)`; open pill › "Add a Mac".
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | phone user | Connect a second Mac from "Add a Mac" | "Connected over Wi-Fi" (or "Connected over Tailscale" when `via=="tailscale"`, connect-next-D4), checkbox row "Also pair Bluetooth for login screen & backup (recommended)" checked, "Android asks twice: make the phone visible, then Pair. Nothing to do on the Mac.", button "Continue", link "Skip" | faked: adapter seam |
| B2 | phone user | Tap "Skip" | sheet closes | real |
(The full BT HID setup flow belongs to journey-first-run-bt.)

## Disagreements → DEFECTS.md
- `connect-next-D1` (S2): discovery list and Connect sheet layout/copy differ from artboard (no source list, no "Nearby · Wi-Fi", no "Not now").
- `connect-next-D2` (S5): first-run Connect jumps to the remote before "Connected…"/BT offer can render.
- `connect-next-D3` (tailscale-owner): a tailnet-only Mac is never discovered (saved Macs filtered out, unknown tailnet not probed).
- `connect-next-D4` (bt-offer): "Connected over Tailscale/Wi-Fi" uses the trust `via`, not the route.
- `connect-next-D5` (blocked): blocked copy points to Revoke all; helper has per-phone Unblock.
- `connect-next-D6` (B3 of approval, jD-b5s): alert line layout, menu order, artboard apostrophes, "Try again" vs "Retry".
- `connect-next-D7` (bad-proximity B2): `retryAfter` of `err rate` lost in the handshake.
- `connect-next-D8`: artboard cites `docs/labs/pairing.md`, which no longer exists.

## UNDEFINED
- need-bluetooth / far: whether the emulator image exposes an enabled Bluetooth adapter (decides which of the two branches a run takes); Android permission / enable dialog copy.
- B2 of wifi-off: system Wi-Fi panel content.
- Approval alert: not observable headless; its exact layout is AppKit.
- Timing of "Connecting…" / "Click Allow…" visibility (sub-second with fakes).
