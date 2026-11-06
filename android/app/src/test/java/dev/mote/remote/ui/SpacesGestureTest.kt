package dev.mote.remote.ui

import dev.mote.remote.ui.SpacesGesture.Out
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpacesGestureTest {
    private fun g() = SpacesGesture(thresholdPx = 100f, slopPx = 10f)
    private fun steps(out: List<Out>) = out.filterIsInstance<Out.Step>()

    @Test fun belowThresholdSendsNothing() {
        val s = g()
        s.down(0f)
        assertTrue(steps(s.move(99f, 0)).isEmpty())
        assertEquals(listOf(Out.ReleaseAll), s.up(10)) // moved, so no Mission Control either
    }

    @Test fun firesOnceAtThresholdThenLocks() {
        val s = g()
        s.down(0f)
        val first = s.move(-100f, 0)
        assertEquals(listOf(Out.Step(right = false)), steps(first))
        assertTrue(first.contains(Out.Committed(right = false, queued = false)))
        // further travel, even past several thresholds or back the other way, sends nothing
        assertTrue(s.move(-400f, 1000).isEmpty())
        assertTrue(s.move(300f, 2000).isEmpty())
        assertTrue(steps(s.up(3000)).isEmpty())
    }

    @Test fun cooldownQueuesAtMostOneStep() {
        val s = g()
        s.down(0f); s.move(100f, 0); s.up(10)
        s.down(0f)
        val out = s.move(100f, 200) // inside 450 ms
        assertTrue(steps(out).isEmpty())
        assertTrue(out.contains(Out.Committed(right = true, queued = true)))
        s.up(220)
        s.down(0f); s.move(-100f, 300); s.up(310) // replaces the queued one, still only one pending
        assertTrue(s.tick(449).isEmpty())
        assertEquals(listOf(Out.Step(right = false)), s.tick(450))
        assertFalse(s.hasPending)
        assertTrue(s.tick(2000).isEmpty())
        // the released step starts a new cooldown
        assertTrue(s.inCooldown(800))
    }

    @Test fun stepAfterCooldownIsImmediate() {
        val s = g()
        s.down(0f); s.move(100f, 0); s.up(5)
        s.down(0f)
        assertEquals(listOf(Out.Step(right = true)), steps(s.move(100f, 450)))
    }

    @Test fun tapIsMissionControlAndReleases() {
        val s = g()
        s.down(0f)
        s.move(5f, 10)
        assertEquals(listOf(Out.MissionControl, Out.ReleaseAll), s.up(50))
    }

    @Test fun cancelReleasesKeysAndDropsQueue() {
        val s = g()
        s.down(0f); s.move(100f, 0); s.up(5)
        s.down(0f); s.move(100f, 100)
        assertTrue(s.hasPending)
        assertEquals(listOf(Out.ReleaseAll), s.cancel())
        assertFalse(s.hasPending)
        assertTrue(s.tick(1000).isEmpty())
    }

    @Test fun followRubberBandsAndIsBounded() {
        val s = g()
        assertEquals(45f, s.follow(100f), 0.01f)
        assertTrue(s.follow(300f) < 60.01f)
        assertEquals(-s.follow(80f), s.follow(-80f), 0.001f)
    }
}
