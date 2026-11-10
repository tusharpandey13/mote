# J7 mac-update: update the helper and the app

Phase 2 spec (format: [README.md](README.md)). Derived from `site/install.sh`, `helper/Sources/MoteHelper/{Vault,InstantPairing,Diagnostics,Signing}.swift`, `docs/signing.md`, `android/app/build.gradle*` signing config. Replaces the phase-1 draft (mac-update-D1..D5 carried). File name kept for `MacUpdateJourney`.

## UNDEFINED
- U5 Keychain item unreadable after the update changed the partition (Mote Helper Local: every build; identity switch: once).
- U9 TCC services to reset on a signature change.
- U12 Phone behaviour when the helper key changed.
- U15 Open at login after the bundle moved or was replaced.
- U21 How a user learns that a new helper or app release exists.

## Goal
Re-running the installer (helper) or installing a newer APK (app) keeps every pairing, permission and setting; the phone reconnects silently. When an update cannot keep something (identity switch), the user is told once, on the Mac and the phone, and recovers in one step.

## Preconditions
| Dimension | Values covered |
|---|---|
| Helper update kind | same identity, developer-id (team partition) / same identity, mote-local (cdhash partition) / identity switch mote-local → developer-id / from adhoc or apple-development (dev builds) |
| Permissions before | all granted / some denied / stale |
| Keychain before | found / never created |
| Phone | trusted, on LAN / on tailnet / Bluetooth HID linked |
| App update | same keystore APK over old / different keystore (debug over release: package `dev.mote.remote.dev` differs) / reinstall after uninstall |
| Protocol | helper and app same version / app newer than helper / helper newer than app |

## Success criteria
- Same-identity update: `diag.keychain.readResult=found`, `partitionMatchesSigning=true`; TCC rows unchanged; phone `session/authed` without UI.
- Identity switch: stale TCC reset once (all services), Keychain recovered with one prompt or an explicit re-pair offer; never a silent new identity.
- App update: TrustRecords, selected host, settings kept.

