package dev.mote.remote.e2e

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.mote.remote.e2e.Rpt.mouse
import dev.mote.remote.e2e.Rpt.signed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MouseTest {
    @get:Rule val rule = MoteRule()
    private val c get() = rule.compose
    private val t get() = rule.transport
    private val pad get() = c.desc("Touchpad")

    @Before fun openMouse() { c.mode("Mouse"); c.waitForIdle(); t.clear() }

    @Test
    fun oneFingerMoveSendsRelativeMotionWithoutClicking() {
        pad.performTouchInput {
            down(center)
            repeat(10) { moveBy(Offset(12f, 0f)) }
            repeat(10) { moveBy(Offset(0f, -12f)) }
            up()
        }
        val r = t.settle()
        assertTrue("motion reports only: $r", r.isNotEmpty() && r.all { it.id == Rpt.MOUSE && it.bytes[0] == 0 })
        assertTrue("moved right", r.sumOf { signed(it.bytes[1]) } > 0)
        assertTrue("moved up", r.sumOf { signed(it.bytes[2]) } < 0)
        assertTrue("no wheel", r.all { it.bytes[3] == 0 && it.bytes[4] == 0 })
    }

    @Test
    fun tapIsLeftClick() {
        pad.press()
        assertEquals(listOf(mouse(buttons = 1), mouse()), t.awaitAtLeast(2))
    }

    @Test
    fun twoFingerTapIsRightClick() {
        pad.performTouchInput {
            down(0, center - Offset(60f, 0f))
            down(1, center + Offset(60f, 0f))
            up(0)
            up(1)
        }
        assertEquals(listOf(mouse(buttons = 2), mouse()), t.awaitAtLeast(2))
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
        val r = t.settle()
        assertTrue("scroll reports only: $r", r.isNotEmpty() && r.all { it.bytes[0] == 0 && it.bytes[1] == 0 && it.bytes[2] == 0 })
        assertEquals("5 notches for 100 dp", 5, r.sumOf { signed(it.bytes[3]) })
        assertTrue(r.all { it.bytes[4] == 0 })

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
        val p = t.settle()
        assertEquals("2 pan notches to the right", 2, p.sumOf { signed(it.bytes[4]) })
    }

    @Test
    fun dragLockHoldsLeftButtonWhileMoving() {
        c.desc("Drag lock off").press()
        assertEquals(listOf(mouse(buttons = 1)), t.awaitAtLeast(1))
        c.desc("Drag lock on").assertExists()

        t.clear()
        pad.performTouchInput { down(center); repeat(5) { moveBy(Offset(15f, 0f)) }; up() }
        val r = t.settle()
        assertTrue("moves keep the button held: $r", r.isNotEmpty() && r.all { it.bytes[0] == 1 })

        t.clear()
        pad.press()
        assertEquals("tap does not click while locked", emptyList<Report>(), t.settle())

        c.desc("Drag lock on").press()
        assertEquals(listOf(mouse()), t.awaitAtLeast(1))
    }

    @Test
    fun leavingMouseModeReleasesDragLock() {
        c.desc("Drag lock off").press()
        t.awaitAtLeast(1)
        t.clear()
        c.mode("Keys")
        c.waitForIdle()
        assertEquals(listOf(mouse()), t.awaitAtLeast(1))
    }

    @Test
    fun leftAndRightButtons() {
        c.desc("Left button").press()
        assertEquals(listOf(mouse(buttons = 1), mouse()), t.awaitAtLeast(2))
        t.clear()
        c.desc("Right button").press()
        assertEquals(listOf(mouse(buttons = 2), mouse()), t.awaitAtLeast(2))
    }
}
