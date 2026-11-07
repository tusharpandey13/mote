# Labs: Instant pairing (preview)

Status: preview behind a Labs flag on both sides, default off. Android: Settings › Labs › "Instant pairing (preview)". Helper: menu › Labs › "Instant pairing" (or `--labs-pairing` for one run). Nothing here changes `docs/protocol.md` (still v1.4); every message is under `labs.pair.*` and a helper with the flag off answers them like any unknown message before auth (`err` `unauth`).

Goal: no codes and no typed addresses. The Mac always advertises over BLE. First connect: one sheet on the phone ("Connect to <Mac>?") and one alert on the Mac ("Allow <Phone> to control this Mac?"). No prompt at all for a phone on the same Tailscale account. Afterwards the phone reconnects silently: LAN, then tailnet, then Bluetooth HID.

## 1. Keys and fingerprints

- Both sides hold one long-lived **Ed25519** key pair. Helper: private key in the login Keychain (generic password, service `<keychain service>.labs`, account `identity`; `--memory-store`: a fresh key per run). Phone: Android Keystore Ed25519 key (API 33+, where the Keystore supports it), else a 32-byte seed AES-256-GCM-wrapped by a Keystore key in an app-private pref.
- `pub`: the 32-byte raw public key, lower-case hex (64 chars). `sig`: the 64-byte Ed25519 signature, hex (128 chars).
- `fp` (fingerprint): first 16 bytes of SHA-256(raw public key), hex (32 chars). Shown grouped (`3f2a 9c01 …`) where a user might compare it.
- Device id of a trusted phone on the helper: `k:` + its `fp`.

Signed strings (UTF-8, `|`-separated, no trailing newline):

```
client proof: mote-labs-pair-v1|client|<hostId>|<hello nonce>|<client pub>
helper proof: mote-labs-pair-v1|helper|<hostId>|<hello nonce>|<cn>|<client pub>
```

`hello nonce` is the per-connection nonce from the protocol's `hello`, `cn` a fresh 32-hex client nonce. Both proofs are bound to this connection and this Mac, so a captured exchange can't be replayed elsewhere or later.

## 2. BLE discovery

The helper keeps advertising the Mote service (`3abad8f4-b472-41fc-bfc5-6f645f33564b`, local name `Mote`) while it runs. With Labs on, the service also has a readable **info characteristic** `6d6f7465-0001-4c61-6273-50616972696e` (no notify, no auth; long reads with offsets work). Its value is compact UTF-8 JSON, at most 512 bytes:

```json
{"v":1,"id":"<hostId>","n":"Tushar's MacBook Pro","fp":"<fp>","lan":["192.168.1.20"],"ts":"mac.tail1234.ts.net","t4":"100.101.102.103","p":8790}
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

The phone scans for the service UUID with `BLUETOOTH_SCAN` (`neverForLocation`, no location permission), connects GATT, reads the characteristic, disconnects, and lists each Mac once (keyed by `id`) in a bottom sheet. Bonjour (`_mote._tcp`, TXT `id`) stays an extra source of LAN addresses. Over the WebSocket, `labs.pair.info` returns the same object (used by tests, where `--no-ble` is set; in `--dry-run` the helper also prints it as `LABS PAIR INFO: {json}`).

## 3. Handshake

Runs on the WebSocket (or the BLE GATT transport) after `hello`, instead of `pair` / `auth`.

```
phone                                         helper
  | <------------- hello {nonce, hostId, ...} -|
  | -- labs.pair.hello {pub, name, cn, sig} -> |   sig = client proof
  | <- labs.pair.hi {pub, fp, sig, host, hostId}|   sig = helper proof; phone checks fp against BLE info / its pin
  |                                            |
  |   trusted key, or same Tailscale owner:    |
  | <------------ labs.pair.ok {via, id, name} |
  | <------------ authed {id, name, paused, ax}|   standard; input works from here
  |                                            |
  |   unknown key: the Mac asks the user       |
  | <------------ labs.pair.pending {timeout}  |
  | <------------ labs.pair.ok + authed        |   Allow
  | <------------ labs.pair.denied {reason}    |   Deny / timeout / busy / rate
