package dev.mote.remote.pairing

import dev.mote.remote.ui.SEARCHING_AFTER_MS
import dev.mote.remote.ui.searchingCardVisible
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure decisions behind U4, U12, U13 and U18. */
class UxGatesTest {
    @Test fun notificationAskOnlyOnceOnAndroid13Plus() { // U4
        assertTrue(shouldAskNotifications(33, granted = false, alreadyAsked = false))
        assertFalse(shouldAskNotifications(32, granted = false, alreadyAsked = false)) // no runtime permission before 13
        assertFalse(shouldAskNotifications(34, granted = true, alreadyAsked = false))
        assertFalse(shouldAskNotifications(34, granted = false, alreadyAsked = true)) // never nag
    }

    @Test fun deniedForGoodOpensAppSettings() { // U13
        assertTrue(needsAppSettings(permitted = false, anyRationale = false))
        assertFalse(needsAppSettings(permitted = false, anyRationale = true)) // the dialog can still be shown
        assertFalse(needsAppSettings(permitted = true, anyRationale = false))
    }

    @Test fun searchingCardAfterFifteenSeconds() { // U18
        assertEquals(15_000L, SEARCHING_AFTER_MS)
        assertFalse(searchingCardVisible(true, 14_999))
        assertTrue(searchingCardVisible(true, 15_000))
        assertFalse(searchingCardVisible(false, 60_000)) // ready, failed or idle: another card or none
    }

    @Test fun keyMismatchIsRecognised() { // U12
        assertTrue(HostKeyChange.isKeyMismatch("helper-key", "This Mac's key changed"))
        assertFalse(HostKeyChange.isKeyMismatch("helper-key", "Another Mac answered"))
        assertFalse(HostKeyChange.isKeyMismatch("revoked", "This Mac's key changed"))
    }
}
