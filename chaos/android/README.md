# Android chaos suite

Seeded, adb-driven robustness tests for the Mote Android app. It looks for crashes, ANRs, leaks and
broken state under hostile device conditions. Bluetooth HID itself can't be exercised on an emulator;
`physical.sh` covers the real link. Results are scored with the shared rubric in
[`../RUBRIC.md`](../RUBRIC.md): severities S1-S4, invariants I1-I10, budgets.

```sh
chaos/android/run.sh --serial emulator-5556 --seed 42 --budget 20m
```

| flag | meaning |
|---|---|
| `--serial` | adb serial (required). Emulator guard: the run stops before changing anything unless the serial is `emulator-*` AND the guest reports `ro.kernel.qemu` or `ro.boot.qemu` = 1, or `ro.hardware` ranchu/goldfish. A serial that isn't `emulator-*` is refused without any adb call. |
| `--allow-physical` | Lifts the guard (`physical.sh` passes it) and prints a warning banner: HID input reaches the paired computer, and device settings get toggled. |
| `--seed N` | Seeds monkey (`N`..`N+3`) and every random sleep/size in the scripts (file-backed LCG, so draws inside subshells stay reproducible). |
| `--budget` | `20m`, `1h`, `900s`. Monkey's share is reserved up front (`MONKEY_SHARE`, default 40%). Each other group gets a weighted slice of the rest (lifecycle 4, resource and network 3, storage 2, others 1); time a group doesn't use carries over to the next. A group always runs its first sub-scenario, and any sub-scenario that doesn't fit its slice is recorded as SKIP. Monkey runs last and takes ~85% of the time left, with a floor of `MONKEY_MIN` (default 50000) total events that may overrun the budget by a minute or two (`MONKEY_MIN=0` for a strict budget). |
| `--apk PATH` / `--no-build` | Install a given APK / skip build+install. Default: build and `install -r -g` the debug APK. |
| `--only a,b` | Subset of `storage,lifecycle,resource,bluetooth,network,connect,monkey` (always run in that order). |
| env | `MONKEY_MIN` (see `--budget`); `MOTE_CHAOS_HELPER=0` never starts a helper in `network`; `MOTE_CHAOS_HELPER_PORT` (default 8797). |

Gradle runs with `--max-workers=2` and JDK 17 (`/usr/libexec/java_home -v 17`). Without
`android/local.properties` or `ANDROID_HOME`, the SDK is taken from `~/Library/Android/sdk`.
The debug build is needed for storage, network and connect (they use `run-as`); the rest works with release.

**adb server.** The harness only uses the default adb server (port 5037) and always passes
`-s <serial>`. It refuses to run when `ANDROID_ADB_SERVER_PORT` points anywhere else: a second adb
server also enumerates USB, so it takes the user's phone away from their own adb. (This happened
once while the suite was being written.) It also refuses when the run itself had to start the adb
server and that server then attached a non-emulator device. Never start a private adb server for
chaos runs.

**Other adb clients.** Gradle's `connectedAndroidTest` installs the app and then uninstalls it on
*every* device the adb server sees (including a plugged-in phone), so running instrumented tests in
another checkout wipes the app mid-run. When the package disappears, `finish` reinstalls it and
records the scenario as SKIP (result void). Instrumented tests should pin a device with
`ANDROID_SERIAL=emulator-NNNN`.

### Device state (I9)

Before it changes anything, the run snapshots (`devstate` in `lib.sh`): global `airplane_mode_on
wifi_on bluetooth_on mobile_data low_power window/transition/animator scales stay_on_while_plugged_in
enable_freeform_support`; system `font_scale user_rotation accelerometer_rotation system_locales`;
secure `always_on_vpn_app/lockdown`; `cmd wifi status`; Bluetooth `enabled:`; `wm density` and `wm size`
overrides; `cmd uimode night`; `persist.sys.locale`; the app's locales; deviceidle deep/light state;
the battery AC override; the standby bucket; and VPN networks (`dumpsys connectivity`). That snapshot is
`devstate.base`. The harness then sets `window_animation_scale 0.5` and `svc power stayon true` and
saves `devstate.harness`. After every scenario and sub-scenario the device goes back to the harness
snapshot; at the end, and on EXIT/INT/TERM, it goes back to the base snapshot. Only keys that differ
are written (no hard-coded defaults), and the run waits for Wi-Fi, Bluetooth and airplane mode to
settle before taking a new snapshot. Any key that still differs is a `devstate-<name>` FAIL (S1, I9):
the diff is printed and saved to `logs/<name>.devstate.diff`. Runtime permissions are re-granted after
every restore.

The app's data is cleared (`pm clear`) once at the start, so a Wi-Fi choice left by an earlier run
(or by monkey) can't hide the first-run gate. On emulators adbd is rooted so ANR traces can be pulled.

