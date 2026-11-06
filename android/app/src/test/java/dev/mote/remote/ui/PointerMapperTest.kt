package dev.mote.remote.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PointerMapperTest {
    @Test fun linearAtSlowSpeeds() {
        assertEquals(PointerMapper.BASE, PointerMapper.gain(0f), 0f)
        assertEquals(PointerMapper.BASE, PointerMapper.gain(PointerMapper.SLOW), 0f)
    }

    @Test fun mildMonotonicBoostThatSaturates() {
        var last = 0f
        for (i in 0..40) {
            val g = PointerMapper.gain(i * 0.1f)
            assertTrue("gain must not decrease", g >= last)
            last = g
        }
        assertEquals(PointerMapper.BASE * PointerMapper.MAX_BOOST, PointerMapper.gain(10f), 1e-5f)
        assertTrue(PointerMapper.gain(10f) <= 1.8f * 1.0f) // never above the old base gain alone
    }

    @Test fun slowMovesAccumulateRemainders() {
        val m = PointerMapper()
        var x = 0
        // 0.5 dp every 16 ms -> 0.4 counts each at BASE 0.8: individually zero, together 4 counts
        repeat(10) { x += m.map(0.5f, 0f, 16).first }
        assertEquals(4, x)
    }

    @Test fun remainderKeepsSignAndResets() {
        val m = PointerMapper()
        var y = 0
        repeat(5) { y += m.map(0f, -0.5f, 16).second }
        assertEquals(-2, y)
        m.reset()
        assertEquals(0, m.map(0f, -0.5f, 16).second)
    }

    @Test fun clampsPerReport() {
        val m = PointerMapper(speed = 2f)
        val (x, y) = m.map(500f, -500f, 5)
        assertEquals(PointerMapper.MAX_COUNT, x)
        assertEquals(-PointerMapper.MAX_COUNT, y)
        // the clamped excess is not replayed into the next report
        assertEquals(0 to 0, m.map(0f, 0f, 16))
    }

    @Test fun speedScalesLinearly() {
        val a = PointerMapper(speed = 0.5f)
        val b = PointerMapper(speed = 2f)
        var sa = 0; var sb = 0
        repeat(20) { sa += a.map(1f, 0f, 16).first; sb += b.map(1f, 0f, 16).first }
        assertEquals(8, sa)
        assertEquals(32, sb)
    }
}
