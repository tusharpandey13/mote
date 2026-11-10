package dev.mote.remote.labs

import dev.mote.remote.labs.context.MacContext
import dev.mote.remote.labs.context.NpState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BlockersTest {
    private val up = Blockers.Link(overHelper = true, linked = true)
    private val chrome = MacContext(enabled = true, app = "Google Chrome", browser = true, automation = "granted")
    private val npOn = NpState(enabled = true, source = "mediaremote")

    @Test fun bluetoothSaysWhy() {
        assertEquals("Needs Wi-Fi or Tailscale; connected over Bluetooth",
            Blockers.smartRemote(Blockers.Link(false, true), chrome, npOn, false)?.text)
        assertEquals("no-helper", Blockers.link(Blockers.Link(false, false))?.code)
        assertEquals("offline", Blockers.link(Blockers.Link(true, false))?.code)
        assertEquals("helper-too-old", Blockers.link(Blockers.Link(true, true, helperProtoTooOld = true))?.code)
    }

    @Test fun helperLabOffSaysWhere() {
        val b = Blockers.smartRemote(up, MacContext(), NpState(), false)
        assertEquals("helper-lab-off", b?.code)
        assertEquals("Turn on Labs › Context & now playing in the Mote Helper menu on the Mac", b?.text)
    }

    /** B3: Labs on both sides, browser frontmost, automation never granted, nothing playing known -> must say why. */
    @Test fun browserWithoutAutomationIsNotSilent() {
        assertEquals("automation-ask", Blockers.smartRemote(up, chrome.copy(automation = "ask"), npOn, false)?.code)
        assertEquals("automation-denied", Blockers.smartRemote(up, chrome.copy(automation = "denied"), npOn, false)?.code)
        assertEquals("no-signal", Blockers.smartRemote(up, chrome, NpState(enabled = true, source = "unavailable"), false)?.code)
    }

    @Test fun nothingBlocksWhenAProfileIsShown() {
        assertNull(Blockers.smartRemote(up, chrome, npOn, profileShown = true))
        assertNull(Blockers.smartRemote(up, MacContext(enabled = true, app = "Finder"), npOn, false))
    }

    @Test fun videoBridgeStates() {
        assertEquals("no-reply", Blockers.videoBridge(up, null, false)?.code)
        assertEquals("helper-lab-off", Blockers.videoBridge(up, "off", false)?.code)
        assertEquals("extension-missing", Blockers.videoBridge(up, "waiting", false)?.code)
        assertEquals("no-video", Blockers.videoBridge(up, "connected", false)?.code)
        assertNull(Blockers.videoBridge(up, "connected", true))
    }
}
