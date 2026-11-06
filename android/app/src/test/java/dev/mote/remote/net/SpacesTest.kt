package dev.mote.remote.net

import dev.mote.remote.hid.Mod
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class SpacesTest {
    private fun j(s: String) = JSONObject(s)

    @Test fun encoders() {
        val sub = j(Proto.spacesSub())
        assertEquals("spaces.sub", sub.getString("t")); assertTrue(sub.getBoolean("on"))
        assertEquals(false, j(Proto.spacesSub(false)).getBoolean("on"))
        val th = j(Proto.spacesThumb(3))
        assertEquals("spaces.thumb", th.getString("t")); assertEquals(3, th.getInt("i"))
        val g = j(Proto.spacesGoto(2))
        assertEquals("spaces.goto", g.getString("t")); assertEquals(2, g.getInt("i"))
    }

    @Test fun parsesSpaces() {
        val m = Proto.parse("""{"t":"spaces","count":4,"current":2,"thumbs":[3,0,7,0],"capture":"on"}""") as Msg.Spaces
        assertEquals(Msg.Spaces(4, 2, listOf(3, 0, 7, 0), "on"), m)
    }

    @Test fun spacesIsClampedAndPadded() {
        val short = Proto.parse("""{"t":"spaces","count":3,"current":9,"thumbs":[5],"capture":"denied"}""") as Msg.Spaces
        assertEquals(listOf(5, 0, 0), short.thumbs)
        assertEquals(3, short.current)
        val long = Proto.parse("""{"t":"spaces","count":2,"current":1,"thumbs":[1,2,3,4]}""") as Msg.Spaces
        assertEquals(listOf(1, 2), long.thumbs)
        assertEquals("unavailable", long.capture)
        val huge = Proto.parse("""{"t":"spaces","count":99,"current":0,"thumbs":[]}""") as Msg.Spaces
        assertEquals(16, huge.count); assertEquals(16, huge.thumbs.size)
        val neg = Proto.parse("""{"t":"spaces","count":-1,"current":-5}""") as Msg.Spaces
        assertEquals(0, neg.count); assertEquals(0, neg.current); assertEquals(emptyList<Int>(), neg.thumbs)
    }

    @Test fun parsesThumb() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3)
        val b64 = Base64.getEncoder().encodeToString(bytes)
        val m = Proto.parse("""{"t":"spaces.thumb","i":3,"v":7,"w":320,"h":200,"mime":"image/jpeg","data":"$b64"}""") as Msg.SpacesThumb
        assertEquals(3, m.i); assertEquals(7, m.v); assertEquals(320, m.w); assertEquals(200, m.h)
        assertArrayEquals(bytes, m.jpeg)
    }

    @Test fun malformedThumbIsDropped() {
        assertNull(Proto.parse("""{"t":"spaces.thumb","i":3,"v":7,"data":"%%% not base64"}"""))
        assertNull(Proto.parse("""{"t":"spaces.thumb","i":3,"v":7}"""))
        assertNull(Proto.parse("""{"t":"spaces.thumb","i":0,"v":7,"data":"AAAA"}"""))
        assertNull(Proto.parse("""{"t":"spaces.thumb","i":"x","v":7,"data":"AAAA"}"""))
    }

    @Test fun navUsesCtrlNumberWhenEnabled() {
        assertEquals(listOf("3" to Mod.CTRL), SpacesNav.keys(3, 1, 4, ctrlNumberShortcuts = true))
        // Desktop 10+ has no ⌃N shortcut: steps instead.
        assertEquals(List(2) { "right" to Mod.CTRL }, SpacesNav.keys(12, 10, 12, ctrlNumberShortcuts = true))
    }

    @Test fun navStepsWithCtrlArrows() {
        assertEquals(List(2) { "right" to Mod.CTRL }, SpacesNav.keys(3, 1, 4, false))
        assertEquals(List(3) { "left" to Mod.CTRL }, SpacesNav.keys(1, 4, 4, false))
        assertEquals(emptyList<Pair<String, Int>>(), SpacesNav.keys(2, 2, 4, false))
    }

    @Test fun navClampsTarget() {
        assertEquals(List(3) { "right" to Mod.CTRL }, SpacesNav.keys(9, 1, 4, false))
        assertEquals(List(1) { "left" to Mod.CTRL }, SpacesNav.keys(0, 2, 4, false))
        assertEquals(listOf("4" to Mod.CTRL), SpacesNav.keys(7, 1, 4, true))
    }
}
