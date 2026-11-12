package dev.mote.remote.journeys

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.pinch
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.mote.remote.labs.Labs
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** docs/journeys/live-screen.md */
@RunWith(AndroidJUnit4::class)
class LiveScreenJourney {
    @get:Rule val j = JourneyRule()

    /** Works around a harness teardown crash (Bt.observe awaitClose on the test thread): dispose the UI first. */
    @org.junit.After fun dlCloseUi() { j.closeActivity() }


    private val VIEWER = "Mac screen. Tap to click, hold to right-click, pinch to zoom."
    private val WARN = "Your screen travels unencrypted"
    private val PAUSED_MSG = "Screen sharing is paused on the Mac (Mote Helper menu › Labs)."
    private val LABOFF_MSG = "Turn on Labs › Live screen in the Mote Helper menu on the Mac."
    private val CENTER = "DRY clickAt \\(-75[5-7]\\.\\d+, 29[0-2]\\.\\d+\\)"
    private lateinit var mac: Helper
    private var phone = ""

    private fun setup(fake: Boolean = true) {
        val (m, name) = if (fake) j.dlHelper("--fake-screen") else j.dlHelper()
        mac = m
        phone = j.phoneName()
        j.dlLabs(Labs.LIVE_SCREEN)
        j.dlConnect(mac, name)
        mac.mark()
    }

    /** Compose only sees state changes on a frame; mac.await blocks the test thread, so pump first. */
    private fun pump() { j.compose.waitForIdle() }

    private fun openPage() { j.tap("Menu"); j.tap("Screen (preview)"); j.expect("Screen", timeoutMs = 5_000) }

    /** Opens the page, acknowledges the LAN warning and waits for frames. */
    private fun streaming() {
        openPage(); j.tap("Show the screen", timeoutMs = 5_000)
        j.expect(VIEWER, timeoutMs = 5_000)   // pumps Compose first: the pref write only reaches the UI on a frame, mac.await blocks the test thread
        mac.await("screen: streaming to \\Q$phone\\E via Wi-Fi"); j.expectGone("Waiting for the first frame…", timeoutMs = 5_000)
    }

    @Test fun main() {
        j.path("live-screen")
        setup()
        j.step("S1") { j.tap("Menu"); j.expect("Screen (preview)"); j.expect("See the Mac's screen, tap to click"); j.expect("Settings"); j.expect("About Mote") }
        j.step("S2") {
            j.tap("Screen (preview)")
            j.expect("Screen", timeoutMs = 5_000); j.expect("Back"); j.expect("Pause sharing")
            j.expect(WARN); j.expect("On plain Wi-Fi, anyone on this network who can capture traffic could see the Mac's screen. Tailscale encrypts it. Frames are never saved.")
            j.expect("Show the screen")
        }
        j.step("S3") {
            // from the setup mark: the live-screen-D4 race may already have logged this line for the same session (no second line on a restart)
            j.tap("Show the screen"); j.expect(VIEWER, timeoutMs = 5_000); mac.await("screen: streaming to \\Q$phone\\E via Wi-Fi")
        }
        j.step("S4") {
            j.expect(VIEWER, timeoutMs = 5_000)
            j.expectGone("Waiting for the first frame…", timeoutMs = 5_000)
            j.expect("Stream stats", timeoutMs = 5_000)
            j.expect(" fps · ", substring = true)
        }
        j.manual("S5", "menu-bar title / menu items (headless); S3's log line is the stand-in")
        j.step("S6") { j.dlTouch(VIEWER) { click(center) }; mac.await("$CENTER b=left count=1") }
        j.step("S7") {
            Thread.sleep(600)
            j.dlTouch(VIEWER) { click(center); advanceEventTime(120); click(center) }
            mac.await("$CENTER b=left count=1"); mac.await("$CENTER b=left count=2")
        }
        j.step("S8") { Thread.sleep(600); j.dlTouch(VIEWER) { longClick(center) }; mac.await("$CENTER b=right count=1") }
        j.step("S9") {
            Thread.sleep(600)
            j.dlTouch(VIEWER) { pinch(center - Offset(20f, 0f), center - Offset(200f, 0f), center + Offset(20f, 0f), center + Offset(200f, 0f)) }
            mac.assertNo("DRY clickAt", 1_500)
            j.expectNot("Pinch to zoom", substring = true)
        }
        j.step("S10") { j.device.pressBack(); pump(); mac.await("screen: stopped \\(stop\\) after \\d+ frames, \\d+ KiB, \\d+ dropped"); j.expect("Play or pause") }
    }

