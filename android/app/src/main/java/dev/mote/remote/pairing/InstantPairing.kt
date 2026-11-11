package dev.mote.remote.pairing

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.mote.remote.data.Prefs
import dev.mote.remote.data.typed
import dev.mote.remote.hid.Hid
import dev.mote.remote.hid.Host
import dev.mote.remote.net.Route
import dev.mote.remote.net.routeOf
import kotlinx.coroutines.withTimeoutOrNull
import dev.mote.remote.net.HelperError
import dev.mote.remote.net.Msg
import dev.mote.remote.net.Net
import dev.mote.remote.net.Proto
import dev.mote.remote.net.SavedHost
import dev.mote.remote.net.Selection
import dev.mote.remote.net.WsSession
import dev.mote.remote.net.WsStatus
import dev.mote.remote.net.WsTarget
import dev.mote.remote.net.WsTransport
import dev.mote.remote.service.HidService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** What the phone remembers about a Mac it trusts through Instant pairing. */
data class TrustRecord(val hostId: String, val helperPub: String, val fp: String, val via: String, val info: BleInfo? = null) {
    fun toJson(): JSONObject = JSONObject().put("hostId", hostId).put("pub", helperPub).put("fp", fp).put("via", via)
        .putOpt("info", info?.toJson())

    companion object {
        fun encode(list: Collection<TrustRecord>): String = JSONArray(list.map { it.toJson() }).toString()
        fun decode(json: String?): Map<String, TrustRecord> = runCatching {
            val a = JSONArray(json ?: return emptyMap())
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                TrustRecord(o.getString("hostId"), o.getString("pub"), o.getString("fp"), o.optString("via"), o.optString("info").ifEmpty { null }?.let(BleInfo::parse))
            }.associateBy { it.hostId }
        }.getOrDefault(emptyMap())
    }
}

/**
 * Instant pairing (docs/pairing.md), the default way to add a Mac: discovery ([finder]), the key
 * handshake with a BLE proximity token, trust records, silent reconnects (LAN, then tailnet) and
 * the Bluetooth HID fallback. Owns no transport: a trusted Mac becomes a normal [SavedHost] whose
 * [WsTarget] authenticates with [labsHandshake].
 */
@SuppressLint("StaticFieldLeak") // application context only
object InstantPairing {
    private val Context.store by preferencesDataStore("labs_pairing", corruptionHandler = Prefs.corruptionHandler)
    private val TRUST = stringPreferencesKey("trust")
    private lateinit var app: Context

    private val _records = MutableStateFlow<Map<String, TrustRecord>>(emptyMap())
    val records: StateFlow<Map<String, TrustRecord>> = _records.asStateFlow()
    val ble by lazy { BleScan(app) }
    val appContext: Context get() = app
    val finder by lazy { Finder(app, Net.scope) }

    /** hostId we fell back to Bluetooth HID from; probed every 30 s to switch back. */
    @Volatile private var fallbackFrom: String? = null

    /** Called from [Net.init] before the saved selection is applied (trusted hosts need their records). */
    suspend fun load(c: Context) {
        if (::app.isInitialized) return
        app = c.applicationContext
        _records.value = TrustRecord.decode(app.store.data.first().typed(TRUST))
        Net.scope.launch { watchForFallback() }
        Net.scope.launch { probeBack() }
        Net.scope.launch { rediscover() }
    }

    private suspend fun save(r: TrustRecord) {
        val m = _records.value + (r.hostId to r)
        _records.value = m
        app.store.edit { it[TRUST] = TrustRecord.encode(m.values) }
    }

    fun deviceName(): String =
        Settings.Global.getString(app.contentResolver, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() } ?: Build.MODEL

