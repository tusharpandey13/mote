package dev.mote.remote.journeys

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.mote.remote.e2e.Call
import dev.mote.remote.e2e.Usage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** docs/journeys/media.md */
@RunWith(AndroidJUnit4::class)
class MediaJourney {
    @get:Rule val j = JourneyRule()

    private val stick = "Pointer stick. Drag to move the pointer, tap to click"

    /** Full deflection to the right for [ms] (Compose clock paused). */
    private fun drag(ms: Long) {
        val clock = j.compose.mainClock
        clock.autoAdvance = false
        try {
            val px = 30f * j.compose.density.density
            j.cNode("Direction pad").performTouchInput { down(center); moveBy(Offset(px, 0f)) }
            clock.advanceTimeBy(ms, ignoreFrameDuration = true)
            j.cNode("Direction pad").performTouchInput { up() }
            clock.advanceTimeBy(32, ignoreFrameDuration = true)
        } finally { clock.autoAdvance = true }
    }

    private fun ring(): androidx.compose.ui.test.TouchInjectionScope.() -> Offset = { center + Offset(0f, -0.82f * height / 2) }

    @Test fun main() {
        j.path("media")
        val (mac, _) = j.cLinkHelper("--fake-spaces", "6") // one fake display: no display chip in place of the hint
        j.step("S1") {
            for (t in listOf("FAVOURITES", "Spaces", "swipe or tap a desktop", "Add favourite", "SCROLL", "VOLUME")) j.expect(t)
            for (d in listOf("Edit", "Return", "Brightness down", "Brightness up", "Scroll up one notch", "Scroll lever", "Scroll down one notch",
                "Direction pad", stick, "Volume up", "Volume lever", "Volume down", "Mute", "Previous track, hold to rewind", "Play or pause",
                "Next track, hold to fast-forward")) j.expect(d)
        }
        j.step("S2") {
            val from = mac.mark().from
            drag(1000)
            Thread.sleep(600)
            val dx = mac.cMoveDx(from)
            if (mac.cLines("DRY move dx=-?[0-9.]+ dy=(?!0\\.0)", from).isNotEmpty()) throw AssertionError("vertical motion in a horizontal drag")
            if (dx !in 1500.0..2000.0) throw AssertionError("~1800 px in 1 s expected, got $dx")
            mac.assertNo("DRY button", 500)
        }
        j.step("S3") { mac.mark(); j.cPress("Direction pad"); mac.await("DRY button left click count=1") }
        j.step("S4") {
            mac.mark(); j.cHold("Direction pad", 600)
            mac.await("DRY button right click count=1"); mac.assertNo("DRY button left", 800)
        }
        j.step("S5") {
            mac.mark()
            j.cHold("Direction pad", 0, at = ring()) { Thread.sleep(1000) }
            mac.await("DRY key usage=82 mods=0 action=down")
            mac.await("DRY key usage=82 mods=0 action=down") // repeat (400 ms, then every 60 ms)
            mac.await("DRY key usage=82 mods=0 action=up")
        }
        j.step("S6") {
            mac.mark(); j.cPress("Next track, hold to fast-forward"); mac.await("DRY consumer next$")
            mac.event("protocol", "in", ".*\"t\":\"consumer\"")   // DU-1
            mac.event("input", "summary", ".*consumer")
        }
        j.step("S7") {
            mac.mark()
            j.cHold("Next track, hold to fast-forward", 600) { mac.await("DRY consumer fast down") }
            mac.await("DRY consumer fast up")
            mac.mark()
            j.cHold("Previous track, hold to rewind", 600) { mac.await("DRY consumer rewind down") }
            mac.await("DRY consumer rewind up")
            mac.assertNo("DRY consumer (next|prev)$", 500)
            mac.mark(); j.cPress("Previous track, hold to rewind"); mac.await("DRY consumer prev$")
        }
        j.step("S8") {
            mac.mark(); j.cPress("Volume up"); mac.await("DRY consumer volup$")
            j.cPress("Volume down"); mac.await("DRY consumer voldown$")
        }
        j.step("S9") {
            val from = mac.mark().from
            val clock = j.compose.mainClock
            clock.autoAdvance = false
            try {
                j.cNode("Volume lever").performTouchInput { down(center); moveTo(center + Offset(0f, -height / 2f)) }
                clock.advanceTimeBy(1000, ignoreFrameDuration = true)
                j.cNode("Volume lever").performTouchInput { up() }
                clock.advanceTimeBy(1000, ignoreFrameDuration = true)
            } finally { clock.autoAdvance = true }
            Thread.sleep(500)
            val n = mac.cLines("DRY consumer volup$", from).size
            if (n !in 10..14) throw AssertionError("~12 volup in 1 s at full travel, got $n")
            if (mac.cLines("DRY consumer voldown", from).isNotEmpty()) throw AssertionError("voldown while pulling up")
        }
        j.step("S10") {
            mac.mark(); j.cPress("Scroll up one notch"); mac.await("DRY scroll dx=0 dy=-24$")
            j.cPress("Scroll down one notch"); mac.await("DRY scroll dx=0 dy=24$")
        }
        j.step("S11") {
            mac.mark(); j.cPress("Mute"); mac.await("DRY consumer mute$")
            j.cPress("Play or pause"); mac.await("DRY consumer play$")
        }
        j.step("S12") {
            val from = mac.mark().from
            j.cHold("Brightness up", 2000)
            Thread.sleep(500)
            val n = mac.cLines("DRY consumer brightup$", from).size
            if (n !in 14..20) throw AssertionError("brightup repeats: expected 14-20 in 2 s, got $n")
            j.cPress("Brightness down"); mac.await("DRY consumer brightdown$")
        }
    }

