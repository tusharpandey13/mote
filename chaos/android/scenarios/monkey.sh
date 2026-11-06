# shellcheck shell=bash
# Monkey: several seeds, unthrottled and throttled, with a meminfo sampler for leak detection.
# Event counts scale with the budget; at the default 20m budget the total is >= 50k events.

meminfo_sampler() { # runs in background; one line per sample: epoch pss_kb java_kb activities views
  while :; do
    sh_ dumpsys meminfo "$PKG" 2>/dev/null | tr -d '\r' | awk -v t="$(now)" '
      /TOTAL PSS:/ {pss=$3} /^ *TOTAL +[0-9]/ && !pss {pss=$2}
      /Java Heap:/ {java=$3} /Activities:/ {act=$NF} /Views:/ {views=$2}
      END { if (pss) print t, pss, java, act, views }' >> "$OUT/meminfo.tsv"
    sleep 10
  done
}

monkey_run() { # seed events throttle
  local seed=$1 ev=$2 thr=$3 name="monkey-s$1-t$3"
  mark; launch; pause 2
  log "monkey seed=$seed events=$ev throttle=${thr}ms"
  # syskeys off: HOME/BACK spam just leaves the app; appswitch keeps it in the package.
  sh_ monkey -p "$PKG" -s "$seed" --throttle "$thr" \
      --pct-syskeys 0 --pct-appswitch 2 --pct-touch 45 --pct-motion 30 --pct-nav 5 --pct-majornav 5 \
      --ignore-crashes --ignore-timeouts --ignore-security-exceptions --monitor-native-crashes \
      -v "$ev" > "$OUT/logs/$name.monkey.txt" 2>&1
  local note=""
  grep -q 'Monkey finished' "$OUT/logs/$name.monkey.txt" || note="monkey aborted early"
  launch; alive || note="${note:+$note; }app not alive after run"
  finish "$name" "$note"
}

scenario_monkey() {
  local base=$1 budget_s per ev
  budget_s=$(left)
  # ~45% of the remaining budget; emulator monkey sustains roughly 150 unthrottled events/s.
  per=$(( budget_s * 45 / 100 ))
  ev=$(( per * 150 / 4 )); [ "$ev" -gt 20000 ] && ev=20000; [ "$ev" -lt 500 ] && ev=500
  : > "$OUT/meminfo.tsv"
  meminfo_sampler & local sampler=$!
  monkey_run "$base" "$ev" 0
  monkey_run $((base + 1)) "$ev" 0
  monkey_run $((base + 2)) "$ev" 0
  # throttled run: slower, lets animations, sheets and the service settle between events
  over || monkey_run $((base + 3)) $(( ev / 4 )) 25
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
  # Activities after the app is restarted in place: >1 instance means a leaked Activity.
  launch; pause 2
  local acts
  acts=$(sh_ dumpsys meminfo "$PKG" | tr -d '\r' | awk '/Activities:/ {print $NF}')
  [ "$growth" -gt 30 ] && [ $(( last - first )) -gt 20000 ] && note="PSS grew ${growth}% (${first}k->${last}k)"
  [ "${acts:-0}" -gt 1 ] && note="${note:+$note; }$acts live Activities"
  local status=PASS; [ -n "$note" ] && status=FAIL
  record leak-meminfo "$status" 0 0 "${note:-PSS ${first}k->${last}k (${growth}%), activities=${acts:-?}, samples=$n}"
}
