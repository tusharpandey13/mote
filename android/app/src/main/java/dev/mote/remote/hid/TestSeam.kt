package dev.mote.remote.hid

import androidx.annotation.VisibleForTesting
import dev.mote.remote.net.Transport
import kotlinx.coroutines.flow.StateFlow

/**
 * Test-only seam. Both hooks are null in production, so the app takes the real paths.
 * Instrumented tests (app/src/androidTest) install fakes before the app touches [dev.mote.remote.net.Net]:
 * - [transport]: replaces the Bluetooth HID transport, so every UI action lands in the fake
 *   (which can present Bluetooth HID or Mote Helper capabilities). [Hid] then skips the real
 *   profile registration and reports it as done (phase READY); its host selection state still works.
 * - [adapter]: replaces the adapter half of [Bt.snapshot] (present / enabled / bonded hosts).
 *   Runtime permissions are still read from the system.
 */
@VisibleForTesting
object TestSeam {
    data class Adapter(val supported: Boolean = true, val enabled: Boolean = true, val hosts: List<Host> = emptyList())

    @Volatile var transport: Transport? = null
    @Volatile var adapter: StateFlow<Adapter>? = null
    /** Discovery tests: Bonjour / NSD finds nothing (a network without multicast). */
    @Volatile var noBonjour: Boolean = false
    /** Discovery tests: the port the LAN sweep probes (a dry-run helper on the dev Mac), instead of 8790. */
    @Volatile var sweepPort: Int? = null
    /** Phone-mode journeys (client-isolated Wi-Fi): also probe this host (the Mac's tailnet IP) on [sweepPort]. Never set in production. */
    @Volatile var probeHost: String? = null
    /** Discovery tests without real BLE: read the proximity token from a `--fake-proximity` dry-run helper over the WebSocket. */
    @Volatile var fakeProximity: Boolean = false
}
