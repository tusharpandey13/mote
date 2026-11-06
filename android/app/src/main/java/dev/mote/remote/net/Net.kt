package dev.mote.remote.net

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.mote.remote.hid.Hid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Process-wide owner of helper hosts: saved hosts (DataStore "hosts"), the selected route, the
 * active [Transport] and LAN discovery. Bluetooth HID stays owned by [Hid]; selecting a helper
 * host only asks [Hid] to stop reconnecting.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@SuppressLint("StaticFieldLeak") // holds only the application context
object Net {
    private val Context.store by preferencesDataStore("hosts")
    private val HOSTS = stringPreferencesKey("helper_hosts")
    private val SELECTION = stringPreferencesKey("selection")
    private val WIFI_CHOSEN = booleanPreferencesKey("wifi_chosen")

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val http by lazy { helperHttpClient() }
    private lateinit var app: Context

    private val btHid = BtHidTransport(scope)
    val bt: Transport = btHid
    private val _active = MutableStateFlow(bt)
    val active: StateFlow<Transport> = _active.asStateFlow()

    private val _saved = MutableStateFlow<List<SavedHost>>(emptyList())
    val saved: StateFlow<List<SavedHost>> = _saved.asStateFlow()
    private val _selection = MutableStateFlow<Selection?>(null)
    val selection: StateFlow<Selection?> = _selection.asStateFlow()
    /** The user chose "Use Wi-Fi with Mote Helper" on the first-run gate (no Bluetooth needed). */
    private val _wifiChosen = MutableStateFlow(false)
    val wifiChosen: StateFlow<Boolean> = _wifiChosen.asStateFlow()
    /** A pairing link opened from outside (mote://pair). The UI shows the pairing sheet for it. */
    val pendingLink = MutableStateFlow<PairLink?>(null)

    /** Status of the helper connection when a helper host is active, else Idle. */
    val wsStatus: StateFlow<WsStatus> = _active
        .flatMapLatest { (it as? WsTransport)?.status ?: flowOf(WsStatus.Idle) }
        .stateIn(scope, SharingStarted.Eagerly, WsStatus.Idle)

    val discovery by lazy { Discovery(app) }

    private val fgFlow = MutableStateFlow(false)
    private val lowLatency by lazy {
        @Suppress("DEPRECATION") // WIFI_MODE_FULL_HIGH_PERF is the pre-29 fallback
        val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
        app.getSystemService(WifiManager::class.java).createWifiLock(mode, "mote-input").apply { setReferenceCounted(false) }
    }

    private var loaded = false
    private var stopJob: Job? = null

