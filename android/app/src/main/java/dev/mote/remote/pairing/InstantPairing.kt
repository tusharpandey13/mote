package dev.mote.remote.labs.pairing

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.mote.remote.data.Prefs
import dev.mote.remote.data.typed
import dev.mote.remote.hid.Hid
import dev.mote.remote.hid.Host
import dev.mote.remote.labs.Labs
import dev.mote.remote.net.Found
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

/** A Mac the sheet can offer: from BLE (with its key fingerprint) and/or Bonjour (address only). */
data class Candidate(val hostId: String, val name: String, val info: BleInfo?, val bonjour: Found?) {
    val fp: String? get() = info?.fp
    val port: Int get() = info?.port ?: bonjour?.port ?: Proto.DEFAULT_PORT
}

/** Pure merge of BLE and Bonjour results: one entry per hostId, BLE first (it carries the fingerprint). */
fun candidates(ble: List<NearbyMac>, bonjour: List<Found>): List<Candidate> {
    val byId = LinkedHashMap<String, Candidate>()
    for (n in ble) byId[n.info.hostId] = Candidate(n.info.hostId, n.info.name, n.info, null)
    for (f in bonjour) {
        val id = f.hostId ?: continue
        byId[id] = byId[id]?.copy(bonjour = f) ?: Candidate(id, f.service, null, f)
    }
    return byId.values.toList()
}

/**
 * Instant pairing (Labs): BLE + Bonjour discovery, the key handshake, trust records, silent
 * reconnects (LAN, then tailnet) and the Bluetooth HID fallback. Owns no transport: a trusted Mac
 * becomes a normal [SavedHost] whose [WsTarget] authenticates with [labsHandshake].
 */
@SuppressLint("StaticFieldLeak") // application context only
object InstantPairing {
    private val Context.store by preferencesDataStore("labs_pairing", corruptionHandler = Prefs.corruptionHandler)
    private val TRUST = stringPreferencesKey("trust")
    private lateinit var app: Context

    private val _records = MutableStateFlow<Map<String, TrustRecord>>(emptyMap())
    val records: StateFlow<Map<String, TrustRecord>> = _records.asStateFlow()
    private val _enabled = MutableStateFlow(false)
    /** Settings › Labs › Instant pairing. */
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()
    val ble by lazy { BleScan(app) }

    /** hostId we fell back to Bluetooth HID from; probed every 30 s to switch back. */
    @Volatile private var fallbackFrom: String? = null

    /** Called from [Net.init] before the saved selection is applied (trusted hosts need their records). */
    suspend fun load(c: Context) {
        if (::app.isInitialized) return
        app = c.applicationContext
        Net.scope.launch { Labs.flow(app, Labs.INSTANT_PAIRING).collect { _enabled.value = it } }
        _records.value = TrustRecord.decode(app.store.data.first().typed(TRUST))
        Net.scope.launch { watchForFallback() }
        Net.scope.launch { probeBack() }
    }

    private suspend fun save(r: TrustRecord) {
        val m = _records.value + (r.hostId to r)
        _records.value = m
        app.store.edit { it[TRUST] = TrustRecord.encode(m.values) }
    }

    fun deviceName(): String =
        Settings.Global.getString(app.contentResolver, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() } ?: Build.MODEL

    /**
     * First connect to [c]: tries its paths in order, runs the handshake (the Mac may ask its user;
     * [onState] reports `Pending`), stores the trust and selects the Mac. Throws [HelperError].
     */
    suspend fun connect(c: Candidate, onState: (PairState) -> Unit = {}): PairState.Done = withContext(Dispatchers.IO) {
        val saved = Net.saved.value.firstOrNull { it.hostId == c.hostId }
        val paths = helperCandidates(selectPaths(c.info, saved, c.bonjour?.address))
        if (paths.isEmpty()) throw HelperError("noroute", "No address for ${c.name}. Is the Mac on this Wi-Fi or Tailscale?")
        val me = Identity.get(app)
        var last: Exception = HelperError("noroute", "Couldn't reach ${c.name}")
        for (addr in paths) {
            val s = try { WsSession.open(Net.http, addr, c.port) } catch (e: CancellationException) { throw e } catch (e: Exception) { last = e; continue }
            try {
                if (s.hello.hostId != c.hostId) { last = HelperError("helper-key", "Another Mac answered at $addr"); continue }
                val pin = _records.value[c.hostId]?.fp ?: c.fp
                val done = labsHandshake(s, me, deviceName(), pin, allowPrompt = true, onState = onState)
                save(TrustRecord(c.hostId, done.helper.pub, done.helper.fp, done.via, c.info))
                withContext(Dispatchers.Main) { adopt(c, s, addr, done) }
                return@withContext done
            } finally { s.close() }
        }
        throw last
    }

    private fun adopt(c: Candidate, s: WsSession, addr: String, done: PairState.Done) {
        val old = Net.saved.value.firstOrNull { it.hostId == c.hostId }
        var h = (old ?: SavedHost(c.hostId, s.hello.host.ifBlank { c.name }, done.authed.id, port = c.port))
            .copy(deviceId = done.authed.id, wrapped = null, local = s.hello.local ?: old?.local)
            .withAddress(addr)
        c.info?.tailnetDns?.let { h = h.withAddress(it) }
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

    /** Silent re-auth: same helper key required, and no prompt (a prompt means the Mac dropped this phone). */
    private suspend fun reconnect(s: WsSession, r: TrustRecord) = try {
        labsHandshake(s, Identity.get(app), deviceName(), r.fp, allowPrompt = false).authed
    } catch (e: HelperError) {
        // WsTransport stops on `auth`: wrong key, revoked or denied need the user, not a retry loop.
        throw if (e.code in setOf("helper-key", "revoked", "denied", "unauth")) HelperError("auth", e.message ?: e.code) else e
    }

    /** Helper unreachable for 15 s and the Mac is linked to a Bluetooth HID host: use Bluetooth. */
    private suspend fun watchForFallback() {
        Net.wsStatus.collectLatest { st ->
            if (st is WsStatus.Ready || st is WsStatus.Idle || !enabled.value) return@collectLatest
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

    const val FALLBACK_MS = 15_000L
    const val PROBE_MS = 30_000L
}
