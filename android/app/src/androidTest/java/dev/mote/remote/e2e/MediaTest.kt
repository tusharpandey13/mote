package dev.mote.remote.e2e

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.mote.remote.e2e.Rpt.consumer
import dev.mote.remote.e2e.Rpt.kb
import dev.mote.remote.e2e.Rpt.mouse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaTest {
    @get:Rule val rule = MoteRule()
    private val c get() = rule.compose
    private val t get() = rule.transport

    @Test
    fun everyConsumerButtonSendsItsUsageThenRelease() {
        val cases = listOf(
            "Play or pause" to 0xCD, "Next track" to 0xB5, "Previous track" to 0xB6, "Mute" to 0xE2,
            "Volume up" to 0xE9, "Volume down" to 0xEA, "Brightness up" to 0x6F, "Brightness down" to 0x70,
        )
        for ((label, usage) in cases) {
            t.clear()
            c.desc(label).press()
            assertEquals(label, listOf(consumer(usage), consumer(0)), t.awaitAtLeast(2))
        }
    }

    @Test
    fun escapeTapsEsc() {
        t.clear()
        c.desc("Escape").press()
        assertEquals(Rpt.tap(Usage.ESC), t.awaitAtLeast(2))
    }

    /** Design 5.5: hold -> after 350 ms repeat at 8/s, 16/s after 1.2 s; release stops instantly. */
    @Test
    fun volumeAndBrightnessRepeatAtDocumentedRates() {
        for ((label, usage) in listOf("Volume up" to 0xE9, "Volume down" to 0xEA, "Brightness up" to 0x6F)) {
            c.mainClock.autoAdvance = false
            t.clear()
            fun presses() = t.settle().count { it == consumer(usage) }
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
            // every step is press + release, nothing else
            val r = t.settle()
            assertEquals(r.size, total * 2)
            assertTrue(r.all { it == consumer(usage) || it == consumer(0) })
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
            assertEquals(label, 2, t.awaitAtLeast(2).size)
        }
    }

    /** D-pad arms hold the arrow key down (the host applies its own key repeat) and release on lift. */
    @Test
    fun dpadArmsHoldArrowKeys() {
        val pad = c.desc("Direction pad")
        val arms = listOf(Offset(0f, -0.7f) to Usage.UP, Offset(0.7f, 0f) to Usage.RIGHT, Offset(0f, 0.7f) to Usage.DOWN, Offset(-0.7f, 0f) to Usage.LEFT)
        for ((dir, usage) in arms) {
            c.mainClock.autoAdvance = false
            t.clear()
            pad.performTouchInput { down(center + Offset(dir.x * width / 2, dir.y * height / 2)) }
            c.mainClock.advanceTimeBy(1500, ignoreFrameDuration = true)
            assertEquals(listOf(kb(0, usage)), t.settle())
            pad.performTouchInput { up() }
            c.mainClock.autoAdvance = true
            assertEquals(listOf(kb(0, usage), kb(0)), t.awaitAtLeast(2))
        }
    }

    @Test
    fun dpadCenterTapIsReturnAndHoldIsSpace() {
        val pad = c.desc("Direction pad")
        t.clear()
        pad.press()
        assertEquals(Rpt.tap(Usage.RETURN), t.awaitAtLeast(2))

        c.mainClock.autoAdvance = false
        t.clear()
        pad.performTouchInput { down(center) }
        c.mainClock.advanceTimeBy(600, ignoreFrameDuration = true)
        pad.performTouchInput { up() }
        c.mainClock.autoAdvance = true
        assertEquals("hold 500 ms = Space, no Return", Rpt.tap(Usage.SPACE), t.awaitAtLeast(2))
    }

    @Test
    fun scrollChevronsSendOneNotch() {
        t.clear()
        c.desc("Scroll up one notch").press()
        assertEquals(listOf(mouse(wheel = -1)), t.awaitAtLeast(1))
        t.clear()
        c.desc("Scroll down one notch").press()
        assertEquals(listOf(mouse(wheel = 1)), t.awaitAtLeast(1))
    }

    /** Full deflection = band 4 = 30 notches/s; direction follows the thumb; release stops. */
    @Test
    fun scrollLeverScrollsProportionallyAndStopsOnRelease() {
        val lever = c.desc("Scroll lever")
        val thumbHalf = with(c.density) { 26.dp.toPx() }
        for ((sign, wheel) in listOf(-1 to -1, 1 to 1)) {
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
            assertTrue("only wheel=$wheel notches: $r", r.all { it == mouse(wheel = wheel) })
            assertTrue("~30 notches/s at full deflection, got ${r.size}", r.size in 26..32)
        }

        // small deflection inside the 6 dp dead zone sends nothing
        c.mainClock.autoAdvance = false
        t.clear()
        lever.performTouchInput { down(center); moveBy(Offset(0f, with(c.density) { 3.dp.toPx() })) }
        c.mainClock.advanceTimeBy(1000, ignoreFrameDuration = true)
        lever.performTouchInput { up() }
        c.mainClock.autoAdvance = true
        assertEquals(emptyList<Report>(), t.settle())
    }
}
