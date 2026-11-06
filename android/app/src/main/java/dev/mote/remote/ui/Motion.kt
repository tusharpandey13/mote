package dev.mote.remote.ui

import android.provider.Settings
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Motion tokens (design-language 5.3). Compose already scales every animation by the system
 * ANIMATOR_DURATION_SCALE (0 = "remove animations" finishes instantly); [reducedMotion] is only
 * needed to switch off the looping pulse, which would otherwise keep redrawing.
 */
object Motion {
    private val standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    private val exitEasing = CubicBezierEasing(0.3f, 0f, 1f, 1f)
    fun <T> pressSpec(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.8f, stiffness = 1500f)
    fun <T> spatialDefault(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.8f, stiffness = 380f)
    fun <T> spatialFast(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.9f, stiffness = 800f)
    fun <T> effectFast(): FiniteAnimationSpec<T> = tween(100, easing = standard)
    fun <T> effectMed(): FiniteAnimationSpec<T> = tween(200, easing = standard)
    fun <T> exit(): FiniteAnimationSpec<T> = tween(120, easing = exitEasing)
    val press = pressSpec<Float>()
    const val PULSE_MS = 1200
}

@Composable
fun reducedMotion(): Boolean {
    val ctx = LocalContext.current
    return remember(ctx) { Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
}
