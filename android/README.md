# Mote for Android

The phone becomes a Bluetooth HID device (keyboard + mouse + consumer control) for a Mac or any
Bluetooth host. No software is needed on the host. With Mote Helper on a Mac it can also connect over
Wi-Fi or Tailscale (protocol: `docs/protocol.md`), which adds Unicode typing, precise brightness and
click-at-point. Architecture: `docs/research/tech.md`, `docs/research/network-transport.md`. Design: `docs/design/design-language.md`.

## Toolchain (verified 2026-11-06)

| | Version |
|---|---|
| JDK | Temurin 17 (`brew install --cask temurin@17`) |
| Gradle wrapper | 9.8.2 |
| Android Gradle Plugin | 9.5.1 (built-in Kotlin) |
| Kotlin / Compose compiler plugin | 2.4.21 |
| Compose BOM | 2026.09.00 (Compose 1.12) |
| SDK | compileSdk 37 (Compose 1.12 requires it), targetSdk 35, minSdk 28 |

SDK setup (once):

```sh
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
yes | sdkmanager --sdk_root=$HOME/Library/Android/sdk --licenses
sdkmanager --sdk_root=$HOME/Library/Android/sdk "platforms;android-37.0" "platforms;android-35" "build-tools;35.0.0" "platform-tools"
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties   # gitignored
```

## Build

```sh
cd android
./gradlew :app:testDebugUnitTest :app:lintRelease   # unit tests + helper integration test + lint (0 errors)
./gradlew :app:assembleDebug                        # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleRelease                      # R8-minified, signed if a keystore is configured
```

### Tests

- `net/ProtoTest`, `net/HostsTest`: message encoding, HMAC (RFC 4231 and a `node:crypto` vector), host grouping, the connect fallback order, address and pairing-link parsing.
- `net/HelperIntegrationTest`: starts the built helper (`helper/build.sh` first) with `--dry-run --headless --no-ble --memory-store --open-pairing` on a free port, then pairs, authenticates and sends input through `WsTransport`, asserting the helper's `DRY ...` log lines (coalesced moves, ordering, Unicode text, brightness, clickAt, wrong token / code). Nothing is injected. It is skipped if no helper binary is found; point it elsewhere with `MOTE_HELPER=/path/to/MoteHelper`. Run only it: `./gradlew :app:testDebugUnitTest --tests '*HelperIntegrationTest'`.

### Release signing

Gradle reads `~/.config/mote/keystore.properties` (outside the repo, `chmod 600`):

```properties
storeFile=/Users/<you>/.config/mote/mote-release.jks
storePassword=...
keyAlias=mote
keyPassword=...
```

If the file is missing, `assembleRelease` produces an unsigned APK. Never commit the keystore or the passwords.
To verify a build, run `$ANDROID_HOME/build-tools/35.0.0/apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk`.

## Install

Turn on USB debugging on the phone, then:

```sh
adb install -r app/build/outputs/apk/release/app-release.apk
```

## First use

1. Open Mote and allow **Nearby devices** and **Notifications**.
2. Tap the device pill, then **Pair new device**. The phone becomes discoverable for 2 minutes.
3. On the Mac, open System Settings > Bluetooth and connect to the phone. Confirm the code on Android.
   macOS may open Keyboard Setup Assistant. Pick ANSI.
4. After that, pick the host from the device pill. While Mote is open it keeps trying to reconnect to the selected host.
   A foreground service keeps the link alive with the screen off. It stops when the app is in the background with nothing connected, or when you tap **Stop** in the notification.

The emulator has no HID device profile, so test on a real phone.

### Over Wi-Fi or Tailscale (Mote Helper)

1. Install Mote Helper on the Mac (`site/install.sh`) and grant Accessibility.
2. In Mote: first-run **Use Wi-Fi with Mote Helper** (no Bluetooth permission needed), or device pill > **Add Mac over Wi-Fi**.
3. On the Mac: Mote Helper menu > **Pair new device**. Pick the Mac in the nearby list and type the 6-digit code,
   or scan the QR with the phone camera and tap **Open in the Mote app** on the page that opens (a `mote://pair` link).
4. Tailscale: on the Mac turn on **Allow Tailscale connections** (menu). If you paired on the LAN while it was on, the
   phone already knows the tailnet address. Otherwise use device pill > **Enter address** with the MagicDNS name or `100.x` IP.

The app reconnects to the selected helper while it is in the foreground (pings every 20 s, backoff up to 8 s) and
closes the socket 30 s after it leaves the screen. Connect order per Mac: the address NSD just found, the cached IP,
`<name>.local`, then the tailnet / typed address. Tokens are imported into the Android Keystore as HMAC keys
(AES-GCM wrapped fallback). Long-press a Mac in the picker to forget it.

## Layout

```
app/src/main/java/dev/mote/remote/
  hid/       HidDescriptor (composite descriptor, report builders), Keys (usages, US typing), Hid (registration, connection, ordered report pipe), Bt (adapter/permission state)
  net/       Transport (+ BtHidTransport), Remote (UI entry point), Proto (codec, HMAC), WsSession / WsTransport (OkHttp),
             Hosts (saved hosts, picker reducer, fallback order, links), Net (selection, persistence), Discovery (NSD), Vault (Keystore)
  service/   HidService: connectedDevice foreground service + notification
  data/      Prefs (DataStore), Macro model + JSON + runner
  ui/        App shell, Media / Mouse / Keys / Macros screens, haptics, motion tokens, theme (Silver / Titanium)
```

## Behaviour notes

- **Scroll direction** assumes macOS natural scrolling (the default). Two-finger scroll follows the content. The scroll lever and its chevrons move toward the top or bottom of the document.
- **Text typing** over Bluetooth uses the US layout; characters it can't type are skipped, with a toast. Over Mote Helper it sends Unicode text (any layout, emoji).
- **fn** can't be sent over generic HID.
- **Brightness** over Bluetooth uses Consumer 0x6F/0x70 (probably works on built-in displays, not verified). Over Mote Helper it uses the helper's path (NX key, DisplayServices fallback).
- **Sticky modifiers** over Mote Helper apply to keys, not to clicks (the protocol's `button` has no modifiers).
- **Pointer speed** over Mote Helper maps touchpad dp to Mac points 1:1 (`WsTransport.POINT_GAIN`); tune on a device.
- **Animations** follow the system "Remove animations" setting.

## Not in this MVP

- Click-at-point on Bluetooth hosts (helper hosts only).
- In-app QR scanning (use the phone camera + "Open in the Mote app"), TLS pinning on the LAN, now-playing.
- Multi-step macro editing in the UI. Steps can be text/delay in JSON, but the editor records only one chord.
- Landscape layouts (the app is portrait-locked).
- 3-finger gestures, tap-and-hold drag.
- Spaces edge auto-repeat.
- Non-US layouts.
- Settings for haptics strength and pointer speed.
- Quick Settings tile and widget.
