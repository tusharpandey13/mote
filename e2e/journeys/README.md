# Journey acceptance tests

Every user journey in `docs/journeys/<id>.md` (preconditions, numbered steps with an exact expected
observable, postconditions, one sub-journey per failure branch) is executed on a headless emulator
against dry-run Mote Helpers. Results: `REPORT.md` (per journey, per step), screenshots in
`/tmp/mote-journeys/<path>/<step>.png`, helper logs in `/tmp/mote-journeys/helper-<name>.log`.

```sh
e2e/journeys/run.sh all              # every journey
e2e/journeys/run.sh connect-next     # one journey (class ConnectNextJourney)
e2e/journeys/run.sh connect-next#far # one path (test method)
```

`run.sh`:
- refuses any target but an emulator (`MJ_SERIAL`, default `emulator-5562`, must match `emulator-N` and report `ro.kernel.qemu=1`); exports `ANDROID_SERIAL` so Gradle installs nowhere else;
- creates the AVD `mote-journeys` (API 35 google_apis arm64, pixel_7) if missing and boots it with `-no-window -gpu guest -no-audio -no-snapshot -port 5562`; shuts it down at the end if it booted it (`MJ_KEEP_EMULATOR=1` keeps it);
- builds the helper if missing (`MOTE_ADHOC=1 ARCHS=arm64 helper/build.sh`; `MJ_REBUILD_HELPER=1` forces it);
- starts `controller.mjs` on 127.0.0.1:8799 (the emulator reaches it at 10.0.2.2:8799);
- runs `./gradlew -PmoteJourneys :app:connectedDevAndroidTest` (`-PmoteJourneys` adds `src/androidTest/journeys` and makes `dev` the test build type, so only `dev.mote.remote.dev` is installed);
- writes `REPORT.md` with `report.mjs`. One run at a time (`/tmp/mote-journeys.lock`).

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
