package dev.mote.remote.journeys

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.mote.remote.labs.Labs
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** docs/journeys/video-bridge.md */
@RunWith(AndroidJUnit4::class)
class VideoBridgeJourney {
    @get:Rule val j = JourneyRule()

    private val CLIENT = "faked: scripted bridge client (WebSocket from the test, Origin chrome-extension://motejourneytest, Host 127.0.0.1:<port>) stands in for the extension (P5b)"
    private val TITLE = "How a lathe works, in 12 minutes"

    private lateinit var mac: Helper
    private var client: DlBridgeClient? = null
    private var bport = 0

    @After fun closeClient() { client?.close(); j.closeActivity() } // also disposes the UI first (harness teardown crash, Bt.observe awaitClose on the test thread)

    private fun state(time: Double = 245.0, paused: Boolean = false, extra: (JSONObject) -> Unit = {}) = JSONObject()
        .put("active", true).put("title", TITLE).put("host", "www.youtube.com").put("site", "youtube")
        .put("duration", 720).put("time", time).put("paused", paused).put("rate", 1).put("seekable", true)
        .put("captions", "off").put("skips", JSONArray(listOf("ad"))).also(extra)

    /** Helper + phone connected with the lab on (P1-P4). [bridge]: turn the bridge on (stdin) and read its port. */
    private fun setup(bridge: Boolean = true, lab: Boolean = true) {
        val (m, name) = j.dlHelper()
        mac = m
        if (lab) j.dlLabs(Labs.VIDEO_BRIDGE)
        j.dlConnect(mac, name)
        if (bridge) bridgeOn()
    }

    private fun bridgeOn() {
        mac.mark(); mac.stdin("bridge on"); mac.await("bridge: on")
        bport = Regex("listening on 127\\.0\\.0\\.1:(\\d+)").find(mac.await("bridge: listening on 127\\.0\\.0\\.1:\\d+"))!!.groupValues[1].toInt()
    }

    private fun code(): String { mac.mark(); mac.stdin("bridge pair"); return mac.await("^BRIDGE CODE: \\d{6}").substringAfter("BRIDGE CODE: ").trim() }

    private fun pairClient(): DlBridgeClient {
        val c = DlBridgeClient(bport).also { client = it }
        c.open(); c.pairAndAuth(code())
        return c
    }

    private fun playing(c: DlBridgeClient) { c.state(state()); j.expect("Pause video", timeoutMs = 5_000) }

    @Test fun main() {
        j.path("video-bridge")
        setup(bridge = false)
        j.step("S1") { bridgeOn() }
        j.manual("S2", "Chrome › Extensions › Load unpacked (browser UI); the scripted client stands in from S4")
        lateinit var c: DlBridgeClient
        var code = ""
        j.step("S3") { code = code(); if (!Regex("\\d{6}").matches(code)) throw AssertionError("code '$code'") }
        j.step("S4", CLIENT) {
            c = DlBridgeClient(bport).also { client = it }
            c.open(); c.pairAndAuth(code)
            mac.await("bridge: paired chrome-extension://motejourneytest")
            mac.await("bridge: authed chrome-extension://motejourneytest")
        }
        j.step("S5") { j.expect("Play or pause"); j.expectNot("Pause video"); j.expectNot("Video position") }
        j.step("S6", CLIENT) {
            c.state(state())
            j.expect(TITLE, timeoutMs = 5_000); j.expect("PREVIEW"); j.expect("youtube.com")
            j.expect("12:00")
            j.dlExpectState("Video position", Regex("4:0\\d of 12:00"))
            for (d in listOf("Back 10 seconds", "Pause video", "Forward 10 seconds", "Playback speed 1×, tap for the next speed",
                "Captions off, tap to turn on", "Full screen", "Skip ad")) j.expect(d)
            for (t in listOf("−10", "+10", "1×", "CC", "Full")) j.expect(t)
            j.expectNot("FAVOURITES"); j.expectNot("Brightness down") // replaced by the panel
        }
        j.step("S7") {
            mac.mark(); j.tap("Forward 10 seconds"); mac.await("bridge: cmd seekBy 10\\.0")
            val m = c.next("labs.video.cmd"); if (m.getString("cmd") != "seekBy") throw AssertionError("client got $m")
            j.dlExpectState("Video position", Regex("4:1\\d of 12:00"))
        }
        j.step("S8") { j.dlSetProgress("Video position", 360f); mac.await("bridge: cmd seek 360\\.0") } // a11y setProgress is in the range units (seconds)
        j.step("S9") {
            j.tap("Playback speed 1×, tap for the next speed"); mac.await("bridge: cmd rate 1\\.25")
            j.expect("1.3×"); j.expect("Playback speed 1.3×, tap for the next speed")
        }
        j.step("S10") { j.tap("Captions off, tap to turn on"); mac.await("bridge: cmd captions$"); j.expect("Captions on, tap to turn off") }
        j.step("S11") { j.tap("Skip ad"); mac.await("bridge: cmd skip ad"); j.expectGone("Skip ad") }
        j.step("S12") { j.tap("Pause video"); mac.await("bridge: cmd toggle"); j.expect("Play video") }
        j.step("S13", CLIENT) {
            c.state(state(time = 600.0, paused = true) { it.put("rate", 1.5).put("captions", "on").put("skips", JSONArray()) })
            j.dlExpectState("Video position", Regex("10:00 of 12:00"))
            j.expect("Play video"); j.expect("Playback speed 1.5×, tap for the next speed")
        }
    }

