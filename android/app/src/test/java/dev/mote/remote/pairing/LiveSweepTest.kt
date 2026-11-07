package dev.mote.remote.pairing

import dev.mote.remote.net.Proto
import dev.mote.remote.net.helperHttpClient
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Opt-in: sweeps a REAL LAN for a running Mote Helper, as the phone does. Read-only: TCP connects,
 * then `hello` + `labs.pair.info`; no pairing, no input. Skipped unless asked:
 *   ./gradlew :app:testDebugUnitTest --tests '*LiveSweepTest' -Dmote.liveSweep=192.168.1.0/24
 * (optional -Dmote.liveSweep.port=8790). Asserts a helper is found and confirmed within 2 s.
 */
class LiveSweepTest {
    @Test fun findsTheHelperOnThisLanWithin2s() = runBlocking {
        val net = System.getProperty("mote.liveSweep")?.takeIf { it.isNotBlank() }
        assumeTrue("set -Dmote.liveSweep=a.b.c.0/24 to run", net != null)
        val (ip, prefix) = net!!.split('/').let { it[0] to (it.getOrNull(1)?.toInt() ?: 24) }
        val port = System.getProperty("mote.liveSweep.port")?.toInt() ?: Proto.DEFAULT_PORT
        // The sweep skips the phone's own address; a network address is never a host, so nothing is skipped here.
        val hosts = sweepHosts(ip, prefix)
        val http = helperHttpClient()
        val t0 = System.currentTimeMillis()
        val hit = withTimeoutOrNull(2000) { lanSweep(hosts, port).firstOrNull { probeHelper(http, it, port) != null } }
        val ms = System.currentTimeMillis() - t0
        assertNotNull("no Mote Helper answered on $net:$port within 2 s", hit)
        val p = probeHelper(http, hit!!, port)!!
        println("live sweep: ${p.name} (hostId ${p.hostId}) at ${p.address} in $ms ms; instant pairing info: ${p.info != null}")
        assertTrue(p.hostId.length == 16)
        assertTrue("took $ms ms", ms <= 2000)
    }
}
