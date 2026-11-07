#!/usr/bin/env bash
# Stable local code-signing identity "Mote Helper Local" (self-signed, created once).
#
# macOS binds Accessibility / Bluetooth / Screen Recording / Automation grants and Keychain
# "Always Allow" to the app's designated requirement. Ad-hoc signatures change on every build, so
# every rebuild loses them. Signing with one fixed certificate keeps the requirement
# (identifier "dev.mote.helper" and certificate leaf = H"<sha1>") identical across rebuilds.
#
# The identity goes into the login keychain. Letting codesign use its key needs your login password
# once: `signing.sh ensure` asks for it in the terminal (security set-key-partition-list); if you
# skip that, the first build shows "codesign wants to sign using key Mote Helper Local": enter the
# password and click Always Allow. Nothing asks again after that. A copy of the identity is kept in ~/.config/mote/mote-helper-local.p12
# (next to the Android keystore; password below): import it on another Mac, or after a reinstall,
# to keep the same signature. A new certificate means re-granting every permission once more.
#
#   helper/signing.sh ensure     # create (or re-import the backup) if missing; print the SHA-1 to sign with
#   helper/signing.sh show       # print the SHA-1, or exit 1 if there is none
#   helper/signing.sh allow      # (again) let codesign use the key: asks for the login password
set -euo pipefail

NAME="Mote Helper Local"
DIR="${MOTE_SIGNING_DIR:-$HOME/.config/mote}"
KC="${MOTE_SIGNING_KEYCHAIN:-$HOME/Library/Keychains/login.keychain-db}"
P12="$DIR/mote-helper-local.p12"
# Only protects the .p12 backup at rest (it never leaves this Mac); not a secret.
PW="mote-helper-local"
OPENSSL=/usr/bin/openssl   # LibreSSL writes PKCS#12 that `security import` reads

sha1() {
  security find-certificate -c "$NAME" -Z "$KC" 2>/dev/null | awk '/^SHA-1 hash:/ {print $3; exit}'
}

create() {
  mkdir -p "$DIR"; chmod 700 "$DIR"
  local tmp; tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' RETURN
  cat > "$tmp/cert.cnf" <<EOF
[req]
distinguished_name = dn
prompt = no
x509_extensions = ext
[dn]
CN = $NAME
[ext]
basicConstraints = critical, CA:false
keyUsage = critical, digitalSignature
extendedKeyUsage = critical, codeSigning
subjectKeyIdentifier = hash
EOF
  "$OPENSSL" req -x509 -newkey rsa:2048 -nodes -days 7300 -sha256 -config "$tmp/cert.cnf" \
    -keyout "$tmp/key.pem" -out "$tmp/cert.pem" 2>/dev/null
  "$OPENSSL" pkcs12 -export -inkey "$tmp/key.pem" -in "$tmp/cert.pem" -name "$NAME" -passout "pass:$PW" -out "$P12"
  chmod 600 "$P12"
  import
  echo "created signing identity \"$NAME\" (backup: $P12)" >&2
}

# -T + partition list: codesign may use the private key without a dialog on every build.
import() {
  security import "$P12" -k "$KC" -P "$PW" -T /usr/bin/codesign >/dev/null
  if [ -t 0 ]; then
    echo "Allow codesign to use the \"$NAME\" key (once): enter your login (keychain) password." >&2
    security set-key-partition-list -S apple-tool:,apple:,codesign: -s -l "$NAME" "$KC" >/dev/null \
      || echo "skipped: the first build will ask instead (Always Allow)." >&2
  fi
}

case "${1:-ensure}" in
  ensure)
    if [ -z "$(sha1)" ]; then if [ -f "$P12" ]; then import; else create; fi; fi
    sha1 ;;
  show) h="$(sha1)"; [ -n "$h" ] || exit 1; echo "$h" ;;
  allow) security set-key-partition-list -S apple-tool:,apple:,codesign: -s -l "$NAME" "$KC" >/dev/null ;;   # re-run the password step
  *) echo "usage: $0 ensure|show|allow" >&2; exit 2 ;;
esac
