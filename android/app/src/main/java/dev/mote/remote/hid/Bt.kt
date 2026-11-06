package dev.mote.remote.hid

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** What stands between the user and a usable remote. Bluetooth is the only transport today. */
enum class BtGate { UNSUPPORTED, NEEDS_PERMISSION, BT_OFF, READY }

/** Live view of the Bluetooth environment: adapter, runtime permissions, bonded hosts. */
data class BtEnv(
    val supported: Boolean = true,
    val permitted: Boolean = false,
    val enabled: Boolean = false,
    val hosts: List<Host> = emptyList(),
) {
    val gate get() = gateOf(supported, permitted, enabled)
}

/** Pure reducer: adapter + permissions -> UI gate. Permission comes first: without it the adapter can't be enabled from the app. */
fun gateOf(supported: Boolean, permitted: Boolean, enabled: Boolean): BtGate = when {
    !supported -> BtGate.UNSUPPORTED
    !permitted -> BtGate.NEEDS_PERMISSION
    !enabled -> BtGate.BT_OFF
    else -> BtGate.READY
}

@SuppressLint("MissingPermission") // bonded devices are read only when permitted
object Bt {
    /** Runtime permissions Mote needs for Bluetooth HID (install-time before API 31). */
    val permissions: Array<String> =
        if (Build.VERSION.SDK_INT >= 31) arrayOf(
            Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_SCAN,
        ) else emptyArray()

    fun permitted(c: Context) = permissions.all { c.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    fun snapshot(c: Context): BtEnv {
        TestSeam.adapter?.value?.let { a ->
            val ok = permitted(c)
            return BtEnv(a.supported, ok, a.enabled, if (ok && a.enabled) a.hosts else emptyList())
        }
        val a = c.getSystemService(BluetoothManager::class.java)?.adapter ?: return BtEnv(supported = false)
        val permitted = permitted(c)
        val enabled = a.isEnabled
        val hosts = if (permitted && enabled) runCatching {
            a.bondedDevices.orEmpty().map(::hostOf).sortedBy { it.kind.ordinal }
        }.getOrDefault(emptyList()) else emptyList()
        return BtEnv(true, permitted, enabled, hosts)
    }

    /**
     * Re-snapshots on adapter on/off, bond and ACL connection changes, and on every ON_RESUME
     * (permission dialogs and Settings don't broadcast). Collect with lifecycle awareness.
     */
    fun observe(context: Context, lifecycle: Lifecycle): Flow<BtEnv> = callbackFlow {
        val c = context.applicationContext
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, i: Intent) { trySend(snapshot(c)) }
        }
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothDevice.ACTION_NAME_CHANGED)
        }
        ContextCompat.registerReceiver(c, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        val resume = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) trySend(snapshot(c)) }
        lifecycle.addObserver(resume)
        trySend(snapshot(c))
        val fake = TestSeam.adapter?.let { f -> launch { f.collect { trySend(snapshot(c)) } } }
        awaitClose {
            fake?.cancel()
            c.unregisterReceiver(receiver)
            lifecycle.removeObserver(resume)
        }
    }.distinctUntilChanged()

    fun hostOf(d: BluetoothDevice): Host {
        val kind = when {
            d.bluetoothClass?.majorDeviceClass != BluetoothClass.Device.Major.COMPUTER -> Host.Kind.OTHER
            d.bluetoothClass?.deviceClass == BluetoothClass.Device.COMPUTER_DESKTOP -> Host.Kind.DESKTOP
            else -> Host.Kind.LAPTOP
        }
        return Host(d.address, runCatching { d.name }.getOrNull() ?: d.address, kind)
    }
}
