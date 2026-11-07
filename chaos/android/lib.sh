# shellcheck shell=bash
# Shared helpers for the Mote Android chaos suite. Sourced by run.sh and scenarios/*.sh.
# Expects: SERIAL, PKG, OUT, DEADLINE (epoch seconds); run.sh writes the seed to $OUT/.rng.
# Rubric (severities S1-S4, invariants I1-I10, budgets): ../RUBRIC.md.

PKG=${PKG:-dev.mote.remote}
ACT="$PKG/.MainActivity"
RESULTS="$OUT/results.tsv"
DEVSTATE="$OUT/devstate.tsv"

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

# am start -W can block forever (seen after monkey left Chrome's first-run screen on top): cap at 10 s
launch() {
  sh_ am start -W -n "$ACT" >/dev/null 2>&1 & local p=$! i=0
  while kill -0 "$p" 2>/dev/null && [ "$i" -lt 20 ]; do sleep 0.5; i=$((i + 1)); done
  kill "$p" 2>/dev/null; wait "$p" 2>/dev/null; return 0
}
home() { sh_ input keyevent KEYCODE_HOME; }
pid() { sh_ pidof "$PKG" 2>/dev/null | tr -d '\r'; }
alive() { [ -n "$(pid)" ]; }
# no early exit in the pipeline: run.sh uses pipefail and SIGPIPE would fail the caller
top_pkg() { sh_ dumpsys activity activities 2>/dev/null | tr -d '\r' | awk '/topResumedActivity/ && !f {print; f=1}'; }

# Number of HidService records the system holds for the app (must be 0 or 1).
svc_count() { sh_ dumpsys activity services "$PKG" 2>/dev/null | tr -d '\r' | grep -c 'ServiceRecord{.*HidService' || true; }

shot() { a exec-out screencap -p > "$OUT/shots/$1.png" 2>/dev/null || true; }
# ui_xy REGEX -> "x y" centre of the first node whose text or content-desc matches REGEX (python re, anchored at start)
ui_xy() {
  ui_text | python3 -I -c '
import re, sys
pat = re.compile(sys.argv[1])
for n in re.findall(r"<node [^>]*>", sys.stdin.read()):
    for attr in ("text", "content-desc"):
        m = re.search(attr + r"=\"([^\"]*)\"", n)
        if m and pat.match(m.group(1)):
            x1, y1, x2, y2 = map(int, re.search(r"bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", n).groups())
            print((x1 + x2) // 2, (y1 + y2) // 2); sys.exit()' "$1"
}
# ui_x LABEL -> centre x of the first node whose text or content-desc is exactly LABEL (empty if none)
ui_x() { local p; p=$(python3 -I -c 'import re,sys; print(re.escape(sys.argv[1]) + "$")' "$1"); ui_xy "$p" | cut -d' ' -f1; }
tap_rail() { local x; x=$(ui_x "$1"); [ -n "$x" ] && sh_ input tap "$x" "$(sh_ wm size | tr -d '\r' | awk -F'[ x]' '/Physical/{print $4 - $4 / 20}')"; }
ui_has() { case "$(ui_text)" in *"$1"*) return 0 ;; *) return 1 ;; esac; }
# ui_wait TEXT SECS: poll the UI until TEXT shows up (true) or SECS pass (false)
ui_wait() { local end=$(( $(now) + $2 )); until ui_has "$1"; do [ "$(now)" -ge "$end" ] && return 1; sleep 1; done; }
ui_text() { sh_ uiautomator dump /sdcard/mote-ui.xml >/dev/null 2>&1; sh_ cat /sdcard/mote-ui.xml 2>/dev/null | tr -d '\r'; }

# --- findings ---------------------------------------------------------------
# A failing check is a note tagged with its rubric severity and violated invariant(s):
#   tag S2 I8 "gate stuck"  ->  "[S2 I8] gate stuck"   ("-" for no invariant). Untagged notes count as [S2 I10].
tag() { echo "[$1 $2] $3"; }
add() { [ -n "$2" ] && echo "${1:+$1; }$2" || echo "$1"; } # add NOTE PART -> joined note

