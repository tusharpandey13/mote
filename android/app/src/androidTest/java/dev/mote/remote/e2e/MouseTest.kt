package dev.mote.remote.e2e

import androidx.compose.ui.geometry.Offset
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
class MouseTest(@Suppress("unused") private val name: String, caps: Caps) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}") fun transports() = TRANSPORTS
        const val LEFT = 1
        const val RIGHT = 2
    }

    @get:Rule val rule = MoteRule(caps = caps)
    private val c get() = rule.compose
    private val t get() = rule.transport
    private val pad get() = c.desc("Touchpad")

    @Before fun openMouse() { rule.awaitLinkState(); c.mode("Mouse"); c.waitForIdle(); t.clear() }

    /** The touchpad reports zero deltas too; transports drop them (HID coalesces, the helper sums). */
    private fun FakeTransport.sent() = settle().filterNot { it == Call.Move(0f, 0f) }
    private fun FakeTransport.sentAtLeast(n: Int): List<Call> { awaitAtLeast(n); return sent() }

    @Test
    fun oneFingerMoveSendsRelativeMotionWithoutClicking() {
        pad.performTouchInput {
            down(center)
            repeat(10) { moveBy(Offset(12f, 0f)) }
            repeat(10) { moveBy(Offset(0f, -12f)) }
            up()
        }
        val r = t.sent()
        assertTrue("motion only: $r", r.isNotEmpty() && r.all { it is Call.Move })
        val moves = r.filterIsInstance<Call.Move>()
        assertTrue("moved right", moves.sumOf { it.dx.toDouble() } > 0)
        assertTrue("moved up", moves.sumOf { it.dy.toDouble() } < 0)
    }

    @Test
    fun tapIsLeftClick() {
        pad.press()
        assertEquals(listOf(Call.Click(LEFT)), t.sentAtLeast(1))
    }

    @Test
    fun twoFingerTapIsRightClick() {
        pad.performTouchInput {
            down(0, center - Offset(60f, 0f))
            down(1, center + Offset(60f, 0f))
            up(0)
            up(1)
        }
        assertEquals(listOf(Call.Click(RIGHT)), t.sentAtLeast(1))
    }

    /** Fingers moving up = wheel positive (host applies natural scrolling); one notch per 20 dp. */
    @Test
    fun twoFingerDragScrolls() {
        val stepPx = with(c.density) { 20.dp.toPx() }
        pad.performTouchInput {
            down(0, center - Offset(60f, 0f))
            down(1, center + Offset(60f, 0f))
            repeat(10) {
                updatePointerBy(0, Offset(0f, -stepPx / 2))
                updatePointerBy(1, Offset(0f, -stepPx / 2))
                move()
            }
            up(0); up(1)
        }
        val r = t.sent()
        assertTrue("scroll only: $r", r.isNotEmpty() && r.all { it is Call.Scroll })
        val s = r.filterIsInstance<Call.Scroll>()
        assertEquals("5 notches for 100 dp", 5, s.sumOf { it.wheel })
        assertTrue(s.all { it.pan == 0 })

        t.clear()
        pad.performTouchInput {
            down(0, center - Offset(0f, 60f))
            down(1, center + Offset(0f, 60f))
            repeat(4) {
                updatePointerBy(0, Offset(stepPx / 2, 0f))
                updatePointerBy(1, Offset(stepPx / 2, 0f))
                move()
            }
            up(0); up(1)
        }
        assertEquals("2 pan notches to the right", 2, t.sent().filterIsInstance<Call.Scroll>().sumOf { it.pan })
    }

    @Test
    fun dragLockHoldsLeftButtonWhileMoving() {
        c.desc("Drag lock off").press()
        assertEquals(listOf(Call.SetButton(LEFT, true)), t.sentAtLeast(1))
        c.desc("Drag lock on").assertExists()

        t.clear()
        pad.performTouchInput { down(center); repeat(5) { moveBy(Offset(15f, 0f)) }; up() }
        val r = t.sent()
        assertTrue("moves only, button stays down: $r", r.isNotEmpty() && r.all { it is Call.Move })

        t.clear()
        pad.press()
        assertEquals("tap does not click while locked", emptyList<Call>(), t.sent())

        c.desc("Drag lock on").press()
        assertEquals(listOf(Call.SetButton(LEFT, false)), t.sentAtLeast(1))
    }

    @Test
    fun leavingMouseModeReleasesDragLock() {
        c.desc("Drag lock off").press()
        t.sentAtLeast(1)
        t.clear()
        c.mode("Keys")
        c.waitForIdle()
        assertEquals(listOf(Call.SetButton(LEFT, false)), t.sentAtLeast(1))
    }

    @Test
    fun leftAndRightButtons() {
        c.desc("Left button").press()
        assertEquals(listOf(Call.SetButton(LEFT, true), Call.SetButton(LEFT, false)), t.sentAtLeast(2))
        t.clear()
        c.desc("Right button").press()
        assertEquals(listOf(Call.SetButton(RIGHT, true), Call.SetButton(RIGHT, false)), t.sentAtLeast(2))
    }
}