## Output

`chaos/android/out/<time>-s<seed>/` (gitignored):
- `summary.md`, also printed: the scenario table, findings by severity S1-S4, invariants I1-I10
  (PASS / FAIL / n/a, with the scenarios that exercised them) and budgets vs actuals. Written by
  `summary.py`.
- `results.tsv`, tab-separated: `scenario status crashes anrs notes severity invariants violated secs`.
  Status is PASS, FAIL, PENDING or SKIP.
- `devstate.tsv` (`name diff_count diff`), `devstate.base` and `devstate.harness`.
- `run.log`, `logs/<scenario>.log` (logcat main/system/crash), `logs/*.crash.txt`
(FATAL EXCEPTION excerpts), `logs/*.anr.txt` (ANR traces, rooted emulators), `logs/monkey-*.monkey.txt`, `meminfo.tsv` (PSS samples during monkey),
`shots/*.png` (RTL, font/density, night, rotation, multi-window, storage-recovery screenshots).
Exit status is non-zero if any check failed. PENDING and SKIP never fail the run.

Each failing check is a note tagged `[S<n> I<n>]` (`tag` in `lib.sh`; untagged notes count as S2/I10).
A crash is S1/I7 in storage, because the bad state persists and the app would crash on every launch;
anywhere else it is S2/I10. A monkey ANR whose pulled trace has no `dev.mote.remote` frame on the main
thread counts as "attributed" and is only checked against the budget (`monkey-anr-budget`: at most
2 per 50k events). Any other ANR fails. Budgets: 0 crashes, 0 ANRs outside monkey, PSS growth fails
when it is both over 30% and over 20 MB, and the device-state diff must be empty.

A scenario fails on any `FATAL EXCEPTION` / `ANR in dev.mote.remote` in logcat, new
`data_app_crash|anr|native_crash` entries in `dumpsys dropbox`, or a broken post-condition: app not
running or not resumed after relaunch, more than one `HidService` record, gate not shown after a
permission revoke (or stuck after re-grant), service still running in the background with no host.

## Scenarios

| group | what it does |
|---|---|
| storage | Replaces the DataStore files between launches. `mote`: random bytes, truncated proto, invalid macro JSON + unknown theme, hostile but valid JSON (duplicate ids, empty/20k-char names, unknown keys/icons/mods, negative delays, emoji). `mote_settings` and `hosts`: random bytes, and values stored with the wrong type (String under Float/Boolean keys). Then the whole directory deleted. App must start with defaults, twice. `prefs_pb.py` writes valid Preferences protos. |
| lifecycle | 40x rapid background/foreground; `am kill` in background; `am force-stop` at random points during `onStart` -> `startForegroundService`; 30 rotations; night-mode toggles; font scale 0.85-2.0 and density 60-150%; multi-window / freeform + task resize; app locale `ar` (RTL) with screenshots of all four modes. |
| resource | Doze (`deviceidle force-idle` + resume while idle), standby buckets rare/restricted/frequent/active, battery-saver flaps, airplane-mode flaps (also cycles Bluetooth), Wi-Fi/data flaps, `send-trim-memory` RUNNING_* and background levels, `pm revoke`/`grant` of each Bluetooth permission (gate must appear, then clear). |
| bluetooth | `cmd bluetooth_manager enable/disable` storms (60 fast, 20 slow) in the foreground, sampling `dumpsys activity services` (<= 1 HidService); then backgrounded: service must stop, and must not come back during a background storm. |
| network | Seeds a saved Mote Helper host "Chaos Mac" into the `hosts` store (10.0.2.2:8797, dummy wrapped credential, selected, `wifi_chosen`). Then: Wi-Fi off, airplane mode, mobile data off, Wi-Fi + data off, and a seeded flap storm. Before, during and after each one, it checks that the app is alive, the pill "Device: Chaos Mac" is shown, and the host sheet shows a connection state from `HostsUi.kt` (`Connecting…`, `Connected · …`, `Couldn't reach…`, the re-pair texts) (I8). If `helper/build/MoteHelper.app` exists, it starts a `--dry-run` helper on the Mac and seeds the helper's real hostId; otherwise the app keeps retrying a closed port. The seeded host is removed at the end. |
| connect | One-tap connect plus VPN and failover checks, each feature-detected (table below). PENDING until its signal appears, then a generic body runs: a storm, then liveness and visible-state checks. |
| monkey | 4 seeds, 3 unthrottled + 1 throttled (25 ms), syskeys off, crashes/timeouts ignored so the run continues and logcat/dropbox are harvested. A watchdog kills the monkey tool if it prints nothing for 90 s (it can hang after `Injection Failed` while the app is idle); that is reported as a tool stall, not an app failure. A sampler records `dumpsys meminfo` every 10 s; `leak-meminfo` fails on >30% and >20 MB PSS growth (first vs last quarter) or more live Activity objects after a forced GC (SIGUSR1) than ActivityRecords the system holds. |

