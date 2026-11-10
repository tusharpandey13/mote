package dev.mote.remote.labs.screen

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/** Labs › Live screen: messages, tap -> normalised point through letterboxing / zoom / pan, double taps. */
class ScreenTest {
    private fun near(a: Float, b: Float) = assertEquals(b, a, 0.001f)

    @Test fun messagesAreClampedAndShaped() {
        val s = JSONObject(ScreenProto.start(w = 4000, fps = 60, credits = 9))
        assertEquals("labs.screen.start", s.getString("t"))
        assertEquals(960, s.getInt("w")); assertEquals(15, s.getInt("fps")); assertEquals(3, s.getInt("credits"))
        val c = JSONObject(ScreenProto.click(1.4, -2.0, "right", 5, "fake-screen"))
        assertEquals(1.0, c.getDouble("x"), 0.0); assertEquals(0.0, c.getDouble("y"), 0.0)
        assertEquals("right", c.getString("button")); assertEquals(2, c.getInt("count")); assertEquals("fake-screen", c.getString("display"))
        assertTrue(JSONObject(ScreenProto.click(0.5, 0.5)).has("x"))
        assertEquals(7, JSONObject(ScreenProto.ack(7)).getInt("seq"))
    }

    @Test fun parsesFramesStartedStoppedAndErrors() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3)
        val o = JSONObject().put("t", "labs.screen.frame").put("seq", 4).put("display", "1").put("w", 960).put("h", 600)
            .put("data", Base64.getEncoder().encodeToString(jpeg)).put("cx", 1.7).put("cy", 0.25).put("fps", 8.5).put("rtt", 41).put("ts", 123L)
        val f = (ScreenProto.parse(o) as ScreenProto.FrameIn).f
        assertEquals(4, f.seq); assertEquals(960, f.w); assertEquals(41, f.rttMs)
        assertEquals(1f, f.cx); assertEquals(0.25f, f.cy)
        assertTrue(f.jpeg.contentEquals(jpeg))
        assertNull("bad base64", ScreenProto.parse(JSONObject(o.toString()).put("data", "!!")))
        assertNull("no seq", ScreenProto.parse(JSONObject(o.toString()).apply { remove("seq") }))
        val st = (ScreenProto.parse(JSONObject().put("t", "labs.screen.started").put("display", "1").put("w", 960).put("h", 600).put("encrypted", true)) as ScreenProto.StartedIn).s
        assertTrue(st.encrypted)
        assertEquals(ScreenProto.StoppedIn("paused"), ScreenProto.parse(JSONObject().put("t", "labs.screen.stopped").put("reason", "paused")))
        assertEquals(ScreenProto.ErrIn("labs-off", "x"), ScreenProto.parse(JSONObject().put("t", "err").put("code", "labs-off").put("msg", "x").put("ref", "labs.screen.start")))
        assertNull("another lab's error", ScreenProto.parse(JSONObject().put("t", "err").put("code", "bad").put("ref", "labs.other.x")))
        assertNull(ScreenProto.explain("stop"))
        assertNotNull(ScreenProto.explain("labs-off"))
    }

    // A 1000x500 box showing a 960x600 frame: fitted to 800x500, letterboxed 100 px left and right.
    private val bw = 1000f; private val bh = 500f; private val iw = 960f; private val ih = 600f

    @Test fun tapMapsThroughTheLetterbox() {
        val (x, y) = ViewMath.toNorm(500f, 250f, bw, bh, iw, ih, 1f, 0f, 0f)!!
        near(x, 0.5f); near(y, 0.5f)
        val (x0, y0) = ViewMath.toNorm(100f, 0f, bw, bh, iw, ih, 1f, 0f, 0f)!!
        near(x0, 0f); near(y0, 0f)
        assertNull("on the bar, not the image", ViewMath.toNorm(50f, 250f, bw, bh, iw, ih, 1f, 0f, 0f))
    }

    @Test fun tapMapsThroughZoomAndPan() {
        // 2x around the centre: the box centre is still the image centre; the box's left edge of the
        // image area (x=300) is now the image's left quarter... at 2x, box x 300 -> image x 400 -> 0.375.
        val (x, _) = ViewMath.toNorm(300f, 250f, bw, bh, iw, ih, 2f, 0f, 0f)!!
        near(x, 0.375f)
        // Panned right by 200 px: what was at box 300 is now at box 500.
        val (xp, _) = ViewMath.toNorm(500f, 250f, bw, bh, iw, ih, 2f, 200f, 0f)!!
        near(xp, 0.375f)
        // fromNorm is the inverse (used for the cursor overlay).
        val (px, py) = ViewMath.fromNorm(0.2f, 0.9f, bw, bh, iw, ih, 3f, -120f, 40f)
        val (nx, ny) = ViewMath.toNorm(px, py, bw, bh, iw, ih, 3f, -120f, 40f)!!
        near(nx, 0.2f); near(ny, 0.9f)
    }

    @Test fun panStaysWithinTheZoomedImage() {
        assertEquals(0f to 0f, ViewMath.clampPan(300f, -300f, 1f, bw, bh, iw, ih))
        // 2x: the image is 1600x1000 in a 1000x500 box, so it can move 300 px sideways and 250 px up/down.
        assertEquals(300f to -250f, ViewMath.clampPan(900f, -900f, 2f, bw, bh, iw, ih))
    }

    @Test fun secondTapNearbyIsADoubleClickAtTheFirstPoint() {
        val t = TapCounter(windowMs = 300, slop = 0.03f)
        assertEquals(Triple(1, 0.5f, 0.5f), t.tap(0.5f, 0.5f, 1000))
        assertEquals(Triple(2, 0.5f, 0.5f), t.tap(0.51f, 0.49f, 1200))
        assertEquals("a third tap starts over", 1, t.tap(0.5f, 0.5f, 1300).first)
        assertEquals("too late", 1, t.tap(0.5f, 0.5f, 1700).first)
        assertEquals("too far", 1, t.tap(0.7f, 0.5f, 1800).first)
    }

    @Test fun noStreamOnPlainLanBeforeTheWarningPreferenceLoads() {
        assertTrue(!mayStream(plainLan = true, warned = null))
        assertTrue(!mayStream(plainLan = true, warned = false))
        assertTrue(mayStream(plainLan = true, warned = true))
        assertTrue(mayStream(plainLan = false, warned = null))
    }
}
