# Instant pairing

Status: default since 0.6 on both sides (it was a Labs preview in 0.5). The wire names keep the `labs.pair.*` prefix of the preview, so 0.5 helpers and phones still recognise them. The 6-digit code (`docs/protocol.md`) stays for the web remote and as a hidden "Pair with code" fallback on the phone (device sheet › More options).

Goal: one tap on the phone, nothing to do on the Mac. The phone finds the Mac (BLE, a LAN sweep, Bonjour, known addresses), shows "Connect to <Mac>" with one button, and the Mac trusts the phone's key because the phone proves it is physically near (a rotating token readable only over Bluetooth) or because both are on the same Tailscale account. The Mac shows a non-blocking notice with a one-click **Block**. Afterwards the phone reconnects silently: LAN, then tailnet, then Bluetooth HID.

Kill switch: helper `--no-instant-pairing` or `defaults write dev.mote.helper instantPairingDisabled -bool YES` (then `labs.pair.*` gets `err unauth` and the BLE pairing characteristics are removed). Cautious users turn on **Require approval for new devices** in the helper menu (default off): every new key then needs Allow on the Mac, as in the preview.

## 1. Keys and fingerprints

- Both sides hold one long-lived **Ed25519** key pair. Helper: private key in the login Keychain, inside the helper's one secrets item (generic password, service `<keychain service>`, account `secrets.v3`, a JSON blob with every helper secret; see helper/README.md; `--memory-store`: a fresh key per run). Phone: Android Keystore Ed25519 key (API 33+, where the Keystore supports it), else a 32-byte seed AES-256-GCM-wrapped by a Keystore key in an app-private pref.
- `pub`: the 32-byte raw public key, lower-case hex (64 chars). `sig`: the 64-byte Ed25519 signature, hex (128 chars).
- `fp` (fingerprint): first 16 bytes of SHA-256(raw public key), hex (32 chars).
- Device id of a trusted phone on the helper: `k:` + its `fp`.

Signed strings (UTF-8, `|`-separated, no trailing newline):

```
client proof: mote-labs-pair-v1|client|<hostId>|<hello nonce>|<client pub>
helper proof: mote-labs-pair-v1|helper|<hostId>|<hello nonce>|<cn>|<client pub>
```

`hello nonce` is the per-connection nonce from the protocol's `hello`, `cn` a fresh 32-hex client nonce. Both proofs are bound to this connection and this Mac.

## 2. BLE: info and proximity

The helper advertises the Mote service (`3abad8f4-b472-41fc-bfc5-6f645f33564b`, local name `Mote`) while it runs. Two read-only characteristics (no notify, no auth; long reads with offsets return the same bytes as the offset-0 read):

**Info** `6d6f7465-0001-4c61-6273-50616972696e`: compact UTF-8 JSON, at most 512 bytes.

```json
{"v":1,"id":"<hostId>","n":"Tushar's MacBook Air","fp":"<fp>","lan":["192.168.1.2"],"ts":"mac.tail1234.ts.net","t4":"100.83.11.75","p":8790}
```

| Field | Meaning |
|---|---|
| `v` | info format, 1 |
| `id` | `hostId` (same as `hello.hostId` and Bonjour TXT `id`) |
| `n` | Mac name, ≤ 64 chars |
| `fp` | fingerprint of the helper's Ed25519 key |
| `lan` | up to 4 LAN IPv4 addresses (`en*`, `bridge*`) |
| `ts`, `t4` | MagicDNS name and tailnet IPv4, only when Tailscale connections are allowed and detected |
| `p` | WebSocket port |

Over the WebSocket, `labs.pair.info` returns the same object (allowed before auth; the LAN sweep uses it to learn `fp` and the tailnet addresses).

**Proximity** `6d6f7465-0002-4c61-6273-50616972696e`: UTF-8 `"<w>:<tk>"`, where

```
w  = floor(unix seconds / 30)                                  (decimal)
tk = hex(HMAC-SHA256(secret, "mote-prox-v1|<hostId>|<w>"))[first 16 bytes]   (32 hex)
```

`secret` is 32 random bytes per helper process, never stored or sent. The token is **only** exposed over BLE (the dry-run test flag `--fake-proximity` is the one exception, see section 8). The helper verifies, in order: malformed or wrong HMAC (constant-time) → `bad`; `|w - now| > 1` → `stale` (so a token is good for 30-90 s, tolerating ±1 window of clock or read delay); already accepted once → `replay`; else ok, and the window is marked used. Used windows older than now-1 are pruned. Consequence: one new phone per 30 s window can be trusted by proximity; a second phone in the same window gets `stale` and succeeds in the next window.

