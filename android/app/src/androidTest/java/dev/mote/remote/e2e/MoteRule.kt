package dev.mote.remote.e2e

import android.Manifest
import android.os.Build
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.semantics.SemanticsActions
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import dev.mote.remote.MainActivity
import dev.mote.remote.hid.Bt
import dev.mote.remote.hid.Hid
import dev.mote.remote.hid.HidState
import dev.mote.remote.hid.Host
import dev.mote.remote.hid.Phase
import dev.mote.remote.hid.TestSeam
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

val MAC = Host("AA:BB:CC:DD:EE:01", "Test Mac", Host.Kind.LAPTOP)
val IMAC = Host("AA:BB:CC:DD:EE:02", "Studio iMac", Host.Kind.DESKTOP)

fun linked(host: Host) = HidState(phase = Phase.READY, selected = host, connected = host)
val unlinked = HidState(phase = Phase.READY)

/**
 * Launches [MainActivity] against fakes: reports go to [transport], the adapter (enabled / bonded
 * hosts) is [adapter], and the HID link state is [state]. Runtime permissions are real; they are
 * granted up front unless [grantPermissions] is false. The orchestrator clears app data between tests.
 */
class MoteRule(
    private val grantPermissions: Boolean = true,
    adapter: TestSeam.Adapter = TestSeam.Adapter(hosts = listOf(MAC)),
    private val state: HidState = linked(MAC),
) : TestRule {
    val transport = FakeTransport()
    val adapter = MutableStateFlow(adapter)
    val compose = createAndroidComposeRule<MainActivity>()
    val device: UiDevice get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    /**
     * Like [FakeTransport.awaitAtLeast], but keeps the Compose clock moving: code that suspends in
     * `delay()` on a composition scope (typing, macros) only progresses when the test clock advances.
     */
    fun awaitReports(count: Int, timeoutMs: Long = 10_000): List<Report> {
        val end = System.currentTimeMillis() + timeoutMs
        while (transport.reports.size < count && System.currentTimeMillis() < end) {
            compose.mainClock.advanceTimeBy(16)
            Thread.sleep(5)
        }
        return transport.settle()
    }

    private val seam = object : ExternalResource() {
        override fun before() {
            TestSeam.sink = transport
            TestSeam.adapter = this@MoteRule.adapter
            Hid.setStateForTest(state)
            if (grantPermissions) grantAll()
        }
    }

    override fun apply(base: Statement, description: Description): Statement =
        RuleChain.outerRule(seam).around(compose).apply(base, description)

    companion object {
        val permissions: List<String> =
            Bt.permissions.toList() + if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()

        fun grantAll() {
            val i = InstrumentationRegistry.getInstrumentation()
            permissions.forEach { i.uiAutomation.grantRuntimePermission(i.targetContext.packageName, it) }
        }
    }
}

// ---- small UI helpers ----

fun SemanticsNodeInteractionsProvider.desc(d: String): SemanticsNodeInteraction = onNodeWithContentDescription(d)

/** Real touch: down then up at the centre (Cap reacts on down, commits on up). */
fun SemanticsNodeInteraction.press(): SemanticsNodeInteraction = performTouchInput { down(center); up() }

/**
 * Accessibility click (what TalkBack does). Use for controls the soft keyboard may cover after typing:
 * performClick() injects a touch at the node's centre, which would land on the IME.
 */
fun SemanticsNodeInteraction.a11yClick(): SemanticsNodeInteraction = performSemanticsAction(SemanticsActions.OnClick)

/** Switch mode through the bottom rail. */
fun SemanticsNodeInteractionsProvider.mode(label: String) {
    onNode(hasText(label) and hasClickAction()).performClick()
}