# Invariants a scenario exercises (results.tsv column 7). The violated ones are column 8.
inv_for() {
  case $1 in
    storage-*) echo I7,I10 ;;
    lifecycle-*) echo I2,I10 ;;
    resource-revoke*) echo I8,I10 ;;
    resource-*) echo I2,I10 ;;
    bluetooth-*) echo I2,I4,I8,I10 ;;
    monkey-anr-budget|monkey-*) echo I10 ;;
    leak-*) echo I3 ;;
    network-*) echo I8,I10 ;;
    devstate-*) echo I9 ;;
    connect-proximity|connect-lan-sweep|connect-wifi-first) echo I8,I10 ;;
    connect-auto-trust) echo I6,I10 ;;
    connect-vpn-storm) echo I8,I9,I10 ;;
    connect-failover) echo I4,I8,I10 ;;
    *) echo - ;;
  esac
}

# --- crash / ANR accounting -------------------------------------------------
# Each scenario starts from a cleared logcat; at the end we harvest crash lines for $PKG.
mark() {
  a logcat -c 2>/dev/null; a logcat -b crash -c 2>/dev/null; DROPBOX_T0=$(dropbox_count); SCEN_T0=$(now)
  sh_ ls /data/anr 2>/dev/null | tr -d '\r' | grep '^anr_' > "$OUT/.anr0" || true
}
dropbox_count() {
  sh_ dumpsys dropbox --print data_app_crash data_app_anr data_app_native_crash 2>/dev/null \
    | tr -d '\r' | grep -c "Process: $PKG\$" || true
}
# harvest NAME -> writes $OUT/logs/NAME.log, echoes "crashes anrs attributed"
# attributed = ANRs of $PKG with a pulled trace whose "main" thread has no app frames (rubric: emulator
# render-thread stalls); /data/anr is readable on rooted emulators only, so elsewhere nothing is attributed.
harvest() {
  local f="$OUT/logs/$1.log"
  a logcat -d -b main,system,crash -v threadtime > "$f" 2>/dev/null
  local c n d att=0 i=0
  c=$(grep -A2 'FATAL EXCEPTION' "$f" | grep -c "Process: $PKG," || true)
  n=$(grep -c "ActivityManager: ANR in $PKG" "$f" || true) # Monkey echoes the same ANR; count once
  if [ "$n" -gt 0 ]; then
    local t
    for t in $(sh_ ls /data/anr 2>/dev/null | tr -d '\r' | grep '^anr_' | grep -vxF -f "$OUT/.anr0" || true); do
      local dst="$OUT/logs/$1.anr$i.txt"
      a pull "/data/anr/$t" "$dst" >/dev/null 2>&1 || continue
      if ! grep -q "Cmd line: $PKG\$" "$dst"; then rm -f "$dst"; continue; fi
      i=$((i + 1))
      [ "$(awk '/^"main"/{f=1} f&&/^$/{exit} f' "$dst" | grep -c "$PKG" || true)" -eq 0 ] && att=$((att + 1))
    done
    [ "$att" -gt "$n" ] && att=$n
  fi
  d=$(( $(dropbox_count) - ${DROPBOX_T0:-0} ))
  [ "$d" -gt "$c" ] && c=$d # dropbox catches crashes that rolled out of the logcat ring
  grep -A25 'FATAL EXCEPTION' "$f" > "$OUT/logs/$1.crash.txt" 2>/dev/null || true
  [ -s "$OUT/logs/$1.crash.txt" ] || rm -f "$OUT/logs/$1.crash.txt"
  echo "$c $n $att"
}

# record SCENARIO STATUS CRASHES ANRS NOTES [SEVERITY] [VIOLATED]
# results.tsv: scenario status crashes anrs notes severity invariants violated secs
record() {
  local secs=$(( $(now) - ${SCEN_T0:-$(now)} ))
  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$1" "$2" "$3" "$4" "$5" "${6:--}" "$(inv_for "$1")" "${7:--}" "$secs" >> "$RESULTS"
  log "RESULT $1: $2 (crashes=$3 anrs=$4${6:+ sev=$6}) $5"
}
# pending NAME REASON: a check that can't run here yet; never fails the run.
pending() { SCEN_T0=$(now); record "$1" PENDING 0 0 "$2"; }

