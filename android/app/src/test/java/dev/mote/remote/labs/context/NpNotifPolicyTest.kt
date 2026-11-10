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

    private fun plan(st: NpState, on: Boolean = true, helper: Boolean = true, linked: Boolean = true, allowed: Boolean = true, now: Long = 5_000, expired: Boolean = false) =
        NpNotifPolicy.plan(st, on, helper, linked, allowed, now, expired)

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
        assertEquals(NpNotifPlan.Hide("setting-off"), plan(playing, on = false, helper = false))
        assertEquals(NpNotifPlan.Hide("disconnected"), plan(playing, linked = false))
        assertEquals(NpNotifPlan.Hide("nothing-playing"), plan(NpState(enabled = true)))
        assertEquals(NpNotifPlan.Hide("nothing-playing"), plan(NpReducer.reduce(playing, NpEvent.Reset)))
        assertEquals(NpNotifPlan.Hide("no-permission"), plan(playing, allowed = false))
        assertEquals(NpNotifPlan.Hide("nothing-playing"), plan(NpState(), allowed = false)) // blocked only when it would show
    }

    /** U1: Bluetooth only gets transport buttons, no metadata. U3: no lab involved anywhere. */
    @Test fun bluetoothOnlyIsTransportOnly() {
        assertEquals(NpNotifPlan.BtOnly, plan(state(), helper = false))
        assertEquals(NpNotifPlan.BtOnly, plan(NpState(), helper = false))
        assertEquals(NpNotifPlan.Hide("no-permission"), plan(NpState(), helper = false, allowed = false))
        assertEquals(NpNotifPlan.Hide("disconnected"), plan(NpState(), helper = false, linked = false))
        assertEquals(NpNotifPlan.Hide("setting-off"), plan(NpState(), helper = false, on = false))
    }

    /** U19: the helper says its now-playing lab is off -> a reason, not silence. */
    @Test fun helperNowPlayingOffIsAReason() {
        val off = NpReducer.reduce(NpState(), NpEvent.Update(JSONObject("""{"t":"labs.np","enabled":false}"""), 5_000)) { null }
        assertTrue(off.received)
        assertEquals(NpNotifPlan.Hide("helper-np-off"), plan(off))
        assertTrue(NpNotifPolicy.describe(NpNotifPlan.Hide("helper-np-off")).second)
    }

    /** U2: paused for PAUSED_STOP_MS stops the service and removes the notification; playing never expires. */
    @Test fun pausedTimeoutHides() {
        assertEquals(10 * 60_000L, NpNotifPolicy.PAUSED_STOP_MS)
        assertEquals(NpNotifPlan.Hide("idle-timeout"), plan(state(playing = false), expired = true))
        assertTrue(plan(state(playing = false), expired = false) is NpNotifPlan.Show)
        assertTrue(plan(state(playing = true), expired = true) is NpNotifPlan.Show)
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
        assertTrue("Bluetooth" in NpNotifPolicy.describe(NpNotifPlan.BtOnly).first)
    }
}
