# J4 first-connect: first connection between phone and helper

Phase 2 spec (format: [README.md](README.md)). Derived from `android/.../pairing/{InstantPairing,ConnectUi,BleScan}.kt`, `helper/Sources/MoteHelper/{InstantPairing,Hub,Vault}.swift`, `docs/pairing.md`, `docs/protocol.md` (1.5). Supersedes the drafts `connect-next.md`, `connect-now.md`, `instant-pairing.md`, `wifi-helper.md` (kept for `report.mjs` until phase 4).

## UNDEFINED
- U5 Trust written while the Keychain is unreadable (memory only, lost at next launch).
- U6 Blocked list while the Keychain is unreadable (fail open today).
- U11 "Allow Tailscale connections" default (protocol.md: off; code and pairing.md: on).
- U13 Route to Settings when Nearby devices is permanently denied.

## Goal
One tap on "Connect to <name>" trusts the phone with no Mac-side action when the phone is next to the Mac (proximity) or on the same Tailscale owner, pins the helper key, and lands on the remote. Every refusal names its cause and next step.

## Preconditions
| Dimension | Values covered |
|---|---|
| Install | fresh both sides / phone reinstalled (helper still trusts old phone key) / helper reinstalled (new identity) |
| Phone Nearby devices | granted / not asked (NeedBluetooth) / denied / permanently denied / Android < 12 (no BLE proximity) |
| Mac Bluetooth | allowed + advertising / denied / off |
| Helper settings | instant pairing on (default) / off / Require approval on / Allow Tailscale on (code default) / off |
| Distance | next to Mac / far (RSSI or no token) / token from previous window (stale) / replayed (bad-proximity) |
| Network | same LAN / LAN without mDNS / tailnet only / unreachable |
| Phone state on helper | unknown / trusted / blocked |
| Keychain | readable / unreadable (`readResult=error`) |
| Signing | any; trust is by Ed25519 key, not signature |

## Success criteria
- Helper: `session/open` then `session/paired{via: proximity|tailscale|approval|code}`; phone in `diag.sessions[]`.
- Phone: `session/paired`, TrustRecord saved with `hostId` + helper key; remote shown "Connected".
- Next launch reconnects silently (J5 RC-1).
- No Mac-side click unless Require approval is on.