```

| Message | Direction | Fields |
|---|---|---|
| `labs.pair.info` | phone -> helper | none. Reply: `labs.pair.info` with the BLE info fields (section 2). Allowed before auth. |
| `labs.pair.hello` | phone -> helper | `pub`, `name` (phone name, ≤ 64 chars), `cn` (32 hex), `sig` |
| `labs.pair.hi` | helper -> phone | `pub`, `fp`, `sig`, `host` (Mac name), `hostId` |
| `labs.pair.pending` | helper -> phone | `timeout` (seconds the Mac waits for an answer, 60) |
| `labs.pair.ok` | helper -> phone | `via`: `trusted` (known key), `approved` (the user clicked Allow), `tailscale` (same tailnet owner); `id` (`k:<fp>`), `name` |
| `labs.pair.denied` | helper -> phone | `reason`: `denied`, `timeout`, `busy` (another prompt is open), `rate` (too many prompts; `retryAfter` seconds) |

Errors (`err`): `bad` (malformed fields or a bad signature; counts as a failed attempt for the per-peer limiter), `rate` (`retryAfter`), `unauth` (Labs is off on this Mac: the phone shows "Turn on Instant pairing in the Mote Helper menu").

The phone stores, per `hostId`: the helper `pub`, the addresses from the info and from `hello`/connects, and `via`. On later connects it requires the same helper key (pinning): a different key is treated as a different Mac and the phone closes the connection without sending input. (The phone's proof carries no secret, so a wrong helper learns nothing reusable from it.)

After `labs.pair.ok` the session is authenticated exactly like after `auth`: all input, Spaces and pause rules apply, and the session is listed under "Connected devices".

## 4. Trust decisions on the helper

In order:

1. **Known key** (in the Labs trust list): `ok via trusted`, no prompt.
2. **Tailscale auto-trust**: the peer address is a tailnet address (100.64/10 or `fd7a:115c:a1e0::/48`) and Tailscale says the peer node belongs to the same user as this Mac: `tailscale whois --json <ip>` → `UserProfile.ID`, compared with `tailscale status --json --peers=false` → `Self.UserID`. Tagged nodes (`Node.Tags` non-empty) never match. On a match the key is stored (`via tailscale`) and `ok via tailscale`. The CLI is looked up at the same paths as for the MagicDNS name; if it is missing or times out (3 s), fall through to 3.
3. **Ask the user**: an alert on the Mac, "Allow <phone name> to control this Mac?" with the phone's fingerprint and how it connects (Wi-Fi address, Tailscale, Bluetooth), buttons **Allow** / **Deny**, auto-deny after 60 s. Allow stores the key (`via approved`).

Prompt rate limits (so a nearby attacker can't spam the Mac):

- one alert at a time (others: `denied busy`);
- per peer (IP or BLE central): 3 prompts per 10 minutes; globally: 6 prompts per 10 minutes (`denied rate`, `retryAfter`);
- a key the user denied gets no new prompt for 10 minutes (`denied denied`, at once);
- bad signatures count towards the existing per-peer limiter (5 failures / minute).

Turning Labs on in the menu also turns on "Allow Tailscale connections" (tailnet peers are needed for Tailscale reconnects) and, once, "Launch at login", so the Mac is always discoverable. "Revoke all devices" also clears the Labs trust list. Turning Labs off stops answering `labs.pair.*` and removes the info characteristic; trusted keys stay until revoked.

## 5. Phone: connection order and fallback

For a trusted Mac the phone tries, in order: the fresh Bonjour address, the LAN addresses from the last BLE info and the last working LAN address, `<local>.local`, then the tailnet (MagicDNS name, then `100.x`). If none answers for 15 s and the Mac is linked to a Bluetooth HID host on this phone, it switches to Bluetooth HID and keeps probing the helper every 30 s in the background, switching back when it answers. The pill shows the active path (Wi-Fi, Tailscale, BT). A `labs.pair.ok via tailscale` shows "Trusted via Tailscale" under the Mac in the sheet. BLE discovery needs Android 12+ (`BLUETOOTH_SCAN` with `neverForLocation`; older versions require the location permission for BLE scans, which Mote doesn't ask for), so Android 9-11 find Macs through Bonjour only. A reconnect never prompts: if the Mac answers `labs.pair.pending` (it dropped this phone), the phone stops and shows the Mac as needing a new Connect.

## 6. Threat model

| Threat | Mitigation |
|---|---|
| BLE spoofing: a fake "Mac" advertising the Mote UUID with a copied name | The info carries the helper's key fingerprint; the phone requires `labs.pair.hi` signed by that key over its own fresh `cn`. A spoofer can copy the fingerprint but not sign. After the first connect the key is pinned per `hostId`. At first contact a spoofer can still present its *own* key and name (TOFU); it then controls nothing on the real Mac and only receives the phone's input, which is the same exposure as connecting to the wrong host. |
| A nearby attacker getting control of the Mac | Nothing is trusted without the user clicking Allow on the Mac (or the same Tailscale owner). The phone's tap alone grants nothing. |
| Prompt spam / approval fatigue | One alert at a time, per-peer and global prompt budgets, 10-minute cool-down after Deny, 60 s auto-deny. The alert names the phone and shows its fingerprint and route. |
| Tailnet auto-trust abuse | Only for peers whose Tailscale node belongs to the same user as this Mac (`whois` UserProfile.ID == Self.UserID), never tagged nodes, never shared-in nodes of other users. The decision comes from the local tailscaled, not from anything the peer sends. Without the CLI there is no auto-trust. |
| Replay of a captured handshake | Both signatures cover the per-connection `hello` nonce (and the phone's `cn`); a replay on a new connection fails. |
| Key theft at rest | Helper: Keychain. Phone: Keystore (or a Keystore-wrapped seed); app data is excluded from backup. |
| Sniffing / tampering on Wi-Fi | Unchanged from protocol.md section 4: no transport encryption on the LAN. The handshake sends no secret (only public keys and signatures), so unlike the 6-digit pairing a sniffer learns no reusable credential; an active man in the middle can still inject into a session after it is authenticated. Tailscale traffic is WireGuard-encrypted. |
| Lost phone | "Revoke all devices" also clears the Labs trust list. |

Known limits of the preview: the alert needs the menu-bar app (headless runs without `--fake-approve` time out); the BLE GATT transport for the handshake is not wired on Android yet (the phone uses BLE for discovery only and connects over Wi-Fi / Tailscale); first contact is trust-on-first-use; the BT HID fallback and Keystore Ed25519 need a real device to verify.

## 7. Test flags (dry-run only)

| Flag | Effect |
|---|---|
| `--labs-pairing` | Labs on for this run |
| `--fake-approve` | answer every prompt with Allow after 100 ms instead of showing an alert |
| `--fake-approve-deny` | answer every prompt with Deny |
| `--fake-tailscale-owner ID` | pretend Tailscale is present: this Mac's owner is `self`, every peer (loopback included) is a tailnet peer owned by `ID` (`self` → auto-trust; `timeout` → answers "not the same owner" after 3 s, like a hung CLI) |

`node helper/tests/labs-pairing.mjs <port>` checks the handshake against `MoteHelper --dry-run --headless --no-ble --memory-store --labs-pairing --fake-approve --port N`; the Android `LabsPairingIntegrationTest` drives the same helper with a fake BLE info payload.
