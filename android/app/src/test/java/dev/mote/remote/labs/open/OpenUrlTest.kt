package dev.mote.remote.labs.open

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenUrlTest {
    @Test fun acceptsHttpAndHttpsAndBareDomains() {
        assertEquals("https://www.netflix.com/browse", OpenUrl.normalize("https://www.netflix.com/browse"))
        assertEquals("http://example.com", OpenUrl.normalize("  http://example.com \n"))
        assertEquals("HTTPS://Example.com/x?y=1#z", OpenUrl.normalize("HTTPS://Example.com/x?y=1#z"))
        assertEquals("https://netflix.com", OpenUrl.normalize("netflix.com"))
        assertEquals("https://www.twitch.tv/somechannel", OpenUrl.normalize("www.twitch.tv/somechannel"))
        assertEquals("https://example.com:8443/a", OpenUrl.normalize("example.com:8443/a"))
    }

    @Test fun refusesEverythingElse() {
        for (bad in listOf(
            "javascript:alert(1)", "JavaScript:alert(1)", " javascript:alert(document.cookie)", "file:///etc/passwd", "file://host/x",
            "data:text/html,<script>alert(1)</script>", "intent://scan/#Intent;scheme=zxing;end", "content://media/1", "ftp://example.com",
            "mote://pair?code=1", "about:blank", "chrome://settings", "vbscript:x", "blob:https://x/1",
            "https://user:pw@example.com", "https://user@example.com", "https://", "http:///path", "https:example.com",
            "https://exa mple.com", "https://example.com/a\nb", "https://example.com/\u0000", "https://example.com/\ta",
            "localhost:8080", "hello", "", "   ", "https://" + "a".repeat(OpenUrl.MAX) + ".com",
        )) assertNull("must refuse ${bad.take(40)}", OpenUrl.normalize(bad))
    }

    @Test fun classifiesLinksSearchesAndRefusals() {
        assertEquals(OpenUrl.Input.Link("https://youtu.be/abc"), OpenUrl.classify("https://youtu.be/abc"))
        assertEquals(OpenUrl.Input.Search("lofi beats"), OpenUrl.classify(" lofi beats "))
        assertEquals(OpenUrl.Input.Search("what is 2:30 in seconds"), OpenUrl.classify("what is 2:30 in seconds"))
        assertNull(OpenUrl.classify("javascript:alert(1)"))
        assertNull(OpenUrl.classify("file:///etc/passwd"))
        assertNull(OpenUrl.classify("  "))
    }

    @Test fun youTubeDetectionSearchAndMode() {
        assertTrue(OpenUrl.isYouTube("https://www.youtube.com/watch?v=x"))
        assertTrue(OpenUrl.isYouTube("https://youtu.be/x"))
        assertTrue(OpenUrl.isYouTube("https://m.youtube.com/"))
        assertFalse(OpenUrl.isYouTube("https://notyoutube.com/"))
        assertFalse(OpenUrl.isYouTube("https://youtube.com.evil.example/"))
        assertEquals("https://www.youtube.com/results?search_query=lofi%20beats%20%26%20rain", OpenUrl.youTubeSearch(" lofi beats & rain"))
        assertEquals("tv", OpenUrl.modeFor("https://youtu.be/x"))
        assertEquals("browser", OpenUrl.modeFor("https://www.netflix.com/"))
        assertEquals("netflix.com", OpenUrl.host("https://www.netflix.com/browse"))
    }

    @Test fun shareIntentTextYieldsTheFirstSafeLink() {
        assertEquals("https://youtu.be/dQw4w9WgXcQ", OpenUrl.fromShare("Check this out https://youtu.be/dQw4w9WgXcQ"))
        assertEquals("https://www.netflix.com/title/80057281", OpenUrl.fromShare("Watch on Netflix: https://www.netflix.com/title/80057281."))
        assertEquals("https://example.com/a", OpenUrl.fromShare("(https://example.com/a)"))
        assertEquals("https://example.com", OpenUrl.fromShare("javascript:alert(1) https://example.com"))
        assertEquals("https://twitch.tv/x", OpenUrl.fromShare("no link here", "https://twitch.tv/x"))
        assertEquals("https://open.spotify.com/track/1", OpenUrl.fromShare("https://open.spotify.com/track/1"))
        assertNull(OpenUrl.fromShare("javascript:alert(1)"))
        assertNull(OpenUrl.fromShare("file:///sdcard/x.html"))
        assertNull(OpenUrl.fromShare("intent://x#Intent;end"))
        assertNull(OpenUrl.fromShare("just some text"))
        assertNull(OpenUrl.fromShare(null, null))
        assertNull(OpenUrl.fromShare("https://user:pw@example.com/"))
    }

    @Test fun protoBuildersValidate() {
        val m = JSONObject(OpenTvProto.openUrl("netflix.com", "browser", fullscreen = true)!!)
        assertEquals("labs.open.url", m.getString("t"))
        assertEquals("https://netflix.com", m.getString("url"))
        assertTrue(m.getBoolean("fullscreen"))
        assertFalse(JSONObject(OpenTvProto.openUrl("https://youtu.be/x", "tv", fullscreen = true)!!).has("fullscreen"))
        assertNull(OpenTvProto.openUrl("javascript:alert(1)", "browser"))
        assertNull(OpenTvProto.openUrl("https://example.com", "sideways"))
        assertNull(OpenTvProto.tvKey("launch"))
        assertEquals("up", JSONObject(OpenTvProto.tvKey("up")!!).getString("k"))
        assertNull(OpenTvProto.action("rm"))
        val pins = JSONObject(OpenTvProto.tvOpen(Sites.defaultPins)).getJSONArray("pins")
        assertEquals(6, pins.length())
    }

    @Test fun parsesTvStateAndLabsErrors() {
        val s = OpenTvProto.parseState(JSONObject("""{"t":"labs.tv.state","enabled":true,"on":true,"page":"youtube","url":"https://www.youtube.com/tv#/watch?v=abc"}"""))
        assertEquals(TvState(true, true, "youtube", "https://www.youtube.com/tv#/watch?v=abc"), s)
        assertEquals(TvState(false, false, "home", null), OpenTvProto.parseState(JSONObject("""{"t":"labs.tv.state"}""")))
        // a hostile url in the state is dropped, never shown or reused
        assertNull(OpenTvProto.parseState(JSONObject("""{"t":"labs.tv.state","on":true,"url":"javascript:alert(1)"}"""))!!.url)
        assertNull(OpenTvProto.parseState(JSONObject("""{"t":"spaces"}""")))
        assertTrue(OpenTvProto.errorText(JSONObject("""{"t":"err","code":"disabled","ref":"labs.open.url"}"""))!!.contains("Labs"))
        assertNull(OpenTvProto.errorText(JSONObject("""{"t":"err","code":"bad","ref":"key"}""")))
    }
}
