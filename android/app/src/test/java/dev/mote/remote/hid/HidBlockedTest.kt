package dev.mote.remote.hid

import org.junit.Assert.assertEquals
import org.junit.Test

class HidBlockedTest {
    @Test fun namesTheOtherMoteWhenInstalled() {  // B8
        assertEquals(HID_BLOCKED_OTHER_MOTE, hidBlockedText("dev.mote.remote.dev") { it == "dev.mote.remote" })
        assertEquals(HID_BLOCKED_OTHER_MOTE, hidBlockedText("dev.mote.remote") { it == "dev.mote.remote.dev" })
        assertEquals("Only one app can be a Bluetooth keyboard. Mote is also installed; close or uninstall it.", HID_BLOCKED_OTHER_MOTE)
    }

    @Test fun genericOtherwise() {
        assertEquals(HID_BLOCKED_GENERIC, hidBlockedText("dev.mote.remote.dev") { false })
        assertEquals(HID_BLOCKED_GENERIC, hidBlockedText("dev.mote.remote") { it == "dev.mote.remote" }) // never itself
        assertEquals(HID_BLOCKED_GENERIC, hidBlockedText("com.other") { true })
    }
}
