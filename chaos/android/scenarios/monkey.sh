# shellcheck shell=bash
# Monkey: several seeds, unthrottled and throttled, with a meminfo sampler for leak detection.
# Event counts scale with the budget; at the default 20m budget the total is >= 50k events.

meminfo_sampler() { # runs in background; one line per sample: epoch pss_kb java_kb activities
  while :; do
    sh_ dumpsys meminfo "$PKG" 2>/dev/null | tr -d '\r' | awk -v t="$(now)" '
      /TOTAL PSS:/ {pss=$3} /^ *TOTAL +[0-9]/ && !pss {pss=$2}
      /Java Heap:/ {java=$3} /Activities:/ {act=$NF}
      END { if (pss) print t, pss, java, act }' >> "$OUT/meminfo.tsv"
    sleep 10
  done
}

monkey_run() { # seed events throttle
  local seed=$1 ev=$2 thr=$3 name="monkey-s$1-t$3"
  mark; launch; pause 2
  log "monkey seed=$seed events=$ev throttle=${thr}ms"
  # syskeys off: HOME/BACK spam just leaves the app; appswitch keeps it in the package.
  local log_f="$OUT/logs/$name.monkey.txt"
  sh_ monkey -p "$PKG" -s "$seed" --throttle "$thr" \
      --pct-syskeys 0 --pct-appswitch 2 --pct-touch 45 --pct-motion 30 --pct-nav 5 --pct-majornav 5 \
      --ignore-crashes --ignore-timeouts --ignore-security-exceptions --monitor-native-crashes \
      -v "$ev" > "$log_f" 2>&1 &
  local mpid=$! size=-1 idle=0 info=""
  # Watchdog: the monkey tool itself sometimes hangs after "Injection Failed" while the app sits idle.
  # No new output for 90 s -> kill it on the device and record it as a tool stall, not an app failure.
  while kill -0 "$mpid" 2>/dev/null; do
    sleep 5
    local cur; cur=$(wc -c < "$log_f" | tr -d ' ')
    if [ "$cur" = "$size" ]; then idle=$((idle + 5)); else idle=0; size=$cur; fi
    if [ "$idle" -ge 90 ]; then
      sh_ pkill -f com.android.commands.monkey >/dev/null 2>&1; info="monkey tool stalled after $(grep -c '^:Sending' "$log_f") events, killed"
      break
    fi
  done
  wait "$mpid" 2>/dev/null
  local note=""
  [ -z "$info" ] && ! grep -q 'Monkey finished' "$log_f" && note="monkey aborted early"
  launch; alive || note="${note:+$note; }app not alive after run"
  finish "$name" "$note" "$info"
}

scenario_monkey() {
  local base=$1 budget_s per ev
  budget_s=$(left)
  # Monkey runs last and takes ~85% of what is left, split over 3 unthrottled runs; the emulator
  # sustains ~75 events/s. MONKEY_MIN (default 50000) is a floor on the total, even if that
  # overruns the budget by a minute or two; MONKEY_MIN=0 makes the budget strict.
  local min=${MONKEY_MIN:-50000}
  per=$(( budget_s * 85 / 100 / 3 ))
  ev=$(( per * 75 )); [ "$ev" -gt 30000 ] && ev=30000
  [ $(( ev * 13 / 4 )) -lt "$min" ] && ev=$(( min * 4 / 13 + 1 )) # 3 runs + a quarter-size throttled run
  [ "$ev" -lt 500 ] && ev=500
  : > "$OUT/meminfo.tsv"
  meminfo_sampler & local sampler=$!
  monkey_run "$base" "$ev" 0
  monkey_run $((base + 1)) "$ev" 0
  monkey_run $((base + 2)) "$ev" 0
  # throttled run: slower, lets animations, sheets and the service settle between events
  monkey_run $((base + 3)) $(( ev / 4 )) 25
  kill "$sampler" 2>/dev/null; wait "$sampler" 2>/dev/null
  leak_check
}

# Flags PSS growth between the first and last quarter of samples, and leaked Activity instances.
leak_check() {
  mark
  local f="$OUT/meminfo.tsv" n note=""
  n=$(wc -l < "$f" | tr -d ' ')
  if [ "$n" -lt 8 ]; then record leak-meminfo SKIP 0 0 "only $n samples"; return; fi
  local q=$(( n / 4 )) first last
  first=$(head -n "$q" "$f" | awk '{s+=$2} END {printf "%d", s/NR}')
  last=$(tail -n "$q" "$f" | awk '{s+=$2} END {printf "%d", s/NR}')
  local growth=$(( (last - first) * 100 / first ))
  # Live Activity objects after a forced GC (SIGUSR1, debuggable builds) vs. ActivityRecords the
  # system still holds: more objects than records means a destroyed Activity is still referenced.
  launch; pause 2
  sh_ run-as "$PKG" kill -10 "$(pid)" >/dev/null 2>&1; pause 4
  local acts recs
  acts=$(sh_ dumpsys meminfo "$PKG" | tr -d '\r' | awk '/Activities:/ {print $NF}')
  recs=$(sh_ dumpsys activity activities | tr -d '\r' | awk -v p="$PKG/" '/\* Hist/ && index($0, p) {n++} END {print n+0}')
  [ "$growth" -gt 30 ] && [ $(( last - first )) -gt 20000 ] && note="PSS grew ${growth}% (${first}k->${last}k)"
  [ "${acts:-0}" -gt "${recs:-1}" ] && note="${note:+$note; }$acts live Activities for $recs records"
  local status=PASS; [ -n "$note" ] && status=FAIL
  record leak-meminfo "$status" 0 0 "${note:-PSS ${first}k->${last}k (${growth}%), activities=${acts:-?}/${recs:-?} records, samples=$n}"
}
