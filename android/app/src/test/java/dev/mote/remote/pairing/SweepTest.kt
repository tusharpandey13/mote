package dev.mote.remote.pairing

import dev.mote.remote.net.Found
import dev.mote.remote.net.helperHttpClient
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import kotlin.concurrent.thread

class SweepTest {
    private val fp = "0123456789abcdef0123456789abcdef"
    private val id = "0123456789abcdef"
    private val info = BleInfo(id, "Tushar's MacBook Air", fp, listOf("192.168.1.2"), "mac.tail1.ts.net", "100.83.11.75", 8790)

    @Test fun sweepCoversThePhonesSlash24() {
        val h = sweepHosts("192.168.1.7", 24)
        assertEquals(253, h.size)
        assertTrue("192.168.1.2" in h); assertTrue("192.168.1.254" in h)
        assertFalse("192.168.1.0" in h); assertFalse("192.168.1.255" in h); assertFalse("192.168.1.7" in h)
        // a bigger subnet still sweeps only the /24 around the phone; a smaller one only itself
        assertEquals(sweepHosts("10.0.3.9", 24), sweepHosts("10.0.3.9", 16))
        val small = sweepHosts("192.168.1.130", 25)
        assertEquals(125, small.size); assertEquals("192.168.1.129", small.first()); assertEquals("192.168.1.254", small.last())
        assertEquals(emptyList<String>(), sweepHosts("fe80::1", 64))
        assertEquals(emptyList<String>(), sweepHosts("300.1.1.1", 24))
    }

    @Test fun sweepEmitsOnlyOpenHostsAndFinishes() = runBlocking {
        val open = setOf("192.168.1.2", "192.168.1.40")
        val hosts = sweepHosts("192.168.1.7", 24)
        val t0 = System.currentTimeMillis()
        val hits = lanSweep(hosts, 8790, timeoutMs = 300, parallel = 128) { h, _, _ -> Thread.sleep(if (h in open) 5 else 300); h in open }.toList()
        assertEquals(open, hits.toSet())
        assertTrue("254 hosts at 128 in parallel take ~2 timeouts, not 254", System.currentTimeMillis() - t0 < 2000)
    }

    @Test fun realTcpSweepFindsAListenerFast() = runBlocking {
        ServerSocket(0).use { srv ->
            thread(isDaemon = true) { runCatching { while (true) srv.accept().close() } }
            val t0 = System.currentTimeMillis()
            // 10.255.255.1 is unroutable here: it must cost at most the timeout, not stall the sweep
            val hits = lanSweep(listOf("10.255.255.1", "127.0.0.1"), srv.localPort, timeoutMs = 300).toList()
            assertEquals(listOf("127.0.0.1"), hits)
            assertTrue(System.currentTimeMillis() - t0 < 1500)
        }
    }

    @Test fun somethingThatIsNotAHelperIsDropped() = runBlocking {
        ServerSocket(0).use { srv ->
            thread(isDaemon = true) { runCatching { while (true) srv.accept().close() } }
            assertNull(probeHelper(helperHttpClient(), "127.0.0.1", srv.localPort))
        }
    }

    @Test fun mergeKeysByHostIdBleFirstAnsweredFirst() {
        val ble = listOf(NearbyMac(info, -50, 0))
        val scan = listOf(Probed(id, "Tushar's MacBook Air", "192.168.1.2", 8790), Probed("fedcba9876543210", "Studio", "192.168.1.9", 8790))
        val known = listOf(Probed(id, "Air", "mac.tail1.ts.net", 8790))
        val bonjour = listOf(Found("Tushar's MacBook Air", id, "192.168.1.2", 8790), Found("Old", null, "192.168.1.50", 8790),
            Found("Mini", "aaaaaaaaaaaaaaaa", "192.168.1.60", 8790))
        val m = mergeFound(ble, scan, known, bonjour)
        assertEquals(listOf(id, "fedcba9876543210", "aaaaaaaaaaaaaaaa"), m.map { it.hostId })
        val air = m[0]
        assertEquals("Tushar's MacBook Air", air.name); assertEquals(fp, air.fp); assertTrue(air.nearby)
        assertEquals(setOf(Source.BLE, Source.LAN_SCAN, Source.KNOWN, Source.BONJOUR), air.sources)
        assertEquals(listOf("192.168.1.2", "mac.tail1.ts.net", "100.83.11.75"), air.addresses) // LAN first, no duplicates
        assertEquals(setOf(Source.LAN_SCAN), m[1].sources); assertNull(m[1].fp); assertFalse(m[1].nearby)
        assertEquals(listOf("192.168.1.60"), m[2].addresses)
    }

    @Test fun sweepOnlyMacGetsItsInfoFromTheProbe() {
        val m = mergeFound(emptyList(), listOf(Probed(id, "Air", "192.168.1.2", 8790, info)), emptyList(), emptyList()).single()
        assertEquals(fp, m.fp)
        assertEquals(listOf("192.168.1.2", "mac.tail1.ts.net", "100.83.11.75"), m.addresses)
        assertFalse(m.nearby)
    }

    @Test fun proximityTokenShape() {
        assertEquals("59012345:" + "ab".repeat(16), PairProto.proxOrNull(("59012345:" + "ab".repeat(16)).toByteArray()))
        assertNull(PairProto.proxOrNull("59012345:XYZ".toByteArray()))
        assertNull(PairProto.proxOrNull(("-1:" + "ab".repeat(16)).toByteArray()))
        assertNull(PairProto.proxOrNull(null))
    }

    @Test fun asleepIsNotTheSameAsHelperNotRunning() {
        val closed = ServerSocket(0).let { val port = it.localPort; it.close(); port }
        assertEquals(Reach.REFUSED, tcpReach("127.0.0.1", closed))
        ServerSocket(0).use { assertEquals(Reach.OPEN, tcpReach("127.0.0.1", it.localPort)) }
        assertEquals(Reach.TIMEOUT, tcpReach("10.255.255.1", 8790, timeoutMs = 200))
        assertTrue(reachNote("Air", listOf(Reach.TIMEOUT, Reach.REFUSED))!!.startsWith("Mote Helper isn't running on Air"))
        assertTrue(reachNote("Air", listOf(Reach.TIMEOUT))!!.startsWith("Air seems asleep"))
        assertNull(reachNote("Air", listOf(Reach.TIMEOUT, Reach.OPEN)))
        assertNull(reachNote("Air", emptyList()))
    }
}
