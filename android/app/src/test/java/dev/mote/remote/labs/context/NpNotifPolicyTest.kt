package dev.mote.remote.labs.context

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** L0: when the now-playing notification shows, and with what. Pure; the Android side is covered on the emulator. */
class NpNotifPolicyTest {
    private fun state(playing: Boolean = true, duration: Int = 200, seek: String = """["mediaremote"]""", title: String = "Song", art: String? = null): NpState {
        val o = JSONObject(
            """{"t":"labs.np","enabled":true,"source":"mediaremote","seek":$seek,"now":1000,
               "np":{"title":"$title","artist":"Band","app":"Music","duration":$duration,"elapsed":30,"rate":${if (playing) 1 else 0},"at":1000
               ${if (art != null) ",\"art\":\"$art\"" else ""}}}""",
        )
        return NpReducer.reduce(NpState(), NpEvent.Update(o, 5_000)) { byteArrayOf(1) }
    }

    private fun plan(st: NpState, lab: Boolean = true, helper: Boolean = true, linked: Boolean = true, allowed: Boolean = true, now: Long = 5_000) =
        NpNotifPolicy.plan(st, lab, helper, linked, allowed, now)

    @Test fun showsTitleArtistPositionAndSeek() {
        val p = plan(state(art = "AAAA")) as NpNotifPlan.Show
        assertEquals("Song", p.title); assertEquals("Band", p.artist)
        assertTrue(p.playing && p.canSeek)
        assertEquals(200_000, p.durationMs); assertEquals(30_000, p.positionMs)
        assertEquals("AAAA", p.artKey)
        assertEquals(32_000, (plan(state(), now = 7_000) as NpNotifPlan.Show).positionMs) // keeps moving
    }

    @Test fun pausedStaysAndStopsMoving() {
        val p = plan(state(playing = false), now = 60_000) as NpNotifPlan.Show
        assertFalse(p.playing); assertEquals(30_000, p.positionMs)
    }

    @Test fun noSeekWithoutDurationOrSeekPath() {
        assertFalse((plan(state(duration = 0)) as NpNotifPlan.Show).canSeek)
        assertFalse((plan(state(seek = "[]")) as NpNotifPlan.Show).canSeek)
    }

    @Test fun hiddenReasonsInPriorityOrder() {
        val playing = state()
        assertEquals(NpNotifPlan.Hide("lab-off"), plan(playing, lab = false, helper = false))
        assertEquals(NpNotifPlan.Hide("no-helper"), plan(playing, helper = false, linked = true)) // Bluetooth only: none, by decision
        assertEquals(NpNotifPlan.Hide("disconnected"), plan(playing, linked = false))
        assertEquals(NpNotifPlan.Hide("nothing-playing"), plan(NpState(enabled = true)))
        assertEquals(NpNotifPlan.Hide("nothing-playing"), plan(NpReducer.reduce(playing, NpEvent.Reset)))
        assertEquals(NpNotifPlan.Hide("no-permission"), plan(playing, allowed = false))
        assertEquals(NpNotifPlan.Hide("nothing-playing"), plan(NpState(), allowed = false)) // blocked only when it would show
    }

    @Test fun sameContentIgnoresPosition() {
        val a = plan(state()) as NpNotifPlan.Show
        assertTrue(a.sameContent(plan(state(), now = 9_000) as NpNotifPlan.Show))
        assertFalse(a.sameContent(plan(state(title = "Other")) as NpNotifPlan.Show))
        assertFalse(a.sameContent(plan(state(playing = false)) as NpNotifPlan.Show))
    }

    @Test fun describeSaysWhy() {
        assertEquals("shown" to false, NpNotifPolicy.describe(plan(state())))
        val (t, bad) = NpNotifPolicy.describe(NpNotifPlan.Hide("no-permission"))
        assertTrue(bad && "not allowed" in t)
        assertTrue("Bluetooth" in NpNotifPolicy.describe(NpNotifPlan.Hide("no-helper")).first)
    }
}
