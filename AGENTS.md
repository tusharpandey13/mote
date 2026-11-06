# AGENTS.md

Mote: Android phone as a Bluetooth remote for a Mac. Public repo: github.com/tusharpandey13/mote.

## Map
- `android/`: Kotlin/Compose app: Bluetooth HID, plus Mote Helper over Wi-Fi / Tailscale (`net/`). Own it via `android/README.md`.
- `helper/`: Swift macOS menu-bar helper (Wi-Fi WebSocket + BLE GATT, CGEvent injector). `helper/tests/smoke.mjs` is its test.
- `site/`: website, `demo.html`, `install.sh`. `site/remote/`: web remote (Wi-Fi WebSocket / Web Bluetooth). The helper bundles it at build time.
- `chaos/helper/`: chaos/robustness suite for the helper (fault proxy, fuzzer, storms, kills): `node chaos/helper/run.mjs --budget 10m --seed N`. See `chaos/README.md`.
- `design/`: Paseo design canvas (`mote-v1`). `tools/design-gen/`: generator. `docs/`: protocol, design language, research.

## Source of truth
- `docs/design/design-language.md` (tokens + revision decisions) -> `design/mote-v1/*.html` artboards are GENERATED: `python3 tools/design-gen/gen.py design/mote-v1`. Edit `tools/design-gen/gen.py` or `design/mote-v1/mote.css`, never the generated HTML.
- Canvas conventions (`data-dc-id`, variants as grouped artboards, comments): `design/README.md`. Resolve comments with the `design_resolve_comments` tool.
- `docs/protocol.md` is the contract between `site/remote/`, `helper/` and the Android app (`android/.../net/`). Change them together.

## Build and test
- Android: JDK 17, `ANDROID_HOME`/`local.properties` (gitignored). In `android/`: `./gradlew :app:testDebugUnitTest :app:lintRelease`, `./gradlew :app:assembleRelease`. Build the helper first: `HelperIntegrationTest` drives the real `--dry-run` helper over WebSocket (skipped if `helper/build/MoteHelper.app` is missing; `MOTE_HELPER=` overrides).
- Helper: `helper/build.sh` (`ARCHS=arm64 ./helper/build.sh` for one arch). Smoke test:
  ```sh
  B=helper/build/MoteHelper.app/Contents/MacOS/MoteHelper
  $B --dry-run --headless --no-ble --memory-store --open-pairing --port 8791   # prints PAIRING CODE
  node helper/tests/smoke.mjs 8791 <code>        # or "-" to reuse the credFile (default /tmp/mote-cred.json)
  # Tailscale Host rules: start the helper with --allow-tailscale and run MOTE_TAILNET=1 node helper/tests/smoke.mjs ...
  ```
  Never inject real input in tests: always `--dry-run`; use `--memory-store` or `--keychain-service NAME`.
- E2E (web remote + helper in `--dry-run`, and `site/install.sh`): `cd e2e/web && npm ci && npm test`. See `e2e/web/README.md`.
- Site: the deployed layout is `site/` plus `design/mote-v1/*` copied to `design/` (see `.github/workflows/pages.yml`). Locally: `mkdir -p /tmp/mote-site/mote/design && cp -R site/. /tmp/mote-site/mote/ && cp -R design/mote-v1/. /tmp/mote-site/mote/design/ && python3 -m http.server -d /tmp/mote-site 8000`, open `http://localhost:8000/mote/`.

## Release
- Keystore and `keystore.properties` live in `~/.config/mote/`. Never commit them; losing the keystore breaks updates.
- Tag `vX.Y.Z`, then `gh release create vX.Y.Z` with: `mote-vX.Y.Z.apk` (+ `.sha256`), `MoteHelper-macos-arm64.zip`, `MoteHelper-macos-x86_64.zip` (+ `.sha256` each). `site/install.sh` downloads `releases/latest/download/MoteHelper-macos-<arch>.zip(.sha256)`; keep those names exact.

## Commits
- Conventional commits (`feat:`, `fix:`, `docs:`, `site:`, `helper:`), small and logical.
- End with the `Co-Authored-By: ...` trailer.
- Never commit build outputs, keystores or secrets.

## Constraints
- HID can't be tested in an emulator; needs a real phone.
- A browser can't be a Bluetooth peripheral; that's why the helper exists.
- The helper is ad-hoc signed: each build needs Accessibility re-granted.
