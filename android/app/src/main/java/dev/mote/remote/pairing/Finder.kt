package dev.mote.remote.pairing

import dev.mote.remote.diag.Events
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import dev.mote.remote.hid.TestSeam
import dev.mote.remote.net.Net
import dev.mote.remote.net.Proto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address

/** The phone's IPv4 on Wi-Fi (not a VPN), with its prefix and the network to bind sockets to. */
data class WifiAddr(val ip: String, val prefix: Int, val network: Network)

/**
 * Finds Mote Helpers from every source at once and merges them by hostId ([mergeFound]):
 * BLE (if already permitted), a TCP sweep of the phone's /24 (multicast-free: works where
 * Bonjour is blocked), Bonjour, and the addresses this phone already knows. Each run is
 * time-boxed ([RUN_MS]); [running] goes false when every source is done.
 */
class Finder(context: Context, private val scope: CoroutineScope) {
    private val app = context.applicationContext
    private val scan = MutableStateFlow<List<Probed>>(emptyList())
    private val known = MutableStateFlow<List<Probed>>(emptyList())
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()
    /** Wall clock of the current run's start (0: never started). */
    @Volatile var startedAt = 0L
        private set
    private var job: Job? = null

    val found: StateFlow<List<FoundMac>> = combine(InstantPairing.ble.found, scan, known, Net.discovery.found) { b, s, k, n ->
        mergeFound(b, s, k, if (TestSeam.noBonjour) emptyList() else n)
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    fun start() {
        if (job?.isActive == true) return
        startedAt = System.currentTimeMillis()
        _running.value = true // earlier hits stay listed: a re-run (e.g. Wi-Fi came back) must not hide a Mac already shown
        InstantPairing.ble.start()
        if (!TestSeam.noBonjour) Net.discovery.start()
        job = scope.launch {
            val a = launch { runCatching { sweep() }.onFailure { Log.w(TAG, "sweep failed", it) } }
            val b = launch { runCatching { probeKnown() }.onFailure { Log.w(TAG, "known failed", it) } }
            withTimeoutOrNull(RUN_MS) { a.join(); b.join() } ?: run { a.cancel(); b.cancel() }
            lastSweepError?.let { Log.w(TAG, "sweep error", it) }
            Log.w(TAG, "run done in ${System.currentTimeMillis() - startedAt} ms: ${found.value.map { it.name to it.sources }}")
            _running.value = false
        }
    }

    fun stop() {
        job?.cancel(); job = null
        _running.value = false
        InstantPairing.ble.stop()
        Net.discovery.stop()
    }

    private suspend fun sweep() {
        val w = wifiAddr(app) ?: run { Log.w(TAG, "sweep: not on Wi-Fi"); Events.log("discovery", "sweep.done", "result" to "no wifi", "opened" to 0); return }
        Events.log("discovery", "sweep.start", "prefix" to w.prefix, "hosts" to sweepHosts(w.ip, w.prefix).size, "port" to (TestSeam.sweepPort ?: Proto.DEFAULT_PORT))
        Log.w(TAG, "sweep: ${w.ip}/${w.prefix}")
        val port = TestSeam.sweepPort ?: Proto.DEFAULT_PORT
        val sockets = WifiSocketFactory(w.network)
        val http = Net.http.newBuilder().socketFactory(sockets).build()
        val t0 = System.currentTimeMillis()
        var opened = 0
        lanSweep(sweepHosts(w.ip, w.prefix), port) { h, p, t -> tcpOpen(h, p, t, sockets) }.collect { addr ->
            opened++
            Events.log("discovery", "sweep.open", "afterMs" to System.currentTimeMillis() - t0)
            Log.w(TAG, "sweep: $addr:$port open after ${System.currentTimeMillis() - t0} ms")
            scope.launch {
                val p = probeHelper(http, addr, port) ?: run {
                    Log.w(TAG, "sweep: $addr is not a Mote Helper"); Events.log("discovery", "sweep.probe", "helper" to false); return@launch
                }
                Events.log("discovery", "sweep.probe", "helper" to true, "afterMs" to System.currentTimeMillis() - t0)
                Log.w(TAG, "sweep: ${p.name} at $addr in ${System.currentTimeMillis() - t0} ms")
                scan.value = scan.value.filterNot { it.address == addr } + p
            }
        }
        Events.log("discovery", "sweep.done", "result" to "finished", "opened" to opened, "ms" to System.currentTimeMillis() - t0, "error" to lastSweepError?.javaClass?.simpleName)
    }

    /** Saved hosts and trust records: cached LAN IP, MagicDNS name, tailnet IP. */
    private suspend fun probeKnown() {
        val trust = InstantPairing.records.value
        val targets = Net.saved.value.flatMap { h ->
            helperCandidates(selectPaths(trust[h.hostId]?.info, h)).map { it to h.port }
        }.distinct()
        val jobs = targets.map { (addr, port) ->
            scope.launch {
                val p = probeHelper(Net.http, addr, port)
                Events.log("discovery", "known.probe", "helper" to (p != null))
                if (p == null) return@launch
                known.value = known.value.filterNot { it.address == addr } + p
            }
        }
        jobs.forEach { it.join() }
    }

    companion object {
        private const val TAG = "MoteFinder"
        const val RUN_MS = 8000L
    }
}

/** The phone's Wi-Fi IPv4 address and prefix, or null when not on Wi-Fi. */
@Suppress("DEPRECATION") // allNetworks: the default network may be a VPN (Tailscale)
fun wifiAddr(c: Context): WifiAddr? = runCatching {
    val cm = c.getSystemService(ConnectivityManager::class.java)
    // During a Wi-Fi reconnect Android lists the old (lingering) and the new Wi-Fi network; allNetworks order is
    // arbitrary, and sockets bound to the old one fail at once (sweep "no Mac", journeys). Prefer the active
    // network (when it isn't the VPN), then a validated one; an unvalidated LAN without internet still qualifies.
    val active = cm.activeNetwork
    cm.allNetworks.sortedByDescending { n ->
        (if (n == active) 2 else 0) + (if (cm.getNetworkCapabilities(n)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true) 1 else 0)
    }.firstNotNullOfOrNull { n ->
        val caps = cm.getNetworkCapabilities(n) ?: return@firstNotNullOfOrNull null
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return@firstNotNullOfOrNull null
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return@firstNotNullOfOrNull null
        val la = cm.getLinkProperties(n)?.linkAddresses?.firstOrNull { it.address is Inet4Address && !it.address.isLoopbackAddress }
            ?: return@firstNotNullOfOrNull null
        WifiAddr(la.address.hostAddress ?: return@firstNotNullOfOrNull null, la.prefixLength, n)
    }
}.getOrNull()

/**
 * Sockets bound to [network] (the Wi-Fi) when Android allows it, else unbound. 0.6.0 used
 * `Network.socketFactory`: with the Tailscale VPN on, every bind fails with EPERM, so every sweep
 * connect failed at once and a Mac on the same Wi-Fi was "not found" (Nothing Phone 2, Android 16).
 */
class WifiSocketFactory(private val network: Network) : javax.net.SocketFactory() {
    // Under a VPN that doesn't allow bypass (Tailscale), binding to the Wi-Fi fails with EPERM; the
    // default route then still reaches the LAN (Tailscale doesn't route it), so fall back to it.
    private fun bound(): java.net.Socket = java.net.Socket().also { s -> runCatching { network.bindSocket(s) } }
    override fun createSocket(): java.net.Socket = bound()
    override fun createSocket(host: String, port: Int): java.net.Socket = bound().apply { connect(java.net.InetSocketAddress(host, port)) }
    override fun createSocket(host: String, port: Int, localHost: java.net.InetAddress, localPort: Int): java.net.Socket =
        bound().apply { bind(java.net.InetSocketAddress(localHost, localPort)); connect(java.net.InetSocketAddress(host, port)) }
    override fun createSocket(host: java.net.InetAddress, port: Int): java.net.Socket = bound().apply { connect(java.net.InetSocketAddress(host, port)) }
    override fun createSocket(address: java.net.InetAddress, port: Int, localAddress: java.net.InetAddress, localPort: Int): java.net.Socket =
        bound().apply { bind(java.net.InetSocketAddress(localAddress, localPort)); connect(java.net.InetSocketAddress(address, port)) }
}
