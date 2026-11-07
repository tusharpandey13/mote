package dev.mote.remote.pairing

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
import kotlinx.coroutines.flow.toList
import org.json.JSONObject

/**
 * Instant pairing end to end against the real helper in dry-run (nothing is injected). BLE is
 * faked: the helper prints its info (`LABS PAIR INFO: {json}`, since tests run with `--no-ble`),
 * the test points its LAN address at loopback as if read over GATT, and `--fake-proximity` lets it
 * read the proximity token over the WebSocket (`labs.pair.prox`) instead of GATT. Tailscale
 * ownership and the opt-in approval are faked by `--fake-tailscale-owner` / `--fake-approve(-deny)`.
 * Skipped unless a helper with `--fake-proximity` is built (helper/build.sh).
 */
class PairingIntegrationTest {
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
        assumeTrue("helper without --fake-proximity; rebuild helper", "--fake-proximity" in help)
        log.clear()
        val args = listOf(bin.path, "--dry-run", "--headless", "--no-ble", "--memory-store", "--fake-proximity", "--port", "0") + extra
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

    /** The token a phone would read over BLE right now (window offset [dw]). */
    private suspend fun prox(dw: Int = 0): String {
        val s = WsSession.open(http, "127.0.0.1", port)
        try {
            s.send(JSONObject().put("t", "labs.pair.prox").put("dw", dw).toString())
            while (true) {
                val m = withTimeout(3000) { s.inbox.receive() }
                if (m is Msg.Other && "labs.pair.prox" in m.raw) return PairProto.proxOrNull(JSONObject(m.raw).getString("v").toByteArray())!!
            }
        } finally { s.close() }
    }

    private suspend fun handshake(
        addr: String, pin: String?, name: String, allowPrompt: Boolean = true, prox: String? = null,
        states: MutableList<PairState>? = null, me: Signer = phone,
    ): PairState.Done {
        val s = WsSession.open(http, addr, port)
        try { return labsHandshake(s, me, name, pin, allowPrompt, prox) { states?.add(it) } } finally { s.close() }
    }

    private fun code(e: Throwable?) = (e as HelperError).code

    @Test fun nearbyPhoneIsTrustedWithNoMacActionThenReconnectsSilently() = runBlocking {
        val info = fakeBle(spawn())
        val paths = selectPaths(info, null)
        assertEquals(PairPath(Route.WIFI, "127.0.0.1"), paths.first())

        val states = ArrayList<PairState>()
        val first = handshake(helperCandidates(paths).first(), info.fp, "jvm phone", prox = prox(), states = states)
        assertEquals("nearby", first.via)
        assertFalse("nothing to click on the Mac", states.any { it is PairState.Pending })
        assertEquals(info.fp, first.helper.fp)
        waitFor("NEW DEVICE:") // the Mac's non-blocking notice (headless: printed)
        assertFalse(logged("labs: prompt"))

        // Reconnects: the key is trusted now; no token, no prompt, and input works through WsTransport.
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
        t.stop()
    }

    @Test fun farAwayStaleAndReplayedTokensAreRefused() = runBlocking {
        val info = fakeBle(spawn())
        assertEquals("far", code(runCatching { handshake("127.0.0.1", info.fp, "jvm far") }.exceptionOrNull()))
        assertEquals("stale", code(runCatching { handshake("127.0.0.1", info.fp, "jvm old", prox = prox(-2)) }.exceptionOrNull()))
        assertEquals("bad-proximity", code(runCatching {
            handshake("127.0.0.1", info.fp, "jvm forged", prox = prox().substringBefore(':') + ":" + "0".repeat(32))
        }.exceptionOrNull()))
        val token = prox()
        assertEquals("nearby", handshake("127.0.0.1", info.fp, "jvm a", prox = token).via)
        val other = SeedSigner(Random.nextBytes(32))
        assertEquals("replay is stale", "stale", code(runCatching { handshake("127.0.0.1", info.fp, "jvm b", prox = token, me = other) }.exceptionOrNull()))
    }

    @Test fun sameTailscaleOwnerIsTrustedWithoutAToken() = runBlocking {
        val info = fakeBle(spawn("--fake-tailscale-owner", "self"))
        val done = handshake("127.0.0.1", info.fp, "jvm tailnet")
        assertEquals("tailscale", done.via)
        assertFalse(logged("labs: prompt"))
    }

    @Test fun anotherTailscaleOwnerWithoutATokenIsFar() = runBlocking {
        val info = fakeBle(spawn("--fake-tailscale-owner", "someone"))
        assertEquals("far", code(runCatching { handshake("127.0.0.1", info.fp, "jvm stranger") }.exceptionOrNull()))
    }

    @Test fun requireApprovalRestoresTheAlert() = runBlocking {
        val info = fakeBle(spawn("--require-approval", "--fake-approve"))
        val states = ArrayList<PairState>()
        val done = handshake("127.0.0.1", info.fp, "jvm cautious", prox = prox(), states = states)
        assertEquals("approved", done.via)
        assertTrue("the Mac asked", states.any { it is PairState.Pending })
    }

    @Test fun requireApprovalDenied() = runBlocking {
        val info = fakeBle(spawn("--require-approval", "--fake-approve-deny"))
        assertEquals("denied", code(runCatching { handshake("127.0.0.1", info.fp, "jvm denied", prox = prox()) }.exceptionOrNull()))
        // A silent reconnect never shows a prompt: it fails at once instead.
        assertTrue(runCatching { handshake("127.0.0.1", info.fp, "jvm denied", allowPrompt = false) }.exceptionOrNull() is HelperError)
    }

    @Test fun aMacWithAnotherKeyIsRejected() = runBlocking {
        spawn()
        assertEquals("helper-key", code(runCatching { handshake("127.0.0.1", "0".repeat(32), "jvm pinned", prox = prox()) }.exceptionOrNull()))
    }

    @Test fun lanSweepFindsAndConfirmsTheHelper() = runBlocking {
        val info = spawn()
        val hits = lanSweep(listOf("10.255.255.1", "127.0.0.1"), port).toList()
        assertEquals(listOf("127.0.0.1"), hits)
        val p = probeHelper(http, "127.0.0.1", port)!!
        assertEquals(info.hostId, p.hostId)
        assertEquals(info.fp, p.info?.fp)
        val m = mergeFound(emptyList(), listOf(p), emptyList(), emptyList()).single()
        assertEquals("127.0.0.1", m.addresses.first())
    }

    @Test fun instantPairingOffOnTheMacIsUnauth() = runBlocking {
        val info = spawnPlain("--no-instant-pairing")
        assertTrue(info)
        val e = runCatching { handshake("127.0.0.1", null, "jvm off") }.exceptionOrNull()
        assertEquals("unauth", code(e))
    }

    private fun spawnPlain(vararg extra: String): Boolean {
        val bin = helperBinary()
        assumeTrue("helper not built", bin != null)
        val p = ProcessBuilder(listOf(bin!!.path, "--dry-run", "--headless", "--no-ble", "--memory-store", "--port", "0") + extra).start()
        proc = p
        Thread { p.errorStream.bufferedReader().forEachLine { log += it } }.apply { isDaemon = true }.start()
        port = Regex("""listening on :(\d+)""").find(waitFor("wifi: listening on :"))!!.groupValues[1].toInt()
        return true
    }
}
