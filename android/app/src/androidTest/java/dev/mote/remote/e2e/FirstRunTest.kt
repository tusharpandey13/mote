package dev.mote.remote.e2e

import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.mote.remote.hid.Bt
import dev.mote.remote.net.Net
import kotlinx.coroutines.flow.update
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The first-run gate's second path: Wi-Fi with Mote Helper, no Bluetooth permission needed. */
@RunWith(AndroidJUnit4::class)
class WifiGateTest {
    @get:Rule val rule = MoteRule(grantPermissions = false, linked = false)
    private val c get() = rule.compose
    private val wifi = "Use Wi-Fi with Mote Helper"

    private fun shown(text: String) = c.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()
    private fun remoteShown() = c.onAllNodes(hasContentDescription("Play or pause")).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun choosingWifiSkipsTheGateAndOpensAddMac() {
        c.onNodeWithText("How do you want to connect?").assertExists()
        c.desc(wifi).performClick()
        c.waitUntil(5_000) { shown("Add Mac over Wi-Fi") } // host sheet, on the add page
        assertTrue(Net.wifiChosen.value)
        assertFalse("no permission was asked for", Bt.permitted(InstrumentationRegistry.getInstrumentation().targetContext))

        // Bluetooth stays reachable from the sheet
        c.desc("Back").performClick()
        c.desc("Set up Bluetooth").assertExists()
        rule.device.pressBack()
        c.waitUntil(5_000) { remoteShown() }

        // the choice sticks: no gate after recreation
        c.activityRule.scenario.recreate()
        c.waitUntil(5_000) { remoteShown() }
        c.onNodeWithText("How do you want to connect?").assertDoesNotExist()
    }

    @Test
    fun wifiIsThePrimaryPathWithoutBluetooth() {
        rule.adapter.update { it.copy(supported = false) }
        c.waitUntil(5_000) { shown("This phone has no Bluetooth adapter Mote can use.") }
        c.desc("Connect with Bluetooth").assertDoesNotExist()
        c.desc(wifi).assertExists()
    }
}
