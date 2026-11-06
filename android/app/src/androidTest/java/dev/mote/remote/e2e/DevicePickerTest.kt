package dev.mote.remote.e2e

import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.mote.remote.data.Prefs
import dev.mote.remote.hid.Hid
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DevicePickerTest {
    @get:Rule val rule = MoteRule(state = unlinked)
    private val c get() = rule.compose

    private fun exists(d: String) = c.onAllNodes(hasContentDescription(d)).fetchSemanticsNodes().isNotEmpty()
    private fun awaitDesc(d: String, present: Boolean = true) = c.waitUntil(5_000) { exists(d) == present }

    @Test
    fun listFollowsBondAndAdapterChangesLive() {
        c.desc("Device: Choose a device. Tap to choose").performClick()
        c.onNodeWithText("Devices").assertExists()
        c.desc("Use Test Mac").assertExists()
        c.desc("Use Studio iMac").assertDoesNotExist()

        rule.adapter.update { it.copy(hosts = listOf(MAC, IMAC)) } // bond added
        awaitDesc("Use Studio iMac")

        rule.adapter.update { it.copy(hosts = listOf(IMAC)) } // bond removed
        awaitDesc("Use Test Mac", present = false)

        rule.adapter.update { it.copy(hosts = emptyList()) }
        c.waitUntil(5_000) { c.onAllNodes(hasText("No paired devices yet.")).fetchSemanticsNodes().isNotEmpty() }

        rule.adapter.update { it.copy(enabled = false) } // adapter off: gate replaces the remote
        c.waitUntil(5_000) { c.onAllNodes(hasText("Turn on Bluetooth")).fetchSemanticsNodes().isNotEmpty() }
        rule.adapter.update { it.copy(enabled = true, hosts = listOf(MAC)) }
        awaitDesc("Device: Choose a device. Tap to choose")
    }

    @Test
    fun selectionIsPersisted() {
        rule.adapter.update { it.copy(hosts = listOf(MAC, IMAC)) }
        c.desc("Device: Choose a device. Tap to choose").performClick()
        awaitDesc("Use Studio iMac")
        c.desc("Use Studio iMac").performClick()
        awaitDesc("Device: Studio iMac. Tap to choose")
        assertEquals(IMAC, Hid.state.value.selected)

        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        c.waitUntil(5_000) { runBlocking { Prefs.selectedHost(ctx).first() } == IMAC.address }

        // survives activity recreation; the sheet marks it as the selected (searching) host
        c.activityRule.scenario.recreate()
        awaitDesc("Device: Studio iMac. Tap to choose")
        c.desc("Device: Studio iMac. Tap to choose").performClick()
        c.onNodeWithText("Searching…").assertExists()
        c.onNodeWithText("Paired").assertExists() // the other host
    }
}