The phone scans for the service UUID with `BLUETOOTH_SCAN` (`neverForLocation`, no location permission) and `BLUETOOTH_CONNECT`, connects GATT, reads info then proximity in one session, and disconnects. It asks for these permissions in context only, the first time a Connect needs proof of proximity ("Mote uses Bluetooth once to check this phone is next to your Mac."), never at launch. A token read more than 20 s ago is re-read before use, and a token is handed out once.

## 3. Handshake

Runs on the WebSocket (or the BLE GATT transport) after `hello`, instead of `pair` / `auth`.

```
phone                                           helper
  | <--------------- hello {nonce, hostId, ...} -|
  | -- labs.pair.hello {pub, name, cn, sig, prox?} -> |  sig = client proof; prox = BLE token
  | <-- labs.pair.hi {pub, fp, sig, host, hostId} |  sig = helper proof; phone checks fp against BLE info / its pin
  |   trusted (section 4):                        |
  | <-------------- labs.pair.ok {via, id, name} -|
  | <-------------- authed {id, name, paused, ax} |  standard; input works from here
  |   Require approval on, unknown key:           |
  | <-------------- labs.pair.pending {timeout}   |
  | <-------------- labs.pair.ok + authed         |  Allow
  |   refused:                                    |
  | <-------------- labs.pair.denied {reason}     |
```

| Message | Direction | Fields |
|---|---|---|
| `labs.pair.info` | phone -> helper | none. Reply: `labs.pair.info` with the info fields. Allowed before auth. |
| `labs.pair.hello` | phone -> helper | `pub`, `name` (≤ 64 chars), `cn` (32 hex), `sig`, optional `prox` (`"<w>:<tk>"`) |
| `labs.pair.hi` | helper -> phone | `pub`, `fp`, `sig`, `host` (Mac name), `hostId` |
| `labs.pair.pending` | helper -> phone | `timeout` (seconds, 60); only with Require approval |
| `labs.pair.ok` | helper -> phone | `via`: `trusted` (known key), `nearby` (valid proximity token), `tailscale` (same tailnet owner), `approved` (Allow clicked); `id` (`k:<fp>`), `name` |
| `labs.pair.denied` | helper -> phone | `reason`: `far` (no proof of proximity), `stale` (token expired or already used), `bad-proximity` (forged token; counts as a failed attempt), `blocked` (Block was clicked for this key), and with Require approval `denied`, `timeout`, `busy`, `rate` (`retryAfter`) |

Errors (`err`): `bad` (malformed fields or a bad signature; counts towards the per-peer limiter), `rate` (`retryAfter`), `unauth` (instant pairing turned off on this Mac, or a pre-0.5 helper).

The phone stores, per `hostId`: the helper `pub`, the addresses it learned, and `via`. Later connects require the same helper key (pinning): a different key is treated as a different Mac and the phone closes the connection without sending input. After `labs.pair.ok` the session is authenticated exactly like after `auth`.

## 4. Trust decisions on the helper

In order:

1. **Blocked key**: `denied blocked`.
2. **Known key** (trust list): `ok via trusted`.
3. **Require approval for new devices** on: the Allow/Deny alert ("Allow <phone> to control this Mac?", phone fingerprint and route, auto-deny after 60 s; one alert at a time, 3 prompts per peer and 6 overall per 10 minutes, 10-minute cool-down after Deny). Proximity and Tailscale don't bypass it.
4. **Proximity**: a valid `prox` → store the key (`via nearby`), `ok via nearby`. Checked before the (slower) Tailscale lookup.
5. **Tailscale owner**: the peer address is a tailnet address (100.64/10 or `fd7a:115c:a1e0::/48`) and the local `tailscale whois --json <ip>` → `UserProfile.ID` equals `tailscale status --json --peers=false` → `Self.UserID`; tagged nodes never match; CLI missing or > 3 s = no match. A stale or bad token still gets this check.
6. Otherwise `denied` with `stale` / `bad-proximity` from step 4, else `far`.

On every new trust (steps 3-5) the menu-bar app shows a **non-blocking** floating notice at the top right, "<Phone> connected to Mote.", with the route (Bluetooth proximity / Tailscale / approved) and one **Block** button; it disappears after 15 s and needs no action. Block removes the key, adds its fingerprint and name to a persistent blocked list (Keychain account `blocked`) and revokes its open sessions (`err revoked`). The helper menu's Trusted phones submenu lists trusted phones (with Block) and blocked ones (with **Unblock**, which lets the phone connect again by proximity or Tailscale). "Revoke all devices" clears the trust list and the blocked list. The 6-digit code window moved to Advanced › Pair with code (web remote). Headless runs log `labs: new device <name> via <via> (k:<fp>)` (dry-run also prints `NEW DEVICE: {json}`).

Helper defaults that make "nothing on the Mac" work: instant pairing on, "Allow Tailscale connections" OFF until the user turns it on in the menu (so Tailscale auto-trust and tailnet addresses need that one switch; upgraders who never set it lose tailnet access until they do), "Launch at login" turned on once at the first launch.

