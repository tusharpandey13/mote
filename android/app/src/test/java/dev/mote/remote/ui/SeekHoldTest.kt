package dev.mote.remote.ui

import dev.mote.remote.ui.SeekHold.Out
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SeekHoldTest {
    @Test fun shortPressIsATap() {
        val s = SeekHold()
        s.down(0)
        assertNull(s.tick(399))
        assertEquals(Out.SKIP, s.up(399))
    }

    @Test fun holdPastThresholdSeeksOnceThenStopsOnLift() {
        val s = SeekHold()
        s.down(0)
        assertEquals(Out.SEEK_START, s.tick(400))
        assertTrue(s.seeking)
        assertNull(s.tick(900)) // started only once
        assertEquals(Out.SEEK_STOP, s.up(2000))
        assertFalse(s.seeking)
    }

    @Test fun liftAfterThresholdWithoutTickNeverSkips() {
        val s = SeekHold()
        s.down(0)
        assertNull(s.up(450)) // no skip, and no release for a press that was never sent
    }

    @Test fun cancelReleasesASeekAndNeverSkips() {
        val s = SeekHold()
        s.down(0)
        assertNull(s.cancel()) // nothing held yet: no skip, nothing to release
        s.down(1000)
        s.tick(1500)
        assertEquals(Out.SEEK_STOP, s.cancel())
        assertNull(s.up(1600)) // already released
    }
}
