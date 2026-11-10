package dev.mote.remote.journeys

import android.Manifest
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.mote.remote.e2e.FakeTransport
import dev.mote.remote.hid.Host
import dev.mote.remote.hid.TestSeam
import dev.mote.remote.labs.Labs
import dev.mote.remote.net.Caps
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** docs/journeys/smart-remote.md */
@RunWith(AndroidJUnit4::class)
class SmartRemoteJourney {
    @get:Rule val j = JourneyRule()

    /** Works around a harness teardown crash (Bt.observe awaitClose on the test thread): dispose the UI first. */
    @org.junit.After fun dlCloseUi() { j.closeActivity() }


    private val FILE = "faked: front app = --fake-context file (controller /file)"
    private val NETFLIX = """{"app":"Netflix","bundle":"com.netflix.Netflix"}"""
    private val YOUTUBE = """{"app":"Google Chrome","bundle":"com.google.Chrome","url":"https://www.youtube.com/watch?v=x","title":"t","browser":true}"""
    private fun safari(text: Boolean, secure: Boolean = false) =
        """{"app":"Safari","bundle":"com.apple.Safari","url":"https://www.google.com/","browser":true,"text":$text,"secure":$secure}"""

    private lateinit var mac: Helper
    private lateinit var ctxFile: String
    private fun file(json: String) { ctxFile = dlFile("ctx-${j.path.replace('.', '-')}.json", json) }

    /** P1-P4: helper with the fake context (+ now playing), lab on, phone connected over Wi-Fi. */
    private fun setup(ctx: String? = NETFLIX, np: Boolean = true, smart: Boolean = true) {
        val flags = mutableListOf<String>()
        if (ctx != null) { file(ctx); flags += listOf("--fake-context", ctxFile) }
        if (np) flags += "--fake-nowplaying"
        val (m, name) = j.dlHelper(*flags.toTypedArray())
        mac = m
        if (smart) j.dlLabs(Labs.SMART_REMOTE)
        j.dlConnect(mac, name)
    }

    private fun mainUntilKeys() {
        j.step("S1") { mac.from = 0; mac.await("labs: context & now playing on \\(fake context now-playing\\)") }
        j.step("S2") { j.expect("NETFLIX", timeoutMs = 8_000); j.expect("Fake Song") }
        j.step("S3") {
            j.expect("PREVIEW"); j.expect("NETFLIX"); j.expect("♪"); j.expect("Fake Song"); j.expect("Mote Labs · Music"); j.expect("PLAYING")
            j.expect("Back 10 seconds"); j.expect("−10"); j.expect("Forward 10 seconds"); j.expect("+10")
            j.dlExpectState("Playback position", Regex("0:4\\d of 5:00"))
            j.expect("5:00")
            for (d in listOf("Skip intro or recap (S)", "Toggle fullscreen (F)", "Leave fullscreen (Esc)", "Mute (M)")) j.expect(d)
            for (t in listOf("Skip intro", "Fullscreen", "Exit full", "Mute")) j.expect(t)
            j.expect("Play or pause") // Media stays below
        }
        j.step("S4") { mac.mark(); j.tap("Skip intro or recap (S)"); mac.await("DRY key usage=22 mods=0 action=tap") }
        j.step("S5") { j.tap("Toggle fullscreen (F)"); mac.await("DRY key usage=9 mods=0 action=tap") }
        j.step("S6") {
            j.dlSetProgress("Playback position", 0.5f)
            mac.await("DRY labs seek via=fake to=150\\.0")
            j.dlExpectState("Playback position", Regex("2:3\\d of 5:00"))
        }
        j.step("S7") { j.tap("Forward 10 seconds"); mac.await("DRY labs seek via=fake to=1[5-6]\\d\\.\\d") }
        j.step("S8", FILE) {
            file(YOUTUBE)
            j.expect("YOUTUBE · GOOGLE CHROME", timeoutMs = 5_000)
            for (d in listOf("Toggle fullscreen (F)", "Theater mode (T)", "Next video (shift N)", "Captions on or off (C)", "Slower (shift comma)", "Faster (shift period)")) j.expect(d)
            j.expect("Fake Song")
        }
        j.step("S9") { mac.mark(); j.tap("Next video (shift N)"); mac.await("DRY key usage=17 mods=2 action=tap") }
        j.step("S10", "faked: focus = text:true in the --fake-context file") {
            file(safari(true))
            j.expect("Text field on the Mac · Safari", timeoutMs = 5_000)
            j.expect("Stop auto-keyboard")
            j.expectNot("Play or pause")
        }
    }

