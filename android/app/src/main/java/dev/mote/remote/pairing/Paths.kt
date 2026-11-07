package dev.mote.remote.pairing

import dev.mote.remote.net.Route
import dev.mote.remote.net.SavedHost
import dev.mote.remote.net.isIpLiteral
import dev.mote.remote.net.routeOf

/** One way to reach a Mac. [address] is null only for [Route.BT] without a linked HID host. */
data class PairPath(val route: Route, val address: String)

/**
 * Connection order for a Mac (docs/pairing.md section 5), pure:
 * 1. LAN: fresh Bonjour address, BLE info LAN IPs, last working LAN IP, `<local>.local`;
 * 2. tailnet: MagicDNS name (stable), then the 100.x / fd7a: address;
 * 3. Bluetooth HID, when the Mac is linked to a bonded HID host ([btAddress]).
 * Duplicates keep their first (best) position. A manual address goes with its route.
 */
fun selectPaths(info: BleInfo?, saved: SavedHost?, bonjour: String? = null, btAddress: String? = null): List<PairPath> {
    val lan = listOfNotNull(bonjour) + info?.lan.orEmpty() + listOfNotNull(saved?.lan, saved?.local?.let { "$it.local" })
    val tailnet = listOfNotNull(info?.tailnetDns, saved?.tailnet, saved?.manual, info?.tailnetIp)
    val manualLan = listOfNotNull(saved?.manual).filter { routeOf(it) == Route.WIFI }
    val ts = tailnet.filter { routeOf(it) == Route.TAILNET }.sortedBy { if (isIpLiteral(it)) 1 else 0 } // stable sort: names first
    val out = (lan + manualLan).filter { routeOf(it) == Route.WIFI }.map { PairPath(Route.WIFI, it) } +
        ts.map { PairPath(Route.TAILNET, it) } +
        listOfNotNull(btAddress?.let { PairPath(Route.BT, it) })
    return out.distinctBy { it.route to it.address.lowercase() }
}

/** The helper (WebSocket) addresses of [paths], in order. */
fun helperCandidates(paths: List<PairPath>): List<String> = paths.filter { it.route != Route.BT }.map { it.address }

/**
 * The error worth showing after trying several paths: the Mac's own answer (a refusal such as
 * `far`) beats a later path that couldn't be reached. (0.6.0 bug: an unreachable tailnet address
 * hid `far` behind "Couldn't reach", so the Bluetooth proof was never offered.)
 */
fun worse(prev: Exception, next: Exception): Exception = when {
    prev is dev.mote.remote.net.HelperError && prev.code != "noroute" && next !is dev.mote.remote.net.HelperError -> prev
    else -> next
}
