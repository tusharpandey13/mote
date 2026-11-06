package dev.mote.remote.e2e

import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.mote.remote.e2e.Rpt.CMD
import dev.mote.remote.e2e.Rpt.SHIFT
import dev.mote.remote.e2e.Rpt.kb
import dev.mote.remote.e2e.Usage.letter
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KeysTest {
    @get:Rule val rule = MoteRule()
    private val c get() = rule.compose
    private val t get() = rule.transport

    @Before fun openKeys() { c.mode("Keys"); c.waitForIdle(); t.clear() }

    @Test
    fun plainKeyGoesDownAndUpWithTheFinger() {
        c.desc("A").press()
        assertEquals(listOf(kb(0, letter('a')), kb(0)), t.awaitAtLeast(2))
    }

    /** off -tap-> armed -key-> off (one-shot); the modifier is held on the host while armed. */
    @Test
    fun tapArmsOneShotModifier() {
        c.desc("command, off").performClick()
        c.desc("command, armed").assertExists()
        assertEquals(listOf(kb(CMD)), t.awaitAtLeast(1))

        t.clear()
        c.desc("A").press()
        assertEquals(listOf(kb(CMD, letter('a')), kb(CMD), kb(0)), t.awaitAtLeast(3))
        c.desc("command, off").assertExists()

        t.clear()
        c.desc("B").press()
        assertEquals(listOf(kb(0, letter('b')), kb(0)), t.awaitAtLeast(2))
    }

    @Test
    fun armedThenTapAgainClears() {
        c.desc("shift, off").performClick()
        Thread.sleep(400) // slower than the 300 ms double-tap window
        c.desc("shift, armed").performClick()
        c.desc("shift, off").assertExists()
        assertEquals(listOf(kb(SHIFT), kb(0)), t.awaitAtLeast(2))
    }

    @Test
    fun doubleTapLocksUntilTappedAgain() {
        // two real taps well inside the 300 ms window (performClick waits for idle in between)
        c.desc("command, off").performTouchInput { click(); advanceEventTime(80); click() }
        c.desc("command, locked").assertExists()
        t.awaitAtLeast(2)

        t.clear()
        c.desc("A").press()
        c.desc("B").press()
        assertEquals(
            listOf(kb(CMD, letter('a')), kb(CMD), kb(CMD, letter('b')), kb(CMD)),
            t.awaitAtLeast(4),
        )
        c.desc("command, locked").performClick()
        c.desc("command, off").assertExists()
    }

    @Test
    fun holdLocks() {
        c.mainClock.autoAdvance = false
        val option = c.onNode(hasContentDescription("option, ", substring = true))
        option.performTouchInput { down(center) }
        c.mainClock.advanceTimeBy(500, ignoreFrameDuration = true)
        option.performTouchInput { up() }
        c.mainClock.autoAdvance = true
        c.desc("option, locked").assertExists()
        assertEquals(listOf(kb(Rpt.ALT)), t.awaitAtLeast(1))
    }

    @Test
    fun chordOfArmedModifiersAppliesToNextKeyThenClears() {
        c.desc("command, off").performClick()
        Thread.sleep(350)
        c.desc("shift, off").performClick()
        c.onNode(hasText("+ next key")).assertExists()
        t.awaitAtLeast(2)
        t.clear()
        c.desc("4").press()
        assertEquals(listOf(kb(CMD or SHIFT, Usage.N4), kb(CMD or SHIFT), kb(0)), t.awaitAtLeast(3))
        c.desc("command, off").assertExists()
        c.desc("shift, off").assertExists()
    }

    @Test
    fun clearModifiersReleasesEverything() {
        c.desc("control, off").performClick()
        c.desc("control, armed").performClick()
        c.desc("command, off").performClick()
        t.awaitAtLeast(3)
        t.clear()
        c.desc("Clear modifiers").performClick()
        assertEquals(listOf(kb(0)), t.awaitAtLeast(1))
        c.desc("control, off").assertExists()
        c.desc("command, off").assertExists()
    }

    /** The text box types US-layout keystrokes; shifted characters are wrapped in Shift. */
    @Test
    fun textBoxTypesUsKeystrokes() {
        c.onNode(hasSetTextAction()).performTextInput("Hi!")
        c.desc("Send text").press()
        val expected = Rpt.tap(letter('h'), SHIFT) + Rpt.tap(letter('i')) + Rpt.tap(Usage.N1, SHIFT)
        assertEquals(expected, rule.awaitReports(expected.size))
        c.onNode(hasSetTextAction()).assert(hasText(""))
    }

    @Test
    fun untypeableCharactersAreSkipped() {
        c.onNode(hasSetTextAction()).performTextInput("é a")
        c.desc("Send text").press()
        val expected = Rpt.tap(Usage.SPACE) + Rpt.tap(letter('a'))
        assertEquals(expected, rule.awaitReports(expected.size))
    }

    @Test
    fun shiftedSymbolKeyTapsWithShift() {
        c.desc("Symbols").performClick()
        c.desc("!").press()
        assertEquals(Rpt.tap(Usage.N1, SHIFT), t.awaitAtLeast(4))
    }
}
