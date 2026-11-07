package dev.mote.remote.labs.open

import dev.mote.remote.labs.LabsBus
import dev.mote.remote.net.Proto
import dev.mote.remote.net.WsStatus
import dev.mote.remote.net.WsTarget
import dev.mote.remote.net.WsTransport
import dev.mote.remote.net.helperHttpClient
import dev.mote.remote.net.pairWith
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Collections

/**
 * Labs Open & TV mode against the real helper in `--dry-run --labs-tv` (nothing opens, nothing is
 * injected): messages sent through [WsTransport.sendLabs] reach the helper, `labs.tv.state` and labs
 * errors come back through [LabsBus]. Skipped when the helper isn't built.
 */
class OpenTvIntegrationTest {
    private var proc: Process? = null
    private val log: MutableList<String> = Collections.synchronizedList(ArrayList())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun helperBinary(): File? {
        val p = System.getProperty("mote.helper") ?: System.getenv("MOTE_HELPER")
        if (p != null) return File(p).takeIf { it.canExecute() }
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            File(dir, "helper/build/MoteHelper.app/Contents/MacOS/MoteHelper").takeIf { it.canExecute() }?.let { return it }
            dir = dir.parentFile
        }
        return null
    }

    private fun waitFor(fragment: String, timeoutMs: Long = 5000): String {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            synchronized(log) { log.firstOrNull { fragment in it } }?.let { return it }
            Thread.sleep(20)
        }
        throw AssertionError("helper never logged \"$fragment\"; log:\n" + synchronized(log) { log.joinToString("\n") })
    }

    @After fun stop() { scope.cancel(); proc?.destroy() }

    @Test fun openAndTvMessagesRoundTrip() = runBlocking {
        val bin = helperBinary()
        assumeTrue("helper not built; run helper/build.sh", bin != null)
        val p = ProcessBuilder(bin!!.path, "--dry-run", "--headless", "--no-ble", "--memory-store", "--open-pairing", "--port", "0", "--labs-tv").start()
        proc = p
        Thread { p.errorStream.bufferedReader().forEachLine { log += it } }.apply { isDaemon = true }.start()
        Thread { p.inputStream.bufferedReader().forEachLine { log += it } }.apply { isDaemon = true }.start()
        val code = waitFor("PAIRING CODE:").substringAfter("PAIRING CODE:").trim()
        val port = Regex("""listening on :(\d+)""").find(waitFor("wifi: listening on :"))!!.groupValues[1].toInt()
        val http = helperHttpClient()
        val pr = pairWith(http, "127.0.0.1", port, code, "jvm labs")
        val token = pr.token.copyOf()
        val t = WsTransport(http, scope, pr.hostId, { WsTarget(listOf("127.0.0.1"), port, pr.deviceId) { Proto.hmacHex(token, it) } })
        t.start()
        withTimeout(10_000) { t.status.first { it is WsStatus.Ready } }

        val on = scope.async { withTimeout(10_000) { LabsBus.incoming.mapNotNull { OpenTvProto.parseState(it) }.first { it.on } } }
        Thread.sleep(100) // let the collector subscribe
        assertTrue(t.sendLabs(OpenTvProto.openUrl("https://youtu.be/jfKfPfyJRdk", "tv")!!))
        val st = on.await()
        assertEquals("https://www.youtube.com/tv#/watch?v=jfKfPfyJRdk", st.url)
        waitFor("DRY labs.tv open page=youtube")

        t.sendLabs(OpenTvProto.tvKey("left")!!)
        waitFor("DRY labs.tv key left")

        val err = scope.async { withTimeout(10_000) { LabsBus.incoming.mapNotNull { OpenTvProto.errorText(it) }.first() } }
        Thread.sleep(100)
        // Bypass the phone-side check to prove the helper refuses it too.
        t.sendLabs("""{"t":"labs.open.url","url":"javascript:alert(1)","mode":"browser"}""")
        assertTrue(err.await().startsWith("The Mac refused that link"))
        assertTrue(synchronized(log) { log.none { "DRY labs.open.url" in it } })
        t.stop()
    }
}
