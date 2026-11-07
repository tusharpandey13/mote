# tailscale: 3. Tailscale

Artboard: `design/mote-v1/journey-tailscale.html` (Shipped). Code: `android/.../net/Tailscale.kt` (tailnetUp, onWifi, needsTailscalePrompt, Tailscale.requestConnect/open/openStore), `net/Net.kt` (refreshLinks, tsPrompt, promptForTailscale, select), `ui/HostsUi.kt` (HostSheet LIST onPick, TailscalePrompt / TailscaleSheet, HostPill, failedText, friendly), `pairing/ConnectUi.kt` (ConnectCard.go, AddressConnect), `net/WsTransport.kt` (reconnect / kick), `net/Hosts.kt` (SavedHost.routes, routeOf), `helper/.../WebServer.swift` (accept, Host header), `Security.swift` (isLocalAddress, hostHeaderAllowed), `MenuBar.swift` ("Allow Tailscale connections"). Docs: `docs/protocol.md` §4 Tailscale; `docs/pairing.md` §5.
Test: `e2e/journeys` → `dev.mote.remote.journeys.TailscaleJourney` (one @Test per path below).

Notation: `<Mac>` = saved host name. `TS0` = a saved, selected-able host whose only route is a tailnet address (`SavedHost.tailnet = "mac.example.ts.net"`, no `lan`/`local`), or one with both (`lan` + `tailnet`) for the off-Wi-Fi variant.

## Preconditions
- P1. App: one saved helper host `TS0` (seeded app data, or created by `AddressConnect` / a pairing link with `ts=`), currently not selected or selected; app on the remote screen.
- P2. Phone: Tailscale app `com.tailscale.ipn` installed with VPN permission and login, VPN OFF. For a LAN+tailnet host: Wi-Fi OFF (`svc wifi disable`, mobile data on). Seams needed: `TestSeam.tailscale` (to be added) faking `Tailscale.installed`, the `CONNECT_VPN` broadcast and the VPN link (a VPN network with a 100.64/10 address) seen by `Tailscale.links`, plus a resolver mapping `mac.example.ts.net` to 10.0.2.2.
- P3. Mac / helper: `MoteHelper --dry-run --headless --no-ble --memory-store --port $P --allow-tailscale` with the phone already trusted (instant pairing) or paired by code.
- P4. Network: tailnet only (no LAN path to the Mac).

## Main path `tailscale`
| # | Actor | Action (exact) | Expected observable (exact) | Seam |
|---|---|---|---|---|
| S1 | phone user | Tap the pill | `HostSheet` LIST "Devices", row "<Mac>" (contentDescription "Use <Mac>") with status "Paired" and badge "TAILSCALE" | real (seeded data) |
| S2 | phone user | Tap "Use <Mac>" | host selected (`ws:<hostId>`), sheet closes; `promptForTailscale` true → sheet "Tailscale is off" / "Turn it on to reach <Mac>." / buttons "Turn on Tailscale", "Not now" | faked: `TestSeam.tailscale` (installed = true, VPN down) |
| S3 | phone user | Tap "Turn on Tailscale" | broadcast `com.tailscale.ipn.CONNECT_VPN` to `com.tailscale.ipn/.IPNReceiver`; text "Turning on Tailscale…" | faked: broadcast recorded by the seam |
| S4a | system | VPN comes up within 6 s | `refreshLinks()` sees a tailnet address → sheet dismissed; `WsTransport` connects to `mac.example.ts.net`; pill badge "TAILSCALE", LED green, contentDescription "Device: <Mac> via Tailscale. Tap to choose" | faked: seam raises the VPN link + DNS map |
| S4b | system | VPN not up after 6 s (`TAILSCALE_WAIT_MS`) | Tailscale app opened (launch intent); sheet text "Finish in the Tailscale app, then come back. Mote connects as soon as Tailscale is on." | faked: seam records the launch |
| S5 | phone user | (after S4b) return to Mote, VPN now up | sheet dismissed; same as S4a | faked |
| S6 | system (helper) | — | stderr `[mote] authed <phone> via Tailscale from <100.x>` (transport "Tailscale" for tailnet peers) | faked: on the emulator the peer is 127.0.0.1, so the helper logs `via Wi-Fi from 127.0.0.1`; a real tailnet is manual |

## Postconditions
- Phone: selection `ws:<hostId>`; `SavedHost.tailnet` unchanged (address that worked recorded via `withAddress`); `Net.tsPrompt` null.
- Helper: no change to trust.

