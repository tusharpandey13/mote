# Android Wi-Fi and Tailscale transports: research

Status: research, 2026-11. Scope: add helper-protocol transports (LAN Wi-Fi, tailnet) to the Android app next to Bluetooth HID. Read first: `AGENTS.md`, `docs/protocol.md`, `docs/research/tech.md` (section 3 and 8 already plan an `AgentTransport`), `helper/Sources/MoteHelper/{WebServer,Security,Config}.swift`.

## 0. Findings from the helper source (correct the brief)

- The peer check `isLocalAddress` (`Security.swift`) **already accepts tailnet peers**: `isLocalV4` allows `100.64/10`, and the IPv6 branch allows `fc00::/7`, which contains Tailscale's `fd7a:115c:a1e0::/48`. So a tailnet peer is not refused at accept time today. `docs/protocol.md` section 4 says the same ("CGNAT 100.64/10").
- What does block a tailnet client today is the **Host check** (`hostAllowed` in `WebServer.swift`): only `localhost`, `*.local`, IPv4 literals, or any `[`-prefixed IPv6 literal pass. `mac.tailnet-name.ts.net` gets `421 Misdirected Request`. A `100.x` IP literal works unchanged.
- The listener binds all interfaces (`NWListener` on the port, only `.cellular` prohibited), so the `utun` Tailscale interface is already served. No interface-specific bind exists or is needed.
- Origin: the upgrade only checks Origin **if present** (`Native clients send no Origin` is a comment in the code). A native OkHttp client sends none. No change needed.
- Bonjour: `NWListener.Service(type: "_mote._tcp")` advertises on the LAN only. The TXT record is empty (no version, id, or fingerprint).
- Weakness to note: `100.64/10` is also what carriers use for CGNAT. Because cellular is prohibited and nothing is forwarded, the risk is low, but "accepts all of 100.64/10" is wider than "my tailnet". An opt-in toggle is cheap hardening (section 3).

## 1. Architecture

**Reuse the protocol as-is.** The app becomes a second client of `docs/protocol.md` v1 over `ws://host:8790/ws`; it needs no protocol change for a first version.

| Concern | Native client behavior | Change needed? |
|---|---|---|
| Transport | OkHttp `WebSocket` (text frames, one JSON per frame, masking handled by OkHttp). The helper requires client masking and caps messages at 64 KiB; both satisfied. | None |
| Origin header | None sent; the helper accepts a missing Origin. Do not fake one. | None |
| Handshake | `hello` -> `auth{id, mac}` with `mac = HMAC-SHA256(token bytes, nonce ascii)`; use `javax.crypto.Mac("HmacSHA256")`, compare nothing client-side. | None |
| Pairing | `pair{code, name}` -> `paired{id, token}`. Code is 6 digits, valid 5 min, one device. The user types it (the helper shows it in the menu). | Optional: QR (below) |
| Token storage | Android Keystore cannot import-and-use an arbitrary HMAC key for a *shared* secret without care: generate an AES-256 **wrapping key** in Keystore (non-exportable, `setUserAuthenticationRequired(false)`), encrypt `{hostId, deviceId, token}` with AES-GCM, store the ciphertext in app-private storage (DataStore). Alternative: import the token as a Keystore `SecretKeyEntry` with `KeyProperties.PURPOSE_SIGN` and algorithm `HmacSHA256` (supported since API 23), so the token never re-enters app memory; this is the better fit since HMAC is the only operation. Spike both; prefer the imported HMAC key. | Client only |
| Android backup | Exclude the store from auto-backup (`android:allowBackup=false` or `dataExtractionRules`), else the token moves to a new phone as an unusable blob. | Client only |

Design: a `Transport` interface already planned in tech.md section 8 (`HidTransport`, `AgentTransport`). Make `AgentTransport` host-scoped (`WsTransport(hostRef)`), with a pure-Kotlin `Protocol` codec (messages, HMAC) that is unit-testable against `helper/tests/smoke.mjs` semantics.

