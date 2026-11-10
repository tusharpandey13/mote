package dev.mote.remote.e2e

import android.os.Build
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** U4: first-run setup explains and offers the notification permission once (Android 13+); "Not now" is remembered. */
@RunWith(AndroidJUnit4::class)
class NotifyAskTest {
    @get:Rule val rule = MoteRule(grantPermissions = false, linked = false)
    private val c get() = rule.compose
    private fun shown(text: String) = c.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()

    @Test fun firstRunExplainsAndAsksOnce() {
        assumeTrue(Build.VERSION.SDK_INT >= 33)
        c.waitUntil(8_000) { shown("Allow notifications?") }
        assert(shown("lock screen"))
        c.onNodeWithText("Not now").performClick()
        c.waitUntil(5_000) { !shown("Allow notifications?") }
    }
}
