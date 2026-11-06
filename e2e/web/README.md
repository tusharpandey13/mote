# e2e/web

End-to-end tests for the web remote (`site/remote/`) driving a real Mote Helper, plus `site/install.sh`.
Playwright, Chromium only, Pixel 7 emulation (mobile viewport, touch).

```sh
cd e2e/web
npm ci
npx playwright install chromium   # once
npm test                          # whole suite
npm run test:repeat               # every test 3 times
npx playwright test tests/mouse.spec.ts -g "drag lock"
```

## How it works

- `global-setup.ts` builds the helper (`helper/build.sh`, native arch) if it's missing or older than
  `helper/Sources`, and copies `site/remote` + the design assets into a temp web root. The helper serves
  that root (`--web-root`), so remote edits are tested without rebuilding. `MOTE_HELPER=/path/to/binary`
  skips the build.
- `lib/helper.ts` starts the helper as `--dry-run --headless --no-ble --memory-store --stdin-control --port 0`
  and refuses to start it without `--dry-run`: nothing is ever injected into the Mac session, and nothing
  touches the Keychain. Each worker gets one helper; tests that kill it or trip its rate limiter start
  their own.
- The oracle is the helper's log (`[mote] DRY key usage=80 mods=1 action=tap`, `DRY consumer play`,
  `DRY move ...`, `DRY scroll ...`, `DRY button ...`, `DRY text "..."`, `paired ...`, `authed ...`).
  Tests take a `mark()` and wait for lines after it.
- No fixed sleeps. Tests wait on log lines or UI state. Timing-dependent behaviour (lever ticks,
  auto-repeat, move coalescing, double-tap windows) runs on Playwright's fake clock (`page.clock`):
  the test pauses it and advances it with `runFor`. Negative checks ("release stops it") use a
  sentinel: tap a key after the window, wait for its line, then count what came before it.
- Multi-touch goes through CDP `Input.dispatchTouchEvent` (`Touch` in `lib/fixtures.ts`), so the page
  gets real touch pointer events with one pointerId per finger.

## Test-only helper hooks

- `--port 0`: bind any free port. The real port is in the log line `wifi: listening on :N`.
- `--stdin-control` (honoured only with `--dry-run`): stdin commands standing in for the menu bar:
  `pair` (open a pairing window, prints `PAIRING CODE: nnnnnn` on stdout), `revoke`, `pause`, `resume`.
- `macro: cancelled` is logged when a running macro is cancelled.

## install.sh tests

`tests/install.spec.ts` runs `site/install.sh` against a local HTTP server that serves a fake
`MoteHelper-macos-<arch>.zip` + `.sha256`, through `MOTE_RELEASE_URL` (default unchanged: the GitHub latest
release over https; plain http is accepted only for 127.0.0.1/localhost). It runs with a temp `HOME` and
PATH stubs for `uname` (fake OS/CPU), `pgrep`/`pkill` (never quit a real Mote Helper) and `open` (never
launch anything). No network; the real `~/Applications` is checked to be untouched.

## Not covered

- Macro editing: the web remote has no macro editor (macros are fixed tiles), so there is nothing to edit.
- A helper restart that keeps its devices (the real app keeps tokens in the Keychain). With
  `--memory-store` the restarted helper forgets the browser, so the reconnect test checks the page
  reconnects on its own and offers its token, then re-pairs.
- Real Bluetooth (needs a phone). The Web Bluetooth tests cover the availability messages and the
  cancelled-chooser path with a stubbed `navigator.bluetooth`.
