# shellcheck shell=bash
# Network chaos with a Mote Helper (Wi-Fi) host selected: phone Wi-Fi off, airplane mode, mobile data
# off, and a flap storm. The app must stay alive and always show a visible connection state (I8).
#
# The host is seeded (debug build, run-as) as a saved helper "Chaos Mac" at 10.0.2.2 (the emulator's
# alias for the Mac's loopback), selected, wifi_chosen=true, plus a Labs instant-pairing trust record
# with a dummy key, so the app has a credential-free WsTarget and keeps retrying ("Connecting…"). If
# helper/build/MoteHelper.app exists (and MOTE_CHAOS_HELPER != 0) a --dry-run helper is started on
# the Mac and its real hostId is used, so the app reaches the handshake (the dummy key then fails it:
# "The Mac no longer knows this phone"); the helper stops at the end.
# network-stale-credential seeds an undecryptable wrapped HMAC token instead: the app must ask to
# pair again rather than reconnect forever (Vault.wrappedSigner).

NW_PORT=${MOTE_CHAOS_HELPER_PORT:-8797}
NW_NAME="Chaos Mac"
NW_ID=c4a05c4a05c4a05c
NW_HELPER_PID=""
# Every row status HostsUi.kt can show for a selected helper host (HostRowCap / failedText).
NW_STATES='Connecting…|Connected · |Paused on the Mac|Mote Helper needs Accessibility|The Mac no longer knows this phone|Pairing data is missing|Pair this Mac again|Couldn.t reach|Tailscale is off|Searching…'

nw_helper_start() {
  local bin="$ROOT/helper/build/MoteHelper.app/Contents/MacOS/MoteHelper"
  [ "${MOTE_CHAOS_HELPER:-1}" != 0 ] && [ -x "$bin" ] && command -v node >/dev/null || return 1
  "$bin" --dry-run --headless --no-ble --memory-store --open-pairing --port "$NW_PORT" > "$OUT/logs/helper.log" 2>&1 &
  NW_HELPER_PID=$!
  local id i
  for i in 1 2 3 4 5 6 7 8 9 10; do
    sleep 1
    id=$(node -e '
const ws = new WebSocket(`ws://127.0.0.1:${process.argv[1]}/ws`);
ws.onmessage = (e) => { const m = JSON.parse(e.data); if (m.t === "hello") { console.log(m.hostId || ""); process.exit(0); } };
ws.onerror = () => process.exit(1); setTimeout(() => process.exit(1), 3000);' "$NW_PORT" 2>/dev/null)
    [ -n "$id" ] && { NW_ID=$id; return 0; }
  done
  nw_helper_stop; return 1
}
nw_helper_stop() { [ -n "$NW_HELPER_PID" ] && { kill "$NW_HELPER_PID" 2>/dev/null; wait "$NW_HELPER_PID" 2>/dev/null; NW_HELPER_PID=""; }; return 0; }

# nw_seed [address] [trust|wrapped]: saved helper host selected, as if paired earlier.
nw_seed() {
  local addr=${1:-10.0.2.2} mode=${2:-trust} f="$OUT/tmp-hosts" cred="" slot=lan
  [ "$mode" = wrapped ] && cred=',"wrapped":"Y2hhb3MtZHVtbXktY3JlZGVudGlhbA=="'
  case $addr in 100.*|*.ts.net) slot=tailnet ;; esac
  python3 -I "$HERE_PB" \
    helper_hosts='[{"hostId":"'"$NW_ID"'","name":"'"$NW_NAME"'","deviceId":"chaos-phone","port":'"$NW_PORT"',"'"$slot"'":"'"$addr"'"'"$cred"'}]' \
    selection="ws:$NW_ID" wifi_chosen:bool=true > "$f"
  put_store "$f" files/datastore/hosts.preferences_pb
  if [ "$mode" = trust ]; then
    python3 -I "$HERE_PB" trust='[{"hostId":"'"$NW_ID"'","pub":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=","fp":"chaos-dummy-fp","via":"chaos"}]' > "$f"
    put_store "$f" files/datastore/labs_pairing.preferences_pb
  else
    sh_ run-as "$PKG" rm -f files/datastore/labs_pairing.preferences_pb
  fi
  rm -f "$f"
}
nw_unseed() { sh_ am force-stop "$PKG"; sh_ run-as "$PKG" rm -f files/datastore/hosts.preferences_pb files/datastore/labs_pairing.preferences_pb; }

