package dev.mote.remote.pairing

import dev.mote.remote.net.Found
import dev.mote.remote.net.Msg
import dev.mote.remote.net.Route
import dev.mote.remote.net.WsSession
import dev.mote.remote.net.routeOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.SocketFactory

/** Where a Mac was found. Every source is merged by `hostId` ([mergeFound]). */
enum class Source { BLE, LAN_SCAN, KNOWN, BONJOUR }

/** A helper that answered over the network: its `hello`, plus `labs.pair.info` when it has instant pairing. */
data class Probed(val hostId: String, val name: String, val address: String, val port: Int, val info: BleInfo? = null)

/** One Mac, merged from every source. [addresses] are LAN first (answered before hints), then tailnet. */
data class FoundMac(
    val hostId: String, val name: String, val info: BleInfo?, val addresses: List<String>, val port: Int, val sources: Set<Source>,
) {
    val fp: String? get() = info?.fp
    val nearby: Boolean get() = Source.BLE in sources
}

/**
 * Pure merge (docs/pairing.md section 5): one entry per hostId, in the order the sources are listed
 * (BLE first: it proves proximity and carries the key fingerprint). Addresses that answered
 * (scan, known) come before hints (Bonjour, BLE info), LAN before tailnet.
 */
fun mergeFound(ble: List<NearbyMac>, scan: List<Probed>, known: List<Probed>, bonjour: List<Found>): List<FoundMac> {
    class Acc(var name: String, var info: BleInfo?, var port: Int) {
        val answered = ArrayList<String>(); val hints = ArrayList<String>(); val sources = LinkedHashSet<Source>()
    }
    val byId = LinkedHashMap<String, Acc>()
    fun acc(id: String, name: String, port: Int) = byId.getOrPut(id) { Acc(name, null, port) }
    for (n in ble) acc(n.info.hostId, n.info.name, n.info.port).apply {
        name = n.info.name; info = n.info; port = n.info.port; sources += Source.BLE
        hints += n.info.lan; hints += listOfNotNull(n.info.tailnetDns, n.info.tailnetIp)
    }
    for ((list, src) in listOf(scan to Source.LAN_SCAN, known to Source.KNOWN)) for (p in list) acc(p.hostId, p.name, p.port).apply {
        if (info == null && p.info != null) { info = p.info; hints += p.info.lan; hints += listOfNotNull(p.info.tailnetDns, p.info.tailnetIp) }
        if (Source.BLE !in sources && name.isBlank()) name = p.name
        answered += p.address; sources += src
    }
    for (f in bonjour) { val id = f.hostId ?: continue; acc(id, f.service, f.port).apply { hints += f.address; sources += Source.BONJOUR } }
    return byId.map { (id, a) ->
        val all = (a.answered + a.hints).distinctBy { it.lowercase() }
        val ordered = all.filter { routeOf(it) != Route.TAILNET } + all.filter { routeOf(it) == Route.TAILNET }
        FoundMac(id, a.name.ifBlank { "Mac" }, a.info, ordered, a.port, a.sources)
    }
}

/**
 * The addresses a LAN sweep tries: the phone's subnet when it is /24 or smaller, else the /24 around
 * [ip]; without the network, broadcast and the phone's own address. Pure. Empty for non-IPv4.
 */
fun sweepHosts(ip: String, prefix: Int): List<String> {
    val b = ip.split('.').map { it.toIntOrNull() ?: return emptyList() }
    if (b.size != 4 || b.any { it !in 0..255 }) return emptyList()
    val self = (b[0] shl 24) or (b[1] shl 16) or (b[2] shl 8) or b[3]
    val bits = prefix.coerceIn(24, 30)
    val mask = -1 shl (32 - bits)
    val net = self and mask
    val size = 1 shl (32 - bits)
    return (1 until size - 1).map { net + it }.filter { it != self }.map { a ->
        "${(a ushr 24) and 255}.${(a ushr 16) and 255}.${(a ushr 8) and 255}.${a and 255}"
    }
}

