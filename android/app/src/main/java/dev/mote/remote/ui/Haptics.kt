package dev.mote.remote.ui

import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalView

/** Semantic haptics from design-language 5.2. */
enum class Haptic { TICK, CLICK, HEAVY, ARM, LOCK, RELEASE, CONNECT, DISCONNECT, REJECT, EDGE }

class Haptics(private val view: View) {
    private val vibrator: Vibrator? = view.context.getSystemService(Vibrator::class.java)
    private var last = 0L

    fun play(h: Haptic) {
        val now = SystemClock.uptimeMillis()
        if (now - last < 25) return // coalesce: at most one haptic per 25 ms
        last = now
        if (Build.VERSION.SDK_INT >= 31 && composed(h)) return
        view.performHapticFeedback(fallback(h))
    }

    private fun composed(h: Haptic): Boolean {
        val v = vibrator ?: return false
        if (Build.VERSION.SDK_INT < 31) return false // THUD / LOW_TICK primitives are API 31
        val c = VibrationEffect.startComposition()
        val prims = mutableListOf<Int>()
        fun add(p: Int, s: Float, delay: Int = 0) { prims += p; c.addPrimitive(p, s, delay) }
        when (h) {
            Haptic.TICK -> add(VibrationEffect.Composition.PRIMITIVE_TICK, 0.6f)
            Haptic.CLICK -> add(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.8f)
            Haptic.HEAVY -> add(VibrationEffect.Composition.PRIMITIVE_THUD, 0.7f)
            Haptic.ARM -> add(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.5f)
            Haptic.LOCK -> { add(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.8f); add(VibrationEffect.Composition.PRIMITIVE_THUD, 0.5f, 40) }
            Haptic.RELEASE -> add(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, 0.4f)
            Haptic.CONNECT -> add(VibrationEffect.Composition.PRIMITIVE_QUICK_RISE, 0.7f)
            Haptic.DISCONNECT -> { add(VibrationEffect.Composition.PRIMITIVE_SLOW_RISE, 0.5f); add(VibrationEffect.Composition.PRIMITIVE_QUICK_FALL, 0.7f) }
            Haptic.REJECT -> { add(VibrationEffect.Composition.PRIMITIVE_TICK, 0.9f); add(VibrationEffect.Composition.PRIMITIVE_TICK, 0.9f, 60) }
            Haptic.EDGE -> add(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, 1.0f)
        }
        if (!v.areAllPrimitivesSupported(*prims.toIntArray())) return false
        v.vibrate(c.compose())
        return true
    }

    private fun fallback(h: Haptic): Int = when (h) {
        Haptic.TICK, Haptic.EDGE, Haptic.RELEASE -> HapticFeedbackConstants.CLOCK_TICK
        Haptic.CLICK -> HapticFeedbackConstants.VIRTUAL_KEY
        Haptic.HEAVY -> HapticFeedbackConstants.LONG_PRESS
        Haptic.ARM -> HapticFeedbackConstants.CONTEXT_CLICK
        Haptic.LOCK, Haptic.CONNECT -> if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.LONG_PRESS
        Haptic.DISCONNECT, Haptic.REJECT -> if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS
    }
}

val LocalHaptics = staticCompositionLocalOf<Haptics?> { null }

@Composable
fun rememberHaptics(): Haptics {
    val view = LocalView.current
    return remember(view) { Haptics(view) }
}

/** Convenience accessor: no-op outside the app root. */
@Composable
fun haptics(): (Haptic) -> Unit {
    val h = LocalHaptics.current
    return remember(h) { { x: Haptic -> h?.play(x) } }
}