# Undecryptable wrapped token, no network change: the sheet must show a re-pair state, not "Connecting…".
nw_stale_credential() {
  local note st
  mark; nw_seed 10.0.2.2 wrapped; : > "$OUT/.nw-states"
  launch; ui_wait "Device: $NW_NAME" 20; pause 4
  note=$(nw_visible stale)
  st=$(sed -n 's/^stale: //p' "$OUT/.nw-states")
  case $st in "Pairing data is missing"*|"Pair this Mac again"*|"The Mac no longer knows"*|"") ;;
    *) note=$(add "$note" "$(tag S3 I8 "unusable credential shows '$st' instead of a re-pair prompt")") ;; esac
  note=$(add "$note" "$(healthy)")
  finish network-stale-credential "$note" "state: ${st:-none}"
  nw_seed
}

# nw_visible WHEN -> tagged note if the app shows no connection state for the selected helper host
nw_visible() {
  local xy st
  alive || { tag S2 I10 "app not running ($1)"; return; }
  xy=$(ui_xy "Device: $NW_NAME")
  [ -n "$xy" ] || { tag S2 I8 "pill for $NW_NAME missing ($1)"; return; }
  sh_ input tap $xy; pause 2
  st=$(ui_text | python3 -I -c '
import re, sys
xml = sys.stdin.read()
vals = re.findall(r"(?:text|content-desc)=\"([^\"]*)\"", xml)
hit = [v for v in vals if re.search(sys.argv[1], v)]
print(hit[0] if hit else "")' "$NW_STATES")
  shot "network-$1"
  sh_ input keyevent KEYCODE_BACK; pause 1
  [ -n "$st" ] || { tag S2 I8 "no connection state shown in the host sheet ($1)"; return; }
  echo "$1: $st" >> "$OUT/.nw-states"
}

nw_case() { # name, "break" commands, "fix" commands
  local name=$1 note="" t
  mark; : > "$OUT/.nw-states"
  launch; ui_wait "Device: $NW_NAME" 20; pause 2
  note=$(add "$note" "$(nw_visible before)")
  eval "$2"; pause 8
  note=$(add "$note" "$(nw_visible during)")
  eval "$3"; pause 10
  note=$(add "$note" "$(nw_visible after)")
  note=$(add "$note" "$(healthy)")
  t=$(paste -sd';' "$OUT/.nw-states" | sed 's/;/; /g')
  finish "network-$name" "$note" "states: ${t:-none}"
}

nw_flap() {
  for _ in $(seq 15); do
    case $(rnd 3) in
      0) sh_ svc wifi disable ;; 1) sh_ svc data disable ;; 2) sh_ cmd connectivity airplane-mode enable ;;
    esac
    sleep "0.$(rnd 9)"
    sh_ cmd connectivity airplane-mode disable; sh_ svc wifi enable; sh_ svc data enable
    sleep "0.$(rnd 9)"
  done
}

scenario_network() {
  if ! sh_ run-as "$PKG" true >/dev/null 2>&1; then
    pending network-wifi-off "needs a debuggable build (run-as) to seed a helper host"; return
  fi
  local helper="no helper (helper/build/MoteHelper.app missing): app retries 10.0.2.2:$NW_PORT"
  nw_helper_start && helper="dry-run helper on :$NW_PORT, hostId $NW_ID"
  log "network: $helper"
  nw_seed
  local s
  for s in wifi-off airplane data-off all-off flap stale-credential; do
    over && { record "network-$s" SKIP 0 0 "budget exhausted"; continue; }
    case $s in
      wifi-off) nw_case "$s" 'sh_ svc wifi disable' 'sh_ svc wifi enable' ;;
      airplane) nw_case "$s" 'sh_ cmd connectivity airplane-mode enable' 'sh_ cmd connectivity airplane-mode disable' ;;
      data-off) nw_case "$s" 'sh_ svc data disable' 'sh_ svc data enable' ;;
      all-off) nw_case "$s" 'sh_ svc wifi disable; sh_ svc data disable' 'sh_ svc wifi enable; sh_ svc data enable' ;;
      flap) nw_case "$s" nw_flap 'pause 1' ;;
      stale-credential) nw_stale_credential ;;
    esac
    settle "network-$s"
  done
  nw_helper_stop
  nw_unseed # later scenarios start from the Bluetooth remote
}
