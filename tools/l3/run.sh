#!/bin/bash
# L3 run, inside the user motetest:  tools/l3/run.sh <journey> [--zip PATH] [--no-install] [--out DIR]
# <journey>: install-helper | setup-mac | first-connect | reconnect | daily | mac-update | vault (see README.md).
# Walks the journey's L3 rows, checks `MoteHelper --diag` after each, writes docs/journeys/l3-<journey>-<date>.md.
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
JOURNEY="" ZIP="" INSTALL=1 OUT="$ROOT/docs/journeys"
while [ $# -gt 0 ]; do
  case "$1" in
    --zip) ZIP="$2"; shift 2 ;;
    --no-install) INSTALL=0; shift ;;
    --out) OUT="$2"; shift 2 ;;
    -*) echo "unknown option $1" >&2; exit 2 ;;
    *) JOURNEY="$1"; shift ;;
  esac
done
[ -n "$JOURNEY" ] || { sed -n '2,4p' "$0" >&2; exit 2; }
HELPER="${MOTE_HELPER:-$HOME/Applications/MoteHelper.app/Contents/MacOS/MoteHelper}"
INSTALL_SH="${MOTE_INSTALL_SH:-$ROOT/site/install.sh}"
EVENTS="${MOTE_EVENTS:-$HOME/Library/Application Support/dev.mote.helper/diag-events.jsonl}"
TMP="$(mktemp -d)"; SRV=""
trap '[ -n "$SRV" ] && kill "$SRV" 2>/dev/null; rm -rf "$TMP"' EXIT

ROWS="$TMP/rows.jsonl"
node "$HERE/l3.mjs" rows "$JOURNEY" > "$ROWS" || { echo "cannot read journey $JOURNEY" >&2; exit 2; }
[ -s "$ROWS" ] || { echo "journey $JOURNEY has no L3 rows"; exit 0; }
echo "Journey $JOURNEY: $(wc -l < "$ROWS" | tr -d ' ') L3 rows. Running as $(id -un)."

if [ "$INSTALL" = 1 ]; then
  if [ -n "$ZIP" ]; then
    # Serve the local zip on loopback; install.sh allows plain http only there.
    ARCH=x86_64; [ "$(uname -m)" = arm64 ] && ARCH=arm64
    mkdir -p "$TMP/rel"; cp "$ZIP" "$TMP/rel/MoteHelper-macos-$ARCH.zip"
    (cd "$TMP/rel" && shasum -a 256 "MoteHelper-macos-$ARCH.zip" > "MoteHelper-macos-$ARCH.zip.sha256")
    python3 -m http.server 8799 -b 127.0.0.1 -d "$TMP/rel" >/dev/null 2>&1 & SRV=$!
    sleep 1; export MOTE_RELEASE_URL=http://127.0.0.1:8799
    echo "Installing the local build $ZIP"
  else
    echo "Installing the published release (releases/latest)"
  fi
  bash "$INSTALL_SH" || { echo "install failed" >&2; exit 1; }
fi

IP="$(ipconfig getifaddr en0 2>/dev/null || ipconfig getifaddr en1 2>/dev/null || echo '<this Mac LAN IP>')"
echo
echo "Phone side: start the Android emulator (not automated) on this Mac's network. The emulator reaches this Mac"
echo "at its LAN IP, $IP (not 10.0.2.2). In Mote on the emulator, enter or pick that address; the pairing code is"
echo "in the helper's Setup window."
echo

: > "$TMP/results.tsv"
while IFS= read -r row <&3; do
  f() { printf '%s' "$row" | node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>process.stdout.write(String(JSON.parse(s)[process.argv[1]]??"")))' "$1"; }
  id="$(f id)"; detected="$(f detectedBy)"
  echo "=== $id"
  echo "Step:     $(f step)"
  echo "Expected: $(f expected)"
  echo "You see:  $(f userSees)"
  case "$(f step) $(f userSees)" in
    *[Dd]ialog*|*[Pp]rompt*|*Allow*|*Keychain*) echo "A system dialog may appear. Click Allow (or Always Allow), then press Enter."; read -r _ ;;
  esac
  printf 'Result? [Enter=pass, f=fail, n=note] '; read -r ans || ans=""
  note=""; case "$ans" in f*|F*|n*|N*) printf 'One line of detail: '; read -r note || note="" ;; esac
  status=pass; case "$ans" in f*|F*) status=fail ;; n*|N*) status=note ;; esac
  detail="$note"
  if [ -n "$detected" ]; then
    "$HELPER" --diag > "$TMP/diag.json" 2>/dev/null || echo '{}' > "$TMP/diag.json"
    while IFS=$'\t' read -r s t; do
      [ -n "$s" ] || continue
      echo "  diag $s: $t"; detail="$detail; $t"
      [ "$s" = fail ] && status=fail
      [ "$s" = note ] && [ "$status" = pass ] && status=note
    done < <(node "$HERE/l3.mjs" check "$TMP/diag.json" "$EVENTS" "$detected")
  fi
  echo "  => $status"
  printf '%s\t%s\t%s\n' "$id" "$status" "${detail#; }" >> "$TMP/results.tsv"
done 3< "$ROWS"

mkdir -p "$OUT"
REPORT="$OUT/l3-$JOURNEY-$(date +%Y-%m-%d).md"
node "$HERE/l3.mjs" report "$REPORT" "$JOURNEY" "$TMP/results.tsv"; rc=$?
echo "Report: $REPORT"
exit $rc
