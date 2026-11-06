package dev.mote.remote.hid

import androidx.annotation.VisibleForTesting
import dev.mote.remote.net.Transport
import kotlinx.coroutines.flow.StateFlow

/**
 * Test-only seam. Both hooks are null in production, so the app takes the real paths.
 * Instrumented tests (app/src/androidTest) install fakes before the app touches [dev.mote.remote.net.Net]:
 * - [transport]: replaces the Bluetooth HID transport, so every UI action lands in the fake
 *   (which can present Bluetooth HID or Mote Helper capabilities). [Hid] then skips the real
 *   profile registration; its host selection state still works.
 * - [adapter]: replaces the adapter half of [Bt.snapshot] (present / enabled / bonded hosts).
 *   Runtime permissions are still read from the system.
 */
@VisibleForTesting
object TestSeam {
    data class Adapter(val supported: Boolean = true, val enabled: Boolean = true, val hosts: List<Host> = emptyList())

    @Volatile var transport: Transport? = null
    @Volatile var adapter: StateFlow<Adapter>? = null
}