## Main path
| ID | Actor | Step | Expected | Failure mode | Detected by | User sees | Recovery | Level | Test | Status |
|---|---|---|---|---|---|---|---|---|---|---|
| UP-1 | User | Re-runs install.sh (J1 INS-1..16) | new build replaces old in `~/Applications`, Setup opens | see J1 | J1 rows | J1 | J1 | L2 | e2e:install.spec.ts "replaces an older install; x86_64 gets the x86_64 asset" | pass |
| UP-2 | Helper | Same DR (developer-id or mote-local self-signed cert) | no tccutil reset; AX/SR/Bluetooth stay granted | DR changed unexpectedly | install.sh "signed differently" line; `diag.signing.designatedRequirement` | Setup rows "Allowed" | n/a | L2 | e2e:install.spec.ts "upgrade from a differently signed (ad-hoc) build resets its stale grants once" (DR-differs branch only); TODO-UP-2 (same-DR branch) | untested |
| UP-3 | Helper | Developer ID update, same team: Keychain partition `teamid:` unchanged | `keychain/read{result:found}` | n/a | `diag.keychain{readResult,partitionMatchesSigning}` | nothing | n/a | L3 | MANUAL-22 | untested |
| UP-4 | Helper | Mote Helper Local update: partition is the cdhash, which changes every build | one Keychain prompt (signing.md) or silent read | code never prompts (`kSecUseAuthenticationUIFail`): `readResult=error` on every update | `keychain/read{result:error}`; `diag.keychain.partitionMatchesSigning=false`; `diag.features.keychain.blockedBy=keychain-unreadable` | nothing on Mac; phone RC-9 | none in UI | L3 | MANUAL-23 | fail (D-UP-1, U5) |
| UP-5 | Helper | Read error then any trust change calls `Vault.update` → save | existing item never overwritten | save not gated on `readResult` (mac-update-D1); outcome unverified | `keychain/write{status,ok}` | pairings gone | re-pair | L1 | TODO-UP-5 (KeychainBackend against a foreign-partition item) | UNDEFINED (U5) |
| UP-6 | Helper | Identity switch (mote-local → developer-id) | TCC reset for all services; Keychain: one prompt or re-pair offer | install.sh resets AX + SR only (D-INS-1); Keychain as UP-4 | `diag.permissions.*.apiState`; `keychain/read` | stale Bluetooth/Automation rows; phone "key doesn't match" | Setup; phone Forget + connect | L3 | MANUAL-24 | fail (D-UP-2, U9) |
| UP-7 | App | Phone reconnects after a same-identity update | `session/authed` within 2 s of helper `lifecycle/ready` | n/a | phone `transport/connect.ready` | brief "Connecting…" | n/a | L2 | android:MacUpdateJourney#main | pass |
| UP-8 | App | Tailscale setting kept across update | Allow Tailscale unchanged; tailnet phone reconnects | setting unset → defaults on (code) vs off (protocol.md) | `lifecycle/setting`; `diag.transports.tailscale.allowed` | n/a | Advanced › Allow Tailscale connections | L2 | android:MacUpdateJourney#tailscaleOff | pass |
| UP-9 | App | Phone newer than helper | Diagnostics asks to update the helper; Labs blocker `helper-too-old` | n/a | `labs/blocked{code:helper-too-old}` | "update Mote Helper" | J1 | L2 | android:DiagnosticsTest#diagnosticsShowsPhoneStateAndAsksToUpdateNoHelper | pass |
| UP-10 | Helper | Helper newer than phone | old messages accepted (protocol is additive); unknown types ignored | n/a | `protocol/bad` count stays 0 | n/a | n/a | L2 | helper:EventCoverageTests#handledTypesTableMatchesTheCode; TODO-UP-10 (old-app fixture) | untested |
| UP-11 | User | Installs newer APK over the old (same keystore) | data kept; trust, selection, settings intact | n/a | `lifecycle/init`; Hosts list unchanged | remote, reconnects | n/a | L4 | MANUAL-25 | untested |
| UP-12 | User | Installs APK signed with another key over an existing install | Android refuses ("App not installed") | n/a | none in app | system error | uninstall (loses trust) then install | L4 | MANUAL-26 | untested |
| UP-13 | App | Prefs from an older version or corrupt | defaults, no crash | n/a | `lifecycle/init` | defaults | n/a | L0 | android:PrefsCorruptionTest | pass |
| UP-14 | Helper | Open at login after update in place | login item still points at the bundle | bundle moved or second copy (U15) | `lifecycle/setting`; SMAppService status | two helpers or none at login | Advanced › Open at login | L3 | MANUAL-27 | UNDEFINED (U15) |
| UP-15 | User | Learns a release exists | some notice | no update check anywhere (helper or app) | n/a | nothing | re-run install.sh | L4 | MANUAL-28 | UNDEFINED (U21) |
| UP-16 | User | Uninstall per install.sh: quit, `rm -rf` | helper gone, nothing starts at login | Open at login (auto-enabled) not mentioned (mac-update-D2) | Login Items list | stale login item | Login Items settings | L3 | MANUAL-29 | fail (D-UP-3) |

## Defects
- D-UP-1 `docs/signing.md` promises "one Keychain prompt per update" for Mote Helper Local; the helper reads and writes with `kSecUseAuthenticationUIFail`, so it never prompts and the item is unreadable after every update.
- D-UP-2 Identity switch has no recovery path on the Mac; on the phone only Forget + connect.
- D-UP-3 (mac-update-D2) uninstall line omits Open at login.
- D-UP-4 (mac-update-D3, D4, D5) artboards: ad-hoc "switch it off and on" copy, "Always Allow on the Keychain prompt", pre-0.6 Devices sheet, top-level Tailscale / Launch at login.
