package dev.mote.remote.journeys

import android.Manifest
import android.content.ClipboardManager
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.test.platform.app.InstrumentationRegistry

/** Group B (connect journeys) helpers. */

/** Types [text] into the text field whose content description (= placeholder) is [desc], replacing what's there. */
fun JourneyRule.bType(desc: String, text: String) {
    expect(desc)
    val n = compose.onAllNodes(hasContentDescription(desc) and hasSetTextAction(), useUnmergedTree = true)[0]
    n.performTextClearance()
    if (text.isNotEmpty()) n.performTextInput(text)
    compose.waitForIdle()
}

/** Long-presses the node with this content description. */
fun JourneyRule.bLongPress(desc: String) {
    expect(desc)
    compose.onAllNodes(hasContentDescription(desc))[0].performTouchInput { longClick() }
    compose.waitForIdle()
}

/** Current primary clip text (the app is in the foreground, same process). */
fun JourneyRule.bClipboard(): String? {
    var out: String? = null
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
        out = ctx.getSystemService(ClipboardManager::class.java)?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
    }
    return out
}

/** The Bluetooth runtime permissions BLE discovery / HID need (API 31+). */
val B_BLE_PERMS = arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
val B_BT_PERMS = arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)

/** Regex-quotes [s] (names with curly apostrophes etc). */
fun bq(s: String) = "\\Q$s\\E"

/**
 * Home, wait [ms], then back to the app through the launcher intent (the task comes to the front;
 * the process and its singletons stay). Stand-in for JourneyRule.backgroundAndReturn, whose
 * moveToState(RESUMED) after Home never resumes the activity on API 35.
 */
fun JourneyRule.bHomeAndReturn(ms: Long) {
    device.pressHome()
    Thread.sleep(ms)
    shell("am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n ${ctx.packageName}/dev.mote.remote.MainActivity")
    device.wait(androidx.test.uiautomator.Until.hasObject(androidx.test.uiautomator.By.pkg(ctx.packageName).depth(0)), 8_000)
    Thread.sleep(500)
}

/** Lets composition-scoped delays (e.g. ConnectCard's 900 ms before onDone) run under the Compose test clock. */
fun JourneyRule.bAdvance(ms: Long) { compose.mainClock.advanceTimeBy(ms); compose.waitForIdle() }

/**
 * Like [JourneyRule.expect], but advances the Compose test clock while waiting: state driven by a
 * composition-scoped `delay()` loop (FindMacPanel's 250 ms ticker → "not found after 5 s") only moves
 * when the test clock does.
 */
fun JourneyRule.bExpectTicking(text: String, timeoutMs: Long = 10_000) {
    val end = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < end) {
        if (runCatching { shown(text) }.getOrDefault(false)) return
        bAdvance(250); Thread.sleep(150)
    }
    throw AssertionError("expected \"$text\" on screen within $timeoutMs ms (Compose clock advanced)")
}

/** Sleeps until the next 30 s proximity window has started (Proximity.swift: one accepted token per window). */
fun bNextProxWindow() {
    val ms = 30_000 - (System.currentTimeMillis() % 30_000) + 1_000
    Thread.sleep(ms)
}

private fun bIsObserverArtifact(t: Throwable): Boolean = generateSequence(t) { it.cause }.any {
    it is IllegalStateException && it.message?.contains("removeObserver must be called on the main thread") == true
}

/**
 * Runs a precondition block. The compose test dispatcher can surface Bt.observe's off-main
 * removeObserver ISE (a test artifact) at any compose call; JourneyRule then swallows the whole test
 * as a "teardown artifact" when no step failed yet, so a broken precondition would pass silently.
 * Here the artifact is skipped (the block is retried once) and any other failure is rethrown wrapped
 * in an AssertionError, which JourneyRule records as `setup` FAIL.
 */
fun JourneyRule.bPre(what: String, block: () -> Unit) {
    var tries = 0
    while (true) {
        try { block(); return } catch (t: Throwable) {
            if (bIsObserverArtifact(t) && tries++ < 2) continue
            throw AssertionError("precondition '$what' failed: ${t.message ?: t}", t)
        }
    }
}

/** A port nothing listens on (for "no helper" runs that later start one there). */
fun bFreePort(): Int = 19_000 + (System.nanoTime() % 900).toInt()
