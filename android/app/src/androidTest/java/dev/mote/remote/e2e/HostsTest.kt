package dev.mote.remote.e2e

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.mote.remote.data.Prefs
import dev.mote.remote.hid.Hid
import dev.mote.remote.net.Net
import dev.mote.remote.net.Selection
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The mixed host picker (ui/HostsUi.kt): pill + sheet, Bluetooth hosts from the (fake) adapter. */
@RunWith(AndroidJUnit4::class)
class HostsTest {
    @get:Rule val rule = MoteRule(linked = false)
    private val c get() = rule.compose
    private val noHost = "Device: Choose a device. Tap to choose"

    private fun shown(text: String) = c.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun listFollowsBondAndAdapterChangesLive() {
        c.desc(noHost).performClick()
        c.onNodeWithText("Devices").assertExists()
        c.desc("Use Test Mac").assertExists()
        c.desc("Use Studio iMac").assertDoesNotExist()

        rule.adapter.update { it.copy(hosts = listOf(MAC, IMAC)) } // bond added
        c.awaitDesc("Use Studio iMac")

        rule.adapter.update { it.copy(hosts = listOf(IMAC)) } // bond removed
        c.awaitDesc("Use Test Mac", present = false)

        rule.adapter.update { it.copy(hosts = emptyList()) }
        c.awaitDesc("Use Studio iMac", present = false)

        rule.adapter.update { it.copy(enabled = false) } // adapter off: gate replaces the remote
        c.waitUntil(5_000) { shown("Turn on Bluetooth") }
        rule.adapter.update { it.copy(enabled = true, hosts = listOf(MAC)) }
        c.awaitDesc(noHost)
    }

    @Test
    fun bluetoothSelectionIsPersisted() {
        rule.adapter.update { it.copy(hosts = listOf(MAC, IMAC)) }
        c.desc(noHost).performClick()
        c.awaitDesc("Use Studio iMac")
        c.desc("Use Studio iMac").performClick()
        c.awaitDesc("Device: Studio iMac via BT. Tap to choose")
        c.waitUntil(5_000) { Hid.state.value.selected == IMAC }
        assertEquals(Selection.Bt(IMAC.address), Net.selection.value)

        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        c.waitUntil(5_000) { runBlocking { Prefs.selectedHost(ctx).first() } == IMAC.address }

        // survives activity recreation; the sheet marks it as the selected (searching) host
        c.activityRule.scenario.recreate()
        c.awaitDesc("Device: Studio iMac via BT. Tap to choose")
        c.desc("Device: Studio iMac via BT. Tap to choose").performClick()
        c.onNodeWithText("Searching…").assertExists()
        c.onNodeWithText("Paired").assertExists() // the other host
    }

    @Test
    fun sheetOffersWifiAndAddressFlows() {
        c.desc(noHost).performClick()
        c.desc("Add Mac over Wi-Fi").performClick()
        c.onNodeWithText("On the Mac, open the Mote Helper menu", substring = true).assertExists()
        c.desc("Back").performClick()
        c.desc("Enter address").performClick()
        c.onNodeWithText("For Tailscale, use the Mac's MagicDNS name", substring = true).assertExists()
        c.desc("Connect").performClick() // empty address: validation, no network
        c.onNodeWithText("Enter a name like", substring = true).assertExists()
    }
}
