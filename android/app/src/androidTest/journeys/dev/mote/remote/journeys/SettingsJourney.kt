package dev.mote.remote.journeys

import android.view.ViewGroup
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.mote.remote.data.Prefs
import dev.mote.remote.data.ThemeChoice
import dev.mote.remote.e2e.Call
import dev.mote.remote.hid.Host
import dev.mote.remote.labs.Labs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** docs/journeys/settings.md */
@RunWith(AndroidJUnit4::class)
class SettingsJourney {
    @get:Rule val j = JourneyRule()

    private fun openSettings() { j.tap("Menu"); j.tap("Settings"); j.expect("Natural scrolling") }
    private fun back() { j.device.pressBack(); j.expect("Brightness up", timeoutMs = 3_000) }

    private fun stickMoveTotal(fake: dev.mote.remote.e2e.FakeTransport): Double {
        val clock = j.compose.mainClock
        fake.clear()
        clock.autoAdvance = false
        try {
            val px = 30f * j.compose.density.density
            j.cNode("Direction pad").performTouchInput { down(center); moveBy(Offset(px, 0f)) }
            clock.advanceTimeBy(1000, ignoreFrameDuration = true)
            j.cNode("Direction pad").performTouchInput { up() }
            clock.advanceTimeBy(32, ignoreFrameDuration = true)
        } finally { clock.autoAdvance = true }
        return fake.settle().cOf<Call.Move>().sumOf { it.dx.toDouble() }
    }

    @Test fun main() {
        j.path("settings")
        val fake = cHid()
        j.cLaunchBt(fake)
        j.step("S1") {
            j.tap("Menu")
            for (t in listOf("Mote", "Close", "Theme", "Dark", "Light", "System", "Manage devices", "1 paired", "Haptics", "Standard",
                "Settings", "Pointer speed, scrolling, keyboard", "About Mote", "Version 0.6.0-dev")) j.expect(t)
        }
        j.step("S2") {
            j.tap("Dark")
            j.cWait("theme DARK persisted", 3_000) { runBlocking { Prefs.theme(j.ctx).first() } == ThemeChoice.DARK }
        }
        j.step("S3") {
            j.tap("Settings")
            for (t in listOf("POINTER", "Pointer speed", "1x (default). Higher moves farther per swipe.", "Scroll speed",
                "1x (default). Two-finger scrolling on the touchpad.", "Natural scrolling", "Content follows your fingers", "Right-click",
                "Hold", "2-finger tap", "SPACES", "Desktops", "4 on the Mac. Sets the cards in the Spaces carousel over Bluetooth.",
                "Use ⌃1–⌃9 shortcuts",
                "Turn on Switch to Desktop 1-9 on the Mac (System Settings, Keyboard Shortcuts, Mission Control). Off: Mote steps with ⌃← and ⌃→.",
                "KEYBOARD", "Clear modifiers on mode switch", "Release modifiers after 30 s idle", "SCREEN", "Keep screen on while connected",
                "Extra dim", "For dark rooms and presentations", "LABS")) j.expect(t)
            val sd = mapOf("Pointer speed" to "1.00x", "Natural scrolling" to "On", "Use control 1 to 9 shortcuts" to "Off",
                "Clear modifiers on mode switch" to "Off", "Release modifiers after 30 s idle" to "On", "Keep screen on while connected" to "On", "Extra dim" to "Off")
            for ((d, v) in sd) if (j.cStateDesc(d) != v) throw AssertionError("$d: state \"${j.cStateDesc(d)}\", expected \"$v\"")
        }
        j.step("S4") {
            j.cSlider("Pointer speed", 7, 8)
            j.expect("2.0x. Higher moves farther per swipe.")
            if (j.cStateDesc("Pointer speed") != "2.00x") throw AssertionError("slider state ${j.cStateDesc("Pointer speed")}")
        }
        j.step("S5", C_BT) {
            back()
            val total = stickMoveTotal(fake)
            if (total !in 3000.0..4000.0) throw AssertionError("pointer speed 2x: ~3600 px in 1 s expected, got $total")
        }
        j.step("S6", C_BT) {
            openSettings()
            j.cSlider("Desktops", 5, 16)
            j.expect("6 on the Mac. Sets the cards in the Spaces carousel over Bluetooth.")
            back()
            j.cScrollRow(5); j.expect("Desktop 6"); j.expectNot("Desktop 7")
            j.cScrollRow(0)
        }
        j.step("S7", C_BT) {
            openSettings()
            j.tap("Use control 1 to 9 shortcuts")
            j.cWait("switch On", 3_000) { j.cStateDesc("Use control 1 to 9 shortcuts") == "On" }
            back()
            fake.clear(); j.cClick("Desktop 3")
            j.cAwaitCall(fake, "Tap(3, ctrl)") { it == listOf(Call.Tap("3", 1)) }
        }
        j.step("S9") {
            openSettings(); j.device.pressBack(); j.expect("Brightness up")
            j.expectNot("Natural scrolling")
        }
    }

