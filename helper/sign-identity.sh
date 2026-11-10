#!/usr/bin/env bash
# Picks the code-signing identity for build.sh. Prints "<kind> <identity>" (kind: env | developer-id | apple-development | local | adhoc).
# Order: MOTE_SIGN_IDENTITY, first "Developer ID Application: ... (TEAMID)", first "Apple Development: ... (TEAMID)"
# (free Xcode personal team; has a Team ID, so the Keychain reads silently across local rebuilds),
# "Mote Helper Local", ad-hoc. With MOTE_RELEASE=1 Apple Development is skipped. MOTE_ADHOC=1 or CI skips every keychain lookup.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"

if [ -n "${MOTE_SIGN_IDENTITY:-}" ]; then
  case "$MOTE_SIGN_IDENTITY" in
    "Developer ID Application:"*) echo "developer-id $MOTE_SIGN_IDENTITY" ;;
    "Apple Development:"*) echo "apple-development $MOTE_SIGN_IDENTITY" ;;
    "Mote Helper Local") echo "local $("$HERE/signing.sh" show)" ;;
    -) echo "adhoc -" ;;
    *) echo "env $MOTE_SIGN_IDENTITY" ;;
  esac
  exit 0
fi
if [ -n "${MOTE_ADHOC:-}" ] || [ -n "${CI:-}" ]; then echo "adhoc -"; exit 0; fi

# `<n>) <sha1> "<Kind>: Name (TEAMID)"`
IDS="$(security find-identity -v -p codesigning 2>/dev/null || true)"
first() { printf '%s\n' "$IDS" | sed -n "s/^ *[0-9]*) \([0-9A-Fa-f]\{40\}\) \"$1: .* ([A-Z0-9]\{10\})\".*\$/\1/p" | head -1; }
DEV="$(first 'Developer ID Application')"
if [ -n "$DEV" ]; then echo "developer-id $DEV"; exit 0; fi
# A release (MOTE_RELEASE=1) never picks Apple Development (its terms forbid distribution): it falls through to "Mote Helper Local".
APPLE=""; [ -n "${MOTE_RELEASE:-}" ] || APPLE="$(first 'Apple Development')"
if [ -n "$APPLE" ]; then echo "apple-development $APPLE"; exit 0; fi

if LOCAL="$("$HERE/signing.sh" ensure 2>/dev/null)" && [ -n "$LOCAL" ]; then echo "local $LOCAL"; exit 0; fi
echo "adhoc -"
