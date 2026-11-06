package dev.mote.remote.e2e

import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Every actionable control has a spoken label, and its touch target (semantics touch bounds,
 * which include Compose's minimum-touch-target expansion) is at least 48 x 48 dp.
 */
@RunWith(AndroidJUnit4::class)
class AccessibilityTest {
    @get:Rule val rule = MoteRule()
    private val c get() = rule.compose

    private fun label(n: SemanticsNode): String? =
        n.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()?.takeIf { it.isNotBlank() }
            ?: n.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }?.takeIf { it.isNotBlank() }
            ?: n.config.getOrNull(SemanticsProperties.EditableText)?.text?.takeIf { it.isNotBlank() }

    /** Problems on the current screen (all windows, merged tree, i.e. what TalkBack focuses). */
    private fun audit(screen: String): List<String> {
        val d = c.density.density
        return c.onAllNodes(hasClickAction()).fetchSemanticsNodes().filter { it.boundsInRoot.width > 0f }.flatMap { n -> // skip scrolled-off
            val name = label(n)
            val t = n.touchBoundsInRoot
            val w = t.width / d
            val h = t.height / d
            buildList {
                if (name == null) add("$screen: unlabeled control at ${n.boundsInRoot}")
                if (w < 47.5f || h < 47.5f) add("$screen: '$name' touch target %.0fx%.0f dp".format(w, h))
            }
        }
    }

    private fun gestureSurfacesLabelled(vararg names: String) = names.forEach { c.desc(it).assertExists() }

    @Test
    fun everyControlIsLabelledAndAtLeast48dp() {
        val problems = mutableListOf<String>()
        problems += audit("Media")
        gestureSurfacesLabelled("Direction pad", "Scroll lever", "Spaces: slide to switch, tap for Mission Control")

        c.mode("Mouse"); problems += audit("Mouse")
        gestureSurfacesLabelled("Touchpad")

        c.mode("Keys"); problems += audit("Keys")
        c.desc("command, off").performTouchInput { click(); advanceEventTime(80); click() } // locked + chord strip Clear
        problems += audit("Keys+mods")
        c.desc("Clear modifiers").performClick()

        c.mode("Macros"); problems += audit("Macros")
        c.desc("Edit").performClick(); problems += audit("Macros edit")
        c.desc("New macro").performClick(); problems += audit("Macro editor")
        c.desc("⌘ cmd").performClick(); c.desc("★ Favourite").performClick() // selected (pressed-look) toggles
        problems += audit("Macro editor, toggles on")
        rule.device.pressBack(); c.waitForIdle()

        c.desc("Menu").performClick(); problems += audit("Menu sheet")
        rule.device.pressBack(); c.waitForIdle()
        c.onNode(hasClickAction() and androidx.compose.ui.test.hasContentDescription("Device:", substring = true)).performClick()
        problems += audit("Device sheet")

        assertEquals(problems.distinct().joinToString("\n"), 0, problems.distinct().size)
    }
}
