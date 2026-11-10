#!/usr/bin/env bash
# Builds build/MoteHelper.app and build/MoteHelper-macos-<arch>.zip + .sha256.
# Needs only the Swift toolchain (Command Line Tools), no Xcode.
#   ./build.sh            # arm64 (and x86_64 too if the SDK can cross-compile it)
#   ARCHS=arm64 ./build.sh
# Signing identity (helper/sign-identity.sh), first match wins:
#   MOTE_SIGN_IDENTITY=<sha1|name>  explicit
#   Developer ID Application: ... (TEAMID) in the keychain  -> hardened runtime + timestamp; the only kind a release may use
#   Apple Development: ... (TEAMID) (free Xcode personal team) -> local builds; Team ID, so the Keychain stays silent on rebuilds
#   "Mote Helper Local" (helper/signing.sh)                 -> dev builds; Keychain prompts after every rebuild
#   ad-hoc (MOTE_ADHOC=1, or CI)
#   MOTE_RELEASE=1  require Developer ID, then notarize + staple (helper/notarize.sh) and zip. See docs/signing.md.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/.." && pwd)"
OUT="$HERE/build"
VERSION="${VERSION:-$(git -C "$REPO" describe --tags --always 2>/dev/null | sed 's/^v//' || echo 0.1.0)}"
BUILD_NUM="$(git -C "$REPO" rev-list --count HEAD 2>/dev/null || echo 1)"
ARCHS="${ARCHS:-arm64 x86_64}"

read -r KIND SIGN < <("$HERE/sign-identity.sh")
RELEASE="${MOTE_RELEASE:-}"
case "$KIND" in
  developer-id) OPTS=(--options runtime --timestamp) ;;
  *) OPTS=(--options runtime) ;;
esac
if [ "$KIND" != developer-id ]; then
  if [ -n "$RELEASE" ]; then
    echo "!! MOTE_RELEASE=1 needs a Developer ID Application identity (got: $KIND). See docs/signing.md." >&2; exit 1
  fi
  if [ "$KIND" = local ]; then
    echo "!! WARNING: signing with the self-signed 'Mote Helper Local' (dev build). Its Keychain partition is this exact build," >&2
    echo "!! so macOS will ask for your login password after EVERY rebuild. Releases need Developer ID (docs/signing.md)." >&2
  elif [ "$KIND" = apple-development ]; then
    echo "==> Apple Development identity (local build, not notarizable; releases need Developer ID)" >&2
  elif [ "$KIND" = adhoc ]; then
    echo "!! ad-hoc signature: permissions and Keychain access do not carry over between builds." >&2
  fi
fi

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
  codesign --force --sign "$SIGN" "${OPTS[@]}" "$APP/Contents/Resources/labs/MoteNowPlaying.dylib"

  echo "==> codesign ($KIND / $SIGN)"
  codesign --force --deep --sign "$SIGN" "${OPTS[@]}" --entitlements "$HERE/MoteHelper.entitlements" --identifier dev.mote.helper "$APP"
  codesign --verify --strict "$APP"
  codesign -d -r- "$APP" 2>&1 | sed -n 's/^[# ]*designated => /    requirement: /p'

  ZIP="$OUT/MoteHelper-macos-$arch.zip"
  rm -f "$ZIP"
  if [ -n "$RELEASE" ]; then "$HERE/notarize.sh" "$APP" "$ZIP"   # submit, staple, re-zip
  else (cd "$OUT/$arch" && ditto -c -k --keepParent MoteHelper.app "$ZIP"); fi
  (cd "$OUT" && shasum -a 256 "$(basename "$ZIP")" > "$(basename "$ZIP").sha256")
  echo "==> $ZIP"; cat "$ZIP.sha256"
done

# Convenience copy of the native build at build/MoteHelper.app.
NATIVE="$(uname -m)"
if [ -d "$OUT/$NATIVE/MoteHelper.app" ]; then
  rm -rf "$OUT/MoteHelper.app"; cp -R "$OUT/$NATIVE/MoteHelper.app" "$OUT/MoteHelper.app"
fi
