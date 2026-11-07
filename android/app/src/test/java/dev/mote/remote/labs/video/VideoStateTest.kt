package dev.mote.remote.labs.video

import dev.mote.remote.labs.video.VideoState.Companion.parse
import dev.mote.remote.labs.video.VideoState.Companion.position
import dev.mote.remote.labs.video.VideoState.Companion.reduce
import dev.mote.remote.labs.video.VideoState.Event
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoStateTest {
    private fun j(s: String) = JSONObject(s)
    private val eps = 1e-9

    private val playing = parse(
        j(
            """{"t":"labs.video.state","bridge":"connected","active":true,"title":"Big Buck Bunny","host":"www.youtube.com",
            "site":"youtube","duration":596.5,"time":42.1,"paused":false,"rate":1,"muted":false,"volume":0.8,"fullscreen":false,
            "live":false,"seekable":true,"hasNext":true,"hasPrev":false,"captions":"off","skips":["ad"]}""",
        ),
        1_000,
    )

    @Test fun parsesTheDocExample() {
        assertEquals("connected", playing.bridge)
        assertTrue(playing.active)
        assertEquals("Big Buck Bunny", playing.title)
        assertEquals(596.5, playing.duration!!, eps)
        assertEquals(42.1, playing.time, eps)
        assertFalse(playing.paused)
        assertEquals("off", playing.captions)
        assertEquals(listOf("ad"), playing.skips)
        assertTrue(playing.canSeek)
        assertEquals(1_000L, playing.receivedAt)
    }

    @Test fun parseClamps() {
        val s = parse(
            j(
                """{"active":true,"title":"${"x".repeat(500)}","host":"${"h".repeat(300)}","rate":16,"volume":3,"time":-5,
                "duration":100,"captions":"maybe","skips":["ad","evil","ad","intro"]}""",
            ),
            0,
        )
        assertEquals(VideoState.TITLE_MAX, s.title.length)
        assertEquals(VideoState.HOST_MAX, s.host.length)
        assertEquals(4.0, s.rate, eps)
        assertEquals(1.0, s.volume, eps)
        assertEquals(0.0, s.time, eps)
        assertEquals("none", s.captions)
        assertEquals(listOf("ad", "intro"), s.skips)

        val low = parse(j("""{"rate":0.01,"volume":-1,"time":500,"duration":100}"""), 0)
        assertEquals(0.25, low.rate, eps)
        assertEquals(0.0, low.volume, eps)
        assertEquals(100.0, low.time, eps) // time <= duration
    }

    @Test fun parseNullDurationIsLiveOrUnknown() {
        val s = parse(j("""{"active":true,"duration":null,"live":true,"seekable":true,"time":12}"""), 0)
        assertNull(s.duration)
        assertFalse(s.canSeek)
        assertNull(parse(j("""{"duration":"Infinity"}"""), 0).duration)
        assertFalse(parse(j("""{}"""), 0).active)
    }

    @Test fun receivedReplacesAndDisconnectedClears() {
        assertEquals(playing, reduce(null, Event.Received(playing)))
        val other = playing.copy(title = "Next")
        assertEquals(other, reduce(playing, Event.Received(other)))
        assertNull(reduce(playing, Event.Disconnected))
        assertNull(reduce(null, Event.Optimistic("toggle", null, 0)))
    }

    @Test fun optimisticToggleFlipsPausedAndReanchorsTime() {
        val s = reduce(playing, Event.Optimistic("toggle", null, 3_000))!!
        assertTrue(s.paused)
        assertEquals(44.1, s.time, eps) // 2 s played before the pause
        assertEquals(3_000L, s.receivedAt)
        assertFalse(reduce(s, Event.Optimistic("toggle", null, 4_000))!!.paused)
        assertTrue(reduce(playing, Event.Optimistic("pause", null, 1_000))!!.paused)
    }

    @Test fun optimisticSeekIsClampedToDuration() {
        assertEquals(596.5, reduce(playing, Event.Optimistic("seek", 9999.0, 1_000))!!.time, eps)
        assertEquals(0.0, reduce(playing, Event.Optimistic("seek", -3, 1_000))!!.time, eps)
        assertEquals(100.0, reduce(playing, Event.Optimistic("seek", 100, 1_000))!!.time, eps)
    }

    @Test fun optimisticSeekBy() {
        assertEquals(52.1, reduce(playing, Event.Optimistic("seekBy", 10, 1_000))!!.time, eps)
        assertEquals(32.1, reduce(playing, Event.Optimistic("seekBy", -10, 1_000))!!.time, eps)
        assertEquals(0.0, reduce(playing, Event.Optimistic("seekBy", -100, 1_000))!!.time, eps)
        val end = playing.copy(time = 590.0)
        assertEquals(596.5, reduce(end, Event.Optimistic("seekBy", 10, 1_000))!!.time, eps)
        // not seekable (live, Netflix without the API): unchanged
        val live = playing.copy(seekable = false)
        assertEquals(42.1, reduce(live, Event.Optimistic("seekBy", 10, 1_000))!!.time, eps)
    }

    @Test fun optimisticRateVolumeMute() {
        assertEquals(2.0, reduce(playing, Event.Optimistic("rate", 2.0, 1_000))!!.rate, eps)
        assertEquals(4.0, reduce(playing, Event.Optimistic("rate", 9, 1_000))!!.rate, eps)
        val muted = playing.copy(muted = true)
        val v = reduce(muted, Event.Optimistic("volume", 0.3, 1_000))!!
        assertEquals(0.3, v.volume, eps)
        assertFalse(v.muted) // volume also unmutes
        assertTrue(reduce(playing, Event.Optimistic("mute", null, 1_000))!!.muted)
        assertFalse(reduce(muted, Event.Optimistic("mute", null, 1_000))!!.muted)
        assertTrue(reduce(muted, Event.Optimistic("mute", true, 1_000))!!.muted)
    }

    @Test fun optimisticCaptionsToggleUnlessNone() {
        assertEquals("on", reduce(playing, Event.Optimistic("captions", null, 1_000))!!.captions)
        assertEquals("off", reduce(playing.copy(captions = "on"), Event.Optimistic("captions", null, 1_000))!!.captions)
        assertEquals("none", reduce(playing.copy(captions = "none"), Event.Optimistic("captions", null, 1_000))!!.captions)
        assertTrue(reduce(playing, Event.Optimistic("fullscreen", null, 1_000))!!.fullscreen)
        assertEquals(emptyList<String>(), reduce(playing, Event.Optimistic("skip", "ad", 1_000))!!.skips)
    }

    @Test fun positionPausedDoesNotMove() {
        val p = playing.copy(paused = true)
        assertEquals(42.1, position(p, 10_000), eps)
    }

    @Test fun positionExtrapolatesWhilePlaying() {
        assertEquals(42.1, position(playing, 1_000), eps)
        assertEquals(44.6, position(playing, 3_500), eps)
        assertEquals(42.1, position(playing, 500), eps) // clock before receipt: no negative elapsed
    }

    @Test fun positionAtRateTwo() {
        assertEquals(46.1, position(playing.copy(rate = 2.0), 3_000), eps)
    }

    @Test fun positionClampsToDuration() {
        assertEquals(596.5, position(playing, 1_000_000), eps)
    }

    @Test fun positionLiveHasNoUpperBound() {
        val live = playing.copy(duration = null, live = true, time = 10.0)
        assertEquals(1009.0, position(live, 1_000_000), eps)
    }

    @Test fun jsonBuilders() {
        assertEquals("""{"t":"labs.video.sub","on":true}""", VideoState.sub(true))
        assertEquals(false, j(VideoState.sub(false)).getBoolean("on"))
        val c = j(VideoState.cmd("seekBy", 10))
        assertEquals("labs.video.cmd", c.getString("t"))
        assertEquals("seekBy", c.getString("cmd"))
        assertEquals(10, c.getInt("value"))
        assertFalse(j(VideoState.cmd("toggle")).has("value"))
    }
}
