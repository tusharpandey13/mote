# shellcheck shell=bash
# Storage chaos: delete / corrupt / poison the Preferences DataStore between launches.
# Needs a debuggable build (run-as). The app must come up with defaults instead of crashing.

DS=files/datastore/mote.preferences_pb
HERE_PB="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/prefs_pb.py"

# put_store LOCAL_FILE : replace the app's DataStore file while the app is stopped
put_store() {
  sh_ am force-stop "$PKG"
  a push "$1" /data/local/tmp/mote-ds >/dev/null
  sh_ run-as "$PKG" sh -c "'mkdir -p files/datastore && cat /data/local/tmp/mote-ds > $DS'"
  sh_ rm -f /data/local/tmp/mote-ds
}

st_case() { # name, then a function that writes the payload to $1
  local name=$1 gen=$2 f="$OUT/tmp-ds"
  mark
  "$gen" "$f"
  put_store "$f"
  launch; pause 3
  shot "storage-$name"
  # visit every mode so the macro list is composed
  local w h; read -r w h <<<"$(sh_ wm size | tr -d '\r' | awk -F'[ x]' '/Physical/{print $3, $4}')"
  for i in 1 2 3 4; do sh_ input tap $(( w * (2 * i - 1) / 8 )) $(( h - h / 20 )); sleep 0.5; done
  # relaunch twice: recovery must persist (no crash loop on the second start)
  sh_ am force-stop "$PKG"; launch; pause 2
  finish "storage-$name" "$(healthy)"
}

g_garbage()  { head -c 4096 /dev/urandom > "$1"; }
g_truncate() { python3 -I "$HERE_PB" macros_json='{"v":1,"macros":[]}' theme=DARK | head -c 9 > "$1"; }
g_badjson()  { python3 -I "$HERE_PB" macros_json='{"v":1,"macros":[{"id":' theme=PURPLE selected_host=zz > "$1"; }
g_hostile()  { # valid JSON with hostile values: unknown keys/icons/mods, empty names, negative delays, huge text
  local big; big=$(head -c 20000 /dev/zero | tr '\0' 'x')
  python3 -I "$HERE_PB" \
    selected_host='not-a-mac' theme=SYSTEM \
    macros_json='{"v":99,"macros":[{"id":"a","name":"","icon":"nope","star":true,"steps":[{"t":"chord","mods":["hyper"],"key":"nokey"},{"t":"delay","ms":-5},{"t":"bogus"}]},{"id":"a","name":"dup id","star":true,"kind":"spaces"},{"id":"b","name":"'"$big"'","star":true,"steps":[{"t":"text","s":"ü😀"}]}]}' > "$1"
}

scenario_storage() {
  if ! sh_ run-as "$PKG" true >/dev/null 2>&1; then
    record storage SKIP 0 0 "build not debuggable (run-as unavailable)"; return
  fi
  launch; pause 2 # make sure the store exists once
  st_case garbage g_garbage
  st_case truncated g_truncate
  st_case bad-json g_badjson
  st_case hostile-values g_hostile
  # delete: remove the file (and the directory) entirely
  mark; sh_ am force-stop "$PKG"; sh_ run-as "$PKG" rm -rf files/datastore
  launch; pause 2; sh_ am force-stop "$PKG"; launch; pause 2
  finish storage-deleted "$(healthy)"
  rm -f "$OUT/tmp-ds"
}
