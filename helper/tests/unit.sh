#!/usr/bin/env bash
# Helper unit tests (helper/tests/MoteHelperTests, Swift Testing). Works with only the Command Line Tools (no Xcode, no XCTest).
# Plain `swift test` can't: SwiftPM only runs Swift Testing when the compiler is given the framework's search path on the command
# line (without it the build fails with "no such module 'Testing'", or, if the path is set in Package.swift, builds and runs ZERO tests).
# This script passes the path, and fails if no test ran.
set -euo pipefail
HERE="$(cd "$(dirname "$0")/.." && pwd)"
args=()
for DEV in "$(xcode-select -p 2>/dev/null || true)" /Library/Developer/CommandLineTools; do
  if [ -d "$DEV/Library/Developer/Frameworks/Testing.framework" ]; then
    F="$DEV/Library/Developer/Frameworks"
    args=(-Xswiftc -F -Xswiftc "$F" -Xlinker -F -Xlinker "$F" -Xlinker -rpath -Xlinker "$F"
          -Xlinker -rpath -Xlinker "$DEV/Library/Developer/usr/lib")
    break
  fi
done
LOG="$(mktemp -t mote-unit)"
set +e
swift test --package-path "$HERE" --scratch-path "$HERE/.build/test" ${args[@]+"${args[@]}"} "$@" 2>&1 | tee "$LOG"
rc=${PIPESTATUS[0]}
set -e
if [ "$rc" -eq 0 ] && ! grep -q "Test run with [1-9][0-9]* tests" "$LOG"; then echo "unit.sh: no tests ran" >&2; rc=1; fi
rm -f "$LOG"
exit "$rc"
