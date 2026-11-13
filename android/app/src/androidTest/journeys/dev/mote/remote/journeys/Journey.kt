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
import dev.mote.remote.diag.Events
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
    /** The Mac as the device sees it: the emulator's host alias, or (phone mode) the Mac's tailnet IP (`-e mjHost`). */
    val host: String = InstrumentationRegistry.getArguments().getString("mjHost") ?: "10.0.2.2"
    /** Phone mode (run.sh MJ_PHONE): a real phone over wireless adb. Wi-Fi / data / airplane steps are skipped. */
    val phone: Boolean = host != "10.0.2.2"
    /** The link the helper and the app name for this route: a Wi-Fi LAN on the emulator, the tailnet in phone mode (the phone reaches the Mac at its 100.x address). */
    val link: String = if (phone) "Tailscale" else "Wi-Fi"
    val base: String = InstrumentationRegistry.getArguments().getString("mjController") ?: "http://$host:8799"

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

    /** Throws when the controller can't be reached (the caller queues it, see Journey.deliver). */
    fun upload(journey: String, step: String, png: ByteArray) {
        run {
            val c = URL("$base/shot?journey=${journey}&step=${step}").openConnection() as HttpURLConnection
            c.connectTimeout = 3000; c.readTimeout = 10_000; c.requestMethod = "POST"; c.doOutput = true
            c.outputStream.use { it.write(png) }
            if (c.responseCode >= 400) throw AssertionError("controller /shot -> ${c.responseCode}")
        }
    }
}

/** A dry-run Mote Helper on the dev Mac, started by the controller (always --dry-run --headless --memory-store --no-ble --no-bonjour --stdin-control). */
class Helper(val name: String, val port: Int, val args: List<String>) {
    /** Log index the next [await] starts at; [mark] moves it to "now". */
    var from = 0
    /** Events are written to the log by a separate tail, so they are not ordered against log lines: they have their own cursor, moved by [mark]. */
    private var evFrom = 0
    fun mark(): Helper { from = Ctl.call("/helper/log", JSONObject().put("name", name)).getInt("next"); evFrom = from; return this }
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
    /**
     * Waits for the helper's structured diag event `area/name` (docs/protocol.md). The controller starts every helper with
     * `--events FILE` and appends each event to the log as `EVENT area/name {fields json}`; [fields] is a regex over that JSON.
     */
    fun event(area: String, name: String, fields: String = ".*", timeoutMs: Long = 10_000): String {
        val re = "^EVENT ${area.replace(".", "\\.")}/${name.replace(".", "\\.")} $fields"
        val r = Ctl.call("/helper/wait", JSONObject().put("name", this.name).put("re", re).put("from", evFrom).put("timeoutMs", timeoutMs))
        if (!r.optBoolean("ok")) throw AssertionError("helper '${this.name}': no event $area/$name $fields within $timeoutMs ms. Tail: ${r.optJSONArray("tail")}")
        evFrom = r.getInt("index") + 1
        return r.getString("line")
    }
    fun stdin(line: String) { Ctl.call("/helper/stdin", JSONObject().put("name", name).put("line", line)) }
    fun stop() { Ctl.call("/helper/stop", JSONObject().put("name", name)) }
    fun sigstop() { Ctl.call("/helper/signal", JSONObject().put("name", name).put("signal", "SIGSTOP")) }
    fun sigcont() { Ctl.call("/helper/signal", JSONObject().put("name", name).put("signal", "SIGCONT")) }
    /** stdout `LABS PAIR INFO: {...}` (instant pairing on): hostId, Mac name, fp, port. */
    fun info(): JSONObject = JSONObject(await("^LABS PAIR INFO: ").substringAfter("LABS PAIR INFO: ")).also { from = 0 }
}

/**
 * LAN sweep port when a journey runs no helper. Never null: null makes the app probe the default helper port
 * 8790 on the Mac, i.e. the dev Mac's REAL installed helper, which then shows up as a discovered Mac.
 */
