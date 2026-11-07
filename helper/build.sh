#!/usr/bin/env bash
# Builds build/MoteHelper.app and build/MoteHelper-macos-<arch>.zip + .sha256.
# Needs only the Swift toolchain (Command Line Tools), no Xcode.
#   ./build.sh            # arm64 (and x86_64 too if the SDK can cross-compile it)
#   ARCHS=arm64 ./build.sh
# Signing: with the stable local identity "Mote Helper Local" (helper/signing.sh creates it once
# in the login keychain), so every rebuild keeps the same designated requirement and macOS keeps
# the Accessibility / Bluetooth / Screen Recording grants and the Keychain "Always Allow".
#   MOTE_SIGN_IDENTITY=<sha1|name>  sign with another identity
#   MOTE_ADHOC=1                    ad-hoc (default on CI, where there is no identity): every build
#                                   then has a new signature and permissions must be granted again.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/.." && pwd)"
OUT="$HERE/build"
VERSION="${VERSION:-$(git -C "$REPO" describe --tags --always 2>/dev/null | sed 's/^v//' || echo 0.1.0)}"
BUILD_NUM="$(git -C "$REPO" rev-list --count HEAD 2>/dev/null || echo 1)"
ARCHS="${ARCHS:-arm64 x86_64}"

SIGN="${MOTE_SIGN_IDENTITY:-}"
if [ -z "$SIGN" ] && [ -z "${MOTE_ADHOC:-}" ] && [ -z "${CI:-}" ]; then
  SIGN="$("$HERE/signing.sh" ensure)" || { echo "!! no signing identity; falling back to ad-hoc"; SIGN=""; }
fi
[ -n "$SIGN" ] || SIGN=-

mkdir -p "$OUT"
for arch in $ARCHS; do
  echo "==> swift build ($arch)"
  if ! swift build --package-path "$HERE" -c release --triple "$arch-apple-macosx13.0" --scratch-path "$HERE/.build/$arch"; then
    if [ "$arch" = arm64 ]; then exit 1; fi
    echo "!! $arch build failed; skipping it"; continue
  fi
  BIN="$HERE/.build/$arch/$arch-apple-macosx/release/MoteHelper"

  APP="$OUT/$arch/MoteHelper.app"
  echo "==> assembling $APP"
  rm -rf "$APP"
  mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources/web/design/fonts"
  cp "$BIN" "$APP/Contents/MacOS/MoteHelper"
  sed -e "s/__VERSION__/$VERSION/" -e "s/__BUILD__/$BUILD_NUM/" "$HERE/Info.plist" > "$APP/Contents/Info.plist"
  # One source for the web remote: site/remote, plus the shared design assets it loads from ../design/.
  cp -R "$REPO/site/remote/." "$APP/Contents/Resources/web/"
  cp "$REPO/design/mote-v1/mote.css" "$REPO/design/mote-v1/icons.js" "$APP/Contents/Resources/web/design/"
  cp "$REPO/design/mote-v1/fonts/"*.woff2 "$REPO/design/mote-v1/fonts/OFL.txt" "$APP/Contents/Resources/web/design/fonts/"

  # Labs (preview): the now-playing reader, run by /usr/bin/perl (see helper/LabsNowPlaying/).
  mkdir -p "$APP/Contents/Resources/labs"
  clang -dynamiclib -fobjc-arc -O2 -arch "$arch" -mmacosx-version-min=13.0 -framework Foundation -framework AppKit -framework ImageIO \
    -o "$APP/Contents/Resources/labs/MoteNowPlaying.dylib" "$HERE/LabsNowPlaying/MoteNowPlaying.m"
  cp "$HERE/LabsNowPlaying/mote-nowplaying.pl" "$APP/Contents/Resources/labs/"
  codesign --force --sign "$SIGN" "$APP/Contents/Resources/labs/MoteNowPlaying.dylib"

  echo "==> codesign ($([ "$SIGN" = - ] && echo ad-hoc || echo "Mote Helper Local / $SIGN"))"
  codesign --force --deep --sign "$SIGN" --options runtime --entitlements "$HERE/MoteHelper.entitlements" --identifier dev.mote.helper "$APP"
  codesign --verify --strict "$APP"
  codesign -d -r- "$APP" 2>&1 | sed -n 's/^[# ]*designated => /    requirement: /p'

  ZIP="$OUT/MoteHelper-macos-$arch.zip"
  rm -f "$ZIP"
  (cd "$OUT/$arch" && ditto -c -k --keepParent MoteHelper.app "$ZIP")
  (cd "$OUT" && shasum -a 256 "$(basename "$ZIP")" > "$(basename "$ZIP").sha256")
  echo "==> $ZIP"; cat "$ZIP.sha256"
done

# Convenience copy of the native build at build/MoteHelper.app.
NATIVE="$(uname -m)"
if [ -d "$OUT/$NATIVE/MoteHelper.app" ]; then
  rm -rf "$OUT/MoteHelper.app"; cp -R "$OUT/$NATIVE/MoteHelper.app" "$OUT/MoteHelper.app"
fi
