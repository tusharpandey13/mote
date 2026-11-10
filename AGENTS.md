# AGENTS.md

Mote: Android phone as a Bluetooth remote for a Mac. Public repo: github.com/tusharpandey13/mote.

## Map
- `android/`: Kotlin/Compose app: Bluetooth HID, plus Mote Helper over Wi-Fi / Tailscale (`net/`). Own it via `android/README.md`.
- `helper/`: Swift macOS menu-bar helper (Wi-Fi WebSocket + BLE GATT, CGEvent injector). `helper/tests/smoke.mjs` is its test.
- `site/`: website, `demo.html`, `install.sh`. `site/remote/`: web remote (Wi-Fi WebSocket / Web Bluetooth). The helper bundles it at build time.
- `chaos/helper/`: chaos/robustness suite for the helper (fault proxy, fuzzer, storms, kills): `node chaos/helper/run.mjs --budget 10m --seed N`. See `chaos/README.md`.
- `design/`: Paseo design canvas (`mote-v1`). `tools/design-gen/`: generator. `docs/`: protocol, design language, research.

## Source of truth
- **After ANY design change run `tools/design-build.sh`, then commit `design/` + `docs/design/`.** It runs gen.py, then `tools/design-export/dtcg.mjs` (`docs/design/tokens.json` -> `docs/design/tokens.dtcg.json`, W3C DTCG, validated), then `tools/design-export/export.mjs` (PNG of every artboard -> `design/exports/<group>/<id>.png` + `design/exports/README.md`; fails if a PNG > 300 KB or total > 5 MB). Needs Playwright (see README screenshots) and pngquant. Never hand-edit `design/exports/`, `design/mote-v1/index.html` (the Pages gallery at `/mote/design/`) or `tokens.dtcg.json`. Exports are written only locally; CI never touches `design/exports`.
- `docs/design/design-language.md` (tokens + revision decisions) -> `design/mote-v1/*.html` artboards are GENERATED: `python3 tools/design-gen/gen.py design/mote-v1`. Edit `tools/design-gen/gen.py` or `design/mote-v1/mote.css`, never the generated HTML.
- Canvas conventions (`data-dc-id`, variants as grouped artboards, comments): `design/README.md`. Resolve comments with the `design_resolve_comments` tool.
- `docs/protocol.md` is the contract between `site/remote/`, `helper/` and the Android app (`android/.../net/`). Change them together.

## Build and test
- Android: JDK 17, `ANDROID_HOME`/`local.properties` (gitignored). In `android/`: `./gradlew :app:testDebugUnitTest :app:lintRelease`, `./gradlew :app:assembleRelease`. Build the helper first: `HelperIntegrationTest` drives the real `--dry-run` helper over WebSocket (skipped if `helper/build/MoteHelper.app` is missing; `MOTE_HELPER=` overrides).
- Test app instance: `./gradlew :app:assembleDev` builds `dev.mote.remote.dev` ("Mote Dev", debug-signed, own data and key); use it on real phones, never the installed `dev.mote.remote`. Discovery e2e on the emulator: a `--dry-run --headless --memory-store --no-ble --fake-proximity --port 8795` helper, then `ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.mote.remote.e2e.SweepDiscoveryTest -Pandroid.testInstrumentationRunnerArguments.moteSweepPort=8795 [-Pandroid.testInstrumentationRunnerArguments.moteFakeProximity=true]`.
- Helper: `helper/build.sh` (`ARCHS=arm64 ./helper/build.sh` for one arch; signing: `docs/signing.md`). Also `helper/tests/unit.sh`, `helper/tests/signing.sh` (stubbed identity/notarize logic). It signs with the stable local identity "Mote Helper Local" (`helper/signing.sh`, created once in the login keychain; see `helper/README.md`), so permission grants survive rebuilds. Agents / non-interactive runs: `MOTE_ADHOC=1` (ad-hoc, no keychain access, no dialogs; CI is ad-hoc automatically). Unit tests: `helper/tests/unit.sh`. Smoke test:
  ```sh
  B=helper/build/MoteHelper.app/Contents/MacOS/MoteHelper
  $B --dry-run --headless --no-ble --memory-store --open-pairing --port 8791   # prints PAIRING CODE
  node helper/tests/smoke.mjs 8791 <code>        # or "-" to reuse the credFile (default /tmp/mote-cred.json)
  # Tailscale Host rules: start the helper with --allow-tailscale and run MOTE_TAILNET=1 node helper/tests/smoke.mjs ...
  ```
  Diagnostics: `MoteHelper --diag` prints the permission/signing/feature snapshot as JSON and exits (no prompts); `--events FILE` writes the event log as JSONL (protocol 1.5, `docs/protocol.md` 2.1). Helper unit tests: `helper/tests/unit.sh` (CLT only; plain `swift test` runs nothing); `node helper/tests/diag.mjs`.
  Never inject real input in tests: always `--dry-run`; use `--memory-store` or `--keychain-service NAME`.
