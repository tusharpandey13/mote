package dev.mote.remote.hid

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppQosSettings
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.util.Log
import dev.mote.remote.data.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

data class Host(val address: String, val name: String, val kind: Kind = Kind.OTHER) {
    enum class Kind { LAPTOP, DESKTOP, OTHER }
}

enum class Phase { OFF, UNSUPPORTED, BT_OFF, REGISTERING, READY, BLOCKED }

data class HidState(
    val phase: Phase = Phase.OFF,
    val selected: Host? = null,
    val connected: Host? = null,
    val connecting: Boolean = false,
    /** The saved host is no longer bonded: needs pairing again. */
    val unpaired: Boolean = false,
    /** Auto-reconnect used its [Reconnect.MAX_ATTEMPTS] and the saved host still isn't there: say so, the user can tap it to try again. */
    val gaveUp: Boolean = false,
) {
    val isConnected get() = connected != null
}

/**
 * Process-wide owner of the BluetoothHidDevice registration and the single report pipe.
 * Lifecycle is driven by [dev.mote.remote.service.HidService]: start() on create, stop() on destroy.
 * Every report is sent from one thread so key/mouse/consumer ordering is preserved.
 * All Bluetooth calls require BLUETOOTH_CONNECT, which the service checks before starting.
 */
@SuppressLint("MissingPermission")
object Hid {
    private const val TAG = "MoteHid"
    private const val HOLD_MS = 30L
    private const val RECONNECT_MS = 4000L

