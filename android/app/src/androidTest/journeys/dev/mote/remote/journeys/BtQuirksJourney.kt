package dev.mote.remote.journeys

import android.Manifest
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.mote.remote.e2e.FakeTransport
import dev.mote.remote.hid.Host
import dev.mote.remote.hid.TestSeam
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** docs/journeys/bt-quirks.md */
@RunWith(AndroidJUnit4::class)
class BtQuirksJourney {
    @get:Rule val j = JourneyRule()

    private val mac = Host("AA:BB:CC:DD:EE:01", "Test Mac", Host.Kind.LAPTOP)
    private val seam = "faked: adapter (bonded host \"Test Mac\") + transport seams"
    private val stale = "The computer kept an old pairing. Fix: on the computer, forget this phone in Bluetooth settings; here, long-press it and forget it too; then Pair new Bluetooth device again."
    private lateinit var adapter: MutableStateFlow<TestSeam.Adapter>

    /** P1: Bluetooth path ready with one bonded host, Devices sheet open. */
    private fun toDevices() {
        j.grant(*B_BT_PERMS)
        if (Build.VERSION.SDK_INT >= 33) j.grant(Manifest.permission.POST_NOTIFICATIONS)
        adapter = MutableStateFlow(TestSeam.Adapter(supported = true, enabled = true, hosts = listOf(mac)))
        j.launch(transport = FakeTransport(), adapter = adapter)
        j.bToBluetoothRemote()
        j.bPre("open Devices") { j.tap("Device: ", substring = true) }
        j.bPre("Devices sheet") { j.expect("Devices") }
    }

    @Test fun main() {
        j.path("bt-quirks")
        j.manual("S1", "Keyboard Setup Assistant is macOS UI after a real Bluetooth HID bond")
        // S2 (HID host connected → "Connected · BT", green LED) needs a real HID connection: the fake transport
        // doesn't drive Hid.state.connected (missing fake); not automated.
        j.step("S3", seam) {
            toDevices()
            j.expect("Use Test Mac")
            j.expect("Add a Mac"); j.expect("More options")
            j.expect("Pair new Bluetooth device")
            j.expect("Makes this phone visible for 2 minutes. Pair it from the computer's Bluetooth settings; Android asks you to confirm the code.")
            j.expect("Connected over Bluetooth but buttons do nothing?")
        }
    }

    @Test fun staleHelp() {
        j.path("bt-quirks.stale-help")
        j.step("B1", seam) {
            toDevices()
            j.tap("Connected over Bluetooth but buttons do nothing?")
            j.expect(stale)
            j.expectNot("Connected over Bluetooth but buttons do nothing?")
        }
        j.static("B2", "no code inspects HID report delivery")
    }

    @Test fun forgetOnPhone() {
        j.path("bt-quirks.forget-on-phone")
        j.step("B1", seam) {
            toDevices()
            j.bLongPress("Use Test Mac")
            Thread.sleep(500)
            j.expectNot("Forget Test Mac on this phone?")
        }
        // B2 (row grouped with a saved helper host) not automated: needs a helper host named like the BT host.
        j.step("B3", "faked: drop the host from TestSeam.adapter.hosts (stands in for Android Settings › Forget)") {
            j.tap("Use Test Mac")
            j.expect("Device: Test Mac via BT. Tap to choose", timeoutMs = 5_000)
            adapter.value = adapter.value.copy(hosts = emptyList())
            j.expect("Device: Not paired: pair again via BT. Tap to choose", timeoutMs = 8_000)
        }
    }

    @Test fun forgetOnMac() {
        j.path("bt-quirks.forget-on-mac")
        j.manual("B1", "macOS System Settings › Bluetooth")
    }

    @Test fun pairAgain() {
        j.path("bt-quirks.pair-again")
        j.step("B1", "real dialog (emulator adapter); copy UNDEFINED") {
            toDevices()
            j.tap("Pair new Bluetooth device")
            if (!j.systemShown("Allow", 8_000)) throw AssertionError("no discoverable system dialog")
            j.systemTap("Deny|Don.t allow|Cancel")
        }
        j.manual("B3", "pair from macOS System Settings")
    }

    @Test fun helperBtStepStale() {
        j.path("bt-quirks.helper-bt-step-stale")
        j.manual("B1", "needs a real Mac with a leftover bond; --fake-bt-pair can't model it")
    }
}
