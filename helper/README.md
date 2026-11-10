# Mote Helper (macOS)

Menu-bar app that turns authenticated Mote messages (Wi-Fi WebSocket, Tailscale, BLE) into input.
Protocol: `docs/protocol.md`; pairing: `docs/pairing.md`.

## Build

```sh
helper/build.sh                 # build/MoteHelper.app + build/MoteHelper-macos-<arch>.zip(.sha256)
ARCHS=arm64 helper/build.sh     # one arch
MOTE_ADHOC=1 helper/build.sh    # ad-hoc signature (CI, agents): no keychain access, permissions don't stick
helper/tests/unit.sh            # Swift unit tests (CLT only, no Xcode; plain `swift test` can't run them): permissions, Keychain blob, screen math, events, diag; `helper/tests/signing.sh` tests identity selection and notarize.sh with stubs
```

Diagnostics (protocol 1.5, `docs/protocol.md` section 2.1): `MoteHelper --diag` prints the snapshot (permissions with raw API answers, signing, Keychain, features) as JSON and exits without any macOS prompt. The running helper writes its own snapshot (mode 0600, no secrets) to `~/Library/Application Support/dev.mote.helper/diag.json` (+ the last 500 events in `diag-events.jsonl`) at launch, ~1 s after any event and every 30 s; `--diag` prints it (`source: "running"`, `pid`, `writtenAt`) when that pid is alive and the file is under 60 s old, otherwise it probes in its own process (`source: "cli"`, runtime sections `unknown`, a `note` says why). `--data-dir DIR` moves the files (tests); `--memory-store` writes none unless `--data-dir` is given; the running helper answers the `diag` message and has a menu item "Diagnostics…" (readable view, Copy). `--events FILE` appends every event as a JSON line; the same events go to `os_log` (`log stream --predicate 'subsystem == "dev.mote.helper"'`) and to `diag.subscribe` clients. Test: `node helper/tests/diag.mjs`.

## Signing: one-time setup

See [docs/signing.md](../docs/signing.md) for the full picture. `build.sh` signs with the first of: `MOTE_SIGN_IDENTITY`,
Developer ID Application, Apple Development (free, Xcode; has a Team ID so the Keychain stays silent on rebuilds), "Mote Helper Local"
(below; the Keychain prompts after every rebuild), ad-hoc. **Release zips** (`MOTE_RELEASE=1 helper/build.sh`) are signed with
Developer ID (then notarized via `helper/notarize.sh`; needs `xcrun notarytool store-credentials mote-notary ...` once) or, until
that exists, with "Mote Helper Local" (not notarized, warning; users get one Keychain prompt per update). Never ad-hoc or Apple Development.
The self-signed section below is the fallback for dev builds without an Apple identity.

macOS ties Accessibility, Bluetooth, Screen Recording and Automation grants, and the Keychain
"Always Allow", to the app's *designated requirement*. An ad-hoc signature changes on every build,
so each rebuild looked like a new app: grants stopped working while their switches still showed on,
and the Keychain asked again. `build.sh` therefore signs with one fixed, self-signed certificate,
**"Mote Helper Local"**, and the requirement stays the same across rebuilds:

```
identifier "dev.mote.helper" and certificate leaf = H"<sha1 of Mote Helper Local>"
```

The first `helper/build.sh` creates it (`helper/signing.sh ensure`): an RSA-2048 code-signing
certificate made with `/usr/bin/openssl`, imported into the login keychain with codesign allowed
to use the key. Once, in that first build:

1. The terminal asks for your login (keychain) password, to let `codesign` use the key without a
   dialog (`security set-key-partition-list`). If you skip it, macOS shows "codesign wants to sign
   using key Mote Helper Local" on the first build: enter the password and click **Always Allow**.
   `helper/signing.sh allow` repeats this step.
2. Open the new build and grant Accessibility in its Setup window. That grant now survives rebuilds.

