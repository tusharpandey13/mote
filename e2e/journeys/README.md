# Journey acceptance tests

Every user journey in `docs/journeys/<id>.md` (preconditions, numbered steps with an exact expected
observable, postconditions, one sub-journey per failure branch) is executed on a headless emulator
against dry-run Mote Helpers. Results: `REPORT.md` (per journey, per step), screenshots in
`/tmp/mote-journeys/<path>/<step>.png`, helper logs in `/tmp/mote-journeys/helper-<name>.log`.

```sh
e2e/journeys/run.sh all              # every journey
e2e/journeys/run.sh connect-next     # one journey (class ConnectNextJourney)
e2e/journeys/run.sh connect-next#far # one path (test method)
e2e/journeys/run.sh --level L2 [J4 reconnect ...]   # the L2 rows of the core specs J1-J7 (all, or the named ones)
```

`--level L2` reads the step tables of `docs/journeys/{install-helper,...,mac-update}.md` (shared reader `tools/journeys/rows.mjs`),
collects the cited tests and runs them in one go: every android cite in ONE Gradle invocation (JVM tests with `--tests`, instrumented
ones as one `class=` list, pinned to the emulator), helper cites via `helper/tests/unit.sh`, `node:` cites with node, `e2e:` cites with
Playwright in `e2e/web`. An unknown class or method is an error before anything boots (`l2.mjs plan`). Per-cite results and logs are in
`/tmp/mote-journeys/l2/`; `REPORT.md` gets a "Core journeys by level" section (pass / partial / fail / not run / manual per row, with
log links, and whether each row's detected-by is asserted from `diag` events: `asserted` / `gap` / `n/a`). Its per-level row counts must
match the Coverage table in `docs/journeys/README.md` (regenerate it with `node e2e/journeys/rows.mjs coverage --write docs/journeys/README.md`);
`--strict` fails the report when they differ. Helper side events are asserted with `Helper.event(area, event, regex)` (the helper's
`--events` JSONL), app side with `JourneyRule.appEvent(area, event)` (the in-process `Events` ring).

`run.sh`:
- refuses any target but an emulator (`MJ_SERIAL`, default `emulator-5562`, must match `emulator-N` and report `ro.kernel.qemu=1`); exports `ANDROID_SERIAL` so Gradle installs nowhere else;
- creates the AVD `mote-journeys` (API 35 google_apis arm64, pixel_7) if missing and boots it with `-no-window -gpu guest -no-audio -no-snapshot -port 5562`; shuts it down at the end if it booted it (`MJ_KEEP_EMULATOR=1` keeps it);
- builds the helper if missing (`MOTE_ADHOC=1 ARCHS=arm64 helper/build.sh`; `MJ_REBUILD_HELPER=1` forces it);
- starts `controller.mjs` on 127.0.0.1:8799 (the emulator reaches it at 10.0.2.2:8799);
- runs `./gradlew -PmoteJourneys :app:connectedDevAndroidTest` (`-PmoteJourneys` adds `src/androidTest/journeys` and makes `dev` the test build type, so only `dev.mote.remote.dev` is installed);
- writes `REPORT.md` with `report.mjs`. One run at a time (`/tmp/mote-journeys.lock`).

## Phone mode (opt-in, real phone over wireless adb)

To keep the Mac cool (no emulator), selected journeys can run on the owner's phone, Mote Dev app only (`dev.mote.remote.dev`;
its data and the animation scales may change; the real `dev.mote.remote` is never installed or touched):

```sh
MJ_PHONE=100.94.249.58:39963 MJ_PHONE_HW=b7e39c2f e2e/journeys/run.sh connect-next#unreachable   # not `all`
```

- Needs both variables; `run.sh` aborts unless `ro.serialno` equals `MJ_PHONE_HW` (a stale wireless-adb address can't hit another device). If the adb port changed, it stops: pass the new one. `--level` is refused.
- No emulator boots. The Mac is reached at its tailnet IP (`MJ_HOST`, default `tailscale ip -4`): the controller binds only that IP and its helpers start with `--allow-tailscale`; the app gets `-e mjHost` (default `10.0.2.2`) and `Ctl.host` replaces the emulator alias in the tests. The phone and the Mac must be on Tailscale.
- Steps that turn Wi-Fi, data or airplane mode off would drop wireless adb: `j.wifi(false)`, `svc wifi|data disable`, `airplane-mode enable` throw an assumption failure, the step is recorded MANUAL "skipped: phone mode (would drop wireless adb)" and the test is reported skipped. The per-journey network reset does nothing.
- The LAN sweep only probes the journey helper's test port (or `NO_HELPER_PORT`), never the real helper's 8790. Journeys that use `10.0.2.3` as a dead address still do (it is unreachable on most LANs).

## Pieces

| File | Role |
|---|---|
| `controller.mjs` | Starts/stops/signals helpers (always `--dry-run --headless --memory-store --no-ble --no-bonjour --stdin-control`, plus whitelisted `--fake-*` / journey flags), feeds stdin-control, serves helper logs, stores screenshots and step results. Never touches the Keychain, TCC or windows. |
| `android/app/src/androidTest/journeys/.../Journey.kt` | `JourneyRule`: `path(id)`, `helper(flags…)`, `launch(seams…)`, `step(id, seam) { }`, `manual/undefined/static(id, why)`, UI waits on exact copy / content descriptions, system-dialog taps (UiAutomator), `shell`, `wifi(on)`. |
| `…/journeys/<Pascal>Journey.kt` | One class per spec, one `@Test` per path; step ids match the spec tables. |
| `report.mjs` | Joins the spec tables with `results.jsonl`. A step without a result is MANUAL / UNDEFINED / STATIC from its spec Seam column, else NOT RUN. Nothing is PASS without an assertion. |

## Seams (labelled `faked:` in the report)

- Discovery: Bonjour off (`TestSeam.noBonjour`), the LAN sweep probes the helper's port (`TestSeam.sweepPort`) on 10.0.2.0/24; the Mac is 10.0.2.2.
- BLE proximity: `TestSeam.fakeProximity` reads the token from a `--fake-proximity` helper over the WebSocket.
- Tailscale owner: helper `--fake-tailscale-owner self`. A real tailnet is MANUAL.
- Bluetooth HID: `TestSeam.transport` = `FakeTransport` (androidTest `e2e/`), adapter `TestSeam.adapter`; helper `--fake-bt-pair[-fail R]`.
- Mac UI (menu bar, notices, alerts, TCC): headless; the helper's log / stdout (`NEW DEVICE:`, `DRY …`) stands in, or the step is MANUAL.
