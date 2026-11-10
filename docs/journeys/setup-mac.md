# J2 setup-mac: Mac first-run setup

Phase 2 spec (format: [README.md](README.md)). Derived from `helper/Sources/MoteHelper/{main,SetupWindow,Permissions,Diagnostics,Hub,MenuBar,Vault,InstantPairing,WebServer}.swift`. Permission statecharts: [permissions.md](permissions.md). Replaces the phase-1 draft of this file; its defects setup-mac-D2..D6 are carried below.

## UNDEFINED
- U5 Keychain unreadable after an identity change: what Setup shows and offers.
- U7 Local Network on macOS 15+: whether "Bonjour registered" may be shown as Allowed.
- U8 B1: what counts as "Screen Recording granted" after grant + relaunch.
- U16 Test seam to fake Accessibility denied under `--dry-run`.
- U17 Open at login when `SMAppService.status == .requiresApproval`.

## Goal
After install (J1) the user grants what Mote needs from one window and ends with a menu-bar helper that is "Ready", advertising on Wi-Fi (Bonjour) and Bluetooth, with honest permission rows.

## Preconditions
| Dimension | Values covered |
|---|---|
| Install | fresh (setupShown unset) / upgrade (setupShown >= 1) / `--setup` from install.sh / reopened via menu "Setup…" |
| Accessibility | not granted / granted / stale after identity change (switch on, `AXIsProcessTrusted` false) / revoked while running |
| Bluetooth (Mac) | notDetermined / allowed / denied / adapter off |
| Local Network | macOS < 15 (n/a) / 15+ unknown / inferred allowed / denied (-65570) / stale |
| Screen Recording | feature off / requested, pending relaunch / granted / stale |
| Automation | no scriptable browser running / per browser notDetermined, granted, denied |
| Keychain `secrets.v3` | absent / found / unreadable (partition mismatch) / corrupt |
| Network | Wi-Fi up / no network / multicast blocked (Bonjour still registers locally) |
| Signing | developer-id / mote-local / adhoc (dev) |

## Success criteria
- Menu status "Mote Helper: Ready"; `diag.features.input.state == ok`.
- `diag.transports.wifi.listening == true`, `diag.transports.bonjour.state == registered`, `diag.transports.ble.advertising == true` (if Bluetooth allowed and on).
- Every Setup row matches the real TCC state (no B1, no B2).
- `diag.keychain.readResult` in {found, notfound} and `partitionMatchesSigning != false`.
- Setup does not auto-open on the next launch.

