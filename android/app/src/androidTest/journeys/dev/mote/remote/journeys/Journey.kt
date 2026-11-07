package dev.mote.remote.journeys

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import dev.mote.remote.MainActivity
import dev.mote.remote.hid.TestSeam
import dev.mote.remote.net.Transport
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.regex.Pattern

/**
 * Talks to the host-side controller (e2e/journeys/controller.mjs), which the emulator reaches at
 * 10.0.2.2 (host loopback). Override with `-e mjController http://10.0.2.2:PORT`.
 */
object Ctl {
    val base: String = InstrumentationRegistry.getArguments().getString("mjController") ?: "http://10.0.2.2:8799"

    fun call(path: String, body: JSONObject = JSONObject(), timeoutMs: Int = 70_000): JSONObject {
        val c = URL(base + path).openConnection() as HttpURLConnection
        c.connectTimeout = 3000; c.readTimeout = timeoutMs; c.requestMethod = "POST"; c.doOutput = true
        c.setRequestProperty("content-type", "application/json")
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = c.responseCode
        val text = (if (code < 400) c.inputStream else c.errorStream).use { it.readBytes().toString(Charsets.UTF_8) }
        val j = JSONObject(text.ifBlank { "{}" })
        if (code >= 400) throw AssertionError("controller $path -> $code ${j.optString("error")}")
        return j
    }

    fun upload(journey: String, step: String, png: ByteArray) {
        runCatching {
            val c = URL("$base/shot?journey=${journey}&step=${step}").openConnection() as HttpURLConnection
            c.connectTimeout = 3000; c.readTimeout = 10_000; c.requestMethod = "POST"; c.doOutput = true
            c.outputStream.use { it.write(png) }
            c.responseCode
        }
    }
}

/** A dry-run Mote Helper on the dev Mac, started by the controller (always --dry-run --headless --memory-store --no-ble --no-bonjour --stdin-control). */
class Helper(val name: String, val port: Int, val args: List<String>) {
    /** Log index the next [await] starts at; [mark] moves it to "now". */
    var from = 0
    fun mark(): Helper { from = Ctl.call("/helper/log", JSONObject().put("name", name)).getInt("next"); return this }
    fun lines(since: Int = 0): List<String> {
        val a = Ctl.call("/helper/log", JSONObject().put("name", name).put("from", since)).getJSONArray("lines")
        return (0 until a.length()).map { a.getString(it) }
    }
    /** Waits for a log line matching [regex] (from [from]); returns it, and moves [from] past it. */
    fun await(regex: String, timeoutMs: Long = 10_000): String {
        val r = Ctl.call("/helper/wait", JSONObject().put("name", name).put("re", regex).put("from", from).put("timeoutMs", timeoutMs))
        if (!r.optBoolean("ok")) throw AssertionError("helper '$name': no log line /$regex/ within $timeoutMs ms. Tail: ${r.optJSONArray("tail")}")
        from = r.getInt("index") + 1
        return r.getString("line")
    }
    /** Asserts no line matching [regex] appears in [withinMs] (from [from]). */
    fun assertNo(regex: String, withinMs: Long = 2_000) {
        val r = Ctl.call("/helper/wait", JSONObject().put("name", name).put("re", regex).put("from", from).put("timeoutMs", withinMs))
        if (r.optBoolean("ok")) throw AssertionError("helper '$name': unexpected log line: ${r.getString("line")}")
    }
    fun stdin(line: String) { Ctl.call("/helper/stdin", JSONObject().put("name", name).put("line", line)) }
    fun stop() { Ctl.call("/helper/stop", JSONObject().put("name", name)) }
    fun sigstop() { Ctl.call("/helper/signal", JSONObject().put("name", name).put("signal", "SIGSTOP")) }
    fun sigcont() { Ctl.call("/helper/signal", JSONObject().put("name", name).put("signal", "SIGCONT")) }
    /** stdout `LABS PAIR INFO: {...}` (instant pairing on): hostId, Mac name, fp, port. */
    fun info(): JSONObject = JSONObject(await("^LABS PAIR INFO: ").substringAfter("LABS PAIR INFO: ")).also { from = 0 }
}

/** Step outcome, as REPORT.md shows it. */
enum class Status { PASS, FAIL, FAKED, UNDEFINED, MANUAL, STATIC }

/**
 * One journey path (= one @Test): records every step's outcome to the controller and screenshots it
 * to /tmp/mote-journeys/<path>/<step>.png. Wrap every spec step in [step]; steps the emulator
 * can't run are recorded with [manual] / [undefined] / [static], never as passed.
 *
 * Each test starts from cleared app data (the orchestrator's clearPackageData) and launches
 * MainActivity itself with [launch], after starting the helpers and installing seams.
 */
