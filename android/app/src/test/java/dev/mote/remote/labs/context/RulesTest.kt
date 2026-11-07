package dev.mote.remote.labs.context

import dev.mote.remote.hid.Key
import dev.mote.remote.hid.Mod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RulesTest {
    private fun browser(host: String?, bundle: String = "com.google.Chrome") =
        MacContext(enabled = true, app = "Chrome", bundle = bundle, host = host, browser = true)

    @Test fun netflixInAnyBrowserOrTheApp() {
        assertEquals(ProfileId.NETFLIX, ContextRules.match(browser("www.netflix.com"))?.id)
        assertEquals(ProfileId.NETFLIX, ContextRules.match(browser("netflix.com", "com.apple.Safari"))?.id)
        assertEquals(ProfileId.NETFLIX, ContextRules.match(MacContext(enabled = true, bundle = "com.netflix.Netflix"))?.id)
        val keys = ContextRules.NETFLIX.actions.map { it.key }
        assertTrue("s" in keys && "f" in keys)
    }

    @Test fun youtubeShortcuts() {
        val p = ContextRules.match(browser("m.youtube.com"))!!
        assertEquals(ProfileId.YOUTUBE, p.id)
        assertEquals(ProfileId.YOUTUBE, ContextRules.match(browser("youtu.be"))?.id)
        val next = p.actions.first { it.label == "Next" }
        assertEquals("n", next.key); assertEquals(Mod.SHIFT, next.mods)
        assertTrue(p.actions.any { it.key == "t" } && p.actions.any { it.key == "c" } && p.actions.any { it.key == "f" })
    }

    @Test fun hostMatchingIsSuffixOnDotBoundaries() {
        assertTrue(ContextRules.hostMatches("www.youtube.com", "youtube.com"))
        assertTrue(ContextRules.hostMatches("YouTube.com.", "youtube.com"))
        assertFalse(ContextRules.hostMatches("notyoutube.com", "youtube.com"))
        assertFalse(ContextRules.hostMatches("youtube.com.evil.example", "youtube.com"))
        assertNull(ContextRules.match(browser("notnetflix.com")))
    }

    @Test fun genericVideoSitesAndApps() {
        assertEquals(ProfileId.VIDEO_SITE, ContextRules.match(browser("player.vimeo.com"))?.id)
        val qt = ContextRules.match(MacContext(enabled = true, bundle = "com.apple.QuickTimePlayerX"))!!
        assertEquals(ProfileId.VIDEO_APP, qt.id)
        assertEquals(Mod.CTRL or Mod.CMD, qt.actions.first().mods)
    }

    @Test fun unknownSiteOnlyGetsMediaControlsWhenItIsPlaying() {
        assertNull(ContextRules.match(browser("mail.example.com")))
        assertEquals(ProfileId.BROWSER_MEDIA, ContextRules.match(browser("radio.example.com"), nowPlayingBundle = "com.google.Chrome")?.id)
        // Something else plays: the tab gets nothing.
        assertNull(ContextRules.match(browser("radio.example.com"), nowPlayingBundle = "com.spotify.client"))
    }

    @Test fun finderAndDisabledGetTheDefaultScreen() {
        assertNull(ContextRules.match(MacContext(enabled = true, app = "Finder", bundle = "com.apple.finder")))
        assertNull(ContextRules.match(browser("www.netflix.com").copy(enabled = false)))
        assertNull(ContextRules.match(MacContext()))
    }

    @Test fun everyActionUsesARealKey() {
        listOf(ContextRules.NETFLIX, ContextRules.YOUTUBE, ContextRules.VIDEO_SITE, ContextRules.VIDEO_APP, ContextRules.BROWSER_MEDIA)
            .flatMap { it.actions }.forEach { assertTrue("${it.key} has no usage", Key.usage(it.key) != 0) }
    }

    @Test fun autoKeyboardOpensOnRisingEdgeAndClosesOnlyWhatItOpened() {
        val text = MacContext(enabled = true, bundle = "com.apple.Notes", text = true)
        val none = text.copy(text = false)
        var ak = AutoKeyboard()
        var (n, a) = ak.next(text, allowed = true, onKeys = false)
        assertEquals(AutoKeyboard.Action.OPEN, a); assertTrue(n.opened); ak = n
        // Still focused: nothing new.
        ak.next(text, true, onKeys = true).let { (n2, a2) -> assertEquals(AutoKeyboard.Action.NONE, a2); ak = n2 }
        ak.next(none, true, onKeys = true).let { (n2, a2) -> assertEquals(AutoKeyboard.Action.CLOSE, a2); assertFalse(n2.opened); ak = n2 }
        // Not allowed (chip pressed): no open, and so no close either.
        ak.next(text, allowed = false, onKeys = false).let { (n2, a2) -> assertEquals(AutoKeyboard.Action.NONE, a2); ak = n2 }
        ak.next(none, true, false).let { (_, a2) -> assertEquals(AutoKeyboard.Action.NONE, a2) }
        // Already on Keys by hand: don't take over, and don't navigate away later.
        AutoKeyboard().next(text, true, onKeys = true).let { (n2, a2) ->
            assertEquals(AutoKeyboard.Action.NONE, a2)
            assertEquals(AutoKeyboard.Action.NONE, n2.next(none, true, true).second)
        }
    }

    @Test fun parsesContextAndDisabledState() {
        val o = org.json.JSONObject("""{"t":"labs.ctx","enabled":true,"app":"Arc","bundle":"company.thebrowser.Browser","host":"www.youtube.com","browser":true,"text":true,"secure":false,"automation":"granted","full":false}""")
        val c = SmartRemote.parseContext(o)
        assertEquals("www.youtube.com", c.host); assertTrue(c.text); assertNull(c.url)
        assertEquals(ProfileId.YOUTUBE, ContextRules.match(c)?.id)
        assertFalse(SmartRemote.parseContext(org.json.JSONObject("""{"t":"labs.ctx","enabled":false}""")).enabled)
    }
}
