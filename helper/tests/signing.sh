#!/usr/bin/env bash
# Tests sign-identity.sh and notarize.sh with stubbed security/codesign/xcrun on PATH (no keychain, no network).
set -uo pipefail
HERE="$(cd "$(dirname "$0")/.." && pwd)"
T="$(mktemp -d)"; trap 'rm -rf "$T"' EXIT
mkdir -p "$T/bin"; fail=0
ok() { if [ "$2" = "$3" ]; then echo "ok   $1"; else echo "FAIL $1: got '$2' want '$3'"; fail=1; fi; }

DEVLINE='  1) AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA "Developer ID Application: Tushar (ABCDE12345)"'
cat > "$T/bin/security" <<STUB
#!/bin/sh
case "\$1" in
  find-identity) [ -n "\$STUB_DEVID" ] && echo '$DEVLINE'; [ -n "\$STUB_APPLE" ] && echo '  3) DDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDD "Apple Development: t@x.y (FGHIJ67890)"'; echo '  2) BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB "Mac Developer: x (ZZZZZZZZZZ)"'; echo '     2 valid identities found' ;;
  find-certificate) [ -n "\$STUB_LOCAL" ] && echo 'SHA-1 hash: CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC' ;;
esac
exit 0
STUB
chmod +x "$T/bin/security"
export PATH="$T/bin:$PATH" MOTE_SIGNING_DIR="$T/sd" MOTE_SIGNING_KEYCHAIN="$T/none.keychain"
pick() { env -u MOTE_SIGN_IDENTITY -u MOTE_ADHOC -u CI "$@" "$HERE/sign-identity.sh"; }

ok "env identity wins"        "$(pick STUB_DEVID=1 MOTE_SIGN_IDENTITY=Foo)" "env Foo"
ok "developer id"             "$(pick STUB_DEVID=1 STUB_LOCAL=1)" "developer-id AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
ok "apple development beats local" "$(pick STUB_APPLE=1 STUB_LOCAL=1)" "apple-development DDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDD"
ok "developer id beats apple dev" "$(pick STUB_APPLE=1 STUB_DEVID=1)" "developer-id AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
ok "local when no dev id"     "$(pick STUB_LOCAL=1)" "local CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC"
ok "adhoc when MOTE_ADHOC"    "$(pick STUB_DEVID=1 MOTE_ADHOC=1)" "adhoc -"
ok "adhoc on CI"              "$(pick STUB_DEVID=1 CI=1)" "adhoc -"
# Last branch: nothing in the keychain and signing.sh cannot create one (openssl stubbed to fail).
printf '#!/bin/sh\nexit 1\n' > "$T/bin/openssl"; chmod +x "$T/bin/openssl"
ok "adhoc fallback"           "$(pick OPENSSL=x)" "adhoc -"

# notarize.sh
mkdir -p "$T/MoteHelper.app"
printf '#!/bin/sh\nfor a; do :; done; : > "$a"\n' > "$T/bin/ditto"; chmod +x "$T/bin/ditto"
cat > "$T/bin/codesign" <<'STUB'
#!/bin/sh
echo "Authority=$STUB_AUTH" >&2; echo "TeamIdentifier=$STUB_TEAM" >&2
STUB
cat > "$T/bin/xcrun" <<'STUB'
#!/bin/sh
echo "xcrun $*" >> "$STUB_LOG"
[ "$2" = history ] && [ -n "$STUB_NOPROFILE" ] && exit 1
exit 0
STUB
chmod +x "$T/bin/codesign" "$T/bin/xcrun"
export STUB_LOG="$T/log"
n() { env "$@" "$HERE/notarize.sh" "$T/MoteHelper.app" "$T/out.zip" 2>&1; echo "rc=$?"; }
out="$(n STUB_AUTH='Developer ID Application: T (ABCDE12345)' STUB_TEAM=ABCDE12345 STUB_NOPROFILE=1)"
echo "$out" | grep -q 'store-credentials mote-notary' && echo "$out" | grep -q 'rc=1'; ok "missing profile fails with store-credentials hint" "$?" 0
out="$(n STUB_AUTH='Mote Helper Local' STUB_TEAM='not set')"
echo "$out" | grep -q 'not signed with a Developer ID' && echo "$out" | grep -q 'rc=1'; ok "non-Developer-ID refused" "$?" 0
: > "$STUB_LOG"
out="$(n STUB_AUTH='Developer ID Application: T (ABCDE12345)' STUB_TEAM=ABCDE12345)"
echo "$out" | grep -q 'rc=0'; ok "happy path rc" "$?" 0
grep -q 'notarytool submit .*--keychain-profile mote-notary --wait' "$STUB_LOG" && grep -q 'stapler staple' "$STUB_LOG"; ok "submit then staple" "$?" 0
exit $fail
