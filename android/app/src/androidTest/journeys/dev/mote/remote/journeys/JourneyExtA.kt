package dev.mote.remote.journeys

import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import dev.mote.remote.e2e.FakeTransport
import dev.mote.remote.hid.TestSeam
import dev.mote.remote.net.Caps
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.regex.Pattern

// Group A (setup-mac, mac-update, setup-phone, first-run-bt) helpers. Prefixed `a` to avoid clashes.

private const val A_PERM_UI = "com.android.permissioncontroller"

/** A fake Bluetooth adapter (TestSeam.adapter); runtime permissions stay real. */
fun aAdapter(supported: Boolean = true, enabled: Boolean = true) = MutableStateFlow(TestSeam.Adapter(supported, enabled))

/** A Bluetooth HID fake that is not linked to any host (no real HID registration on the emulator). */
fun aHidFake() = FakeTransport(Caps.HID).also { it.linked.value = false }

/** Port nothing listens on: the LAN sweep must never find the developer's real helper on 10.0.2.2:8790. */
const val A_NO_HELPER_PORT = 9

/**
 * Answers every runtime-permission dialog that shows up (Nearby devices, Notifications) in turn.
 * [allow] decides per dialog from its message text. Returns the messages answered.
 */
fun JourneyRule.aAnswerPermissions(timeoutMs: Long = 6_000, allow: (String) -> Boolean): List<String> {
    val answered = mutableListOf<String>()
    while (true) {
        val wait = if (answered.isEmpty()) timeoutMs else 3_000L
        val allowBtn = device.wait(Until.findObject(By.res(A_PERM_UI, "permission_allow_button")), wait) ?: return answered
        val msg = device.findObject(By.res(A_PERM_UI, "permission_message"))?.text ?: ""
        if (allow(msg)) allowBtn.click()
        else {
            val deny = device.findObject(By.res(Pattern.compile("$A_PERM_UI:id/permission_deny(_and_dont_ask_again)?_button")))
                ?: throw AssertionError("permission dialog \"$msg\": no deny button")
            deny.click()
        }
        answered += msg
        device.waitForIdle()
        Thread.sleep(300)
    }
}

fun JourneyRule.aAllowAll(): List<String> = aAnswerPermissions { true }
fun JourneyRule.aDenyAll(): List<String> = aAnswerPermissions { false }

fun aIsNotificationDialog(msg: String) = msg.contains("notification", ignoreCase = true)

/** Android's "make your phone visible" dialog (ACTION_REQUEST_DISCOVERABLE): taps Allow / Deny. */
fun JourneyRule.aDiscoverable(allow: Boolean, timeoutMs: Long = 8_000) {
    if (!aSystemShown(".*(visible|discoverable).*", timeoutMs)) throw AssertionError("no Bluetooth visibility (discoverable) system dialog within $timeoutMs ms")
    val b = device.wait(Until.findObject(By.text(Pattern.compile(if (allow) "Allow" else "(Deny|Don.t allow)", Pattern.CASE_INSENSITIVE)).pkg(A_NOT_APP)), 3_000)
        ?: throw AssertionError("discoverable dialog: no ${if (allow) "Allow" else "Deny"} button")
    b.click()
}

/** If Android shows a Bluetooth-enable request (the fake adapter is off, the real one may be on), leave it with Back. */
fun JourneyRule.aDismissEnableDialog(timeoutMs: Long = 2_500) {
    if (aSystemShown(".*(turn on Bluetooth|wants to turn on).*", timeoutMs)) device.pressBack()
}

private val A_NOT_APP: Pattern = Pattern.compile("^(?!dev\\.mote\\.remote).*")

/** System UI (any package but the app) shows text matching [regex] (full match, case-insensitive). */
fun JourneyRule.aSystemShown(regex: String, timeoutMs: Long = 5_000): Boolean =
    device.wait(Until.hasObject(By.text(Pattern.compile(regex, Pattern.CASE_INSENSITIVE or Pattern.DOTALL)).pkg(A_NOT_APP)), timeoutMs) == true

fun JourneyRule.aPerm(p: String): Boolean = ctx.checkSelfPermission(p) == android.content.pm.PackageManager.PERMISSION_GRANTED

/**
 * Like [JourneyRule.expect], but keeps the Compose test clock moving: UI that waits in `delay()` on a
 * composition scope (ConnectCard's 900 ms "Connected", BtStep's 1.5 s "both set up", FindMacPanel's
 * 250 ms tick toward "not found") only progresses when the test clock advances.
 */
fun JourneyRule.aExpect(text: String, timeoutMs: Long = 8_000, substring: Boolean = false) {
    val end = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < end) {
        if (runCatching { shown(text, substring) }.getOrDefault(false)) return
        runCatching { compose.mainClock.advanceTimeBy(100) }
        Thread.sleep(50)
    }
    throw AssertionError("expected \"$text\" on screen within $timeoutMs ms")
}

/** Waits until the remote (Media screen) is shown. */
fun JourneyRule.aRemote(timeoutMs: Long = 10_000) = aExpect("Play or pause", timeoutMs)

/** First-run connect from FindMacScreen to the dry-run [mac] named [name] (fake proximity). */
fun JourneyRule.aConnect(name: String) {
    expect("Connect to $name", timeoutMs = 10_000)
    tap("Connect to $name")
}

const val A_BT_OFFER = "Also pair Bluetooth for login screen & backup (recommended)"