    @Test fun main() {
        j.path("smart-remote")
        setup()
        mainUntilKeys()
        j.step("S11") {
            j.tap("Stop auto-keyboard")
            j.expectGone("Text field on the Mac · Safari")
            j.expectNot("Play or pause")
            if (runBlocking { Labs.bool(j.ctx, "smart_remote.auto_keyboard", true).first() }) throw AssertionError("auto_keyboard still true")
        }
    }

    @Test fun textBlur() {
        j.path("smart-remote.text-blur")
        setup(); file(safari(true)); j.expect("Text field on the Mac · Safari", timeoutMs = 6_000)
        j.step("B1", FILE) {
            file(safari(false))
            j.expect("Play or pause", timeoutMs = 5_000)
            j.expectNot("Text field on the Mac · Safari")
        }
    }

    @Test fun manualLeave() {
        j.path("smart-remote.manual-leave")
        setup(); file(safari(true)); j.expect("Text field on the Mac · Safari", timeoutMs = 6_000)
        j.step("B1") { j.tap("Media"); j.expect("Play or pause") }
        j.step("B2", FILE) {
            file(safari(false)); Thread.sleep(1_500)
            j.expect("Play or pause"); j.expectNot("Stop auto-keyboard")
        }
    }

    @Test fun password() {
        j.path("smart-remote.password")
        setup()
        j.step("B1", FILE) { file(safari(true, secure = true)); j.expect("Password field on the Mac · Safari", timeoutMs = 6_000); j.expect("Stop auto-keyboard") }
    }

    @Test fun autoOffTurnOn() {
        j.path("smart-remote.auto-off-turn-on")
        setup(); file(safari(true)); j.expect("Stop auto-keyboard", timeoutMs = 6_000); j.tap("Stop auto-keyboard")
        j.step("B1") { j.tap("Media"); j.expect("Auto-keyboard off · turn on", timeoutMs = 5_000) }
        j.step("B2", FILE) {
            file(safari(false)); Thread.sleep(800); file(safari(true)); Thread.sleep(1_500)
            j.expect("Play or pause"); j.expectNot("Stop auto-keyboard")
        }
        j.step("B3", "real + faked: --fake-context file") {
            j.tap("Auto-keyboard off · turn on"); j.expectGone("Auto-keyboard off · turn on")
            file(safari(false)); Thread.sleep(800); file(safari(true))
            j.expect("Text field on the Mac · Safari", timeoutMs = 5_000)
        }
    }

    @Test fun noProfile() {
        j.path("smart-remote.no-profile")
        setup(ctx = """{"app":"Finder","bundle":"com.apple.finder"}""")
        j.step("B1", FILE) {
            j.expect("FINDER", timeoutMs = 8_000); j.expect("Fake Song")
            j.expectNot("Toggle fullscreen (F)"); j.expectNot("Skip intro or recap (S)")
        }
    }

    @Test fun noProfileNoNowPlaying() {
        j.path("smart-remote.no-profile")
        setup(ctx = """{"app":"Finder","bundle":"com.apple.finder"}""", np = false)
        j.step("B2") {
            mac.from = 0; mac.await("labs: now-playing reader off in dry run", 8_000)
            Thread.sleep(1_500)
            j.expectNot("PREVIEW"); j.expectNot("FINDER"); j.expectNot("SMART REMOTE")
        }
    }

    @Test fun noNowPlaying() {
        j.path("smart-remote.no-nowplaying")
        setup(np = false)
        j.step("B1") {
            j.expect("NETFLIX", timeoutMs = 8_000); j.expect("PREVIEW")
            for (d in listOf("Skip intro or recap (S)", "Toggle fullscreen (F)", "Leave fullscreen (Esc)", "Mute (M)")) j.expect(d)
            j.expectNot("Playback position"); j.expectNot("Back 10 seconds")
        }
    }

