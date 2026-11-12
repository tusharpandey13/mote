# J5 reconnect: reconnect to a known Mac

Phase 2 spec (format: [README.md](README.md)). Derived from `android/.../net/{Net,WsTransport}.kt`, `pairing/{InstantPairing,Sweep}.kt`, `hid/Reconnect.kt`, `ui/HostsUi.kt`, `helper/Sources/MoteHelper/{InstantPairing,Hub,Vault}.swift`, `docs/pairing.md` §5. Replaces the phase-1 draft (reconnect-D1..D7 carried); folds in `bt-quirks.md` and `recovery.md` entry points.

## UNDEFINED
- U5 Helper identity unreadable at launch (new key every launch).
- U12 What the phone does when the pinned helper key changed (reinstall, Keychain loss).
- U18 How long the phone keeps retrying in the foreground before saying anything beyond "Connecting…".

## Goal
Opening Mote (or bringing it to the foreground) reconnects to the selected Mac with no user action, over the best route available, and explains it when it cannot.

## Preconditions
| Dimension | Values covered |
|---|---|
| Install | phone trusted, helper unchanged / helper updated same identity / helper reinstalled or Keychain lost (new key) / phone revoked or blocked on the Mac |
| Route | same LAN (Bonjour) / LAN, Mac IP changed (DHCP), mDNS blocked / tailnet / Bluetooth HID linked / none |
| Mac | awake, helper running / asleep / helper not running / helper restarting |
| Phone | foreground / backgrounded > 30 s / network switch (Wi-Fi ↔ cellular) |
| Bluetooth (HID path) | bonded both sides / Mac forgot bond / phone forgot bond / adapter off |
| Keychain on Mac | readable / unreadable at launch |

## Success criteria
- Phone `transport/connect.ready` and helper `session/authed` within 2 s of foreground on LAN; no pairing UI.
- Mac moved IP: found by the one-shot LAN sweep after `REDISCOVER_MS` = 4 s.
- Helper unreachable 15 s (`FALLBACK_MS`) and a Bluetooth host linked: falls back to HID, probes the helper every 30 s (`PROBE_MS`), returns when it answers.
- `auth`/`revoked` stop retries and say why (WsTransport returns `Failed(code)`).