A copy of the identity is kept in `~/.config/mote/mote-helper-local.p12` (next to the Android
keystore). Back it up: `signing.sh` re-imports it on a new Mac, and a new certificate means every
permission has to be granted once more. Check a build: `codesign -dr - helper/build/MoteHelper.app`
must print the same requirement every time.

Releases: build the zips locally with this identity (see AGENTS.md "Release"). Users who install
with `site/install.sh` get whatever the release is signed with; `install.sh` resets the old
Accessibility and Screen Recording entries once when the signature changed (e.g. from an ad-hoc build).

## Permissions and the Setup window

Nothing asks for a permission from a background event (a phone connecting, a Space switch, a browser
coming to the front). The Setup window (`SetupWindow.swift`, model in `Permissions.swift`) opens at the
first launch of a new setup version, whenever Accessibility is missing, after `--setup`
(install.sh, "Relaunch helper"), and from the menu, **Setup…**. Each row has a one-line reason, a live
status (re-read every second, so a grant shows at once without a restart) and a button that opens the
exact System Settings pane.

| Permission | Tier | Used for | Asked |
|---|---|---|---|
| Accessibility | required | pointer, clicks, typing | Setup: Grant |
| Bluetooth | recommended | one-tap pairing (proximity), BLE remote | Setup: Grant (the BLE server only starts once allowed) |
| Local Network (macOS 15+) | recommended | Wi-Fi remote | macOS asks on first LAN use; no API to read it, so the row opens Settings |
| Screen Recording | optional, off | Space thumbnails, Labs › Live screen | when you turn one of them on |
| Automation | optional, off | Labs › Context: the browser's active tab | when you turn the lab on, or "Allow … tabs" |

The helper does not use notifications. Screen Recording may need a restart on some macOS versions:
the Setup window then offers **Relaunch helper**.

## Keychain

Every secret is in **one** generic-password item, service `dev.mote.helper`, account `secrets.v4.<partition>` (one per signing identity; `<partition>` is `teamid:X` or `cdhash:H`): a JSON
blob with the code-paired devices, the Labs bridge pairings, the trusted and blocked phone keys and the
Mac's Ed25519 identity (`Vault.swift`). At launch the helper lists the service's accounts by attributes only, then reads its own account without UI when it is the newest; if another build's account (`secrets.v3`, another `secrets.v4.*`) is newer or its own is missing, it reads that one (at most one Keychain prompt, off the main thread) and copies it into its own account, never rewriting or deleting the old item. Any read error other than "not found" makes the vault ephemeral (memory only), never "empty" (docs/pairing.md section 7). A fresh install writes its account on the first launch. With a Team ID signature the item's partition is
`teamid:<TEAM>`, so updates read it silently (docs/signing.md). Older items (`secrets.v2`, `secrets`, `devices`, ...) are never read or
deleted; pairings do not carry over (re-pair once). The proximity secret is per process and never stored. Tests use `--memory-store`
(no Keychain at all) or `--keychain-service NAME`.

## Menu

Default: status (with **Finish setup…** while Accessibility is missing), the screen-sharing indicator,
connected phones, **Devices** (trusted / blocked phones, Block / Unblock, Revoke all devices…),
**Pause input**, **Setup…**, **Quit**. A line "Mote Helper X.Y.Z is available" (opens the release page) appears when
the daily GitHub release check finds a newer version; it never downloads anything.

**Advanced**: Wi-Fi remote URLs (click to copy), Wi-Fi / Bluetooth status, Allow Tailscale connections
(off until you turn it on; + the tailnet URL), Share Space thumbnails (off by default; asks for Screen Recording when turned on)
and Clear Space thumbnails, Require approval for new devices, this Mac's key, Pair with code (web
remote)…, Share now playing (on by default), Check for updates daily (on), Open at login ("Needs approval in Login Items…" when macOS holds it).

**Labs**: TV mode, Context (frontmost app), browser extension bridge, Live screen (previews, all off).

Test flags (need `--dry-run`): `--fake-ax-denied`, `--update-url URL`, `--update-check-now`; tests: `helper/tests/ux.mjs`.