    /**
     * First connect to [c]: tries its addresses in order (LAN, then tailnet) and runs the handshake
     * with a proximity token read over BLE when Bluetooth is usable. The Mac trusts a new key
     * without anyone touching it when the token is fresh or both are on the same Tailscale account
     * (docs/pairing.md section 4). Stores the trust and selects the Mac. Throws [HelperError]:
     * `far` with Bluetooth unusable means the caller should ask for it and try again.
     */
    suspend fun connect(c: FoundMac, onState: (PairState) -> Unit = {}): PairState.Done = withContext(Dispatchers.IO) {
        val saved = Net.saved.value.firstOrNull { it.hostId == c.hostId }
        val paths = orderPaths(c.addresses + helperCandidates(selectPaths(c.info, saved)))
        if (paths.isEmpty()) throw HelperError("noroute", "No address for ${c.name}. Is the Mac on this Wi-Fi or Tailscale?")
        Log.i(TAG, "connect ${c.hostId} paths=$paths port=${c.port} ble=${ble.ready()}")
        val me = Identity.get(app)
        var prox: String? = ble.proximity(c.hostId)
        Log.i(TAG, "proximity token: ${if (prox != null) "read" else "none"}")
        var staleRetry = true
        var last: Exception = HelperError("noroute", "Couldn't reach ${c.name}")
        var i = 0
        while (i < paths.size) {
            val addr = paths[i]
            val s = try { WsSession.open(Net.http, addr, c.port) } catch (e: CancellationException) { throw e } catch (e: Exception) {
                Log.w(TAG, "open $addr:${c.port} failed: $e"); last = worse(last, e); i++; continue
            }
            try {
                if (s.hello.hostId != c.hostId) { last = worse(last, HelperError("helper-key", "Another Mac answered at $addr")); i++; continue }
                val pin = _records.value[c.hostId]?.fp ?: c.fp
                if (prox == null && dev.mote.remote.hid.TestSeam.fakeProximity) prox = fakeProximity(s)
                val token = prox.also { prox = null } // single-use on the Mac
                val done = try {
                    labsHandshake(s, me, deviceName(), pin, allowPrompt = true, prox = token, onState = onState)
                } catch (e: HelperError) {
                    Log.w(TAG, "handshake at $addr: ${e.code} ${e.message}")
                    last = worse(last, e)
                    when {
                        e.code == "stale" && staleRetry -> { staleRetry = false; prox = ble.proximity(c.hostId, maxAgeMs = 0); continue }
                        e.code == "far" -> { i++; continue } // a tailnet path may still be trusted (same owner)
                        else -> throw e
                    }
                }
                // Ephemeral Mac (Keychain unavailable): this key lives one helper process. Never replace a stored pin with it.
                val rec = TrustRecord(c.hostId, done.helper.pub, done.helper.fp, done.via, c.info)
                if (!done.helper.ephemeral) save(rec)
                else if (_records.value[c.hostId] == null) _records.value = _records.value + (c.hostId to rec) // this session only, not persisted
                if (done.helper.ephemeral) HostKeyChange.reportEphemeral(c.hostId, c.name)
                withContext(Dispatchers.Main) { adopt(c, s, addr, done) }
                return@withContext done
            } finally { s.close() }
        }
        throw last
    }

    private fun adopt(c: FoundMac, s: WsSession, addr: String, done: PairState.Done) {
        val old = Net.saved.value.firstOrNull { it.hostId == c.hostId }
        var h = (old ?: SavedHost(c.hostId, s.hello.host.ifBlank { c.name }, done.authed.id, port = c.port))
            .copy(deviceId = done.authed.id, wrapped = null, local = s.hello.local ?: old?.local)
            .withAddress(addr)
        c.info?.tailnetDns?.let { h = h.withAddress(it) }
        if (h.btAddress == null) dev.mote.remote.hid.Bt.snapshot(app).hosts.firstOrNull { it.name.equals(h.name, ignoreCase = true) }
            ?.let { h = h.copy(btAddress = it.address) } // enables the Bluetooth HID fallback
        Net.adoptTrusted(h)
    }

    /** [Net.targetFor] hook: a WsTarget for a Mac trusted here, or null (not ours: HMAC token path). */
    fun target(h: SavedHost, fresh: String?): WsTarget? {
        if (!::app.isInitialized) return null
        val r = _records.value[h.hostId] ?: return null
        val candidates = helperCandidates(selectPaths(r.info, h, fresh))
        return object : WsTarget(candidates, h.port, h.deviceId, { "" }) {
            override val authenticate: suspend (WsSession) -> Msg.Authed = { s -> reconnect(s, r) }
        }
    }

    /**
     * U12: the user agreed to trust the Mac's new key. Drops the pinned key and runs the first-connect handshake again,
     * which re-reads the BLE proximity token; if that fails the old record is put back and the error is thrown.
     */
    suspend fun trustNewKey(hostId: String) {
        val old = _records.value[hostId] ?: throw HelperError("noroute", "This Mac isn't trusted here yet.")
        val h = Net.saved.value.firstOrNull { it.hostId == hostId }
        val found = FoundMac(hostId, h?.name ?: "this Mac", null, emptyList(), h?.port ?: dev.mote.remote.net.Proto.DEFAULT_PORT, setOf(Source.KNOWN))
        _records.value = _records.value - hostId
        try {
            connect(found)
        } catch (e: Exception) {
            _records.value = _records.value + (hostId to old) // keep the old pin: nothing changed
            throw e
        }
        Net.active.value.let { (it as? WsTransport)?.kick() }
    }

    /** Silent re-auth: same helper key required, and no prompt (a prompt means the Mac dropped this phone). */
    private suspend fun reconnect(s: WsSession, r: TrustRecord) = try {
        labsHandshake(s, Identity.get(app), deviceName(), r.fp, allowPrompt = false, onState = { st ->
            if (st is PairState.Verified && st.helper.ephemeral) HostKeyChange.reportEphemeral(r.hostId, Net.saved.value.firstOrNull { it.hostId == r.hostId }?.name ?: "This Mac")
        }).authed
    } catch (e: HelperError) {
        if (HostKeyChange.isKeyMismatch(e.code, e.message)) {
            HostKeyChange.report(r.hostId, Net.saved.value.firstOrNull { it.hostId == r.hostId }?.name ?: "This Mac")
        }
        // WsTransport stops on `auth`: wrong key or revoked need the user. Denials (far, blocked, ...) pass through: it shows a card and backs off.
        throw if (!e.denial && e.code in setOf("helper-key", "revoked", "unauth")) HelperError("auth", e.message ?: e.code) else e
    }