    @Test fun helperOff() {
        j.path("video-bridge.helper-off")
        setup(bridge = false)
        j.step("B1") { Thread.sleep(1_000); j.expect("Play or pause"); j.expectNot("Video position"); j.expectNot("PREVIEW") }
        j.manual("B2", "extension popup copy (browser UI)")
    }

    @Test fun waiting() {
        j.path("video-bridge.waiting")
        setup()
        j.step("B1") { Thread.sleep(1_000); j.expect("Play or pause"); j.expectNot("Video position") }
    }

    @Test fun wrongCode() {
        j.path("video-bridge.wrong-code")
        setup()
        val c = DlBridgeClient(bport).also { client = it }
        c.open()
        val good = code()
        val bad = if (good == "000000") "111111" else "000000"
        j.step("B1", CLIENT) {
            c.send(JSONObject().put("t", "labs.video.pair").put("code", bad))
            val e = c.next("err"); if (e.optString("code") != "code" || e.optString("msg") != "Wrong code") throw AssertionError("got $e")
            mac.await("bridge: wrong pairing code")
        }
        j.step("B2", CLIENT) {
            var rate: JSONObject? = null
            repeat(6) {
                if (rate == null) {
                    c.send(JSONObject().put("t", "labs.video.pair").put("code", bad))
                    val e = c.next("err"); if (e.optString("code") == "rate") rate = e
                }
            }
            val r = rate ?: throw AssertionError("no err rate after 7 wrong codes")
            if (r.optString("msg") != "too many attempts" || r.optInt("retryAfter", 0) <= 0) throw AssertionError("got $r")
        }
    }

    @Test fun pairingClosed() {
        j.path("video-bridge.pairing-closed")
        setup()
        j.step("B1", CLIENT) {
            val c = DlBridgeClient(bport).also { client = it }
            c.open()
            c.send(JSONObject().put("t", "labs.video.pair").put("code", "123456"))
            val e = c.next("err")
            if (e.optString("code") != "pairing-closed" || e.optString("msg") != "Open Labs › Pair browser extension… in the Mote Helper menu") throw AssertionError("got $e")
        }
    }

    @Test fun noVideo() {
        j.path("video-bridge.no-video")
        setup(); val c = pairClient(); playing(c)
        j.step("B1", CLIENT) {
            c.state(JSONObject().put("active", false))
            j.expectGone("Pause video"); j.expect("Play or pause"); j.expectNot("Video position")
        }
        j.undefined("B2", "race: a tap landing between the page losing its video and the phone hearing it can't be timed from the test")
    }

    @Test fun live() {
        j.path("video-bridge.live")
        setup(); val c = pairClient()
        j.step("B1", CLIENT) {
            c.state(state { it.put("live", true).put("duration", JSONObject.NULL).put("seekable", false) })
            j.expect("LIVE", timeoutMs = 5_000)
            j.dlExpectState("Video position", Regex("Live"))
            mac.mark(); j.tap("Forward 10 seconds"); mac.assertNo("bridge: cmd", 1_000)
        }
    }

