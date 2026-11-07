#!/usr/bin/env bash
# Mote Android chaos suite. See README.md; scoring rubric: ../RUBRIC.md.
#   chaos/android/run.sh --serial emulator-5556 --seed 42 --budget 20m [--apk PATH] [--no-build]
#                        [--only storage,lifecycle,resource,bluetooth,network,connect,monkey] [--allow-physical]
set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
ALL="storage,lifecycle,resource,bluetooth,network,connect,monkey"
SERIAL="" SEED=1 BUDGET=20m APK="" BUILD=1 ONLY=$ALL ALLOW_PHYSICAL=0

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

# ---- emulator guard ---------------------------------------------------------
# Real emulator = serial emulator-* AND the guest says so (ro.kernel.qemu / ro.boot.qemu = 1, or
# ro.hardware ranchu/goldfish). Nothing else runs on the device before this passes: a non-emulator
# serial is refused without any adb call; an emulator-* serial gets only read-only getprops.
IS_EMU=0
# adb server: only ever the user's default one (5037). A second server enumerates USB too and grabs
# the user's phone away from their own adb, so a non-default ANDROID_ADB_SERVER_PORT is refused.
case ${ANDROID_ADB_SERVER_PORT:-5037} in 5037) ;; *)
  echo "refusing: ANDROID_ADB_SERVER_PORT=$ANDROID_ADB_SERVER_PORT. The harness must use the default adb server; a private one claims USB devices (the user's phone)." >&2
  exit 2 ;; esac
case $SERIAL in
  emulator-*)
    # If this run is what starts the adb server, it must not end up holding a physical device.
    started=$(adb start-server 2>&1 | grep -c 'daemon started' || true)
    if [ "$started" -gt 0 ] && adb devices | awk 'NR > 1 && $2 == "device" && $1 !~ /^emulator-/ {f=1} END {exit !f}'; then
      echo "refusing: this run started the adb server and it attached a physical device; stop and let the user's adb own it" >&2
      exit 2
    fi
    props=$(adb -s "$SERIAL" shell 'getprop ro.kernel.qemu; getprop ro.boot.qemu; getprop ro.hardware' 2>/dev/null | tr -d '\r')
    printf '%s\n' "$props" | grep -qxE '1|ranchu|goldfish' && IS_EMU=1 ;;
esac
if [ "$IS_EMU" != 1 ]; then
  if [ "$ALLOW_PHYSICAL" != 1 ]; then
    echo "refusing $SERIAL: not an emulator (needs serial emulator-* and ro.kernel.qemu/ro.boot.qemu=1 or ro.hardware ranchu/goldfish). Physical devices: physical.sh" >&2
    exit 2
  fi
  cat >&2 <<EOF
################################################################################
#  PHYSICAL DEVICE: $SERIAL
#  HID input from this run REACHES THE PAIRED COMPUTER (keys, clicks, media).
#  Device settings WILL be toggled: Wi-Fi, Bluetooth, airplane mode, mobile data,
#  battery saver, Doze, font scale, density, rotation, night mode, locales.
#  They are restored to the run-start snapshot at the end (and on Ctrl-C).
################################################################################
EOF
fi
export IS_EMU

case $BUDGET in *m) BUDGET_S=$(( ${BUDGET%m} * 60 )) ;; *h) BUDGET_S=$(( ${BUDGET%h} * 3600 )) ;; *s) BUDGET_S=${BUDGET%s} ;; *) BUDGET_S=$BUDGET ;; esac
T0=$(date +%s)
DEADLINE=$(( T0 + BUDGET_S ))
OUT="$HERE/out/$(date +%Y%m%d-%H%M%S)-s$SEED"
mkdir -p "$OUT/logs" "$OUT/shots"
: > "$OUT/results.tsv"; : > "$OUT/devstate.tsv"
echo "$SEED" > "$OUT/.rng" # every rnd() draw is reproducible for a given seed
export SERIAL OUT DEADLINE ROOT

