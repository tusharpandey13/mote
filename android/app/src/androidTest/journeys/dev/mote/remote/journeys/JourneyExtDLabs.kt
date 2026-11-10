package dev.mote.remote.journeys

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import dev.mote.remote.labs.LabFlag
import dev.mote.remote.labs.Labs
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Group D (Labs journeys) helpers. Every name starts with `dl`. */

/** Seam text for instant pairing with the WebSocket proximity token (precondition only). */
const val DL_PROX = "faked: proximity token read over the WebSocket from a --fake-proximity helper (TestSeam.fakeProximity)"

/** Precondition: Labs flags on the phone (DataStore `labs`), before [JourneyRule.launch]. */
fun JourneyRule.dlLabs(vararg flags: LabFlag, on: Boolean = true) = runBlocking { flags.forEach { Labs.set(ctx, it, on) } }

/** Precondition: a helper with instant pairing + fake proximity (+ [extra]); returns it and the Mac name. */
fun JourneyRule.dlHelper(vararg extra: String): Pair<Helper, String> {
    val mac = helper("--fake-proximity", *extra)
    mac.await("instant pairing: on")
    val name = mac.info().getString("n")
    return mac to name
}

/**
 * Precondition: first connect through FindMacScreen (one tap), skipping the optional Bluetooth step,
 * until the remote shows the pill for [name] over Wi-Fi. Leaves [Helper.from] after the authed line.
 */
fun JourneyRule.dlConnect(mac: Helper, name: String) {
    launch(fakeProximity = true)
    expect("Connect to $name", timeoutMs = 10_000)
    tap("Connect to $name")
    mac.await("authed .* via Wi-Fi", 15_000)
    val pill = "Device: $name via Wi-Fi. Tap to choose"
    if (expectAny("Skip", pill, timeoutMs = 10_000) == "Skip") tap("Skip")
    expect(pill, timeoutMs = 10_000)
}

/** Flips a Settings › Labs switch through the UI (Menu › Settings), then goes back. */
fun JourneyRule.dlToggleLabUi(title: String) {
    tap("Menu")
    tap("Settings")
    expect("LABS", timeoutMs = 5_000)
    val n = compose.onNode(hasContentDescription(title) and hasClickAction(), useUnmergedTree = true)
    n.performScrollTo()
    n.performClick()
    compose.waitForIdle()
    tap("Back")
}

/** stateDescription of the first node with content description [desc]. */
fun JourneyRule.dlState(desc: String): String? =
    compose.onAllNodes(hasContentDescription(desc), useUnmergedTree = true).fetchSemanticsNodes()
        .firstOrNull()?.config?.getOrNull(SemanticsProperties.StateDescription)

/** Waits until [desc]'s stateDescription matches [re]. */
fun JourneyRule.dlExpectState(desc: String, re: Regex, timeoutMs: Long = 5_000) {
    val end = System.currentTimeMillis() + timeoutMs
    var last: String? = null
    while (System.currentTimeMillis() < end) {
        last = runCatching { dlState(desc) }.getOrNull()
        if (last != null && re.matches(last)) return
        Thread.sleep(100)
    }
    throw AssertionError("\"$desc\" state: expected /$re/, was \"$last\"")
}

/** Accessibility setProgress on the node with content description [desc]. */
fun JourneyRule.dlSetProgress(desc: String, v: Float) {
    compose.onAllNodes(hasContentDescription(desc), useUnmergedTree = true)[0]
        .performSemanticsAction(SemanticsActions.SetProgress) { it(v) }
}

/** Touch gestures on the node with content description [desc]. */
fun JourneyRule.dlTouch(desc: String, block: androidx.compose.ui.test.TouchInjectionScope.() -> Unit) {
    compose.onAllNodes(hasContentDescription(desc), useUnmergedTree = true)[0].performTouchInput(block)
}

/** Types [text] into the text field whose content description is [desc] (replacing its text). */
fun JourneyRule.dlType(desc: String, text: String) {
    val n = compose.onAllNodes(hasContentDescription(desc) and androidx.compose.ui.test.hasSetTextAction(), useUnmergedTree = true)[0]
    n.performTextClearance()
    n.performTextInput(text)
    compose.waitForIdle()
}

/** Text of the editable field whose content description is [desc]. */
fun JourneyRule.dlFieldText(desc: String): String =
    compose.onAllNodes(hasContentDescription(desc) and androidx.compose.ui.test.hasSetTextAction(), useUnmergedTree = true)
        .fetchSemanticsNodes().firstOrNull()?.config?.getOrNull(SemanticsProperties.EditableText)?.text ?: ""

/** Puts [text] on the emulator clipboard (from the app process, main thread). */
fun JourneyRule.dlClip(text: String) {
    androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync {
        ctx.getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText("t", text))
    }
}

