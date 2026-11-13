// Exact instructions for each L4 row (the specs only hold a one-line summary). checklist.mjs prints them; its test
// fails when an L4 row has no entry here. `do`: numbered steps, `pass`: what passing looks like, `prep`: what the
// agent sets up first (shown, not run).
export const STEPS = {
  "INS-18": {
    prep: "Done in the L3 session (motetest), so your own grants stay: the agent builds the candidate release zip.",
    do: [
      "Quit your own Mote Helper (menu bar › Quit) so the ports are free, switch to motetest, run tools/l3/reset-user.sh, then install the v0.6.0 release: tools/l3/run.sh install-helper (it downloads the published release).",
      "On the phone, Mote Dev › Add a Mac › connect to that helper over Wi-Fi (phone at the desk).",
      "Install the candidate over it: tools/l3/run.sh install-helper --zip <candidate zip>.",
      "Watch Mote Dev for 15 s. On the Mac, answer a Keychain prompt with Always Allow if one appears.",
    ],
    pass: "Mote Dev reconnects by itself; at most one Keychain prompt; no \"key doesn't match\", no re-pair.",
  },
  "SP-1": {
    prep: "The agent builds the release APK and copies it to the phone's Download folder.",
    do: [
      "On the phone open Files › Downloads and tap mote-candidate.apk.",
      "If Android asks, allow Files to install unknown apps, go back, tap the APK again.",
      "Tap Update (or Install).",
    ],
    pass: "Android shows its install dialog and ends with \"App installed\".",
  },
  "SP-5": {
    do: [
      "Needs a network that drops multicast but lets devices reach each other (some mesh routers, a phone hotspot). The office guest Wi-Fi isolates devices completely and does not count.",
      "Phone and Mac on that network. Mote Dev › Devices › Forget this Mac, then Add a Mac.",
    ],
    pass: "\"Connect to <your Mac>\" appears a little later than usual (found by the sweep, not Bonjour). No such network: answer s.",
  },
  "SP-20": {
    do: [
      "You did this today: the Bluetooth re-pair after RC-13 went through Mote Dev's Pair screen.",
      "Answer p if Android's \"make visible\" dialog, the Mac's pairing, and the code confirmation all worked and the touchpad moved the Mac's pointer; otherwise f with what happened.",
    ],
    pass: "Bluetooth pairing finished from Mote Dev and input worked.",
  },
  "FC-5": {
    do: [
      "Mote Dev › Devices › tap this Mac (Wi-Fi row) › Forget.",
      "Walk to the other side of the room or the next room (8 m or more, walls help).",
      "Tap Add a Mac › Connect to <your Mac>.",
      "Walk back to the desk (phone within 1 m of the Mac) and tap Retry.",
    ],
    pass: "From far away: refused with \"Bring the phone close to your Mac\". At the desk: \"Connected\", no code typed.",
  },
  "FC-20": {
    do: [
      "At the desk, Forget this Mac again in Mote Dev, then Add a Mac › Connect to <your Mac>.",
      "Count seconds from the tap until \"Connected\".",
    ],
    pass: "Connected in 5 s or less.",
  },
  "RC-13": {
    do: ["Done with the agent on 2026-11-13 (Mac › Bluetooth › Forget This Device). Answer p."],
    pass: "Mote Dev went yellow, then red \"Not paired · pair again\".",
  },
  "RC-16": {
    do: [
      "Sleep: with Mote Dev connected over Wi-Fi, close the lid for 1 minute, open it, unlock.",
      "Address change: turn the Mac's Wi-Fi off and on (it may get a new IP).",
      "Leave Wi-Fi: with this Mac's Bluetooth link active in Mote Dev, turn Wi-Fi off on the phone (keep Bluetooth on); use the touchpad. Then turn Wi-Fi back on.",
    ],
    pass: "After wake and after the Wi-Fi toggle it reconnects by itself within about 10 s. With phone Wi-Fi off the badge turns BT within about 15 s and input still works; with Wi-Fi back it returns to WI-FI within about 30 s. Note which part failed.",
  },
  "DU-21": {
    do: [
      "In Mote Dev turn on Labs › Smart remote; play music on the Mac (Music, Spotify or YouTube).",
      "Lock the phone and look at the lock screen.",
      "Connect a Bluetooth headset to the phone and press its play/pause button.",
    ],
    pass: "Lock screen shows title, artist, artwork and play/pause/next; the headset button pauses and resumes the Mac.",
  },
  "DU-22": {
    do: [
      "Use Mote Dev as your remote for a day: media keys and volume, touchpad and scroll, typing (with an emoji), a macro, Spaces, ⌘P pause on the Mac, the YouTube video profile, the now-playing notification.",
      "Run this row later on its own: node tools/l4/checklist.mjs --only DU-22. Now answer s.",
    ],
    pass: "Nothing broke during the day; note anything odd.",
  },
  "UP-11": {
    do: [
      "Right after SP-1, open Mote (the real one, not Mote Dev).",
      "Check Devices, the selected Mac, Settings and your macros; then use the touchpad.",
    ],
    pass: "Everything is still there and it reconnects without pairing again.",
  },
  "UP-12": {
    prep: "The agent gives you a command that tries to install a debug-signed dev.mote.remote over Mote. Android refuses it, so Mote is unchanged.",
    do: ["Run that command, then open Mote."],
    pass: "The install fails with INSTALL_FAILED_UPDATE_INCOMPATIBLE and Mote still opens with its data.",
  },
  "UP-15": {
    do: [
      "Only testable while an older helper runs and a newer release is published: do it after the 0.7.0 release with the 0.6.0 helper still installed (for example in motetest before the upgrade).",
      "Open the Mote menu bar icon. Then open Advanced and turn off \"Check for updates daily\".",
    ],
    pass: "The menu shows \"Mote Helper 0.7.0 is available\" and clicking it opens the release page; after turning the check off it no longer checks. Not testable now: answer s.",
  },
};
