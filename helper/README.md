# Mote Helper (macOS)

Menu-bar app that turns authenticated Mote messages (Wi-Fi WebSocket, Tailscale, BLE) into input.
Protocol: `docs/protocol.md`; pairing: `docs/pairing.md`.

## Build

```sh
helper/build.sh                 # build/MoteHelper.app + build/MoteHelper-macos-<arch>.zip(.sha256)
ARCHS=arm64 helper/build.sh     # one arch
MOTE_ADHOC=1 helper/build.sh    # ad-hoc signature (CI, agents): no keychain access, permissions don't stick
helper/tests/unit.sh            # Swift unit tests (permission model, Keychain blob migration, screen math)
```

## Signing: one-time setup

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

Every secret is in **one** generic-password item, service `dev.mote.helper`, account `secrets`: a JSON
blob with the code-paired devices, the Labs bridge pairings, the trusted and blocked phone keys and the
Mac's Ed25519 identity (`Vault.swift`). It is read once at launch and cached, so there is at most one
Keychain prompt, and none after "Always Allow" with the stable signature. Builds before 0.6 used five
items (`dev.mote.helper/devices`, `dev.mote.helper.bridge/devices`, `dev.mote.helper.labs/{trusted,blocked,identity}`);
they are migrated into the blob on first launch and deleted. The proximity secret is per process and
never stored. Tests use `--memory-store` (no Keychain at all) or `--keychain-service NAME`.

## Menu

Default: status (with **Finish setup…** while Accessibility is missing), the screen-sharing indicator,
connected phones, **Devices** (trusted / blocked phones, Block / Unblock, Revoke all devices…),
**Pause input**, **Setup…**, **Quit**.

**Advanced**: Wi-Fi remote URLs (click to copy), Wi-Fi / Bluetooth status, Allow Tailscale connections
(+ the tailnet URL), Share Space thumbnails (off by default; asks for Screen Recording when turned on)
and Clear Space thumbnails, Require approval for new devices, this Mac's key, Pair with code (web
remote)…, Open at login.

**Labs**: TV mode, Context & now playing, browser extension bridge, Live screen (previews, all off).
