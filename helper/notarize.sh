#!/usr/bin/env bash
# Notarize + staple a Developer-ID-signed MoteHelper.app, then (re)write the release zip.
#   helper/notarize.sh <MoteHelper.app> <out.zip>   (build.sh MOTE_RELEASE=1 calls this)
#   helper/notarize.sh <MoteHelper-*.zip>           (unzips, notarizes, re-zips in place)
# One-time: xcrun notarytool store-credentials mote-notary --apple-id <id> --team-id <TEAM> --password <app-specific-password>
set -euo pipefail
PROFILE="${MOTE_NOTARY_PROFILE:-mote-notary}"
IN="${1:?usage: notarize.sh <MoteHelper.app|zip> [out.zip]}"

TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
if [ -d "$IN" ]; then APP="$IN"; OUTZIP="${2:?pass the output zip path}"
else
  OUTZIP="${2:-$IN}"
  ditto -x -k "$IN" "$TMP/x"; APP="$TMP/x/MoteHelper.app"
fi
[ -d "$APP" ] || { echo "!! no app at $APP" >&2; exit 1; }

# Refuse anything not signed by Developer ID with a Team ID (the Keychain needs teamid:<TEAM>).
INFO="$(codesign -dvv "$APP" 2>&1 || true)"
echo "$INFO" | grep -q '^Authority=Developer ID Application:' && echo "$INFO" | grep -q '^TeamIdentifier=[A-Z0-9]\{10\}$' || {
  echo "!! $APP is not signed with a Developer ID Application identity; refusing to package a release. See docs/signing.md." >&2; exit 1; }

if ! xcrun notarytool history --keychain-profile "$PROFILE" >/dev/null 2>&1; then
  cat >&2 <<MSG
!! notarytool keychain profile "$PROFILE" not found (or not usable). Store it once:
   xcrun notarytool store-credentials $PROFILE --apple-id <apple-id> --team-id <TEAMID> --password <app-specific-password>
MSG
  exit 1
fi

ditto -c -k --keepParent "$APP" "$TMP/submit.zip"
echo "==> notarytool submit (waits)"
xcrun notarytool submit "$TMP/submit.zip" --keychain-profile "$PROFILE" --wait
xcrun stapler staple "$APP"
rm -f "$OUTZIP"
ditto -c -k --keepParent "$APP" "$OUTZIP"
( cd "$(dirname "$OUTZIP")" && shasum -a 256 "$(basename "$OUTZIP")" > "$(basename "$OUTZIP").sha256" )
echo "==> $OUTZIP (notarized, stapled)"