    @Test fun secondOpen() {
        j.path("live-screen.second-open")
        setup(); streaming(); j.tap("Back"); pump(); mac.await("screen: stopped \\(stop\\)")
        j.step("B1") { openPage(); j.expectNot(WARN); mac.await("screen: streaming to \\Q$phone\\E via Wi-Fi"); j.expect(VIEWER, timeoutMs = 5_000) }
    }

    @Test fun warningRace() {
        j.path("live-screen.warning-race")
        setup()
        j.step("B1") {
            openPage(); j.expect(WARN, timeoutMs = 5_000)
            // Spec: a start may slip out before the notice settles (live-screen-D4); never a frame after it.
            Thread.sleep(1_500)
            mac.from = 0
            val started = mac.lines().any { it.contains("screen: streaming") }
            if (started) throw AssertionError("a stream started before the LAN warning was acknowledged (live-screen-D4)")
        }
    }

    @Test fun phonePause() {
        j.path("live-screen.phone-pause")
        setup(); streaming()
        j.step("B1") {
            j.tap("Pause sharing"); pump(); mac.await("screen: stopped \\(stop\\)")
            j.expect("Sharing paused"); j.expect("Resume sharing")
            j.dlTouch(VIEWER) { click(center) }; mac.assertNo("DRY clickAt", 1_000)
        }
        j.step("B2") { j.tap("Resume sharing"); pump(); mac.await("screen: streaming to \\Q$phone\\E via Wi-Fi"); j.expectGone("Sharing paused") }
    }

    @Test fun background() {
        j.path("live-screen.background")
        setup(); streaming()
        j.step("B1") { j.device.pressHome(); pump(); mac.await("screen: stopped \\(stop\\)", 10_000) }
        j.step("B2") {
            j.shell("am start -n dev.mote.remote.dev/dev.mote.remote.MainActivity")
            pump(); mac.await("screen: streaming to \\Q$phone\\E via Wi-Fi", 10_000)
            j.expect(VIEWER, timeoutMs = 5_000)
        }
    }

    @Test fun macPause() {
        j.path("live-screen.mac-pause")
        setup(); streaming()
        j.step("B1") { mac.stdin("screen-pause"); mac.await("screen: sharing paused"); mac.await("screen: stopped \\(paused\\)"); j.expect(PAUSED_MSG, timeoutMs = 5_000) }
        j.step("B2") {
            mac.stdin("screen-resume"); mac.await("screen: sharing resumed")
            mac.assertNo("screen: streaming", 2_000); j.expect(PAUSED_MSG)
        }
        j.step("B3") {
            j.tap("Pause sharing"); j.tap("Resume sharing", timeoutMs = 5_000)
            j.expectGone(PAUSED_MSG, timeoutMs = 5_000); j.expect(" fps · ", substring = true) // same viewer: the helper logs no new "streaming to"
        }
    }

    @Test fun macPausedAtStart() {
        j.path("live-screen.mac-paused-at-start")
        setup()
        j.step("B1") {
            mac.stdin("screen-pause"); mac.await("screen: sharing paused")
            openPage(); j.tap("Show the screen", timeoutMs = 5_000)
            j.expect(PAUSED_MSG, timeoutMs = 5_000); j.expectNot("Waiting for the first frame…")
            mac.assertNo("screen: streaming", 500)
        }
    }

    @Test fun labOffMid() {
        j.path("live-screen.lab-off-mid")
        setup(); streaming()
        j.step("B1") { mac.stdin("screen-lab-off"); mac.await("labs: live screen off"); mac.await("screen: stopped \\(off\\)"); j.expect(LABOFF_MSG, timeoutMs = 5_000) }
        j.step("B2") { mac.stdin("screen-lab-on"); mac.await("labs: live screen on"); mac.assertNo("screen: streaming", 2_000); j.expect(LABOFF_MSG) }
    }

