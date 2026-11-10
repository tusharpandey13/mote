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
 * The now-playing notification's inputs against the real helper in dry-run: a `--fake-nowplaying-reader` plays a
 * song, then (a file appears) stops. Buttons are the same consumer / seek messages the notification sends.
 * Skipped when the helper isn't built (`helper/build.sh`).
 */
class NpNotifIntegrationTest {
    private var proc: Process? = null
    private val log: MutableList<String> = Collections.synchronizedList(ArrayList())

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

    private suspend fun WsSession.nextNp(f: (JSONObject) -> Boolean): JSONObject = withTimeout(6000) {
        while (true) {
            val m = inbox.receive() as? Msg.Other ?: continue
            if (m.t != "labs.np") continue
            val o = JSONObject(m.raw)
            if (f(o)) return@withTimeout o
        }
        @Suppress("UNREACHABLE_CODE") error("unreachable")
    }

    @Test fun playsThenStopsAndButtonsReachTheMac() = runBlocking {
        val bin = helperBinary()
        assumeTrue("helper not built; run helper/build.sh", bin != null)
        val stopFile = File.createTempFile("mote-np-stop", "").apply { delete(); deleteOnExit() }
        val reader = File.createTempFile("mote-np-reader", ".sh").apply {
            deleteOnExit()
            writeText(
                """#!/bin/sh
echo '{"ok":true}'
echo '{"np":{"title":"Reader Song","artist":"Band","app":"Music","bundle":"com.apple.Music","duration":200,"elapsed":10,"rate":1,"at":'$(date +%s)'000}}'
while [ ! -e '${stopFile.path}' ]; do sleep 0.1; done
echo '{"np":null}'
sleep 600
""",
            )
            setExecutable(true)
        }
        val ctx = File.createTempFile("mote-ctx", ".json").apply { deleteOnExit(); writeText("{}") }
        val p = ProcessBuilder(
            bin!!.path, "--dry-run", "--headless", "--no-ble", "--memory-store", "--open-pairing", "--port", "0",
            "--fake-context", ctx.path, "--fake-nowplaying-reader", reader.path,
        ).start()
        proc = p
        Thread { p.errorStream.bufferedReader().forEachLine { log += it } }.apply { isDaemon = true }.start()
        Thread { p.inputStream.bufferedReader().forEachLine { log += it } }.apply { isDaemon = true }.start()
        val code = waitFor("PAIRING CODE:").substringAfter("PAIRING CODE:").trim()
        val port = Regex("""listening on :(\d+)""").find(waitFor("wifi: listening on :"))!!.groupValues[1].toInt()
        val http = helperHttpClient()
        val pairing = pairWith(http, "127.0.0.1", port, code, "np it")
        val s = WsSession.open(http, "127.0.0.1", port)
        s.auth(pairing.deviceId) { nonce -> Proto.hmacHex(pairing.token, nonce) }

        s.send("""{"t":"labs.np.sub","on":true}""")
        var st = NpReducer.reduce(NpState(), NpEvent.Update(s.nextNp { !it.isNull("np") }, 0))
        val shown = NpNotifPolicy.plan(st, lab = true, overHelper = true, linked = true, allowed = true, nowMs = 0)
        assertTrue("expected a notification, got $shown", shown is NpNotifPlan.Show)
        assertEquals("Reader Song", (shown as NpNotifPlan.Show).title)

        // The buttons: the same messages the notification's callbacks send.
        for ((k, line) in listOf("next" to "DRY consumer next", "prev" to "DRY consumer prev", "play" to "DRY consumer play")) {
            s.send(Proto.consumer(k))
            waitFor(line)
        }

        // Nothing plays any more: the plan hides the notification.
        stopFile.writeText("x")
        st = NpReducer.reduce(st, NpEvent.Update(s.nextNp { it.isNull("np") }, 1))
        assertEquals(NpNotifPlan.Hide("nothing-playing"), NpNotifPolicy.plan(st, true, true, true, true, 1))
        s.close()
    }
}
