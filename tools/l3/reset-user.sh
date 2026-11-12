#!/bin/bash
# Wipes every trace of Mote Helper for the test user: TCC grants, Keychain items, app support, app, defaults.
# Run as motetest, or `sudo -u motetest tools/l3/reset-user.sh`. Refuses to run as anyone else (it deletes things).
set -u
if [ "$(id -un)" != "motetest" ]; then
  echo "refusing: this must run as the user motetest, not $(id -un)." >&2
  exit 2
fi
# Under `sudo -u`, $HOME may still be the caller's. Never trust it. (MOTE_L3_HOME: tests only.)
H="${MOTE_L3_HOME:-$(eval echo "~motetest")}"
case "$H" in /*) ;; *) echo "refusing: cannot resolve motetest's home ($H)" >&2; exit 2 ;; esac
[ -d "$H" ] || { echo "refusing: $H is not a directory" >&2; exit 2; }
export HOME="$H"
ID=dev.mote.helper

echo "Quitting Mote Helper"
pkill -U "$(id -u)" -x MoteHelper 2>/dev/null || true
sleep 1

echo "Resetting TCC grants for $ID"
tccutil reset All "$ID" || echo "  (tccutil failed; check System Settings > Privacy for leftovers)"

echo "Deleting Keychain items (service $ID, every account)"
n=0
while [ "$n" -lt 50 ] && security delete-generic-password -s "$ID" >/dev/null 2>&1; do n=$((n + 1)); done
echo "  deleted $n item(s)"

echo "Removing app support, app and defaults"
rm -rf "$H/Library/Application Support/$ID" "$H/Applications/MoteHelper.app"
defaults delete "$ID" >/dev/null 2>&1 || true
echo "Done. Clean state for $ID."