    @Test fun labOffAtStart() {
        j.path("live-screen.lab-off-at-start")
        setup()
        mac.stdin("screen-lab-off"); mac.await("labs: live screen off")
        j.step("B1") { openPage(); j.tap("Show the screen", timeoutMs = 5_000); j.expect(LABOFF_MSG, timeoutMs = 5_000) }
    }

    @Test fun dryRunNoFake() {
        j.path("live-screen.dry-run-no-fake")
        setup(fake = false)
        mac.stdin("screen-lab-on"); mac.await("labs: live screen on")
        j.step("B1") { openPage(); j.tap("Show the screen", timeoutMs = 5_000); j.expect("Live screen is off in a dry run (use --fake-screen)", timeoutMs = 5_000) }
    }

    @Test fun inputPausedClick() {
        j.path("live-screen.input-paused-click")
        setup(); streaming()
        j.step("B1") { mac.stdin("pause"); mac.await("input paused"); Thread.sleep(500); j.expectNot(PAUSED_MSG) }
        j.step("B2") { j.dlTouch(VIEWER) { click(center) }; j.expect(PAUSED_MSG, timeoutMs = 5_000); mac.assertNo("DRY clickAt", 500) }
    }

    @Test fun letterboxTap() {
        j.path("live-screen.letterbox-tap")
        setup(); streaming()
        j.step("B1") { j.dlTouch(VIEWER) { click(Offset(centerX, 4f)) }; mac.assertNo("DRY clickAt", 1_500) }
    }

    @Test fun linkDropKill() {
        j.path("live-screen.link-drop")
        setup(); streaming()
        j.step("B1") {
            mac.stop()
            j.expect("Connecting to the Mac…", timeoutMs = 60_000); j.expect("The screen shows once Mote Helper answers.")
        }
    }

    @Test fun linkDropWifi() {
        j.path("live-screen.link-drop")
        setup(); streaming()
        j.step("B2") {
            // On the emulator 10.0.2.2 is reachable over mobile data too, so Wi-Fi off alone keeps the socket up;
            // a real link drop means no network at all (resetNetwork() turns data back on for the next test).
            j.wifi(false); j.shell("svc data disable")
            mac.await("screen: stopped \\((timeout|gone)\\)", 15_000)
            j.expect("Connecting to the Mac…", timeoutMs = 60_000)
        }
    }

    @Test fun quickReconnect() {
        j.path("live-screen.quick-reconnect")
        setup(); streaming()
        j.step("B1") {
            // Emulator: with Wi-Fi off the host is still reachable over mobile data, so the link comes back in
            // well under a second and the status stays Ready (no "Connecting" flicker). The page must start
            // the stream again on the new socket, not keep the last frame.
            mac.mark()
            j.wifi(false)
            mac.await("screen: stopped \\((timeout|gone)\\)", 15_000)
            val end = System.currentTimeMillis() + 15_000
            while (true) { // frames only recompose when Compose is pumped, and await blocks the test thread
                pump()
                if (runCatching { mac.await("screen: streaming to \\Q$phone\\E", 1_000) }.isSuccess) break
                if (System.currentTimeMillis() > end) throw AssertionError("stream not restarted on the new socket within 15 s")
            }
            j.expectNot("Connecting to the Mac…")
        }
    }

    @Test fun bluetooth() {
        j.path("live-screen.bluetooth")
        j.dlLabs(Labs.LIVE_SCREEN)
        j.dlBluetoothOnly()
        j.step("B1", "faked: TestSeam.transport FakeTransport (HID caps)") {
            openPage()
            j.expect("Live screen needs Mote Helper"); j.expect("Needs Wi-Fi or Tailscale; connected over Bluetooth. Bluetooth can't carry the picture.")
            j.expectNot("Pause sharing")
        }
    }

    @Test fun labOffPhone() {
        j.path("live-screen.lab-off-phone")
        setup()
        j.step("B1") {
            j.dlToggleLabUi("Live screen (preview)")
            j.tap("Menu"); j.expect("Settings"); j.expectNot("Screen (preview)")
        }
    }
}
