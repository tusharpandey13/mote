package dev.mote.remote.journeys

import android.Manifest
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.mote.remote.hid.Host
import dev.mote.remote.hid.TestSeam
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** docs/journeys/reconnect.md RC-15: a Bluetooth-only row offers Forget (hand-over to Android's Bluetooth settings). */
@RunWith(AndroidJUnit4::class)
class BtForgetJourney {
    @get:Rule val j = JourneyRule()

    private val mac = Host("AA:BB:CC:DD:EE:01", "Test Mac", Host.Kind.LAPTOP)

    @Test fun forgetBluetoothOnlyHost() {
        j.path("reconnect.rc-15")
        j.step("RC-15", "faked: adapter (bonded host \"Test Mac\", no helper) + Bluetooth transport seams") {
            j.grant(*B_BT_PERMS)
            if (Build.VERSION.SDK_INT >= 33) j.grant(Manifest.permission.POST_NOTIFICATIONS)
            val adapter = aAdapter().also { it.value = it.value.copy(hosts = listOf(mac)) }
            j.launch(transport = aHidFake(), adapter = adapter)
            j.bToBluetoothRemote()
            j.bPre("open Devices") { j.tap("Device: ", substring = true) }
            j.bPre("Devices sheet") { j.expect("Use Test Mac") }
            j.bLongPress("Use Test Mac")
            j.expect("Forget Test Mac? Android removes Bluetooth pairings in its own settings.")
            j.expect("Open settings")
            // Cancel leaves the row alone (the bond is Android's; the app has nothing of its own to remove).
            j.tap("Cancel")
            j.expectNot("Open settings")
            j.expect("Use Test Mac")
        }
    }
}
