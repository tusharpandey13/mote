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
        assertTrue(!g.has("display") && !th.has("display"))
        assertEquals("D-2", j(Proto.spacesGoto(2, "D-2")).getString("display"))
        assertEquals("D-2", j(Proto.spacesThumb(1, "D-2")).getString("display"))
    }

    @Test fun parsesDisplays() {
        val m = Proto.parse(
            """{"t":"spaces","count":3,"current":1,"thumbs":[4,0,0],"capture":"on","display":"B","shared":false,
               "displays":[{"id":"A","name":"Built-in","count":6,"current":2,"thumbs":[1,2,0,0,0,0]},
                           {"id":"B","name":"DELL","count":3,"current":1,"thumbs":[4,0,0]}]}""",
        ) as Msg.Spaces
        assertEquals("B", m.display)
        assertEquals(false, m.shared)
        assertEquals(listOf(Msg.SpacesDisplay("A", "Built-in", 6, 2, listOf(1, 2, 0, 0, 0, 0)), Msg.SpacesDisplay("B", "DELL", 3, 1, listOf(4, 0, 0))), m.displays)
    }

    @Test fun displaysAreClampedAndJunkDropped() {
        val m = Proto.parse(
            """{"t":"spaces","count":1,"current":1,"thumbs":[1],"shared":true,"displays":[
               {"id":"Main","name":"All displays","count":99,"current":120,"thumbs":[-3]},
               {"name":"no id","count":2}, 7, null, {"id":"","count":1}, {"id":"Main","count":2}]}""",
        ) as Msg.Spaces
        assertEquals(1, m.displays.size)
        val d = m.displays[0]
        assertEquals(16, d.count); assertEquals(16, d.current); assertEquals(0, d.thumbs[0]); assertEquals(16, d.thumbs.size)
        assertTrue(m.shared)
        assertNull(m.display)
        val many = Proto.parse("""{"t":"spaces","count":0,"displays":[${(1..20).joinToString(",") { "{\"id\":\"d$it\"}" }}]}""") as Msg.Spaces
        assertEquals(8, many.displays.size)
        // A 1.3 helper: no displays at all.
        val old = Proto.parse("""{"t":"spaces","count":4,"current":2,"thumbs":[3,0,7,0],"capture":"on"}""") as Msg.Spaces
        assertEquals(emptyList<Msg.SpacesDisplay>(), old.displays); assertNull(old.display); assertEquals(false, old.shared)
        val notList = Proto.parse("""{"t":"spaces","count":1,"displays":"x","display":5}""") as Msg.Spaces
        assertEquals(emptyList<Msg.SpacesDisplay>(), notList.displays); assertEquals("5", notList.display)
    }

    @Test fun thumbCarriesDisplay() {
        val m = Proto.parse("""{"t":"spaces.thumb","i":1,"v":2,"display":"B","data":"/9g="}""") as Msg.SpacesThumb
        assertEquals("B", m.display)
        assertNull((Proto.parse("""{"t":"spaces.thumb","i":1,"v":2,"data":"/9g="}""") as Msg.SpacesThumb).display)
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

}
