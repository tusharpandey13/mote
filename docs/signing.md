# Signing the macOS helper

## Root cause of the Keychain prompts

The helper keeps its secrets in one login-Keychain item (service `dev.mote.helper`, see `helper/Sources/MoteHelper/Vault.swift`).
A Keychain item has two gates: the ACL trusted-app requirement, and the **partition_id**. Our ACL requirement is stable
(`certificate leaf = H"47f4..."` for "Mote Helper Local"), but the partition is `cdhash:<this build's hash>`, because a
self-signed or ad-hoc signature has no Team ID. Another build is outside that partition, so macOS asks for the login password
on every new build (`security dump-keychain -a` / `security find-generic-password -g` shows the `partition_id` entry).
With a Team ID in the signature the partition becomes `teamid:<TEAM>` and every build signed by the same team reads silently.

## What each identity gives

| Identity | Team ID | Keychain across rebuilds / updates | Distribution |
|---|---|---|---|
| Ad-hoc (`MOTE_ADHOC=1`, CI) | no | prompts, permissions reset | no |
| "Mote Helper Local" (self-signed) | no | prompts on every rebuild/update | interim releases only: not notarized, one Keychain prompt per update |
| **Apple Development** (free personal team, Xcode) | yes | silent on local rebuilds | no: its terms forbid distribution; refused for releases |
| **Developer ID Application** ($99/yr) + notarization | yes | silent across updates | yes: preferred release identity |

`helper/build.sh` picks, in order: `MOTE_SIGN_IDENTITY`, Developer ID, Apple Development, Mote Helper Local (loud warning), ad-hoc.
`MOTE_RELEASE=1 helper/build.sh` accepts Developer ID (sign, then `helper/notarize.sh`: submit, staple, re-zip) or "Mote Helper Local"
(hardened runtime, no notarization, loud warning). It skips Apple Development (select "Mote Helper Local" automatically) and refuses ad-hoc.
`notarize.sh` itself still refuses a bundle without a Developer ID authority and Team ID.

Interim policy (owner decision, until Developer ID is bought): releases are signed with "Mote Helper Local". Its Keychain partition is the
exact build, so every helper update shows customers one Keychain prompt (login password, Always Allow), and the first launch is not
notarized (right-click Open, or `install.sh` strips quarantine). Release notes must say so. Moving to Developer ID later changes the team: one more prompt or re-pair.

Secrets live in account `secrets.v3`. Earlier items (`secrets.v2`, `secrets`, per-secret ones) are never read: a cdhash-partitioned item prompts
any other build. Users re-pair once.

## Current local path: Apple Development (free)

Xcode, Settings, Accounts, sign in with your Apple ID, Manage Certificates, **+**, Apple Development. Then `helper/build.sh` finds
`Apple Development: ... (TEAMID)` by itself. Grant Accessibility once; the Keychain item (created by this team's build) stays readable.
Switching identity later (to Developer ID) changes the team, so the item is unreadable silently once: one prompt or re-pair.

## Distribution: Developer ID, one-time steps

1. Enrol at developer.apple.com/programs ($99/yr).
2. Create a "Developer ID Application" certificate without Xcode: Keychain Access, Certificate Assistant, Request a Certificate From a
   Certificate Authority (save to disk); developer.apple.com, Certificates, **+**, Developer ID Application, upload the CSR, download the `.cer`,
   double-click to install into the login keychain.
3. Store notarization credentials (app-specific password from appleid.apple.com):
   `xcrun notarytool store-credentials mote-notary --apple-id <id> --team-id <TEAMID> --password <app-specific-password>`
4. Export the identity (Keychain Access, My Certificates, right click, Export .p12) to `~/.config/mote/` and back it up with the Android keystore.
5. Release: `MOTE_RELEASE=1 helper/build.sh`, then the AGENTS.md Release steps.

Hardened runtime entitlements: only `com.apple.security.automation.apple-events` (Labs Context sends Apple Events to the browser;
`NSAppleEventsUsageDescription` is in `Info.plist`). Bluetooth, Accessibility, Screen Recording and network need no entitlement outside the sandbox.