    @Test fun videoApp() {
        j.path("smart-remote.video-app")
        setup(ctx = """{"app":"QuickTime Player","bundle":"com.apple.QuickTimePlayerX"}""")
        j.step("B1", FILE) {
            j.expect("VIDEO · QUICKTIME PLAYER", timeoutMs = 8_000)
            mac.mark()
            j.tap("Toggle fullscreen (control command F)"); mac.await("DRY key usage=9 mods=9 action=tap")
            j.tap("Play or pause (space)"); mac.await("DRY key usage=44 mods=0 action=tap")
            j.tap("Back 5 seconds (left arrow)"); mac.await("DRY key usage=80 mods=0 action=tap")
            j.tap("Forward 5 seconds (right arrow)"); mac.await("DRY key usage=79 mods=0 action=tap")
        }
        j.step("B2", FILE) {
            file("""{"app":"Safari","bundle":"com.apple.Safari","url":"https://vimeo.com/1","browser":true}""")
            j.expect("VIDEO · SAFARI", timeoutMs = 5_000)
            mac.mark(); j.tap("Toggle fullscreen (F)"); mac.await("DRY key usage=9 mods=0 action=tap")
        }
    }

    @Test fun helperLabOff() {
        j.path("smart-remote.helper-lab-off")
        setup(ctx = null, np = false)
        j.step("B1") {
            j.expect("SMART REMOTE", timeoutMs = 8_000); j.expect("PREVIEW")
            j.expect("Turn on Labs › Context & now playing in the Mote Helper menu on the Mac.")
        }
    }

    @Test fun noHelperBluetooth() {
        j.path("smart-remote.no-helper")
        j.dlLabs(Labs.SMART_REMOTE)
        j.dlBluetoothOnly()
        j.step("B1", "faked: TestSeam.transport = FakeTransport (HID caps)") {
            j.expect("SMART REMOTE", timeoutMs = 8_000)
            j.expect("Smart remote needs the Mote Helper over Wi-Fi or Tailscale.")
        }
    }

    @Test fun noHelperKill() {
        j.path("smart-remote.no-helper")
        setup()
        j.expect("NETFLIX", timeoutMs = 8_000)
        j.step("B2") {
            mac.stop()
            j.expect("Smart remote needs the Mote Helper over Wi-Fi or Tailscale.", timeoutMs = 60_000)
            j.expect("SMART REMOTE")
        }
    }

    @Test fun automationDenied() {
        j.path("smart-remote.automation-denied")
        setup(ctx = """{"app":"Safari","bundle":"com.apple.Safari","browser":true,"automation":"denied"}""")
        j.step("B1", FILE) { j.expect("Allow Mote Helper to control Safari (System Settings › Privacy › Automation) to see the tab.", timeoutMs = 8_000) }
    }

    @Test fun automationAsk() {
        j.path("smart-remote.automation-ask")
        setup(ctx = """{"app":"Safari","bundle":"com.apple.Safari","browser":true,"automation":"ask"}""")
        j.step("B1", FILE) {
            j.expect("SAFARI", timeoutMs = 8_000); j.expect("Fake Song")
            j.expectNot("Allow Mote Helper to control Safari", substring = true)
            j.expectNot("Allow Safari tabs", substring = true)
        }
    }

    @Test fun seekPaused() {
        j.path("smart-remote.seek-paused")
        setup()
        j.expect("Playback position", timeoutMs = 8_000)
        j.step("B1") { mac.mark(); mac.stdin("pause"); mac.await("input paused") }
        j.step("B2") {
            j.dlSetProgress("Playback position", 0.5f)
            j.expect("input is paused on the Mac", timeoutMs = 5_000)
            mac.assertNo("DRY labs seek", 1_000)
            j.dlExpectState("Playback position", Regex("0:[4-5]\\d of 5:00"))
        }
        j.step("B3") { j.tap("Skip intro or recap (S)"); mac.assertNo("DRY key", 1_500) }
    }

    @Test fun labOffPhone() {
        j.path("smart-remote.lab-off-phone")
        setup()
        j.expect("NETFLIX", timeoutMs = 8_000)
        j.step("B1") {
            j.dlToggleLabUi("Smart remote (preview)")
            j.expect("Play or pause", timeoutMs = 5_000)
            j.expectNot("NETFLIX"); j.expectNot("PREVIEW"); j.expectNot("Fake Song")
        }
    }
}
