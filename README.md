# MOTE

[![Latest release](https://img.shields.io/github/v/release/tusharpandey13/mote)](https://github.com/tusharpandey13/mote/releases/latest)
![License: TBD](https://img.shields.io/badge/license-TBD-lightgrey)

**Your Android phone as a Bluetooth remote for your Mac (or any Bluetooth host).**
Media keys, brightness, D-pad, a scroll lever, a touchpad, a full keyboard with sticky modifiers, and macros.

Website: https://tusharpandey13.github.io/mote/ (once deployed it will also be at https://tusharpandey.com/mote/, via custom-domain redirect). It is deployed by GitHub Pages from `site/` and `design/` (`.github/workflows/pages.yml`).

## Screenshots

No screenshots are committed. Look at the design instead:

- Design artboards (generated HTML, open in a browser): [`design/mote-v1/media-light.html`](design/mote-v1/media-light.html), [`pad.html`](design/mote-v1/pad.html), [`keys.html`](design/mote-v1/keys.html), [`macros.html`](design/mote-v1/macros.html), [`pairing.html`](design/mote-v1/pairing.html)
- Interactive demo: [`site/demo.html`](site/demo.html) (on the website: `/demo.html`)

## Two ways to use it

| | 1. Android app (pure Bluetooth HID) | 2. Browser + Mote Helper on the Mac |
|---|---|---|
| Phone side | Mote APK | Any browser; Chrome on Android for Bluetooth |
| Mac side | Nothing. The phone looks like a keyboard + mouse + media controller | Mote Helper (menu-bar app) |
| Transport | Bluetooth HID | Wi-Fi (`http://<mac>.local:8790`, WebSocket) or BLE (Web Bluetooth) |
| Pairing | macOS Bluetooth settings | Code shown by the helper, then a per-device token |
| Works at login / lock screen | Yes | No |
| Text typing | US layout only | Any Unicode |
| Extras | Foreground service keeps the link alive with screen off | `clickAt`, layout-independent text, brightness fallback |

Install the helper (macOS, no sudo, SHA-256 verified):

```sh
curl -fsSL https://tusharpandey13.github.io/mote/install.sh | bash
```

Then open `http://<mac>.local:8790` on the phone, or `remote/` on the site for Bluetooth. The helper needs Accessibility permission to inject input.

Security: pairing needs a one-time code shown on the Mac, each device gets its own token, and the menu bar has Pause input and Revoke all devices. Wi-Fi mode is plain http/ws, not encrypted; pair on a network you trust. Details: [`docs/protocol.md`](docs/protocol.md) section 4.

## Install the Android app

1. Download `mote-v0.1.0.apk` and `mote-v0.1.0.apk.sha256` from [Releases](https://github.com/tusharpandey13/mote/releases/latest).
2. Verify: `shasum -a 256 -c mote-v0.1.0.apk.sha256` (run in the download folder).
3. Install it (open the APK on the phone, or `adb install -r mote-v0.1.0.apk`).
4. Open Mote, allow Nearby devices and Notifications, tap the device pill, then **Pair new device**. The phone is discoverable for 2 minutes.
5. On the Mac: System Settings > Bluetooth, connect to the phone, confirm the code. If Keyboard Setup Assistant opens, pick ANSI.

## Status and known limitations

Early (v0.1.0). Be skeptical:

- **Not yet tested on real hardware.** The emulator has no HID profile.
- Brightness (Consumer 0x6F/0x70) is unconfirmed; it probably works on built-in displays.
- Typing in the app uses the US layout; characters it can't type are skipped. No `fn` over generic HID.
- The touchpad has mouse semantics. No 3-finger gestures or tap-and-hold drag.
- Scroll direction assumes macOS natural scrolling.
- Not in the MVP: click-at-point macros, multi-step macro editing in the UI (the editor records one chord), landscape layouts, non-US layouts, haptics/pointer-speed settings, Quick Settings tile and widget.
- Helper: ad-hoc signed, not notarized. After an update you may need to re-grant Accessibility and click "Always Allow" on a Keychain prompt. Wi-Fi traffic is unencrypted, and BLE writes are not link-encrypted. The helper does not work at the lock screen.

Full lists: [`android/README.md`](android/README.md), [`docs/protocol.md`](docs/protocol.md).

## Building from source

- Android: [`android/README.md`](android/README.md) (JDK 17, `./gradlew :app:assembleRelease`).
- Helper: `helper/build.sh` (Swift toolchain only, builds `helper/build/MoteHelper.app` and the zips).
- Site: static files in `site/`; the web remote is `site/remote/`.
- Agents and contributors: see [`AGENTS.md`](AGENTS.md).

## Repo layout

| Path | What |
|---|---|
| `android/` | Kotlin + Compose app (Bluetooth HID device) |
| `helper/` | Swift macOS menu-bar helper (HTTP/WebSocket, Bonjour, BLE GATT) and its smoke test |
| `site/` | Website, demo, `install.sh`, web remote (`site/remote/`) |
| `design/` | Design canvas files (`mote-v1`, generated) |
| `tools/design-gen/` | Generator for the design artboards |
| `docs/` | Protocol, design language, research notes |
| `.github/` | GitHub Pages workflow |
| `NOTICE` | Third-party attributions |

## Credits

- [Linkpad](https://github.com/Devdas-gupta/linkpad) (MIT): HID report descriptor layout and QoS values, see [`NOTICE`](NOTICE).
- [Unbounded](https://github.com/googlefonts/unbounded) font (SIL OFL 1.1), see [`android/licenses/OFL-Unbounded.txt`](android/licenses/OFL-Unbounded.txt).

License: TBD (no LICENSE file yet).
