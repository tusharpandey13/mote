# J1 install-helper: install Mote Helper on the Mac

Phase 2 spec (format: [README.md](README.md)). Derived from `site/install.sh`, `helper/Sources/MoteHelper/{main,Vault,InstantPairing,Diagnostics}.swift`, `docs/signing.md`.

## UNDEFINED
- U9 Which TCC services install.sh resets on a signature change (now Accessibility + ScreenCapture only).
- U10 What install.sh does when `/Applications/MoteHelper.app` also exists.
- U14 How long install.sh waits for the old helper to exit before `rm -rf`.

## Goal
A user pastes one command and ends with the current signed helper in `~/Applications`, running, with the Setup window open, and no stale permission or Keychain state from an older build.

## Preconditions
| Dimension | Values covered |
|---|---|
| Install | fresh (no app, no Keychain item) / upgrade same identity / upgrade different identity (ad-hoc, Apple Development, Mote Helper Local, Developer ID) / second copy in `/Applications` |
| Platform | macOS arm64 / x86_64 / other CPU / not macOS |
| Network | online / offline / GitHub reachable but asset missing / tampered asset |
| Helper running | not running / running and quits / running and does not quit within 1 s |
| Permissions before | none / granted for old signature (stale after identity change) |
| Signing identity of new build | developer-id (target) / mote-local (interim) / apple-development, adhoc (must never ship) |

## Success criteria
- `~/Applications/MoteHelper.app` matches the release zip SHA-256; `diag.signing.quarantined == false`.
- Helper running; `lifecycle/start` and `signing/identity` logged with `identity` in {developer-id, mote-local}.
- Setup window shown (`ui/setup.shown`).
- No permission row looks granted while not working (no stale TCC entry).
- `diag.keychain.readResult` in {found, notfound}; never `error` after install.

