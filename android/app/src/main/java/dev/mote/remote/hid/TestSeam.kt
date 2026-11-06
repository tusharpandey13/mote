package dev.mote.remote.hid

import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.flow.StateFlow

/**
 * Test-only seam. Both hooks are null in production, so the app takes the real Bluetooth paths.
 * Instrumented tests (app/src/androidTest) install fakes before launching the activity:
 * - [sink]: every HID report goes here instead of the Bluetooth proxy, and [Hid] skips the real
 *   profile registration so its [HidState] is driven by the test.
 * - [adapter]: replaces the adapter half of [Bt.snapshot] (present / enabled / bonded hosts).
 *   Runtime permissions are still read from the system.
 */
@VisibleForTesting
object TestSeam {
    fun interface Sink { fun send(id: Int, data: ByteArray) }

    data class Adapter(val supported: Boolean = true, val enabled: Boolean = true, val hosts: List<Host> = emptyList())

    @Volatile var sink: Sink? = null
    @Volatile var adapter: StateFlow<Adapter>? = null
}
