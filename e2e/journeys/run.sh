#!/usr/bin/env bash
# User-journey acceptance tests on a headless emulator. See e2e/journeys/README.md.
#   e2e/journeys/run.sh all                 every journey (docs/journeys/*.md)
#   e2e/journeys/run.sh wifi-helper         one journey (its JourneyClass, every path)
#   e2e/journeys/run.sh wifi-helper#main    one path (test method)
#   e2e/journeys/run.sh --level L2 [install-helper J4 ...]   the L2 rows of the core journeys J1-J7 (docs/journeys/README.md):
#                                           their cited tests (android in ONE gradle call, helper, node, e2e), then REPORT.md
# Env: MJ_SERIAL (default emulator-5562; only emulator-* serials are accepted), MJ_KEEP_EMULATOR=1
# (leave it running), MJ_REBUILD_HELPER=1, MJ_NO_BUILD=1 (skip gradle assemble; still runs tests).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
HERE="$ROOT/e2e/journeys"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
ADB="$SDK/platform-tools/adb"
SERIAL="${MJ_SERIAL:-emulator-5562}"
EMU_PORT="${SERIAL#emulator-}"
AVD=mote-journeys
IMAGE="system-images;android-35;google_apis;arm64-v8a"
OUT="${MJ_OUT:-/tmp/mote-journeys}"
CTL_PORT="${MJ_CONTROLLER_PORT:-8799}"
LEVEL=""; [ "${1:-}" = --level ] && { LEVEL="${2:?--level needs a value, e.g. L2}"; shift 2; }
TARGET="${1:-all}"

die() { echo "run.sh: $*" >&2; exit 2; }
pascal() { echo "$1" | awk -F- '{for(i=1;i<=NF;i++) printf "%s%s", toupper(substr($i,1,1)), substr($i,2)}'; }

# ---- selector: fail before booting anything ----
if [ -n "$LEVEL" ]; then
  # every cited class / method / file / title must exist (reads only the specs and sources)
  MJ_OUT="$OUT" node "$HERE/l2.mjs" plan --level "$LEVEL" "$@" || die "invalid cites in the $LEVEL rows (see above)"