## Main path
| ID | Actor | Step | Expected | Failure mode | Detected by | User sees | Recovery | Level | Test | Status |
|---|---|---|---|---|---|---|---|---|---|---|
| INS-1 | User | Runs `curl -fsSL https://tusharpandey13.github.io/mote/install.sh \| bash` | Script prints the plan (download, verify, install, clear quarantine) | Not macOS | exit 1 | "Mote Helper is a macOS app. On other systems use the Android app or the web remote." | Use web remote / Android BT | L2 | e2e:install.spec.ts "not macOS -> refuses" | pass |
| INS-2 | Helper (script) | Picks `MoteHelper-macos-$ARCH.zip` from `uname -m` | arm64 or x86_64 asset | Unsupported CPU | exit 1 | "unsupported CPU: <arch>" | none | L2 | e2e:install.spec.ts "unsupported CPU -> refuses" | pass |
| INS-3 | Helper (script) | Checks curl, shasum, ditto | all present | tool missing | exit 1 | "curl is required" (etc.) | install tool | L2 | TODO-INS-3 | untested |
| INS-4 | Helper (script) | Downloads zip + `.sha256` from `releases/latest/download` (https, TLS 1.2+; http only for loopback override) | both files in temp dir | offline / 404 | curl -f non-zero, `set -e` exit | curl error text, no "Done" | retry later | L2 | e2e:install.spec.ts "the override only allows plain http for loopback"; e2e:install.spec.ts "default download URL is unchanged" | pass |
| INS-5 | Helper (script) | Verifies SHA-256 | match, prints "ok <hash>" | mismatch / empty checksum | exit 1 | "checksum mismatch (expected …, got …). Not installing." | retry; report | L2 | e2e:install.spec.ts "checksum mismatch -> aborts"; e2e:install.spec.ts "missing checksum file -> aborts" | pass |
| INS-6 | Helper (script) | Unpacks with ditto | `MoteHelper.app` present | bad archive | exit 1 | "archive doesn't contain MoteHelper.app" | report | L2 | e2e:install.spec.ts "archive without MoteHelper.app -> aborts" | pass |
| INS-7 | Helper (script) | If `pgrep -x MoteHelper`: "Quitting the running Mote Helper", `pkill` (TERM), then polls up to 5 s | old helper exits within 5 s; its sessions close; if it is still alive the script aborts with "Quit Mote Helper first" and changes nothing; `--force` (documented in the script header) then `pkill -9`s it and continues | helper still alive after 5 s (busy main thread, Setup modal) | script output | nothing; old process keeps running from a deleted bundle | quit from menu, re-run | L3 | e2e:install.spec.ts "INS-7 (U14): a helper that quits within the wait", "...will not quit -> after 5 s aborts", "...--force kills a stubborn helper" | pass |
| INS-8 | Helper (script) | Reads old and new designated requirement (`codesign -d -r-`) | strings captured | old app absent (fresh) | `OLD_REQ` empty: no reset | nothing | n/a | L2 | e2e:install.spec.ts "downloads, verifies, installs into ~/Applications and opens it" | pass |
| INS-9 | Helper (script) | `rm -rf` then `ditto` into `${MOTE_INSTALL_DIR:-~/Applications}`, `xattr -dr com.apple.quarantine` | new bundle in place, not quarantined | disk full / permission | `set -e` exit after `rm -rf`: old app gone, new missing | ditto error | re-run | L2 | e2e:install.spec.ts "replaces an older install; x86_64 gets the x86_64 asset" | pass |
| INS-10 | Helper (script) | DR differs: "The new version is signed differently…", `tccutil reset` Accessibility, ScreenCapture, BluetoothAlways and AppleEvents for `dev.mote.helper` | stale AX/SR/Bluetooth/Automation switches removed once (U9) | n/a (a service macOS does not know is a no-op) | `diag.permissions.bluetooth.apiState` vs Setup row; `permission/check` | possible "Allowed" Bluetooth row for a grant macOS ignores, or a prompt again | System Settings, remove and re-add | L3 | e2e:install.spec.ts "upgrade from a differently signed (ad-hoc) build resets its stale grants once" (all four services) | pass |
| INS-11 | Helper (script) | DR differs: Keychain item partition no longer matches | (no step exists) | `secrets.v3` unreadable silently (`kSecUseAuthenticationUIFail`) | helper `keychain/read` result=error; `diag.keychain.partitionMatchesSigning=false`; `diag.features.keychain.blockedBy=keychain-unreadable` | nothing on the Mac; phone "key doesn't match" (J5 RC-9) | none in UI (`fix: repair-keychain` has no action) | L3 | TODO-INS-11 | fail (D-INS-2, U5) |
| INS-12 | Helper (script) | `open "$APP" --args --setup` | new helper launches with `--setup` | old instance still running (INS-7): `open` activates it, `--setup` dropped | `lifecycle/start` missing for new pid | no Setup window, or Setup of the old build | quit, open from `~/Applications` | L3 | TODO-INS-12 | untested |
| INS-13 | Helper | Launch: `lifecycle/start`, `signing/identity`, Keychain read once | fresh: `keychain/read` notfound then `keychain/write` + `keychain/partition`; upgrade same partition: found | see INS-11 | `diag.keychain.readResult` | n/a | n/a | L0 | helper:VaultTests#freshInstallWritesBlobOnceSoLaterLaunchesNeverRetryMigration; helper:VaultTests#authErrorIsNotPresentAndNeverRetriedOrOverwritten | pass |
| INS-14 | Helper | Signing identity is a release identity | `diag.signing.identity` in {developer-id, mote-local}, `teamId` set for developer-id | apple-development / adhoc shipped | `signing/identity` event; `helper/tests/signing.sh` at release | stale permissions every update | rebuild with right identity | L1 | node:helper/tests/signing.sh | pass |
| INS-15 | Helper | Registers Open at login once (`loginApplied`); records `loginWanted` + `loginPath`; install.sh updates a lone writable `/Applications/MoteHelper.app` in place and warns when a second copy exists (U10) | SMAppService registered for this bundle | second copy in `/Applications` is the registered one (the warning tells the user to delete one) | `lifecycle/login.item`; install.sh output | old build starts at login, new one on demand: two menu icons or port clash (`transport/listen.state` failed) | remove one copy | L3 | e2e:install.spec.ts "INS-10 (U10): ..." x3; helper:UxTests#loginItemDecision; MANUAL for the SMAppService part | untested |
| INS-16 | Helper (script) | Prints "Done. Follow the Setup window (allow Accessibility; Bluetooth is recommended), then open Mote on your phone…" | Setup visible (J2) | n/a | `ui/setup.shown` | Setup window | J2 | L2 | e2e:install.spec.ts "downloads, verifies, installs into ~/Applications and opens it" | pass |
| INS-17 | User | Real install on a clean macOS user (`motetest`) from the published release | INS-1..16 hold with real Gatekeeper and TCC | any | `MoteHelper --diag` | per row | per row | L3 | MANUAL-1 | untested |
| INS-18 | User | Real upgrade over a previous release on the owner's Mac (paired phone) | phone reconnects silently after install (J5) | INS-11 | phone `transport/connect.auth`; helper `session/auth.failed` | "key doesn't match" | re-pair | L4 | MANUAL-2 | untested |

## Defects
- D-INS-1 install.sh resets only Accessibility and ScreenCapture on a DR change; Bluetooth and AppleEvents grants are keyed the same way and go stale. FIXED (U9): install.sh now resets all four.
- D-INS-2 install.sh comment and `docs/signing.md` treat a signature change as a TCC problem only; the Keychain partition also changes (cdhash partition for Mote Helper Local on every build), and the helper never prompts, so the item is unreadable with no UI.
- D-INS-3 `sleep 1` after `pkill` is not a wait; `rm -rf` can run under a live process (INS-7).
- D-INS-4 A copy in `/Applications` is never detected (INS-15).
