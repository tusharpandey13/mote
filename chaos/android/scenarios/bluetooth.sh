# shellcheck shell=bash
# Bluetooth adapter chaos: high-frequency enable/disable while foreground and background.
# Invariants: no crash, <= 1 HidService record at any time, service gone once the app is backgrounded
# with no host connected (it must not leak), remote screen back once Bluetooth is on again.

bt_toggle_storm() { # count max_sleep_tenths
  local n=$1 mx=$2 peak=0 c
  for i in $(seq "$n"); do
    if [ $(( i % 2 )) -eq 1 ]; then sh_ cmd bluetooth_manager disable >/dev/null 2>&1
    else sh_ cmd bluetooth_manager enable >/dev/null 2>&1; fi
    sleep "0.$(rnd "$mx")"
    if [ $(( i % 5 )) -eq 0 ]; then c=$(svc_count); [ "${c:-0}" -gt "$peak" ] && peak=$c; fi
  done
  echo "$peak"
}

scenario_bluetooth() {
  if ! case "$(sh_ pm has-feature android.hardware.bluetooth)" in *true*) true ;; *) false ;; esac; then
    record bluetooth-toggle SKIP 0 0 "device has no Bluetooth"; return
  fi
  mark; launch; pause 2
  local note="" peak
  peak=$(bt_toggle_storm 60 3)                     # fast: 0-0.2 s between toggles
  [ "$peak" -gt 1 ] && note="peak $peak HidService records (fast storm)"
  peak=$(bt_toggle_storm 20 9)                     # slower: lets the HID proxy bind/unbind
  [ "$peak" -gt 1 ] && note="${note:+$note; }peak $peak HidService records (slow storm)"
  sh_ cmd bluetooth_manager enable >/dev/null 2>&1; pause 5
  shot bt-after-storm
  alive || note="${note:+$note; }app died during storm"
  ui_has "Mote connects over Bluetooth" && note="${note:+$note; }gate still shown with Bluetooth on"
  # background: with no host connected the service must stop itself
  home; pause 5
  local c; c=$(svc_count)
  [ "${c:-0}" -gt 0 ] && note="${note:+$note; }HidService still running in background ($c)"
  # storm while backgrounded, then come back
  bt_toggle_storm 20 5 >/dev/null
  sh_ cmd bluetooth_manager enable >/dev/null 2>&1; pause 3
  c=$(svc_count); [ "${c:-0}" -gt 0 ] && note="${note:+$note; }HidService restarted in background ($c)"
  local h; h=$(healthy); [ -n "$h" ] && note="${note:+$note; }$h"
  finish bluetooth-toggle "$note"
}
