package dev.mote.remote.pairing

import dev.mote.remote.net.WsStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RediscoverTest {
    @Test fun deniedDoesNotSweep() {
        assertFalse(InstantPairing.shouldRediscover(WsStatus.Denied("far")))
        assertFalse(InstantPairing.shouldRediscover(WsStatus.Idle))
        assertTrue(InstantPairing.shouldRediscover(WsStatus.Failed("revoked", "")))
        assertTrue(InstantPairing.shouldRediscover(WsStatus.Connecting))
    }
}
