package dev.mote.remote.labs.context

import dev.mote.remote.net.Msg
import dev.mote.remote.net.Proto
import dev.mote.remote.net.WsSession
import dev.mote.remote.net.helperHttpClient
import dev.mote.remote.net.pairWith
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Collections

/**
 * Labs › Smart remote against the real helper in dry-run with `--fake-context` / `--fake-nowplaying`
 * (nothing is injected). Skipped when the helper isn't built (`helper/build.sh`).
 */
class LabsContextIntegrationTest {
    private var proc: Process? = null
    private val log: MutableList<String> = Collections.synchronizedList(ArrayList())
    private val ctxFile = File.createTempFile("mote-ctx", ".json").apply { deleteOnExit() }

    private fun helperBinary(): File? {
        (System.getProperty("mote.helper") ?: System.getenv("MOTE_HELPER"))?.let { return File(it).takeIf(File::canExecute) }
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            File(dir, "helper/build/MoteHelper.app/Contents/MacOS/MoteHelper").takeIf(File::canExecute)?.let { return it }
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
        throw AssertionError("helper never logged \"$fragment\":\n" + log.joinToString("\n"))
    }

    @After fun stop() { proc?.destroy() }

    private suspend fun WsSession.nextLabs(t: String, f: (JSONObject) -> Boolean = { true }): JSONObject = withTimeout(4000) {
        while (true) { val o = labs(inbox.receive()) ?: continue; if (o.optString("t") == t && f(o)) return@withTimeout o }
        @Suppress("UNREACHABLE_CODE") error("unreachable")
    }

    /** A `labs.*` message as the app's LabsBus sees it (raw JSON of [Msg.Other]). */
    private fun labs(m: Msg): JSONObject? = (m as? Msg.Other)?.takeIf { it.t.startsWith("labs.") }?.let { JSONObject(it.raw) }

    @Test fun contextNowPlayingAndSeekThroughTheAppCodecs() = runBlocking {
        val bin = helperBinary()
        assumeTrue("helper not built; run helper/build.sh", bin != null)
        ctxFile.writeText("""{"app":"Google Chrome","bundle":"com.google.Chrome","url":"https://www.youtube.com/watch?v=abc","title":"x"}""")
        val p = ProcessBuilder(
            bin!!.path, "--dry-run", "--headless", "--no-ble", "--memory-store", "--open-pairing", "--port", "0",
            "--fake-context", ctxFile.path, "--fake-nowplaying",
        ).start()
        proc = p
        Thread { p.errorStream.bufferedReader().forEachLine { log += it } }.apply { isDaemon = true }.start()
        Thread { p.inputStream.bufferedReader().forEachLine { log += it } }.apply { isDaemon = true }.start()
        val code = waitFor("PAIRING CODE:").substringAfter("PAIRING CODE:").trim()
        val port = Regex("""listening on :(\d+)""").find(waitFor("wifi: listening on :"))!!.groupValues[1].toInt()

        val http = helperHttpClient()
        val pairing = pairWith(http, "127.0.0.1", port, code, "labs it")
        val s = WsSession.open(http, "127.0.0.1", port)
        s.auth(pairing.deviceId) { nonce -> Proto.hmacHex(pairing.token, nonce) }

        s.send("""{"t":"labs.ctx.sub","on":true}""")
        val ctx = SmartRemote.parseContext(s.nextLabs("labs.ctx") { it.optString("bundle").isNotEmpty() })
        assertEquals("www.youtube.com", ctx.host)
        assertEquals(null, ctx.url) // privacy default
        assertEquals(ProfileId.YOUTUBE, ContextRules.match(ctx)?.id)

        s.send("""{"t":"labs.np.sub","on":true}""")
        var st = NpReducer.reduce(NpState(), NpEvent.Update(s.nextLabs("labs.np") { !it.isNull("np") }, 0))
        assertEquals("Fake Song", st.np?.title)
        assertTrue(st.canScrub)

        st = NpReducer.reduce(st, NpEvent.SeekSent(150.0, 10))
        s.send("""{"t":"labs.np.seek","to":150}""")
        // The pushed state and the reply arrive in either order.
        var reply: JSONObject? = null
        var moved: JSONObject? = null
        withTimeout(4000) {
            while (reply == null || moved == null) {
                val o = labs(s.inbox.receive()) ?: continue
                if (o.optString("t") == "labs.np.seek") reply = o
                if (o.optString("t") == "labs.np" && o.optJSONObject("np")?.optDouble("elapsed") == 150.0) moved = o
            }
        }
        assertEquals("fake", reply!!.getString("via"))
        st = NpReducer.reduce(st, NpEvent.Update(moved!!, 20))
        assertEquals(null, st.pendingSeek) // confirmed
        assertEquals(150.0, st.displayPosition(20)!!, 0.5)
        waitFor("DRY labs seek via=fake to=150.0")

        // The Mac focuses a text field: the auto-keyboard opens.
        ctxFile.writeText("""{"app":"Notes","bundle":"com.apple.Notes","text":true}""")
        val notes = SmartRemote.parseContext(s.nextLabs("labs.ctx") { it.optBoolean("text") })
        assertEquals(AutoKeyboard.Action.OPEN, AutoKeyboard().next(notes, allowed = true, onKeys = false).second)
        s.close()
    }
}
