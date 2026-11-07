#!/usr/bin/env bash
# Mote Helper installer for macOS.
#   curl -fsSL https://tusharpandey13.github.io/mote/install.sh | bash
#
# What it does (nothing else, no sudo):
#   1. checks this is macOS on arm64 or x86_64
#   2. downloads the latest MoteHelper-macos-<arch>.zip and its .sha256 from GitHub Releases
#   3. verifies the SHA-256
#   4. installs to ~/Applications/MoteHelper.app (replacing an older copy)
#   5. removes the quarantine flag from that app only
#   6. if the old copy had a different code signature (e.g. an ad-hoc build), resets Mote Helper's
#      stale Accessibility / Screen Recording entries once (tccutil, this app only), so they don't block
#   7. opens it: the Setup window lists each permission with a button to the right Settings pane
# Source: https://github.com/tusharpandey13/mote (site/install.sh)
set -euo pipefail

REPO="tusharpandey13/mote"
DEST_DIR="${MOTE_INSTALL_DIR:-$HOME/Applications}"
APP="$DEST_DIR/MoteHelper.app"

say() { printf '\033[1m==>\033[0m %s\n' "$*"; }
die() { printf '\033[31merror:\033[0m %s\n' "$*" >&2; exit 1; }

[ "$(uname -s)" = "Darwin" ] || die "Mote Helper is a macOS app. On other systems use the Android app or the web remote."
case "$(uname -m)" in
  arm64) ARCH=arm64 ;;
  x86_64) ARCH=x86_64 ;;
  *) die "unsupported CPU: $(uname -m)" ;;
esac
command -v curl >/dev/null || die "curl is required"
command -v shasum >/dev/null || die "shasum is required"
command -v ditto >/dev/null || die "ditto is required"

ASSET="MoteHelper-macos-$ARCH.zip"
# MOTE_RELEASE_URL overrides the release download base (tests: a local fixture server).
# Plain http is only allowed for loopback; anything else must be https.
BASE_URL="${MOTE_RELEASE_URL:-https://github.com/$REPO/releases/latest/download}"
URL="$BASE_URL/$ASSET"
PROTO='=https'
case "$BASE_URL" in *@*) ;; http://127.0.0.1[:/]*|http://localhost[:/]*) PROTO='=http' ;; esac

say "This will:"
echo "    download  $URL"
echo "    verify    its SHA-256 against $ASSET.sha256 from the same release"
echo "    install   $APP"
echo "    clear the quarantine flag on that app only, then open its Setup window"
echo

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

say "Downloading $ASSET"
curl -fL --proto "$PROTO" --tlsv1.2 --progress-bar -o "$TMP/$ASSET" "$URL"
curl -fsSL --proto "$PROTO" --tlsv1.2 -o "$TMP/$ASSET.sha256" "$URL.sha256"

say "Verifying SHA-256"
EXPECTED="$(awk '{print $1; exit}' "$TMP/$ASSET.sha256")"
ACTUAL="$(shasum -a 256 "$TMP/$ASSET" | awk '{print $1}')"
[ -n "$EXPECTED" ] && [ "$EXPECTED" = "$ACTUAL" ] || die "checksum mismatch (expected $EXPECTED, got $ACTUAL). Not installing."
echo "    ok $ACTUAL"

say "Unpacking"
ditto -x -k "$TMP/$ASSET" "$TMP/x"
[ -d "$TMP/x/MoteHelper.app" ] || die "archive doesn't contain MoteHelper.app"

if pgrep -x MoteHelper >/dev/null 2>&1; then
  say "Quitting the running Mote Helper"
  pkill -x MoteHelper || true
  sleep 1
fi

# macOS keys permission grants to the code signature ("designated requirement"). Ad-hoc builds get
# a new one every version, which leaves a stale Accessibility switch that looks on but does nothing.
requirement() { codesign -d -r- "$1" 2>&1 | sed -n 's/^[# ]*designated => //p'; }
OLD_REQ=""
[ -d "$APP" ] && OLD_REQ="$(requirement "$APP" || true)"
NEW_REQ="$(requirement "$TMP/x/MoteHelper.app" || true)"

say "Installing to $APP"
mkdir -p "$DEST_DIR"
rm -rf "$APP"
ditto "$TMP/x/MoteHelper.app" "$APP"
xattr -dr com.apple.quarantine "$APP" 2>/dev/null || true

if [ -n "$OLD_REQ" ] && [ -n "$NEW_REQ" ] && [ "$OLD_REQ" != "$NEW_REQ" ]; then
  say "The new version is signed differently: clearing Mote Helper's old permission entries"
  echo "    (only Mote Helper's; grant Accessibility again in the Setup window)"
  tccutil reset Accessibility dev.mote.helper >/dev/null 2>&1 || true
  tccutil reset ScreenCapture dev.mote.helper >/dev/null 2>&1 || true
fi

say "Opening Mote Helper Setup"
open "$APP" --args --setup

cat <<EOF

Done. Follow the Setup window (allow Accessibility; Bluetooth is recommended), then open Mote on
your phone. Reopen it any time from the menu-bar icon: Setup…
Uninstall: quit it from the menu, then  rm -rf "$APP"
EOF
