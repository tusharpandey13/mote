# Cross-device E2E testing and journey docs for Mote

Research date 2026-11-10. Web search only; I did not fetch full pages, and search snippets were the evidence. "(unverified)" marks claims I could not confirm from a source. Items from general knowledge are labelled as such.

## TL;DR recommendation

Mote's bugs sit in the OS layer your fakes replace. No tool tests that layer for free. Spend effort on three things, cheapest first.

1. **Make the helper report its own state** (a `/diag` or WebSocket `diag` message, plus a "Why isn't this working" screen). It costs 0 GB of disk, about 1-2 days, and fixes the "Labs show nothing, no reason" class directly. It is also what lets every later test assert state instead of guessing. Detail in section 6.
2. **Write a journey FMEA table** (about 15-25 rows, one markdown file in `docs/`). Each row links to a test or marks it `MANUAL`. About half a day. Format in section 1.
3. **Replace the OS-facing code with thin adapters.** Define `PermissionProbe`, `KeychainStore` and `Discovery` protocols. Production uses the real OS. Tests use fixtures recorded from the real Mac. This is about 1-2 days and 0 GB. It catches logic bugs such as "granted but UI says Grant". It cannot catch the OS itself misbehaving.
4. **Run a real-OS smoke test by hand or on a schedule, in a separate clean macOS user account on the owner's Mac.** Cost is about 3-8 GB (a user home plus the app). It covers first install via `curl|bash`, TCC prompts and Keychain prompts, each with real UI. A clean user also has no leftover TCC grants for your bundle ID, because TCC is per-user for most services (general knowledge). Screen Recording and Accessibility are stored in the per-user DB in some macOS versions and system-wide in others (unverified for 26/27).
5. **Optional later: one Tart golden VM** (about 30-40 GB; the macOS 27 base image download was 29.9 GB, per [Apple forums digest](https://developer.apple.com/forums/activities/1586080)). Do not do this now because disk is short. Prefer an external SSD if you ever do.

Skip device farms and HIL rigs for BT HID. BT HID pairing stays a manual checklist, driven by a script that prints the steps (section 5).

Estimated total: about 4-5 engineer days, under 10 GB disk, 0 dollars. A VM or cloud Mac adds roughly 35 GB or roughly 0.08 USD/min (GitHub macOS, unverified for 2026).

## 1. Journey and spec documentation

| Method | Fits multi-actor with failures? | Verdict |
|---|---|---|
| Gherkin (Given-When-Then) | Good for readability. It poorly expresses "which actor, which layer". | Use for the happy path only, if at all. |
| Use case + system sequence diagram | Good for actors and messages. Failure handling lives in prose. | Use for protocol docs (you already have `docs/protocol.md`). |
| Service blueprint | Designed for user + frontstage + backstage + support layers. Heavy for engineering. | Overkill. |
| Statecharts | Best for permission states (unknown / granted / denied / stale). | Use per subsystem, not per journey. |
| FMEA table | Lists step, failure, detection, user-visible effect, mitigation, test. Built for "what can go wrong". | **Recommended.** |
| Contract tests (Pact) | Good for API boundaries. Not for OS state. | You already have `protocol.md` as the contract. |

I found no 2024-2026 source that compares these for companion apps (unverified as a gap). Blueprint and FMEA descriptions are from general knowledge. Statecharts follow Harel and are in the same family as XState (general knowledge).

**Recommended format: a journey table where every row is one step with a failure mode.** Columns: `ID | Actor | Step | Failure mode | How we detect it | What the user sees | Test level | Status`.

Example row:

| ID | Actor | Step | Failure mode | Detect | User sees | Test level | Status |
|---|---|---|---|---|---|---|---|
| J3.2 | macOS | Helper requests Screen Recording after install | Grant given, but `CGPreflightScreenCaptureAccess()` stays false after relaunch because the binary is ad-hoc signed and the signing identity changed | `diag.permissions.screenRecording = {granted:false, signing:"adhoc", cdhash:...}` | Setup shows "Grant" plus the reason "signed with a different identity; re-grant after update" | L3 clean-user manual, L1 unit on probe adapter with recorded fixture | MANUAL |

The case in J3.2 is supported by evidence. [Apple forums 819406](https://developer.apple.com/forums/thread/819406) reports preflight returning false minutes after the grant, and links it to ad-hoc signing and an unstable identity. Switching to an Apple Development cert fixed it. A conflicting report ([Cap #1722](https://github.com/CapSoftware/Cap/issues/1722)) says Sequoia may need a Developer ID cert; I could not confirm that. Mote's release is self-signed (see `docs/signing.md`), so this matters for every release user, not just you.

Test levels to use in the table:
- L0 = unit.
- L1 = adapter with recorded fixture.
- L2 = dry-run helper + emulator (what you have).
- L3 = real OS in a clean user or VM.
- L4 = real phone, manual.

## 2. How real products test cross-device flows

Honest finding: I found almost no public write-ups of how KDE Connect, Deskflow/Barrier, Unified Remote or Tailscale test their end-to-end flows. Searches for KDE Connect and Deskflow test strategy returned nothing specific; see their repos (`invent.kde.org` `autotests`, `deskflow/.github/workflows`) (unverified; not read). What I did find:

- **KDE** documents [Appium-based GUI testing](https://develop.kde.org/docs/apps/tests/appium), running the app under a WebDriver that works in CI without a display. KDE also uses [openQA](https://linux.kde.org/docs/openqa/) for whole-OS tests. That is the "real OS in a VM, driven by screenshots" approach.
- **Appium multi-session**: one test holds two driver objects (phone, second device), each with distinct `systemPort`/`wdaLocalPort`, and sequences the steps ([HeadSpin](https://headspin.io/blog/testing-real-time-user-interaction-using-multiple-simultaneous-appium-sessions), [Drizz](https://www.drizz.dev/post/ios-android-parallel-testing)). The Mac side would be Appium's Mac2 driver (general knowledge) or plain XCUITest.
- **Everyone else** (general knowledge, not sourced): protocol-level tests with fakes, plus a manual release checklist. Mote's current split is the norm. The gap is a written checklist for the L3/L4 parts and diagnostics.
- **Tools I did not research**: Maestro, Detox, Firebase Test Lab, BrowserStack, AWS Device Farm. All are single-device mobile UI tools (general knowledge). None can drive a Mac or a pairing with real Bluetooth, so they add nothing for Mote's failing layer.
- **AI-agent-in-VM** is a new pattern for Mac UI checks: [Flavio Copes on testing Mac apps in a Tart VM](https://flaviocopes.com/mac-test-vm/), and [cua / Lume](https://cua.ai/docs/how-to-guides/driver/run-tests-in-macos-lume-vm). Note the same caveat in both: the agent's session needs Accessibility, Screen Recording and System Events granted first.

## 3. macOS system layer in automated tests

**VMs.**
- Tart (Cirrus Labs) is the common choice. It is now maintained by OpenAI after Cirrus Labs joined them in 2026, licence FSL-1.1-ALv2, converting to Apache 2.0 after two years ([libhunt / forum digest](https://www.libhunt.com/r/tart); the ownership claim came from one aggregator, unverified).
- Apple's licence allows 2 macOS guests per physical Mac, enforced by macOS ([Eclectic Light](https://eclecticlight.co/2022/08/04/virtualisation-on-apple-silicon-macs-8-how-apple-limits-vms/), [Apple forum 826102](https://developer.apple.com/forums/thread/826102)). Linux guests are exempt. Irrelevant for one VM.
- A VM cannot do Bluetooth or real Local Network behaviour of your home Wi-Fi (bridged networking is possible; general knowledge). It cannot sign into iCloud reliably (general knowledge).
- I found no 2026 GitHub Actions macOS runner pricing (not verified). GitHub-hosted runners are ephemeral and cannot do TCC without SIP changes (below).

**TCC in tests.**
- *MDM PPPC profile*: works for Accessibility and a few services, but Screen Recording cannot be silently granted by MDM; it can only be set to "allow standard users to approve" (general knowledge, not found in this search). Needs an MDM enrolment, so it is overkill for one dev.
- *Editing TCC.db*: the system DB needs SIP off ([Apple forum 730043](https://developer.apple.com/forums/thread/730043), [Bitrise](https://support.bitrise.io/en/articles/9676651-modifying-tcc-framework-from-the-command-line)). A report says the user TCC DB moved into a protected container on the macOS 27 beta, so CI pre-grant "appears completely broken" ([Apple forum 834560](https://developer.apple.com/forums/thread/834560)). The `desktest` project inserts TCC rows with code-signing requirement blobs in a Tart golden image ([desktest macos-support](https://tgfed.nhyvvfeijnh.workers.dev/Edison-Watch/desktest/blob/master/docs/macos-support.md), unverified mirror).
- *`tccutil reset`*: only revokes. Useful to reset your own bundle between runs. It cannot grant.
- *`tccplus`*: third-party grant tool. Not in my results (unverified).
- *UI-scripting the prompt* with System Events: works only if the driving process itself has Accessibility, and the TCC prompt UI is a protected window on recent macOS (general knowledge, unverified).
- *Stable signing identity matters more than tooling.* [cua docs](https://cua.ai/docs/reference/cua-driver/macos-permissions) say not to grant TCC to ad-hoc builds. Use a stable local identity. Mote already signs locally with an Apple Development cert, which is a good test identity. Keep that identity fixed across test builds.

**Local Network.** There is no API to query it. Apple's DTS guidance is to infer: Bonjour fails with `kDNSServiceErr_PolicyDenied (-65570)`, and `NWBrowser` goes to `.waiting(PolicyDenied)` ([Apple forum 780655](https://developer.apple.com/forums/thread/780655), [651107](https://developer.apple.com/forums/thread/651107), [658151](https://developer.apple.com/forums/thread/658151)). Caveats: the first NWBrowser update can say "ready" regardless ([770563](https://developer.apple.com/forums/thread/770563)); on Tahoe, a meta-query browse returned `NoAuth (-65555)` even with the toggle on ([824856](https://developer.apple.com/forums/thread/824856)). So run a real probe browse at startup and report the result in `diag`.

**Keychain in CI.** I found nothing in this search. General knowledge: create a temporary keychain with `security create-keychain` + `unlock-keychain` + `set-key-partition-list`. The partition-list step is what removes the prompts you hit. Your `--memory-store` and `--keychain-service NAME` flags are already the right isolation. Add one L3 test that uses a real service name in the clean user.

**Not automatable (as far as I could find):** granting Screen Recording or Local Network by script on a stock Mac; the first-run Gatekeeper dialog for a downloaded self-signed app (general knowledge); real BT pairing consent on the phone and the Mac; multicast-blocked Wi-Fi from a real router (can only be simulated on a lab AP or with `pfctl`, unverified).

## 4. Low-disk options, ranked

| Rank | Approach | Disk | Cost | Fidelity | Catches |
|---|---|---|---|---|---|
| 1 | Diagnostics + "why not working" surface | 0 | 1-2 d | Medium; shows real state in the field | Silent failures |
| 2 | Adapters with recorded real-OS fixtures | ~0 | 1-2 d | Low-medium (logic only) | "Granted but UI says Grant" logic bugs, error mapping |
| 3 | Clean macOS user on the same Mac | 3-8 GB | 0.5 d + manual runs | High (real TCC, Keychain, Gatekeeper, curl\|bash) | First-install, prompts, stale-grant bugs |
| 4 | Test-only stable signing identity | ~0 | 15 min | Needed for any TCC test to mean anything | Signing-identity-related TCC resets |
| 5 | Ephemeral cloud macOS runner | 0 local | per-minute (unverified) | Medium; cannot grant TCC, no BT, no home Wi-Fi | Build/install smoke, `install.sh` only |
| 6 | Tart slim VM (golden image + clone) | 30-40 GB (one base) | 1-2 d | High for TCC after one manual grant; no BT | Full first-run journeys repeatable |

Note a clean user is not a full reset: Keychain items, LaunchAgents and `/Applications` are shared with your main user. Install into `~/Applications` and use a distinct bundle ID or keychain service for the test build (general knowledge).

## 5. Bluetooth HID and BLE

- **Android emulator**: the virtual controller is netsim/RootCanal, built in; enable it with `-packet-streamer-endpoint default` on an API 34+ AVD ([Zephyr docs](https://docs.zephyrproject.org/latest/connectivity/bluetooth/bluetooth-tools.html), [AOSP RootCanal](https://android.googlesource.com/platform/packages/modules/Bluetooth/+/7e2a892c56fdcfd84bb87c5695de6d3ba0d7fe59/tools/rootcanal/)).
- **Bumble** can attach a host stack to netsim in either role ([Bumble Android docs](https://google.github.io/bumble/platforms/android.html), [dev.to walk-through](https://dev.to/engelon/how-i-gave-the-android-emulator-real-bluetooth-1jbc)). This lets you test the phone's BLE GATT client against a scripted fake peripheral that speaks the helper's GATT protocol. It does not test macOS Bluetooth (CoreBluetooth) or the real HID host.
- **Verdict**: use Bumble + netsim for the Android BLE/GATT client logic and error paths (disconnects, timeouts, bad MTU). It would not have found any bug you listed. BT HID pairing with a real Mac stays manual: a checklist, run before each release. Passing a USB BT dongle into a macOS VM is not supported by Apple's Virtualization framework as far as I know (unverified). Sniffers (nRF) are for debugging, not CI.
- Do not run the emulator work without pinning `ANDROID_SERIAL` (your AGENTS.md rule).

## 6. Observability that makes journeys testable

These patterns come from general practice. I found no source that describes them for companion apps (unverified as a gap).

1. **State snapshot**: helper exposes `diag` (WebSocket message and `--diag` CLI) returning JSON: version, build signing identity (adhoc/dev/none) and cdhash, each permission as `{state, how determined, last checked, reason}`, Keychain status, listener ports, Bonjour advertise result, Local Network probe result (from `PolicyDenied`), connected clients, and each Lab feature as `{enabled, blocked_by: [...]}`.
2. **Reasons, not booleans**: every feature that can silently fail exposes a `blocked_by` code and a human string. The Android UI shows a "Why isn't this working?" sheet populated from it. Tests assert on codes.
3. **Ring-buffer log** of the last N events, retrievable over `diag` and copyable from the Setup window.
4. **Permission state machine**: `unknown -> prompted -> granted | denied | stale(signing changed)`. Re-probe on app foreground and after relaunch. Log the transition.
5. **Test hooks**: a `--diag-json` flag that prints and exits, so an L3 script in the clean user can assert "after clicking Grant in the real prompt, `screenRecording.state == granted`".
6. **Journey ID in logs** on phone and helper, so one flow can be correlated across devices.

## Comparison table

| Layer | Today | Add | Catches the owner's bugs? |
|---|---|---|---|
| Unit / dry-run / Playwright / chaos | Yes | none | No (they fake the OS) |
| Adapters + recorded fixtures | No | Yes | Partly (logic) |
| Diagnostics + why-screen | No | Yes | Detects, does not prevent |
| Clean user L3 script + FMEA checklist | No | Yes | Yes (TCC, Keychain, install) |
| Tart VM | No | Later | Yes, repeatably |
| Cloud macOS runner | No | Optional | Install only |
| netsim/Bumble | No | Optional | Android BLE logic only |
| Device farm / Maestro / Detox | No | No | No |
| Real phone + Mac checklist | Ad hoc | Written | Yes (BT, Wi-Fi) |

## Risks and unknowns

- macOS 26/27 TCC behaviour is moving. The user DB protection report is a beta-era forum post ([834560](https://developer.apple.com/forums/thread/834560)). Do not build automation that depends on editing TCC.db.
- Whether a self-signed (not Apple-issued) release build can ever hold a Screen Recording grant across relaunch is unresolved. Sources conflict ([819406](https://developer.apple.com/forums/thread/819406) vs [Cap #1722](https://github.com/CapSoftware/Cap/issues/1722)). Test this directly in the clean user with the release artifact; it may be the cause of the owner's "Grant after relaunch" bug.
- A clean user shares Keychain machine state and `/Applications`; it can mask bugs that depend on a truly fresh Mac.
- Tart ownership/licence and image sizes come from aggregator pages. Verify before relying on them.
- I did not verify GitHub macOS runner pricing, MDM Screen Recording limits, `tccplus`, or dongle passthrough.
- No public source found on how KDE Connect, Deskflow, Unified Remote or Tailscale test E2E.