class JourneyRule : TestRule {
    val compose: ComposeTestRule = createEmptyComposeRule()
    val device: UiDevice get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
    val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    /** Spec path id, e.g. `wifi-helper` or `wifi-helper.tailscale-owner`. Set first in every test. */
    var path: String = "unset"
        private set
    private val helpers = mutableListOf<Helper>()
    private var scenario: ActivityScenario<MainActivity>? = null
    private var failedStep: String? = null

    fun path(id: String) { path = id }

    // ---- results ----

    private fun record(step: String, status: Status, note: String = "", seam: String = "") {
        runCatching {
            Ctl.call("/step", JSONObject().put("path", path).put("step", step).put("status", status.name.lowercase())
                .put("note", note.take(600)).put("seam", seam))
        }
    }

    /**
     * Runs one spec step. [seam] is copied from the spec: "real", or "faked: <what>" (then a passing
     * step is reported FAKED, never PASS). On failure the step is recorded FAIL with the message and
     * a screenshot, and the test stops there (later steps are reported "not run").
     */
    fun step(id: String, seam: String = "real", block: () -> Unit) {
        try {
            block()
            compose.waitForIdle()
        } catch (t: Throwable) {
            shot(id)
            failedStep = id
            record(id, Status.FAIL, (t.message ?: t.toString()) + visibleSummary(), seam)
            throw t
        }
        shot(id)
        record(id, if (seam.startsWith("faked")) Status.FAKED else Status.PASS, "", seam)
    }

    fun manual(id: String, why: String) = record(id, Status.MANUAL, why, "manual")
    fun undefined(id: String, why: String) = record(id, Status.UNDEFINED, why, "undefined")
    fun static(id: String, why: String) = record(id, Status.STATIC, why, "static")

    fun shot(id: String) {
        runCatching {
            val bmp: Bitmap = ui.takeScreenshot() ?: return
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            Ctl.upload(path, id, out.toByteArray())
        }
    }

    private fun visibleSummary(): String = runCatching { "\nVisible: " + visibleTexts().joinToString(" | ").take(400) }.getOrDefault("")

    // ---- helpers / app ----

    /** Starts a dry-run helper with journey [args] (fakes only; the controller adds the safe flags). */
    fun helper(vararg args: String, name: String = "mac", port: Int = 0): Helper {
        val r = Ctl.call("/helper/start", JSONObject().put("name", name).put("args", JSONArray(args.toList())).put("port", port))
        val a = r.getJSONArray("args")
        return Helper(name, r.getInt("port"), (0 until a.length()).map { a.getString(it) }).also { helpers += it }
    }

    /**
     * Launches the app. Seams: Bonjour off (the emulator can't see the Mac's Bonjour anyway, and the
     * test helper doesn't advertise); the LAN sweep probes [sweepPort] (the helper's port) on
     * 10.0.2.0/24; [fakeProximity] reads the token from a `--fake-proximity` helper over the
     * WebSocket instead of BLE; [transport]/[adapter] replace Bluetooth HID / the adapter.
     */
    fun launch(
        sweepPort: Int? = helpers.firstOrNull()?.port,
        fakeProximity: Boolean = false,
        transport: Transport? = null,
        adapter: StateFlow<TestSeam.Adapter>? = null,
    ) {
        TestSeam.noBonjour = true
        TestSeam.sweepPort = sweepPort
        TestSeam.fakeProximity = fakeProximity
        TestSeam.transport = transport
        TestSeam.adapter = adapter
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    /** Closes the activity (not the process: app singletons and their state survive, as on a real back/home + return). */
    fun closeActivity() { scenario?.close(); scenario = null }

    /** Back to the launcher and in again (onStop/onStart). */
    fun backgroundAndReturn(ms: Long = 1500) {
        device.pressHome(); Thread.sleep(ms)
        scenario?.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
    }

    fun shell(cmd: String): String {
        val pfd = ui.executeShellCommand(cmd)
        return android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes().toString(Charsets.UTF_8) }
    }

    fun grant(vararg perms: String) = perms.forEach { ui.grantRuntimePermission(ctx.packageName, it) }

