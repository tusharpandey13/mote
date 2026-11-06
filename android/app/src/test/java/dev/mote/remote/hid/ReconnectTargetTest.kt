package dev.mote.remote.hid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReconnectTargetTest {
    private val mac = "FC:B2:14:74:79:4B"
    private val other = "AA:BB:CC:DD:EE:FF"

    @Test fun selectedWins() = assertEquals(other, Hid.reconnectTarget(other, mac))
    @Test fun pluggedWhenNothingSelected() = assertEquals(mac, Hid.reconnectTarget(null, mac))
    @Test fun noneWhenNeither() = assertNull(Hid.reconnectTarget(null, null))
}
