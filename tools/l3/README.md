# L3: real-OS runs in a clean user

Runs Mote Helper's real install, Gatekeeper, TCC and Keychain in a separate standard macOS user, `motetest`, so
your own account is never touched. The phone is the Android emulator on the same LAN. Plan: `docs/plans/journey-testing.md` (Phase 5).

Disk: 3-8 GB for the user's home (installs, logs). Delete the user in System Settings > Users & Groups when done.

## Order
1. Once, as you (admin), in Terminal: `tools/l3/setup-user.sh`. It asks for a password for `motetest`, then your
   admin password. It does not change sharing settings; if Remote Login is already on and restricted to a list, it asks before adding `motetest`.
2. Make the repo readable by `motetest` (it runs the scripts from this checkout): `chmod -R a+rX <repo>` and ensure parent folders are traversable, or clone into `/Users/Shared/mote`.
3. Switch to `motetest`: Fast User Switching (menu bar > user name > motetest), or `ssh motetest@localhost` (needs Remote Login; permission dialogs only show in the GUI session, so prefer Fast User Switching and run from Terminal there).
4. Per run, inside `motetest`:
   - `tools/l3/reset-user.sh` (wipes TCC grants, Keychain items, app support, app, defaults; refuses to run as anyone else)
   - `tools/l3/run.sh <journey>`; add `--zip PATH` to test a candidate zip instead of the published release, `--no-install` to skip the install.
   Journeys: `install-helper` (1), `setup-mac` (2), `first-connect` (4), `reconnect` (5), `mac-update` (7), also `daily`, and `vault`.
5. Start the emulator on your Mac's network and connect it to the Mac's LAN IP (run.sh prints it).
6. The report lands in `docs/journeys/l3-<journey>-<date>.md`. Copy it back to your account and commit.

For each row: read Step and Expected, do it, click through any macOS dialog, press Enter. Then answer Enter (pass), `f` (fail) or `n` (note). run.sh runs `MoteHelper --diag` and checks the `diag.<path>` fields the row cites (`==` means a required value);
event cites are checked in `diag-events.jsonl`, else shown as "not checked". Any failed check fails the row.

## Vault run (`run.sh vault`)
Reset first, then run it: VAULT-1 attribute-only listing without a prompt, VAULT-2 a fresh install shows exactly one Keychain prompt (confirm by eye),
VAULT-3 after quitting and reopening the helper, `diag.keychain.readResult == found` and no new prompt. Use `--no-install` if the helper is already installed.

## Accept (plan)
Journeys 1, 2, 4, 5 and 7 pass at L3 on the release build, and B1 is confirmed fixed (or reproduced) on the self-signed
release artifact (the downloaded zip, "Mote Helper Local"), not on a local Apple Development build.

## Tests
`tools/l3/test.sh` runs everything with stubbed commands (no sudo, no keychain, no real install); also `bash -n` and shellcheck.