**Optional protocol additions (small, backward compatible, all "unknown fields ignored")**:
1. `hello` gains `hostId` (stable per-install random id) and `fp` (TLS cert fingerprint, if TLS is on). Lets the client recognize a host across LAN IP, `.local` and tailnet name changes, and key the stored token by `hostId`, not by address.
2. `ping`/`pong` application messages are *not* needed: WebSocket control pings are handled (`parseFrames` answers opcode 9 with 10).
3. A pairing QR (see below).

**Pairing via QR**: the helper menu shows a QR encoding `mote://pair?h=<host-or-ip>&p=8790&c=<code>&n=<name>&fp=<sha256 of TLS cert, if any>&ts=<tailnet-name>`. Scan with CameraX + ML Kit barcode (or the Google code scanner, which needs no camera permission). It removes typing the code and, with `fp`, delivers the **out-of-band pin** that makes self-signed TLS safe (section 3). The 6-digit manual entry stays as fallback. Cost: one menu item in the helper, one parser in the app.

## 2. Discovery

**LAN: NsdManager `_mote._tcp`.**
- Use `discoverServices(SERVICE_TYPE "_mote._tcp.", PROTOCOL_DNS_SD, listener)`. The service type string needs the trailing dot on some OS versions.
- On API 34+ prefer `registerServiceInfoCallback` over the legacy `resolveService` ([API ref](https://developer.android.com/reference/android/net/nsd/NsdManager.ServiceInfoCallback)); it supports continuous updates and multiple addresses. Legacy `resolveService` allows **one resolve at a time** (`FAILURE_ALREADY_ACTIVE` = error 3) and is a known source of flakiness: queue resolves serially.
- Reported flakiness: discovery that works once and then reports nothing after an app restart, stale or wrong-IP resolves, `onDiscoveryStarted` with no further callbacks ([SO: not discovering after some time](https://kiwix.lema.org/stackoverflow.com_en_all_2023-11/questions/27478329/android-nsdmanager-not-discovering-services-after-some-time), [SO: wrong IP resolved](https://kiwix.lema.org/stackoverflow.com_en_all_2023-11/questions/55270638/network-service-discovery-wrong-ip-resolved), [SO: resolve of multiple services](https://kiwix.lema.org/stackoverflow.com_en_all_2023-11/questions/24665342/nsdmanager-doesn-t-resolve-multiple-discovered-services)). Anecdotal, not version-bounded; treat as "assume flaky".
- Mitigations: always `stopServiceDiscovery` before restarting; hold a `WifiManager.MulticastLock` (needs `CHANGE_WIFI_MULTICAST_STATE`) only during an active scan, release after; time-box scans (e.g. 8 s) with one retry; **never depend on NSD alone**: cache the last-known address per `hostId` and try it first; fall back to `<name>.local` (Android resolves `.local` via the system resolver on API 29+, not reliably before); finally manual entry. Scan only while the host picker is visible; no background discovery (battery, and Android 13+ `NEARBY_WIFI_DEVICES` is not required for NSD but local-network permission models may tighten, so spike on current Android).
- Client isolation on guest or hotel Wi-Fi silently blocks mDNS and direct TCP; the UI must say "no hosts found, try Tailscale or Bluetooth" rather than spin.
- Improvement: add TXT records (`id`, `v`, `fp`) in the helper so the picker can show a stable name and match saved hosts without connecting.

**Tailnet.**
- Bonjour/mDNS **does not work over Tailscale**: it uses UDP multicast, which the tailnet interface does not carry ([Tailscale issue 1013](https://github.com/tailscale/tailscale/issues/1013); [community confirmation](https://community.getchannels.com/t/tailscale-discovery/39284)). MagicDNS resolves *names* (via the resolver at 100.100.100.100), not services ([Alan Pearce](https://alanpearce.eu/post/when-tailscale-magicdns-isn't/)). Unicast DNS-SD with split DNS is possible but is far too much setup for this app ([Moltbot Bonjour doc](https://docs.molt.bot/gateway/bonjour)).
- An Android app cannot ask the Tailscale app for its peer list: the LocalAPI is a Unix socket in the Tailscale process, not exposed to other apps (I found no official Android API; unverified, spike by checking for an exported intent/service; assume none). The Tailscale **admin API** (HTTPS, API key or OAuth) could list devices, but putting a tailnet credential in a remote-control app is the wrong trade. Rejected.
- The phone's Tailscale app is a system VPN: our app's sockets to `100.x` or `*.ts.net` go through it with no code on our side. The app should only check `ConnectivityManager` for a `TRANSPORT_VPN` network to hint "Tailscale looks off" on failure.
- **What works**: manual host entry (`name.tailnet.ts.net` or `100.x.y.z`), validated by actually connecting and reading `hello`; save it as a host. Better: **carry the tailnet address in the pairing QR** (`ts=` field; the helper can read its own tailnet IP from `utun` `100.64/10` addresses via `getifaddrs`, and its MagicDNS name by reading `tailscale status --json` if the CLI exists, else leave it to the user). Pair once on the LAN by QR and the phone already knows both addresses, no typing. Remember per host: `{hostId, name, lastLan, mdnsName, tailnetName, tailnetIp, token ref}`; connect order: last-success address, then the others in parallel (happy-eyeballs style, 1.5 s).
- Prefer the MagicDNS name over the `100.x` IP when both are known (IPs can change if the node is re-registered; names are stable until renamed).

**Recommended UX**: "Add host" sheet with three tabs: *Nearby* (NSD list), *Scan QR* (default if the helper menu shows one), *Enter address* (hint text: `name.tailnet.ts.net` or `100.x.y.z`). Saved hosts reconnect without scanning. A host row shows both known addresses and which one worked last.

## 3. Helper changes

| Change | Why | Cost |
|---|---|---|
| Host check accepts `*.ts.net` (and optionally user-listed names) | Today MagicDNS names get 421 | ~5 lines in `hostAllowed` + smoke test |
| Menu toggle "Allow Tailscale peers" (default off), gate `100.64/10` and `fd7a:115c:a1e0::/48` (narrow the current `fc00::/7` to ULA minus tailnet only when off) | Current code is implicitly "on" for CGNAT. Making it explicit keeps the default LAN-only promise and narrows to the real tailnet prefix. | small; changes documented behavior, so update `docs/protocol.md` section 4 and `helper/tests/smoke.mjs` together (AGENTS.md rule) |
| Interface bind | Not needed: all-interface bind already serves `utun`. Binding only on the tailscale interface would break LAN. Keep. | 0 |
| TXT record (`id`, `v`, `fp`) and `hello.hostId` | Stable identity across addresses | small |
| QR in the menu | Pairing + pin delivery | medium (CoreImage `CIQRCodeGenerator`, one window) |
| Per-peer rate limit keyed by IP | Over Tailscale the peer IP is the node's `100.x` (stable, fine). Behind a subnet router or exit node, the source may be SNATed and shared; acceptable. | 0 |

**Plain `ws` over the tailnet is acceptable.** Tailscale traffic is end-to-end WireGuard-encrypted and peer-authenticated by node key, so a sniffer or ARP spoofer on either local network sees ciphertext. The pairing token and `text` payloads (which may be passwords being typed) are protected in transit. Residual risk is other tailnet members and ACLs: with default ACLs every device (and shared-in users' devices) in the tailnet can reach port 8790. Mitigation is app-level auth (already there) plus a note to restrict via Tailscale ACLs; do **not** weaken: pairing window + 6-digit code limits still apply.

**The LAN is the actual gap**: `docs/protocol.md` already lists "No transport encryption on Wi-Fi" as a known limit. A native client can do better than a browser, because it is not constrained by the public CA system. Options:

| Option | Mechanism | Pros | Cons |
|---|---|---|---|
| A. Self-signed cert + fingerprint pinning via pairing QR | Helper generates a long-lived key and self-signed cert (Keychain), serves `wss` on a second port or same port with TLS. QR carries `fp` (SHA-256 of cert). App uses a custom `X509TrustManager` that accepts only that fingerprint, with hostname verification off (we pin the key, not the name). OkHttp supports a custom `SSLSocketFactory`/trust manager ([OkHttp docs](https://square.github.io/okhttp/), [pinning discussion](https://kiwix.lema.org/stackoverflow.com_en_all_2023-11/questions/42288868/okhttp3-accept-all-certificates-and-use-a-certificatepinner)). Works on any LAN IP, offline, no accounts. | Fixes the sniff-the-pairing-token attack on LAN for the native client. | The browser web remote cannot use it (untrusted cert), so keep plain `ws` for it; two listeners. Server side needs TLS in `NWListener` with `sec_identity` (hand-rolled HTTP stays). Typing the pairing code without QR leaves a first-contact TOFU window (accept-and-pin, show fingerprint words). Cert rotation = re-pair. |
| B. `tailscale cert` for `*.ts.net` | Let's Encrypt cert for the Mac's MagicDNS name, [needs HTTPS enabled in the admin console, 90-day certs, you renew them](https://tailscale.com/kb/1153/tailscale-cert); names are published in Certificate Transparency logs. Real trusted `wss://name.ts.net`, no pinning code, browser remote works too. | Trusted by Android and browsers | Tailnet-only (not LAN IPs), requires the Tailscale CLI on the Mac and admin console setup, renewal plumbing, CT log leak of the machine name. And redundant over WireGuard anyway. |
| C. Do nothing on LAN | status quo | free | Keeps the documented sniffing limit |

Recommendation: **ship plain `ws` first (LAN and tailnet)**, with the tailnet toggle. Add **option A** as an opt-in "Encrypt LAN connection" in the helper once QR exists, native client only. Skip B (cost and setup high, benefit only duplicates WireGuard). Always let the app show a "secure" vs "LAN unencrypted" badge.

## 4. Latency and battery

- **Keepalive**: OkHttp `pingInterval` defaults to 0 (disabled) ([OkHttpClient.Builder](https://javadoc.io/static/com.squareup.okhttp3/okhttp/3.9.1/okhttp3/OkHttpClient.Builder.html)); set 15-20 s on LAN. On a tailnet use 20-25 s (NAT mappings on cellular often expire at 30-60 s; DERP relay paths add latency). A failed pong closes the socket, so you detect a dead Mac in about 2 x interval. Helper side already answers pings; it has a 5 min unauthenticated timeout only, and no idle timeout for authed sessions.
- **Reconnect**: exponential backoff 0.5 s -> 8 s with jitter, reset on success; retry on `ConnectivityManager.NetworkCallback` `onAvailable`/`onCapabilitiesChanged` immediately instead of waiting.
- **Mouse coalescing**: BT HID sends a report per touch event at the HID report rate. Over WebSocket, sum `dx,dy` per frame and flush at the display vsync (about 60 Hz, 16 ms) or at 8 ms when latency-tuned; never queue: if a send is still in flight (`WebSocket.queueSize()` over 4 KB), keep accumulating and send the sum. Scroll: same, flush at 30-60 Hz. Keys, buttons and `clickAt`: send immediately, never coalesce, and flush the pending move first to keep ordering. LAN RTT is typically 2-10 ms; a tailnet direct path is similar plus about 1-2 ms, but a DERP-relayed path can be 30-150 ms: show a latency chip from ping RTT and reduce move rate when RTT > 60 ms (relative moves stay correct since sums are preserved).
- **Wi-Fi power save**: Wi-Fi radios in power-save add up to about 100 ms bursts on sparse traffic; `WifiManager.WifiLock` with `WIFI_MODE_FULL_LOW_LATENCY` (API 29+) while the touchpad is on screen and the screen is on only. Release on pause. Never hold it in the background.
- **Doze and service**: Doze suspends network access except in maintenance windows ([Android Doze docs](https://developer.android.com/training/monitoring-device-state/doze-standby)); a foreground service holding a socket is the usual approach but I could not confirm an explicit Doze exemption, so test (spike). Remote use is screen-on and interactive, so a foreground service is only needed for *background* use (media keys from the notification, volume buttons). Policy: no service while the UI is foreground; start the existing `connectedDevice`-type service (tech.md section 5) only when the user asks to keep the connection with the app in the background; close the socket after N minutes with no input (default 10) and say so in the notification. Prefer short-lived sockets over wakelocks.

## 5. Capabilities: transport vs HID

| Capability | BT HID | Helper (Wi-Fi/tailnet) |
|---|---|---|
| Keys, mouse, scroll, media, volume | Yes | Yes |
| Precise brightness (absolute set, steps) | No (only brightness up/down keys, Mac-dependent; tech.md) | Yes (`consumer brightup/down`, DisplayServices fallback; extend with absolute `brightness` message) |
| Unicode / layout-independent text, emoji, paste-like typing | No (HID typing is layout-bound) | Yes (`text`, up to 2000 UTF-16 units) |
| Click at a point (`clickAt`, normalized or points, per display) | No (relative mouse only) | Yes |
| Macros with waits, `fn` modifier | Limited | Yes |
| Now-playing metadata / artwork, volume level readback, host state (paused, ax) | No (no back-channel) | Possible: add helper-to-client `status` / `nowPlaying` messages (MediaRemote is private API: spike; fall back to AppleScript for Music/Spotify) |
| Lock screen, login window, FileVault prompt, before-login | Yes | **No** (helper is a user-session agent, tech.md section 3) |
| Works with any host (Windows, Linux, TV, iPad) | Yes | **No** (macOS helper only) |
| Zero install on the host | Yes | **No** (install + Accessibility grant, re-grant on each ad-hoc-signed update) |
| Away from home (tailnet) | No (range) | Yes, with Tailscale on both |
| Needs network | No | Yes (same LAN or tailnet) |

Implication: the transports are **additive capabilities, not a replacement**. The UI should gray out helper-only actions on a HID host, with a one-line reason.

## 6. UX

- **Device pill**: shows `<host name> + transport badge`: `BT` (Bluetooth HID), `Wi-Fi` (LAN helper), `TS` (tailnet helper), plus state dot (connected / connecting / offline) and, on helper hosts, latency chip. Tap opens the picker.
- **Picker**: one list of hosts, mixing kinds. Each row: name, badges for *every* known route (a Mac can be paired both as BT HID and as helper host), last seen. Same physical Mac with both routes is two rows that share a `hostId` when known, grouped into one row with a route selector ("Use Wi-Fi / Bluetooth"). Auto-pick order for a grouped Mac: helper on LAN > helper on tailnet > BT HID, but let the user pin one. Also show a one-tap "Lock screen? Switch to Bluetooth" hint when a helper host drops because the Mac locked.
- **Capability-aware UI**: on a BT HID host hide or gray `Type text`, `Tap on screen area`, brightness slider (show brightness +/- instead), now-playing card.
- **First-run gate**: today "BT only". Change to "How do you want to connect?": **Bluetooth** (nothing to install, works at the login screen, needs Bluetooth HID support) and **Wi-Fi / network** (needs Mote Helper on the Mac: link to `install.sh`, then QR or code). Skippable, re-enterable via "Add host". Tailscale is not a first-run choice: it appears in *Add host > Enter address* and as a hint when no LAN host is found. Capability check first: if `BluetoothHidDevice` is unsupported (tech.md risk 1), default and emphasize Wi-Fi.
- Errors: map `err` codes (`auth`, `rate` with `retryAfter`, paused, `ax:false` "Accessibility not granted") to plain messages. Offer "Revoke on Mac" guidance on `auth` failure (token revoked -> forget host, re-pair).

## 7. Phased plan (cost / impact)

| Phase | Work | Cost | Impact | Notes |
|---|---|---|---|---|
| 0. Spikes (2-3 days) | See risks below | low | de-risks everything | Do first; they are asymmetric: a day of spike avoids a rewrite |
| 1. LAN helper client (about 1-1.5 wk) | `Protocol` codec + OkHttp `WsTransport`, manual IP/`.local` entry, pairing by typed code, Keystore-held token, saved hosts, pill badge, text + clickAt + brightness UI, reconnect | medium | high (unlocks Unicode, precise brightness, clickAt) | No helper change needed. Ship behind a "Wi-Fi (beta)" entry |
| 2. Discovery + QR (about 1 wk) | NSD with fallback chain, helper TXT records, QR window in menu + scanner | medium | high (UX) | Helper changes small. Update protocol doc |
| 3. Tailnet (about 3 days) | `*.ts.net` Host check, toggle, narrowed prefixes, `ts=` in QR, route fallback order, docs, smoke tests | low | high for away-from-home users, a niche that loves it | Cheap because source already accepts tailnet IPs |
| 4. Encrypted LAN (about 1 wk) | Self-signed TLS + QR pin (option A), badge | medium-high | medium (closes the documented LAN sniff limit, only for native) | Do only after real demand or a security review; two listeners add complexity |
| 5. Back-channel (open-ended) | `nowPlaying`, volume state, helper-to-client `status` | high, private APIs | medium | Gate on MediaRemote feasibility; scope per feature |

Asymmetric reasoning: phases 1 and 3 have the best ratio (little new code, large capability gain); a protocol or helper bug caught late is expensive because `docs/protocol.md` is a contract shared with `site/remote/` (change both together). Phase 4 and 5 carry the most complexity debt per unit of user value; defer. Never let a transport change break BT HID, the app's core promise (works at the login window, any host).

## 8. Risks to spike

1. **Android 14+ NSD behavior** on real devices (Pixel, Samsung, Xiaomi): does `registerServiceInfoCallback` find the helper reliably with the Network.framework advertisement; how often does it need the multicast lock; does `.local` resolution work through the system resolver on minSdk 28-30.
2. **Keystore HMAC import**: does `SecretKeyEntry` + `KeyGenParameterSpec(PURPOSE_SIGN, HmacSHA256)` accept a 32-byte imported token on all OEMs (some Keystore implementations are buggy); fallback = AES-GCM wrapped.
3. **Tailnet connect from a non-Tailscale-aware app**: confirm sockets to `100.x` and `*.ts.net` reach the Mac with the Tailscale Android VPN on (including when "Always-on VPN" is off, cellular data, and when Private DNS is enabled: MagicDNS name resolution for third-party apps depends on the VPN's DNS; verify). Also test DERP-relayed latency and the helper's peer-address view (is it the `100.x`, not a SNATed address).
4. **TLS on `NWListener` + hand-rolled HTTP** (phase 4): `sec_identity` from a Keychain self-signed cert, OkHttp custom trust manager and SNI/hostname handling, and how it interacts with the Origin/Host checks.
5. **Doze and keepalive**: socket survival with screen off and the foreground service (does the system suspend it despite the service?); battery draw per hour with 20 s pings.
6. **Mouse feel over Wi-Fi**: p99 frame latency with 60 Hz coalescing against BT HID, with Wi-Fi power-save on/off and `WIFI_MODE_FULL_LOW_LATENCY`.
7. **MediaRemote** availability on current macOS for now-playing (private API; may need a fallback or be dropped).
8. **QR scanner** dependency choice (Google code scanner needs Play Services; ML Kit bundled adds about 2-3 MB; F-Droid builds need the bundled one, see tech.md risk 10).

## Recommendation

Add the helper protocol as a second `Transport` unchanged (OkHttp WebSocket, native client sends no Origin, HMAC from Keystore). Ship LAN first with manual address and saved hosts, then NSD plus a pairing QR that also carries the tailnet name. Tailnet support is nearly free on the helper: peers are already accepted, only the Host check (`*.ts.net`) and an explicit opt-in toggle are missing. Plain `ws` is fine over WireGuard; for LAN, pin a self-signed cert through the QR later. Keep BT HID as the always-available baseline and show it as a route, not a mode.

## Sources

- Android NSD: [NsdManager.ServiceInfoCallback](https://developer.android.com/reference/android/net/nsd/NsdManager.ServiceInfoCallback), community reports linked in section 2
- Tailscale: [Enabling HTTPS / tailscale cert](https://tailscale.com/kb/1153/tailscale-cert), [multicast/mDNS request, issue 1013](https://github.com/tailscale/tailscale/issues/1013), [Alan Pearce on MagicDNS](https://alanpearce.eu/post/when-tailscale-magicdns-isn't/), [Moltbot Bonjour doc](https://docs.molt.bot/gateway/bonjour)
- Android Doze: [Doze and App Standby](https://developer.android.com/training/monitoring-device-state/doze-standby)
- OkHttp: [OkHttpClient.Builder (pingInterval)](https://javadoc.io/static/com.squareup.okhttp3/okhttp/3.9.1/okhttp3/OkHttpClient.Builder.html)
- Claims marked "unverified" or "spike" in the text were not confirmed by the sources found in this session.