## Main path
| ID | Actor | Step | Expected | Failure mode | Detected by | User sees | Recovery | Level | Test | Status |
|---|---|---|---|---|---|---|---|---|---|---|
| FC-1 | User | Taps "Connect to <name>" | "Connecting…"; WS to LAN candidates, then tailnet | n/a | `transport/connect.try`; helper `transport/conn.accepted`, `transport/ws.upgrade`, `session/open` | "Connecting…" | n/a | L2 | android:ConnectNextJourney#main | pass |
| FC-2 | App | Reads BLE info + proximity token (30 s window, ±1, single use) | token sent in hello | BLE not ready, SDK >= 31 | helper `session/pair.failed{reason:far}` → phone NeedBluetooth | "Mote uses Bluetooth once to check this phone is next to your Mac." / "Allow and connect" | Allow | L2 | android:ConnectNextJourney#needBluetooth | pass |
| FC-3 | Helper | Trust order: blocked → known → require approval → proximity → Tailscale owner → refuse | proximity OK: `session/paired`, trusted list written to `secrets.v3` | n/a | `session/paired`; `keychain/write{ok:true}` | "Connected" | n/a | L2 | android:PairingIntegrationTest#nearbyPhoneIsTrustedWithNoMacActionThenReconnectsSilently; android:InstantPairingJourney#main | pass |
| FC-4 | Helper | Phone far / token stale / replayed | refuse `far`, `stale` (phone retries once), `bad-proximity` | n/a | `session/pair.failed{reason}`; phone `session/pair.failed` | pairFailure copy per code | move closer, retry | L2 | android:PairingIntegrationTest#farAwayStaleAndReplayedTokensAreRefused; android:ConnectNextJourney#far; android:ConnectNextJourney#stale; android:ConnectNextJourney#badProximity | pass |
| FC-5 | Helper | Proximity with real radios at desk distance vs across the room | near trusted, far refused | RSSI threshold wrong for the room | helper/Tests/proximity | per FC-3/FC-4 | n/a | L4 | MANUAL-15 | untested |
| FC-6 | User | Nearby permanently denied, taps "Allow and connect" | route to app Settings | `perms.launch` returns denied, nothing else | `permission/result{ctx:connect,granted:false}` | button does nothing | none in app | L2 | TODO-FC-6 | fail (D-FC-1, U13) |
| FC-7 | Helper | Same Tailscale owner, no token | trusted via Tailscale | other owner | `session/paired{via:tailscale}` / `pair.failed{reason:far}` | "Connected. Trusted via Tailscale" | n/a | L2 | android:PairingIntegrationTest#sameTailscaleOwnerIsTrustedWithoutAToken; android:PairingIntegrationTest#anotherTailscaleOwnerWithoutATokenIsFar | pass |
| FC-8 | Helper | Require approval on | Mac alert; Allow → paired; Deny → `denied`; no answer → `timeout` | n/a | `ui/newdevice.shown`; `session/paired` / `pair.failed{reason:denied}` | "Click Allow on <Mac>…" | Allow on Mac | L2 | android:ConnectNextJourney#requireApprovalAllow; android:ConnectNextJourney#requireApprovalDeny; android:ConnectNextJourney#requireApprovalTimeout | pass |
| FC-9 | Helper | Phone on blocked list | refuse `blocked` | copy says "Unblock it with Revoke all devices in the Mote Helper menu"; menu also has Devices › Unblock | `session/pair.failed{reason:blocked}` | blocked copy | Devices › Unblock / Revoke all | L2 | android:ConnectNextJourney#blocked; android:RecoveryJourney#revokeUnblocks | pass |
| FC-10 | Helper | Instant pairing off | refuse `unauth`; pairing window path | n/a | `session/pair.failed{reason:unauth}` | unauth copy | Advanced › "Pair with code (web remote)…" | L2 | android:PairingIntegrationTest#instantPairingOffOnTheMacIsUnauth; android:ConnectNextJourney#instantOff | pass |
| FC-11 | User | Pair with code (web remote / Android Pair with code) | code accepted, token issued | wrong code / rate limit / window closed | `session/pair.failed{reason}` | "wrong code", rate copy | retry | L2 | android:ConnectNowJourney#main; android:ConnectNowJourney#wrongCode; android:ConnectNowJourney#rate; android:ConnectNowJourney#closed; node:helper/tests/pairing.mjs | pass |
| FC-12 | App | Helper key differs from the pinned one for this `hostId` | refuse with `helper-key`, never send input | n/a | `transport/connect.mismatch` | "key doesn't match" copy | Forget the Mac, connect again | L2 | android:PairingIntegrationTest#aMacWithAnotherKeyIsRejected; android:ConnectNextJourney#helperKey; android:HelperIntegrationTest#aDifferentMacAtTheSameAddressIsSkippedNotAuthed | pass |
| FC-13 | App | Helper not running / port closed | ConnectException copy | copy says "Open it from Applications"; install.sh installs to `~/Applications` | `transport/connect.failed` | "Open it from Applications" | open from `~/Applications` | L2 | android:ConnectNextJourney#unreachable | fail (D-FC-2) |
| FC-14 | App | No answer within the socket timeout | `timeout` copy | n/a | `transport/connect.failed{error:timeout}` | timeout copy | Retry | L2 | android:ConnectNextJourney#timeout | pass |
| FC-15 | User | Two Macs found | both listed, each connect pins its own key | n/a | `discovery/nsd.found` x2 | two rows | n/a | L2 | android:ConnectNextJourney#twoMacs | pass |
| FC-16 | App | After pairing: "Also pair Bluetooth for login screen & backup" | trusted phone asks the Mac to pair Bluetooth HID | Mac Bluetooth denied/off | helper `protocol/in{t:bt.pair}`, reply `bt.pair.status{state}`; `diag.features.bluetooth.blockedBy` | offer card / failure copy | skip | L2 | android:PairingIntegrationTest#trustedPhoneAsksTheMacToPairBluetooth; android:ConnectNextJourney#btOffer | pass |
| FC-17 | Helper | Second phone in the same proximity window | each token single use; second phone needs its own token | n/a | `session/pair.failed{reason:bad-proximity}` on replay | per FC-4 | n/a | L2 | android:InstantPairingJourney#sameWindowSecondPhone | pass |
| FC-18 | Helper | Keychain unreadable (`readResult=error`) at pairing time | trust persists | trust only in memory; `keychain/write{ok:false}` (or overwrite, see D-FC-3) | `keychain/write{status,ok}`; `diag.keychain.writeStatus` | "Connected" now, refused after the next helper launch (J5 RC-9) | none in UI | L3 | TODO-FC-18 | fail (D-FC-3, U5) |
| FC-19 | Helper | Keychain unreadable and phone was blocked | refuse `blocked` | blocked list not loaded, phone re-trusted by proximity | `session/paired` for a previously blocked key | "Connected" | none | L3 | TODO-FC-19 | fail (D-FC-4, U6) |
| FC-20 | User | Real phone + Mac, home network, first connect | FC-1..3 in <= 5 s from tap | any | phone Diagnostics; `MoteHelper --diag` | "Connected" | per row | L4 | MANUAL-16 | untested |

## Defects
- D-FC-1 NeedBluetooth has no route to Settings after permanent denial.
- D-FC-2 ConnectException copy says "Applications"; install.sh installs to `~/Applications`.
- D-FC-3 `Vault.update` saves regardless of `readResult`. With `error`, `SecItemUpdate` under UIFail either fails (trust kept in memory only; the log line "kept in memory until the next launch" is then right) or succeeds and overwrites the old blob (mac-update-D1). Which one happens for a foreign-partition item is unverified (L1 fixture needed).
- D-FC-4 Blocked list is lost with an unreadable Keychain: a blocked phone near the Mac is trusted again.
- D-FC-5 protocol.md §4 says "Allow Tailscale connections" defaults off; `Config.swift` defaults it on when unset (pairing.md agrees with code).
