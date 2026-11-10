package dev.mote.remote.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresApi
import dev.mote.remote.diag.Events
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.Inet4Address
import java.net.InetAddress

/**
 * Finds Mote Helpers on the LAN (`_mote._tcp`). Runs only while asked (the host picker is open),
 * time-boxed, and holds a Wi-Fi multicast lock only while scanning. Resolution uses
 * registerServiceInfoCallback on API 34+; below that, resolves are serialised (the legacy API
 * allows one at a time). All state changes happen on the main thread.
 */
class Discovery(context: Context) {
    private val app = context.applicationContext
    private val nsd = app.getSystemService(NsdManager::class.java)
    private val wifi = app.getSystemService(WifiManager::class.java)
    private val main = Handler(Looper.getMainLooper())

    private val _found = MutableStateFlow<List<Found>>(emptyList())
    val found: StateFlow<List<Found>> = _found.asStateFlow()
    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private var lock: WifiManager.MulticastLock? = null
    private var listener: NsdManager.DiscoveryListener? = null
    private val infoCallbacks = HashMap<String, Any>() // NsdManager.ServiceInfoCallback on 34+
    private val pending = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private val timeout = Runnable { stop() }

    fun start(durationMs: Long = 30_000) {
        if (listener != null || nsd == null) {
            if (nsd == null) Events.log("discovery", "nsd.failed", "reason" to "no NsdManager")
            return
        }
        Events.log("discovery", "nsd.start", "durationMs" to durationMs)
        lock = wifi?.createMulticastLock("mote-nsd")?.apply { setReferenceCounted(false); runCatching { acquire() } }
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "discovery failed: $errorCode"); Events.log("discovery", "nsd.failed", "code" to errorCode); main.post { stop() }
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceFound(info: NsdServiceInfo) { Log.i(TAG, "found ${info.serviceName}"); Events.log("discovery", "nsd.found"); main.post { resolve(info) } }
            override fun onServiceLost(info: NsdServiceInfo) { main.post { lost(info.serviceName) } }
        }
        listener = l
        _scanning.value = true
        runCatching { nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l) }.onFailure {
            Events.log("discovery", "nsd.failed", "reason" to (it.message ?: it.javaClass.simpleName).take(80)); stop()
        }
        main.postDelayed(timeout, durationMs)
    }

    fun stop() {
        main.removeCallbacks(timeout)
        listener?.let { l -> runCatching { nsd?.stopServiceDiscovery(l) } }
        listener = null
        if (Build.VERSION.SDK_INT >= 34) infoCallbacks.values.forEach { unregister(it) }
        infoCallbacks.clear()
        pending.clear(); resolving = false
        lock?.let { runCatching { if (it.isHeld) it.release() } }
        lock = null
        _scanning.value = false
    }

    private fun resolve(info: NsdServiceInfo) {
        if (listener == null) return
        if (Build.VERSION.SDK_INT >= 34) { watch(info); return }
        pending.addLast(info)
        resolveNext()
    }

    @RequiresApi(34)
    private fun watch(info: NsdServiceInfo) {
        if (info.serviceName in infoCallbacks) return
        val cb = object : NsdManager.ServiceInfoCallback {
            override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) { Log.w(TAG, "info callback failed: $errorCode") }
            override fun onServiceUpdated(si: NsdServiceInfo) { main.post { update(si, si.hostAddresses) } }
            override fun onServiceLost() { main.post { lost(info.serviceName) } }
            override fun onServiceInfoCallbackUnregistered() {}
        }
        infoCallbacks[info.serviceName] = cb
        runCatching { nsd?.registerServiceInfoCallback(info, { main.post(it) }, cb) }
    }

    @RequiresApi(34)
    private fun unregister(cb: Any) { runCatching { nsd?.unregisterServiceInfoCallback(cb as NsdManager.ServiceInfoCallback) } }

    @Suppress("DEPRECATION") // resolveService is the only resolver below API 34
    private fun resolveNext() {
        if (resolving || listener == null) return
        val info = pending.removeFirstOrNull() ?: return
        resolving = true
        nsd?.resolveService(info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(si: NsdServiceInfo, errorCode: Int) {
                main.post { resolving = false; resolveNext() }
            }
            override fun onServiceResolved(si: NsdServiceInfo) {
                main.post { update(si, listOfNotNull(si.host)); resolving = false; resolveNext() }
            }
        })
    }

    private fun update(si: NsdServiceInfo, addrs: List<InetAddress>) {
        if (listener == null) return
        // Prefer IPv4: it survives in saved hosts and avoids link-local scope ids.
        Log.i(TAG, "resolved ${si.serviceName} ${addrs.size} addrs port ${si.port}")
        val a = addrs.firstOrNull { it is Inet4Address } ?: addrs.firstOrNull() ?: run {
            Events.log("discovery", "nsd.failed", "reason" to "no address", "port" to si.port); return
        }
        Events.log("discovery", "nsd.resolved", "addresses" to addrs.size, "port" to si.port, "hasId" to (si.attributes["id"] != null))
        val id = si.attributes["id"]?.toString(Charsets.UTF_8)?.takeIf { it.isNotBlank() }
        val f = Found(si.serviceName, id, a.hostAddress?.substringBefore('%') ?: return, si.port)
        _found.value = _found.value.filterNot { it.service == f.service } + f
    }

    private fun lost(service: String) {
        Events.log("discovery", "nsd.lost")
        _found.value = _found.value.filterNot { it.service == service }
        if (Build.VERSION.SDK_INT >= 34) infoCallbacks.remove(service)?.let { unregister(it) }
    }

    companion object {
        private const val TAG = "MoteNsd"
        const val SERVICE_TYPE = "_mote._tcp."
    }
}