## Main path
| ID | Actor | Step | Expected | Failure mode | Detected by | User sees | Recovery | Level | Test | Status |
|---|---|---|---|---|---|---|---|---|---|---|
| SM-1 | Helper | Launch (not `--dry-run`): `shouldAutoOpen` = setupShown < 1 or Accessibility missing | Setup window opens; `ui/setup.shown{rows}` | `--dry-run` never auto-opens | `ui/setup.shown` | Setup window: headline "Mote needs Accessibility…" | menu "Setup…" (⌘,) | L0 | helper:PermissionTests#freshInstall; helper:PermissionTests#accessibilityAloneClearsAutoOpen | pass |
| SM-2 | Helper | Builds rows from `Diag.states(fromSnapshot:)`; polls every 1 s | rows: Accessibility (required), Bluetooth, Local Network (recommended), Screen Recording, Automation (optional) | row state disagrees with TCC | `permission/check{perm,state,api,raw}` | wrong button | see permission rows | L0 | helper:PermissionTests#tiers; helper:PermissionTests#permsFilter | pass |
| SM-3 | User | Accessibility "Grant" | `AXIsProcessTrustedWithOptions(prompt)` + Privacy › Accessibility pane opens; `ui/setup.button`, `permission/request` | pane does not open | `ui/setup.button` without `permission/request` | nothing happens | menu "Setup…" again | L3 | MANUAL-3 | untested |
| SM-4 | macOS | User turns Mote Helper on in Accessibility | `AXIsProcessTrusted` true within 2 s (Hub poll), `state{ax:true}` broadcast, row "Allowed", menu "Ready" | stale entry from an older signature: switch on, API false | `permission/change` absent; `diag.permissions.accessibility.apiState=notDetermined` while switch on | row stays "Grant", menu "Needs Accessibility" / "Finish setup…" | remove the entry (-), re-add; install.sh resets on DR change | L3 | MANUAL-4 | untested |
| SM-5 | Helper | Accessibility never reports denied (false = notDetermined) | button stays "Grant" (never "Open Settings") | user expects a denied state | `permissions.accessibility.state` | "Grant" forever if user refuses | same as SM-3 | L0 | helper:PermissionTests#deniedRecommendedStillGrant; helper:DiagTests#accessibilityAndPause | pass |
| SM-6 | User | Bluetooth "Grant" (notDetermined) | helper starts BLE, macOS shows Bluetooth prompt; Allow: `discovery/ble.state` poweredOn, `discovery/ble.advertising`; row "Allowed" | Don't Allow | `permission/change{perm:bluetooth,state:denied}`; `diag.features.bluetooth.blockedBy=bluetooth-denied`, `fix=open-bluetooth-settings` | row "Open Settings" | opens Privacy › Bluetooth | L3 | MANUAL-5 | untested |
| SM-7 | Helper | Bluetooth allowed, adapter off | `diag.features.bluetooth{state:blocked,blockedBy:bluetooth-off,fix:turn-bluetooth-on}` | Setup has no adapter row | `discovery/ble.state` poweredOff | row "Allowed"; menu Advanced › Bluetooth status | turn Bluetooth on | L0 | TODO-SM-7 | untested |
| SM-8 | Helper | Bluetooth granted before this launch: BLE started at launch only if `PermProbe.check(.bluetooth) == granted` | advertising without a prompt; install.sh also resets BluetoothAlways and AppleEvents on a DR change, so a stale grant no longer survives (U9 b, decided 2026-11-10) | stale grant after identity change (not reset by install.sh) | `discovery/ble.failed`; `diag.transports.ble.status` | phone instant pairing "far" (no proximity) | Settings › Bluetooth remove/re-add | L3 | MANUAL-6 | untested |
| SM-9 | Helper | Local Network, macOS < 15 | row text "Can't check: macOS doesn't report this" | n/a | `permissions.localNetwork.applies=false` | text, no button | n/a | L0 | helper:DiagTests#localNetworkUnknownIsNeverBlocked | pass |
| SM-10 | Helper | Local Network, macOS 15+: inferred from Bonjour (`registered` = granted, -65570 = denied, else unknown) | Row shows "Probably allowed" (never "Allowed") after `discovery/bonjour.registered`, with "macOS doesn't let apps check this permission"; before it: "Unknown" with the same note (U7) | registration succeeds although Local Network is denied, or -65570 although allowed | `discovery/bonjour.failed{dnsCode,policyDenied}`; `permissions.localNetwork.inferred=true,inferredFrom` | wrong row | Privacy › Local Network | L1 | helper:DiagTests#localNetworkDeniedShowsPolicyDenied; helper:UxTests#localNetworkNeverSaysAllowed; TODO-SM-10 (fixture of real NetService results under allowed/denied) | untested |
| SM-11 | Helper | B2: Local Network allowed in System Settings, Setup shows "Open Settings" | row "Allowed" | reported by owner on 0.6.0; only path to "Open Settings" is `state=denied` from -65570 | `discovery/bonjour.failed{dnsCode:-65570}`; `diag.transports.bonjour.errorCode` | "Open Settings" although allowed | none known | L3 | MANUAL-7 | fail (B2) |
| SM-12 | User | Screen Recording "Enable" (feature off) | enables Space thumbnails; row becomes "Grant" | n/a | `lifecycle/setting{spaceThumbnails}` | "Grant" | n/a | L0 | helper:PermissionTests#optionalEnabledAsksForGrant; helper:DiagTests#screenRecordingOffWhenNothingUsesIt | pass |
| SM-13 | User | Screen Recording "Grant" | `CGRequestScreenCaptureAccess` (first time) else pane opens; after the user allows, "Relaunch helper" shown (`open -n --args --setup`) | prompt never shown again after first request | `permission/request{perm:screenRecording}` | "Relaunch helper" | Relaunch | L0 | helper:PermissionTests#relaunchOfferedOnlyAfterAskingScreenRecording | pass |
| SM-14 | Helper | B1: after grant + relaunch, row "Allowed" | `CGPreflightScreenCaptureAccess` true; `permissions.screenRecording.detail.otherAppWindowTitlesVisible=true` | reported: row still "Grant" after relaunch | `permissions.screenRecording{apiState,raw,detail}` | "Grant" | none known | L3 | MANUAL-8 | fail (B1, U8) |
| SM-15 | Helper | Automation row (per running scriptable browser, `AEDeterminePermissionToAutomateTarget(ask:false)`) | optional; "Enable" when Smart remote off | denied | `diag.features.labs.context.blockedBy=automation-not-granted`, `fix=open-automation-settings` | "Open Settings" | Privacy › Automation | L1 | TODO-SM-15 | untested |
| SM-16 | Helper | Keychain state is not a Setup row | readable item | `readResult=error` (partition mismatch) or `unreadable` (corrupt) | `keychain/read{result}`; `diag.features.keychain.blockedBy` keychain-unreadable / keychain-corrupt, `fix=repair-keychain` | nothing (no row, no menu line) | none in UI | L0 | helper:DiagTests#keychainStatusCodes | fail (D-SM-1, U5) |
| SM-17 | Helper | Wi-Fi listener on `--port` (default) with Bonjour `_mote._tcp` TXT id/v | `transport/listen.state` ready; `discovery/bonjour.attempt` then `registered` | port in use / listener waiting | `diag.features.wifi.blockedBy` listener-failed / listener-waiting, `fix=free-port` / `restart-helper` | menu Advanced › Wi-Fi status | Quit other app, restart | L2 | node:helper/tests/smoke.mjs; node:helper/tests/diag.mjs | pass |
| SM-18 | User | Done (required OK) or Later | `ui/setup.closed`; setupShown stored | n/a | `ui/setup.closed` | headline "You're set…" / button "Done" | n/a | L3 | MANUAL-3 | untested |
| SM-19 | User | Toggle "Open Mote Helper at login" | SMAppService register/unregister; Advanced › "Open at login" checkmark follows; with status `.requiresApproval` the menu shows "Needs approval in Login Items…" and Setup shows the same text with an "Open Login Items" button (U17) | n/a | `lifecycle/login.item` | toggle off | Login Items settings | L3 | helper:UxTests#loginItemCaption; MANUAL-9 (real approval state) | untested |
| SM-20 | Helper | First real launch registers Open at login once (`loginApplied`) | login item on without asking | user did not choose it (setup-mac-D3) | `lifecycle/setting` | Open at login already on | toggle off | L0 | TODO-SM-20 | untested |
| SM-21 | Helper | Menu bar after setup | "Mote Helper: Ready"; "Devices (0)"; no "Finish setup…" | AX missing: "Needs Accessibility" + "Finish setup…"; paused: "Paused: input is ignored" | `ui/menu.opened`; Hub `state{paused,ax}` | status line | "Finish setup…" opens Setup | L3 | MANUAL-10 | untested |
| SM-22 | Helper | Pairing window | not opened by default (instant pairing on); opened with `--open-pairing` or (no devices and instant pairing off) | n/a | `ui/pairing.shown` | no pairing code window | Advanced › "Pair with code (web remote)…" | L2 | node:helper/tests/pairing.mjs | pass |
| SM-23 | Helper | Relaunch after setup | Setup does not auto-open | AX became stale: Setup reopens | `ui/setup.shown` | Setup again | SM-4 | L0 | helper:PermissionTests#accessibilityAloneClearsAutoOpen | pass |
| SM-24 | Helper | Accessibility revoked while running | within 2 s: `state{ax:false}`, input refused with err `ax` | n/a | `input/blocked{reason:ax}`; `diag.features.input.blockedBy=accessibility-not-granted` | menu "Needs Accessibility"; phone notice (J6 DU-6) | re-grant | L2 | node:helper/tests/ux.mjs (--fake-ax-denied: authed.ax false, key -> err ax, diag blocked); the revoke-while-running transition stays L3 (partial: denied state only) | untested |
| SM-25 | Helper | `MoteHelper --diag` while running | relays the running helper's `diag.json` (pid alive, < 60 s old), else own snapshot `source=cli` | stale file | `lifecycle/diag.cli` | JSON | n/a | L0 | helper:DiagFileTests#freshAndAliveIsUsed; helper:DiagFileTests#staleDeadMissingBrokenFallBack | pass |
| SM-26 | User | Full setup on a clean `motetest` user, then on the owner's Mac | SM-1..21 with real TCC | any | `MoteHelper --diag` before and after each grant | per row | per row | L3 | MANUAL-11 | untested |

## Defects
- D-SM-1 Keychain unreadable/corrupt is computed (`features.keychain`, `fix: repair-keychain`) but no menu item, Setup row or action exists.
- D-SM-2 `dry-run` makes `inputReady = axTrusted || dryRun`, so SM-24 and every "Needs Accessibility" path is L3-only; phase-1 drafts cite `--fake-ax-denied` and `--store-file`, which did not exist. FIXED (U16): `--fake-ax-denied` (dry-run only) exists; `--store-file` still does not.
- D-SM-3 (setup-mac-D2) design menu tour differs from code (Devices ▸, Setup…, Advanced ▸ "Open at login").
- D-SM-4 (setup-mac-D3) Open at login is enabled automatically at first real launch (SM-20).
- D-SM-5 (setup-mac-D4) artboard shows a macOS Accessibility prompt at launch and "Grant Accessibility…"; code opens Setup and shows "Finish setup…".
- D-SM-6 (setup-mac-D5, D6) Live screen copy and pre-0.6 installer output on artboards.
- D-SM-7 Setup has no Bluetooth-adapter-off line (SM-7); only Advanced › Bluetooth status.