const val NO_HELPER_PORT = 18_799

/** Phone mode seam label (recorded on every step after a launch that probes the helper at mjHost). */
const val PROBE_SEAM = "faked: discovery via mjHost (isolated Wi-Fi: Bonjour and the LAN sweep can't reach the Mac, so the app also probes the Mac's tailnet IP on the journey helper's port)"

const val PHONE_SKIP = "skipped: phone mode (would drop wireless adb)"

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
    /** Results and screenshots the controller didn't get (a step that turned the network off); sent after the test. */
    private val undelivered = mutableListOf<() -> Unit>()

    /** Sends to the controller now, or queues it: a lost record would leave a stale result in REPORT.md. */
    private fun deliver(send: () -> Unit) {
        if (undelivered.isNotEmpty() || runCatching(send).isFailure) undelivered += send
    }

    /** After the network is back: sends what was queued, or fails the test (its results would be missing). */
    private fun flushUndelivered() {
        val end = System.currentTimeMillis() + 30_000
        while (undelivered.isNotEmpty()) {
            if (runCatching(undelivered.first()).isSuccess) undelivered.removeAt(0)
            else if (System.currentTimeMillis() > end) throw AssertionError("journeys controller unreachable after the test: ${undelivered.size} result(s) not recorded")
            else Thread.sleep(500)
        }
    }

    private var appFrom = 0L
    /** Set by [launch] in phone mode when a journey helper is the discovery target. */
    private var probing = false

    /** Collapses the notification / quick-settings shade and closes system dialogs (a real phone keeps what the last run left open). */
    fun closeShade() {
        runCatching { shell("cmd statusbar collapse") }
        runCatching { shell("am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS") }
        device.waitForIdle()
    }

    /**
     * Waits for the phone's own diag event `area/name` (the app's [Events] ring buffer, same process) logged since [path] was
     * set, whose fields equal every pair in [where]. Assert the "Detected by" event next to the UI text, never only the text.
     */
    fun appEvent(area: String, name: String, vararg where: Pair<String, Any?>, timeoutMs: Long = 10_000): Events.Event {
        val end = System.currentTimeMillis() + timeoutMs
        while (true) {
            Events.snapshot().firstOrNull { e -> e.seq > appFrom && e.area == area && e.event == name && where.all { (k, v) -> e.fields[k] == v } }?.let { return it }
            if (System.currentTimeMillis() > end) throw AssertionError("no app event $area/$name ${where.toList()} within $timeoutMs ms. Last: ${Events.snapshot().takeLast(6).map { "${it.area}/${it.event}${it.fields}" }}")
            Thread.sleep(100)
        }
    }

    /** From here on [appEvent] / [appEventCount] only see events logged after this call (like [Helper.mark]). */
    fun appMark() { appFrom = Events.head }

    /** How many app events `area/name` with fields [where] were logged since [path] was set (no waiting). */
    fun appEventCount(area: String, name: String, vararg where: Pair<String, Any?>): Int =
        Events.snapshot().count { e -> e.seq > appFrom && e.area == area && e.event == name && where.all { (k, v) -> e.fields[k] == v } }

    /** Asserts the app logged no `area/name` with fields [where] since [path] was set. */
    fun noAppEvent(area: String, name: String, vararg where: Pair<String, Any?>) {
        val n = appEventCount(area, name, *where)
        if (n != 0) throw AssertionError("unexpected app event $area/$name ${where.toList()} x$n")
    }

    fun path(id: String) {
        appFrom = Events.head
        path = id
        Ctl.call("/begin", JSONObject().put("path", id)) // set first, while the network is still up
    }

    // ---- results ----

    private fun record(step: String, status: Status, note: String = "", seam: String = "") {
        val body = JSONObject().put("path", path).put("step", step).put("status", status.name.lowercase())
            .put("note", note.take(600)).put("seam", seam)
        deliver { Ctl.call("/step", body, timeoutMs = 10_000) }
    }

    /**
     * Runs one spec step. [seam] is copied from the spec: "real", or "faked: <what>" (then a passing
     * step is reported FAKED, never PASS). On failure the step is recorded FAIL with the message and
     * a screenshot, and the test stops there (later steps are reported "not run").
     */
    fun step(id: String, seamIn: String = "real", block: () -> Unit) {
        val seam = if (!probing) seamIn else if (seamIn == "real") PROBE_SEAM else "$seamIn; $PROBE_SEAM"
        try {
            block()
            compose.waitForIdle()
        } catch (t: org.junit.AssumptionViolatedException) {
            failedStep = id // keeps the outer handler from adding a "setup" FAIL
            record(id, Status.MANUAL, t.message ?: PHONE_SKIP, "manual")
            throw t
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
            val png = out.toByteArray(); val p = path
            deliver { Ctl.upload(p, id, png) }
        }
    }

    private fun visibleSummary(): String = runCatching { "\nVisible: " + visibleTexts().joinToString(" | ").take(400) }.getOrDefault("")

    // ---- helpers / app ----

    /** Starts a dry-run helper with journey [args] (fakes only; the controller adds the safe flags). */
    fun helper(vararg args: String, name: String = "mac", port: Int = 0, keep: Boolean = false): Helper {
        val r = Ctl.call("/helper/start", JSONObject().put("name", name).put("args", JSONArray(args.toList())).put("port", port))
        val a = r.getJSONArray("args")
        return Helper(name, r.getInt("port"), (0 until a.length()).map { a.getString(it) }).also { if (keep) kept += it else helpers += it }
    }

    /** A helper a previous test of the same ordered class started with keep = true (null: not running). */
    fun attach(name: String): Helper? {
        val r = runCatching { Ctl.call("/helper/info", JSONObject().put("name", name)) }.getOrNull() ?: return null
        val a = r.getJSONArray("args")
        return Helper(name, r.getInt("port"), (0 until a.length()).map { a.getString(it) }).also { kept += it; it.mark() }
    }
    private val kept = mutableListOf<Helper>()

    /**
     * Launches the app. Seams: Bonjour off (the emulator can't see the Mac's Bonjour anyway, and the
     * test helper doesn't advertise); the LAN sweep probes [sweepPort] (the helper's port) on
     * the device LAN; [fakeProximity] reads the token from a `--fake-proximity` helper over the
     * WebSocket instead of BLE; [transport]/[adapter] replace Bluetooth HID / the adapter.
     */
    fun launch(
        sweepPort: Int? = (helpers + kept).firstOrNull()?.port ?: NO_HELPER_PORT,
        fakeProximity: Boolean = false,
        transport: Transport? = null,
        adapter: StateFlow<TestSeam.Adapter>? = null,
    ) {
        TestSeam.noBonjour = true
        TestSeam.sweepPort = sweepPort
        // Phone mode: the Wi-Fi may isolate clients (no Bonjour, no LAN sweep), so also probe the Mac's tailnet IP on the helper's port.
        probing = Ctl.phone && sweepPort != null && sweepPort != NO_HELPER_PORT
        TestSeam.probeHost = if (probing) Ctl.host else null
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
        // ActivityScenario can't resume an activity the launcher stopped: bring the task back like the launcher does.
        shell("am start -n ${ctx.packageName}/dev.mote.remote.MainActivity -f 0x00020000") // FLAG_ACTIVITY_REORDER_TO_FRONT
        device.waitForIdle()
    }

    fun shell(cmd: String): String {
        if (Ctl.phone && Regex("""svc (wifi|data) disable|airplane-mode enable""").containsMatchIn(cmd))
            throw org.junit.AssumptionViolatedException(PHONE_SKIP)
        val pfd = ui.executeShellCommand(cmd)
        return android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes().toString(Charsets.UTF_8) }
    }

    fun grant(vararg perms: String) = perms.forEach { ui.grantRuntimePermission(ctx.packageName, it) }

    fun wifi(on: Boolean) {
        if (Ctl.phone && !on) throw org.junit.AssumptionViolatedException(PHONE_SKIP)
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

    /**
     * One poll interval. The Compose test clock only moves when the test advances it, so UI driven by
     * `delay()` (ConnectCard's 900 ms close, FindMacPanel's 5 s "not found", BtStep's 1.5 s) would
     * stall under a plain sleep: advance it in step with real time.
     */
    fun tick(ms: Long = 50) {
        runCatching { compose.mainClock.advanceTimeBy(ms) }
        Thread.sleep(ms)
    }

    /** Waits until [text] (text or content description) is on screen. */
    fun expect(text: String, timeoutMs: Long = 5_000, substring: Boolean = false) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (runCatching { shown(text, substring) }.getOrDefault(false)) return
            tick()
        }
        throw AssertionError("expected \"$text\" on screen within $timeoutMs ms")
    }

    /** Waits until any of [texts] is shown; returns the first that is. */
    fun expectAny(vararg texts: String, timeoutMs: Long = 5_000, substring: Boolean = false): String {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            texts.firstOrNull { runCatching { shown(it, substring) }.getOrDefault(false) }?.let { return it }
            tick()
        }
        throw AssertionError("expected one of ${texts.toList()} on screen within $timeoutMs ms")
    }

    fun expectGone(text: String, timeoutMs: Long = 5_000, substring: Boolean = false) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (!runCatching { shown(text, substring) }.getOrDefault(true)) return
            tick()
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
                resetNetwork()
                if (Ctl.phone) { closeShade(); device.pressHome() }
                var lost: Throwable? = null
                try {
                    base.evaluate()
                } catch (t: org.junit.AssumptionViolatedException) {
                    if (failedStep == null) record("setup", Status.MANUAL, t.message ?: PHONE_SKIP, "manual")
                    throw t
                } catch (t: Throwable) {
                    // Test-only artifact: the compose test dispatcher resumes Bt.observe's awaitClose off the
                    // main thread when the activity is disposed (production collects on Main). Not a step failure.
                    if (failedStep == null && isTeardownArtifact(t)) return
                    if (failedStep == null) { shot("setup"); record("setup", Status.FAIL, (t.message ?: t.toString()) + visibleSummary()) }
                    throw t
                } finally {
                    // The activity is left to the orchestrator (fresh process per test): closing it here
                    // disposes the composition on the compose test dispatcher (see isTeardownArtifact).
                    // Network first: stopping helpers and the queued results both need the controller.
                    resetNetwork()
                    if (Ctl.phone) closeShade()
                    helpers.forEach { runCatching { it.stop() } }
                    TestSeam.transport = null; TestSeam.adapter = null; TestSeam.noBonjour = false
                    TestSeam.sweepPort = null; TestSeam.probeHost = null; TestSeam.fakeProximity = false
                    lost = runCatching { flushUndelivered() }.exceptionOrNull()
                }
                lost?.let { throw it } // a passing test whose results never reached the controller fails
            }
        }
    }

    /**
     * Per-journey network reset (plan phase 4): a previous journey's Wi-Fi off / airplane mode must not leak in.
     * App data is reset by the orchestrator (clearPackageData = pm clear, which also revokes runtime permissions and
     * clears the first-run flags); it can't be done from here because the test runs inside the app's process.
     */
    private fun resetNetwork() {
        if (Ctl.phone) return // never touch the phone's radios: wireless adb rides on them
        runCatching { shell("cmd connectivity airplane-mode disable") }
        runCatching { shell("svc data enable") }
        runCatching { wifi(true) }
    }

    private fun isTeardownArtifact(t: Throwable): Boolean = generateSequence(t) { it.cause }.any {
        it is IllegalStateException && it.message?.contains("removeObserver must be called on the main thread") == true
    } // only the primary throwable (and its causes): a real failure carrying the artifact as suppressed still fails

    override fun apply(base: Statement, description: Description): Statement =
        RuleChain.outerRule(outer).around(compose).apply(base, description)
}
