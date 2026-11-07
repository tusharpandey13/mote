package dev.mote.remote.labs.open

import org.junit.Assert.assertEquals
import org.junit.Test

class SitesTest {
    private fun s(n: Int) = Site("Site $n", "https://example.com/$n")

    @Test fun recentsAreNewestFirstDedupedAndCapped() {
        var r = emptyList<Site>()
        for (i in 1..20) r = Sites.addRecent(r, s(i))
        assertEquals(Sites.RECENTS_MAX, r.size)
        assertEquals("https://example.com/20", r.first().url)
        r = Sites.addRecent(r, Site("again", "https://example.com/15"))
        assertEquals(Sites.RECENTS_MAX, r.size)
        assertEquals(Site("again", "https://example.com/15"), r.first())
        assertEquals(1, r.count { it.url == "https://example.com/15" })
    }

    @Test fun unsafeRecentsAndPinsAreDropped() {
        assertEquals(emptyList<Site>(), Sites.addRecent(emptyList(), Site("x", "javascript:alert(1)")))
        assertEquals(Sites.defaultPins, Sites.addPin(Sites.defaultPins, Site("x", "file:///etc/passwd")))
    }

    @Test fun recentTitleFallsBackToHost() {
        assertEquals("netflix.com", Sites.addRecent(emptyList(), Site(" ", "https://www.netflix.com/browse")).single().title)
    }

    @Test fun pinsAddRemoveAndCap() {
        var p = Sites.addPin(Sites.defaultPins, Site("Plex", "app.plex.tv"))
        assertEquals(7, p.size)
        assertEquals(Site("Plex", "https://app.plex.tv"), p.last())
        assertEquals(p, Sites.addPin(p, Site("dup", "https://app.plex.tv")))
        p = Sites.removePin(p, "https://www.netflix.com/browse")
        assertEquals(6, p.size)
        var full = emptyList<Site>()
        for (i in 1..20) full = Sites.addPin(full, s(i))
        assertEquals(Sites.PINS_MAX, full.size)
    }

    @Test fun codecRoundTripsAndToleratesJunk() {
        val list = listOf(Site("A", "https://a.example"), Site("B", "https://b.example/x?y=1"))
        assertEquals(list, Sites.decode(Sites.encode(list), emptyList(), 12))
        assertEquals(Sites.defaultPins, Sites.decode(null, Sites.defaultPins, 12))
        assertEquals(Sites.defaultPins, Sites.decode("{not json", Sites.defaultPins, 12))
        val junk = """[{"title":"ok","url":"https://ok.example"},{"title":"evil","url":"javascript:alert(1)"},7,{"url":"https://ok.example"},{"title":"","url":"https://www.h.example"}]"""
        assertEquals(listOf(Site("ok", "https://ok.example"), Site("h.example", "https://www.h.example")), Sites.decode(junk, emptyList(), 12))
    }
}