## Branches
### `tailscale.not-now` (from S2; not drawn)
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | phone user | Tap "Not now" | sheet closes; host stays selected; pill LED amber (`WsStatus.Connecting`), badge "TAILSCALE"; WsTransport keeps retrying with backoff | faked |

### `tailscale.not-installed` (from S2; coverage "not-installed variant text-only")
Preconditions delta: no Tailscale app (the emulator default).
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | phone user | Tap "Use <Mac>" | "Tailscale is off" / "Turn it on to reach <Mac>." / "Tailscale isn't installed on this phone." / buttons "Get Tailscale", "Not now" | real |
| B2 | phone user | Tap "Get Tailscale" | `market://details?id=com.tailscale.ipn`; without a store, `https://play.google.com/store/apps/details?id=com.tailscale.ipn` in a browser | real (store/browser UI UNDEFINED) |

### `tailscale.open-fails` (from S4b; not drawn)
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | system | 6 s pass and the Tailscale launch intent fails | "Turning on Tailscale…" disappears; sheet back to "Turn it on to reach <Mac>." with "Turn on Tailscale" (no message) | faked |

### `tailscale.lan-available`: host with a LAN route while on Wi-Fi (from S2; not drawn)
Preconditions delta: `TS0` has `lan` + `tailnet`, Wi-Fi ON.
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | phone user | Tap "Use <Mac>" | no Tailscale sheet (`needsTailscalePrompt` false: on Wi-Fi with a Wi-Fi route); connects on the LAN address; badge "WI-FI" | real |
| B2 | (variant) | Wi-Fi OFF | Tailscale sheet as S2 | real for Wi-Fi state; faked VPN |

### `tailscale.launch-no-prompt`: app opened with the tailnet host already selected (not drawn)
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | phone user | Launch Mote, VPN off | no Tailscale sheet (prompt only on pick / Connect / Enter address / pair); pill LED amber, badge "TAILSCALE" (`!onWifi && TAILNET in routes`), "Connecting…" in the Devices row | real for UI; faked VPN state |
| B2 | phone user | Turn VPN on outside Mote | default-network callback `kick()`s the socket → connects, badge "TAILSCALE" | faked |

### `tailscale.helper-refuses`: "Allow Tailscale connections" off on the Mac (not drawn)
Preconditions delta: helper without `--allow-tailscale` (memory-store default: off).
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | system (helper) | tailnet peer connects | stderr `[mote] wifi: refused non-LAN peer <100.x>:<port>` (MagicDNS / tailnet Host header → HTTP 421 when allowed=false) | manual: needs a real tailnet peer (emulator peer is 127.0.0.1, always local) |
| B2 | system (phone) | — | WsTransport keeps retrying; Devices row "Connecting…"; via Enter address the error is "Couldn't reach <host>. Check Allow Tailscale connections in the Mote Helper menu." (VPN up) or "Couldn't reach <host>. Tailscale looks off on this phone." | manual |

### `tailscale.enter-address`: tailnet name typed in Add a Mac › Enter address (not drawn)
| # | Actor | Action | Expected observable | Seam |
|---|---|---|---|---|
| B1 | phone user | Add a Mac › "Enter address", type `mac.example.ts.net`, tap "Find", VPN off | Tailscale sheet (as S2) | faked |
| B2 | system | VPN up | sheet closes; nothing else happens (no continuation passed, ConnectUi.kt:177); user must tap "Find" again despite "Mote connects as soon as Tailscale is on." (tailscale-D2) | faked |

## Disagreements → DEFECTS.md
- `tailscale-D1` (S1): artboard Devices sheet shows "Add Mac over Wi-Fi" / "Enter address" buttons; code has "Add a Mac" and "More options" (→ "Pair with code", "Enter address").
- `tailscale-D2` (enter-address B2): the Enter address Tailscale prompt promises auto-connect but has no continuation.
- `tailscale-D3` (S3, not-installed): "Turning on Tailscale…" and not-installed states exist in code but aren't drawn.

## UNDEFINED
- S4a/S4b: whether the real Tailscale app honours `CONNECT_VPN` (depends on Tailscale's version, VPN permission and login); how fast the VPN comes up.
- `Tailscale.links` is a heuristic (any VPN with a 100.64/10 address counts as Tailscale).
- Real tailnet behaviour (WireGuard, MagicDNS resolution, `tailscale whois`) is not reproducible on the emulator.
