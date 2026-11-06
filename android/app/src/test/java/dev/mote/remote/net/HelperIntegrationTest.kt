package dev.mote.remote.net

import dev.mote.remote.hid.Consumer
import dev.mote.remote.hid.Mod
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
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

    @Before fun startHelper() {
        val bin = helperBinary()
        assumeTrue("helper not built; run helper/build.sh", bin != null)
        // --port 0: the helper binds any free port and logs it (no race with other helpers on this Mac).
        proc = ProcessBuilder(bin!!.path, "--dry-run", "--headless", "--no-ble", "--memory-store", "--open-pairing", "--port", "0").start()
        Thread { proc.errorStream.bufferedReader().forEachLine { log += it } }.apply { isDaemon = true }.start()
        val out = proc.inputStream.bufferedReader()
        code = generateSequence { out.readLine() }.first { it.startsWith("PAIRING CODE:") }.substringAfter(":").trim()
        port = Regex("""listening on :(\d+)""").find(waitFor("wifi: listening on :"))!!.groupValues[1].toInt()
    }

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
