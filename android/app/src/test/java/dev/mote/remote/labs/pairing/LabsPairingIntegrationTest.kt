package dev.mote.remote.labs.pairing

import dev.mote.remote.hid.Mod
import dev.mote.remote.net.HelperError
import dev.mote.remote.net.Msg
import dev.mote.remote.net.Route
import dev.mote.remote.net.WsSession
import dev.mote.remote.net.WsStatus
import dev.mote.remote.net.WsTarget
import dev.mote.remote.net.WsTransport
import dev.mote.remote.net.helperHttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Collections
import kotlin.random.Random

/**
 * Instant pairing end to end against the real helper in dry-run (nothing is injected). The BLE
 * info payload is faked: the helper prints it (`LABS PAIR INFO: {json}`, since tests run with
 * `--no-ble`), and the test points its LAN address at loopback, as if read over GATT.
 * Approval and Tailscale ownership are faked by `--fake-approve(-deny)` / `--fake-tailscale-owner`.
 * Skipped unless a helper with `--labs-pairing` is built (helper/build.sh).
 */
class LabsPairingIntegrationTest {
    private var proc: Process? = null
    private val log: MutableList<String> = Collections.synchronizedList(ArrayList())
    private var port = 0
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http = helperHttpClient()
    private val phone = SeedSigner(Random.nextBytes(32))

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

    /** Starts a Labs helper; returns the BLE info it advertises. */
    private fun spawn(vararg extra: String): BleInfo {
        val bin = helperBinary()
        assumeTrue("helper not built; run helper/build.sh", bin != null)
        val help = ProcessBuilder(bin!!.path, "--help").redirectErrorStream(true).start().inputStream.bufferedReader().readText()
        assumeTrue("helper without --labs-pairing; rebuild helper", "--labs-pairing" in help)
        log.clear()
        val args = listOf(bin.path, "--dry-run", "--headless", "--no-ble", "--memory-store", "--labs-pairing", "--port", "0") + extra
        val p = ProcessBuilder(args).start()
        proc = p
        Thread { p.errorStream.bufferedReader().forEachLine { log += it } }.apply { isDaemon = true }.start()
        Thread { p.inputStream.bufferedReader().forEachLine { log += it } }.apply { isDaemon = true }.start()
        port = Regex("""listening on :(\d+)""").find(waitFor("wifi: listening on :"))!!.groupValues[1].toInt()
        val info = BleInfo.parse(waitFor("LABS PAIR INFO:").substringAfter("LABS PAIR INFO:").trim())!!
        assertEquals(port, info.port)
        return info
    }

    @After fun stop() {
        scope.cancel()
        proc?.destroy()
    }

    private fun waitFor(fragment: String, timeoutMs: Long = 5000): String {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            synchronized(log) { log.firstOrNull { fragment in it } }?.let { return it }
            Thread.sleep(20)
        }
        throw AssertionError("helper never logged \"$fragment\"; log:\n" + synchronized(log) { log.joinToString("\n") })
    }

    private fun logged(fragment: String) = synchronized(log) { log.any { fragment in it } }

    /** As read over GATT on the Mac's LAN: loopback stands in for the Mac's LAN IP. */
    private fun fakeBle(info: BleInfo) = BleInfo.parse(info.copy(lan = listOf("127.0.0.1")).toJson().toByteArray())!!

    private suspend fun handshake(addr: String, pin: String?, name: String, allowPrompt: Boolean = true, states: MutableList<PairState>? = null): PairState.Done {
        val s = WsSession.open(http, addr, port)
        try { return labsHandshake(s, phone, name, pin, allowPrompt) { states?.add(it) } } finally { s.close() }
    }

    @Test fun approvedOnceThenSilentReconnectAndInput() = runBlocking {
        val info = fakeBle(spawn("--fake-approve"))
        val paths = selectPaths(info, null)
        assertEquals(PairPath(Route.WIFI, "127.0.0.1"), paths.first())

        val states = ArrayList<PairState>()
        val first = handshake(helperCandidates(paths).first(), info.fp, "jvm phone", states = states)
        assertEquals("approved", first.via)
        assertTrue("the Mac asked", states.any { it is PairState.Pending })
        assertEquals(info.fp, first.helper.fp)
        waitFor("labs: prompt jvm phone")
        waitFor("labs: trusted jvm phone via approved")

        // Reconnects: the same key is trusted now, no prompt, and input works through WsTransport.
        var via: String? = null
        val t = WsTransport(http, scope, info.hostId, {
            object : WsTarget(helperCandidates(paths), port, first.authed.id, { "" }) {
                override val authenticate: suspend (WsSession) -> Msg.Authed = { s ->
                    labsHandshake(s, phone, "jvm phone", info.fp, allowPrompt = false).also { via = it.via }.authed
                }
            }
        })
        t.start()
        val ready = withTimeout(10_000) { t.status.first { it is WsStatus.Ready || it is WsStatus.Failed } }
        assertEquals("127.0.0.1", (ready as WsStatus.Ready).address)
        assertEquals("trusted", via)
        t.tap("a", Mod.CMD)
        waitFor("DRY key usage=4 mods=8 action=tap")
        assertEquals("one prompt only", 1, synchronized(log) { log.count { "labs: prompt" in it } })
        t.stop()
    }

    @Test fun sameTailscaleOwnerIsTrustedWithoutAPrompt() = runBlocking {
        val info = fakeBle(spawn("--fake-tailscale-owner", "self"))
        val states = ArrayList<PairState>()
        val done = handshake("127.0.0.1", info.fp, "jvm tailnet", states = states)
        assertEquals("tailscale", done.via)
        assertFalse(states.any { it is PairState.Pending })
        assertFalse(logged("labs: prompt"))
    }

    @Test fun anotherOwnerIsAskedAndDenied() = runBlocking {
        val info = fakeBle(spawn("--fake-tailscale-owner", "someone", "--fake-approve-deny"))
        val e = runCatching { handshake("127.0.0.1", info.fp, "jvm stranger") }.exceptionOrNull()
        assertEquals("denied", (e as HelperError).code)
        waitFor("labs: prompt jvm stranger")
        // A silent reconnect never shows a prompt: it fails at once instead.
        val again = runCatching { handshake("127.0.0.1", info.fp, "jvm stranger", allowPrompt = false) }.exceptionOrNull()
        assertTrue(again is HelperError)
    }

    @Test fun aMacWithAnotherKeyIsRejected() = runBlocking {
        spawn("--fake-approve")
        val e = runCatching { handshake("127.0.0.1", "0".repeat(32), "jvm pinned") }.exceptionOrNull()
        assertEquals("helper-key", (e as HelperError).code)
    }

    @Test fun labsOffOnTheMacIsUnauth() = runBlocking {
        val bin = helperBinary()
        assumeTrue("helper not built", bin != null)
        val p = ProcessBuilder(bin!!.path, "--dry-run", "--headless", "--no-ble", "--memory-store", "--port", "0").start()
        proc = p
        Thread { p.errorStream.bufferedReader().forEachLine { log += it } }.apply { isDaemon = true }.start()
        port = Regex("""listening on :(\d+)""").find(waitFor("wifi: listening on :"))!!.groupValues[1].toInt()
        val e = runCatching { handshake("127.0.0.1", null, "jvm off") }.exceptionOrNull()
        assertEquals("unauth", (e as HelperError).code)
    }
}
