package dev.mote.remote.journeys

import android.app.Activity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.geometry.Offset
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import dev.mote.remote.e2e.Call
import dev.mote.remote.e2e.FakeTransport
import dev.mote.remote.hid.Host
import dev.mote.remote.hid.TestSeam
import dev.mote.remote.net.Caps
import dev.mote.remote.net.Net
import dev.mote.remote.net.Remote
import dev.mote.remote.net.WsStatus
import kotlinx.coroutines.flow.MutableStateFlow

/** Group C (daily, media, spaces, macros, settings) helpers. */

const val C_BT = "faked: BT HID transport (FakeTransport)"
const val C_PROX = "faked: proximity token read over the WebSocket from a --fake-proximity helper (TestSeam.fakeProximity) instead of BLE"
val C_MAC = Host("AA:BB:CC:DD:EE:01", "Test Mac", Host.Kind.LAPTOP)

fun cPill(name: String, route: String? = "Wi-Fi") = "Device: $name${route?.let { " via $it" } ?: ""}. Tap to choose"

fun JourneyRule.cGrantBt() = grant(
    "android.permission.BLUETOOTH_CONNECT", "android.permission.BLUETOOTH_ADVERTISE",
    "android.permission.BLUETOOTH_SCAN", "android.permission.POST_NOTIFICATIONS",
)

fun cAdapter(vararg hosts: Host) = MutableStateFlow(TestSeam.Adapter(enabled = true, hosts = hosts.toList()))

/** Polls [cond] (app state, same process). */
fun JourneyRule.cWait(what: String, timeoutMs: Long = 10_000, cond: () -> Boolean) {
    val end = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < end) {
        if (runCatching(cond).getOrDefault(false)) return
        // recomposition (and so effects such as keepScreenOn) runs on the Compose test clock
        runCatching { compose.mainClock.advanceTimeBy(16, ignoreFrameDuration = true) }
        Thread.sleep(100)
    }
    throw AssertionError("timed out after $timeoutMs ms waiting for $what (wsStatus=${Net.wsStatus.value})")
}

/**
 * Precondition fixture: a dry-run helper with [flags] (+ `--fake-proximity`), the app paired to it by
 * one-tap connect (FindMacScreen, fake proximity), the optional Bluetooth step skipped, the remote
 * on screen and the link Ready. Returns the helper and its Mac name.
 */
fun JourneyRule.cLinkHelper(
    vararg flags: String,
    transport: dev.mote.remote.net.Transport? = null,
    adapter: kotlinx.coroutines.flow.StateFlow<TestSeam.Adapter>? = null,
): Pair<Helper, String> {
    val mac = helper("--fake-proximity", *flags)
    mac.await("instant pairing: on", 15_000)
    val name = mac.info().getString("n")
    launch(fakeProximity = true, transport = transport, adapter = adapter)
    tap("Connect to $name", timeoutMs = 10_000)
    mac.await("labs: trusted .* via nearby", 15_000)
    if (expectAny("Skip", cPill(name), timeoutMs = 10_000) == "Skip") tap("Skip")
    expect(cPill(name), timeoutMs = 10_000)
    cWait("helper link Ready", 10_000) { Net.wsStatus.value is WsStatus.Ready && Remote.linked.value }
    compose.waitForIdle()
    return mac to name
}

/** Bluetooth HID fixture: permissions granted, [fake] installed as the transport, one bonded host; opens into the remote. */
fun JourneyRule.cLaunchBt(fake: FakeTransport, vararg hosts: Host = arrayOf(C_MAC)): MutableStateFlow<TestSeam.Adapter> {
    cGrantBt()
    val a = cAdapter(*hosts)
    launch(sweepPort = null, transport = fake, adapter = a)
    expect("Brightness up", timeoutMs = 10_000)
    cWait("FakeTransport link", 5_000) { Remote.linked.value == fake.linked.value }
    compose.waitForIdle()
    return a
}

fun JourneyRule.cNode(desc: String): SemanticsNodeInteraction = compose.onNode(hasContentDescription(desc), useUnmergedTree = false)

/** Real touch down + up at the centre of the node with content description [desc]. */
fun JourneyRule.cPress(desc: String) { cNode(desc).performTouchInput { down(center); up() } }

