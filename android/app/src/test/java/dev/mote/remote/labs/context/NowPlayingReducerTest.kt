package dev.mote.remote.labs.context

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class NowPlayingReducerTest {
    private var decodes = 0
    private val decode: (String) -> ByteArray? = { decodes++; byteArrayOf(1, 2, 3) }

    private fun np(elapsed: Double, rate: Double = 1.0, at: Long = 1_000_000, now: Long = 1_000_000, art: String? = null, extra: String = "") =
        JSONObject(
            """{"t":"labs.np","enabled":true,"source":"mediaremote","seek":["mediaremote","keys"],"now":$now,
               "np":{"title":"Song","artist":"Band","app":"Music","bundle":"com.apple.Music","duration":200,"elapsed":$elapsed,"rate":$rate,"at":$at
               ${if (art != null) ",\"art\":\"$art\"" else ""}$extra}}""",
        )

    private fun reduce(s: NpState, e: NpEvent) = NpReducer.reduce(s, e, decode)

    @Test fun extrapolatesFromTheMacsTimestamps() {
        // elapsed 10 s was true 2 s before the Mac sent the message; we receive it at phone time 50_000.
        val s = reduce(NpState(), NpEvent.Update(np(10.0, at = 1_000_000, now = 1_002_000), recvMs = 50_000))
        val p = s.np!!
        assertTrue(s.enabled && s.canScrub)
        assertEquals(12.0, p.position(50_000), 1e-6)
        assertEquals(15.0, p.position(53_000), 1e-6)
        assertEquals(200.0, p.position(10_000_000), 1e-6) // clamped to the duration
        assertEquals(listOf("mediaremote", "keys"), s.seek)
    }

    @Test fun pausedDoesNotMove() {
        val s = reduce(NpState(), NpEvent.Update(np(30.0, rate = 0.0), 0))
        assertFalse(s.np!!.playing)
        assertEquals(30.0, s.displayPosition(60_000)!!, 1e-6)
    }

    @Test fun pendingSeekWinsUntilConfirmedOrStale() {
        var s = reduce(NpState(), NpEvent.Update(np(10.0), 0))
        s = reduce(s, NpEvent.SeekSent(120.0, 1_000))
        assertEquals(120.0, s.displayPosition(1_500)!!, 1e-6)
        // A late report of the old position doesn't snap the scrubber back.
        s = reduce(s, NpEvent.Update(np(11.0), 1_200))
        assertEquals(120.0, s.pendingSeek!!, 1e-6)
        assertEquals(120.0, s.displayPosition(1_300)!!, 1e-6)
        // The confirming report clears it.
        s = reduce(s, NpEvent.Update(np(120.5), 1_400))
        assertNull(s.pendingSeek)
        assertEquals(120.5, s.displayPosition(1_400)!!, 1e-6)
        // An unconfirmed seek expires after PENDING_MS.
        s = reduce(s, NpEvent.SeekSent(5.0, 2_000))
        assertEquals(s.np!!.position(2_000 + NpReducer.PENDING_MS), s.displayPosition(2_000 + NpReducer.PENDING_MS)!!, 1e-6)
    }

    @Test fun keySeekMovesTheAnchorLocally() {
        var s = reduce(NpState(), NpEvent.Update(np(10.0, rate = 0.0), 0))
        s = reduce(s, NpEvent.SeekSent(40.0, 100))
        s = reduce(s, NpEvent.SeekReply("keys", 40.0, null, 200))
        assertEquals(40.0, s.np!!.position(300), 1e-6)
        // A seek the Mac couldn't place (relative keys without a position) drops the pending value.
        s = reduce(s, NpEvent.SeekReply("keys", null, "hint", 400))
        assertNull(s.pendingSeek)
        assertEquals("hint", s.note)
    }

    @Test fun artworkDecodedOnlyWhenItChanges() {
        var s = reduce(NpState(), NpEvent.Update(np(1.0, art = "AAAA"), 0))
        val first = s.np!!.art
        assertNotNull(first)
        s = reduce(s, NpEvent.Update(np(2.0, art = "AAAA"), 10))
        assertSame(first, s.np!!.art)
        s = reduce(s, NpEvent.Update(np(3.0, art = "BBBB"), 20))
        assertEquals(2, decodes)
        s = reduce(s, NpEvent.Update(np(3.0), 30))
        assertNull(s.np!!.art)
    }

    @Test fun nothingPlayingDisabledAndErrors() {
        var s = reduce(NpState(), NpEvent.Update(JSONObject("""{"t":"labs.np","enabled":true,"source":"mediaremote","np":null,"seek":["keys"]}"""), 0))
        assertTrue(s.enabled); assertNull(s.np); assertFalse(s.canScrub)
        s = reduce(s, NpEvent.Error("Accessibility permission not granted"))
        assertEquals("Accessibility permission not granted", s.note)
        s = reduce(s, NpEvent.Update(JSONObject("""{"t":"labs.np","enabled":false}"""), 0))
        assertFalse(s.enabled)
        assertEquals(NpState(), reduce(s, NpEvent.Reset))
    }

    @Test fun hostileValuesAreClamped() {
        val s = reduce(NpState(), NpEvent.Update(np(-5.0, rate = 99.0, extra = ""","title2":1"""), 0))
        assertEquals(4.0, s.np!!.rate, 1e-6)
        assertEquals(0.0, s.np!!.position(0), 1e-6)
    }

    @Test fun formatsTimes() {
        assertEquals("0:00", formatTime(-1.0))
        assertEquals("1:05", formatTime(65.4))
        assertEquals("1:02:03", formatTime(3723.0))
    }
}
