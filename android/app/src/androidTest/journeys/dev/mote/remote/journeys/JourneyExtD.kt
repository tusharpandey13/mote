package dev.mote.remote.journeys

import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import dev.mote.remote.e2e.FakeTransport
import dev.mote.remote.e2e.press
import dev.mote.remote.hid.Host
import dev.mote.remote.hid.TestSeam
import dev.mote.remote.net.Caps
import dev.mote.remote.net.Net
import dev.mote.remote.net.Selection
import dev.mote.remote.net.WsStatus
import kotlinx.coroutines.flow.MutableStateFlow

/** Group D (reconnect, recovery) helpers. */

const val D_PROX = "faked: proximity token read over the WebSocket from a --fake-proximity helper (TestSeam.fakeProximity) instead of BLE"

/** Bluetooth runtime permissions + notifications. */
fun JourneyRule.dGrantBt() = grant(
    "android.permission.BLUETOOTH_CONNECT", "android.permission.BLUETOOTH_ADVERTISE",
    "android.permission.BLUETOOTH_SCAN", "android.permission.POST_NOTIFICATIONS",
)

fun dAdapter(vararg hosts: Host, enabled: Boolean = true) = MutableStateFlow(TestSeam.Adapter(enabled = enabled, hosts = hosts.toList()))

fun dFakeHid(linked: Boolean = false) = FakeTransport(Caps.HID).also { it.linked.value = linked }

fun dPill(name: String, route: String?) = "Device: $name${route?.let { " via $it" } ?: ""}. Tap to choose"

/** Waits for a condition on app state (same process), polling. */
fun JourneyRule.dWait(what: String, timeoutMs: Long = 10_000, cond: () -> Boolean) {
    val end = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < end) {
        if (runCatching(cond).getOrDefault(false)) return
        Thread.sleep(100)
    }
    throw AssertionError("timed out after $timeoutMs ms waiting for $what (wsStatus=${Net.wsStatus.value}, selection=${Net.selection.value})")
}

fun JourneyRule.dWs(timeoutMs: Long = 10_000, what: String = "ws status", cond: (WsStatus) -> Boolean) =
    dWait(what, timeoutMs) { cond(Net.wsStatus.value) }

/**
 * Pairs the phone with [mac] by instant pairing (fake proximity), from FindMacScreen or, when the
 * app opened into the remote, from the pill › "Add a Mac". Skips the optional Bluetooth step.
 * Leaves the remote on screen with the Mac connected over Wi-Fi.
 */
fun JourneyRule.dPair(mac: Helper, name: String) {
    val first = expectAny("Connect to $name", dPill("Choose a device", null), timeoutMs = 10_000)
    if (first != "Connect to $name") { tap(dPill("Choose a device", null)); tap("Add a Mac") }
    tap("Connect to $name", timeoutMs = 10_000)
    val phone = phoneName()
    mac.await("labs: trusted \\Q$phone\\E via (nearby|tailscale)", 15_000)
    dLastPairMs = System.currentTimeMillis()
    dSkipBt(name)
    expect(dPill(name, "Wi-Fi"), timeoutMs = 10_000)
}

/** The optional "Also pair Bluetooth…" step, when shown: Skip. */
fun JourneyRule.dSkipBt(name: String) {
    val end = System.currentTimeMillis() + 10_000
    while (System.currentTimeMillis() < end) {
        if (shown("Skip")) { tap("Skip"); return }
        if (shown(dPill(name, "Wi-Fi")) && !shown("Connect to your Mac") && !shown("Connected over Wi-Fi") && !shown("Connected")) return
        compose.mainClock.advanceTimeBy(150) // ConnectCard closes 900 ms after "Connected" (composition delay)
        Thread.sleep(150)
    }
}

/** Relaunches the activity (process and app singletons survive). */
fun JourneyRule.dRelaunch(fakeProximity: Boolean = true, transport: dev.mote.remote.net.Transport? = TestSeam.transport,
                          adapter: kotlinx.coroutines.flow.StateFlow<TestSeam.Adapter>? = TestSeam.adapter) {
    closeActivity()
    Thread.sleep(500)
    launch(fakeProximity = fakeProximity, transport = transport, adapter = adapter)
}

fun JourneyRule.dLongPress(desc: String) {
    expect(desc)
    compose.onAllNodes(hasContentDescription(desc))[0].performTouchInput { longClick() }
}

fun JourneyRule.dPress(desc: String) {
    expect(desc)
    compose.onAllNodes(hasContentDescription(desc))[0].press()
}

/** Ongoing notification text via the shade; closes the shade again. */
fun JourneyRule.dNotification(text: String, timeoutMs: Long = 8_000) {
    device.openNotification()
    val ok = device.wait(Until.hasObject(By.text(text)), timeoutMs) == true
    device.pressBack()
    if (!ok) throw AssertionError("notification text \"$text\" not shown within $timeoutMs ms")
}

/**
 * Like [JourneyRule.expect], but through ComposeTestRule.waitUntil, which advances the Compose main
 * clock: UI that waits with `delay()` in a composition (FindMacPanel's 5 s "not found", the 1.5 s
 * reach probe, ConnectCard's 900 ms close) only moves on while the clock advances.
 */
fun JourneyRule.dExpect(text: String, timeoutMs: Long = 10_000) {
    try { compose.waitUntil(timeoutMs) { runCatching { shown(text) }.getOrDefault(false) } }
    catch (e: Throwable) { throw AssertionError("expected \"$text\" on screen within $timeoutMs ms (clock-advancing wait)") }
}

fun JourneyRule.dExpectAny(vararg texts: String, timeoutMs: Long = 10_000): String {
    var hit: String? = null
    try { compose.waitUntil(timeoutMs) { hit = texts.firstOrNull { runCatching { shown(it) }.getOrDefault(false) }; hit != null } }
    catch (e: Throwable) { throw AssertionError("expected one of ${texts.toList()} within $timeoutMs ms (clock-advancing wait)") }
    return hit!!
}

/** Proximity tokens are one per 30 s window and single-use (Proximity.swift): wait for a fresh window before re-pairing (recovery-D10). */
@Volatile var dLastPairMs = 0L

fun dFreshProximityWindow(sinceMs: Long = dLastPairMs) {
    val wait = 31_000 - (System.currentTimeMillis() - sinceMs)
    if (wait > 0) Thread.sleep(wait)
}

fun dSelectedBt(addr: String) = Net.selection.value == Selection.Bt(addr)
