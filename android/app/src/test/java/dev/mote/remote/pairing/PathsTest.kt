package dev.mote.remote.pairing

import dev.mote.remote.net.Found
import dev.mote.remote.net.Route
import dev.mote.remote.net.SavedHost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PathsTest {
    private val fp = "0123456789abcdef0123456789abcdef"
    private val info = BleInfo("0123456789abcdef", "Mac", fp, listOf("192.168.1.20", "10.0.0.5"), "mac.tail1.ts.net", "100.101.102.103", 8790)

    @Test fun lanThenTailnetNameThenTailnetIpThenBluetooth() {
        val saved = SavedHost("0123456789abcdef", "Mac", "k:x", lan = "192.168.1.9", local = "Macs-MBP", tailnet = "mac.tail1.ts.net", manual = "100.64.0.9")
        val p = selectPaths(info, saved, bonjour = "192.168.1.30", btAddress = "AA:BB:CC:DD:EE:FF")
        assertEquals(
            listOf(
                PairPath(Route.WIFI, "192.168.1.30"), PairPath(Route.WIFI, "192.168.1.20"), PairPath(Route.WIFI, "10.0.0.5"),
                PairPath(Route.WIFI, "192.168.1.9"), PairPath(Route.WIFI, "Macs-MBP.local"),
                PairPath(Route.TAILNET, "mac.tail1.ts.net"), PairPath(Route.TAILNET, "100.64.0.9"), PairPath(Route.TAILNET, "100.101.102.103"),
                PairPath(Route.BT, "AA:BB:CC:DD:EE:FF"),
            ),
            p,
        )
        assertEquals(8, helperCandidates(p).size)
    }

    @Test fun duplicatesKeepTheirBestPosition() {
        val p = selectPaths(info, SavedHost("0123456789abcdef", "Mac", "k:x", lan = "192.168.1.20"), bonjour = "192.168.1.20")
        assertEquals(listOf("192.168.1.20", "10.0.0.5", "mac.tail1.ts.net", "100.101.102.103"), helperCandidates(p))
    }

    @Test fun nothingKnownIsEmptyAndBtOnlyIsBt() {
        assertEquals(emptyList<PairPath>(), selectPaths(null, null))
        assertEquals(listOf(PairPath(Route.BT, "AA")), selectPaths(null, null, btAddress = "AA"))
    }

    @Test fun bleInfoParsing() {
        assertEquals(info, BleInfo.parse(info.toJson().toByteArray()))
        val min = BleInfo.parse("""{"v":1,"id":"0123456789abcdef","n":"","fp":"$fp","p":8791}""")!!
        assertEquals("Mac", min.name); assertEquals(8791, min.port); assertNull(min.tailnetDns); assertEquals(emptyList<String>(), min.lan)
        assertNull(BleInfo.parse("""{"v":2,"id":"0123456789abcdef","fp":"$fp"}"""))        // unknown version
        assertNull(BleInfo.parse("""{"v":1,"id":"XYZ","fp":"$fp"}"""))                      // bad id
        assertNull(BleInfo.parse("""{"v":1,"id":"0123456789abcdef","fp":"short"}"""))       // bad fp
        assertNull(BleInfo.parse("""{"v":1,"id":"0123456789abcdef","fp":"$fp","p":0}"""))  // bad port
        assertNull(BleInfo.parse("not json".toByteArray()))
        val evil = BleInfo.parse("""{"v":1,"id":"0123456789abcdef","fp":"$fp","lan":["1.2.3.4","a/b","x y"],"ts":"evil/../"}""")!!
        assertEquals(listOf("1.2.3.4"), evil.lan); assertNull(evil.tailnetDns)
    }

    @Test fun trustRecordsRoundTrip() {
        val r = TrustRecord("0123456789abcdef", "ab".repeat(32), fp, "tailscale", info)
        assertEquals(mapOf(r.hostId to r), TrustRecord.decode(TrustRecord.encode(listOf(r))))
        assertEquals(emptyMap<String, TrustRecord>(), TrustRecord.decode("garbage"))
    }

    @Test fun theMacsRefusalBeatsALaterUnreachablePath() {
        val start = dev.mote.remote.net.HelperError("noroute", "none yet")
        val far = dev.mote.remote.net.HelperError("far", "far")
        val timeout = java.net.SocketTimeoutException("connect timed out")
        // LAN says far, then the tailnet address times out: still far (so the app offers Bluetooth)
        assertEquals("far", (worse(worse(start, far), timeout) as dev.mote.remote.net.HelperError).code)
        // nothing answered at all: the network error
        assertEquals(timeout, worse(start, timeout))
        // a later refusal replaces an earlier one
        val stale = dev.mote.remote.net.HelperError("stale", "stale")
        assertEquals(stale, worse(far, stale))
    }
}