## Main path
| ID | Actor | Step | Expected | Failure mode | Detected by | User sees | Recovery | Level | Test | Status |
|---|---|---|---|---|---|---|---|---|---|---|
| RC-1 | App | Launch with a trusted helper host | `startScreen` REMOTE; `Net.foreground=true` → `ws.start()`, candidates in order | n/a | `lifecycle/foreground`; `transport/connect.try` | pill "<name>", LED waiting then OK | n/a | L2 | android:DailyJourney#main; android:HostsTest#fallbackOrderIsNsdThenCachedIpThenLocalThenTailnetThenManual | pass |
| RC-2 | Helper | Known phone key, silent auth | `session/authed`; no Mac UI | n/a | helper `session/authed`; phone `transport/connect.auth`, `connect.ready` | badge WI-FI, controls enabled | n/a | L2 | android:PairingIntegrationTest#nearbyPhoneIsTrustedWithNoMacActionThenReconnectsSilently; android:InstantPairingJourney#reconnectLan; android:HandshakeTest#outOfOrderAndSilentReconnect | pass |
| RC-3 | App | Mac IP changed, Bonjour silent | after 4 s one LAN sweep, new address saved | Mac on other subnet | `discovery/sweep.start`, `sweep.open` | "Connecting…" ~4-5 s | n/a | L2 | android:InstantPairingJourney#reconnectNewIp; android:PairingIntegrationTest#lanSweepFindsAndConfirmsTheHelper | pass |
| RC-4 | App | Off LAN, tailnet route | tailnet names/IPs tried after LAN | Tailscale VPN off | `transport/connect.try{route:tailnet}`; `transport/tailscale.prompt` | badge TAILSCALE / prompt | open Tailscale | L2 | android:InstantPairingJourney#reconnectTailnet; android:DailyJourney#tailnet | pass |
| RC-5 | App | Helper unreachable 15 s, Bluetooth host linked | HID fallback; probe helper every 30 s; back to Wi-Fi when it answers | no linked btAddress: stays "Connecting…" | `transport/select`; `transport/connect.backoff{ms}` | badge BT | n/a | L2 | android:InstantPairingJourney#btFallback; android:ReconnectJourney#fallbackBt | pass |
| RC-6 | App | Backoff 0.5 s → 8 s (jitter); kick on network change / foreground | retries skip wait on `discovery/network` | n/a | `transport/connect.backoff`, `connect.dropped` | "Connecting…" | n/a | L0 | android:ReconnectJourney#helperNetwork | pass |
| RC-7 | App | Mac asleep vs helper not running | asleep: no answer ("<name> seems asleep or away. Wake it…"); not running: refused ("Mote Helper isn't running") | offline phone told "isn't running" (reconnect-D5) | `discovery/sweep.probe{result:TIMEOUT/REFUSED}` | per copy | wake Mac / open helper | L0 | android:SweepTest#asleepIsNotTheSameAsHelperNotRunning; android:DailyJourney#asleep; android:DailyJourney#nohelper | pass |
| RC-8 | Helper | Helper restarted (same identity) mid-session | phone drops, reconnects silently | n/a | `transport/connect.dropped` then `connect.ready`; helper `lifecycle/start`, `session/authed` | brief "Connecting…" | n/a | L2 | e2e:app.spec.ts "helper killed mid-session -> disconnected UI -> restarted -> reconnects" | pass |
| RC-9 | Helper | Helper launched with `keychain/read{result:error}` | identity loaded | `LabsKeyStore.identity()` mints a new Ed25519 key each launch | helper `keychain/read{result:error}`, `keychain/write{ok:false}`; phone `transport/connect.mismatch` → `Failed(auth)` | "key doesn't match" copy every launch | Forget the Mac, connect again (lost again next launch) | L3 | helper:VaultUnreadableTests#partitionMismatchDoesOneInteractiveReadAndRewrites (identity kept); helper:EphemeralTrustTests#helloReportsEphemeralKey; helper:HubKeychainTests#pinnedPhoneGetsKeychainUnavailableWhileEphemeral (`err keychain-unavailable`, `ephemeral: true`: the phone keeps its token); helper/tests/keychain.mjs (partitionmismatch keeps the seed fp; denied: `hi.ephemeral`); the phone UI "This Mac has a new key. Trust it?" belongs to the Android agent (U12) | pass helper side; phone UI open (U5, U12) |
| RC-10 | Helper | Phone revoked / blocked on the Mac | refuse `revoked` / `blocked`; phone stops (`auth`) | n/a | helper `session/revoked`, `session/auth.failed`; phone `transport/connect.failed{code}` | revoked copy | connect again (re-trust by proximity) / Unblock | L2 | android:RecoveryJourney#revokedRelaunch; android:RecoveryJourney#block; android:InstantPairingJourney#reconnectRefused; android:HelperIntegrationTest#wrongTokenFailsWithAuthAndStops | pass |
| RC-11 | App | App backgrounded | socket closed 30 s after `foreground=false`; reopened on return | n/a | `lifecycle/foreground{v:false}`; `session/closed` | nothing (no notification over Wi-Fi, daily-D1) | n/a | L2 | android:ReconnectJourney#background; android:DailyJourney#background; android:NpNotificationJourney#backgroundKeepsPlaying | pass (socket closes 30 s after background unless the now-playing service holds it) |
| RC-12 | App | Bluetooth HID host selected, out of range | `Reconnect` targets selected bonded host, gives up after `MAX_ATTEMPTS`=3 until something changes | after 3 attempts pill and notification still say searching (reconnect-D3) | `transport/bt.state` | "Searching for <name>…" | tap the device | L0 | android:ReconnectTest#capsAttempts; android:ReconnectTest#selectedBondedWins | fail (D-RC-3) |
| RC-18 | App | U18: helper link Connecting for 15 s | Searching card "Still looking for <Mac>" with causes (asleep or away, Mote Helper not running, other Wi-Fi / Tailscale off) and Try again / Devices | n/a | `ui/banner{kind:searching}` | card on the remote screen | per cause | L0 | android:UxGatesTest#searchingCardAfterFifteenSeconds | pass (buttons laid out with weights after UxCardsTest found a clipped button) |
| RC-19 | App | U12: pinned helper key changed | card "This Mac has a new key. Trust it?"; Trust re-pairs with the BLE proximity check (`InstantPairing.trustNewKey`), Not now keeps the old pin; never sends input meanwhile. Hook for the helper message: `HostKeyChange.onHelperNewKey` | refuse with `helper-key` | `ui/banner{kind:new-key}`; `ui/tap{target:new-key-trust}` | the card | Forget the Mac | L0 | android:HandshakeTest#pinMismatchAsksTheUser; android:UxGatesTest#keyMismatchIsRecognised; android:UxCardsTest#newKeyAsksToTrust | pass (explicit helper message `labs.pair.hi newKey`: field parsed, UI hook ready; helper side on fix-vault) |
| RC-20 | App | U12 contract: Mac in ephemeral mode (`labs.pair.hi ephemeral:true`, or classic `auth` code `keychain-unavailable`) | card "This Mac can't save pairings" (Keychain unavailable, approve on the Mac); key accepted for this session only, stored pin and token are kept, nothing deleted | refuse a bad signature even with a stale pin | `ui/banner{kind:mac-ephemeral}` | the card, OK | repair the Mac Keychain | L0 | android:HandshakeTest#ephemeralKeyIsSessionOnly; android:HandshakeTest#keychainUnavailableHasItsOwnCopy; android:UxCardsTest#ephemeralMacSaysSoAndKeepsNothing | pass (helper side on fix-vault) |
| RC-13 | macOS | Mac removed the Bluetooth bond, phone kept it | phone detects and offers re-pair | never detected (reconnect-D4) | none | "Searching…" forever | forget on phone, pair again | L2 | android:BtQuirksJourney#forgetOnMac | fail (D-RC-4) |
| RC-14 | User | Long-press › Forget a helper Mac | trust and pin removed; if it was the last Mac → FindMacScreen | artboard says pair again from Devices (reconnect-D7) | `ui/tap`; `ui/screen{screen:FIND_MAC}` | FindMacScreen | connect again (J4) | L2 | android:ReconnectJourney#forgetHelper | pass |
| RC-15 | User | Bluetooth-only row: long-press › Forget | forget offered | not offered (reconnect-D1, D2) | n/a | nothing | Android Bluetooth settings | L2 | TODO-RC-15 | fail (D-RC-2) |
| RC-16 | User | Real phone, real Mac: sleep/wake, router DHCP change, walk out of Wi-Fi with BT linked | RC-2, RC-3, RC-5 | any | phone Diagnostics, `MoteHelper --diag` sessions | per row | per row | L4 | MANUAL-17 | untested |
| RC-17 | Helper | L2 restart keeping identity | `--keychain-service NAME` keeps `secrets.v3` across restarts (`--memory-store` does not) | test uses `--memory-store`: new hostId/key | n/a | n/a | n/a | L2 | TODO-RC-17 | untested |

## Defects
- D-RC-1 Unreadable Keychain → new helper identity per launch → every trusted phone fails `helper-key`/`auth` until re-paired, every launch.
- D-RC-2 (reconnect-D1, D2) Bluetooth-only rows cannot be forgotten in-app though copy says to.
- D-RC-3 (reconnect-D3) after 3 HID attempts the UI keeps saying searching.
- D-RC-4 (reconnect-D4) Mac-side unpair never detected.
- D-RC-5 (reconnect-D5) offline phone may be told the helper isn't running.
- D-RC-6 (reconnect-D6, D7) artboard Searching card, buttons and Forget flow differ from code.