/** TCP connect with a timeout: true if something listens at [host]:[port]. */
fun tcpOpen(host: String, port: Int, timeoutMs: Int, factory: SocketFactory = SocketFactory.getDefault()): Boolean = try {
    factory.createSocket().use { it.connect(InetSocketAddress(host, port), timeoutMs); true }
} catch (e: java.net.SocketTimeoutException) { false } catch (e: java.net.ConnectException) { false } catch (e: Exception) {
    lastSweepError = e; false
}

/** The last unexpected sweep failure (not a timeout / refusal), for diagnostics: a systematic one means the sweep is broken. */
@Volatile var lastSweepError: Exception? = null

/**
 * Connects to TCP [port] on every host, [parallel] at a time, [timeoutMs] each, and emits the ones
 * that accepted, as they do (a /24 takes about 254 / [parallel] x [timeoutMs] at worst).
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun lanSweep(
    hosts: List<String>, port: Int, timeoutMs: Int = 300, parallel: Int = 128,
    open: (String, Int, Int) -> Boolean = { h, p, t -> tcpOpen(h, p, t) },
): Flow<String> = channelFlow {
    val io = Dispatchers.IO.limitedParallelism(parallel)
    val gate = Semaphore(parallel)
    val jobs = hosts.map { h -> launch(io) { gate.withPermit { if (open(h, port, timeoutMs)) send(h) } } }
    launch { jobs.forEach { it.join() }; close() }
    awaitClose()
}

/**
 * Confirms that [address] is a Mote Helper: its `hello` (hostId required), then `labs.pair.info`
 * (fingerprint and other addresses; null from a helper without instant pairing). Null if not a helper.
 */
suspend fun probeHelper(http: OkHttpClient, address: String, port: Int, infoTimeoutMs: Long = 1500): Probed? = withContext(Dispatchers.IO) {
    val s = runCatching { WsSession.open(http, address, port) }.getOrNull() ?: return@withContext null
    try {
        val id = s.hello.hostId ?: return@withContext null
        s.send(PairProto.info())
        val info = withTimeoutOrNull(infoTimeoutMs) {
            var r: BleInfo? = null
            while (r == null) {
                val m = s.inbox.receive()
                if (m is Msg.Err) break
                if (m is Msg.Other) r = runCatching { BleInfo.from(JSONObject(m.raw)) }.getOrNull()
            }
            r
        }
        Probed(id, s.hello.host.ifBlank { info?.name ?: address }, address, port, info?.takeIf { it.hostId == id })
    } catch (_: Exception) { null } finally { s.close() }
}

/** What a TCP connect to the helper port says about the Mac. */
enum class Reach { OPEN, REFUSED, TIMEOUT }

/** OPEN: something listens; REFUSED: the Mac answered but nothing listens (helper not running); TIMEOUT: no answer (asleep / away). */
fun tcpReach(host: String, port: Int, timeoutMs: Int = 1500, factory: SocketFactory = SocketFactory.getDefault()): Reach = try {
    (factory.createSocket() as Socket).use { it.connect(InetSocketAddress(host, port), timeoutMs) }
    Reach.OPEN
} catch (_: java.net.ConnectException) {
    Reach.REFUSED
} catch (_: Exception) {
    Reach.TIMEOUT
}

/**
 * Pure: plain words for a saved Mac that doesn't connect, from probing its addresses. Null when the
 * helper port is open (the problem is elsewhere: auth, Host rules) or nothing was probed.
 */
fun reachNote(name: String, reaches: List<Reach>): String? = when {
    reaches.isEmpty() || Reach.OPEN in reaches -> null
    Reach.REFUSED in reaches -> "Mote Helper isn't running on $name. Open it from Applications on the Mac."
    else -> "$name seems asleep or away. Wake it (open the lid or press a key) and keep it on this Wi-Fi or Tailscale."
}
