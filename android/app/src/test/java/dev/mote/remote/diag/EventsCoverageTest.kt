package dev.mote.remote.diag

import dev.mote.remote.net.Proto
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fails when code and the event catalogue drift: a message type that is sent or handled, or a connect step,
 * without an event. Scans the sources, so adding a message to Proto.kt without a Wire entry breaks the build.
 */
class EventsCoverageTest {
    private val main = listOf("src/main/java", "app/src/main/java").map { File(it) }.first { it.isDirectory }
    private fun src(rel: String) = File(main, "dev/mote/remote/$rel").readText()
    private fun allKt() = main.walkTopDown().filter { it.extension == "kt" }.toList()

    @Test fun catalogMatchesEventsLoggedInCode() {
        val areas = Events.CATALOG.keys.joinToString("|")
        val re = Regex("\"($areas)\",\\s*\"([a-z][a-z.]*)\"")
        val found = HashMap<String, MutableSet<String>>()
        for (f in allKt()) {
            
            for (m in re.findAll(f.readText())) found.getOrPut(m.groupValues[1]) { sortedSetOf() }.add(m.groupValues[2])
        }
        val missing = Events.CATALOG.flatMap { (a, es) -> es.filter { it !in (found[a] ?: emptySet()) }.map { "$a.$it" } }
        val extra = found.flatMap { (a, es) -> es.filter { it !in Events.CATALOG[a].orEmpty() }.map { "$a.$it" } }
        assertTrue("in CATALOG but never logged: $missing", missing.isEmpty())
        assertTrue("logged but not in CATALOG: $extra", extra.isEmpty())
    }

    @Test fun everySentMessageTypeIsKnownToWire() {
        val sent = Regex("obj\\(\"([a-zA-Z.]+)\"\\)").findAll(src("net/Proto.kt")).map { it.groupValues[1] }.toSet()
        assertTrue("Proto.kt builds no messages?", sent.isNotEmpty())
        val unlogged = sent.filter { it !in Wire.OUT_TYPES }
        assertTrue("sent by Proto.kt but not in Wire.OUT_TYPES (no event): $unlogged", unlogged.isEmpty())
    }

    @Test fun everyHandledMessageTypeIsKnownToWire() {
        val body = src("net/Proto.kt").substringAfter("when (val t = o.getString(\"t\"))")
        val handled = Regex("^\\s*\"([a-zA-Z.]+)\" ->", RegexOption.MULTILINE).findAll(body).map { it.groupValues[1] }.toSet()
        assertTrue("Proto.parse handles nothing?", handled.isNotEmpty())
        val unlogged = handled.filter { it !in Wire.IN_TYPES }
        assertTrue("parsed by Proto.kt but not in Wire.IN_TYPES: $unlogged", unlogged.isEmpty())
        // routed (not parsed) types
        for (t in listOf("diag", "diag.events")) assertTrue(t in Wire.IN_TYPES)
    }

    @Test fun sessionSendAndReceiveGoThroughWire() {
        val ws = src("net/WsSession.kt")
        assertTrue("WsSession.send must call Wire.out", ws.contains("Wire.out("))
        assertTrue("WsSession.onMessage must call Wire.inn", ws.contains("Wire.inn("))
        assertTrue("err frames must call Wire.err", ws.contains("Wire.err("))
    }

    @Test fun connectStepsAllEmitEvents() {
        val steps = listOf("try", "fail", "hello", "mismatch", "auth", "ready", "failed", "dropped", "backoff").map { "connect.$it" }
        val ws = src("net/WsTransport.kt") + src("net/WsSession.kt")
        for (s in steps) {
            assertTrue("$s missing from CATALOG", s in Events.CATALOG["transport"].orEmpty())
            assertTrue("$s never logged", ws.contains("\"transport\", \"$s\""))
        }
    }

    @Test fun wireStampsCorrAndNeverLogsPayload() {
        Events.clear(); Corr.reset()
        val w = Wire.out(Proto.ping(1))
        assertEquals("ping", Wire.typeOf(w))
        assertTrue(Wire.corrOf(w) != null)
        val k = Wire.out(Proto.pair("123456", "Pixel"))
        Wire.inn("""{"t":"hello","secret":"abc"}""", "hello")
        val log = Events.toJsonl()
        assertTrue(log.contains("\"event\":\"out\""))
        assertTrue(!log.contains("123456") && !log.contains("abc"))
        assertTrue(Wire.corrOf(k) != Wire.corrOf(w))
    }

    @Test fun authCarriesClient() {
        val a = Proto.auth("id", "mac")
        assertTrue(a.contains("\"client\"") && a.contains(Proto.CLIENT_APP) && a.contains(Proto.CLIENT_PROTO))
    }

    @Test fun ringBufferKeepsLast5000() {
        Events.clear()
        repeat(5100) { Events.log("ui", "tap", "n" to it) }
        assertEquals(5000, Events.size)
        val first = Events.snapshot().first()
        assertEquals("app", first.toJson().getString("side"))
    }
}
