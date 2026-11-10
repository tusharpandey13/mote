package dev.mote.remote.e2e

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.mote.remote.diag.Events
import dev.mote.remote.labs.Labs
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Settings > Diagnostics and the inline Labs blockers, over the Bluetooth fake (no Mote Helper link).
 * Pin to an emulator: ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDevDebugAndroidTest.
 */
@RunWith(AndroidJUnit4::class)
class DiagnosticsTest {
    @get:Rule val rule = MoteRule()
    private val c get() = rule.compose
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun openSettings() {
        c.desc("Menu").performClick()
        c.onNode(hasText("Settings") and hasClickAction()).performClick()
        c.awaitDesc("Pointer speed")
    }

    @Test fun diagnosticsShowsPhoneStateAndAsksToUpdateNoHelper() {
        openSettings()
        c.onNodeWithText("Why isn't this working").performScrollTo().performClick()
        c.onNodeWithText("Diagnostics").assertExists()
        c.onNodeWithText("This phone".uppercase(), ignoreCase = true).assertExists()
        c.onNodeWithText("Bluetooth (connected)").performScrollTo().assertExists()
        // Bluetooth has no helper to ask: the Mac block says why instead of staying blank
        c.onNodeWithText("Not connected over Wi-Fi or Tailscale, so the Mac cannot be asked").performScrollTo().assertExists()
        c.onNodeWithText("Share log").assertExists()
        assertTrue("screen shown is logged", Events.snapshot().any { it.area == "ui" && it.event == "screen" && it.field("screen") == "diagnostics" })
    }

    @Test fun smartRemoteOverBluetoothSaysWhy() {
        runBlocking { Labs.set(ctx, Labs.SMART_REMOTE, true) }
        rule.awaitLinkState()
        c.waitUntil(8_000) { c.onAllNodes(hasText("Needs Wi-Fi or Tailscale; connected over Bluetooth", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        assertTrue("blocker is logged", Events.snapshot().any { it.area == "labs" && it.event == "blocked" && it.field("code") == "bluetooth-only" })
    }
}