# finish NAME [failure-note] [info-note] : harvest + record; FAIL on crash/ANR or a failure note.
# Monkey runs (monkey-*) don't fail on ANRs whose trace has no app frames; they are summed into
# MONKEY_ANR_ATTR and checked against the rubric budget (<= 2 per 50k events) by monkey-anr-budget.
finish() {
  local name=$1 note=${2:-} info=${3:-} c n att
  read -r c n att <<<"$(harvest "$name")"
  if ! ensure_installed; then
    record "$name" SKIP "$c" "$n" "package uninstalled by another adb client during the scenario (e.g. connectedAndroidTest on all devices); reinstalled, result void"
    return
  fi
  if [ "$c" -gt 0 ]; then
    case $name in storage-*) note=$(add "$note" "$(tag S1 I7 "$c crash(es) on persisted state")") ;;
      *) note=$(add "$note" "$(tag S2 I10 "$c crash(es)")") ;; esac
  fi
  local un=$n
  case $name in monkey-s*) un=$(( n - att )); MONKEY_ANR_ATTR=$(( ${MONKEY_ANR_ATTR:-0} + att ))
    [ "$att" -gt 0 ] && info=$(add "$info" "$att ANR(s) attributed: no app frames on main") ;;
    *) [ "$att" -gt 0 ] && info=$(add "$info" "$att of the ANR trace(s) have no app frames on main (rubric: still a failure outside monkey)") ;; esac
  [ "$un" -gt 0 ] && note=$(add "$note" "$(tag S2 I10 "$un ANR(s) not attributed")")
  local status=PASS sev="" viol=""
  if [ -n "$note" ]; then
    status=FAIL
    note=$(printf '%s' "$note" | python3 -I -c '
import sys
parts = [p.strip() for p in sys.stdin.read().split("; ") if p.strip()]
print("; ".join(p if p.startswith("[S") else "[S2 I10] " + p for p in parts))')
    sev=$(grep -oE '\[S[1-4]' <<<"$note" | tr -d '[' | sort | head -n 1)
    viol=$(grep -oE '\[S[1-4] [^]]*\]' <<<"$note" | grep -oE 'I[0-9]+' | sort -t I -k2 -n -u | paste -sd, -)
  fi
  local msg=${note:-ok}; [ -n "$info" ] && msg="$msg; $info"
  record "$name" "$status" "$c" "$n" "$msg" "$sev" "$viol"
}

# --- device state (I9) --------------------------------------------------------
# Snapshot of every device setting the suite (or the harness) can touch. One key=value per line, sorted.
DS_GLOBAL="airplane_mode_on wifi_on bluetooth_on mobile_data low_power window_animation_scale transition_animation_scale animator_duration_scale stay_on_while_plugged_in enable_freeform_support"
DS_SYSTEM="font_scale user_rotation accelerometer_rotation system_locales"
DS_SECURE="always_on_vpn_app always_on_vpn_lockdown"
devstate() {
  sh_ "
for k in $DS_GLOBAL; do echo global.\$k=\$(settings get global \$k); done
for k in $DS_SYSTEM; do echo system.\$k=\$(settings get system \$k); done
for k in $DS_SECURE; do echo secure.\$k=\$(settings get secure \$k); done
echo wifi=\$(cmd wifi status 2>/dev/null | head -n 1)
echo bt=\$(dumpsys bluetooth_manager 2>/dev/null | grep -m 1 'enabled:' | tr -d ' ')
echo density=\$(wm density | grep Override | sed 's/.*: //')
echo size=\$(wm size | grep Override | sed 's/.*: //')
echo night=\$(cmd uimode night | sed 's/.*: //')
echo locale=\$(getprop persist.sys.locale)
echo app_locales=\$(cmd locale get-app-locales $PKG 2>/dev/null | sed 's/.* are //')
echo deviceidle=\$(dumpsys deviceidle get deep)/\$(dumpsys deviceidle get light)
echo battery_ac=\$(dumpsys battery | grep -m 1 'AC powered' | sed 's/.*: //')
echo standby=\$(am get-standby-bucket $PKG 2>/dev/null)
echo vpn=\$(dumpsys connectivity | grep -c 'ni{VPN')
" 2>/dev/null | tr -d '\r' | sort
}
ds_get() { sed -n "s/^$2=//p" "$1"; }
bucket_name() { case $1 in 10) echo active ;; 20) echo working_set ;; 30) echo frequent ;; 40) echo rare ;; 45) echo restricted ;; *) echo "" ;; esac; }

