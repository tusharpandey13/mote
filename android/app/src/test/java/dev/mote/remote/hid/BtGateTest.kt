package dev.mote.remote.hid

import org.junit.Assert.assertEquals
import org.junit.Test

class BtGateTest {
    @Test fun noAdapterIsUnsupportedRegardlessOfRest() {
        assertEquals(BtGate.UNSUPPORTED, gateOf(supported = false, permitted = true, enabled = true))
        assertEquals(BtGate.UNSUPPORTED, BtEnv(supported = false).gate)
    }

    @Test fun permissionComesBeforeAdapterState() {
        assertEquals(BtGate.NEEDS_PERMISSION, gateOf(supported = true, permitted = false, enabled = false))
        assertEquals(BtGate.NEEDS_PERMISSION, gateOf(supported = true, permitted = false, enabled = true))
    }

    @Test fun permittedButOffAsksToEnable() {
        assertEquals(BtGate.BT_OFF, gateOf(supported = true, permitted = true, enabled = false))
    }

    @Test fun permittedAndOnIsReady() {
        assertEquals(BtGate.READY, BtEnv(supported = true, permitted = true, enabled = true).gate)
    }

    @Test fun turningBluetoothOffLaterReopensTheGate() {
        val on = BtEnv(permitted = true, enabled = true)
        assertEquals(BtGate.READY, on.gate)
        assertEquals(BtGate.BT_OFF, on.copy(enabled = false).gate)
    }
}
