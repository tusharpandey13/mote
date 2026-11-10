package dev.mote.remote.e2e

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.mote.remote.pairing.HostKeyChange
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** U12 (new key / ephemeral Mac cards) and U4 (first-run notification ask). */
@RunWith(AndroidJUnit4::class)
class UxCardsTest {
    @get:Rule val rule = MoteRule(linked = true)
    private val c get() = rule.compose
    private fun shown(text: String) = c.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()

    @After fun clear() { HostKeyChange.dismiss() }

    @Test fun newKeyAsksToTrust() {
        HostKeyChange.report("0123456789abcdef", "Studio")
        c.waitUntil(5_000) { shown("This Mac has a new key. Trust it?") }
        assertTrue(shown("Studio is using a different Mote Helper key"))
        c.onNodeWithText("Not now").performClick()
        c.waitUntil(5_000) { !shown("This Mac has a new key") }
    }

    @Test fun ephemeralMacSaysSoAndKeepsNothing() {
        HostKeyChange.reportEphemeral("0123456789abcdef", "Studio")
        c.waitUntil(5_000) { shown("This Mac can't save pairings") }
        assertTrue(shown("Keychain unavailable"))
        c.onNodeWithText("OK").performClick()
        c.waitUntil(5_000) { !shown("This Mac can't save pairings") }
    }
}
