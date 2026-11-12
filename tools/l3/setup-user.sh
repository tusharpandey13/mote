#!/bin/bash
# Creates the standard (non-admin) macOS user `motetest` for L3 runs. Run ONCE, as the owner (an admin), in a terminal.
# sysadminctl only creates users as root: the script re-runs itself with sudo (your admin password, once), then
# sysadminctl prompts for the new user's password (nothing goes in argv or a file).
# Idempotent: exits 0 when the user exists. Does not change sharing settings, except the optional, asked
# Remote Login access-list entry below.
set -eu
USER_NAME=motetest
[ "$(id -u)" -eq 0 ] || [ -n "${MOTE_L3_NO_SUDO:-}" ] || exec sudo "$0" "$@"

if id -u "$USER_NAME" >/dev/null 2>&1; then
  echo "User $USER_NAME already exists; nothing to do."
else
  echo "Creating the standard user $USER_NAME. Type its new password when asked."
  sysadminctl -addUser "$USER_NAME" -fullName "Mote Test" -password -
  id -u "$USER_NAME" >/dev/null 2>&1 || { echo "error: user was not created" >&2; exit 1; }
  echo "Created $USER_NAME (standard user, not admin)."
fi

# Remote Login (ssh): only touched if it is already on AND restricted to a list that lacks motetest. Asks first.
if nc -z 127.0.0.1 22 >/dev/null 2>&1; then
  echo "Remote Login is already enabled."
  if dseditgroup -o read com.apple.access_ssh >/dev/null 2>&1 \
     && ! dseditgroup -o checkmember -m "$USER_NAME" com.apple.access_ssh >/dev/null 2>&1; then
    printf 'Remote Login is limited to a list that does not include %s. Add it? [y/N] ' "$USER_NAME"
    read -r a || a=n
    case "$a" in [yY]*) dseditgroup -o edit -a "$USER_NAME" -t user com.apple.access_ssh ;; *) echo "Left unchanged." ;; esac
  fi
else
  echo "Remote Login is off; left off. Use Fast User Switching to reach $USER_NAME (see tools/l3/README.md)."
fi

echo "Disk used by $USER_NAME's home:"
du -sh "/Users/$USER_NAME" 2>/dev/null | tail -1; echo "  (a few hundred MB after first login, 3-8 GB after runs)"
