package dev.mote.remote.e2e

import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import dev.mote.remote.hid.Bt
import dev.mote.remote.hid.TestSeam
import kotlinx.coroutines.flow.update
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val PERMISSION_UI = "com.android.permissioncontroller"

/** Answers every system permission dialog that appears; returns how many were answered. */
fun UiDevice.answerPermissionDialogs(allow: Boolean): Int {
    val id = if (allow) "permission_allow_button" else "permission_deny_button"
    var n = 0
    while (true) {
        val button = wait(Until.findObject(By.res(PERMISSION_UI, id)), 5_000) ?: return n
        button.click()
        n++
        waitForIdle()
    }
}

@RunWith(AndroidJUnit4::class)
class FirstRunGateTest {
    @get:Rule val rule = MoteRule(grantPermissions = false)
    private val c get() = rule.compose
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val allow = "Connect with Bluetooth"

    private fun remoteShown() = c.onAllNodes(hasContentDescription("Play or pause")).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun deniedKeepsGateThenGrantResolvesIt() {
        c.desc("Use Bluetooth instead (no helper)").performClick() // first run opens into finding the Mac
        c.onNodeWithText("How do you want to connect?").assertExists()
        assertFalse(remoteShown())

        c.desc(allow).performClick()
        assertTrue("a permission dialog was shown", rule.device.answerPermissionDialogs(allow = false) > 0)
        c.waitForIdle()
        assertFalse(Bt.permitted(ctx))
        c.onNodeWithText("How do you want to connect?").assertExists()
        assertFalse(remoteShown())

        c.desc(allow).performClick()
        assertTrue(rule.device.answerPermissionDialogs(allow = true) > 0)
        c.waitUntil(10_000) { remoteShown() }
        assertTrue(Bt.permitted(ctx))
    }
}

@RunWith(AndroidJUnit4::class)
class BluetoothGateTest {
    @get:Rule val rule = MoteRule(adapter = TestSeam.Adapter(enabled = false, hosts = listOf(MAC)))
    private val c get() = rule.compose

    private fun shown(text: String) = c.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun bluetoothOffShowsGateUntilAdapterTurnsOn() {
        c.onNodeWithText("Turn on Bluetooth").assertExists()
        c.onNodeWithText("Bluetooth is off; turn it on to continue.", substring = true).assertExists()

        rule.adapter.update { it.copy(enabled = true) }
        c.waitUntil(5_000) { c.onAllNodes(hasContentDescription("Play or pause")).fetchSemanticsNodes().isNotEmpty() }

        rule.adapter.update { it.copy(enabled = false) } // turned off while in use
        c.waitUntil(5_000) { shown("Turn on Bluetooth") }
    }

    @Test
    fun noAdapterIsUnsupported() {
        rule.adapter.update { it.copy(supported = false) }
        c.waitUntil(5_000) { shown("This phone has no Bluetooth adapter Mote can use.") }
        c.onNodeWithText("Turn on Bluetooth").assertDoesNotExist()
    }
}
