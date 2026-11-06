package dev.mote.remote.e2e

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import dev.mote.remote.net.Caps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
    @org.junit.Before fun linked() = rule.awaitLinkState()

    @Test
    fun everyConsumerButtonSendsItsUsage() {
        val cases = listOf(
            "Play or pause" to Usage.PLAY_PAUSE, "Next track" to Usage.NEXT, "Previous track" to Usage.PREV, "Mute" to Usage.MUTE,
            "Volume up" to Usage.VOL_UP, "Volume down" to Usage.VOL_DOWN, "Brightness up" to Usage.BRIGHT_UP, "Brightness down" to Usage.BRIGHT_DOWN,
        )
        for ((label, usage) in cases) {
            t.clear()
            c.desc(label).press()
            assertEquals(label, listOf(Call.Consumer(usage)), t.awaitAtLeast(1))
        }
    }

    @Test
    fun escapeTapsEsc() {
        t.clear()
        c.desc("Escape").press()
        assertEquals(listOf(Call.Tap("esc")), t.awaitAtLeast(1))
    }

    /** Design 5.5: hold -> after 350 ms repeat at 8/s, 16/s after 1.2 s; release stops instantly. */
    @Test
    fun volumeAndBrightnessRepeatAtDocumentedRates() {
        for ((label, usage) in listOf("Volume up" to Usage.VOL_UP, "Volume down" to Usage.VOL_DOWN, "Brightness up" to Usage.BRIGHT_UP)) {
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
    fun muteAndTrackButtonsNeverRepeat() {
        for (label in listOf("Mute", "Play or pause", "Next track")) {
            c.mainClock.autoAdvance = false
            t.clear()
            c.desc(label).performTouchInput { down(center) }
            c.mainClock.advanceTimeBy(2000, ignoreFrameDuration = true)
            c.desc(label).performTouchInput { up() }
            c.mainClock.autoAdvance = true
            assertEquals(label, 1, t.awaitAtLeast(1).size)
        }
    }

    /** D-pad arms hold the arrow key down (the host applies its own key repeat) and release on lift. */
    @Test
    fun dpadArmsHoldArrowKeys() {
        val pad = c.desc("Direction pad")
        val arms = listOf(Offset(0f, -0.7f) to "up", Offset(0.7f, 0f) to "right", Offset(0f, 0.7f) to "down", Offset(-0.7f, 0f) to "left")
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

    @Test
    fun dpadCenterTapIsReturnAndHoldIsSpace() {
        val pad = c.desc("Direction pad")
        t.clear()
        pad.press()
        assertEquals(listOf(Call.Tap("return")), t.awaitAtLeast(1))

        c.mainClock.autoAdvance = false
        t.clear()
        pad.performTouchInput { down(center) }
        c.mainClock.advanceTimeBy(600, ignoreFrameDuration = true)
        pad.performTouchInput { up() }
        c.mainClock.autoAdvance = true
        assertEquals("hold 500 ms = Space, no Return", listOf(Call.Tap("space")), t.awaitAtLeast(1))
    }

    @Test
    fun scrollChevronsSendOneNotch() {
        t.clear()
        c.desc("Scroll up one notch").press()
        assertEquals(listOf(Call.Scroll(-1, 0)), t.awaitAtLeast(1))
        t.clear()
        c.desc("Scroll down one notch").press()
        assertEquals(listOf(Call.Scroll(1, 0)), t.awaitAtLeast(1))
    }

    /** Full deflection = band 4 = 30 notches/s; direction follows the thumb; release stops. */
    @Test
    fun scrollLeverScrollsProportionallyAndStopsOnRelease() {
        val lever = c.desc("Scroll lever")
        val thumbHalf = with(c.density) { 26.dp.toPx() }
        for (sign in listOf(-1, 1)) {
            c.mainClock.autoAdvance = false
            t.clear()
            lever.performTouchInput {
                down(center)
                moveTo(center + Offset(0f, sign * (height / 2f - thumbHalf + 4f)))
            }
            c.mainClock.advanceTimeBy(1000, ignoreFrameDuration = true)
            lever.performTouchInput { up() }
            val n = t.settle().size
            c.mainClock.advanceTimeBy(1000, ignoreFrameDuration = true)
            c.mainClock.autoAdvance = true
            val r = t.settle()
            assertEquals("release stops scrolling", n, r.size)
            assertTrue("only wheel=$sign notches: $r", r.all { it == Call.Scroll(sign, 0) })
            assertTrue("~30 notches/s at full deflection, got ${r.size}", r.size in 26..32)
        }

        // small deflection inside the 6 dp dead zone sends nothing
        c.mainClock.autoAdvance = false
        t.clear()
        lever.performTouchInput { down(center); moveBy(Offset(0f, with(c.density) { 3.dp.toPx() })) }
        c.mainClock.advanceTimeBy(1000, ignoreFrameDuration = true)
        lever.performTouchInput { up() }
        c.mainClock.autoAdvance = true
        assertEquals(emptyList<Call>(), t.settle())
    }

    @Test
    fun controlsRejectInputWhileUnlinked() {
        rule.transport.linked.value = false
        rule.awaitLinkState()
        t.clear()
        for (label in listOf("Play or pause", "Volume up", "Escape")) c.desc(label).press()
        c.desc("Direction pad").press()
        assertEquals(emptyList<Call>(), t.settle())
    }
}
