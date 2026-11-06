package dev.mote.remote.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

/** Text inside fixed-size chrome (top bar, favourites header, Spaces cards) stops growing past this font scale. */
const val CHROME_MAX_FONT_SCALE = 1.3f

/**
 * Caps the system font scale for [content]. Those rows have fixed dp heights sized to the design; at
 * font scale 2.0 their labels overflowed and clipped (chaos lifecycle-font-density). Text elsewhere
 * (sheets, settings, gate) still follows the full system scale.
 */
@Composable
fun CappedFontScale(max: Float = CHROME_MAX_FONT_SCALE, content: @Composable () -> Unit) {
    val d = LocalDensity.current
    if (d.fontScale <= max) content()
    else CompositionLocalProvider(LocalDensity provides Density(d.density, max), content = content)
}