## 5. Phone: discovery, first run and reconnects

**Discovery** runs every source at once and merges them by `hostId` (`mergeFound`), showing the first result as soon as it arrives (typically < 1 s on a LAN):

- **LAN sweep** (mandatory, multicast-free): TCP connect to port 8790 on every address of the phone's Wi-Fi /24 (or its smaller subnet), 128 at a time, 300 ms timeout, sockets bound to the Wi-Fi network (not a VPN); each open port is confirmed with `hello` (hostId) and `labs.pair.info`. This is what works on networks that drop multicast (Bonjour and `.local` silently find nothing there).
- **BLE** info, when Bluetooth is already permitted.
- **Bonjour** (`_mote._tcp`, TXT `id`), as an extra source.
- **Known addresses** of saved Macs: last LAN IP, MagicDNS name, tailnet IP.

Every run is time-boxed (8 s), and the card never waits open-endedly: phone not on Wi-Fi → "Turn on Wi-Fi" (`Settings.Panel.ACTION_WIFI`); nothing after 5 s → "Mote Helper not found on this network" with Retry, the install command and a small "Enter address"; a Tailscale-only Mac with the VPN off → the Tailscale prompt; a refusal → its reason in plain words.

**First run** opens straight into this discovery card ("Connect to your Mac", then "Connect to Tushar's MacBook Air"); "Use Bluetooth instead (no helper)" is the secondary path. No permission is asked at launch. On Connect the phone tries the Mac's addresses (LAN first, then tailnet) with a fresh proximity token when Bluetooth is usable; if the Mac answers `far` and Bluetooth isn't permitted or is off, it asks for it in context and retries; `stale` re-reads the token once.

**Reconnect** on every launch, silently and without a token (the key is trusted): fresh Bonjour address, last LAN IP / BLE info addresses, `<local>.local`, then the MagicDNS name, then the tailnet IP. If a trusted Mac doesn't answer for 4 s on Wi-Fi, the phone sweeps the LAN once and adds the new address (DHCP changes). If nothing answers for 15 s and the Mac is linked to a Bluetooth HID host, it switches to Bluetooth HID and probes the helper every 30 s to switch back. The pill shows the active path (Wi-Fi, Tailscale, BT). A reconnect never prompts: a refusal (revoked, blocked) shows the Mac as needing Add a Mac again.

### Bluetooth HID setup (same flow, optional)

Right after the first connect the card offers **Also pair Bluetooth for login screen & backup** (recommended, pre-checked, skippable; "Use Bluetooth instead (no helper)" stays the separate Bluetooth-only path). Bluetooth HID works where the helper can't (login screen, FileVault unlock, helper not running) and is the instant fallback.

1. The phone gets the Bluetooth permissions (in context), turns the adapter on if needed, registers as a keyboard / mouse and asks Android to make itself visible for 120 s (one Android prompt).
2. Over a trusted helper session it sends `{"t":"bt.pair","name":"<phone adapter name>"}` (authed only; `err unauth` before auth, `err busy` if one is running, `err bad` for a blank name).
3. The helper runs a 12 s classic inquiry for that name, then `IOBluetoothDevicePair` and answers the numeric-comparison confirmation itself, only for that device, that request (120 s) and while the requesting session is still authed. Progress: `{"t":"bt.pair.status","state":"searching"|"pairing"|"paired"|"failed","reason"?,"address"?}`, `reason` one of `notfound`, `timeout`, `refused`, `unavailable`. Android still shows its own "Pair with <Mac>?" dialog: one tap on the phone, nothing on the Mac.
4. `{"t":"bt.info"}` → `{"t":"bt.info","address":"AA:BB:…","name":…,"on":bool}` gives the Mac's Bluetooth address; the phone links it to the saved Mac and connects HID. From then on both links stay up and the pill shows both (Wi-Fi / Tailscale badge plus BT).

Limits: matching is by the phone's Bluetooth name (two phones with the same name, or a name not resolved within 12 s, give `notfound`; retry); IOBluetooth is not tested on real hardware by the automated suite; if a macOS version shows its own connection alert anyway, the user clicks it once. The auto-accept trusts the helper's Wi-Fi trust of the phone: someone who could get an instant-pairing key (section 6) could also get a Bluetooth bond.

## 6. Threat model

Proximity ≈ Bluetooth range (typically 10-30 m indoors, through walls). Instant pairing trades the old "click Allow on the Mac" for "be near the Mac or on its Tailscale account", which is what makes it one tap.

