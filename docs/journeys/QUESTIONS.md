# Owner questions (collected overnight 2026-11-12)

## Decided 2026-11-12
1. **Connect-flow copy for a removed phone:** keep both. Devices card: "The Mac no longer trusts this phone. Long-press it to
   forget, then Add a Mac again."; Add a Mac flow (pairing/ConnectUi.kt `pairFailure`): "<Mac> no longer trusts this phone.
   Remove this Mac, then add it again." Each names the next step on its own screen.
2. **Install the new builds:** after the open bugs below are fixed (helper with "Mote Helper Local", one Keychain prompt;
   owner runs `adb install` for Mote Dev).

## Bugs found overnight (fixed 2026-11-12)
- `live-screen.link-drop` stale frame: a quick reconnect kept the status Ready, so the page never re-sent
  `labs.screen.start` on the new socket (app bug; `WsStatus.Ready.link`, 295fe41). New path `live-screen.quick-reconnect`.
- "run.sh exited 0 with a failed step": the step's own result upload failed while the network was off (silently dropped),
  and the `fail` shown was an older run's row. Results are now queued until the network is back, and each run drops a
  path's older rows (58c4a99).
- `first-run-bt.bt-fail-notfound` B1 flake: the emulator dropped the tap on Android's discoverable dialog; the test now taps
  until the dialog is gone (58c4a99).
