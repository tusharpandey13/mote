# Open questions for the owner (collected overnight 2026-11-12)

1. **Connect-flow copy for a removed phone.** When "Add a Mac" hits a Mac that removed this phone, the agent wrote:
   "<Mac> no longer trusts this phone. Remove this Mac, then add it again." (android pairing/ConnectUi.kt `pairFailure`).
   The Devices card says "The Mac no longer trusts this phone. Long-press it to forget, then Add a Mac again." Keep both, or align?
2. **Install the new builds?** Your installed helper (58054ff) and APK (pre-1b) are old. When you want: build the helper with the
   "Mote Helper Local" identity (one Keychain prompt per update) and `adb install` the Mote Dev APK yourself.

## Open bugs found overnight (no decision needed; next session)
- `live-screen.link-drop` B2: after Wi-Fi + data off the helper logs `screen: stopped`, but the phone keeps the last frame and
  "Stream stats" for 60 s and never shows "Connecting to the Mac…" (OkHttp pings every 20 s, so it should notice within ~40 s).
  Either the emulator keeps a route to 10.0.2.2 (check `ip route` / eth0 during the step) or the Live screen page ignores the dead link.
- `run.sh live-screen#linkDropWifi` exited 0 (gradle exit 0) although results.jsonl recorded B2 `fail`: the harness can hide a failing step.
- `first-run-bt.bt-fail-notfound` B1 flake (1 of 2 runs): after Continue + Nearby allow + visibility, the app stays on the Bluetooth
  offer and the helper never logs the pairing attempt.