# restore_to SNAPSHOT_FILE: put back every key that differs from the snapshot, then wait for the
# asynchronous ones (Wi-Fi, Bluetooth, airplane) to settle. Check-only keys: locale, system_locales, vpn.
restore_to() {
  local want=$1 cur="$OUT/.ds-cur" pass k v
  for pass in 1 2 3; do
    devstate > "$cur"
    cmp -s "$cur" "$want" && break
    v=$(ds_get "$want" global.airplane_mode_on)
    if [ "$(ds_get "$cur" global.airplane_mode_on)" != "$v" ]; then
      if [ "$v" = 1 ]; then sh_ cmd connectivity airplane-mode enable; else sh_ cmd connectivity airplane-mode disable; fi
      sleep 2; devstate > "$cur"
    fi
    if [ "$(ds_get "$cur" wifi)" != "$(ds_get "$want" wifi)" ]; then
      case $(ds_get "$want" wifi) in *enabled*) sh_ svc wifi enable ;; *disabled*) sh_ svc wifi disable ;; esac
    fi
    v=$(ds_get "$want" global.mobile_data)
    if [ "$(ds_get "$cur" global.mobile_data)" != "$v" ]; then
      if [ "$v" = 0 ]; then sh_ svc data disable; else sh_ svc data enable; fi
    fi
    if [ "$(ds_get "$cur" bt)" != "$(ds_get "$want" bt)" ]; then
      case $(ds_get "$want" bt) in *true*) sh_ cmd bluetooth_manager enable ;; *false*) sh_ cmd bluetooth_manager disable ;; esac >/dev/null 2>&1
    fi
    local ns
    for k in $DS_GLOBAL $DS_SYSTEM $DS_SECURE; do
      case $k in airplane_mode_on|wifi_on|bluetooth_on|mobile_data|system_locales) continue ;; esac
      ns=global; case " $DS_SYSTEM " in *" $k "*) ns=system ;; esac; case " $DS_SECURE " in *" $k "*) ns=secure ;; esac
      v=$(ds_get "$want" "$ns.$k")
      [ "$(ds_get "$cur" "$ns.$k")" = "$v" ] && continue
      if [ "$v" = null ]; then sh_ settings delete "$ns" "$k" >/dev/null; else sh_ settings put "$ns" "$k" "$v"; fi
    done
    v=$(ds_get "$want" density)
    if [ "$(ds_get "$cur" density)" != "$v" ]; then if [ -z "$v" ]; then sh_ wm density reset; else sh_ wm density "$v"; fi; fi
    v=$(ds_get "$want" size)
    if [ "$(ds_get "$cur" size)" != "$v" ]; then if [ -z "$v" ]; then sh_ wm size reset; else sh_ wm size "$v"; fi; fi
    v=$(ds_get "$want" night)
    [ "$(ds_get "$cur" night)" != "$v" ] && [ -n "$v" ] && sh_ cmd uimode night "$v" >/dev/null
    v=$(ds_get "$want" app_locales)
    if [ "$(ds_get "$cur" app_locales)" != "$v" ]; then
      v=${v#[}; v=${v%]}; sh_ cmd locale set-app-locales "$PKG" --locales "$v" >/dev/null 2>&1
    fi
    if [ "$(ds_get "$cur" deviceidle)" != "$(ds_get "$want" deviceidle)" ] || [ "$(ds_get "$cur" battery_ac)" != "$(ds_get "$want" battery_ac)" ]; then
      sh_ dumpsys deviceidle unforce >/dev/null 2>&1; sh_ dumpsys battery reset >/dev/null 2>&1
    fi
    v=$(bucket_name "$(ds_get "$want" standby)")
    [ "$(ds_get "$cur" standby)" != "$(ds_get "$want" standby)" ] && [ -n "$v" ] && sh_ am set-standby-bucket "$PKG" "$v" >/dev/null 2>&1
    v=$(ds_get "$want" vpn)
    if [ "$(ds_get "$cur" vpn)" != "$v" ] && tailscale_installed; then
      if [ "$v" = 0 ]; then ts_vpn DISCONNECT_VPN; else ts_vpn CONNECT_VPN; fi
    fi
    # Wi-Fi / Bluetooth / airplane changes land asynchronously
    local i; for i in 1 2 3 4 5 6; do sleep 1; devstate > "$cur"; cmp -s "$cur" "$want" && break 2; done
  done
  grant_all
}

