package dev.mote.remote.e2e

import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.mote.remote.data.UiPrefs
import dev.mote.remote.e2e.Mods.CTRL
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Spaces carousel over Bluetooth (rev 6): numbered desktop cards; a tap jumps there with ⌃N when
 * "Use ⌃1–⌃9 shortcuts" is on, otherwise by stepping ⌃← / ⌃→ (one every 300 ms, real time). The Mac
 * can't report the current desktop over Bluetooth, so the app tracks the last one it jumped to.
 */
@RunWith(AndroidJUnit4::class)
class SpacesTest {
    @get:Rule val rule = MoteRule()
    private val c get() = rule.compose
    private val t get() = rule.transport
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before fun linked() = rule.awaitLinkState()

    /** Cards live in a LazyRow: scroll [n] into view first. */
    private fun show(n: Int) {
        c.onNode(hasScrollToNodeAction() and hasAnyDescendant(hasContentDescription("Desktop ", substring = true)))
            .performScrollToNode(hasContentDescription("Desktop $n", substring = true))
    }

    private fun go(n: Int, expected: List<Call>): List<Call> {
        show(n)
        t.clear()
        c.desc(c.cardLabel(n)).performClick()
        val r = t.awaitAtLeast(expected.size)
        assertEquals("tap Desktop $n", expected, r)
        c.awaitDesc("Desktop $n, current")
        return r
    }

    @Test
    fun stepsWithoutCtrlNumberShortcutsAndTracksCurrent() {
        c.desc("Desktop 1, current").assertExists() // default: desktop 1, 4 cards
        show(4); c.desc("Desktop 4").assertExists()
        val start = System.currentTimeMillis()
        go(3, List(2) { Call.Tap("right", CTRL) })
        assertTrue("steps are spaced (~300 ms) so macOS keeps up", System.currentTimeMillis() - start >= 300)
        show(1); c.desc("Desktop 1").assertExists() // no longer current
        go(1, List(2) { Call.Tap("left", CTRL) })
        t.clear()
        show(1)
        c.desc("Desktop 1, current").performClick() // already there: nothing to send
        assertEquals(emptyList<Call>(), t.settle())
    }

    @Test
    fun jumpsWithCtrlNumberWhenEnabled() {
        runBlocking { UiPrefs.update(ctx) { it.copy(ctrlNumberShortcuts = true) } }
        c.waitForIdle()
        go(4, listOf(Call.Tap("4", CTRL)))
        go(2, listOf(Call.Tap("2", CTRL)))
    }

    @Test
    fun desktopCountSetsTheCards() {
        runBlocking { UiPrefs.update(ctx) { it.copy(desktopCount = 6) } }
        c.waitForIdle()
        show(6)
        go(6, List(5) { Call.Tap("right", CTRL) })
    }

    private fun openSheet() {
        c.mode("Macros")
        c.desc("Spaces: choose a desktop").performClick()
        c.waitUntil(5_000) { c.onAllNodes(hasText("Over Bluetooth", substring = true, ignoreCase = true)).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun hintShown() = c.onAllNodes(hasText("Jump straight to a desktop", substring = true)).fetchSemanticsNodes().isNotEmpty()

    /** One-time hint in the Spaces sheet: "Remind me later" hides it for now, "Done" for good. */
    @Test
    fun hintShowsOnceUntilDone() {
        openSheet()
        assertTrue(hintShown())
        c.onNode(hasText("Remind me later") and hasClickAction()).performClick()
        c.waitUntil(5_000) { !hintShown() }
        rule.device.pressBack(); c.waitForIdle()

        openSheet()
        assertTrue("later = shown again next time", hintShown())
        c.desc("Done").performClick()
        c.waitUntil(5_000) { runBlocking { UiPrefs.flow(ctx).first() }.spacesHintDone }
        c.waitUntil(5_000) { !hintShown() }
        rule.device.pressBack(); c.waitForIdle()

        openSheet()
        assertTrue("done = never again", !hintShown())
        c.onNodeWithText("Swipe the row or tap a desktop", substring = true).assertExists()
    }

    @Test
    fun noHintWhenCtrlNumberShortcutsAreOn() {
        runBlocking { UiPrefs.update(ctx) { it.copy(ctrlNumberShortcuts = true) } }
        openSheet()
        assertTrue(!hintShown())
    }
}

/** Card label: "Desktop N" or "Desktop N, current". */
private fun androidx.compose.ui.test.junit4.ComposeTestRule.cardLabel(n: Int): String =
    if (onAllNodes(androidx.compose.ui.test.hasContentDescription("Desktop $n, current")).fetchSemanticsNodes().isNotEmpty()) "Desktop $n, current"
    else "Desktop $n"
