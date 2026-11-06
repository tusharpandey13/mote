package dev.mote.remote.e2e

import android.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.core.view.WindowCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.mote.remote.data.Prefs
import dev.mote.remote.data.ThemeChoice
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ThemeTest {
    @get:Rule val rule = MoteRule()
    private val c get() = rule.compose
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    /** Luminance of the chassis at the left edge, mid-height (outside every control). */
    private fun chassisLuma(): Int {
        c.waitForIdle()
        val b = c.onRoot().captureToImage().asAndroidBitmap()
        val p = b.getPixel(4, b.height / 2)
        return (Color.red(p) * 299 + Color.green(p) * 587 + Color.blue(p) * 114) / 1000
    }

    private fun lightBars(): Boolean {
        var light = false
        c.activityRule.scenario.onActivity { light = WindowCompat.getInsetsController(it.window, it.window.decorView).isAppearanceLightStatusBars }
        return light
    }

    private fun pick(t: ThemeChoice) {
        c.desc("Menu").performClick()
        c.desc(t.name).performClick()
        c.waitUntil(5_000) { runBlocking { Prefs.theme(ctx).first() } == t }
        rule.device.pressBack() // close the sheet
        c.waitForIdle()
    }

    private fun assertDark(dark: Boolean) {
        c.waitUntil(5_000) { lightBars() == !dark }
        val l = chassisLuma()
        assertTrue("chassis luma $l for dark=$dark", if (dark) l < 0x60 else l > 0xB0)
    }

    @Test
    fun lightByDefaultDarkOnDemandAndPersisted() {
        assertDark(false)
        pick(ThemeChoice.DARK)
        assertDark(true)
        c.activityRule.scenario.recreate()
        assertDark(true)
        pick(ThemeChoice.LIGHT)
        assertDark(false)
        assertEquals(ThemeChoice.LIGHT, runBlocking { Prefs.theme(ctx).first() })
    }

    @Test
    fun systemFollowsNightMode() {
        try {
            rule.device.executeShellCommand("cmd uimode night no")
            pick(ThemeChoice.SYSTEM)
            assertDark(false)
            rule.device.executeShellCommand("cmd uimode night yes")
            assertDark(true)
        } finally {
            rule.device.executeShellCommand("cmd uimode night no")
        }
    }
}