elif [ "$TARGET" != all ] && [[ "$TARGET" == *,* ]]; then
  # a,b,c: several journeys in ONE gradle invocation, in that order (reproduces order-dependent leaks)
  for id in ${TARGET//,/ }; do [ -f "$ROOT/android/app/src/androidTest/journeys/dev/mote/remote/journeys/$(pascal "$id")Journey.kt" ] || die "no journey class $(pascal "$id")Journey"; done
elif [ "$TARGET" != all ]; then
  id="${TARGET%%#*}"; method=""; [[ "$TARGET" == *"#"* ]] && method="#${TARGET#*#}"
  # The selector must name a real class (and @Test method): otherwise Gradle runs nothing and exits 0.
  cls="$ROOT/android/app/src/androidTest/journeys/dev/mote/remote/journeys/$(pascal "$id")Journey.kt"
  [ -f "$cls" ] || die "no journey class $(pascal "$id")Journey (expected ${cls#"$ROOT"/})"
  [ -z "$method" ] || grep -Eq "@Test[[:space:]]+fun[[:space:]]+${method#\#}[[:space:]]*\(" "$cls" \
    || die "no @Test method '${method#\#}' in $(pascal "$id")Journey"
  # A class without a spec (e.g. np-notification) still runs; REPORT.md has no section for it (report.mjs
  # walks the specs), so its outcome is the gradle exit code, junit XML and results.jsonl.
  [ -f "$ROOT/docs/journeys/$id.md" ] || echo "run.sh: warning: no spec docs/journeys/$id.md (running $(pascal "$id")Journey anyway; not in REPORT.md, see gradle exit / $OUT/junit)" >&2
fi

# ---- guard: only ever an emulator ----
[[ "$SERIAL" =~ ^emulator-[0-9]+$ ]] || die "refusing serial '$SERIAL': only emulator-* targets are allowed"
export ANDROID_SERIAL="$SERIAL"   # gradle installs/runs ONLY on this device
guard_device() {
  local qemu; qemu="$("$ADB" -s "$SERIAL" shell getprop ro.kernel.qemu 2>/dev/null | tr -d '\r')"
  [ "$qemu" = "1" ] || qemu="$("$ADB" -s "$SERIAL" shell getprop ro.boot.qemu 2>/dev/null | tr -d '\r')"
  [ "$qemu" = "1" ] || die "$SERIAL is not an emulator (ro.kernel.qemu != 1): aborting"
}

# ---- one run at a time (shared emulator, gradle, controller) ----
LOCK=/tmp/mote-journeys.lock
for _ in $(seq 1 720); do mkdir "$LOCK" 2>/dev/null && break; sleep 5; done
[ -d "$LOCK" ] || die "could not get $LOCK"
STARTED_EMU=0; CTL_PID=""
cleanup() {
  [ -n "$CTL_PID" ] && kill "$CTL_PID" 2>/dev/null || true
  if [ "$STARTED_EMU" = 1 ] && [ "${MJ_KEEP_EMULATOR:-0}" != 1 ]; then
    "$ADB" -s "$SERIAL" emu kill >/dev/null 2>&1 || true
    # emu kill is async: wait until the device is gone, or the next run sees a dying "device" and fails its first adb shell
    for _ in $(seq 1 30); do "$ADB" devices | grep -q "^$SERIAL[[:space:]]" || break; sleep 1; done
  fi
  rmdir "$LOCK" 2>/dev/null || true
}
trap cleanup EXIT

# ---- emulator ----
if ! "$ADB" devices | grep -q "^$SERIAL[[:space:]]*device"; then
  if ! "$SDK/emulator/emulator" -list-avds | grep -qx "$AVD"; then
    echo no | "$SDK/cmdline-tools/latest/bin/avdmanager" create avd -n "$AVD" -k "$IMAGE" -d pixel_7 >/dev/null
  fi
  echo "booting $AVD on $SERIAL (headless)"
  nohup "$SDK/emulator/emulator" -avd "$AVD" -no-window -gpu guest -no-audio -no-snapshot -no-boot-anim -port "$EMU_PORT" \
    >"/tmp/mote-journeys-emulator.log" 2>&1 &
  STARTED_EMU=1
  "$ADB" -s "$SERIAL" wait-for-device
  for _ in $(seq 1 180); do [ "$("$ADB" -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ] && break; sleep 2; done
fi
guard_device
for k in window_animation_scale transition_animation_scale animator_duration_scale; do "$ADB" -s "$SERIAL" shell settings put global $k 0; done
"$ADB" -s "$SERIAL" shell svc wifi enable

# ---- helper build ----
HELPER="$ROOT/helper/build/MoteHelper.app/Contents/MacOS/MoteHelper"
if [ ! -x "$HELPER" ] || [ "${MJ_REBUILD_HELPER:-0}" = 1 ]; then (cd "$ROOT" && MOTE_ADHOC=1 ARCHS=arm64 ./helper/build.sh >/tmp/mote-journeys-helper-build.log 2>&1) || die "helper build failed: /tmp/mote-journeys-helper-build.log"; fi

# ---- controller ----
mkdir -p "$OUT"
if ! curl -fsS -X POST "http://127.0.0.1:$CTL_PORT/ping" >/dev/null 2>&1; then
  MJ_OUT="$OUT" MJ_CONTROLLER_PORT="$CTL_PORT" MOTE_HELPER_BIN="$HELPER" node "$HERE/controller.mjs" >>"$OUT/controller.log" 2>&1 &
  CTL_PID=$!
  for _ in $(seq 1 50); do curl -fsS -X POST "http://127.0.0.1:$CTL_PORT/ping" >/dev/null 2>&1 && break; sleep 0.2; done
fi
curl -fsS -X POST "http://127.0.0.1:$CTL_PORT/run" >/dev/null || die "controller on :$CTL_PORT has no /run (an old controller is still running? kill it)"

# ---- which tests ----
# Ordered journeys keep app data between their tests (each test is still a fresh process), so a
# later test gets a real cold start after an earlier one paired: they run in their own Gradle
# invocation with clearPackageData=false (Gradle reinstalls the app, so they start from a clean install).
ORDERED="core-connect"
P=-Pandroid.testInstrumentationRunnerArguments
RUNS=()   # one entry per gradle invocation: space-separated -P args
if [ -n "$LEVEL" ]; then
  :   # handled below
elif [ "$TARGET" = all ]; then
  not=""; for o in $ORDERED; do not="${not:+$not,}dev.mote.remote.journeys.$(pascal "$o")Journey"; done
  RUNS+=("$P.package=dev.mote.remote.journeys $P.notClass=$not")
  for o in $ORDERED; do RUNS+=("$P.class=dev.mote.remote.journeys.$(pascal "$o")Journey $P.clearPackageData=false"); done
  : >"$OUT/results.jsonl"
elif [[ "$TARGET" == *,* ]]; then
  cl=""; for id in ${TARGET//,/ }; do cl="${cl:+$cl,}dev.mote.remote.journeys.$(pascal "$id")Journey"; done
  RUNS+=("$P.class=$cl")
else
  extra=""; [[ " $ORDERED " == *" $id "* ]] && extra=" $P.clearPackageData=false"
  RUNS+=("$P.class=dev.mote.remote.journeys.$(pascal "$id")Journey$method$extra")
  # drop this journey's previous results (all paths, or the one path)
  [ -f "$OUT/results.jsonl" ] && node -e '
    const fs=require("fs"),[f,id,m]=process.argv.slice(1);
    const keep=fs.readFileSync(f,"utf8").split("\n").filter(Boolean).filter(l=>{const p=JSON.parse(l).path||"";return m?true:!(p===id||p.startsWith(id+"."))});
    fs.writeFileSync(f,keep.join("\n")+(keep.length?"\n":""));' "$OUT/results.jsonl" "$id" "$method"
fi

# ---- build + run ----
cd "$ROOT/android"
RC=0; : >"$OUT/gradle.log"
if [ -n "$LEVEL" ]; then
  # one Gradle invocation: JVM unit tests (--tests) and instrumented tests (pinned to $SERIAL by ANDROID_SERIAL)
  GARGS=(); while IFS= read -r a; do GARGS+=("$a"); done < <(MJ_OUT="$OUT" node "$HERE/l2.mjs" gradle-args)
  rm -rf app/build/test-results/testDevUnitTest app/build/outputs/androidTest-results/connected
  # SweepDiscoveryTest needs a dry-run helper on the host at a fixed port
  "$HELPER" --dry-run --headless --memory-store --no-ble --fake-proximity --port 8795 >"$OUT/l2/sweep-helper.log" 2>&1 &
  SWEEP_PID=$!
  set +e
  ./gradlew --max-workers=2 -q "${GARGS[@]}" "$P.mjController=http://10.0.2.2:$CTL_PORT" "$P.moteSweepPort=8795" >"$OUT/l2/gradle.log" 2>&1
  RC=$?
  set -e
  kill "$SWEEP_PID" 2>/dev/null || true
  tail -15 "$OUT/l2/gradle.log"
  mkdir -p "$OUT/junit"; cp -R app/build/outputs/androidTest-results/connected/. "$OUT/junit/" 2>/dev/null || true
  curl -fsS -X POST "http://127.0.0.1:$CTL_PORT/helper/stopall" >/dev/null 2>&1 || true
  MJ_OUT="$OUT" node "$HERE/l2.mjs" collect
  MJ_OUT="$OUT" node "$HERE/l2.mjs" run-others
  RUNS=()
fi
for run in ${RUNS[@]+"${RUNS[@]}"}; do
  set +e
  # shellcheck disable=SC2086 # $run is a list of -P args
  ./gradlew --max-workers=2 -q -PmoteJourneys :app:connectedDevAndroidTest $run \
    "$P.mjController=http://10.0.2.2:$CTL_PORT" 2>&1 | tee -a "$OUT/gradle.log" | grep -v '^$' | tail -40
  rc=${PIPESTATUS[0]}
  set -e
  [ "$rc" = 0 ] || RC=$rc
  mkdir -p "$OUT/junit"; cp -R "$ROOT/android/app/build/outputs/androidTest-results/connected/." "$OUT/junit/" 2>/dev/null || true
  curl -fsS -X POST "http://127.0.0.1:$CTL_PORT/helper/stopall" >/dev/null 2>&1 || true   # kept helpers of ordered journeys
done

# ---- report ----
node "$HERE/report.mjs" --out "$OUT" --specs "$ROOT/docs/journeys" --write "$HERE/REPORT.md" ${LEVEL:+--strict} || { [ -n "$LEVEL" ] && RC=1; }

echo "report: $HERE/REPORT.md  screenshots: $OUT/<path>/<step>.png  (gradle exit $RC)"
exit "$RC"
