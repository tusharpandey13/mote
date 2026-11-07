package dev.mote.remote.e2e

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import dev.mote.remote.net.Caps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class MediaTest(@Suppress("unused") private val name: String, caps: Caps) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}") fun transports() = TRANSPORTS
    }

    @get:Rule val rule = MoteRule(caps = caps)
    private val c get() = rule.compose
    private val t get() = rule.transport

    // clock-paused tests need the controls enabled before the clock stops
    @Before fun linked() = rule.awaitLinkState()

    @Test
    fun everyMediaControlSendsItsUsage() {
        val cases = listOf(
            "Play or pause" to Usage.PLAY_PAUSE, "Mute" to Usage.MUTE,
            "Next track, hold to fast-forward" to Usage.NEXT, "Previous track, hold to rewind" to Usage.PREV,
            "Volume up" to Usage.VOL_UP, "Volume down" to Usage.VOL_DOWN, // slider end zones: one step per tap
            "Brightness up" to Usage.BRIGHT_UP, "Brightness down" to Usage.BRIGHT_DOWN,
        )
        for ((label, usage) in cases) {
            t.clear()
            c.desc(label).press()
            assertEquals(label, listOf(Call.Consumer(usage)), t.awaitAtLeast(1))
        }
    }

    /** Design 5.5: brightness hold -> after 350 ms repeat at 8/s, 16/s after 1.2 s; release stops instantly. */
    @Test
    fun brightnessRepeatsAtDocumentedRates() {
        for ((label, usage) in listOf("Brightness up" to Usage.BRIGHT_UP, "Brightness down" to Usage.BRIGHT_DOWN)) {
            c.mainClock.autoAdvance = false
            t.clear()
            fun presses() = t.settle(quietMs = 100).count { it == Call.Consumer(usage) }
            val node = c.desc(label)
            node.performTouchInput { down(center) }
            c.mainClock.advanceTimeBy(300, ignoreFrameDuration = true)
            assertEquals("$label: one step before the 350 ms delay", 1, presses())
            c.mainClock.advanceTimeBy(1050, ignoreFrameDuration = true) // t = 1350
            val slow = presses() - 1 // steps in (350, 1350]: 8/s window of 1 s
            c.mainClock.advanceTimeBy(650, ignoreFrameDuration = true) // t = 2000
            val atTwo = presses()
            c.mainClock.advanceTimeBy(1000, ignoreFrameDuration = true) // t = 3000
            val fast = presses() - atTwo
            node.performTouchInput { up() }
            val total = presses()
            c.mainClock.advanceTimeBy(1000, ignoreFrameDuration = true)
            assertEquals("$label: release stops repeating", total, presses())
            assertTrue("$label: ~8 steps/s in the slow phase, got $slow", slow in 7..9)
            assertTrue("$label: ~16 steps/s in the fast phase, got $fast", fast in 15..17)
            assertTrue(t.calls.all { it == Call.Consumer(usage) })
            c.mainClock.autoAdvance = true
        }
    }

    @Test
    fun muteAndPlayNeverRepeat() {
        for (label in listOf("Mute", "Play or pause")) {
            c.mainClock.autoAdvance = false
            t.clear()
            c.desc(label).performTouchInput { down(center) }
            c.mainClock.advanceTimeBy(2000, ignoreFrameDuration = true)
            c.desc(label).performTouchInput { up() }
            c.mainClock.autoAdvance = true
            assertEquals(label, listOf(Call.Consumer(usage(label))), t.awaitAtLeast(1))
        }
    }

    private fun usage(label: String) = if (label == "Mute") Usage.MUTE else Usage.PLAY_PAUSE

    /** Prev/next: tap skips; holding past 400 ms holds fast-forward / rewind down until lift, no skip. */
    @Test
    fun holdToSeekVersusTapToSkip() {
        for ((label, seek) in listOf("Next track, hold to fast-forward" to Usage.FAST_FORWARD, "Previous track, hold to rewind" to Usage.REWIND)) {
            c.mainClock.autoAdvance = false
            t.clear()
            val cap = c.desc(label)
            cap.performTouchInput { down(center) }
            // the seek timer runs on the Compose clock: step it past the 400 ms threshold
            c.mainClock.advanceTimeBy(600, ignoreFrameDuration = true)
            assertEquals("$label: seek held, no skip yet", listOf(Call.ConsumerHold(seek, true)), t.settle())
            cap.performTouchInput { up() }
            c.mainClock.autoAdvance = true
            assertEquals("$label: lift releases the seek and never skips",
                listOf(Call.ConsumerHold(seek, true), Call.ConsumerHold(seek, false)), t.awaitAtLeast(2))
        }
    }

    /** D-pad arms (the r82-110 ring) hold the arrow key down (the host applies its own key repeat) and release on lift. */
    @Test
    fun dpadArmsHoldArrowKeys() {
        val pad = c.desc("Direction pad")
        val arms = listOf(Offset(0f, -0.82f) to "up", Offset(0.82f, 0f) to "right", Offset(0f, 0.82f) to "down", Offset(-0.82f, 0f) to "left")
        for ((dir, key) in arms) {
            c.mainClock.autoAdvance = false
            t.clear()
            pad.performTouchInput { down(center + Offset(dir.x * width / 2, dir.y * height / 2)) }
            c.mainClock.advanceTimeBy(1500, ignoreFrameDuration = true)
            assertEquals(listOf(Call.KeyDown(key)), t.settle())
            pad.performTouchInput { up() }
            c.mainClock.autoAdvance = true
            assertEquals(listOf(Call.KeyDown(key), Call.KeyUp(key)), t.awaitAtLeast(2))
        }
    }

    /** Rev 7: the D-pad centre is a joystick. Tap = left click, 450 ms hold = right click; OK/Return is gone. */
    @Test
    fun stickTapIsLeftClickAndHoldIsRightClick() {
        val pad = c.desc("Direction pad")
        t.clear()
        pad.press()
        assertEquals(listOf(Call.Click(MouseTest.LEFT)), t.awaitAtLeast(1))

        c.mainClock.autoAdvance = false
        t.clear()
        pad.performTouchInput { down(center) }
        c.mainClock.advanceTimeBy(600, ignoreFrameDuration = true)
        pad.performTouchInput { up() }
        c.mainClock.autoAdvance = true
        assertEquals("hold 450 ms = right click, no left click", listOf(Call.Click(MouseTest.RIGHT)), t.awaitAtLeast(1))
    }

    /** Full deflection to the right for 1 s ~ 1800 host px at Pointer speed 1.0, all to the right; release stops. */
    @Test
    fun stickDeflectionMovesThePointer() {
        val pad = c.desc("Direction pad")
        c.mainClock.autoAdvance = false
        t.clear()
        pad.performTouchInput { down(center); moveBy(Offset(with(c.density) { 30.dp.toPx() }, 0f)) } // past the 20dp travel
        c.mainClock.advanceTimeBy(1000, ignoreFrameDuration = true)
        pad.performTouchInput { up() }
        val moves = t.settle().filterIsInstance<Call.Move>()
        c.mainClock.advanceTimeBy(500, ignoreFrameDuration = true)
        c.mainClock.autoAdvance = true
        assertEquals("release stops", moves.size, t.settle().filterIsInstance<Call.Move>().size)
        assertTrue("moves right only: $moves", moves.all { it.dx > 0f && it.dy == 0f })
        val total = moves.sumOf { it.dx.toDouble() }
        assertTrue("~1800 px in 1 s, got $total", total in 1500.0..2000.0)
        assertTrue("no click after a drag", t.settle().none { it is Call.Click })
    }

    @Test
    fun scrollEndsSendOneNotch() {
        t.clear()
        c.desc("Scroll up one notch").press()
        assertEquals(listOf(Call.Scroll(-1, 0)), t.awaitAtLeast(1))
        t.clear()
        c.desc("Scroll down one notch").press()
        assertEquals(listOf(Call.Scroll(1, 0)), t.awaitAtLeast(1))
    }

    /** Holds [lever] at full deflection ([sign] -1 = up) for 1 s on the paused clock; returns the calls. */
    private fun deflect(lever: String, sign: Int): List<Call> {
        val node = c.desc(lever)
        c.mainClock.autoAdvance = false
        t.clear()
        node.performTouchInput { down(center); moveTo(center + Offset(0f, sign * height / 2f)) } // clamps to full travel
        c.mainClock.advanceTimeBy(1000, ignoreFrameDuration = true)
        node.performTouchInput { up() }
        val n = t.settle().size
        c.mainClock.advanceTimeBy(1000, ignoreFrameDuration = true)
        c.mainClock.autoAdvance = true
        val r = t.settle()
        assertEquals("$lever: release stops", n, r.size)
        return r
    }

    /** SCROLL slider: full deflection = band 4 = 30 notches/s; direction follows the thumb. */
    @Test
    fun scrollSliderScrollsProportionally() {
        for (sign in listOf(-1, 1)) {
            val r = deflect("Scroll lever", sign)
            assertTrue("only wheel=$sign: $r", r.all { it == Call.Scroll(sign, 0) })
            assertTrue("~30 notches/s at full deflection, got ${r.size}", r.size in 26..32)
        }
        // inside the 6 dp dead zone nothing is sent
        c.mainClock.autoAdvance = false
        t.clear()
        c.desc("Scroll lever").performTouchInput { down(center); moveBy(Offset(0f, with(c.density) { 3.dp.toPx() })) }
        c.mainClock.advanceTimeBy(1000, ignoreFrameDuration = true)
        c.desc("Scroll lever").performTouchInput { up() }
        c.mainClock.autoAdvance = true
        assertEquals(emptyList<Call>(), t.settle())
    }

    /** VOLUME slider: full deflection = band 4 = 12 steps/s; up = volume up. */
    @Test
    fun volumeSliderStepsProportionally() {
        for ((sign, usage) in listOf(-1 to Usage.VOL_UP, 1 to Usage.VOL_DOWN)) {
            val r = deflect("Volume lever", sign)
            assertTrue("only $usage: $r", r.all { it == Call.Consumer(usage) })
            assertTrue("~12 steps/s at full deflection, got ${r.size}", r.size in 10..14)
        }
    }

    /** Defaults (rev 7): Spaces and Return are favourites; the rest of the strip is one "Add favourite" slot. */
    @Test
    fun defaultFavouritesAreSpacesReturnAndAddSlot() {
        c.desc("Desktop 1, current").assertExists() // Spaces is the carousel row
        t.clear()
        c.desc("Return").press() // OK left the D-pad for the joystick
        assertEquals(listOf(Call.SetMods(0, 0), Call.TapAwait("return", 0)), t.awaitAtLeast(2))
        c.desc("Escape").assertDoesNotExist() // esc moved to Macros
        c.desc("Lock").assertDoesNotExist()
        c.onNode(hasText("Add favourite") and hasClickAction()).performClick()
        c.awaitDesc("New macro") // jumps to Macros
    }

    @Test
    fun controlsRejectInputWhileUnlinked() {
        rule.transport.linked.value = false
        rule.awaitLinkState()
        t.clear()
        for (label in listOf("Play or pause", "Brightness up", "Mute", "Volume up")) c.desc(label).press()
        c.desc("Direction pad").press()
        assertEquals(emptyList<Call>(), t.settle())
    }
}
