#!/bin/bash
# Tests setup-user.sh, reset-user.sh and run.sh with PATH-stubbed commands that only log their argv.
# Nothing real is run: no sudo, sysadminctl, tccutil, security, ssh or install.sh.   Usage: tools/l3/test.sh
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
W="$(mktemp -d)"; trap 'rm -rf "$W"' EXIT
mkdir -p "$W/bin" "$W/home/Library/Application Support/dev.mote.helper" "$W/home/Applications/MoteHelper.app" "$W/out"
LOG="$W/calls.log"; : > "$LOG"
fails=0
# ok NAME CMD...: passes when CMD succeeds
ok() { local n="$1"; shift; if "$@"; then echo "ok   $n"; else echo "FAIL $n"; fails=$((fails + 1)); fi; }
has() { grep -q -- "$1" "${2:-$LOG}"; }
hasnt() { ! grep -qE -- "$1" "${2:-$LOG}"; }

# Stubs: log argv. id answers from $STUB_USER; `id -u motetest` succeeds only if $STUB_EXISTS=1.
for c in sudo sysadminctl tccutil security curl ssh defaults pkill dseditgroup nc du; do
  printf '#!/bin/bash\necho "%s $*" >> "%s"\n[ "%s" = security ] && [ -f "%s/sec" ] && exit 1\nexit 0\n' "$c" "$LOG" "$c" "$W" > "$W/bin/$c"
done
cat > "$W/bin/id" <<EOS
#!/bin/bash
echo "id \$*" >> "$LOG"
case "\$*" in "-un") echo "\${STUB_USER:-motetest}" ;; "-u motetest") [ "\${STUB_EXISTS:-0}" = 1 ] || exit 1 ;; *) echo 501 ;; esac
EOS
cat > "$W/bin/MoteHelper" <<'EOS'
#!/bin/bash
echo "MoteHelper $*" >> "$STUB_LOG"
echo '{"keychain":{"readResult":"found","otherAccounts":[],"partitionMatchesSigning":true},"signing":{"quarantined":false,"identity":"mote-local"},"permissions":{"bluetooth":{"apiState":"granted"}},"features":{"keychain":{}}}'
EOS
printf '#!/bin/bash\necho "install.sh $*" >> "%s"\n' "$LOG" > "$W/install.sh"
chmod +x "$W"/bin/* "$W/install.sh"
export PATH="$W/bin:$PATH" STUB_LOG="$LOG" MOTE_HELPER="$W/bin/MoteHelper" MOTE_INSTALL_SH="$W/install.sh" \
       MOTE_L3_HOME="$W/home" MOTE_EVENTS="$W/none.jsonl"

# reset: refuses unless motetest, and then touches nothing
STUB_USER=owner "$HERE/reset-user.sh" >/dev/null 2>&1; rc=$?
ok "reset refuses as another user (rc=$rc)" test "$rc" = 2
ok "reset refused before touching anything" hasnt 'tccutil|security|pkill'
ok "reset left the files alone when refused" test -d "$W/home/Applications/MoteHelper.app"
"$HERE/reset-user.sh" >/dev/null 2>&1
ok "reset runs tccutil reset All dev.mote.helper" has 'tccutil reset All dev.mote.helper'
ok "reset deletes keychain items of the service" has 'security delete-generic-password -s dev.mote.helper'
ok "reset removed app" test ! -e "$W/home/Applications/MoteHelper.app"
ok "reset removed app support" test ! -e "$W/home/Library/Application Support/dev.mote.helper"

# setup: creates once, then is idempotent; the password never appears in argv
: > "$LOG"; STUB_EXISTS=0 "$HERE/setup-user.sh" </dev/null >/dev/null 2>&1
ok "setup (new user) calls sysadminctl -addUser motetest" has 'sysadminctl -addUser motetest'
ok "setup passes only '-' as password" hasnt 'password [^-]'
: > "$LOG"; STUB_EXISTS=1 "$HERE/setup-user.sh" </dev/null >/dev/null 2>&1; rc=$?
ok "setup is idempotent (rc=$rc)" test "$rc" = 0
ok "setup did not call sysadminctl again" hasnt sysadminctl

# run: walks the install-helper L3 rows, calls the stubbed installer and diag, writes the report
: > "$LOG"
n=$(node "$HERE/../journeys/rows.mjs" --level L3 | grep -c '"journey":"install-helper"')
yes '' | "$HERE/run.sh" install-helper --out "$W/out" > "$W/run.out" 2>&1
R=$(find "$W/out" -name 'l3-install-helper-*.md' | head -1)
ok "run.sh install-helper wrote a report" test -n "$R"
ok "report has $n L3 rows" test "$(grep -c '^| INS-' "$R")" = "$n"
ok "run.sh called the installer" has '^install.sh'
ok "run.sh called --diag" has 'MoteHelper --diag'
ok "report checked diag.keychain.partitionMatchesSigning" has 'partitionMatchesSigning = true' "$R"
ok "event cites are 'not checked' without diag-events.jsonl" has 'not checked' "$R"
: > "$LOG"; echo x > "$W/a.zip"; yes '' | "$HERE/run.sh" install-helper --zip "$W/a.zip" --out "$W/out" >/dev/null 2>&1
ok "run.sh --zip still calls the installer" has '^install.sh'

# vault pseudo-journey: readResult=found passes; error fails
yes '' | "$HERE/run.sh" vault --no-install --out "$W/out" >/dev/null 2>&1
ok "vault passes with readResult=found" test $? = 0
printf '#!/bin/bash\necho "{\\"keychain\\":{\\"readResult\\":\\"error\\"}}"\n' > "$W/bin/MoteHelper"
yes '' | "$HERE/run.sh" vault --no-install --out "$W/out" >/dev/null 2>&1
ok "vault fails with readResult=error" test $? = 1

# static checks
for s in "$HERE"/*.sh; do ok "bash -n $(basename "$s")" bash -n "$s"; done
if command -v shellcheck >/dev/null; then ok "shellcheck" shellcheck -x "$HERE"/*.sh; else echo "skip shellcheck (not installed)"; fi
if [ "$fails" = 0 ]; then echo "all passed"; else echo "$fails failed"; exit 1; fi