    @Test fun offline() {
        j.path("media.offline")
        val fake = cHid(linked = false)
        j.cLaunchBt(fake)
        j.step("B1", "$C_BT (FakeTransport.linked = false)") {
            fake.clear()
            for (d in listOf("Play or pause", "Brightness up", "Mute", "Volume up", "Scroll up one notch", "Next track, hold to fast-forward")) j.cPress(d)
            j.cPress("Direction pad")
            val c = fake.settle()
            if (c.isNotEmpty()) throw AssertionError("unlinked controls sent $c")
        }
    }

    @Test fun seekcancel() {
        j.path("media.seekcancel")
        val (mac, _) = j.cLinkHelper()
        j.step("B2") {
            mac.mark()
            val clock = j.compose.mainClock
            clock.autoAdvance = false
            try {
                val n = j.cNode("Next track, hold to fast-forward")
                n.performTouchInput { down(center) }
                clock.advanceTimeBy(300, ignoreFrameDuration = true)
                n.performTouchInput { moveBy(Offset(0f, -height * 3f)); up() }
                clock.advanceTimeBy(500, ignoreFrameDuration = true)
            } finally { clock.autoAdvance = true }
            mac.assertNo("DRY consumer (next|fast)", 1_500)
        }
        j.step("B1") {
            mac.mark()
            val clock = j.compose.mainClock
            clock.autoAdvance = false
            try {
                j.cNode("Next track, hold to fast-forward").performTouchInput { down(center) }
                clock.advanceTimeBy(600, ignoreFrameDuration = true)
                mac.await("DRY consumer fast down")
                j.compose.onNode(androidx.compose.ui.test.hasText("Macros") and androidx.compose.ui.test.hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
                clock.advanceTimeBy(1000, ignoreFrameDuration = true)
            } finally { clock.autoAdvance = true }
            mac.await("DRY consumer fast up")
            mac.assertNo("DRY consumer next$", 500)
        }
    }

    @Test fun midhold() {
        j.path("media.midhold")
        val (mac, _) = j.cLinkHelper()
        j.step("B1") { mac.mark(); j.cHold("Direction pad", 300); mac.assertNo("DRY button", 1_500) }
    }

    @Test fun paused() {
        j.path("media.paused")
        val (mac, _) = j.cLinkHelper()
        j.step("B1", "faked: stdin-control `pause`") { mac.mark(); mac.stdin("pause"); mac.await("input paused") }
        j.step("B2") {
            j.appEvent("session", "state", "paused" to true)   // DU-5: the phone learns the Mac paused input
            j.cWait("paused", 5_000) { (dev.mote.remote.net.Net.wsStatus.value as? dev.mote.remote.net.WsStatus.Ready)?.paused == true }
            mac.mark(); j.cPress("Next track, hold to fast-forward"); mac.assertNo("DRY consumer next", 2_000)
            mac.event("input", "blocked", ".*\"reason\":\"paused\"")   // DU-5: the helper refused the key
        }
    }

    @Test fun a11y() {
        j.path("media.a11y")
        val (mac, _) = j.cLinkHelper()
        j.step("B1") {
            mac.mark()
            j.cNode(stick).performSemanticsAction(SemanticsActions.OnClick)
            mac.await("DRY button left click count=1")
            val n = j.cNode(stick).fetchSemanticsNode()
            val act = n.config[SemanticsActions.CustomActions].first { it.label == "Right click" }
            j.compose.runOnUiThread { act.action() }
            mac.await("DRY button right click count=1")
        }
        j.step("B2") {
            mac.mark()
            j.compose.onNode(hasContentDescription("Next track, hold to fast-forward")).performSemanticsAction(SemanticsActions.OnClick)
            mac.await("DRY consumer next$"); mac.assertNo("DRY consumer fast", 800)
        }
    }

    @Test fun bt() {
        j.path("media.bt")
        val fake = cHid()
        j.cLaunchBt(fake)
        j.step("B1", C_BT) {
            fake.clear(); drag(1000)
            val m = fake.settle().cOf<Call.Move>()
            val dx = m.sumOf { it.dx.toDouble() }
            if (m.any { it.dx <= 0f || it.dy != 0f } || dx !in 1500.0..2000.0) throw AssertionError("moves right ~1800 expected: total $dx, $m")
        }
        j.step("B2", C_BT) { fake.clear(); j.cPress("Direction pad"); j.cAwaitCall(fake, "Click(1)") { it == listOf(Call.Click(1)) } }
        j.step("B3", C_BT) { fake.clear(); j.cHold("Direction pad", 600); j.cAwaitCall(fake, "Click(2) only") { it == listOf(Call.Click(2)) } }
        j.step("B4", C_BT) {
            fake.clear(); j.cHold("Direction pad", 1000, at = ring())
            j.cAwaitCall(fake, "KeyDown/KeyUp up") { it == listOf(Call.KeyDown("up"), Call.KeyUp("up")) }
        }
        j.step("B5", C_BT) {
            fake.clear(); j.cPress("Next track, hold to fast-forward")
            j.cAwaitCall(fake, "Consumer(NEXT)") { it == listOf(Call.Consumer(Usage.NEXT)) }
            fake.clear(); j.cHold("Next track, hold to fast-forward", 600)
            j.cAwaitCall(fake, "seek hold") { it == listOf(Call.ConsumerHold(Usage.FAST_FORWARD, true), Call.ConsumerHold(Usage.FAST_FORWARD, false)) }
        }
        j.step("B6", C_BT) {
            val cases = listOf("Volume up" to Call.Consumer(Usage.VOL_UP), "Volume down" to Call.Consumer(Usage.VOL_DOWN),
                "Scroll up one notch" to Call.Scroll(-1, 0), "Scroll down one notch" to Call.Scroll(1, 0),
                "Mute" to Call.Consumer(Usage.MUTE), "Play or pause" to Call.Consumer(Usage.PLAY_PAUSE),
                "Brightness up" to Call.Consumer(Usage.BRIGHT_UP), "Brightness down" to Call.Consumer(Usage.BRIGHT_DOWN))
            for ((d, call) in cases) { fake.clear(); j.cPress(d); j.cAwaitCall(fake, "$d -> $call") { it == listOf(call) } }
        }
    }
}
