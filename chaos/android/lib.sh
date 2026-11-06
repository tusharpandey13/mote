# shellcheck shell=bash
# Shared helpers for the Mote Android chaos suite. Sourced by run.sh and scenarios/*.sh.
# Expects: SERIAL, PKG, OUT, DEADLINE (epoch seconds); run.sh writes the seed to $OUT/.rng.

PKG=${PKG:-dev.mote.remote}
ACT="$PKG/.MainActivity"
RESULTS="$OUT/results.tsv"

a() { adb -s "$SERIAL" "$@"; }
sh_() { adb -s "$SERIAL" shell "$@"; }
log() { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$*" | tee -a "$OUT/run.log" >&2; }
now() { date +%s; }
left() { echo $(( DEADLINE - $(now) )); }
over() { [ "$(now)" -ge "$DEADLINE" ]; }
# rnd N -> seeded integer in [0, N). LCG state lives in a file so draws made inside $(...)
# subshells still advance one shared sequence (bash RANDOM does not survive subshells).
rnd() {
  local s; s=$(cat "$OUT/.rng")
  s=$(( (s * 1103515245 + 12345) % 2147483648 ))
  echo "$s" > "$OUT/.rng"
  echo $(( (s / 65536) % $1 ))
}
pause() { sleep "${1:-1}"; }

launch() { sh_ am start -W -n "$ACT" >/dev/null 2>&1; }
home() { sh_ input keyevent KEYCODE_HOME; }
pid() { sh_ pidof "$PKG" 2>/dev/null | tr -d '\r'; }
alive() { [ -n "$(pid)" ]; }
# no early exit in the pipeline: run.sh uses pipefail and SIGPIPE would fail the caller
top_pkg() { sh_ dumpsys activity activities 2>/dev/null | tr -d '\r' | awk '/topResumedActivity/ && !f {print; f=1}'; }

# Number of HidService records the system holds for the app (must be 0 or 1).
svc_count() { sh_ dumpsys activity services "$PKG" 2>/dev/null | tr -d '\r' | grep -c 'ServiceRecord{.*HidService' || true; }

shot() { a exec-out screencap -p > "$OUT/shots/$1.png" 2>/dev/null || true; }
# ui_x LABEL -> centre x of the first node whose text or content-desc is exactly LABEL (empty if none)
ui_x() {
  ui_text | python3 -I -c '
import re, sys
label = sys.argv[1]
for n in re.findall(r"<node [^>]*>", sys.stdin.read()):
    if re.search(r"(text|content-desc)=\"%s\"" % re.escape(label), n):
        x1, x2 = re.search(r"bounds=\"\[(\d+),\d+\]\[(\d+),", n).groups()
        print((int(x1) + int(x2)) // 2); break' "$1"
}
tap_rail() { local x; x=$(ui_x "$1"); [ -n "$x" ] && sh_ input tap "$x" "$(sh_ wm size | tr -d '\r' | awk -F'[ x]' '/Physical/{print $4 - $4 / 20}')"; }
ui_has() { case "$(ui_text)" in *"$1"*) return 0 ;; *) return 1 ;; esac; }
ui_text() { sh_ uiautomator dump /sdcard/mote-ui.xml >/dev/null 2>&1; sh_ cat /sdcard/mote-ui.xml 2>/dev/null | tr -d '\r'; }

# --- crash / ANR accounting -------------------------------------------------
# Each scenario starts from a cleared logcat; at the end we harvest crash lines for $PKG.
mark() { a logcat -c 2>/dev/null; a logcat -b crash -c 2>/dev/null; DROPBOX_T0=$(dropbox_count); }
dropbox_count() {
  sh_ dumpsys dropbox --print data_app_crash data_app_anr data_app_native_crash 2>/dev/null \
    | tr -d '\r' | grep -c "Process: $PKG\$" || true
}
# harvest NAME -> writes $OUT/logs/NAME.log, echoes "crashes anrs"
harvest() {
  local f="$OUT/logs/$1.log"
  a logcat -d -b main,system,crash -v threadtime > "$f" 2>/dev/null
  local c n d
  c=$(grep -A2 'FATAL EXCEPTION' "$f" | grep -c "Process: $PKG," || true)
  n=$(grep -c "ANR in $PKG" "$f" || true)
  d=$(( $(dropbox_count) - ${DROPBOX_T0:-0} ))
  [ "$d" -gt "$c" ] && c=$d # dropbox catches crashes that rolled out of the logcat ring
  grep -A25 'FATAL EXCEPTION' "$f" > "$OUT/logs/$1.crash.txt" 2>/dev/null || true
  [ -s "$OUT/logs/$1.crash.txt" ] || rm -f "$OUT/logs/$1.crash.txt"
  echo "$c $n"
}

# record SCENARIO STATUS CRASHES ANRS NOTES
record() {
  printf '%s\t%s\t%s\t%s\t%s\n' "$1" "$2" "$3" "$4" "$5" >> "$RESULTS"
  log "RESULT $1: $2 (crashes=$3 anrs=$4) $5"
}

# finish NAME [extra-failure-note] : harvest + record; status FAIL if crash/ANR or note given.
finish() {
  local name=$1 note=${2:-} c n
  read -r c n <<<"$(harvest "$name")"
  local status=PASS
  if [ "$c" -gt 0 ] || [ "$n" -gt 0 ] || [ -n "$note" ]; then status=FAIL; fi
  record "$name" "$status" "$c" "$n" "${note:-ok}"
}

# Restore a sane device state (called between scenarios and on exit).
restore_device() {
  sh_ settings put system font_scale 1.0 >/dev/null 2>&1
  sh_ wm density reset >/dev/null 2>&1
  sh_ cmd uimode night no >/dev/null 2>&1
  sh_ settings put system accelerometer_rotation 0 >/dev/null 2>&1
  sh_ settings put system user_rotation 0 >/dev/null 2>&1
  sh_ dumpsys deviceidle unforce >/dev/null 2>&1
  sh_ dumpsys battery reset >/dev/null 2>&1
  sh_ settings put global low_power 0 >/dev/null 2>&1
  sh_ cmd connectivity airplane-mode disable >/dev/null 2>&1
  sh_ svc wifi enable >/dev/null 2>&1
  sh_ svc data enable >/dev/null 2>&1
  sh_ cmd bluetooth_manager enable >/dev/null 2>&1
  sh_ am set-standby-bucket "$PKG" active >/dev/null 2>&1
  sh_ cmd locale set-app-locales "$PKG" --locales "" >/dev/null 2>&1
  grant_all
}

grant_all() {
  for p in BLUETOOTH_CONNECT BLUETOOTH_ADVERTISE BLUETOOTH_SCAN POST_NOTIFICATIONS; do
    sh_ pm grant "$PKG" "android.permission.$p" >/dev/null 2>&1
  done
}
