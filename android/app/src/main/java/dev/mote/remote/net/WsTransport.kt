package dev.mote.remote.net

import dev.mote.remote.diag.Events
import dev.mote.remote.diag.HelperDiag
import dev.mote.remote.hid.Hid
import dev.mote.remote.hid.Key
import dev.mote.remote.labs.LabsBus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.random.Random

/** Where and how to connect, re-read before every attempt (addresses change as NSD reports them). */
open class WsTarget(val candidates: List<String>, val port: Int, val deviceId: String, val sign: (String) -> String) {
    /** Replaces the HMAC `auth` when non-null (Labs instant pairing: key handshake). */
    open val authenticate: (suspend (WsSession) -> Msg.Authed)? get() = null
}

sealed interface WsStatus {
    data object Idle : WsStatus
    data object Connecting : WsStatus
    data class Ready(val address: String, val paused: Boolean, val ax: Boolean) : WsStatus
    /** Needs the user: re-pair (`auth`, `revoked`), update the helper (`version`), or no credential. */
    data class Failed(val code: String, val msg: String) : WsStatus
    /** The Mac answered `labs.pair.denied` / `err far...` with [reason]; the link retries slowly, the user can act (B6). */
    data class Denied(val reason: String) : WsStatus
    /** The Mac is reading its Keychain (`keychain-reading`); nothing is wrong, retrying after `retryAfter`. */
    data object Reading : WsStatus
}

/**
 * Mote Helper over WebSocket (protocol v1). Connects to the first answering candidate, does the
 * HMAC challenge, then keeps the link while [start]ed: OkHttp pings every 20 s and a lost socket
 * reconnects with backoff (0.5 s to 8 s, jittered). Pointer moves and scrolls are summed and sent
 * at most every 16 ms (~60 Hz); keys and clicks flush pending motion first so ordering holds.
 */
