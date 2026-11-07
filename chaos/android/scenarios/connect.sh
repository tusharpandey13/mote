# shellcheck shell=bash
# One-tap connect (being built in parallel; docs/labs/pairing.md section 5) and VPN / failover chaos.
# Each check is feature-detected; when its code or prerequisite is missing it records PENDING with
# the reason, which never fails the run. Detection greps android/app/src/main/java for declarations:
#
#   connect-proximity   BLE proximity token     (class|object) (ProximityToken|Proximity)
#   connect-lan-sweep   LAN subnet sweep        (class|object) (SubnetSweep|LanSweep|Sweep)
#   connect-wifi-first  Wi-Fi-first auto-connect (class|object) (AutoConnect|WifiFirst|ConnectPlanner)
#   connect-auto-trust  phone-side auto-trust    (class|object|fun) (AutoTrust|autoTrust)
#   connect-vpn-storm   Tailscale toggle storm   com.tailscale.ipn installed on the device
#   connect-failover    LAN<->tailnet<->BT       a real phone with a paired Mac (BT HID: physical.sh)
#
# Once detected, a generic chaos body runs (storm + liveness + visible state); extend it with the
# feature's own assertions when the code lands.

SIG_PROXIMITY='(class|object) (ProximityToken|Proximity)\b'
SIG_SWEEP='(class|object) (SubnetSweep|LanSweep|Sweep)\b'
SIG_WIFI_FIRST='(class|object) (AutoConnect|WifiFirst|ConnectPlanner)\b'
SIG_AUTO_TRUST='(class|object|fun) (AutoTrust|autoTrust)\b'

cn_generic() { # name, storm command
  local note
  mark; nw_seed; launch; ui_wait "Device: $NW_NAME" 20; pause 2
  eval "$2"; pause 8
  note=$(add "" "$(nw_visible "$1")")
  note=$(add "$note" "$(healthy)")
  finish "connect-$1" "$note" "generic smoke body; add feature asserts"
  nw_unseed
}

cn_proximity() {
  has_code "$SIG_PROXIMITY" || { pending connect-proximity "no BLE proximity token code yet (grep: $SIG_PROXIMITY)"; return; }
  bt_present || { pending connect-proximity "device has no Bluetooth"; return; }
  cn_generic proximity 'bt_toggle_storm 20 5 >/dev/null; sh_ cmd bluetooth_manager enable >/dev/null 2>&1'
}
cn_sweep() {
  has_code "$SIG_SWEEP" || { pending connect-lan-sweep "no LAN subnet sweep code yet (grep: $SIG_SWEEP)"; return; }
  cn_generic lan-sweep nw_flap
}
cn_wifi_first() {
  has_code "$SIG_WIFI_FIRST" || { pending connect-wifi-first "no Wi-Fi-first auto-connect code yet (grep: $SIG_WIFI_FIRST)"; return; }
  cn_generic wifi-first 'sh_ svc wifi disable; sleep 3; sh_ svc wifi enable'
}
cn_auto_trust() {
  has_code "$SIG_AUTO_TRUST" || { pending connect-auto-trust "no phone-side auto-trust code yet (grep: $SIG_AUTO_TRUST)"; return; }
  # I6 is decided by the helper's trust rules (chaos/helper); here only liveness under a reconnect storm.
  cn_generic auto-trust 'sh_ am force-stop "$PKG"; launch; sh_ am force-stop "$PKG"; launch'
}

cn_vpn_storm() {
  tailscale_installed || { pending connect-vpn-storm "com.tailscale.ipn not installed on $SERIAL"; return; }
  local note
  mark; nw_seed 100.100.100.100; launch; ui_wait "Device: $NW_NAME" 20; pause 2
  for _ in $(seq 12); do ts_vpn CONNECT_VPN; sleep "0.$(rnd 9)"; ts_vpn DISCONNECT_VPN; sleep "0.$(rnd 9)"; done
  pause 5
  note=$(add "" "$(nw_visible vpn-storm)")
  note=$(add "$note" "$(healthy)")
  finish connect-vpn-storm "$note" "tailnet host 100.100.100.100"
  nw_unseed
}

cn_failover() {
  if [ "${IS_EMU:-1}" = 1 ]; then
    pending connect-failover "Bluetooth HID can't run on an emulator: physical.sh with a paired Mac + helper"; return
  fi
  pending connect-failover "not automated yet: needs a helper reachable on LAN and tailnet and a BT-linked Mac (manual: docs/labs/pairing.md 5, 15 s fallback, 30 s switch-back)"
}

scenario_connect() {
  local s
  for s in cn_proximity cn_sweep cn_wifi_first cn_auto_trust cn_vpn_storm cn_failover; do
    over && { record "$s" SKIP 0 0 "budget exhausted"; continue; }
    "$s"; settle "$s"
  done
}
