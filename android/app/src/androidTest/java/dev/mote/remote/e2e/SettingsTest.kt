package dev.mote.remote.e2e

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.mote.remote.data.UiPrefs
import dev.mote.remote.data.UiSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Menu > Settings: the Pointer / Scroll speed sliders persist (mote_settings DataStore) and drive the touchpad. */
@RunWith(AndroidJUnit4::class)
class SettingsTest {
    @get:Rule val rule = MoteRule()
    private val c get() = rule.compose
    private val t get() = rule.transport
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun saved(): UiSettings = runBlocking { UiPrefs.flow(ctx).first() }

    private fun openSettings() {
        c.desc("Menu").performClick()
        c.onNode(hasText("Settings") and hasClickAction()).performClick()
        c.awaitDesc("Pointer speed")
    }

    /**
     * Tap a speed slider at its left (0.25x) or right (2x) end and wait for the save: each change writes a
     * whole UiSettings snapshot, so a second change before the first lands would overwrite it.
     */
    private fun setSpeed(slider: String, max: Boolean) {
        c.desc(slider).performTouchInput { click(if (max) centerRight - Offset(2f, 0f) else centerLeft + Offset(2f, 0f)) }
        val want = if (max) UiSettings.SPEED_MAX else UiSettings.SPEED_MIN
        c.waitUntil(5_000) { saved().let { if (slider == "Pointer speed") it.pointerSpeed else it.scrollSpeed } == want }
        // and for the UI to see it (LocalSettings), so the next change starts from the saved snapshot
        c.waitUntil(5_000) {
            c.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "%.2fx".format(want)) and
                androidx.compose.ui.test.hasContentDescription(slider)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun shows(slider: String, value: String) =
        c.desc(slider).assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, value))

    @Test
    fun speedSlidersPersist() {
        openSettings()
        shows("Pointer speed", "1.00x")
        setSpeed("Pointer speed", max = true)
        setSpeed("Scroll speed", max = false)
        c.waitUntil(5_000) { saved().pointerSpeed == UiSettings.SPEED_MAX && saved().scrollSpeed == UiSettings.SPEED_MIN }
        shows("Pointer speed", "2.00x")
        shows("Scroll speed", "0.25x")

        c.activityRule.scenario.recreate()
        c.awaitDesc("Pointer speed") // the open Settings page is restored too
        shows("Pointer speed", "2.00x")
        shows("Scroll speed", "0.25x")
    }

    /** Slow (linear) one-finger motion: counts scale with Pointer speed. */
    private fun slowSwipeCounts(): Int {
        c.mode("Mouse")
        rule.awaitLinkState()
        t.clear()
        c.desc("Touchpad").performTouchInput { down(center - Offset(100f, 0f)); repeat(30) { moveBy(Offset(5f, 0f)) }; up() }
        return t.settle().filterIsInstance<Call.Move>().sumOf { it.dx.toInt() }
    }

    private fun twoFingerNotches(): Int {
        t.clear()
        val step = with(c.density) { 10.dp.toPx() }
        c.desc("Touchpad").performTouchInput {
            down(0, center - Offset(60f, 0f)); down(1, center + Offset(60f, 0f))
            repeat(10) { updatePointerBy(0, Offset(0f, -step)); updatePointerBy(1, Offset(0f, -step)); move() }
            up(0); up(1)
        }
        return t.settle().filterIsInstance<Call.Scroll>().sumOf { it.wheel }
    }

    @Test
    fun speedsDriveTheTouchpad() {
        val baseMove = slowSwipeCounts()
        val baseScroll = twoFingerNotches()
        assertEquals("100 dp at 1x = 5 notches", 5, baseScroll)

        c.mode("Media")
        openSettings()
        setSpeed("Pointer speed", max = true)
        setSpeed("Scroll speed", max = true)
        c.waitUntil(5_000) { saved().pointerSpeed == 2f && saved().scrollSpeed == 2f }
        c.desc("Back").performClick()

        val fastMove = slowSwipeCounts()
        val ratio = fastMove.toFloat() / baseMove
        assertTrue("2x pointer speed doubles slow motion: $baseMove -> $fastMove", ratio in 1.7f..2.3f)
        assertEquals("100 dp at 2x = 10 notches", 10, twoFingerNotches())
    }
}
