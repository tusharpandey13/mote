package dev.mote.remote.net

import dev.mote.remote.hid.Host
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HostsTest {
    private val mac = SavedHost(
        hostId = "0123456789abcdef", name = "Tushar’s MacBook Pro", deviceId = "dev",
        lan = "192.168.1.20", local = "Tushars-MacBook-Pro", tailnet = "mac.tail1234.ts.net",
    )

    @Test fun fallbackOrderIsNsdThenCachedIpThenLocalThenTailnetThenManual() {
        assertEquals(
            listOf("192.168.1.20", "Tushars-MacBook-Pro.local", "mac.tail1234.ts.net"),
            mac.candidates(),
        )
        assertEquals(
            listOf("192.168.1.33", "192.168.1.20", "Tushars-MacBook-Pro.local", "mac.tail1234.ts.net", "10.0.0.9"),
            mac.copy(manual = "10.0.0.9").candidates(discovered = "192.168.1.33"),
        )
        // duplicates collapse (NSD returned the cached IP)
        assertEquals(listOf("192.168.1.20", "Tushars-MacBook-Pro.local", "mac.tail1234.ts.net"), mac.candidates("192.168.1.20"))
        // a tailnet-only host (entered by address) has a single candidate
        assertEquals(listOf("100.101.1.2"), SavedHost("x", "x", "d").withAddress("100.101.1.2").candidates())
    }

    @Test fun addressesLandInTheRightSlot() {
        val h = SavedHost("x", "x", "d")
        assertEquals("100.64.0.1", h.withAddress("100.64.0.1").tailnet)
        assertEquals("mac.foo.ts.net", h.withAddress("mac.foo.ts.net").tailnet)
        assertEquals("fd7a:115c:a1e0::5", h.withAddress("fd7a:115c:a1e0::5").tailnet)
        assertEquals("10.1.2.3", h.withAddress("10.1.2.3").lan)
        assertEquals("Mac", h.withAddress("Mac.local").local)
        assertEquals("nas.home.arpa", h.withAddress("nas.home.arpa").manual)
        // A 100.x typed for a host that has a MagicDNS name keeps the name (stable) and adds the IP.
        val named = h.withAddress("mac.tail.ts.net").withAddress("100.83.11.75")
        assertEquals("mac.tail.ts.net", named.tailnet)
        assertEquals("100.83.11.75", named.manual)
        assertEquals(listOf("mac.tail.ts.net", "100.83.11.75"), named.candidates())
        assertEquals(listOf(Route.TAILNET), named.routes)
    }

    @Test fun routeClassification() {
        assertEquals(Route.TAILNET, routeOf("100.127.255.1"))
        assertEquals(Route.WIFI, routeOf("100.128.0.1"))
        assertEquals(Route.WIFI, routeOf("100.63.0.1"))
        assertEquals(Route.TAILNET, routeOf("Mac.Tail.TS.NET."))
        assertEquals(Route.WIFI, routeOf("192.168.0.2"))
        assertEquals(listOf(Route.WIFI, Route.TAILNET), mac.routes)
        assertEquals(listOf(Route.TAILNET), SavedHost("x", "x", "d", manual = "100.100.1.1").routes)
    }

    @Test fun parsesTypedAddresses() {
        assertEquals("mac.tail.ts.net" to 8790, parseAddress(" mac.tail.ts.net "))
        assertEquals("100.64.1.2" to 9000, parseAddress("100.64.1.2:9000"))
        assertEquals("fd7a:115c:a1e0::1" to 8790, parseAddress("[fd7a:115c:a1e0::1]"))
        assertEquals("fd7a:115c:a1e0::1" to 8791, parseAddress("[fd7a:115c:a1e0::1]:8791"))
        assertEquals("fd7a:115c:a1e0::1" to 8790, parseAddress("fd7a:115c:a1e0::1"))
        assertEquals("mac.local" to 8790, parseAddress("http://mac.local:8790/#code=1"))
        assertNull(parseAddress(""))
        assertNull(parseAddress("bad host"))
        assertNull(parseAddress("mac:99999"))
    }

    @Test fun parsesPairingLinks() {
        assertEquals(
            PairLink("192.168.1.20", 8790, "123456", "0123456789abcdef", "mac.tail1234.ts.net"),
            PairLink.parse("http://192.168.1.20:8790/#code=123456&id=0123456789abcdef&ts=mac.tail1234.ts.net"),
        )
        assertEquals(
            PairLink("Mac.local", 8790, "654321", "abc", null),
            PairLink.parse("mote://pair?h=Mac.local%3A8790&code=654321&id=abc"),
        )
        // v1.0 helper QR: just the code
        assertEquals(PairLink("10.0.0.2", 8790, "000111"), PairLink.parse("http://10.0.0.2:8790/#code=000111"))
        assertNull(PairLink.parse("http://10.0.0.2:8790/#code=12345"))
        assertNull(PairLink.parse("https://evil.example/#code=123456"))
        assertNull(PairLink.parse("garbage"))
    }

    @Test fun savedHostsRoundTrip() {
        val list = listOf(mac, SavedHost("y", "Y", "d2", wrapped = "blob", btAddress = "AA:BB", port = 8791))
        assertEquals(list, SavedHost.decode(SavedHost.encode(list)))
        assertEquals(emptyList<SavedHost>(), SavedHost.decode(null))
        assertEquals(emptyList<SavedHost>(), SavedHost.decode("{"))
    }

    @Test fun selectionEncoding() {
        assertEquals(Selection.Bt("AA:BB:CC"), Selection.decode(Selection.Bt("AA:BB:CC").encode()))
        assertEquals(Selection.Helper("abc"), Selection.decode("ws:abc"))
        assertNull(Selection.decode("zz"))
    }

    @Test fun hostListGroupsOneMacAcrossTransports() {
        val btMac = Host("AA:BB", "Tushar's MacBook Pro", Host.Kind.LAPTOP) // straight apostrophe: still the same Mac
        val btTv = Host("CC:DD", "Living room TV")
        val nsdMac = Found("Tushar’s MacBook Pro", "0123456789abcdef", "192.168.1.33", 8790)
        val nsdOther = Found("Studio iMac", "fedcba9876543210", "192.168.1.40", 8790)
        val rows = hostRows(listOf(btMac, btTv), listOf(mac), listOf(nsdMac, nsdOther))

        assertEquals(listOf("h:0123456789abcdef", "bt:CC:DD", "f:fedcba9876543210"), rows.map { it.key })
        val r = rows[0]
        assertEquals(btMac, r.bt)
        assertEquals(nsdMac, r.found)
        assertEquals(listOf(Route.WIFI, Route.TAILNET, Route.BT), r.routes)
        assertEquals(Selection.Helper("0123456789abcdef"), r.defaultSelection)
        assertEquals(listOf(Route.BT), rows[1].routes)
        assertEquals(Selection.Bt("CC:DD"), rows[1].defaultSelection)
        assertEquals(listOf(Route.WIFI), rows[2].routes)
        assertNull("unpaired helper: tapping it starts pairing", rows[2].defaultSelection)
    }

    @Test fun hostListPrefersLinkedBtAddressAndMergesUnpairedTwins() {
        val linked = mac.copy(name = "Renamed", btAddress = "AA:BB")
        val rows = hostRows(listOf(Host("AA:BB", "Old name")), listOf(linked), emptyList())
        assertEquals(1, rows.size)
        assertEquals("AA:BB", rows[0].bt?.address)

        // A helper found nearby, not paired yet, next to its Bluetooth twin: one row
        val rows2 = hostRows(listOf(Host("EE:FF", "Studio iMac")), emptyList(), listOf(Found("Studio iMac", "1111", "10.0.0.5", 8790)))
        assertEquals(1, rows2.size)
        assertEquals(listOf(Route.WIFI, Route.BT), rows2[0].routes)
        assertEquals(Selection.Bt("EE:FF"), rows2[0].defaultSelection)

        // same hostId seen twice (IPv4 and IPv6 service instances) groups into one row
        val rows3 = hostRows(emptyList(), emptyList(), listOf(Found("A", "id1", "10.0.0.5", 8790), Found("A (2)", "id1", "fe80::1", 8790)))
        assertEquals(1, rows3.size)
    }

    @Test fun nearbyUnpairedOnePerHelper() {
        // Bonjour conflict renames of one helper (same id), a helper without an id seen twice, a paired one
        val found = listOf(
            Found("Mac (2)", "id1", "192.168.1.7", 8787), Found("Mac", "id1", "192.168.1.7", 8787), Found("Mac (3)", "id1", "192.168.1.7", 8787),
            Found("Old", null, "192.168.1.8", 8787), Found("Old (2)", null, "192.168.1.8", 8787), Found("Other", null, "192.168.1.8", 9000),
            Found("Paired", mac.hostId, "192.168.1.9", 8787),
        )
        assertEquals(listOf("Mac (2)", "Old", "Other"), nearbyUnpaired(found, listOf(mac)).map { it.service })
    }

    @Test fun bluetoothOnlyRowsAreForgetInAndroidSettings() {
        val bt = Host("AA:BB:CC:DD:EE:01", "Mac", Host.Kind.LAPTOP)
        val rows = hostRows(listOf(bt), emptyList(), emptyList())
        assertEquals(true, rows.single().btOnly)
        // a paired helper Mac (even with a Bluetooth twin) is forgotten in-app
        assertEquals(false, hostRows(listOf(bt), listOf(mac.copy(btAddress = bt.address)), emptyList()).single().btOnly)
        // a helper that is only nearby has no Bluetooth: nothing to forget
        assertEquals(false, HostRow("f:x", "x").btOnly)
    }
}
