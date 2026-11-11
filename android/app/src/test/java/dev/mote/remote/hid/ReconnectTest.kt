package dev.mote.remote.hid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconnectTest {
    private val mac = "FC:B2:14:74:79:4B"
    private val other = "AA:BB:CC:DD:EE:FF"

    @Test fun selectedBondedWins() = assertEquals(other, Reconnect.target(other, mac, setOf(mac, other)))
    @Test fun pluggedWhenNothingSelected() = assertEquals(mac, Reconnect.target(null, mac, setOf(mac)))
    @Test fun unbondedSelectedFallsBackToBondedPlugged() = assertEquals(mac, Reconnect.target(other, mac, setOf(mac)))
    @Test fun neverTargetsUnbonded() {
        assertNull(Reconnect.target(mac, mac, emptySet()))
        assertNull(Reconnect.target(null, null, setOf(mac)))
    }

    @Test fun unpairedOnlyWhenSelectedNotBonded() {
        assertTrue(Reconnect.unpaired(mac, emptySet()))
        assertFalse(Reconnect.unpaired(mac, setOf(mac)))
        assertFalse(Reconnect.unpaired(null, emptySet()))
    }

    @Test fun bondedOnlyKnownWithPermissionAndAdapterOn() {
        val hosts = listOf(Host(mac, "Mac"))
        assertEquals(setOf(mac), Reconnect.bonded(BtEnv(supported = true, permitted = true, enabled = true, hosts = hosts)))
        assertEquals(emptySet<String>(), Reconnect.bonded(BtEnv(supported = true, permitted = true, enabled = true)))
        assertNull(Reconnect.bonded(BtEnv(supported = true, permitted = false, enabled = true)))
        assertNull(Reconnect.bonded(BtEnv(supported = true, permitted = true, enabled = false)))
        assertNull(Reconnect.bonded(BtEnv(supported = false)))
    }

    @Test fun capsAttempts() {
        assertTrue(Reconnect.allowed(Reconnect.MAX_ATTEMPTS - 1, 0, 0))
        assertFalse(Reconnect.allowed(Reconnect.MAX_ATTEMPTS, 0, 0))
    }

    @Test fun pausedWhileDiscoverable() {
        assertFalse(Reconnect.allowed(0, nowMs = 1_000, pausedUntilMs = 2_000))
        assertTrue(Reconnect.allowed(0, nowMs = 2_000, pausedUntilMs = 2_000))
    }
}
