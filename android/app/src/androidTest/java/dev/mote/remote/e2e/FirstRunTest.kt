package dev.mote.remote.e2e

import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.mote.remote.hid.Bt
import dev.mote.remote.hid.TestSeam
import dev.mote.remote.net.Net
import kotlinx.coroutines.flow.update
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** First run: opens into finding the Mac (no permission asked); Bluetooth is the secondary path. */
@RunWith(AndroidJUnit4::class)
class FirstRunTest {
    @get:Rule val rule = MoteRule(grantPermissions = false, linked = false)
    private val c get() = rule.compose
    private val bluetooth = "Use Bluetooth instead (no helper)"

    private fun shown(text: String) = c.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()
    private fun connectCard() = c.onAllNodes(hasContentDescription("Connect to ", substring = true)).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun opensIntoFindingTheMacThenBluetoothIsSecondary() {
        c.onNodeWithText("Connect to your Mac").assertExists()
        c.onNodeWithText("How do you want to connect?").assertDoesNotExist()
        assertFalse("no permission was asked for", Bt.permitted(InstrumentationRegistry.getInstrumentation().targetContext))
        // never an open-ended wait: something to do after ~5 s
        c.waitUntil(10_000) { shown("Mote Helper not found") || shown("Turn on Wi-Fi") || connectCard() }

        c.desc(bluetooth).performClick()
        c.waitUntil(5_000) { shown("How do you want to connect?") }
        assertTrue(Net.btChosen.value)
        c.desc("Connect to your Mac instead").performClick()
        c.waitUntil(5_000) { shown("Looking for your Mac") || shown("Mote Helper not found") || shown("Turn on Wi-Fi") || connectCard() }
        c.onNodeWithText("How do you want to connect?").assertDoesNotExist()
    }

    @Test
    fun noBluetoothAdapterStillOffersTheMac() {
        rule.adapter.update { it.copy(supported = false) }
        c.desc(bluetooth).performClick()
        c.waitUntil(5_000) { shown("This phone has no Bluetooth adapter Mote can use.") }
        c.desc("Connect with Bluetooth").assertDoesNotExist()
        c.desc("Connect to your Mac").assertExists()
    }
}

/**
 * Regression for a network that drops multicast (Bonjour finds nothing): the LAN sweep alone must
 * find a Mote Helper and show "Connect to <Mac>" within a few seconds. Needs a dry-run helper on
 * the dev Mac, which the emulator reaches at 10.0.2.2 (inside its /24):
 *   MoteHelper --dry-run --headless --no-ble --memory-store --port 8795
 *   ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=dev.mote.remote.e2e.SweepDiscoveryTest \
 *     -Pandroid.testInstrumentationRunnerArguments.moteSweepPort=8795
 * Skipped without `moteSweepPort`. Read-only: it never taps Connect.
 */
@RunWith(AndroidJUnit4::class)
class SweepDiscoveryTest {
    private val args = androidx.test.platform.app.InstrumentationRegistry.getArguments()
    private val port = args.getString("moteSweepPort")?.toIntOrNull()
    private val fakeProx = args.getString("moteFakeProximity") == "true"
    @get:Rule val rule = MoteRule(grantPermissions = false, linked = false)
    private val c get() = rule.compose

    init { TestSeam.noBonjour = true; TestSeam.sweepPort = port; TestSeam.fakeProximity = fakeProx } // before the rule launches the activity
    @After fun clear() { TestSeam.noBonjour = false; TestSeam.sweepPort = null; TestSeam.fakeProximity = false }

    @Test
    fun sweepFindsTheHelperWithoutBonjour() {
        assumeTrue("pass -e moteSweepPort N with a dry-run helper on the host", port != null)
        val t0 = System.currentTimeMillis()
        c.waitUntil(4_000) { c.onAllNodes(hasContentDescription("Connect to ", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        val ms = System.currentTimeMillis() - t0
        assertTrue("found in $ms ms", ms < 4_000)
        assertFalse(c.onAllNodes(hasText("Mote Helper not found", substring = true)).fetchSemanticsNodes().isNotEmpty())
        // SP-4 / RC-3: the sweep found it, not Bonjour (the same sweep rediscover() runs after an IP change)
        val ev = dev.mote.remote.diag.Events.snapshot()
        assertTrue("discovery/sweep.start logged", ev.any { it.area == "discovery" && it.event == "sweep.start" })
        assertTrue("discovery/sweep.open logged", ev.any { it.area == "discovery" && it.event == "sweep.open" })
        assertFalse("no nsd.found without Bonjour", ev.any { it.area == "discovery" && it.event == "nsd.found" })
    }

    private fun desc(d: String) = c.onAllNodes(hasContentDescription(d, substring = true)).fetchSemanticsNodes().isNotEmpty()
    private fun text(t: String) = c.onAllNodes(hasText(t, substring = true)).fetchSemanticsNodes().isNotEmpty()

    /**
     * Regression (0.6.0 on a real phone): Android's platform Ed25519 rejected every helper signature
     * ("isn't the Mac it claims to be"), and a refused connect could surface as "Couldn't reach".
     * With `-e moteFakeProximity true` the token comes from the `--fake-proximity` helper: one tap
     * must end trusted (the Bluetooth step or the remote). Without it (no BLE on an emulator) the
     * Mac answers `far`, which must ask for Bluetooth, never claim the Mac is unreachable.
     */
    @Test
    fun oneTapConnectsOrAsksForBluetoothNeverUnreachable() {
        assumeTrue("pass -e moteSweepPort N with a dry-run --fake-proximity helper on the host", port != null)
        c.waitUntil(4_000) { desc("Connect to ") }
        c.onAllNodes(hasContentDescription("Connect to ", substring = true)).onFirst().performClick()
        c.waitUntil(15_000) {
            text("login screen") || desc("Play or pause") || text("Mote uses Bluetooth once") || text("couldn't confirm") ||
                text("Couldn't reach") || text("isn't the Mac")
        }
        assertFalse("refusal shown as unreachable", text("Couldn't reach"))
        assertFalse("helper signature rejected", text("isn't the Mac"))
        if (fakeProx) assertTrue("trusted with no Mac action", text("login screen") || desc("Play or pause"))
        else assertTrue("asks for Bluetooth proof", text("Mote uses Bluetooth once") || text("couldn't confirm"))
    }
}