# shellcheck source=lib.sh
. "$HERE/lib.sh"
for f in "$HERE"/scenarios/*.sh; do . "$f"; done

a get-state 2>/dev/null | grep -q device || { echo "$SERIAL not online" >&2; exit 2; }
a wait-for-device
until [ "$(sh_ getprop sys.boot_completed | tr -d '\r')" = 1 ]; do sleep 2; done
# root adbd on emulators so ANR traces in /data/anr can be pulled (unrooted again at exit)
ROOTED=0
if [ "$IS_EMU" = 1 ] && [ "$(sh_ id -u | tr -d '\r')" != 0 ]; then a root >/dev/null 2>&1; sleep 2; a wait-for-device; ROOTED=1; fi
trap '[ "$ROOTED" = 1 ] && a unroot >/dev/null 2>&1' EXIT # replaced by final_restore once the baseline exists

if [ "$BUILD" = 1 ]; then
  log "building debug APK"
  [ -n "${JAVA_HOME:-}" ] || { JAVA_HOME=$(/usr/libexec/java_home -v 17 2>/dev/null); export JAVA_HOME; }
  [ -f "$ROOT/android/local.properties" ] || [ -n "${ANDROID_HOME:-}" ] || export ANDROID_HOME="$HOME/Library/Android/sdk"
  (cd "$ROOT/android" && ./gradlew -q --max-workers=2 :app:assembleDebug) || { echo "build failed" >&2; exit 1; }
  APK="$ROOT/android/app/build/outputs/apk/debug/app-debug.apk"
fi
if [ -n "$APK" ]; then
  log "installing $APK on $SERIAL"
  a install -r -g "$APK" >/dev/null || { echo "install failed" >&2; exit 1; }
fi

# ---- device-state baseline (I9) ---------------------------------------------
# BASE = the user's state at run start: restored and checked at exit. HARNESS = BASE plus what the
# harness itself needs while running (screen stays on, faster animations): restored between scenarios.
BASE_SNAP="$OUT/devstate.base" HARNESS_SNAP="$OUT/devstate.harness" FINAL_DONE=0
devstate > "$BASE_SNAP"
final_restore() {
  [ "$FINAL_DONE" = 1 ] && return; FINAL_DONE=1
  kill $(jobs -p) 2>/dev/null; sh_ pkill -f com.android.commands.monkey >/dev/null 2>&1
  log "restoring device to the run-start snapshot"
  devstate_check final "$BASE_SNAP"
  [ "$ROOTED" = 1 ] && a unroot >/dev/null 2>&1
}
trap final_restore EXIT
trap 'log "interrupted"; exit 130' INT TERM

log "seed=$SEED budget=${BUDGET_S}s serial=$SERIAL emulator=$IS_EMU out=$OUT"
sh_ settings put global window_animation_scale 0.5 >/dev/null
sh_ svc power stayon true
devstate > "$HARNESS_SNAP"
sh_ input keyevent KEYCODE_WAKEUP; sh_ wm dismiss-keyguard >/dev/null 2>&1
# Fresh app state: earlier runs (or monkey tapping "Use Wi-Fi") persist a Wi-Fi choice that hides the gate.
sh_ pm clear "$PKG" >/dev/null
restore_device

# Fixed order; monkey goes last and absorbs leftover budget. Every other group gets a weighted slice
# of the budget minus MONKEY_SHARE (default 40%, when monkey is selected), so a short budget still
# reaches every group (each runs at least its first sub-scenario); time a group doesn't use carries over.
weight() { case $1 in lifecycle) echo 4 ;; resource|network) echo 3 ;; storage) echo 2 ;; *) echo 1 ;; esac; }
RUN_DEADLINE=$DEADLINE reserve=0
case ",$ONLY," in *,monkey,*) reserve=$(( BUDGET_S * ${MONKEY_SHARE:-40} / 100 )) ;; esac
groups=()
for s in ${ALL//,/ }; do case ",$ONLY," in *",$s,"*) groups+=("$s") ;; esac; done
for idx in "${!groups[@]}"; do
  s=${groups[$idx]}
  DEADLINE=$RUN_DEADLINE
  if over; then record "$s" SKIP 0 0 "budget exhausted"; continue; fi
  if [ "$s" != monkey ]; then
    wsum=0; for g in "${groups[@]:$idx}"; do [ "$g" = monkey ] || wsum=$(( wsum + $(weight "$g") )); done
    slice=$(( (RUN_DEADLINE - $(now) - reserve) * $(weight "$s") / wsum ))
    [ "$slice" -lt 30 ] && slice=30
    DEADLINE=$(( $(now) + slice )); [ "$DEADLINE" -gt "$RUN_DEADLINE" ] && DEADLINE=$RUN_DEADLINE
  fi
  log "=== scenario $s ($(left)s for this group)"
  if [ "$s" = monkey ]; then scenario_monkey "$SEED"; else "scenario_$s"; fi
  DEADLINE=$RUN_DEADLINE
  settle "$s"
done

final_restore
printf 'seed=%s\nbudget_s=%s\nelapsed_s=%s\nserial=%s\nemulator=%s\n' "$SEED" "$BUDGET_S" "$(( $(date +%s) - T0 ))" "$SERIAL" "$IS_EMU" > "$OUT/run.env"
python3 -I "$HERE/summary.py" "$OUT" > "$OUT/summary.md"
cat "$OUT/summary.md"
! grep -q $'\tFAIL\t' "$OUT/results.tsv"