    private val exec = Executors.newSingleThreadExecutor { Thread(it, "mote-hid") }
    private val hidDispatcher = exec.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + hidDispatcher)

    private val _state = MutableStateFlow(HidState())
    val state: StateFlow<HidState> = _state.asStateFlow()

    /** Sticky modifiers: armed = one-shot, locked = latched. Both are held down on the host. */
    data class Mods(val armed: Int = 0, val locked: Int = 0) {
        val active get() = armed or locked
    }
    private val _mods = MutableStateFlow(Mods())
    val mods: StateFlow<Mods> = _mods.asStateFlow()

    /** True while an activity is visible; auto-reconnect only runs then. Leaving releases modifiers. */
    private val _fg = MutableStateFlow(false)
    val inForeground: StateFlow<Boolean> = _fg.asStateFlow()
    var foreground: Boolean
        get() = _fg.value
        set(v) { _fg.value = v; if (v) envChanged() else setMods(Mods()) }

    private lateinit var appContext: Context
    private var adapter: BluetoothAdapter? = null
    private var proxy: BluetoothHidDevice? = null
    private var device: BluetoothDevice? = null
    /** Host the stack reports as virtually plugged at registration; reconnect target when none is selected. */
    private var plugged: String? = null
    private var attempts = 0
    private var pausedUntil = 0L
    /** A Mote Helper host is active ([deselect]); Bluetooth auto-connect is off until [select]. */
    private var released = false
    private var reconnectJob: Job? = null

    // keyboard / mouse state, touched only on the HID thread
    private var chordMods = 0
    private val held = ArrayList<Int>(6)
    private var buttons = 0
    private val moveLock = Any()
    private var accX = 0f
    private var accY = 0f
    private var flushPending = false

    fun start(context: Context) = exec.execute {
        appContext = context.applicationContext
        // tests: no real registration; act as if it succeeded, so host selection behaves as on a phone
        if (TestSeam.transport != null) { _state.update { it.copy(phase = Phase.READY) }; return@execute }
        if (proxy != null || _state.value.phase == Phase.REGISTERING) return@execute
        val a = context.getSystemService(BluetoothManager::class.java)?.adapter
        adapter = a
        if (a == null) { _state.update { it.copy(phase = Phase.UNSUPPORTED) }; return@execute }
        scope.launch {
            val addr = Prefs.selectedHost(appContext).first()
            if (addr != null && _state.value.selected == null) _state.update { it.copy(selected = hostFor(addr)) }
        }
        if (!a.isEnabled) { _state.update { it.copy(phase = Phase.BT_OFF) }; return@execute }
        _state.update { it.copy(phase = Phase.REGISTERING) }
        if (!a.getProfileProxy(appContext, profileListener, BluetoothProfile.HID_DEVICE)) {
            _state.update { it.copy(phase = Phase.UNSUPPORTED) }
        }
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            while (isActive) { delay(RECONNECT_MS); tryReconnect() }
        }
    }

    fun stop() {
        if (TestSeam.transport != null) return
        reconnectJob?.cancel(); reconnectJob = null
        exec.execute {
            releaseAll()
            runCatching { proxy?.unregisterApp() }
            proxy?.let { p -> runCatching { adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, p) } }
            proxy = null; device = null
            _state.update { it.copy(phase = Phase.OFF, connected = null, connecting = false) }
        }
    }

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, p: BluetoothProfile) {
            val hid = p as BluetoothHidDevice
            proxy = hid
            val sdp = BluetoothHidDeviceAppSdpSettings(
                HidDescriptor.SDP_NAME, HidDescriptor.SDP_DESCRIPTION, HidDescriptor.SDP_PROVIDER,
                HidDescriptor.SUBCLASS, HidDescriptor.DESCRIPTOR,
            )
            // Populated QoS: some vendor stacks drop reports when QoS is null. Values from Linkpad (MIT).
            val qos = BluetoothHidDeviceAppQosSettings(
                BluetoothHidDeviceAppQosSettings.SERVICE_BEST_EFFORT, 6000, 1024, 10000, 11250, 11250,
            )
            val ok = runCatching { hid.registerApp(sdp, qos, qos, exec, callback) }.getOrDefault(false)
            if (!ok) _state.update { it.copy(phase = Phase.BLOCKED) }
        }

        // Fires when Bluetooth turns off. Close the proxy (so the system doesn't silently rebind it)
        // and drop to a non-registering phase: the next start() then registers from scratch.
        override fun onServiceDisconnected(profile: Int) = exec.execute {
            val p = proxy
            proxy = null; device = null
            p?.let { runCatching { adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, it) } }
            _state.update { it.copy(phase = if (adapter?.isEnabled == true) Phase.OFF else Phase.BT_OFF, connected = null, connecting = false) }
        }
    }

    private val callback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            Log.i(TAG, "registered=$registered plugged=${pluggedDevice?.address}")
            _state.update { it.copy(phase = if (registered) Phase.READY else Phase.BLOCKED) }
            if (registered) {
                plugged = pluggedDevice?.address
                if (pluggedDevice != null && proxy?.getConnectionState(pluggedDevice) == BluetoothProfile.STATE_CONNECTED) {
                    onConnected(pluggedDevice)
                } else tryReconnect()
            } else {
                device = null
                _state.update { it.copy(connected = null, connecting = false) }
            }
        }

        override fun onConnectionStateChanged(d: BluetoothDevice, st: Int) {
            Log.i(TAG, "conn ${d.address} -> $st")
            when (st) {
                BluetoothProfile.STATE_CONNECTED -> onConnected(d)
                BluetoothProfile.STATE_CONNECTING -> _state.update { it.copy(connecting = true) }
                BluetoothProfile.STATE_DISCONNECTED -> if (device == null || device?.address == d.address) {
                    device = null
                    resetInputState()
                    _state.update { it.copy(connected = null, connecting = false) }
                }
            }
        }

        override fun onGetReport(d: BluetoothDevice, type: Byte, id: Byte, bufferSize: Int) {
            val size = when (id.toInt()) {
                HidDescriptor.ID_KEYBOARD -> 8
                HidDescriptor.ID_MOUSE -> 5
                HidDescriptor.ID_CONSUMER -> 2
                else -> { proxy?.reportError(d, BluetoothHidDevice.ERROR_RSP_INVALID_RPT_ID); return }
            }
            proxy?.replyReport(d, type, id, ByteArray(size))
        }

        override fun onSetReport(d: BluetoothDevice, type: Byte, id: Byte, data: ByteArray?) {
            proxy?.reportError(d, BluetoothHidDevice.ERROR_RSP_SUCCESS)
        }
    }

    private fun onConnected(d: BluetoothDevice) {
        device = d
        attempts = 0
        val host = Bt.hostOf(d)
        _state.update { it.copy(connected = host, selected = host, connecting = false, gaveUp = false) }
        scope.launch { Prefs.setSelectedHost(appContext, d.address) }
    }

    // ---- hosts / connection ----

    fun select(host: Host) {
        exec.execute {
            val p = proxy
            val cur = device
            _state.update { it.copy(selected = host, unpaired = false, gaveUp = false) }
            scope.launch { Prefs.setSelectedHost(appContext, host.address) }
            if (p != null && cur != null && cur.address != host.address) runCatching { p.disconnect(cur) }
            // Explicit user tap: one connect even if unbonded (may start pairing once). Then normal policy.
            attempts = 0; pausedUntil = 0L; released = false
            if (p != null && _state.value.phase == Phase.READY && _state.value.connected?.address != host.address) {
                adapter?.getRemoteDevice(host.address)?.let { d -> attempts++; runCatching { p.connect(d) } }
            }
        }
    }

    /** Bond/ACL/adapter change or app resume: give auto-reconnect a fresh budget. */
    fun envChanged() = exec.execute { attempts = 0; _state.update { it.copy(gaveUp = false) }; tryReconnect() }

    /** Pairing flow started: phone discoverable for [ms]; no auto-connect meanwhile. */
    fun pausePairing(ms: Long) = exec.execute { pausedUntil = System.currentTimeMillis() + ms }

    fun disconnect() = exec.execute { device?.let { d -> runCatching { proxy?.disconnect(d) } } }

    /**
     * Another transport (Mote Helper) took over: forget the selection, drop the link, and don't
     * auto-connect (not even to the plugged host) until the user picks a Bluetooth host again.
     */
    fun deselect() = exec.execute {
        released = true
        _state.update { it.copy(selected = null, unpaired = false, gaveUp = false) }
        device?.let { d -> runCatching { proxy?.disconnect(d) } }
        if (::appContext.isInitialized) scope.launch { Prefs.clearSelectedHost(appContext) }
    }

    private fun tryReconnect() {
        if (released) return
        val s = _state.value
        // Bonded set from Bt.snapshot (honours TestSeam.adapter), and "Not paired" is decided before
        // the proxy check: with TestSeam.transport or a failed HID registration there is no proxy.
        if (!::appContext.isInitialized) return
        val bonded = Reconnect.bonded(Bt.snapshot(appContext)) ?: return
        val unpaired = Reconnect.unpaired(s.selected?.address, bonded)
        if (unpaired != s.unpaired) _state.update { it.copy(unpaired = unpaired) }
        val p = proxy ?: return
        val target = Reconnect.target(s.selected?.address, plugged, bonded) ?: return
        if (!foreground || s.phase != Phase.READY || s.connected != null) return
        if (!Reconnect.allowed(attempts, System.currentTimeMillis(), pausedUntil)) {
            // Budget spent (not merely paused for pairing): the UI stops saying "Searching" (D-RC-3).
            if (Reconnect.exhausted(attempts) && !s.gaveUp) _state.update { it.copy(gaveUp = true) }
            return
        }
        val d = adapter?.getRemoteDevice(target) ?: return
        if (p.getConnectionState(d) == BluetoothProfile.STATE_CONNECTING) return
        attempts++
        runCatching { p.connect(d) }
    }

    private fun hostFor(address: String): Host? =
        runCatching { adapter?.getRemoteDevice(address)?.let(Bt::hostOf) }.getOrNull()

    // ---- report pipe (HID thread only) ----

    private fun send(id: Int, data: ByteArray) {
        val p = proxy ?: return
        val d = device ?: return
        runCatching { p.sendReport(d, id, data) }
    }

    private fun sendKeyboard() = send(HidDescriptor.ID_KEYBOARD, HidDescriptor.keyboardReport(_mods.value.active or chordMods, held))

    private fun tapNow(usage: Int, mods: Int) {
        chordMods = mods
        if (mods != 0) { sendKeyboard(); Thread.sleep(8) }
        held.add(usage); sendKeyboard(); Thread.sleep(HOLD_MS)
        held.remove(usage); sendKeyboard()
        if (mods != 0) { chordMods = 0; Thread.sleep(8); sendKeyboard() }
    }

    private fun resetInputState() { _mods.value = Mods(); chordMods = 0; held.clear(); buttons = 0 }

    private fun releaseAll() {
        resetInputState()
        send(HidDescriptor.ID_KEYBOARD, HidDescriptor.keyboardReport(0, emptyList()))
        send(HidDescriptor.ID_MOUSE, HidDescriptor.mouseReport(0, 0, 0))
        send(HidDescriptor.ID_CONSUMER, HidDescriptor.consumerReport(0))
    }

    // ---- public input API (fire-and-forget, ordered) ----

    fun consumer(usage: Int) = exec.execute {
        send(HidDescriptor.ID_CONSUMER, HidDescriptor.consumerReport(usage))
        Thread.sleep(HOLD_MS)
        send(HidDescriptor.ID_CONSUMER, HidDescriptor.consumerReport(0))
    }

    /** Holds a consumer usage down ([down] = true) until released with [down] = false (hold-to-seek). */
    fun consumerHold(usage: Int, down: Boolean) = exec.execute {
        send(HidDescriptor.ID_CONSUMER, HidDescriptor.consumerReport(if (down) usage else 0))
    }

    /** Sticky modifiers are sent as real modifier-down so touchpad clicks become cmd-click etc. */
    fun setMods(m: Mods) {
        _mods.value = m
        exec.execute { sendKeyboard() }
    }

    /** One-shot (armed) modifiers clear after the next non-modifier key. */
    private fun consumeArmed() {
        val m = _mods.value
        if (m.armed != 0) { _mods.value = m.copy(armed = 0); sendKeyboard() }
    }

    /** Tap a key on top of the sticky modifiers (plus optional extra [mods]). */
    fun tap(key: String, mods: Int = 0) = exec.execute { tapNow(Key.usage(key), mods); consumeArmed() }

    fun keyDown(key: String) = exec.execute { val u = Key.usage(key); if (u !in held && held.size < 6) held.add(u); sendKeyboard() }

    fun keyUp(key: String) = exec.execute { held.remove(Key.usage(key)); sendKeyboard(); consumeArmed() }

    /** Blocking variants used by the macro runner (already on a background coroutine). */
    suspend fun tapAwait(key: String, mods: Int) = withContext(hidDispatcher) { tapNow(Key.usage(key), mods) }

    /** Types US-layout keystrokes; returns the number of skipped (untypeable) characters. */
    suspend fun typeText(text: String): Int {
        var skipped = 0
        for (c in text) {
            val km = Key.forChar(c)
            if (km == null) { skipped++; continue }
            tapAwait(km.first, km.second)
            delay(8)
        }
        return skipped
    }

    fun move(dx: Float, dy: Float) {
        synchronized(moveLock) {
            accX += dx; accY += dy
            if (flushPending) return
            flushPending = true
        }
        exec.execute(::flushMove)
    }

    /** Coalesces pointer deltas into at most one report per ~8 ms, split into ±127 chunks. */
    private fun flushMove() {
        while (true) {
            val x: Int; val y: Int; val more: Boolean
            synchronized(moveLock) {
                x = accX.toInt().coerceIn(-127, 127); y = accY.toInt().coerceIn(-127, 127)
                accX -= x; accY -= y
                more = kotlin.math.abs(accX) >= 1f || kotlin.math.abs(accY) >= 1f
                flushPending = more
            }
            if (x != 0 || y != 0) send(HidDescriptor.ID_MOUSE, HidDescriptor.mouseReport(buttons, x, y))
            if (!more) return
            Thread.sleep(8)
        }
    }

    fun setButton(mask: Int, down: Boolean) = exec.execute {
        buttons = if (down) buttons or mask else buttons and mask.inv()
        send(HidDescriptor.ID_MOUSE, HidDescriptor.mouseReport(buttons, 0, 0))
    }

    fun click(mask: Int) = exec.execute {
        send(HidDescriptor.ID_MOUSE, HidDescriptor.mouseReport(buttons or mask, 0, 0))
        Thread.sleep(HOLD_MS)
        send(HidDescriptor.ID_MOUSE, HidDescriptor.mouseReport(buttons, 0, 0))
    }

    /** Positive wheel = away from the user, positive pan = right (host applies natural scrolling). */
    fun scroll(wheel: Int, pan: Int) = exec.execute {
        if (wheel != 0 || pan != 0) send(HidDescriptor.ID_MOUSE, HidDescriptor.mouseReport(buttons, 0, 0, wheel, pan))
    }

    const val BUTTON_LEFT = 1
    const val BUTTON_RIGHT = 2
}
