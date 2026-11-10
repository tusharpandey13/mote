package dev.mote.remote.diag

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/** Android runtime permissions Mote uses, as facts for the event log and the Diagnostics screen. */
object Perms {
    /** name -> granted. Permissions that do not exist on this API level are granted by definition. */
    fun state(c: Context): Map<String, Boolean> {
        fun has(p: String, minSdk: Int) = Build.VERSION.SDK_INT < minSdk || c.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
        return linkedMapOf(
            "bluetoothConnect" to has(Manifest.permission.BLUETOOTH_CONNECT, 31),
            "bluetoothScan" to has(Manifest.permission.BLUETOOTH_SCAN, 31),
            "bluetoothAdvertise" to has(Manifest.permission.BLUETOOTH_ADVERTISE, 31),
            "notifications" to has(Manifest.permission.POST_NOTIFICATIONS, 33),
        )
    }

    /** Logs the current grants (`permission.check`). */
    fun check(c: Context, why: String) {
        Events.log("permission", "check", "why" to why, *state(c).map { it.key to it.value }.toTypedArray())
    }

    fun requested(names: Collection<String>, from: String) {
        Events.log("permission", "request", "from" to from, "permissions" to names.map { it.substringAfterLast('.') })
    }

    fun result(c: Context, from: String) {
        Events.log("permission", "result", "from" to from, *state(c).map { it.key to it.value }.toTypedArray())
    }
}
