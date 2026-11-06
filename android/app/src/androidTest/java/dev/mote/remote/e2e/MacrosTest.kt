package dev.mote.remote.e2e

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import dev.mote.remote.data.Macro
import dev.mote.remote.data.Macros
import dev.mote.remote.data.Step
import dev.mote.remote.e2e.Mods.CMD
import dev.mote.remote.e2e.Mods.CTRL
import dev.mote.remote.hid.Mod
import dev.mote.remote.net.Caps
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class MacrosTest(@Suppress("unused") private val name: String, private val caps: Caps) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}") fun transports() = TRANSPORTS
    }

    @get:Rule val rule = MoteRule(caps = caps)
    private val c get() = rule.compose
    private val t get() = rule.transport
    @org.junit.Before fun linked() = rule.awaitLinkState()
    private val spaces = "Spaces: slide to switch, tap for Mission Control"

    /** Running a macro clears sticky modifiers first, then taps the chord. */
    private fun ran(key: String, mods: Int) = listOf(Call.SetMods(0, 0), Call.TapAwait(key, mods))

    private fun saved() = runBlocking { Macros.flow(InstrumentationRegistry.getInstrumentation().targetContext).first() }

    /** Waits for the editor's DataStore write to land. */
    private fun awaitSaved(name: String): Macro {
        c.waitUntil(5_000) { saved().any { it.name == name } }
        c.waitForIdle()
        return saved().single { it.name == name }
    }

    private fun toggleFavourite(macro: String) {
        val before = saved().single { it.name == macro }.star
        c.onNodeWithText("EDIT").performClick()
        c.desc("Edit $macro").performClick()
        c.desc("★ Favourite").performClick()
        c.desc("Save").performClick()
        c.waitUntil(5_000) { saved().single { it.name == macro }.star != before }
        c.onNodeWithText("DONE").performClick()
        c.waitForIdle()
    }

    @Test
    fun recordChordRunAndFavouriteIntoMediaStrip() {
        c.mode("Macros")
        // the strip holds 4 slots and the defaults fill them (Spaces = 2); free one first
        toggleFavourite("Lock")

        c.desc("New macro").performClick()
        val fields = c.onAllNodes(hasSetTextAction())
        fields[0].performTextInput("Find")
        c.desc("⌘ cmd").a11yClick() // the keyboard is up now; a touch would land on it
        fields[1].performTextInput("f") // records the key
        c.onNodeWithText("⌘F").assertExists() // live chord preview
        c.desc("★ Favourite").a11yClick()
        c.desc("Save").a11yClick()
        val find = awaitSaved("Find")
        assertEquals(listOf(Step.Chord(Mod.CMD, "f")), find.steps)
        assertTrue(find.star)

        c.awaitDesc("Find")
        t.clear()
        c.desc("Find").press()
        assertEquals(ran("f", CMD), rule.awaitCalls(2))

        c.mode("Media")
        c.waitForIdle()
        t.clear()
        c.desc("Find").press()
        assertEquals("runs from the Media favourites strip", ran("f", CMD), rule.awaitCalls(2))
        c.desc("Lock").assertDoesNotExist()
    }

    @Test
    fun editorNeedsNameAndKeyBeforeSaving() {
        c.mode("Macros")
        c.desc("New macro").performClick()
        c.desc("Add a name and a key").performClick()
        c.onNodeWithText("New macro").assertExists() // still open
        c.onAllNodes(hasSetTextAction())[0].performTextInput("Mission")
        c.desc("⌃ ctrl").a11yClick()
        c.desc("↑").a11yClick() // special key row (keyboard is up)
        c.desc("Save").a11yClick()
        awaitSaved("Mission")
        c.awaitDesc("Mission")
        t.clear()
        c.desc("Mission").press()
        assertEquals(ran("up", CTRL), rule.awaitCalls(2))
    }

    /** Click at point needs absolute positioning: only the helper transport offers it. */
    @Test
    fun clickAtPointOnlyWithHelper() {
        c.mode("Macros")
        c.desc("New macro").performClick()
        c.onAllNodes(hasSetTextAction())[0].performTextInput("Dock")
        c.desc("Click at point").a11yClick()
        c.waitForIdle()
        if (!caps.clickAt) {
            c.onNodeWithText("Click at point needs a Mac with Mote Helper", substring = true).assertExists()
            c.onNodeWithText("Tap or drag where to click", substring = true).assertDoesNotExist() // still a shortcut
            return
        }
        c.onNodeWithText("Tap or drag where to click", substring = true).assertExists()
        c.desc("Save").a11yClick()
        assertEquals(listOf(Step.ClickAt(0.5f, 0.5f)), awaitSaved("Dock").steps)
        c.awaitDesc("Dock")
        t.clear()
        c.desc("Dock").press()
        assertEquals(listOf(Call.SetMods(0, 0), Call.ClickAt(0.5f, 0.5f)), rule.awaitCalls(2))
    }

    @Test
    fun spacesTileHoldAndSlideSwitchesSpaces() {
        val tile = c.desc(spaces)
        val step = with(c.density) { 32.dp.toPx() }
        t.clear()
        tile.performTouchInput {
            down(Offset(width * 0.25f, centerY))
            repeat(10) { moveBy(Offset(step * 0.25f, 0f)) } // +2.5 steps right
            repeat(7) { moveBy(Offset(-step * 0.25f, 0f)) } // back 1.75 steps: one step left of the anchor
            up()
        }
        val expected = listOf(Call.Tap("right", CTRL), Call.Tap("right", CTRL), Call.Tap("left", CTRL))
        assertEquals("slide only, no Mission Control on release", expected, t.awaitAtLeast(3))

        t.clear()
        tile.press()
        assertEquals("tap = ⌃↑ (Mission Control)", listOf(Call.Tap("up", CTRL)), t.awaitAtLeast(1))
    }

    /** Lock (⌃⌘Q) needs a second tap within 2 s. */
    @Test
    fun lockAsksForConfirmation() {
        val lock = c.desc("Lock")
        t.clear()
        lock.press()
        c.onNodeWithText("Tap again").assertExists()
        assertEquals(emptyList<Call>(), t.settle())
        lock.press()
        assertEquals(ran("q", CTRL or CMD), rule.awaitCalls(2))

        // the arm expires after 2 s
        c.mainClock.autoAdvance = false
        t.clear()
        lock.press()
        c.mainClock.advanceTimeBy(2_100, ignoreFrameDuration = true)
        c.mainClock.autoAdvance = true
        c.onNodeWithText("Tap again").assertDoesNotExist()
        lock.press()
        assertEquals("re-armed, not fired", emptyList<Call>(), t.settle())
    }

    @Test
    fun deleteMacro() {
        c.mode("Macros")
        c.onNodeWithText("EDIT").performClick()
        c.desc("Edit Mission Control").performClick()
        c.onNodeWithText("Delete macro").performClick()
        c.waitUntil(5_000) { saved().none { it.name == "Mission Control" } }
        c.onNodeWithText("DONE").performClick()
        c.onAllNodes(hasContentDescription("Mission Control")).fetchSemanticsNodes().let { assertTrue(it.isEmpty()) }
    }
}
