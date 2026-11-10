package dev.mote.remote.pairing

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * A Mac seen over BLE, with its info characteristic and, when the helper has one, the proximity
 * token read in the same GATT session at [proxAt] (wall clock ms).
 */
data class NearbyMac(val info: BleInfo, val rssi: Int, val seenAt: Long, val prox: String? = null, val proxAt: Long = 0)

/**
 * Finds Mote Helpers by BLE: scans for the Mote service UUID (BLUETOOTH_SCAN with neverForLocation,
 * so Android 12+ only: below that BLE scans need the location permission, which Mote doesn't ask
 * for), then connects to each new one, reads the info and proximity characteristics and
 * disconnects. One GATT session at a time, 8 s each. Main thread only (except [proximity]). Never
 * throws: no adapter / permission = no results.
 */
@SuppressLint("MissingPermission") // every entry point checks [permitted]
class BleScan(context: Context) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val _found = MutableStateFlow<List<NearbyMac>>(emptyList())
    val found: StateFlow<List<NearbyMac>> = _found.asStateFlow()

    private var scanning = false
    private val queue = ArrayDeque<BluetoothDevice>()
    private val tried = HashMap<String, Long>() // BLE address -> last read attempt
    private var gatt: BluetoothGatt? = null
    private val stopRunnable = Runnable { stop() }

    fun permitted(): Boolean = Build.VERSION.SDK_INT >= 31 && PERMISSIONS.all { app.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private val adapter get() = app.getSystemService(BluetoothManager::class.java)?.adapter
    /** Bluetooth is usable for discovery right now (Android 12+, permitted, adapter on). */
    fun ready(): Boolean = permitted() && runCatching { adapter?.isEnabled == true }.getOrDefault(false)

    /**
     * A fresh proximity token for [hostId], or null (not nearby, no permission, helper too old).
     * Reuses one read in the last [maxAgeMs]; otherwise reconnects to the Mac (scanning if it hasn't
     * been seen) and waits up to [timeoutMs]. A token is single-use on the Mac, so it is cleared here
     * once handed out.
     */
    suspend fun proximity(hostId: String, timeoutMs: Long = 5000, maxAgeMs: Long = 20_000): String? = withContext(Dispatchers.Main) {
        if (!ready()) return@withContext null
        val now = System.currentTimeMillis()
        val cached = _found.value.firstOrNull { it.info.hostId == hostId }
        if (cached?.prox != null && now - cached.proxAt < maxAgeMs) return@withContext take(hostId)
        val dev = devices[hostId]
        if (dev != null) { tried.remove(dev.address); if (queue.none { it.address == dev.address }) queue.addFirst(dev); next() } else start()
        withTimeoutOrNull(timeoutMs) { _found.first { l -> l.any { it.info.hostId == hostId && it.prox != null && it.proxAt >= now } } }
            ?: return@withContext null
        take(hostId)
    }

    private fun take(hostId: String): String? {
        val m = _found.value.firstOrNull { it.info.hostId == hostId } ?: return null
        update(m.copy(prox = null))
        return m.prox
    }

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) { main.post { seen(result) } }
        override fun onBatchScanResults(results: MutableList<ScanResult>) { main.post { results.forEach(::seen) } }
        override fun onScanFailed(errorCode: Int) { Log.w(TAG, "scan failed: $errorCode"); main.post { scanning = false } }
    }

    fun start(durationMs: Long = 20_000) {
        if (scanning || !permitted()) return
        val scanner = runCatching { adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner }.getOrNull() ?: return
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(UUID.fromString(PairProto.SERVICE_UUID))).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanning = runCatching { scanner.startScan(listOf(filter), settings, callback) }.isSuccess
        main.removeCallbacks(stopRunnable)
        if (scanning) main.postDelayed(stopRunnable, durationMs)
    }

    fun stop() {
        main.removeCallbacks(stopRunnable)
        if (scanning) runCatching { adapter?.bluetoothLeScanner?.stopScan(callback) }
        scanning = false
        queue.clear()
    }

    private fun seen(r: ScanResult) {
        val addr = r.device.address
        val known = _found.value.firstOrNull { it.info.hostId == bleIds[addr] }
        if (known != null) { update(known.copy(rssi = r.rssi, seenAt = System.currentTimeMillis())); return }
        val last = tried[addr] ?: 0L
        if (System.currentTimeMillis() - last < 30_000 || queue.any { it.address == addr }) return
        rssis[addr] = r.rssi
        queue.addLast(r.device)
        next()
    }

    private val bleIds = HashMap<String, String>() // BLE address -> hostId
    private val devices = HashMap<String, BluetoothDevice>() // hostId -> device, for proximity re-reads
    private val rssis = HashMap<String, Int>()
    private var reading: BleInfo? = null // info of the current session, until the proximity read

    private fun update(m: NearbyMac) {
        _found.value = (_found.value.filterNot { it.info.hostId == m.info.hostId } + m).sortedByDescending { it.rssi }
    }

    private fun next() {
        if (gatt != null) return
        val d = queue.removeFirstOrNull() ?: return
        tried[d.address] = System.currentTimeMillis()
        val g = runCatching { d.connectGatt(app, false, gattCallback, BluetoothDevice.TRANSPORT_LE) }.getOrNull() ?: return next()
        gatt = g
        main.postDelayed({ if (gatt === g) finish(g) }, 8000)
    }

    private fun finish(g: BluetoothGatt) {
        runCatching { g.disconnect(); g.close() }
        if (gatt !== g) return // already finished (timeout, then the disconnect callback)
        gatt = null
        reading?.let { publish(g, it, null) } // helper without the proximity characteristic
        reading = null
        next()
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            main.post {
                if (newState == BluetoothProfile.STATE_CONNECTED) { if (!runCatching { g.requestMtu(517) }.getOrDefault(false)) g.discoverServices() }
                else if (newState == BluetoothProfile.STATE_DISCONNECTED) finish(g)
            }
        }
        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) { main.post { runCatching { g.discoverServices() } } }
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            main.post {
                val ch = g.getService(UUID.fromString(PairProto.SERVICE_UUID))?.getCharacteristic(UUID.fromString(PairProto.INFO_UUID))
                if (ch == null || !runCatching { g.readCharacteristic(ch) }.getOrDefault(false)) finish(g) // helper without instant pairing
            }
        }
        override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            main.post { read(g, ch, value, status) }
        }
        @Deprecated("API < 33 delivers the value on the characteristic")
        override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            @Suppress("DEPRECATION") val v = ch.value ?: ByteArray(0)
            main.post { read(g, ch, v, status) }
        }
    }

    /** Info first, then (same session) the proximity token. */
    private fun read(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
        if (gatt !== g) return
        val ok = status == BluetoothGatt.GATT_SUCCESS
        if (ch.uuid == UUID.fromString(PairProto.PROX_UUID)) {
            reading?.let { publish(g, it, if (ok) PairProto.proxOrNull(value) else null) }
            reading = null
            finish(g); return
        }
        val info = (if (ok) BleInfo.parse(value) else null) ?: run { finish(g); return }
        reading = info
        val prox = g.getService(UUID.fromString(PairProto.SERVICE_UUID))?.getCharacteristic(UUID.fromString(PairProto.PROX_UUID))
        if (prox == null || !runCatching { g.readCharacteristic(prox) }.getOrDefault(false)) finish(g)
    }

    private fun publish(g: BluetoothGatt, info: BleInfo, prox: String?) {
        val addr = g.device.address
        bleIds[addr] = info.hostId
        devices[info.hostId] = g.device
        val now = System.currentTimeMillis()
        update(NearbyMac(info, rssis[addr] ?: -100, now, prox, if (prox != null) now else 0))
    }

    companion object {
        private const val TAG = "MoteBleScan"
        /** What BLE discovery needs (asked in context, never at launch). */
        val PERMISSIONS = listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    }
}