- E2E (web remote + helper in `--dry-run`, and `site/install.sh`): `cd e2e/web && npm ci && npm test`. See `e2e/web/README.md`.
- User journeys (specs `docs/journeys/<id>.md`, defects `docs/journeys/DEFECTS.md`): `e2e/journeys/run.sh [journey-id|journey-id#path|all]` boots its own headless emulator (`mote-journeys`, `emulator-5562`; aborts on any non-emulator target), installs `dev.mote.remote.dev`, drives dry-run helpers and writes `e2e/journeys/REPORT.md` (screenshots in `/tmp/mote-journeys/`). See `e2e/journeys/README.md`. Update the spec when a journey changes; a step is only "pass" if asserted for real ("faked" = against a labelled seam).
- Site: the deployed layout is `site/` plus `design/mote-v1/*` copied to `design/` (see `.github/workflows/pages.yml`). Locally: `mkdir -p /tmp/mote-site/mote/design && cp -R site/. /tmp/mote-site/mote/ && cp -R design/mote-v1/. /tmp/mote-site/mote/design/ && python3 -m http.server -d /tmp/mote-site 8000`, open `http://localhost:8000/mote/`.

## Release
- Keystore and `keystore.properties` live in `~/.config/mote/`. Never commit them; losing the keystore breaks updates.
- Helper release zips: `MOTE_RELEASE=1 helper/build.sh`. Developer ID signs, then `helper/notarize.sh` (submit, staple, re-zip). Until Developer ID is bought, "Mote Helper Local" is accepted (hardened runtime, NOT notarized, warning; state "one Keychain prompt per helper update" in the release notes). Apple Development (free personal team, terms forbid distribution) and ad-hoc are refused; a release skips Apple Development automatically. Setup, identities and the Keychain root cause: `docs/signing.md`. Local builds use Apple Development so the Keychain stays silent. Back up the exported Developer ID `.p12` in `~/.config/mote/`.
- Tag `vX.Y.Z`, then `gh release create vX.Y.Z` with: `mote-vX.Y.Z.apk` (+ `.sha256`), `MoteHelper-macos-arm64.zip`, `MoteHelper-macos-x86_64.zip` (+ `.sha256` each). `site/install.sh` downloads `releases/latest/download/MoteHelper-macos-<arch>.zip(.sha256)`; keep those names exact.
- Publishing a release (and any push to main touching `design/**`, `tools/readme-shots/**` or `README.md`) runs `.github/workflows/readme-shots.yml`: it re-renders the README images and `site/og.png` (only those; `design/exports` is local-only, see Source of truth), commits changes as `github-actions[bot]` (`[skip ci]`) and dispatches `pages.yml`. The live site embeds the artboards directly, so it is always current after deploy. Nothing to do by hand; `gh workflow run readme-shots.yml` forces it.

## Commits
- Conventional commits (`feat:`, `fix:`, `docs:`, `site:`, `helper:`), small and logical.
- End with the `Co-Authored-By: ...` trailer.
- Never commit build outputs, keystores or secrets.

## Constraints
- HID can't be tested in an emulator; needs a real phone.
- A browser can't be a Bluetooth peripheral; that's why the helper exists.
- macOS keys permissions (TCC) and Keychain ACLs to the helper's signature: keep the "Mote Helper Local" identity; an ad-hoc build loses them. The helper never asks for a permission from a background event: only the Setup window (`SetupWindow.swift`, `Permissions.swift`) or a feature toggle may prompt.
- Instrumented tests (`connectedAndroidTest`, `am instrument`) MUST be pinned to an emulator: `ANDROID_SERIAL=emulator-5554 ./gradlew ...` (or `adb -s emulator-…`). Gradle otherwise installs, runs, clears data and uninstalls on EVERY connected device, including the user's physical phone (serial b7e39c2f). Never run tests on the physical phone unless the user explicitly asks.

## README screenshots
(`design/exports/` is a separate, locally built gallery: use `tools/design-build.sh`.)
`docs/assets/readme/*.png` and `site/og.png` are renders of the design artboards (hero = 3 phones), generated by CI (see Release). Every `docs/assets/readme/<name>.png` referenced in `README.md` is rendered from `design/mote-v1/<name>.html`; add the reference to add a shot. Locally: `cd e2e/web && npm ci && npx playwright install chromium`, optionally `brew install pngquant` (smaller files; CI uses it, so install it for identical output), then from the repo root `node tools/readme-shots/shots.mjs` (`PLAYWRIGHT_DIR` overrides where `node_modules/playwright` is found). Output is deterministic for a given pngquant version. Keep each shot <= 150 KB and the hero <= 400 KB. Swap in real device screenshots once the app restyle lands.
