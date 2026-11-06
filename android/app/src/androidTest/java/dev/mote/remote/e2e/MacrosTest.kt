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
        c.desc("Edit").performClick()
        c.desc("Edit $macro").performClick()
        c.desc("★ Favourite").performClick()
        c.desc("Save").performClick()
        c.waitUntil(5_000) { saved().single { it.name == macro }.star != before }
        c.desc("Done").performClick()
        c.waitForIdle()
    }

    @Test
    fun recordChordRunAndFavouriteIntoMediaStrip() {
        c.mode("Macros")
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
    }

    @Test
    fun editorNeedsNameAndKeyBeforeSaving() {
        c.mode("Macros")
        c.desc("New macro").performClick()
        c.desc("Add a name and a key").performClick()
        c.desc("Add a name and a key").assertExists() // still open, still invalid
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

    /** Slides [dx] (fraction of the tile width) in small steps, without lifting. */
    private fun androidx.compose.ui.test.TouchInjectionScope.slide(dx: Float) {
        val n = 12
        repeat(n) { moveBy(Offset(width * dx / n, 0f)) }
    }

    /** One deliberate slide = exactly one step, however far it goes; lift releases all keys. */
    @Test
    fun spacesSlideIsOneStep() {
        t.clear()
        c.desc(spaces).performTouchInput { down(Offset(width * 0.05f, centerY)); slide(0.9f); up() } // > 2 thresholds
        assertEquals(listOf(Call.Tap("right", CTRL), Call.SetMods(0, 0)), t.awaitAtLeast(2))
    }

    /** A slide inside the 450 ms cooldown is queued (at most one) and sent when the cooldown ends. */
    @Test
    fun spacesSecondSlideWaitsForCooldown() {
        t.clear()
        c.desc(spaces).performTouchInput {
            down(Offset(width * 0.1f, centerY)); slide(0.6f); up()
            down(Offset(width * 0.9f, centerY)); slide(-0.6f); up() // left, right away
        }
        val r = rule.awaitCalls(4)
        assertEquals(
            "second step only after the first slide's cooldown, after its own lift",
            listOf(Call.Tap("right", CTRL), Call.SetMods(0, 0), Call.SetMods(0, 0), Call.Tap("left", CTRL)), r,
        )
    }

    @Test
    fun spacesTapIsMissionControl() {
        t.clear()
        c.desc(spaces).press()
        assertEquals(listOf(Call.Tap("up", CTRL), Call.SetMods(0, 0)), t.awaitAtLeast(2))
    }

    @Test
    fun escapeIsAMacroNow() {
        c.mode("Macros")
        c.awaitDesc("Escape")
        t.clear()
        c.desc("Escape").press()
        assertEquals(ran("esc", 0), rule.awaitCalls(2))
    }

    /** Lock (⌃⌘Q) needs a second tap within 2 s. */
    @Test
    fun lockAsksForConfirmation() {
        c.mode("Macros")
        c.awaitDesc("Lock")
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
        c.desc("Edit").performClick()
        c.desc("Edit Mission Control").performClick()
        c.onNodeWithText("Delete macro").performClick()
        c.waitUntil(5_000) { saved().none { it.name == "Mission Control" } }
        c.desc("Done").performClick()
        c.onAllNodes(hasContentDescription("Mission Control")).fetchSemanticsNodes().let { assertTrue(it.isEmpty()) }
    }
}