### Pending checks (connect)

Detection greps `android/app/src/main/java` (`has_code` in `lib.sh`), so a check starts running on
its own once its code lands. Extend the generic body with the feature's own assertions at that point.

| check | runs when the sources / device have | until then |
|---|---|---|
| `connect-proximity`: BLE proximity token | `(class or object) ProximityToken` or `Proximity`, and Bluetooth on the device | PENDING |
| `connect-lan-sweep`: LAN subnet sweep | `(class or object) SubnetSweep`, `LanSweep` or `Sweep` | PENDING |
| `connect-wifi-first`: Wi-Fi-first auto-connect | `(class or object) AutoConnect`, `WifiFirst` or `ConnectPlanner` | PENDING |
| `connect-auto-trust`: phone side (I6 itself is decided by the helper) | `(class, object or fun) AutoTrust` or `autoTrust` | PENDING |
| `connect-vpn-storm`: Tailscale CONNECT/DISCONNECT_VPN storm with a tailnet host | `com.tailscale.ipn` installed on the device | PENDING |
| `connect-failover`: LAN, tailnet and BT failover and switch-back (docs/pairing.md section 5) | never on an emulator (no BT HID); still manual on a phone | PENDING |

## Physical device

`physical.sh --serial <serial> --seed 1 --budget 30m` runs the same suite (monkey excluded unless
`ONLY=...,monkey`) after asking you to retype the serial. Bluetooth storms there drop and re-establish
the real HID link; HID reports are real input on the paired computer, so unpair it or use a scratch
desktop. Not run automatically.

## Bugs found so far

| severity | bug | fix |
|---|---|---|
| high | Corrupt DataStore file (`CorruptionException`) crashed the app on every launch (thrown on the `mote-hid` thread from `Hid.start`, and from the Compose collectors): a crash loop only fixable by clearing data. | `Prefs.corruptionHandler` (`ReplaceFileCorruptionHandler { emptyPreferences() }`) on all three stores (`mote`, `mote_settings`, `hosts`); `PrefsCorruptionTest`. |
| medium | A value stored with the wrong type (schema change across versions, hand-edited file) crash-looped the app: `prefs[key]` is an unchecked cast, so `UiPrefs.read` / `Net.init` threw `ClassCastException`. | `Preferences.typed(key)` type-checked read for every DataStore read; `PrefsCorruptionTest.wrongTypeReadsAsNull`. |
| low | Duplicate macro ids in stored JSON crashed the Macros grid (`Key "a" was already used`). | `Macros.decode` drops duplicate ids; `MacroDecodeTest`. |
| medium | RTL system locale (ar, he, fa) mirrored the remote: mouse R left of L, next/prev, vol +/- and brightness swapped, Spaces arrows reversed. The UI is English-only, so nothing gained from mirroring. | `android:supportsRtl="false"`. `lifecycle-rtl` asserts the Left button is left of Right. |
| low | Font scale 2.0 clipped the fixed-height rows: Edit chip, Spaces carousel cards (numeral over the label), wordmark. No crash. | `CappedFontScale` (max 1.3) around the top bar, favourites header and carousel; other text keeps the full scale. See `shots/font-2.0.png`. |
| info | Monkey ANRs (1-2 per 50k events, input dispatch timeout): the main thread waits on RenderThread inside hwui (`RenderProxy::destroy` while a Compose popup is dismissed, `RenderProxy::setStopped`), with RenderThread stuck in the emulator EGL `dequeueBuffer` or a `RenderNodeAnimator` JNI callback. No app frames are on either stack; they land at different events across identical seed-7 runs, and SystemUI ANRs in the same runs. Consistent with the `-gpu guest` software renderer under host load. | None. Re-check on a phone (`physical.sh`) or a hardware-GPU emulator. |
| S1 (I9, harness) | The suite itself left the device changed: `window_animation_scale 0.5`, `svc power stayon true` and lifecycle's `enable_freeform_support 1` were never restored, and every restore wrote hard-coded defaults rather than the user's values. | Run-start snapshot + restore-to-snapshot + diff check (above). |
| S3 (I8) | An undecryptable wrapped helper token (Keystore wrap key gone, corrupt blob) only failed inside auth, which looked like a dropped socket, so the app reconnected forever showing "Connecting…" instead of asking to pair again. | `Vault.wrappedSigner` checks the blob up front, so a bad blob means no signer and "Pairing data is missing"; `VaultTest`, `network-stale-credential`. |
| info | A second `MainActivity` task can exist after `am start --windowingMode` (multi-window scenario) despite `singleTask`. `onStop` of the hidden instance would then clear `Hid/Net.foreground` while the other is visible. Only reachable via adb here. | Not fixed. |
