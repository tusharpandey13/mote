#!/bin/sh
# Process-death test (black box, host-driven). An instrumented test can't do this: `am kill` would kill
# the instrumentation together with the app. Steps: open Keys, type unsent text, go Home, `am kill`
# the backgrounded app, relaunch it, and expect the mode and the text to be restored from saved state.
# Needs the debug APK installed and an adapter that can be enabled (an emulator works).
# Usage: ANDROID_SERIAL=emulator-5554 android/scripts/process-death-test.sh
set -eu
PKG=dev.mote.remote
TEXT=survivesdeath
adb() { command adb ${ANDROID_SERIAL:+-s "$ANDROID_SERIAL"} "$@"; }
fail() { echo "FAIL: $*"; exit 1; }

dump() { adb shell uiautomator dump /sdcard/mote-ui.xml >/dev/null && adb shell cat /sdcard/mote-ui.xml; }

# Centre of the first node whose attribute $1 equals $2, read from the UI dump on stdin.
centre() {
  python3 -I -c '
import re, sys
attr, val = sys.argv[1], sys.argv[2]
for n in re.findall(r"<node [^>]*>", sys.stdin.read()):
    if re.search(r"\b%s=\"%s\"" % (attr, re.escape(val)), n):
        l, t, r, b = map(int, re.search(r"bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"", n).groups())
        print((l + r) // 2, (t + b) // 2); break
' "$1" "$2"
}

wait_for() { # attr value [tries]
  i=0
  while [ $i -lt "${3:-20}" ]; do
    xy=$(dump | centre "$1" "$2")
    [ -n "$xy" ] && { echo "$xy"; return 0; }
    i=$((i + 1)); sleep 1
  done
  return 1
}

launch() { adb shell am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n $PKG/.MainActivity >/dev/null; }

adb shell pm clear $PKG >/dev/null
for p in BLUETOOTH_CONNECT BLUETOOTH_ADVERTISE BLUETOOTH_SCAN POST_NOTIFICATIONS; do
  adb shell pm grant $PKG android.permission.$p 2>/dev/null || true
done
adb shell cmd bluetooth_manager enable >/dev/null 2>&1 || adb shell svc bluetooth enable >/dev/null 2>&1 || true

launch
n=0; xy=""
while [ -z "$xy" ] && [ $n -lt 5 ]; do # retry: the first tap can land while the UI is still settling
  k=$(wait_for text Keys) || fail "remote UI not shown (is Bluetooth on?)"
  adb shell input tap $k
  xy=$(wait_for class android.widget.EditText 5) || xy=""
  n=$((n + 1))
done
[ -n "$xy" ] || fail "Keys text box not found"
adb shell input tap $xy
sleep 1
adb shell input text $TEXT
wait_for text $TEXT 5 >/dev/null || fail "text not typed"

adb shell input keyevent KEYCODE_HOME
sleep 3 # onStop saves state; the foreground service stops itself with no host connected
pid=$(adb shell pidof $PKG || true)
[ -n "$pid" ] || fail "app process not running before kill"
adb shell am kill $PKG
i=0; while [ -n "$(adb shell pidof $PKG || true)" ] && [ $i -lt 20 ]; do sleep 0.5; i=$((i + 1)); done
[ -z "$(adb shell pidof $PKG || true)" ] || fail "am kill did not kill pid $pid"

launch # same launcher intent: brings the existing task back
wait_for text $TEXT >/dev/null || fail "unsent text not restored after process death"
dump | centre content-desc "Send text" | grep -q . || fail "not in Keys mode after restore"
[ "$(adb shell pidof $PKG)" != "$pid" ] || fail "process was not recreated"
echo "PASS: mode and text restored after process death (pid $pid killed)"
