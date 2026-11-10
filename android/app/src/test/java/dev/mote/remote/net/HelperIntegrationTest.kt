package dev.mote.remote.net

import dev.mote.remote.hid.Consumer
import dev.mote.remote.hid.Mod
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Collections

/**
 * End to end against the real helper in dry-run (nothing is injected): pair -> HMAC auth ->
 * input through [WsTransport], checked against the helper's `DRY ...` log lines.
 *
 * Needs a built helper (`helper/build.sh`); skipped otherwise. Override the binary with
 * `-Dmote.helper=/path/to/MoteHelper` or `MOTE_HELPER=...`.
 */
class HelperIntegrationTest {
    private lateinit var proc: Process
    private val log: MutableList<String> = Collections.synchronizedList(ArrayList())
    private var port = 0
    private lateinit var code: String
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http = helperHttpClient()

    private fun helperBinary(): File? {
        val p = System.getProperty("mote.helper") ?: System.getenv("MOTE_HELPER")
        if (p != null) return File(p).takeIf { it.canExecute() }
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val f = File(dir, "helper/build/MoteHelper.app/Contents/MacOS/MoteHelper")
            if (f.canExecute()) return f
            dir = dir.parentFile
        }
        return null
    }

    @Before fun startHelper() = spawn()

    /** Starts a helper with [extra] args; both its stdout and stderr go to [log]. */
    private fun spawn(vararg extra: String) {
        val bin = helperBinary()
        assumeTrue("helper not built; run helper/build.sh", bin != null)
        // --port 0: the helper binds any free port and logs it (no race with other helpers on this Mac).
        val args = listOf(bin!!.path, "--dry-run", "--headless", "--no-ble", "--memory-store", "--open-pairing", "--port", "0") + extra
        proc = ProcessBuilder(args).start()
        val p = proc
        Thread { p.errorStream.bufferedReader().forEachLine { log += it } }.apply { isDaemon = true }.start()
        Thread { p.inputStream.bufferedReader().forEachLine { log += it } }.apply { isDaemon = true }.start()
        code = waitFor("PAIRING CODE:").substringAfter("PAIRING CODE:").trim()
        port = Regex("""listening on :(\d+)""").find(waitFor("wifi: listening on :"))!!.groupValues[1].toInt()
    }

    private fun seen(fragment: String, timeoutMs: Long): String? = runCatching { waitFor(fragment, timeoutMs) }.getOrNull()

    @After fun stopHelper() {
        scope.cancel()
        if (::proc.isInitialized) proc.destroy()
    }

    private fun waitFor(fragment: String, timeoutMs: Long = 5000): String {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            synchronized(log) { log.firstOrNull { fragment in it } }?.let { return it }
            Thread.sleep(20)
        }
        throw AssertionError("helper never logged \"$fragment\"; log:\n" + synchronized(log) { log.joinToString("\n") })
    }

    private fun dry(prefix: String) = synchronized(log) { log.filter { "DRY $prefix" in it } }

    /** Protocol 1.5: auth carries `client`, the helper answers `diag` and echoes our `corr`. */
    @Test fun diagSnapshotAndCorrOverWebSocket() = runBlocking {
        val p = pairWith(http, "127.0.0.1", port, code, "jvm diag")
        val token = p.token.copyOf()
        dev.mote.remote.diag.HelperDiag.clear()
        val t = WsTransport(http, scope, p.hostId, {
            WsTarget(listOf("127.0.0.1"), port, p.deviceId) { Proto.hmacHex(token, it) }
        })
        t.start()
        withTimeout(10_000) { t.status.first { it is WsStatus.Ready } }
        assertTrue(t.sendLabs(Proto.diag()))
        val s = withTimeout(10_000) {
            dev.mote.remote.diag.HelperDiag.state.first { it.snapshot != null }
        }
        assertEquals(dev.mote.remote.diag.HelperDiag.Status.OK, s.status)
        assertEquals("1.5", s.snapshot!!.optJSONObject("helper")?.optString("protocol"))
        assertTrue("snapshot echoes our corr", s.snapshot!!.optString("corr").isNotEmpty())
        dev.mote.remote.diag.HelperDiag.clear()
    }

    @Test fun pairAuthAndSendInputOverWebSocket() = runBlocking {
        val p = pairWith(http, "127.0.0.1", port, code, "jvm test")
        assertTrue(p.hello.hostId!!.matches(Regex("[0-9a-f]{16}")))
        val token = p.token.copyOf()

        // First candidate doesn't resolve: the transport falls back to the next one.
        val t = WsTransport(http, scope, p.hostId, {
            WsTarget(listOf("no-such-host.invalid", "127.0.0.1"), port, p.deviceId) { Proto.hmacHex(token, it) }
        })
        t.start()
        val ready = withTimeout(10_000) { t.status.first { it is WsStatus.Ready || it is WsStatus.Failed } }
        assertEquals("127.0.0.1", (ready as WsStatus.Ready).address)
        waitFor("authed jvm test via Wi-Fi")

        t.tap("a", Mod.CMD)
        waitFor("DRY key usage=4 mods=8 action=tap")

        t.setMods(dev.mote.remote.hid.Hid.Mods(armed = Mod.SHIFT))
        t.tap("b")
        waitFor("DRY key usage=5 mods=2 action=tap")
        assertEquals("armed modifier is one-shot", 0, t.mods.value.armed)

        t.typeText("Grüße 東京 👋")
        waitFor("DRY text \"Grüße 東京 👋\"")

        t.consumer(Consumer.BRIGHTNESS_UP)
        waitFor("DRY consumer brightup")

        // 60 Hz coalescing: 20 quick moves become a few messages with the same total.
        repeat(20) { t.move(1.5f, 0f) }
        t.click(1) // flushes pending motion first
        waitFor("DRY button left click count=1")
        val moves = dry("move")
        val sum = moves.sumOf { Regex("dx=(-?[0-9.]+)").find(it)!!.groupValues[1].toDouble() }
        assertEquals(30.0, sum, 0.0)
        assertTrue("moves were coalesced (${moves.size} messages)", moves.size < 20)
        val i = synchronized(log) { log.indexOfLast { "DRY move" in it } to log.indexOfFirst { "DRY button" in it } }
        assertTrue("motion is sent before the click", i.first < i.second)

        t.scroll(2, 0)
        waitFor("DRY scroll dx=0 dy=48")

        assertTrue(t.clickAt(0.5f, 0.5f))
        waitFor("DRY clickAt")

        t.stop()
        assertEquals(WsStatus.Idle, t.status.value)
    }

    /** Protocol 1.3 Spaces against `--fake-spaces 4` (4 desktops, current 1, solid-colour JPEG thumbnails). */
    @Test fun spacesStateThumbnailsAndGoto() = runBlocking {
        proc.destroy(); proc.waitFor()
        log.clear()
        // An older helper may not know the flag (exits or ignores it): skip rather than fail.
        val started = runCatching { spawn("--fake-spaces", "4") }.isSuccess
        assumeTrue("helper without --fake-spaces; rebuild helper", started && seen("fake spaces: 4", 3000) != null)

        val p = pairWith(http, "127.0.0.1", port, code, "jvm spaces")
        val token = p.token.copyOf()
        val t = WsTransport(http, scope, p.hostId, {
            WsTarget(listOf("127.0.0.1"), port, p.deviceId) { Proto.hmacHex(token, it) }
        })
        t.start()
        val jpeg = { b: ByteArray -> b.size > 2 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() }
        val s = withTimeout(10_000) {
            t.spaces.first { it != null && it.count == 4 && it.current == 1 && it.thumbs[1]?.jpeg?.let(jpeg) == true }
        }!!
        assertEquals("on", s.capture)
        assertEquals(4, s.versions.size)

        t.spacesGoto(3)
        waitFor("DRY spaces goto=3")
        val after = withTimeout(10_000) { t.spaces.first { it != null && it.current == 3 && it.thumbs[3]?.jpeg?.let(jpeg) == true } }!!
        assertEquals(4, after.count)

        t.stop()
        assertEquals(null, t.spaces.value)
    }

    /**
     * Protocol 1.4 against `--fake-spaces 6,3 --stdin-control`: both displays parsed, the active display
     * switch (stdin `display 2`) arrives live and the state then mirrors it, goto addresses a display.
     */
    @Test fun spacesFollowTheActiveDisplay() = runBlocking {
        proc.destroy(); proc.waitFor()
        log.clear()
        val started = runCatching { spawn("--fake-spaces", "6,3", "--stdin-control") }.isSuccess
        assumeTrue("helper without multi-display --fake-spaces; rebuild helper", started && seen("fake spaces: 6,3", 3000) != null)

        val p = pairWith(http, "127.0.0.1", port, code, "jvm displays")
        val token = p.token.copyOf()
        val t = WsTransport(http, scope, p.hostId, {
            WsTarget(listOf("127.0.0.1"), port, p.deviceId) { Proto.hmacHex(token, it) }
        })
        t.start()
        val s = withTimeout(10_000) { t.spaces.first { it != null && it.thumbs[1] != null } }!!
        assertEquals(listOf(DisplaySpaces("fake-1", "Fake Display 1", 6, 1), DisplaySpaces("fake-2", "Fake Display 2", 3, 1)), s.displays)
        assertEquals("fake-1", s.activeDisplay)
        assertEquals(6, s.count)
        assertEquals(false, s.shared)
        val v1 = s.thumbs[1]!!.version

        proc.outputStream.write("display 2\n".toByteArray()); proc.outputStream.flush()
        val sw = withTimeout(10_000) { t.spaces.first { it?.activeDisplay == "fake-2" && it.thumbs[1] != null } }!!
        assertEquals(3, sw.count)
        assertEquals(3, sw.versions.size)
        assertEquals(sw.active, DisplaySpaces("fake-2", "Fake Display 2", 3, 1))
        assertTrue("thumbnail of fake-2, not fake-1's", sw.thumbs[1]!!.version != v1)

        t.spacesGoto(2, "fake-1")
        waitFor("DRY spaces goto=2 from=1 via=step display=fake-1")
        waitFor("DRY spaces warp display=fake-1")
        val back = withTimeout(10_000) { t.spaces.first { it?.activeDisplay == "fake-1" && it.current == 2 } }!!
        assertEquals(6, back.count)
        // fake-1's desktop 1 thumbnail survived the round trip through fake-2 in the per-display cache.
        assertEquals(v1, back.thumbs[1]?.version)
        t.stop()
    }

    /**
     * Labs › Live screen against `--fake-screen` (synthetic frames, a 1512x982 pt Retina display at
     * (-1512, -200)): frames reach [WsTransport.labs], acks pace them, a tap maps onto the offset display,
     * and leaving (stop) ends the stream on the Mac.
     */
    @Test fun liveScreenFramesClicksAndStop() = runBlocking {
        proc.destroy(); proc.waitFor()
        log.clear()
        val started = runCatching { spawn("--fake-screen") }.isSuccess
        assumeTrue("helper without --fake-screen; rebuild helper", started && seen("fake screen: fake-screen", 3000) != null)

        val p = pairWith(http, "127.0.0.1", port, code, "jvm screen")
        val token = p.token.copyOf()
        val t = WsTransport(http, scope, p.hostId, { WsTarget(listOf("127.0.0.1"), port, p.deviceId) { Proto.hmacHex(token, it) } })
        t.start()
        withTimeout(10_000) { t.status.first { it is WsStatus.Ready } }
        val events = kotlinx.coroutines.channels.Channel<dev.mote.remote.labs.screen.ScreenProto.In>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        val sub = scope.launch { dev.mote.remote.labs.LabsBus.incoming.collect { o -> dev.mote.remote.labs.screen.ScreenProto.parse(o)?.let { events.trySend(it) } } }
        Thread.sleep(100)
        assertTrue(t.sendLabs(dev.mote.remote.labs.screen.ScreenProto.start(w = 960, fps = 15)))
        var frames = 0
        var last: dev.mote.remote.labs.screen.ScreenProto.Frame? = null
        withTimeout(10_000) {
            while (frames < 10) {
                val e = events.receive()
                if (e is dev.mote.remote.labs.screen.ScreenProto.FrameIn) {
                    frames++; last = e.f
                    t.sendLabs(dev.mote.remote.labs.screen.ScreenProto.ack(e.f.seq))
                }
            }
        }
        val f = last!!
        assertEquals("fake-screen", f.display)
        assertEquals(960, f.w)
        assertTrue("JPEG", f.jpeg[0] == 0xFF.toByte() && f.jpeg[1] == 0xD8.toByte())
        t.sendLabs(dev.mote.remote.labs.screen.ScreenProto.click(0.25, 0.5, "right", 1, f.display))
        waitFor("DRY clickAt (-1134.0, 291.0) b=right count=1")
        t.sendLabs(dev.mote.remote.labs.screen.ScreenProto.stop())
        waitFor("screen: stopped (stop)")
        sub.cancel()
        t.stop()
    }

    @Test fun wrongTokenFailsWithAuthAndStops() = runBlocking {
        val p = pairWith(http, "127.0.0.1", port, code, "jvm bad")
        val t = WsTransport(http, scope, p.hostId, {
            WsTarget(listOf("127.0.0.1"), port, p.deviceId) { Proto.hmacHex(ByteArray(32), it) }
        })
        t.start()
        val st = withTimeout(10_000) { t.status.first { it is WsStatus.Failed } }
        assertEquals("auth", (st as WsStatus.Failed).code)
    }

    @Test fun aDifferentMacAtTheSameAddressIsSkippedNotAuthed() = runBlocking {
        val p = pairWith(http, "127.0.0.1", port, code, "jvm other")
        val token = p.token.copyOf()
        // Saved under another hostId (as if this IP now belongs to a different Mac).
        val t = WsTransport(http, scope, "0000000000000000", {
            WsTarget(listOf("127.0.0.1"), port, p.deviceId) { Proto.hmacHex(token, it) }
        })
        t.start()
        Thread.sleep(2500)
        assertTrue("never ready against the wrong host", t.status.value !is WsStatus.Ready)
        assertTrue("no auth attempt reached the other Mac", synchronized(log) { log.none { "auth failed" in it || "authed jvm other" in it } })
        t.stop()
    }

    @Test fun wrongPairingCodeIsRejected() = runBlocking {
        val wrong = if (code == "000000") "111111" else "000000"
        val e = runCatching { pairWith(http, "127.0.0.1", port, wrong, "jvm") }.exceptionOrNull()
        assertEquals("code", (e as HelperError).code)
    }
}