    @Test fun haptics() {
        j.path("settings.haptics")
        j.cLaunchBt(cHid())
        j.step("B1") {
            j.tap("Menu"); j.tap("Haptics")
            for (t in listOf("STRENGTH", "Off", "Light", "Standard", "Strong", "TRY THEM", "Try Tick", "Try Click", "Try Heavy", "Try Lock", "Try Reject",
                "Key presses", "Tick on every key and D-pad step", "Slider detents", "Ticks as scroll and volume speed change",
                "Connect and disconnect", "Distinct pulses when the link changes",
                "Haptics follow Android's touch feedback setting. If you feel nothing, turn on Settings, then Sound & vibration, then Touch feedback.")) j.expect(t)
            for (d in listOf("Key presses", "Slider detents", "Connect and disconnect")) if (j.cStateDesc(d) != "On") throw AssertionError("$d not On")
        }
        j.step("B2") {
            j.tap("Off")
            back(); j.tap("Menu")
            j.expect("Off")
            j.expectNot("Standard")
        }
    }

    /** True if any view of the activity's content asks to keep the screen on (Compose sets it on its AndroidComposeView). */
    private fun keepOn(): Boolean {
        var on = false
        fun walk(v: android.view.View) { if (v.keepScreenOn) on = true; if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i)) }
        val act = j.cActivity() // cActivity uses runOnMainSync itself: call it off the main thread
        InstrumentationRegistry.getInstrumentation().runOnMainSync { walk(act.window.decorView) }
        return on
    }

    /** The window manager's view: our MainActivity window carries FLAG_KEEP_SCREEN_ON. */
    private fun wmKeepOn(): Boolean {
        val d = j.shell("dumpsys window windows")
        val i = d.indexOf("${j.ctx.packageName}/dev.mote.remote.MainActivity")
        if (i < 0) return false
        val block = d.substring(i, minOf(d.length, i + 4000)).substringBefore("Window #")
        return "KEEP_SCREEN_ON" in block
    }

    private fun keepDiag() = "view flag=${keepOn()}, window flag=${wmKeepOn()}, linked=${dev.mote.remote.net.Remote.linked.value}"

    private fun brightness(): Float {
        var b = 0f
        val act = j.cActivity()
        InstrumentationRegistry.getInstrumentation().runOnMainSync { b = act.window.attributes.screenBrightness }
        return b
    }

    @Test fun screen() {
        j.path("settings.screen")
        val fake = cHid()
        j.cLaunchBt(fake)
        j.step("B1", "real (instrumentation reads the view flag)") {
            fun on() = keepOn() || wmKeepOn()
            runCatching { j.cWait("keepScreenOn while linked", 3_000) { on() } }.onFailure { throw AssertionError("not kept on while linked: ${keepDiag()}") }
            fake.linked.value = false
            runCatching { j.cWait("keepScreenOn off when unlinked", 3_000) { !on() } }.onFailure { throw AssertionError("still on while unlinked: ${keepDiag()}") }
            fake.linked.value = true
            runCatching { j.cWait("keepScreenOn back", 3_000) { on() } }.onFailure { throw AssertionError("not back on: ${keepDiag()}") }
        }
        j.step("B2", "real (instrumentation)") {
            openSettings()
            j.tap("Extra dim")
            j.cWait("screenBrightness 0.02", 3_000) { brightness() == 0.02f }
            j.tap("Extra dim")
            j.cWait("screenBrightness -1", 3_000) { brightness() == -1f }
        }
    }

    @Test fun keyboard() {
        j.path("settings.keyboard")
        val fake = cHid()
        j.cLaunchBt(fake)
        j.step("B1") {
            j.tap("Keys")
            j.cClick("command, off")
            j.expect("command, armed"); j.expect("next key"); j.expect("Clear modifiers")
        }
        j.step("B2") {
            j.cAdvance(30_100)
            j.expect("command, off"); j.expect("Modifiers: tap once to arm, twice to lock")
        }
        j.step("B3", C_BT) {
            openSettings(); j.tap("Clear modifiers on mode switch"); j.device.pressBack()
            j.expect("command, off")
            j.cClick("command, off"); j.expect("command, armed")
            fake.clear()
            j.tap("Media"); j.expect("Brightness up")
            j.cAwaitCall(fake, "SetMods(0,0)") { Call.SetMods(0, 0) in it }
            j.tap("Keys"); j.expect("command, off")
        }
    }

    /** Two fingers 40 dp up on the touchpad (12 moves). */
    private fun twoFingerUp() {
        val step = 40f * j.compose.density.density / 10
        j.cNode("Touchpad").performTouchInput {
            down(0, center - Offset(40f, 0f)); down(1, center + Offset(40f, 0f))
            repeat(10) { updatePointerBy(0, Offset(0f, -step)); updatePointerBy(1, Offset(0f, -step)); move() }
            up(0); up(1)
        }
    }

    @Test fun pointerMouse() {
        j.path("settings.pointer-mouse")
        val fake = cHid()
        j.cLaunchBt(fake)
        j.tap("Mouse"); j.expect("Touchpad")
        fun wheel() = fake.settle().cOf<Call.Scroll>().sumOf { it.wheel }
        j.step("B1", C_BT) { fake.clear(); twoFingerUp(); val w = wheel(); if (w != 2) throw AssertionError("wheel sum 2 expected, got $w (${fake.calls})") }
        j.step("B2", C_BT) {
            openSettings(); j.tap("Natural scrolling"); j.device.pressBack(); j.expect("Touchpad")
            fake.clear(); twoFingerUp(); val w = wheel(); if (w != -2) throw AssertionError("wheel sum -2 expected, got $w")
        }
        j.step("B3", C_BT) {
            fake.clear()
            j.cNode("Touchpad").performTouchInput { down(center); advanceEventTime(600); up() }
            j.cAwaitCall(fake, "Click(2)") { it == listOf(Call.Click(2)) }
        }
        j.step("B4", C_BT) {
            openSettings(); j.tap("2-finger tap"); j.device.pressBack(); j.expect("Touchpad")
            fake.clear()
            j.cNode("Touchpad").performTouchInput { down(center); advanceEventTime(600); up() }
            Thread.sleep(500)
            if (fake.settle().any { it is Call.Click }) throw AssertionError("hold must not click with 2-finger tap: ${fake.calls}")
            j.cNode("Touchpad").performTouchInput { down(0, center); down(1, center + Offset(60f, 0f)); advanceEventTime(80); up(0); up(1) }
            j.cAwaitCall(fake, "Click(2)") { it == listOf(Call.Click(2)) }
        }
    }

    @Test fun labs() {
        j.path("settings.labs")
        j.cLaunchBt(cHid())
        openSettings()
        j.step("B1") {
            val rows = listOf(
                "Open & TV mode (preview)" to "An Open… sheet that drives the Mac, and a TV remote for YouTube. Needs Mote Helper with Labs › TV mode on.",
                "Smart remote (preview)" to "Adapts Media to what is on the Mac: now playing with a scrubber, app buttons, keyboard on text fields. Needs Mote Helper with Labs › Context & now playing on.",
                "Browser video control (preview)" to "Control the video in the Mac's browser tab with the Mote Video Bridge extension. Needs Mote Helper with Labs › Browser extension bridge on.",
                "Live screen (preview)" to "See the Mac's screen and tap to click. Needs Mote Helper over Wi-Fi or Tailscale with Labs › Live screen on.",
            )
            for ((t, s) in rows) { j.expect(t); j.expect(s); if (j.cStateDesc(t) != "Off") throw AssertionError("$t not Off") }
            j.expectNot("Instant pairing (preview)")
            val tops = rows.map { (t, _) -> j.compose.onNode(androidx.compose.ui.test.hasText(t), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.top }
            if (tops != tops.sorted()) throw AssertionError("Labs order differs: $tops")
        }
        j.step("B2") {
            j.tap("Live screen (preview)")
            j.cWait("labs.live_screen", 3_000) { runBlocking { Labs.flow(j.ctx, Labs.LIVE_SCREEN).first() } }
            j.device.pressBack(); j.tap("Menu")
            j.expect("Screen (preview)"); j.expect("See the Mac's screen, tap to click")
        }
    }

    @Test fun about() {
        j.path("settings.about")
        j.cLaunchBt(cHid())
        j.step("B1") {
            j.tap("Menu"); j.tap("About Mote")
            for (t in listOf("VERSION", "Mote 0.6.0-dev", "Keyboard, mouse and media remote over Bluetooth HID", "OPEN-SOURCE LICENCES",
                "Unbounded", "Inter", "AndroidX and Jetpack Compose", "SOURCE", "github.com/tusharpandey13/mote", "Issues and releases")) j.expect(t)
        }
    }

    @Test fun devices() {
        j.path("settings.devices")
        val adapter = cAdapter()
        j.cLinkHelper(transport = cHid(linked = false), adapter = adapter)
        j.step("B1") { j.tap("Menu"); j.tap("Manage devices"); j.expect("Devices"); j.device.pressBack(); j.expectGone("Devices") }
        j.step("B2", "faked: BT adapter (TestSeam.adapter: 2 bonded hosts)") {
            j.cGrantBt()
            adapter.value = adapter.value.copy(hosts = listOf(C_MAC, Host("AA:BB:CC:DD:EE:02", "Studio iMac", Host.Kind.DESKTOP)))
            j.tap("Menu")
            j.expect("2 paired", timeoutMs = 5_000)
        }
    }
}