    fun init(context: Context) {
        if (loaded) return
        loaded = true
        app = context.applicationContext
        scope.launch {
            val p = app.store.data.first()
            _saved.value = SavedHost.decode(p[HOSTS])
            _wifiChosen.value = p[WIFI_CHOSEN] ?: false
            Selection.decode(p[SELECTION])?.let { apply(it) }
        }
        // Wi-Fi power save delayed 3 of 12 sparse taps by 140-530 ms (Nothing Phone 2, Android 16);
        // hold a low-latency Wi-Fi lock only while a helper host is active and the app is on screen.
        scope.launch {
            combine(_active, fgFlow) { t, fg -> fg && t is WsTransport }.distinctUntilChanged().collect { on ->
                lowLatency.run { if (on) runCatching { acquire() } else if (isHeld) release() }
            }
        }
        runCatching {
            app.getSystemService(ConnectivityManager::class.java)?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { (_active.value as? WsTransport)?.kick() }
            })
        }
        // Every network including VPNs: keeps tailnetUp / wifi current for the Tailscale prompt.
        refreshLinks()
        runCatching {
            val req = NetworkRequest.Builder().removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build()
            app.getSystemService(ConnectivityManager::class.java)?.registerNetworkCallback(req, object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = refreshLinks()
                override fun onLost(network: Network) = refreshLinks()
                override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) = refreshLinks()
                override fun onCapabilitiesChanged(network: Network, nc: NetworkCapabilities) = refreshLinks()
            })
        }
    }

    private val _tailnetUp = MutableStateFlow(false)
    /** Heuristic (see [tailnetUp]): a VPN holding a Tailscale address is up. */
    val tailnetUpFlow: StateFlow<Boolean> = _tailnetUp.asStateFlow()
    private var wifiNow = false
    private val _onWifi = MutableStateFlow(false)
    /** On a non-VPN Wi-Fi network right now (LAN routes plausible). */
    val onWifiFlow: StateFlow<Boolean> = _onWifi.asStateFlow()

    private fun refreshLinks() {
        val links = Tailscale.links(app)
        wifiNow = onWifi(links)
        _onWifi.value = wifiNow
        val up = tailnetUp(links)
        _tailnetUp.value = up
        if (up) tsPrompt.value?.let { p -> tsPrompt.value = null; p.then?.let { scope.launch(Dispatchers.Main) { it() } } }
    }

    /** "Tailscale is off" prompt: shown for [host]; [then] runs once the VPN is up. */
    class TsPrompt(val host: String, val then: (() -> Unit)? = null)
    val tsPrompt = MutableStateFlow<TsPrompt?>(null)

    /** Shows the prompt and returns true when [routes] need Tailscale and it is off. */
    fun promptForTailscale(host: String, routes: List<Route>, seenOnLan: Boolean, then: (() -> Unit)? = null): Boolean {
        refreshLinks()
        if (!needsTailscalePrompt(routes, _tailnetUp.value, wifiNow, seenOnLan)) return false
        tsPrompt.value = TsPrompt(host, then)
        return true
    }

    /** Foreground only: the helper socket is closed 30 s after the app leaves the screen. */
    var foreground = false
        set(v) {
            field = v
            fgFlow.value = v
            stopJob?.cancel()
            val ws = _active.value as? WsTransport ?: return
            if (v) { ws.start(); ws.kick() } else stopJob = scope.launch { delay(30_000); ws.stop() }
        }

    fun select(sel: Selection) {
        scope.launch { app.store.edit { it[SELECTION] = sel.encode() } }
        apply(sel)
    }

    private fun apply(sel: Selection) {
        _selection.value = sel
        val cur = _active.value
        when (sel) {
            is Selection.Bt -> {
                (cur as? WsTransport)?.stop()
                _active.value = bt
            }
            is Selection.Helper -> {
                if (cur is WsTransport && cur.hostId == sel.hostId) { if (foreground) cur.start(); return }
                (cur as? WsTransport)?.stop()
                Hid.deselect()
                val ws = WsTransport(http, scope, sel.hostId, { targetFor(sel.hostId) }, ::onReady)
                _active.value = ws
                if (foreground) ws.start()
            }
        }
    }

    private fun targetFor(hostId: String): WsTarget? {
        val h = _saved.value.firstOrNull { it.hostId == hostId } ?: return null
        val sign = Vault.signer(h) ?: return null
        val fresh = discovery.found.value.firstOrNull { it.hostId == hostId }
        return WsTarget(h.candidates(fresh?.address), fresh?.port ?: h.port, h.deviceId, sign)
    }

    private fun onReady(address: String, hello: Msg.Hello) {
        val id = (_active.value as? WsTransport)?.hostId ?: return
        update(id) { h -> h.withAddress(address).copy(name = hello.host.ifBlank { h.name }, local = hello.local ?: h.local) }
    }

    private fun update(hostId: String, f: (SavedHost) -> SavedHost) {
        val list = _saved.value.map { if (it.hostId == hostId) f(it) else it }
        if (list == _saved.value) return
        _saved.value = list
        scope.launch { app.store.edit { it[HOSTS] = SavedHost.encode(list) } }
    }

    private suspend fun persist(list: List<SavedHost>) {
        _saved.value = list
        app.store.edit { it[HOSTS] = SavedHost.encode(list) }
    }

    fun chooseWifi() {
        _wifiChosen.value = true
        scope.launch { app.store.edit { it[WIFI_CHOSEN] = true } }
    }

    /** Opens [address] and returns its hello: checks that a helper answers before pairing / saving. */
    suspend fun probe(address: String, port: Int): Msg.Hello {
        val s = WsSession.open(http, address, port)
        s.close()
        return s.hello
    }

    /**
     * Pairs with the helper at [address] using [code], stores the token in the Keystore and the host
     * in DataStore, then selects it. [tailnet] (from a pairing link) is remembered as a route.
     */
    suspend fun pair(address: String, port: Int, code: String, tailnet: String? = null): SavedHost {
        val p = pairWith(http, address, port, code, deviceName())
        val wrapped = Vault.store(p.hostId, p.token)
        p.token.fill(0)
        val old = _saved.value.firstOrNull { it.hostId == p.hostId }
        var h = SavedHost(
            p.hostId, p.hello.host.ifBlank { address }, p.deviceId, port = port, wrapped = wrapped,
            local = p.hello.local, tailnet = old?.tailnet, btAddress = old?.btAddress,
        ).withAddress(address)
        if (tailnet != null) h = h.withAddress(tailnet)
        persist(_saved.value.filterNot { it.hostId == p.hostId } + h)
        chooseWifi()
        select(Selection.Helper(h.hostId))
        return h
    }

    /** Adds a typed address (e.g. a tailnet name) to an already paired host and selects it. */
    fun addAddress(hostId: String, address: String) {
        update(hostId) { it.withAddress(address) }
        select(Selection.Helper(hostId))
        (_active.value as? WsTransport)?.kick()
    }

    fun linkBt(hostId: String, btAddress: String) = update(hostId) { it.copy(btAddress = btAddress) }

    fun forget(hostId: String) {
        Vault.forget(hostId)
        scope.launch { persist(_saved.value.filterNot { it.hostId == hostId }) }
        if (_selection.value == Selection.Helper(hostId)) {
            (_active.value as? WsTransport)?.stop()
            _active.value = bt
            _selection.value = null
            scope.launch { app.store.edit { it.remove(SELECTION) } }
        }
    }


    private fun deviceName(): String =
        Settings.Global.getString(app.contentResolver, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() } ?: Build.MODEL
}
