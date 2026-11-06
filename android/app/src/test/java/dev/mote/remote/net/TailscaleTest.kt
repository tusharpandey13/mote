package dev.mote.remote.net

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class TailscaleTest {
    private fun ip(s: String) = InetAddress.getByName(s)
    private val cell = NetLink(vpn = false, wifi = false, iface = "rmnet_data2", addrs = listOf(ip("192.0.0.2")))
    private val wifi = NetLink(vpn = false, wifi = true, iface = "wlan0", addrs = listOf(ip("192.168.1.9")))
    // As seen on a Nothing Phone 2 with Tailscale on: tun2 holding 100.94.249.58/32 and fd7a:115c:a1e0::7c01:f9d7
    private val ts = NetLink(vpn = true, wifi = false, iface = "tun2", addrs = listOf(ip("100.94.249.58"), ip("fd7a:115c:a1e0::7c01:f9d7")))
    // NextDNS (another VPN) on the same phone: tun0 with 192.0.2.1
    private val nextDns = NetLink(vpn = true, wifi = false, iface = "tun0", addrs = listOf(ip("192.0.2.1")))

    @Test fun tailscaleVpnIsDetectedByItsAddresses() {
        assertTrue(tailnetUp(listOf(cell, ts)))
        assertTrue(tailnetUp(listOf(ts.copy(addrs = listOf(ip("fd7a:115c:a1e0::1"))))))   // IPv6-only tailnet
        assertFalse(tailnetUp(listOf(cell)))
        assertFalse("another VPN is not Tailscale", tailnetUp(listOf(cell, nextDns)))
        assertFalse("a 100.64/10 address on a non-VPN network (carrier CGNAT) is not Tailscale",
            tailnetUp(listOf(cell.copy(addrs = listOf(ip("100.70.1.2"))))))
    }

    @Test fun tailnetRanges() {
        assertTrue(isTailnetAddress(ip("100.64.0.1")))
        assertTrue(isTailnetAddress(ip("100.127.255.254")))
        assertFalse(isTailnetAddress(ip("100.128.0.1")))
        assertFalse(isTailnetAddress(ip("100.63.255.255")))
        assertFalse(isTailnetAddress(ip("fd7a:115c:a1e1::1")))
        assertFalse(isTailnetAddress(ip("10.0.0.1")))
    }

    @Test fun wifiMeansANonVpnWifiNetwork() {
        assertTrue(onWifi(listOf(wifi, ts)))
        assertFalse(onWifi(listOf(cell, ts.copy(wifi = true)))) // a VPN over Wi-Fi reports WIFI too; it doesn't count
    }

    @Test fun promptOnlyWhenTheTailnetIsTheOnlyRoute() {
        val tsOnly = listOf(Route.TAILNET)
        val both = listOf(Route.WIFI, Route.TAILNET)
        assertTrue("away from home, Tailscale off", needsTailscalePrompt(both, tailnetUp = false, onWifi = false, seenOnLan = false))
        assertTrue("tailnet-only host, even on Wi-Fi", needsTailscalePrompt(tsOnly, tailnetUp = false, onWifi = true, seenOnLan = false))
        assertFalse("at home: the LAN route works", needsTailscalePrompt(both, tailnetUp = false, onWifi = true, seenOnLan = false))
        assertFalse("seen nearby on this Wi-Fi", needsTailscalePrompt(tsOnly, tailnetUp = false, onWifi = true, seenOnLan = true))
        assertFalse("Tailscale already on", needsTailscalePrompt(both, tailnetUp = true, onWifi = false, seenOnLan = false))
        assertFalse("no tailnet route", needsTailscalePrompt(listOf(Route.WIFI, Route.BT), tailnetUp = false, onWifi = false, seenOnLan = false))
    }
}