    /** Helper unreachable for 15 s and the Mac is linked to a Bluetooth HID host: use Bluetooth. */
    private suspend fun watchForFallback() {
        Net.wsStatus.collectLatest { st ->
            if (!shouldRediscover(st)) return@collectLatest
            val ws = Net.active.value as? WsTransport ?: return@collectLatest
            if (_records.value[ws.hostId] == null) return@collectLatest
            val h = Net.saved.value.firstOrNull { it.hostId == ws.hostId } ?: return@collectLatest
            val bt = h.btAddress ?: return@collectLatest
            delay(FALLBACK_MS)
            if (!Net.foreground || Net.active.value !== ws) return@collectLatest
            fallbackFrom = h.hostId
            withContext(Dispatchers.Main) {
                Net.select(Selection.Bt(bt))
                HidService.start(app)
                Hid.select(Host(bt, h.name))
            }
        }
    }

    /** While on the fallback, check the helper every 30 s and switch back when it answers. */
    private suspend fun probeBack() {
        while (true) {
            delay(PROBE_MS)
            val id = fallbackFrom ?: continue
            val sel = Net.selection.value
            val h = Net.saved.value.firstOrNull { it.hostId == id }
            if (sel !is Selection.Bt || h == null || sel.address != h.btAddress) { fallbackFrom = null; continue }
            if (!Net.foreground) continue
            val t = target(h, Net.discovery.found.value.firstOrNull { it.hostId == id }?.address) ?: continue
            val back = t.candidates.any { addr -> runCatching { Net.probe(addr, t.port).hostId == id }.getOrDefault(false) }
            if (back) { fallbackFrom = null; withContext(Dispatchers.Main) { Net.select(Selection.Helper(id)) } }
        }
    }

    /**
     * The Mac's LAN IP changed (DHCP) and Bonjour is blocked: when a trusted Mac doesn't answer for
     * [REDISCOVER_MS], sweep the LAN once per outage and add the address it answers at.
     */
    /** B6: a denial is not a lost address (nor a reason for the Bluetooth fallback). `addAddress` kicks the transport past its 30 s denied backoff, so sweeping on Denied would retry silently every ~4 s. */
    fun shouldRediscover(st: WsStatus) = st !is WsStatus.Ready && st !is WsStatus.Idle && st !is WsStatus.Denied

    private suspend fun rediscover() {
        Net.wsStatus.collectLatest { st ->
            if (!shouldRediscover(st)) return@collectLatest
            val ws = Net.active.value as? WsTransport ?: return@collectLatest
            if (_records.value[ws.hostId] == null) return@collectLatest
            delay(REDISCOVER_MS)
            if (!Net.foreground || Net.active.value !== ws) return@collectLatest
            val w = wifiAddr(app) ?: return@collectLatest
            val port = Net.saved.value.firstOrNull { it.hostId == ws.hostId }?.port ?: Proto.DEFAULT_PORT
            val sockets = WifiSocketFactory(w.network)
            val http = Net.http.newBuilder().socketFactory(sockets).build()
            val hit = withTimeoutOrNull(Finder.RUN_MS) {
                lanSweep(sweepHosts(w.ip, w.prefix), port) { h, p, t -> tcpOpen(h, p, t, sockets) }
                    .firstOrNull { addr -> probeHelper(http, addr, port)?.hostId == ws.hostId }
            }
            if (hit != null) withContext(Dispatchers.Main) { Net.addAddress(ws.hostId, hit) }
        }
    }

    /** Tests only: the token a `--fake-proximity` dry-run helper hands out in place of the BLE read. */
    private suspend fun fakeProximity(s: WsSession): String? {
        s.send(org.json.JSONObject().put("t", "labs.pair.prox").toString())
        return withTimeoutOrNull(3000) {
            var v: String? = null
            while (v == null) {
                val m = s.inbox.receive()
                if (m is Msg.Err) break
                if (m is Msg.Other && "labs.pair.prox" in m.raw) v = PairProto.proxOrNull(org.json.JSONObject(m.raw).optString("v").toByteArray()) ?: break
            }
            v
        }
    }

    /** LAN addresses first, then tailnet (stable), without duplicates. */
    fun orderPaths(addrs: List<String>): List<String> {
        val d = addrs.distinctBy { it.lowercase() }
        return d.filter { routeOf(it) != Route.TAILNET } + d.filter { routeOf(it) == Route.TAILNET }
    }

    private const val TAG = "MotePair"
    const val FALLBACK_MS = 15_000L
    const val REDISCOVER_MS = 4_000L
    const val PROBE_MS = 30_000L
}
