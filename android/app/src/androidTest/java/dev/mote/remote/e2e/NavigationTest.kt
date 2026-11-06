package dev.mote.remote.e2e

import android.content.pm.ActivityInfo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NavigationTest {
    @get:Rule val rule = MoteRule()
    private val c get() = rule.compose

    private val markers = listOf("Media" to "Play or pause", "Mouse" to "Touchpad", "Keys" to "Send text", "Macros" to "New macro")

    @Test
    fun railVisitsAllFourModes() {
        c.desc("Play or pause").assertIsDisplayed() // Media is the start mode
        for ((mode, marker) in markers + markers.reversed()) {
            c.mode(mode)
            c.desc(marker).assertIsDisplayed()
            markers.filter { it.first != mode }.forEach { (_, other) -> c.desc(other).assertDoesNotExist() }
        }
    }

    private fun editKeysState() {
        c.mode("Keys")
        c.onNode(hasSetTextAction()).performTextInput("draft")
        c.desc("Symbols").performClick() // -> symbol rows
        c.onNodeWithText("HIDE").performClick() // collapse the function rows
    }

    private fun assertKeysState() {
        c.desc("Send text").assertIsDisplayed()
        c.onNode(hasSetTextAction()).assert(hasText("draft"))
        c.desc("Letters").assertExists()
        c.onNodeWithText("SHOW").assertExists()
    }

    @Test
    fun keysStateSurvivesSwitchingModes() {
        editKeysState()
        for (m in listOf("Media", "Mouse", "Macros", "Keys")) c.mode(m)
        assertKeysState()
    }

    @Test
    fun configChangeKeepsModeAndState() {
        editKeysState()
        c.activityRule.scenario.recreate()
        assertKeysState()
    }

    @Test
    fun rotationIsLockedToPortraitAndKeepsState() {
        editKeysState()
        try {
            rule.device.setOrientationLeft()
            c.waitForIdle()
            c.activityRule.scenario.onActivity { assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, it.requestedOrientation) }
            assertKeysState()
        } finally {
            rule.device.setOrientationNatural()
            rule.device.unfreezeRotation()
        }
        assertKeysState()
    }
}