    @Test fun options() {
        j.path("video-bridge.options")
        setup(); val c = pairClient(); playing(c)
        j.step("B1", CLIENT) { c.state(state { it.put("captions", "none") }); j.expectGone("CC"); j.expectNot("Captions off, tap to turn on") }
        j.step("B2", CLIENT) {
            c.state(state { it.put("hasPrev", true).put("hasNext", true) })
            mac.mark()
            j.tap("Previous video", timeoutMs = 5_000); mac.await("bridge: cmd prev")
            j.tap("Next video"); mac.await("bridge: cmd next")
        }
        j.step("B3", CLIENT) {
            c.state(state { it.put("skips", JSONArray(listOf("intro", "ad"))) })
            j.expect("Skip ad", timeoutMs = 5_000); j.expectNot("Skip intro")
        }
        j.step("B4") { mac.mark(); j.tap("Full screen"); mac.await("bridge: cmd fullscreen"); j.expect("Exit full screen"); j.expect("Exit full") }
        j.step("B5", CLIENT) {
            c.state(state { it.put("rate", 2) }); j.expect("Playback speed 2×, tap for the next speed", timeoutMs = 5_000)
            mac.mark(); j.tap("Playback speed 2×, tap for the next speed"); mac.await("bridge: cmd rate 0\\.5")
            j.expect("0.50×")
        }
    }

    @Test fun paused() {
        j.path("video-bridge.paused")
        setup(); val c = pairClient(); playing(c)
        j.step("B1") { mac.mark(); mac.stdin("pause"); mac.await("input paused") }
        j.step("B2") {
            j.tap("Pause video"); j.expect("Play video", timeoutMs = 3_000)
            mac.assertNo("bridge: cmd toggle", 1_000)
            j.expectNot("input is paused on the Mac", substring = true)
        }
    }

    @Test fun pageRefused() {
        j.path("video-bridge.page-refused")
        setup(); val c = pairClient(); playing(c)
        j.step("B1", CLIENT) {
            mac.mark()
            c.send(JSONObject().put("t", "labs.video.result").put("cmd", "next").put("ok", false).put("code", "notfound"))
            mac.await("bridge: next failed: notfound")
            Thread.sleep(1_000)
            j.expectNot("The page couldn't do next")
        }
    }

    @Test fun forget() {
        j.path("video-bridge.forget")
        setup(); val c = pairClient(); playing(c)
        j.step("B1", "real + $CLIENT") {
            mac.mark(); mac.stdin("bridge forget"); mac.await("bridge: extension forgotten")
            val e = c.next("err"); if (e.optString("code") != "revoked") throw AssertionError("client got $e")
            j.expectGone("Pause video", timeoutMs = 5_000)
        }
    }

    @Test fun revoke() {
        j.path("video-bridge.forget")
        setup(); val c = pairClient(); playing(c)
        j.step("B2") {
            mac.mark(); mac.stdin("revoke"); mac.await("bridge: extension forgotten"); mac.await("all devices revoked")
            val e = c.next("err"); if (e.optString("code") != "revoked") throw AssertionError("client got $e")
        }
    }

    @Test fun notHelperBluetooth() {
        j.path("video-bridge.not-helper")
        j.dlLabs(Labs.VIDEO_BRIDGE)
        j.dlBluetoothOnly()
        j.step("B1", "faked: TestSeam.transport FakeTransport (HID caps)") {
            j.expect("Play or pause", timeoutMs = 8_000); j.expectNot("Video position"); j.expectNot("Pause video")
        }
    }

    @Test fun notHelperKill() {
        j.path("video-bridge.not-helper")
        setup(); val c = pairClient(); playing(c)
        j.step("B2") { mac.stop(); j.expectGone("Pause video", timeoutMs = 60_000) }
    }

    @Test fun labOffPhone() {
        j.path("video-bridge.lab-off-phone")
        setup(); val c = pairClient(); playing(c)
        j.step("B1") { j.dlToggleLabUi("Browser video control (preview)"); j.expect("Play or pause", timeoutMs = 5_000); j.expectNot("Pause video") }
    }
}
