package dev.mote.remote.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

class StickLogicTest {
    @Test fun deadzoneIsStill() {
        assertEquals(0f, StickCurve.velocity(0f), 0f)
        assertEquals(0f, StickCurve.velocity(0.12f), 0f)
        assertTrue(StickCurve.velocity(0.13f) > 0f)
    }

    @Test fun curveIsPowerOnePointEight() {
        assertEquals(1800f, StickCurve.velocity(1f), 1e-3f)
        assertEquals(1800f, StickCurve.velocity(1.4f), 1e-3f) // clamped
        val d = 0.56f
        assertEquals(1800f * ((d - 0.12f) / 0.88f).pow(1.8f), StickCurve.velocity(d), 1e-3f)
        assertEquals(1800f * 0.5f.pow(1.8f), StickCurve.velocity(0.56f), 1e-2f)
    }

    @Test fun pointerSpeedScalesVMax() {
        assertEquals(3600f, StickCurve.velocity(1f, 2f), 1e-3f)
        assertEquals(450f, StickCurve.velocity(1f, 0.25f), 1e-3f)
    }

    @Test fun driverSendsVelocityTimesTimeAndKeepsFractions() {
        val s = StickDriver()
        // full right for one second at 120 Hz = 1800 px
        var x = 0; var y = 0
        repeat(120) { val (dx, dy) = s.tick(1f, 0f, 1f / 120); x += dx; y += dy }
        assertEquals(1800f, x.toFloat(), 1f)
        assertEquals(0, y)
        // a slow drift (< 1 px per tick) still accumulates
        val slow = StickDriver(); var sx = 0
        val v = StickCurve.velocity(0.2f) / 120
        assertTrue(v < 1f)
        repeat(120) { sx += slow.tick(0f, 0.2f, 1f / 120).second }
        assertEquals(v * 120, sx.toFloat(), 1f)
    }

    @Test fun edgeHapticFiresOnceAndRearms() {
        val s = StickDriver()
        assertFalse(s.edge(0.97f))
        assertTrue(s.edge(0.98f))
        assertFalse(s.edge(1f))
        assertFalse(s.edge(0.92f))
        assertFalse(s.edge(0.99f))
        assertFalse(s.edge(0.89f))
        assertTrue(s.edge(0.99f))
    }

    @Test fun quickStillLiftIsTap() {
        val p = StickPress()
        p.down(0); p.move(3f)
        assertEquals(StickPress.Out.NONE, p.tick(150))
        assertEquals(StickPress.Out.TAP, p.up(199))
    }

    @Test fun slowLiftIsNothing() {
        val p = StickPress()
        p.down(0)
        assertEquals(StickPress.Out.NONE, p.up(300))
    }

    @Test fun movedPastSlopIsNotAClick() {
        val p = StickPress()
        p.down(0); p.move(4f)
        assertEquals(StickPress.Out.NONE, p.tick(500))
        assertEquals(StickPress.Out.NONE, p.up(100))
    }

    @Test fun holdIsLongPressOnce() {
        val p = StickPress()
        p.down(0)
        assertEquals(StickPress.Out.NONE, p.tick(449))
        assertEquals(StickPress.Out.LONG, p.tick(450))
        assertEquals(StickPress.Out.NONE, p.tick(600))
        assertEquals(StickPress.Out.NONE, p.up(700))
        assertFalse(p.clicking)
    }
}