# restore_device: back to the harness baseline (run-start snapshot + harness overrides). Between scenarios.
restore_device() { restore_to "${HARNESS_SNAP:-$OUT/devstate.harness}"; }

# devstate_check NAME [SNAPSHOT]: restore, then FAIL (S1, I9) on any remaining difference. Logged to devstate.tsv.
devstate_check() {
  local name=$1 want=${2:-${HARNESS_SNAP:-$OUT/devstate.harness}} t0; t0=$(now)
  restore_to "$want"
  devstate > "$OUT/.ds-after"
  local d; d=$(diff "$want" "$OUT/.ds-after" | grep '^[<>]' || true)
  local nd; nd=$(printf '%s' "$d" | grep -c '^<' || true)
  printf '%s\t%s\t%s\n' "$name" "$nd" "$(printf '%s' "$d" | paste -sd' ' -)" >> "$DEVSTATE"
  if [ "$nd" -gt 0 ]; then
    printf '%s\n' "$d" > "$OUT/logs/$name.devstate.diff"
    log "DEVICE STATE DIFF after $name (want < > got):"; printf '%s\n' "$d" | tee -a "$OUT/run.log" >&2
    SCEN_T0=$t0; record "devstate-$name" FAIL 0 0 "[S1 I9] $nd key(s) differ after restore: $(printf '%s' "$d" | paste -sd' ' -)" S1 I9
  fi
}
# settle NAME: restore the device and check it (every scenario ends with this).
settle() { devstate_check "$1"; }

grant_all() {
  for p in BLUETOOTH_CONNECT BLUETOOTH_ADVERTISE BLUETOOTH_SCAN POST_NOTIFICATIONS; do
    sh_ pm grant "$PKG" "android.permission.$p" >/dev/null 2>&1
  done
}

# ensure_installed: true if $PKG is still installed. Otherwise (another adb client, typically Gradle's
# connectedAndroidTest, installs + uninstalls on every attached device) reinstall $APK and return 1.
ensure_installed() {
  sh_ pm path "$PKG" 2>/dev/null | grep -q package: && return 0
  log "WARNING: $PKG was uninstalled by another adb client; reinstalling"
  [ -n "${APK:-}" ] && a install -r -g "$APK" >/dev/null 2>&1 && grant_all
  return 1
}

tailscale_installed() { sh_ pm path com.tailscale.ipn 2>/dev/null | grep -q package:; }
ts_vpn() { sh_ am broadcast -a "com.tailscale.ipn.$1" -n com.tailscale.ipn/.IPNReceiver >/dev/null 2>&1; }

# has_code REGEX: the Android sources declare it (feature detection for scenarios that wait on new code)
has_code() { grep -rqE "$1" "$ROOT/android/app/src/main/java" 2>/dev/null; }
