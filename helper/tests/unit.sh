#!/usr/bin/env bash
# Helper unit tests (helper/tests/MoteHelperTests, Swift Testing). With Xcode, plain `swift test` works; with only the
# Command Line Tools, Testing.framework lives outside the default search paths, so point at it.
set -euo pipefail
HERE="$(cd "$(dirname "$0")/.." && pwd)"
args=()
DEV="$(xcode-select -p 2>/dev/null || true)"
if [ -d "$DEV/Library/Developer/Frameworks/Testing.framework" ]; then
  F="$DEV/Library/Developer/Frameworks"
  args=(-Xswiftc -F -Xswiftc "$F" -Xlinker -F -Xlinker "$F" -Xlinker -rpath -Xlinker "$F"
        -Xlinker -rpath -Xlinker "$DEV/Library/Developer/usr/lib")
fi
exec swift test --package-path "$HERE" --scratch-path "$HERE/.build/test" "${args[@]}" "$@"