class WsTransport(
    private val http: OkHttpClient,
    private val scope: CoroutineScope,
    val hostId: String,
    private val target: () -> WsTarget?,
    private val onReady: (address: String, hello: Msg.Hello) -> Unit = { _, _ -> },
) : Transport {
    override val caps = Caps.HELPER

    private val _status = MutableStateFlow<WsStatus>(WsStatus.Idle)
    val status: StateFlow<WsStatus> = _status.asStateFlow()
    override val linked: StateFlow<Boolean> = _status.map { it is WsStatus.Ready }.stateIn(scope, SharingStarted.Eagerly, false)

    private val _mods = MutableStateFlow(Hid.Mods())
    override val mods: StateFlow<Hid.Mods> = _mods.asStateFlow()

    private val _spaces = MutableStateFlow<SpacesState?>(null)
    /** Spaces state with thumbnails; null until the helper answers `spaces.sub` (pre-1.3 helpers never do). */
    override val spaces: StateFlow<SpacesState?> = _spaces.asStateFlow()

    @Volatile private var session: WsSession? = null
    private var loop: Job? = null
    private val wake = Channel<Unit>(Channel.CONFLATED)

    // ---- connection ----

    /** Starts (or keeps) the connect loop. Idempotent. */
    @Synchronized fun start() {
        if (loop?.isActive == true) return
        loop = scope.launch { run() }
    }

    /** Closes the socket and stops reconnecting. */
    @Synchronized fun stop() {
        loop?.cancel(); loop = null
        session?.close(); session = null
        repeats.values.forEach { it.cancel() }; repeats.clear()
        _spaces.value = null
        _status.value = WsStatus.Idle
    }

    /** Skip the current backoff wait (network came back, app came to the foreground). */
    fun kick() { wake.trySend(Unit) }

    private suspend fun run() {
        var backoff = MIN_BACKOFF
        var deniedBackoff = DENIED_MIN_BACKOFF
        var keepStatus = false
        while (true) {
            val t = target() ?: run {
                Events.log("transport", "connect.failed", "host" to hostId.take(12), "code" to "unpaired")
                _status.value = WsStatus.Failed("unpaired", "Pair this Mac again"); return
            }
            if (_status.value.let { it !is WsStatus.Ready && it !is WsStatus.Denied && it !is WsStatus.Reading }) _status.value = WsStatus.Connecting
            var s: WsSession? = null
            for (addr in t.candidates) {
                Events.log("transport", "connect.try", "address" to addr, "port" to t.port, "candidates" to t.candidates.size)
                s = runCatching { WsSession.open(http, addr, t.port) }.getOrElse { e ->
                    if (e is CancellationException) throw e
                    Events.log("transport", "connect.fail", "address" to addr, "reason" to (e.message ?: e.javaClass.simpleName).take(80))
                    if (e is HelperError && e.code == "version") {
                        Events.log("transport", "connect.failed", "code" to e.code)
                        _status.value = WsStatus.Failed(e.code, e.message ?: ""); return
                    }
                    null
                }
                if (s != null) Events.log("transport", "connect.hello", "address" to addr, "helperProto" to s.hello.v)
                // A different Mac now answers at this address (DHCP reuse, another helper): never
                // auth against it, that would burn its rate limit and look like a revoked pairing.
                if (s != null && !isSameHost(s.hello)) {
                    Events.log("transport", "connect.mismatch", "address" to addr)
                    s.close(); s = null
                }
                if (s != null) break
            }
            if (s != null) {
                try {
                    Events.log("transport", "connect.auth", "address" to s.address, "instant" to (t.authenticate != null))
                    val a = t.authenticate?.invoke(s) ?: s.auth(t.deviceId, t.sign)
                    session = s
                    backoff = MIN_BACKOFF
                    deniedBackoff = DENIED_MIN_BACKOFF
                    _status.value = WsStatus.Ready(s.address, a.paused, a.ax)
                    Events.log("transport", "connect.ready", "address" to s.address, "paused" to a.paused, "ax" to a.ax)
                    onReady(s.address, s.hello)
                    s.send(Proto.spacesSub())
                    for (m in s.inbox) when (m) {
                        is Msg.State -> {
                            Events.log("session", "state", "paused" to m.paused, "ax" to m.ax)
                            _status.value = WsStatus.Ready(s.address, m.paused, m.ax)
                        }
                        is Msg.Spaces -> onSpaces(s, m)
                        is Msg.SpacesThumb -> onThumb(m)
                        // `spaces*` errors (old helper: `unknown`; `notfound`, `unsupported`) leave the link up.
                        is Msg.Err -> if (m.code == "revoked") throw HelperError(m.code, m.msg)
                            else if (m.ref == "diag" || m.ref == "diag.subscribe") HelperDiag.onErr(m.code, m.ref)
                            else if (LabsBus.isLabs(m.ref) || m.code == "disabled") LabsBus.deliver(org.json.JSONObject().put("t", "err").put("code", m.code).put("msg", m.msg).put("ref", m.ref).toString())
                        is Msg.Other -> if (LabsBus.isLabs(m.t)) {
                            Events.coalesced("push.${m.t}", "labs", "push", null, mapOf("type" to m.t, "bytes" to m.raw.length))
                            LabsBus.deliver(m.raw)
                        } else if (m.t == "diag" || m.t == "diag.events") HelperDiag.deliver(m.raw)
                        else -> Unit
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: HelperError) {
                    Events.log("transport", "connect.failed", "code" to e.code, "address" to s.address)
                    when (e.code) {
                        "auth", "revoked", "keychain-unavailable" -> { session = null; s.close(); _status.value = WsStatus.Failed(e.code, e.message ?: ""); return }
                        "rate" -> backoff = (e.retryAfter ?: 60) * 1000L
                        KEYCHAIN_READING -> { _status.value = WsStatus.Reading; backoff = (e.retryAfter ?: 2) * 1000L; keepStatus = true }
                        in DENIAL_CODES -> {
                            Events.log("pairing", "denied", "reason" to e.code)
                            _status.value = WsStatus.Denied(e.code)
                            backoff = deniedBackoff; deniedBackoff = (deniedBackoff * 2).coerceAtMost(DENIED_MAX_BACKOFF)
                            keepStatus = true
                            wake.tryReceive() // a kick queued before the denial (addAddress) must not skip the backoff
                        }
                    }
                } catch (e: Exception) {
                    // socket dropped: fall through to backoff
                    Events.log("transport", "connect.dropped", "address" to s.address, "reason" to (e.message ?: e.javaClass.simpleName).take(80))
                } finally {
                    session = null
                    HelperDiag.reset()
                    _spaces.value = null // thumbnail versions are only meaningful within one helper run
                    s.close()
                }
            }
            if (!keepStatus) _status.value = WsStatus.Connecting
            keepStatus = false
            Events.log("transport", "connect.backoff", "ms" to backoff, "candidates" to t.candidates.size)
            withTimeoutOrNull(backoff + Random.nextLong(backoff / 4 + 1)) { wake.receive() }
            backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF)
        }
    }

    // Thumbnails are cached per display ("" = a 1.3 helper, which only knows the main display), so
    // switching the active display back and forth shows cached pictures at once. Run loop only.
    /** Thumbnail cache: display -> 1-based index -> newest thumbnail. */
    private val thumbCache = HashMap<String, Map<Int, SpaceThumb>>()
    /** Thumbnail version last requested per (display, desktop), so repeated `spaces` don't re-request. */
    private val requested = HashMap<Pair<String, Int>, Int>()
    /** Latest helper versions per display, from the last `spaces`. */
    private var versionsByDisplay: Map<String, List<Int>> = emptyMap()

    /**
     * New `spaces` state: fetch every thumbnail of the active display whose version differs from the
     * cached one. A stale thumbnail stays visible until its replacement arrives; displays that went
     * away and desktops past a display's count are dropped.
     */
    private fun onSpaces(s: WsSession, m: Msg.Spaces) {
        if (_spaces.value == null) { requested.clear(); thumbCache.clear() } // new session
        val key = m.display ?: ""
        versionsByDisplay = if (m.displays.isEmpty()) mapOf(key to m.thumbs) else m.displays.associate { it.id to it.thumbs } + (key to m.thumbs)
        thumbCache.keys.retainAll(versionsByDisplay.keys)
        for ((d, t) in thumbCache.entries.toList()) thumbCache[d] = t.filterKeys { it <= versionsByDisplay.getValue(d).size }
        val kept = thumbCache[key].orEmpty()
        m.thumbs.forEachIndexed { k, v ->
            val i = k + 1
            if (v > 0 && kept[i]?.version != v && requested[key to i] != v) { requested[key to i] = v; s.send(Proto.spacesThumb(i, m.display)) }
        }
        _spaces.value = SpacesState(
            m.count, m.current, m.capture, m.thumbs, kept,
            m.displays.map { DisplaySpaces(it.id, it.name, it.count, it.current) }, m.display, m.shared,
        )
    }

    /** Stores a thumbnail if it is still the latest version for its desktop; shows it if its display is active. */
    private fun onThumb(m: Msg.SpacesThumb) {
        val st = _spaces.value ?: return
        val key = m.display ?: st.activeDisplay ?: ""
        if (versionsByDisplay[key]?.getOrNull(m.i - 1) != m.v) return
        val t = thumbCache[key].orEmpty() + (m.i to SpaceThumb(m.i, m.v, m.jpeg))
        thumbCache[key] = t
        if (key == (st.activeDisplay ?: "")) _spaces.value = st.copy(thumbs = t)
    }

    /** Pre-1.1 helpers send no hostId (saved as "addr:…"), so only a present, different id is a mismatch. */
    private fun isSameHost(h: Msg.Hello) = h.hostId == null || hostId.startsWith("addr:") || h.hostId == hostId

    // ---- send pipe ----

    private val sendLock = Any()
    private var mx = 0f; private var my = 0f; private var sx = 0f; private var sy = 0f
    private val flushScheduled = AtomicBoolean(false)

    /** Sends [json] after any pending motion, so a click lands where the pointer was moved to. */
    private fun sendNow(json: String) {
        synchronized(sendLock) {
            flushMotionLocked()
            val s = session
            if (s == null) Events.coalesced("input.dropped", "input", "dropped", null, mapOf("reason" to "no session"))
            s?.send(json)
        }
    }

    private fun scheduleFlush() {
        if (!flushScheduled.compareAndSet(false, true)) return
        scope.launch {
            delay(FRAME_MS)
            flushScheduled.set(false)
            val more = synchronized(sendLock) { flushMotionLocked() }
            if (more) scheduleFlush()
        }
    }

    /** Sends whole-point sums, keeps fractions. Returns true if motion is still pending (busy link). */
    private fun flushMotionLocked(): Boolean {
        val s = session ?: run { mx = 0f; my = 0f; sx = 0f; sy = 0f; return false }
        if (s.queued() > MAX_QUEUED) return true // keep summing; never queue stale moves
        val dx = mx.toInt().coerceIn(-4000, 4000); val dy = my.toInt().coerceIn(-4000, 4000)
        if (dx != 0 || dy != 0) { s.send(Proto.move(dx, dy)); mx -= dx; my -= dy }
        val wx = sx.toInt(); val wy = sy.toInt()
        if (wx != 0 || wy != 0) { s.send(Proto.scroll(wx, wy)); sx -= wx; sy -= wy }
        return abs(mx) >= 1f || abs(my) >= 1f || abs(sx) >= 1f || abs(sy) >= 1f
    }

    // ---- Transport ----

    override fun setMods(m: Hid.Mods) { _mods.value = m }

    private fun consumeArmed() { _mods.value.let { if (it.armed != 0) _mods.value = it.copy(armed = 0) } }

    override fun tap(key: String, mods: Int) {
        sendNow(Proto.key(Key.usage(key), _mods.value.active or mods))
        consumeArmed()
    }

    private val repeats = ConcurrentHashMap<String, Job>()

    /** Injected key-downs don't auto-repeat on the Mac, so held keys repeat here (like the Keys deck). */
    override fun keyDown(key: String) {
        val usage = Key.usage(key)
        sendNow(Proto.key(usage, _mods.value.active, "down"))
        repeats.put(key, scope.launch {
            delay(REPEAT_DELAY_MS)
            while (isActive) { sendNow(Proto.key(usage, _mods.value.active, "down")); delay(REPEAT_MS) }
        })?.cancel()
    }

    override fun keyUp(key: String) {
        repeats.remove(key)?.cancel()
        sendNow(Proto.key(Key.usage(key), _mods.value.active, "up"))
        consumeArmed()
    }

    override suspend fun tapAwait(key: String, mods: Int) { sendNow(Proto.key(Key.usage(key), mods)); delay(10) }

    /** Unicode text: layout-independent, nothing is skipped. */
    override suspend fun typeText(text: String): Int {
        Proto.text(text).forEach { sendNow(it) }
        return 0
    }

    override fun consumer(usage: Int) { Proto.consumerKey(usage)?.let { sendNow(Proto.consumer(it)) } }

    override fun consumerHold(usage: Int, down: Boolean) {
        Proto.consumerKey(usage)?.let { sendNow(Proto.consumer(it, if (down) "down" else "up")) }
    }

    override fun move(dx: Float, dy: Float) {
        synchronized(sendLock) { mx += dx * POINT_GAIN; my += dy * POINT_GAIN }
        scheduleFlush()
    }

    override fun setButton(mask: Int, down: Boolean) = sendNow(Proto.button(Proto.buttonName(mask), if (down) "down" else "up"))
    override fun click(mask: Int) = sendNow(Proto.button(Proto.buttonName(mask)))

    /** HID wheel ticks to pixels. The helper doesn't apply natural scrolling, so the HID sign maps directly. */
    override fun scroll(wheel: Int, pan: Int) {
        synchronized(sendLock) { sy += wheel * SCROLL_PX; sx -= pan * SCROLL_PX }
        scheduleFlush()
    }

    override fun clickAt(x: Float, y: Float): Boolean { sendNow(Proto.clickAt(x.toDouble(), y.toDouble())); return true }

    /** `spaces.goto`; the helper picks ⌃N or ⌃← / ⌃→ steps and the next `spaces` confirms the switch. */
    override fun spacesGoto(index: Int, display: String?) = sendNow(Proto.spacesGoto(index, display))

    override fun sendLabs(json: String): Boolean {
        if (session == null) return false
        sendNow(json)
        return true
    }

    companion object {
        const val FRAME_MS = 16L
        const val MAX_QUEUED = 4096L
        const val MIN_BACKOFF = 500L
        const val MAX_BACKOFF = 8000L
        const val DENIED_MIN_BACKOFF = 30_000L // B6: a denial is not a transient fault; no tight loop
        const val DENIED_MAX_BACKOFF = 300_000L
        const val REPEAT_DELAY_MS = 400L
        const val REPEAT_MS = 60L
        const val SCROLL_PX = 24f
        const val POINT_GAIN = 1f // touchpad dp to Mac points; tune on a device
    }
}
