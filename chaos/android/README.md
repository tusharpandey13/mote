# Android chaos suite

Seeded, adb-driven robustness tests for the Mote Android app. It looks for crashes, ANRs, leaks and
broken state under hostile device conditions. Bluetooth HID itself can't be exercised on an emulator;
`physical.sh` covers the real link.

```sh
chaos/android/run.sh --serial emulator-5556 --seed 42 --budget 20m
```

| flag | meaning |
|---|---|
| `--serial` | adb serial (required). Only `emulator-*` serials are accepted; physical devices go through `physical.sh`. |
| `--seed N` | Seeds monkey (`N`..`N+3`) and every random sleep/size in the scripts (file-backed LCG, so draws inside subshells stay reproducible). |
| `--budget` | `20m`, `1h`, `900s`. Scenarios check the deadline; monkey runs last and scales its event count to the time left (at 20m: 3 x 20k unthrottled + 5k throttled events). |
| `--apk PATH` / `--no-build` | Install a given APK / skip build+install. Default: build and `install -r -g` the debug APK. |
| `--only a,b` | Subset of `storage,lifecycle,resource,bluetooth,monkey`. |

The debug build is needed for the storage scenario (it uses `run-as`); everything else works with release.
Device settings (font scale, density, night mode, rotation, Doze, battery saver, airplane, Wi-Fi/data,
Bluetooth, standby bucket, app locale, permissions) are restored after every scenario and on exit.

## Output

`chaos/android/out/<time>-s<seed>/` (gitignored): `summary.md` (the table, also printed),
`results.tsv`, `run.log`, `logs/<scenario>.log` (logcat main/system/crash), `logs/*.crash.txt`
(FATAL EXCEPTION excerpts), `logs/monkey-*.monkey.txt`, `meminfo.tsv` (PSS samples during monkey),
`shots/*.png` (RTL, font/density, night, rotation, multi-window, storage-recovery screenshots).
Exit status is non-zero if any scenario failed.

A scenario fails on any `FATAL EXCEPTION` / `ANR in dev.mote.remote` in logcat, new
`data_app_crash|anr|native_crash` entries in `dumpsys dropbox`, or a broken post-condition: app not
running or not resumed after relaunch, more than one `HidService` record, gate not shown after a
permission revoke (or stuck after re-grant), service still running in the background with no host.

## Scenarios

| group | what it does |
|---|---|
| storage | Replaces `files/datastore/mote.preferences_pb` between launches: random bytes, truncated proto, invalid macro JSON + unknown theme, hostile but valid JSON (duplicate ids, empty/20k-char names, unknown keys/icons/mods, negative delays, emoji), deleted directory. App must start with defaults, twice. `prefs_pb.py` writes valid Preferences protos. |
| lifecycle | 40x rapid background/foreground; `am kill` in background; `am force-stop` at random points during `onStart` -> `startForegroundService`; 30 rotations; night-mode toggles; font scale 0.85-2.0 and density 60-150%; multi-window / freeform + task resize; app locale `ar` (RTL) with screenshots of all four modes. |
| resource | Doze (`deviceidle force-idle` + resume while idle), standby buckets rare/restricted/frequent/active, battery-saver flaps, airplane-mode flaps (also cycles Bluetooth), Wi-Fi/data flaps, `send-trim-memory` RUNNING_* and background levels, `pm revoke`/`grant` of each Bluetooth permission (gate must appear, then clear). |
| bluetooth | `cmd bluetooth_manager enable/disable` storms (60 fast, 20 slow) in the foreground, sampling `dumpsys activity services` (<= 1 HidService); then backgrounded: service must stop, and must not come back during a background storm. |
| monkey | 4 seeds, 3 unthrottled + 1 throttled (25 ms), syskeys off, crashes/timeouts ignored so the run continues and logcat/dropbox are harvested. A sampler records `dumpsys meminfo` every 10 s; `leak-meminfo` fails on >30% and >20 MB PSS growth (first vs last quarter) or >1 live Activity. |

## Physical device

`physical.sh --serial <serial> --seed 1 --budget 30m` runs the same suite (monkey excluded unless
`ONLY=...,monkey`) after asking you to retype the serial. Bluetooth storms there drop and re-establish
the real HID link; HID reports are real input on the paired computer, so unpair it or use a scratch
desktop. Not run automatically.

## Bugs found so far

| severity | bug | fix |
|---|---|---|
| high | Corrupt DataStore file (`CorruptionException`) crashed the app on every launch (thrown on the `mote-hid` thread from `Hid.start`, and from the Compose collectors): a crash loop only fixable by clearing data. | `ReplaceFileCorruptionHandler { emptyPreferences() }` in `Prefs`; `PrefsCorruptionTest`. |
| low | Duplicate macro ids in stored JSON crashed the Macros grid (`Key "a" was already used`). | `Macros.decode` drops duplicate ids. Covered by `storage-hostile-values`. |
| medium | RTL system locale (ar, he, fa) mirrored the remote: mouse R left of L, next/prev, vol +/- and brightness swapped, Spaces arrows reversed. The UI is English-only, so nothing gained from mirroring. | `android:supportsRtl="false"`. `lifecycle-rtl` asserts L is left of R. |
| low (open) | Font scale 2.0 clips the favourites header ("EDIT") and the Spaces tile subtitle; device pill truncates. No crash. | Not fixed (UI owned elsewhere); see `shots/font-2.0.png`. |