/** Precondition for the Bluetooth branches: no helper, Bluetooth chosen on FindMacScreen, FakeTransport (HID caps). */
fun JourneyRule.dlBluetoothOnly(): dev.mote.remote.e2e.FakeTransport {
    grant(android.Manifest.permission.BLUETOOTH_CONNECT, android.Manifest.permission.BLUETOOTH_ADVERTISE, android.Manifest.permission.BLUETOOTH_SCAN)
    val t = dev.mote.remote.e2e.FakeTransport(dev.mote.remote.net.Caps.HID)
    launch(sweepPort = null, transport = t, adapter = kotlinx.coroutines.flow.MutableStateFlow(
        dev.mote.remote.hid.TestSeam.Adapter(hosts = listOf(dev.mote.remote.hid.Host("AA:BB:CC:DD:EE:01", "Test Mac", dev.mote.remote.hid.Host.Kind.LAPTOP)))))
    // Bluetooth permissions granted before launch: startScreen opens the remote directly (btPermitted + adapter ready).
    if (expectAny("Use Bluetooth instead (no helper)", "Play or pause", timeoutMs = 10_000) != "Play or pause") tap("Use Bluetooth instead (no helper)")
    expect("Play or pause", timeoutMs = 8_000)
    return t
}

/** Taps the LAST clickable node with this text / content description (e.g. the sheet's copy over the screen's). */
fun JourneyRule.dlTapLast(text: String, timeoutMs: Long = 5_000) {
    expect(text, timeoutMs)
    val m = (androidx.compose.ui.test.hasText(text) or hasContentDescription(text)) and hasClickAction()
    val n = compose.onAllNodes(m).fetchSemanticsNodes().size
    if (n == 0) throw AssertionError("no clickable \"$text\"")
    compose.onAllNodes(m)[n - 1].performSemanticsAction(SemanticsActions.OnClick)
}

/** Writes [json] to /tmp/mote-journeys/files/<file> on the Mac (controller) and returns its path. */
fun dlFile(file: String, json: String): String =
    Ctl.call("/file", JSONObject().put("file", file).put("content", json)).getString("path")

/**
 * A scripted browser-extension client of the helper's video bridge (stands in for the extension):
 * WebSocket to the bridge port through the emulator's host alias with the loopback Host header and a
 * `chrome-extension://` Origin, exactly what the extension sends.
 */
class DlBridgeClient(val port: Int, val origin: String = "chrome-extension://motejourneytest") {
    val inbox = LinkedBlockingQueue<JSONObject>()
    private val http = okhttp3.OkHttpClient()
    private lateinit var ws: okhttp3.WebSocket
    var nonce = ""

    fun open(): JSONObject {
        val req = okhttp3.Request.Builder().url("http://10.0.2.2:$port/bridge")
            .header("Host", "127.0.0.1:$port").header("Origin", origin).build()
        ws = http.newWebSocket(req, object : okhttp3.WebSocketListener() {
            override fun onMessage(webSocket: okhttp3.WebSocket, text: String) { inbox.put(JSONObject(text)) }
            override fun onFailure(webSocket: okhttp3.WebSocket, t: Throwable, response: okhttp3.Response?) {
                inbox.put(JSONObject().put("t", "_failure").put("msg", "${t.message} ${response?.code}"))
            }
            override fun onClosed(webSocket: okhttp3.WebSocket, code: Int, reason: String) { inbox.put(JSONObject().put("t", "_closed")) }
        })
        val hello = next("hello")
        nonce = hello.getString("nonce")
        return hello
    }

    fun send(j: JSONObject) { ws.send(j.toString()) }

    /** Next message of type [t] (others are skipped) within [ms]. */
    fun next(t: String, ms: Long = 8_000): JSONObject {
        val end = System.currentTimeMillis() + ms
        while (true) {
            val left = end - System.currentTimeMillis()
            if (left <= 0) throw AssertionError("bridge client: no '$t' within $ms ms")
            val m = inbox.poll(left, TimeUnit.MILLISECONDS) ?: continue
            if (m.optString("t") == "_failure") throw AssertionError("bridge client: socket failed: ${m.optString("msg")}")
            if (m.optString("t") == t) return m
        }
    }

    /** Pairs with [code], then authenticates (HMAC-SHA256 of the nonce with the token). */
    fun pairAndAuth(code: String) {
        send(JSONObject().put("t", "labs.video.pair").put("code", code))
        val p = next("labs.video.paired")
        auth(p.getString("id"), p.getString("token"))
    }

    fun auth(id: String, token: String) {
        val key = token.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val m = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }
        val mac = m.doFinal(nonce.toByteArray()).joinToString("") { "%02x".format(it) }
        send(JSONObject().put("t", "labs.video.auth").put("id", id).put("mac", mac))
        next("labs.video.authed")
    }

    fun state(j: JSONObject) = send(j.put("t", "labs.video.state"))

    fun close() { runCatching { ws.close(1000, null) } }

}

/** Waits until the active transport reports no link (e.g. after the helper was stopped). */
fun JourneyRule.dlWaitUnlinked(timeoutMs: Long = 60_000) {
    val end = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < end) { if (!dev.mote.remote.net.Remote.linked.value) return; Thread.sleep(200) }
    throw AssertionError("still linked after $timeoutMs ms")
}
