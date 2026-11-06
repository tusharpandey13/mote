# shellcheck shell=bash
# Lifecycle chaos: process death, background/foreground storms, config-change storms, multi-window, RTL.

# Common post-condition: app relaunches, is resumed and the HidService is not duplicated.
healthy() {
  local note=""
  launch; pause 2
  alive || note="app not running after relaunch"
  case "$(top_pkg)" in *"$PKG"*) ;; *) note="${note:+$note; }not resumed" ;; esac
  local n; n=$(svc_count); [ "${n:-0}" -gt 1 ] && note="${note:+$note; }$n HidService records"
  echo "$note"
}

lc_bgfg() { # rapid background/foreground
  mark; launch
  for _ in $(seq 40); do
    home; sleep "0.$(rnd 4)"; sh_ am start -n "$ACT" >/dev/null; sleep "0.$(rnd 4)"
  done
  finish lifecycle-bgfg "$(healthy)"
}

lc_kill() { # am kill in background, then cold restore; also kill while service is running
  mark
  for _ in $(seq 10); do
    launch; pause 1; home; pause 1
    sh_ am kill "$PKG"; sleep "0.$(rnd 9)"
  done
  finish lifecycle-am-kill "$(healthy)"
}

lc_forcestop_fgs() { # force-stop at random points while MainActivity.onStart starts the FGS
  mark
  for _ in $(seq 25); do
    sh_ am start -n "$ACT" >/dev/null 2>&1
    sleep "0.$(rnd 6)$(rnd 10)"
    sh_ am force-stop "$PKG"
  done
  finish lifecycle-forcestop-fgs "$(healthy)"
}

lc_rotation() {
  mark; launch
  sh_ settings put system accelerometer_rotation 0
  for i in $(seq 30); do sh_ settings put system user_rotation $(( i % 4 )); sleep "0.$(rnd 5)"; done
  sh_ settings put system user_rotation 1; pause 2; shot rotation-landscape
  sh_ settings put system user_rotation 0
  finish lifecycle-rotation "$(healthy)"
}

lc_night() {
  mark; launch
  for i in $(seq 20); do
    if [ $(( i % 2 )) -eq 0 ]; then sh_ cmd uimode night yes; else sh_ cmd uimode night no; fi
    sleep "0.$(rnd 8)"
  done
  sh_ cmd uimode night yes; pause 2; shot night-yes
  sh_ cmd uimode night no
  finish lifecycle-night "$(healthy)"
}

lc_font_density() {
  mark; launch
  local d0; d0=$(sh_ wm density | tr -d '\r' | awk '/Physical/{print $3}')
  for fs in 0.85 1.3 2.0 1.0 1.5; do sh_ settings put system font_scale "$fs"; sleep 1; done
  sh_ settings put system font_scale 2.0; pause 2; shot font-2.0
  for f in 75 150 60 125; do sh_ wm density $(( d0 * f / 100 )); sleep 1; done
  sh_ wm density $(( d0 * 150 / 100 )); sh_ settings put system font_scale 1.3; pause 2; shot density-150-font-1.3
  sh_ settings put system font_scale 1.0; sh_ wm density reset
  finish lifecycle-font-density "$(healthy)"
}

lc_multiwindow() {
  mark
  sh_ settings put global enable_freeform_support 1 >/dev/null 2>&1
  sh_ am force-stop "$PKG"
  # windowingMode 5 = freeform, 6 = multi-window; best effort, emulator phones may refuse both.
  sh_ am start --windowingMode 6 -n "$ACT" >/dev/null 2>&1; pause 2; shot multiwindow
  sh_ am start --windowingMode 5 -n "$ACT" >/dev/null 2>&1; pause 2; shot freeform
  # resize storm via task bounds when freeform is honoured
  local task; task=$(sh_ am stack list 2>/dev/null | tr -d '\r' | awk -v p="$PKG" '$0 ~ p {for(i=1;i<=NF;i++) if($i ~ /^taskId=/){sub("taskId=","",$i); sub(":","",$i); print $i; exit}}')
  if [ -n "$task" ]; then
    for _ in $(seq 10); do
      sh_ am task resize "$task" 0 0 $(( 300 + $(rnd 700) )) $(( 400 + $(rnd 1200) )) >/dev/null 2>&1; sleep 0.3
    done
  fi
  sh_ am force-stop "$PKG"
  finish lifecycle-multiwindow "$(healthy)"
}

lc_rtl() {
  mark
  sh_ cmd locale set-app-locales "$PKG" --locales ar >/dev/null 2>&1
  launch; pause 2; shot rtl-ar-main
  # open the menu sheet (top-right) and the mode rail tabs for layout screenshots
  local w h; read -r w h <<<"$(sh_ wm size | tr -d '\r' | awk -F'[ x]' '/Physical/{print $3, $4}')"
  for i in 1 2 3 4; do sh_ input tap $(( w * (2 * i - 1) / 8 )) $(( h - h / 20 )); pause 1; shot "rtl-ar-tab$i"; done
  # The remote mirrors physical controls: an RTL locale must not swap mouse L/R (or prev/next, vol -/+).
  local note="" l r
  tap_rail Mouse; pause 1
  l=$(ui_x L); r=$(ui_x R)
  if [ -n "$l" ] && [ -n "$r" ] && [ "$l" -gt "$r" ]; then note="RTL mirrors mouse buttons (R left of L)"; fi
  sh_ cmd locale set-app-locales "$PKG" --locales "" >/dev/null 2>&1
  local h; h=$(healthy); [ -n "$h" ] && note="${note:+$note; }$h"
  finish lifecycle-rtl "$note"
}

scenario_lifecycle() {
  for s in lc_bgfg lc_kill lc_forcestop_fgs lc_rotation lc_night lc_font_density lc_multiwindow lc_rtl; do
    over && { record "$s" SKIP 0 0 "budget exhausted"; continue; }
    "$s"; restore_device
  done
}
