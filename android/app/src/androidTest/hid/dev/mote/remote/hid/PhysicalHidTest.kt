package dev.mote.remote.hid

import android.Manifest
import android.bluetooth.BluetoothManager
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real Bluetooth HID on a physical phone (no fakes). Requires Bluetooth on and a host already
 * bonded and in range (paired through the app's "Pair new device"). Choose the host with
 * `-Pandroid.testInstrumentationRunnerArguments.host=AA:BB:CC:DD:EE:FF`; the default is the first
 * bonded computer. See android/README.md.
 */
@PhysicalHid
@RunWith(AndroidJUnit4::class)
class PhysicalHidTest {
    private val instr = InstrumentationRegistry.getInstrumentation()
    private val ctx = instr.targetContext

    @Before
    fun setUp() {
        TestSeam.sink = null
        TestSeam.adapter = null
        val perms = Bt.permissions.toList() + if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()
        perms.forEach { instr.uiAutomation.grantRuntimePermission(ctx.packageName, it) }
        val adapter = ctx.getSystemService(BluetoothManager::class.java)?.adapter
        assumeTrue("Bluetooth adapter present and enabled", adapter?.isEnabled == true)
    }

    @After
    fun tearDown() {
        Hid.foreground = false
        Hid.stop()
    }

    private fun await(timeoutMs: Long, what: String, ok: (HidState) -> Boolean): HidState {
        val s = runBlocking { withTimeoutOrNull(timeoutMs) { Hid.state.first(ok) } }
        assertNotNull("$what within $timeoutMs ms; last state ${Hid.state.value}", s)
        return s!!
    }

    @Test
    fun registerAppSucceeds() {
        Hid.start(ctx)
        await(10_000, "registerApp -> onAppStatusChanged(registered=true)") { it.phase == Phase.READY }
    }

    @Test
    fun connectsToBondedHost() {
        val env = Bt.snapshot(ctx)
        val wanted = InstrumentationRegistry.getArguments().getString("host")
        val host = if (wanted != null) env.hosts.firstOrNull { it.address.equals(wanted, ignoreCase = true) }
        else env.hosts.firstOrNull { it.kind != Host.Kind.OTHER }
        assumeTrue("a bonded host (${wanted ?: "any computer"}) among ${env.hosts}", host != null)

        Hid.start(ctx)
        await(10_000, "HID app registered") { it.phase == Phase.READY }
        Hid.foreground = true // auto-reconnect only runs in the foreground
        Hid.select(host!!)
        await(30_000, "STATE_CONNECTED with ${host.name}") { it.connected?.address == host.address }
    }
}