/** Holds [desc] for [ms] of Compose time (clock paused), then lifts. [during] runs while held. */
fun JourneyRule.cHold(desc: String, ms: Long, at: (androidx.compose.ui.test.TouchInjectionScope.() -> Offset)? = null, during: () -> Unit = {}) {
    val clock = compose.mainClock
    clock.autoAdvance = false
    try {
        cNode(desc).performTouchInput { down(at?.invoke(this) ?: center) }
        clock.advanceTimeBy(ms, ignoreFrameDuration = true)
        during()
        cNode(desc).performTouchInput { up() }
        clock.advanceTimeBy(32, ignoreFrameDuration = true)
    } finally { clock.autoAdvance = true }
}

/** Advances the Compose clock (composition-scope delays: macros, confirm reset, idle release). */
fun JourneyRule.cAdvance(ms: Long) { compose.mainClock.advanceTimeBy(ms, ignoreFrameDuration = true); compose.waitForIdle() }

/** Accessibility click on the clickable node with exact content description [desc]. */
fun JourneyRule.cClick(desc: String) {
    compose.onAllNodes(hasContentDescription(desc) and hasClickAction())[0].performSemanticsAction(SemanticsActions.OnClick)
}

fun JourneyRule.cType(desc: String, text: String) { compose.onNode(hasContentDescription(desc)).performTextInput(text) }

fun JourneyRule.cStateDesc(desc: String): String? =
    compose.onNode(hasContentDescription(desc)).fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription)

/** Number of nodes showing exactly [text] (unmerged). */
fun JourneyRule.cCount(text: String): Int = compose.onAllNodes(hasText(text), useUnmergedTree = true).fetchSemanticsNodes().size

/** True if some node with this text / description has a click action. */
fun JourneyRule.cClickable(text: String): Boolean =
    compose.onAllNodes((hasText(text) or hasContentDescription(text)) and hasClickAction()).fetchSemanticsNodes().isNotEmpty()

/** Taps MetalSlider [desc] at the position of step [index] (0-based) of [steps]. */
fun JourneyRule.cSlider(desc: String, index: Int, steps: Int) {
    val n = cNode(desc)
    runCatching { n.performScrollTo() }
    val dp = compose.density.density
    n.performTouchInput {
        val x0 = 28f * dp
        val track = width - 56f * dp
        click(Offset(x0 + track * index / (steps - 1), height / 2f))
    }
    compose.waitForIdle()
}

/** Scrolls the (only) lazy row on screen to [index]. */
fun JourneyRule.cScrollRow(index: Int) { compose.onAllNodes(hasScrollToIndexAction())[0].performScrollToIndex(index) }

fun JourneyRule.cActivity(): Activity {
    var a: Activity? = null
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
        a = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).firstOrNull()
    }
    return a ?: throw AssertionError("no resumed activity")
}

/** Helper log lines matching [re] from index [from]. */
fun Helper.cLines(re: String, from: Int): List<String> { val r = Regex(re); return lines(from).filter { r.containsMatchIn(it) } }

/** Sum of `DRY move dx=` from [from]. */
fun Helper.cMoveDx(from: Int): Double = cLines("DRY move dx=", from).sumOf { Regex("dx=(-?[0-9.]+)").find(it)!!.groupValues[1].toDouble() }

/** Calls of type [T] in [calls]. */
inline fun <reified T : Call> List<Call>.cOf(): List<T> = filterIsInstance<T>()

/** Waits until [fake] recorded a call matching [p] (real time). */
fun JourneyRule.cAwaitCall(fake: FakeTransport, what: String, timeoutMs: Long = 5_000, p: (List<Call>) -> Boolean): List<Call> {
    val end = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < end) {
        val c = fake.calls
        if (p(c)) return c
        compose.mainClock.advanceTimeBy(16, ignoreFrameDuration = true)
        Thread.sleep(20)
    }
    throw AssertionError("FakeTransport: no $what within $timeoutMs ms; calls=${fake.calls}")
}

/**
 * Home, wait [ms], then back in through the launcher intent (MainActivity is singleTask, so the same
 * instance resumes). Stands in for JourneyRule.backgroundAndReturn, whose moveToState(RESUMED) can't
 * bring a stopped activity back once the launcher is in front.
 */
fun JourneyRule.cHomeAndReturn(ms: Long) {
    device.pressHome(); Thread.sleep(ms)
    shell("am start -n ${ctx.packageName}/dev.mote.remote.MainActivity")
    cWait("activity resumed", 8_000) { runCatching { cActivity() }.isSuccess }
    compose.waitForIdle()
}

fun cHid(linked: Boolean = true) = FakeTransport(Caps.HID).also { it.linked.value = linked }
