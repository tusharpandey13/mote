package dev.mote.remote.journeys

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.mote.remote.net.Net
import dev.mote.remote.net.WsStatus
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** docs/journeys/daily.md */
@RunWith(AndroidJUnit4::class)
class DailyJourney {
    @get:Rule val j = JourneyRule()

    private fun authed(phone: String) = "authed \\Q$phone\\E via Wi-Fi from "

    @Test fun main() {
        j.path("daily")
        val (mac, name) = j.cLinkHelper()
        val phone = j.phoneName()
        // "relaunch": the activity closes and the socket is dropped 30 s later (Net.foreground)
        j.closeActivity()
        j.cWait("socket closed after 30 s in the background", 40_000) { Net.wsStatus.value is WsStatus.Idle }
        mac.mark()
        j.step("S1") {
            j.launch(fakeProximity = true)
            j.expect("Brightness up", timeoutMs = 8_000)
            j.expectNot("Connect to your Mac")
            j.expect(cPill(name))
        }
        j.step("S2") { mac.await(authed(phone), 10_000) }
        j.step("S3") {
            j.cWait("Ready", 5_000) { Net.wsStatus.value is WsStatus.Ready }
            j.expect(cPill(name))
        }
        j.step("S4") {
            j.tap(cPill(name))
            j.expect("Devices")
            j.expect("Mote reconnects to the selected device while the app is open.")
            j.expect("Use $name")
            j.expect("Connected · Wi-Fi")
            j.expect("Add a Mac")
            j.expect("More options")
            j.expect("Set up Bluetooth")
        }
        j.step("S5") { j.device.pressBack(); j.expectGone("Devices") }
    }

    @Test fun asleep() {
        j.path("daily.asleep") // B1-B3 need a SavedHost seeding seam (non-answering address): not automated
    }

    @Test fun nohelper() {
        j.path("daily.nohelper")
        val (mac, name) = j.cLinkHelper()
        mac.stop()
        j.step("B1") {
            j.cWait("connecting", 10_000) { Net.wsStatus.value is WsStatus.Connecting }
            j.expect(cPill(name))
        }
        j.step("B2") {
            j.tap(cPill(name))
            j.expect("Connecting…")
            j.expectNot("Searching…")
            j.cAdvance(1_600) // the sheet's 1.5 s delay before probing runs on the Compose clock
            // What the probe sees decides the note (reachNote); assert the matching copy.
            val h = Net.saved.value.first()
            val reaches = h.candidates().take(4).map { dev.mote.remote.pairing.tcpReach(it, h.port) }
            val refused = "Mote Helper isn't running on $name. Open it from Applications on the Mac."
            val asleep = "$name seems asleep or away. Wake it (open the lid or press a key) and keep it on this Wi-Fi or Tailscale."
            val want = dev.mote.remote.pairing.reachNote(name, reaches)
                ?: throw AssertionError("a closed helper port still answers TCP (reaches ${h.candidates().take(4)} = $reaches): no note is shown, see daily-D7")
            j.expect(want, timeoutMs = 8_000)
            if (want != refused && want != asleep) throw AssertionError("unexpected note $want")
        }
    }

    @Test fun paused() {
        j.path("daily.paused")
        val (mac, name) = j.cLinkHelper()
        j.step("B1", "faked: stdin-control `pause` (menu is headless-unobservable: title \"Mote Helper: Paused: input is ignored\")") {
            mac.mark(); mac.stdin("pause"); mac.await("input paused")
        }
        j.step("B2") {
            j.cWait("paused state", 5_000) { (Net.wsStatus.value as? WsStatus.Ready)?.paused == true }
            j.expect(cPill(name))
        }
        j.step("B3") { j.tap(cPill(name)); j.expect("Paused on the Mac") }
        j.step("B4") {
            j.device.pressBack(); j.expectGone("Devices")
            mac.mark()
            j.cPress("Play or pause")
            mac.assertNo("DRY consumer play", 2_000)
        }
        j.step("B5", "faked: stdin-control `resume`") {
            mac.stdin("resume"); mac.await("input resumed")
            j.cWait("resumed state", 5_000) { (Net.wsStatus.value as? WsStatus.Ready)?.paused == false }
            mac.assertNo("DRY consumer play", 1_000)
            j.tap(cPill(name)); j.expect("Connected · Wi-Fi")
        }
    }

    @Test fun switchMacs() {
        j.path("daily.switch") // needs --fake-host-name (two dry-run helpers report the same computer name): not automated
    }

    @Test fun background() {
        j.path("daily.background")
        val (mac, _) = j.cLinkHelper()
        val phone = j.phoneName()
        j.step("B1") {
            j.device.pressHome()
            j.cWait("socket closed 30 s after leaving the screen", 40_000) { Net.wsStatus.value is WsStatus.Idle }
        }
        j.step("B2") {
            // B1 left the socket closed; return, let it reconnect, then leave for < 30 s
            j.cHomeAndReturn(500)
            j.cWait("Ready again", 15_000) { Net.wsStatus.value is WsStatus.Ready }
            mac.mark()
            j.cHomeAndReturn(5_000)
            mac.assertNo("authed ", 3_000)
        }
        j.step("B3") {
            mac.mark()
            j.cHomeAndReturn(32_000)
            mac.await(authed(phone), 15_000)
        }
    }

    @Test fun tailnet() {
        j.path("daily.tailnet") // needs a fake Tailscale VPN state: not automated
    }

    @Test fun notif() {
        j.path("daily.notif")
        j.cLinkHelper()
        j.cGrantBt()
        j.closeActivity()
        j.launch(fakeProximity = true)
        j.expect("Brightness up", timeoutMs = 8_000)
        j.step("B1") {
            j.device.openNotification()
            try {
                if (!j.systemShown("^Ready to pair$", 8_000)) throw AssertionError("notification text \"Ready to pair\" not shown")
            } finally { j.device.pressBack() }
        }
    }

    @Test fun notifBt() {
        j.path("daily.notif")
        val fake = cHid(linked = false)
        j.cLaunchBt(fake)
        j.step("B2", "faked: BT HID transport (FakeTransport); Hid never reports connected under the seam") {
            j.tap(cPill("Choose a device", null))
            j.tap("Use ${C_MAC.name}")
            j.device.openNotification()
            try {
                if (!j.systemShown("Searching for \\QTest Mac\\E…", 8_000)) throw AssertionError("notification \"Searching for Test Mac…\" not shown")
            } finally { j.device.pressBack() }
        }
    }
}
