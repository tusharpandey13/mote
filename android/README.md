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
- `labs/context/NpNotifPolicyTest`, `NpNotifIntegrationTest`: when the now-playing media notification shows (pure plan) and its inputs against the dry-run helper with a fake reader (docs/labs/context.md).
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

## Tests

| Suite | Where | Runs on | Command |
|---|---|---|---|
| Unit (encoder, gate reducer) | `app/src/test/` | JVM | `./gradlew :app:testDebugUnitTest` |
| End-to-end UI (default) | `app/src/androidTest/java/dev/mote/remote/e2e/` | emulator or phone | `./gradlew :app:connectedDebugAndroidTest` |
| Process death | `scripts/process-death-test.sh` | emulator or phone | see below |
| Physical HID (opt-in) | `app/src/androidTest/hid/` | real phone + bonded Mac | see below |

Set `ANDROID_SERIAL=<serial>` when more than one device is attached; otherwise Gradle runs on all of them.

### End-to-end UI suite

Compose UI tests plus UiAutomator for system dialogs, run through the AndroidX Test Orchestrator with
`clearPackageData`, so every test starts in a fresh process with no DataStore and no runtime permissions.

```sh
cd android
ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest
# one class: -Pandroid.testInstrumentationRunnerArguments.class=dev.mote.remote.e2e.MediaTest
```

Bluetooth HID doesn't exist on the emulator, so `MoteRule` installs fakes through `hid/TestSeam.kt`
(both hooks are null in production):

- `TestSeam.transport` = `FakeTransport`, a `net.Transport` that `Net` uses in place of Bluetooth HID.
  Every UI action (`Remote.*`) lands there as a `Call`. With `Caps.HID` it stands in for Bluetooth HID, with
  `Caps.HELPER` for Mote Helper. The Media, Mouse, Keys and Macros suites run once per transport. `Hid`
  skips the real profile registration, but host selection (`Hid.select`) still works.
- `TestSeam.adapter` = a `MutableStateFlow<TestSeam.Adapter>`: adapter present / enabled / bonded hosts.
  Updating it re-emits `Bt.observe`, like the system broadcasts do. Runtime permissions stay real.

Tests assert the exact transport calls (key names, modifier bits, consumer usages, wheel/pan).
The HID byte encoding is covered by `EncoderTest`, and the helper wire protocol by `ProtoTest` and
`HelperIntegrationTest` (JVM). Timing tests (brightness auto-repeat, long-press, the SCROLL / VOLUME
sliders, macro confirm) pause the Compose clock (`mainClock.autoAdvance = false`) and advance it
explicitly, so they're deterministic. Hold-to-seek and the Bluetooth Spaces step queue (300 ms per step) use
real time (`SystemClock`, the main dispatcher), so those tests wait in real time.

On a busy Mac the emulator can crash with a host GL error. If that happens, boot it with `-gpu guest`.

### Process death

An instrumented test can't kill its own process (`am kill` would take the instrumentation down too), so
this one is a host-driven black-box script. It opens Keys, types unsent text, goes Home, runs
`adb shell am kill`, relaunches, and checks that the mode and the text are restored. It uses the real
adapter, so Bluetooth must be on (the script runs `cmd bluetooth_manager enable`).

```sh
./gradlew :app:installDebug
ANDROID_SERIAL=emulator-5554 scripts/process-death-test.sh
```

### Physical HID suite

`@PhysicalHid` tests use the real `BluetoothHidDevice` with no fakes. They assert that `registerApp`
succeeds (phase `READY`) and that the HID connection reaches `STATE_CONNECTED` with a bonded host.
The default run excludes them. Before you run them:

1. Use a real phone with Bluetooth on, and quit other apps that register as a HID device.
2. Pair the Mac once through the app (**Pair new device**). Keep the Mac awake and in range.

```sh
ANDROID_SERIAL=<phone-serial> ./gradlew :app:connectedDebugAndroidTest -PmoteHid
# a specific host: add -Pandroid.testInstrumentationRunnerArguments.host=AA:BB:CC:DD:EE:FF
```

If no host is given, the test uses the first bonded computer. If none is bonded, it's skipped (assumption failure).

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
