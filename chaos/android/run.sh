#!/usr/bin/env bash
# Mote Android chaos suite. See README.md.
#   chaos/android/run.sh --serial emulator-5556 --seed 42 --budget 20m [--apk PATH] [--no-build]
#                        [--only monkey,lifecycle,resource,bluetooth,storage]
set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
SERIAL="" SEED=1 BUDGET=20m APK="" BUILD=1 ONLY="monkey,lifecycle,resource,bluetooth,storage" ALLOW_PHYSICAL=0

while [ $# -gt 0 ]; do
  case $1 in
    --serial) SERIAL=$2; shift 2 ;;
    --seed) SEED=$2; shift 2 ;;
    --budget) BUDGET=$2; shift 2 ;;
    --apk) APK=$2; BUILD=0; shift 2 ;;
    --no-build) BUILD=0; shift ;;
    --only) ONLY=$2; shift 2 ;;
    --allow-physical) ALLOW_PHYSICAL=1; shift ;;
    -h|--help) sed -n 2,4p "$0"; exit 0 ;;
    *) echo "unknown arg $1" >&2; exit 2 ;;
  esac
done
[ -n "$SERIAL" ] || { echo "--serial is required" >&2; exit 2; }
case $SERIAL in
  emulator-*) ;;
  *) [ "$ALLOW_PHYSICAL" = 1 ] || { echo "refusing non-emulator serial $SERIAL (use physical.sh)" >&2; exit 2; } ;;
esac

case $BUDGET in *m) BUDGET_S=$(( ${BUDGET%m} * 60 )) ;; *h) BUDGET_S=$(( ${BUDGET%h} * 3600 )) ;; *s) BUDGET_S=${BUDGET%s} ;; *) BUDGET_S=$BUDGET ;; esac
DEADLINE=$(( $(date +%s) + BUDGET_S ))
OUT="$HERE/out/$(date +%Y%m%d-%H%M%S)-s$SEED"
mkdir -p "$OUT/logs" "$OUT/shots"
: > "$OUT/results.tsv"
echo "$SEED" > "$OUT/.rng" # every rnd() draw is reproducible for a given seed
export SERIAL OUT DEADLINE

# shellcheck source=lib.sh
. "$HERE/lib.sh"
for f in "$HERE"/scenarios/*.sh; do . "$f"; done

a get-state 2>/dev/null | grep -q device || { echo "$SERIAL not online" >&2; exit 2; }
a wait-for-device
until [ "$(sh_ getprop sys.boot_completed | tr -d '\r')" = 1 ]; do sleep 2; done
# root adbd on emulators so ANR traces in /data/anr can be pulled (no-op on user builds)
case $SERIAL in emulator-*) a root >/dev/null 2>&1; sleep 2; a wait-for-device ;; esac

if [ "$BUILD" = 1 ]; then
  log "building debug APK"
  [ -n "${JAVA_HOME:-}" ] || JAVA_HOME=$(/usr/libexec/java_home -v 17 2>/dev/null) && export JAVA_HOME
  (cd "$ROOT/android" && ./gradlew -q :app:assembleDebug) || { echo "build failed" >&2; exit 1; }
  APK="$ROOT/android/app/build/outputs/apk/debug/app-debug.apk"
fi
if [ -n "$APK" ]; then
  log "installing $APK on $SERIAL"
  a install -r -g "$APK" >/dev/null || { echo "install failed" >&2; exit 1; }
fi

log "seed=$SEED budget=${BUDGET_S}s serial=$SERIAL out=$OUT"
sh_ settings put global window_animation_scale 0.5 >/dev/null
sh_ svc power stayon true
sh_ input keyevent KEYCODE_WAKEUP; sh_ wm dismiss-keyguard >/dev/null 2>&1
trap 'restore_device; sh_ svc power stayon false' EXIT
# Fresh app state: earlier runs (or monkey tapping "Use Wi-Fi") persist a Wi-Fi choice that hides the gate.
sh_ pm clear "$PKG" >/dev/null
restore_device

# Fixed order; monkey takes the largest share so it goes last and absorbs leftover budget.
for s in storage lifecycle resource bluetooth monkey; do
  case ",$ONLY," in *",$s,"*) ;; *) continue ;; esac
  if over; then record "$s" SKIP 0 0 "budget exhausted"; continue; fi
  log "=== scenario $s ($(left)s left)"
  if [ "$s" = monkey ]; then scenario_monkey "$SEED"; else "scenario_$s"; fi
  restore_device
done

# ---- summary ----
{
  echo "# Mote chaos run: seed $SEED, budget $BUDGET, $SERIAL"
  echo
  echo "| scenario | status | crashes | ANRs | notes |"
  echo "|---|---|---|---|---|"
  awk -F'\t' '{printf "| %s | %s | %s | %s | %s |\n", $1, $2, $3, $4, $5}' "$OUT/results.tsv"
  echo
  awk -F'\t' '{n[$2]++} END {printf "PASS %d, FAIL %d, SKIP %d\n", n["PASS"], n["FAIL"], n["SKIP"]}' "$OUT/results.tsv"
  ls "$OUT"/logs/*.crash.txt >/dev/null 2>&1 && { echo; echo "Crash excerpts: $OUT/logs/*.crash.txt"; }
} > "$OUT/summary.md"
cat "$OUT/summary.md"
! grep -q $'\tFAIL\t' "$OUT/results.tsv"
