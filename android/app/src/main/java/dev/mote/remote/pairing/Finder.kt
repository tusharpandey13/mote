package dev.mote.remote.pairing

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
        _running.value = true
        scan.value = emptyList()
        InstantPairing.ble.start()
        if (!TestSeam.noBonjour) Net.discovery.start()
        job = scope.launch {
            val a = launch { sweep() }
            val b = launch { probeKnown() }
            withTimeoutOrNull(RUN_MS) { a.join(); b.join() } ?: run { a.cancel(); b.cancel() }
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
        val w = wifiAddr(app) ?: run { Log.i(TAG, "sweep: not on Wi-Fi"); return }
        val port = TestSeam.sweepPort ?: Proto.DEFAULT_PORT
        val http = Net.http.newBuilder().socketFactory(w.network.socketFactory).build()
        val t0 = System.currentTimeMillis()
        lanSweep(sweepHosts(w.ip, w.prefix), port) { h, p, t -> tcpOpen(h, p, t, w.network.socketFactory) }.collect { addr ->
            scope.launch {
                val p = probeHelper(http, addr, port) ?: return@launch
                Log.i(TAG, "sweep: ${p.name} at $addr in ${System.currentTimeMillis() - t0} ms")
                scan.value = scan.value.filterNot { it.address == addr } + p
            }
        }
    }

    /** Saved hosts and trust records: cached LAN IP, MagicDNS name, tailnet IP. */
    private suspend fun probeKnown() {
        val trust = InstantPairing.records.value
        val targets = Net.saved.value.flatMap { h ->
            helperCandidates(selectPaths(trust[h.hostId]?.info, h)).map { it to h.port }
        }.distinct()
        val jobs = targets.map { (addr, port) ->
            scope.launch {
                val p = probeHelper(Net.http, addr, port) ?: return@launch
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
    cm.allNetworks.firstNotNullOfOrNull { n ->
        val caps = cm.getNetworkCapabilities(n) ?: return@firstNotNullOfOrNull null
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return@firstNotNullOfOrNull null
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return@firstNotNullOfOrNull null
        val la = cm.getLinkProperties(n)?.linkAddresses?.firstOrNull { it.address is Inet4Address && !it.address.isLoopbackAddress }
            ?: return@firstNotNullOfOrNull null
        WifiAddr(la.address.hostAddress ?: return@firstNotNullOfOrNull null, la.prefixLength, n)
    }
}.getOrNull()