| Threat | Mitigation | Residual risk |
|---|---|---|
| Same LAN, far away (another flat on shared Wi-Fi, a compromised IoT device, a hotel network) | No proximity token without BLE range, no tailnet identity: `denied far`. Forged tokens fail the HMAC (`bad-proximity`, rate-limited); old ones are `stale`. | None beyond the pre-0.6 exposure (the helper port answers `hello` / `labs.pair.info`, which reveal the Mac's name, key fingerprint and addresses). |
| Someone within Bluetooth range (a neighbour through a wall, a person in the café) | Nothing: by design they can read a token and get trusted. They get a key-pinned device; the Mac shows "<Phone> connected to Mote. Block". | **Real.** A nearby attacker can take control of the Mac until the owner clicks Block (they may not be looking). Turn on "Require approval for new devices" where untrusted people are within ~30 m, or pause input. |
| Token relay (an accomplice near the Mac reads the token and sends it to someone far away) | The token lives ≤ 90 s and is single-use per window. | Possible with a live relay; it needs a device in BLE range anyway. |
| Token sniffing on the LAN (Wi-Fi has no transport encryption) | Single use: the honest phone's hello consumes it first. | An on-path attacker who can also beat or block the phone's hello could use it. |
| BLE spoofing: a fake "Mac" advertising the Mote UUID | The info carries the helper's key fingerprint; `labs.pair.hi` must be signed by that key over the phone's fresh `cn`. After first contact the key is pinned per `hostId`. | At first contact a spoofer can present its own key and name (trust on first use); it then receives the phone's input, but controls nothing on the real Mac. |
| Tailnet auto-trust abuse | Only nodes owned by the same Tailscale user (local `tailscaled` decides), never tagged or shared-in nodes. Without the CLI there is no auto-trust. | Anyone who can add a device to your Tailscale account can control the Mac. |
| Replay of a captured handshake | Signatures cover the per-connection `hello` nonce and the phone's `cn`. | None known. |
| Key theft at rest | Helper: Keychain. Phone: Keystore (or a Keystore-wrapped seed); app data is excluded from backup. | A rooted phone or an unlocked Mac account. |
| Active man in the middle on Wi-Fi | The handshake sends no reusable secret. | After authentication the session is unencrypted on the LAN (protocol.md section 4): an active attacker can inject input. Tailscale traffic is WireGuard-encrypted. |
| Lost phone | Block (when it reconnects), or "Revoke all devices" in the helper menu. | Until revoked it keeps working on the LAN / tailnet. |

## 7. Compatibility

- Upgrading the helper to the `secrets.v3` build: earlier Keychain items (`secrets.v2`, `secrets`, `devices`, `dev.mote.helper.bridge`, `dev.mote.helper.labs`) are stale and never read, so phones re-pair once (one tap). Remove the stale items in Keychain Access (search "dev.mote.helper"); the helper does not delete them because that can prompt.

- 0.6 phone + 0.5 helper with Labs on: the handshake works, but without a proximity characteristic the 0.5 helper falls back to its Allow alert. With Labs off (or older helpers): `unauth` → "Update Mote Helper, or use Pair with code".
- 0.5 phone + 0.6 helper: the phone sends no `prox`, so it is trusted only via Tailscale or with Require approval.
- The web remote keeps the 6-digit code.

## 8. Test flags (dry-run only)

| Flag | Effect |
|---|---|
| `--fake-proximity` | answer `{"t":"labs.pair.prox","dw":N}` (allowed before auth, `dw` -3...3) with `{"t":"labs.pair.prox","v":"<w>:<tk>","w":w}`, as if read over BLE |
| `--require-approval` | Require approval on for this run |
| `--fake-approve` / `--fake-approve-deny` | answer every approval prompt with Allow / Deny after 100 ms |
| `--fake-tailscale-owner ID` | every peer (loopback included) is a tailnet node owned by `ID` (`self` = this Mac's owner; `timeout` = answers "not the same owner" after 3 s, like a hung CLI) |
| `--no-instant-pairing` | turn instant pairing off |
| `--stdin-control` + `block` / `unblock` | click Block for the most recent new device / Unblock the most recently blocked |
| `--fake-bt-pair` / `--fake-bt-pair-fail REASON` | `bt.pair` goes searching → pairing → paired (`02:00:00:00:00:01`), or fails with REASON, without real Bluetooth |

Tests: `swiftc -O helper/Sources/MoteHelper/Proximity.swift helper/tests/proximity/main.swift -o /tmp/prox-test && /tmp/prox-test` (token windows, skew, replay); `node helper/tests/pairing.mjs` (spawns dry-run helpers for every trust path); Android `PairingIntegrationTest` (the app's handshake, sweep and reconnect against the dry-run helper), `SweepTest`, `FirstRunTest`; opt-in `LiveSweepTest` (`-Dmote.liveSweep=192.168.1.0/24`, read-only, against a real helper); emulator `SweepDiscoveryTest` (Bonjour faked empty, `-e moteSweepPort N` with a dry-run helper on the host).