    fun wifi(on: Boolean) {
        shell("svc wifi ${if (on) "enable" else "disable"}")
        val want = if (on) "Wi-Fi is enabled" else "Wi-Fi is disabled"
        val end = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < end && !shell("cmd wifi status").contains(want)) Thread.sleep(250)
        if (on) { // wait for the AndroidWifi network to come back with an IP
            while (System.currentTimeMillis() < end && !shell("ip -4 addr show wlan0").contains("inet ")) Thread.sleep(250)
        }
    }

    /** The phone name the app sends to the Mac (`InstantPairing.deviceName()`). */
    fun phoneName(): String = android.provider.Settings.Global.getString(ctx.contentResolver, android.provider.Settings.Global.DEVICE_NAME)
        ?.takeIf { it.isNotBlank() } ?: Build.MODEL

    // ---- UI queries (Compose semantics: exact copy, or content description) ----

    private fun matcher(text: String, substring: Boolean) =
        hasText(text, substring = substring) or hasContentDescription(text, substring = substring)

    fun shown(text: String, substring: Boolean = false): Boolean =
        compose.onAllNodes(matcher(text, substring), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    fun visibleTexts(): List<String> = compose.onAllNodes(SemanticsMatcher("any text") {
        it.config.getOrNull(SemanticsProperties.Text) != null || it.config.getOrNull(SemanticsProperties.ContentDescription) != null
    }, useUnmergedTree = true).fetchSemanticsNodes().flatMap { n ->
        (n.config.getOrNull(SemanticsProperties.Text)?.map { it.text } ?: emptyList()) +
            (n.config.getOrNull(SemanticsProperties.ContentDescription) ?: emptyList())
    }.distinct()

    /** Waits until [text] (text or content description) is on screen. */
    fun expect(text: String, timeoutMs: Long = 5_000, substring: Boolean = false) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (runCatching { shown(text, substring) }.getOrDefault(false)) return
            Thread.sleep(100)
        }
        throw AssertionError("expected \"$text\" on screen within $timeoutMs ms")
    }

    /** Waits until any of [texts] is shown; returns the first that is. */
    fun expectAny(vararg texts: String, timeoutMs: Long = 5_000, substring: Boolean = false): String {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            texts.firstOrNull { runCatching { shown(it, substring) }.getOrDefault(false) }?.let { return it }
            Thread.sleep(100)
        }
        throw AssertionError("expected one of ${texts.toList()} on screen within $timeoutMs ms")
    }

    fun expectGone(text: String, timeoutMs: Long = 5_000, substring: Boolean = false) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (!runCatching { shown(text, substring) }.getOrDefault(true)) return
            Thread.sleep(100)
        }
        throw AssertionError("expected \"$text\" to leave the screen within $timeoutMs ms")
    }

    fun expectNot(text: String, substring: Boolean = false) {
        if (shown(text, substring)) throw AssertionError("\"$text\" must not be on screen")
    }

    /** Taps the clickable node with this text or content description (accessibility click; works under the IME). */
    fun tap(text: String, substring: Boolean = false, timeoutMs: Long = 5_000) {
        expect(text, timeoutMs, substring)
        val nodes = compose.onAllNodes(matcher(text, substring) and androidx.compose.ui.test.hasClickAction())
        if (nodes.fetchSemanticsNodes().isNotEmpty()) nodes[0].performSemanticsAction(SemanticsActions.OnClick)
        else compose.onAllNodes(matcher(text, substring), useUnmergedTree = true)[0].performClick()
    }

    /** Android system UI (permission / Bluetooth dialogs): taps the button whose text matches [regex]. */
    fun systemTap(regex: String, timeoutMs: Long = 8_000) {
        val sel = By.text(Pattern.compile(regex, Pattern.CASE_INSENSITIVE))
        val o = device.wait(Until.findObject(sel), timeoutMs) ?: throw AssertionError("system UI: no button /$regex/ within $timeoutMs ms")
        o.click()
    }

    fun systemShown(regex: String, timeoutMs: Long = 5_000): Boolean =
        device.wait(Until.hasObject(By.text(Pattern.compile(regex, Pattern.CASE_INSENSITIVE or Pattern.DOTALL))), timeoutMs) == true

    // ---- rule ----

    private val outer = object : TestRule {
        override fun apply(base: Statement, description: Description) = object : Statement() {
            override fun evaluate() {
                try {
                    Ctl.call("/ping")
                } catch (e: Exception) {
                    throw AssertionError("journeys controller not reachable at ${Ctl.base}: run e2e/journeys/run.sh", e)
                }
                try {
                    base.evaluate()
                } catch (t: Throwable) {
                    if (failedStep == null) { shot("setup"); record("setup", Status.FAIL, (t.message ?: t.toString()) + visibleSummary()) }
                    throw t
                } finally {
                    runCatching { scenario?.close() }
                    helpers.forEach { runCatching { it.stop() } }
                    runCatching { shell("svc wifi enable") }
                    TestSeam.transport = null; TestSeam.adapter = null; TestSeam.noBonjour = false
                    TestSeam.sweepPort = null; TestSeam.fakeProximity = false
                }
            }
        }
    }

    override fun apply(base: Statement, description: Description): Statement =
        RuleChain.outerRule(outer).around(compose).apply(base, description)
}
