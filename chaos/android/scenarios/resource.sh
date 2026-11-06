# shellcheck shell=bash
# Resource chaos: Doze, standby buckets, battery saver, airplane mode, network flaps, memory pressure,
# runtime-permission revocation.

GATE_TEXT="How do you want to connect?"

rs_doze() {
  mark; launch; pause 1; home; pause 1
  sh_ dumpsys battery unplug
  sh_ dumpsys deviceidle force-idle >/dev/null; pause 5
  sh_ dumpsys deviceidle step >/dev/null
  launch; pause 2                      # resume while still idle
  sh_ dumpsys deviceidle unforce >/dev/null; sh_ dumpsys battery reset
  finish resource-doze "$(healthy)"
}

rs_standby() {
  mark
  for b in rare restricted frequent active; do
    home; sh_ am set-standby-bucket "$PKG" "$b"; pause 1; launch; pause 1
  done
  finish resource-standby "$(healthy)"
}

rs_battery_saver() {
  mark; launch
  sh_ dumpsys battery unplug
  for _ in $(seq 6); do sh_ settings put global low_power 1; sleep 1; sh_ settings put global low_power 0; sleep "0.$(rnd 9)"; done
  sh_ dumpsys battery reset
  finish resource-battery-saver "$(healthy)"
}

rs_airplane() { # airplane mode also turns Bluetooth off/on, which drives the gate and HidService
  mark; launch
  for _ in $(seq 8); do
    sh_ cmd connectivity airplane-mode enable; sleep "1.$(rnd 9)"
    sh_ cmd connectivity airplane-mode disable; sleep "1.$(rnd 9)"
  done
  sh_ cmd bluetooth_manager enable >/dev/null 2>&1; pause 3
  finish resource-airplane "$(healthy)"
}

rs_network() {
  mark; launch
  for _ in $(seq 10); do
    sh_ svc wifi disable; sh_ svc data disable; sleep "0.$(rnd 9)"
    sh_ svc wifi enable; sh_ svc data enable; sleep "0.$(rnd 9)"
  done
  finish resource-network-flap "$(healthy)"
}

rs_trim() {
  mark; launch
  for l in RUNNING_MODERATE RUNNING_LOW RUNNING_CRITICAL; do sh_ am send-trim-memory "$PKG" "$l"; pause 1; done
  home; pause 1
  for l in BACKGROUND MODERATE COMPLETE; do sh_ am send-trim-memory "$PKG" "$l"; pause 1; done
  finish resource-trim-memory "$(healthy)"
}

# Revoking a runtime permission kills the process; on relaunch the gate must show, and a re-grant
# must bring the remote back. Also revoke while the app sits in the foreground.
rs_revoke() {
  mark; local note=""
  for p in BLUETOOTH_CONNECT BLUETOOTH_ADVERTISE BLUETOOTH_SCAN; do
    launch; pause 2
    sh_ pm revoke "$PKG" "android.permission.$p"; pause 1
    launch; pause 2
    if bt_present; then
      ui_has "$GATE_TEXT" || note="${note:+$note; }no gate after revoking $p"
    fi
    shot "revoked-$p"
    sh_ pm grant "$PKG" "android.permission.$p"
    sh_ am force-stop "$PKG"; launch; pause 2   # re-grant from Settings also restarts the app
    if bt_present && bt_on; then
      ui_has "$GATE_TEXT" && note="${note:+$note; }gate stuck after re-granting $p"
    fi
  done
  local h; h=$(healthy); [ -n "$h" ] && note="${note:+$note; }$h"
  finish resource-revoke-permission "$note"
}

bt_present() { case "$(sh_ pm has-feature android.hardware.bluetooth)" in *true*) true ;; *) false ;; esac; }
bt_on() { [ "$(sh_ settings get global bluetooth_on | tr -d '\r')" = 1 ]; }

scenario_resource() {
  for s in rs_doze rs_standby rs_battery_saver rs_airplane rs_network rs_trim rs_revoke; do
    over && { record "$s" SKIP 0 0 "budget exhausted"; continue; }
    "$s"; restore_device
  done
}
