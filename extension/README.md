# Mote Video Bridge (preview)

A browser extension that lets the Mote app control the main video in your browser tab directly: play/pause, seek, ±10 s, speed, volume, fullscreen, next/previous, captions and skip-ad / skip-intro buttons. It talks only to Mote Helper on this Mac (`ws://127.0.0.1:8792`). Contract and threat model: [docs/labs/video-bridge.md](../docs/labs/video-bridge.md).

Labs preview: off by default everywhere, may break when sites change.

## Install (Chrome, Edge, Arc, Brave: "Load unpacked")

1. Mote Helper menu › **Labs** › turn on **Browser extension bridge**.
2. Open `chrome://extensions` (Edge: `edge://extensions`, Arc: `arc://extensions`).
3. Turn on **Developer mode** (top right).
4. Click **Load unpacked** and choose this `extension/` folder.
5. Pin "Mote Video Bridge" and click it. In the Mote Helper menu choose **Labs › Pair browser extension…**; the menu now shows "Extension code: 123 456". Type it in the popup and press **Pair**. The popup says "Connected to Mote Helper".
6. In the Mote app: **Settings › Labs › Browser video control (preview)**. Play a video in the browser; a **Web video** panel appears in the Media tab while connected over Wi-Fi or Tailscale.

The extension keeps its token in `chrome.storage.local`. "Forget pairing" in the popup, or Labs › "Forget browser extension" in the helper menu, removes it. Moving the folder changes an unpacked extension's ID; pair again after that (the helper pins the paired extension origin).

## What it controls

| Site | Adapter features |
|---|---|
| Any page | Largest visible `<video>` (playing ones first), in every frame the browser lets extensions into; captions via `textTracks`; skip buttons found by text ("Skip ad", "Skip intro") |
| YouTube | Title, next / previous, captions button, skip ad |
| Netflix | Title, skip intro / recap, next episode; seeking through the page's player API (may break) |
| Prime Video | Title, skip intro / ad, next up |
| Twitch | Title, live detection (no seeking on live) |

Adapters are CSS selectors in `adapters.js`. Limits (fullscreen needs a user gesture, autoplay policy, Netflix seeking): see the doc.

Play from the phone may be refused by the browser's autoplay policy on a page you haven't interacted with yet (the phone shows an error); click the page once, then it works.

## Files

- `manifest.json`: Manifest V3, Chrome 116+.
- `background.js`: service worker: the helper connection, pairing, which tab/frame is relayed, command routing.
- `content.js` + `adapters.js`: per frame: finds the main video, reports state, runs commands.
- `netflix-main.js`: page-world script (`world: "MAIN"`) for Netflix seeking.
- `popup.html` / `popup.js`: status and pairing.

## Safari (not built)

`xcrun safari-web-extension-converter extension/ --app-name "Mote Video Bridge"` produces an Xcode project wrapping the same files. Untested: Safari's handling of `ws://127.0.0.1` from an extension worker and of `world: "MAIN"` differs by version.

## Tests

`cd e2e/web && npm ci && npx playwright test tests/video-bridge.spec.ts` loads this folder unpacked into Chromium against local pages and a `--dry-run` helper. `node helper/tests/bridge.mjs` covers the helper side.
