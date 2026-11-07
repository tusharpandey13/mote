#!/usr/bin/env bash
# Physical-device chaos: the same suite plus real Bluetooth HID link chaos, which an emulator can't do.
# NOT run in CI or by agents. Needs a phone with USB debugging and a paired Mac running nothing important
# (HID reports are real input: keep the Mac on a scratch desktop, or unpair it before running monkey).
#
#   chaos/android/physical.sh --serial <adb-serial> --seed 1 --budget 30m
#
# Differences from the emulator run:
#   * --allow-physical lifts run.sh's emulator guard (serial emulator-* and qemu/ranchu/goldfish props)
#     and prints a warning banner.
#   * monkey is excluded by default: with a connected host it would type and click on the Mac.
#     Add it explicitly with ONLY=storage,lifecycle,resource,bluetooth,monkey and the Mac unpaired.
#   * device settings are snapshotted at start and restored to that snapshot after every scenario and
#     on exit/Ctrl-C; any leftover difference fails the run (I9). The network scenario seeds a fake
#     helper host at 10.0.2.2, which a phone can't reach: it only checks the visible state.
#   * Bluetooth toggles drop and re-establish the HID link with the Mac; watch the Mac's
#     Bluetooth menu for stuck "Connected" states and the phone notification for stale text.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
SERIAL="" ARGS=()
while [ $# -gt 0 ]; do
  case $1 in --serial) SERIAL=$2; ARGS+=("$1" "$2"); shift 2 ;; *) ARGS+=("$1"); shift ;; esac
done
[ -n "$SERIAL" ] || { echo "--serial is required" >&2; exit 2; }
echo "About to run chaos on physical device $SERIAL. HID input may reach the paired computer."
read -r -p "Type the serial again to continue: " confirm
[ "$confirm" = "$SERIAL" ] || { echo "aborted"; exit 1; }
exec "$HERE/run.sh" --allow-physical --only "${ONLY:-storage,lifecycle,resource,bluetooth}" "${ARGS[@]}"
