package dev.mote.remote.e2e

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import dev.mote.remote.e2e.Mods.ALT
import dev.mote.remote.e2e.Mods.CMD
import dev.mote.remote.e2e.Mods.SHIFT
import dev.mote.remote.net.Caps
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class KeysTest(@Suppress("unused") private val name: String, caps: Caps) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}") fun transports() = TRANSPORTS
    }

    @get:Rule val rule = MoteRule(caps = caps)
    private val c get() = rule.compose
    private val t get() = rule.transport

    /**
     * Two real taps; returns the wall-clock gap. The app times double taps with SystemClock (300 ms
     * window), and the second tap needs the UI to have seen "armed" (Remote.mods is derived
     * asynchronously), as it would for a finger. The clock is paused so injection doesn't wait for
     * press animations.
     */
    private fun doubleTap(name: String, bit: Int): Long {
        val key = c.onNode(hasContentDescription("$name, ", substring = true))
        c.mainClock.autoAdvance = false
        key.performTouchInput { click() }
        val t0 = android.os.SystemClock.uptimeMillis()
        while (dev.mote.remote.net.Remote.mods.value.armed and bit == 0 && android.os.SystemClock.uptimeMillis() - t0 < 200) Thread.sleep(2)
        c.mainClock.advanceTimeByFrame() // recompose with "armed"
        key.performTouchInput { click() }
        val gap = android.os.SystemClock.uptimeMillis() - t0
        c.mainClock.autoAdvance = true
        return gap
    }

    /** Locks [name] by double tap; retries when the device was too slow to tap twice within the window. */
    private fun lockByDoubleTap(name: String, bit: Int) {
        val gaps = mutableListOf<Long>()
        repeat(5) {
            val gap = doubleTap(name, bit).also { gaps += it }
            if (gap < 280) { c.awaitDesc("$name, locked"); return }
            c.waitForIdle()
            if (c.onAllNodes(hasContentDescription("Clear modifiers")).fetchSemanticsNodes().isNotEmpty()) c.desc("Clear modifiers").performClick()
            c.awaitDesc("$name, off")
        }
        org.junit.Assume.assumeTrue("device too slow to inject a double tap within 300 ms (gaps $gaps)", false)
    }

    /** Hold 450 ms = locked (deterministic: the Compose clock times it). */
    private fun holdLock(name: String) {
        c.mainClock.autoAdvance = false
        val key = c.onNode(hasContentDescription("$name, ", substring = true))
        key.performTouchInput { down(center) }
        c.mainClock.advanceTimeBy(500, ignoreFrameDuration = true)
        key.performTouchInput { up() }
        c.mainClock.autoAdvance = true
        c.awaitDesc("$name, locked")
    }

    @Before fun openKeys() { rule.awaitLinkState(); c.mode("Keys"); c.waitForIdle(); t.clear() }

    @Test
    fun plainKeyGoesDownAndUpWithTheFinger() {
        c.desc("A").press()
        assertEquals(listOf(Call.KeyDown("a"), Call.KeyUp("a")), t.awaitAtLeast(2))
    }

    /** off -tap-> armed -key-> off (one-shot); the modifier is held on the host while armed. */
    @Test
    fun tapArmsOneShotModifier() {
        c.desc("command, off").performClick()
        c.awaitDesc("command, armed")
        assertEquals(listOf(Call.SetMods(CMD, 0)), t.awaitAtLeast(1))

        t.clear()
        c.desc("A").press()
        assertEquals(listOf(Call.KeyDown("a"), Call.KeyUp("a")), t.awaitAtLeast(2))
        c.awaitDesc("command, off")
    }

    @Test
    fun armedThenTapAgainClears() {
        c.desc("shift, off").performClick()
        c.awaitDesc("shift, armed")
        Thread.sleep(400) // slower than the 300 ms double-tap window
        c.desc("shift, armed").performClick()
        c.awaitDesc("shift, off")
        assertEquals(listOf(Call.SetMods(SHIFT, 0), Call.SetMods(0, 0)), t.awaitAtLeast(2))
    }

    @Test
    fun doubleTapLocksUntilTappedAgain() {
        lockByDoubleTap("command", Mods.CMD)
        t.clear()
        c.desc("A").press()
        c.desc("B").press()
        assertEquals(listOf(Call.KeyDown("a"), Call.KeyUp("a"), Call.KeyDown("b"), Call.KeyUp("b")), t.awaitAtLeast(4))
        c.desc("command, locked").assertExists() // still locked after keys
        t.clear()
        c.desc("command, locked").performClick()
        c.awaitDesc("command, off")
        assertEquals(listOf(Call.SetMods(0, 0)), t.awaitAtLeast(1))
    }

    @Test
    fun holdLocks() {
        c.mainClock.autoAdvance = false
        val option = c.onNode(hasContentDescription("option, ", substring = true))
        option.performTouchInput { down(center) }
        c.mainClock.advanceTimeBy(500, ignoreFrameDuration = true)
        option.performTouchInput { up() }
        c.mainClock.autoAdvance = true
        c.awaitDesc("option, locked")
        assertEquals(listOf(Call.SetMods(0, ALT)), t.awaitAtLeast(1))
    }

    @Test
    fun chordOfArmedModifiersAppliesToNextKey() {
        c.desc("command, off").performClick()
        c.awaitDesc("command, armed")
        Thread.sleep(350)
        c.desc("shift, off").performClick()
        c.awaitDesc("shift, armed")
        c.onNode(hasText("next key")).assertExists()
        assertEquals(listOf(Call.SetMods(CMD, 0), Call.SetMods(CMD or SHIFT, 0)), t.awaitAtLeast(2))
        t.clear()
        c.desc("4").press()
        assertEquals(listOf(Call.KeyDown("4"), Call.KeyUp("4")), t.awaitAtLeast(2))
        c.awaitDesc("command, off")
        c.awaitDesc("shift, off")
    }

    @Test
    fun clearModifiersReleasesEverything() {
        holdLock("control")
        c.desc("command, off").performClick()
        c.awaitDesc("command, armed")
        t.clear()
        c.desc("Clear modifiers").performClick()
        assertEquals(listOf(Call.SetMods(0, 0)), t.awaitAtLeast(1))
        c.awaitDesc("control, off")
        c.awaitDesc("command, off")
    }

    @Test
    fun textBoxSendsTextToTheTransport() {
        c.onNode(hasSetTextAction()).performTextInput("Hi!")
        c.desc("Send text").press()
        assertEquals(listOf(Call.TypeText("Hi!")), rule.awaitCalls(1))
        c.onNode(hasSetTextAction()).assert(hasText(""))
    }

    @Test
    fun shiftedSymbolKeyTapsWithShift() {
        c.desc("Symbols").performClick()
        c.desc("!").press()
        assertEquals(listOf(Call.Tap("1", SHIFT)), t.awaitAtLeast(1))
    }

    @Test
    fun functionRowsCollapse() {
        c.desc("F5").assertExists()
        c.onNodeWithText("HIDE").performClick()
        c.desc("F5").assertDoesNotExist()
        c.desc("home").assertDoesNotExist()
        assertEquals(emptyList<Call>(), t.settle())
        c.onNodeWithText("SHOW").performClick()
        c.desc("F5").press()
        assertEquals(listOf(Call.KeyDown("f5"), Call.KeyUp("f5")), t.awaitAtLeast(2))
    }
}
