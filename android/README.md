# Mote for Android

The phone becomes a Bluetooth HID device (keyboard + mouse + consumer control) for a Mac or any
Bluetooth host. No software is needed on the host. Architecture: `docs/research/tech.md`. Design: `docs/design/design-language.md`.

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
./gradlew :app:testDebugUnitTest :app:lintRelease   # encoder unit tests + lint (0 errors)
./gradlew :app:assembleDebug                        # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleRelease                      # R8-minified, signed if a keystore is configured
```

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

## Layout

```
app/src/main/java/dev/mote/remote/
  hid/       HidDescriptor (composite descriptor, report builders), Keys (usages, US typing), Hid (registration, connection, ordered report pipe)
  service/   HidService: connectedDevice foreground service + notification
  data/      Prefs (DataStore), Macro model + JSON + runner
  ui/        App shell, Media / Mouse / Keys / Macros screens, haptics, motion tokens, theme (Silver / Titanium)
```

## Behaviour notes

- **Scroll direction** assumes macOS natural scrolling (the default). Two-finger scroll follows the content. The scroll lever and its chevrons move toward the top or bottom of the document.
- **Text typing** uses the US layout. Characters it can't type are skipped, with a toast.
- **fn** can't be sent over generic HID.
- **Brightness** uses Consumer 0x6F/0x70. It probably works on built-in displays, but this isn't verified.
- **Animations** follow the system "Remove animations" setting.

## Not in this MVP

- Click-at-point macros.
- Multi-step macro editing in the UI. Steps can be text/delay in JSON, but the editor records only one chord.
- Landscape layouts (the app is portrait-locked).
- 3-finger gestures, tap-and-hold drag.
- Spaces edge auto-repeat.
- Non-US layouts.
- Settings for haptics strength and pointer speed.
- Quick Settings tile and widget.
