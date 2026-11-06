package dev.mote.remote.ui

import kotlin.math.abs
import kotlin.math.hypot

/**
 * Touchpad finger motion (dp) -> HID relative counts. macOS applies its own pointer acceleration on
 * top of HID deltas, so ours stays mild: linear (precise) below [SLOW] dp/ms, then the gain rises
 * gently to at most [MAX_BOOST]x by [FAST] dp/ms. Sub-count remainders carry over, so slow moves are
 * not lost, and each report is clamped to ±[MAX_COUNT].
 */
class PointerMapper(var speed: Float = 1f) {
    private var remX = 0f
    private var remY = 0f

    /** Returns whole counts (x, y) for this motion sample; the fraction is kept for the next one. */
    fun map(dxDp: Float, dyDp: Float, dtMs: Long): Pair<Int, Int> {
        val v = hypot(dxDp, dyDp) / dtMs.coerceAtLeast(1)
        val g = gain(v) * speed
        val fx = remX + dxDp * g
        val fy = remY + dyDp * g
        val ix = fx.toInt().coerceIn(-MAX_COUNT, MAX_COUNT)
        val iy = fy.toInt().coerceIn(-MAX_COUNT, MAX_COUNT)
        // keep only the sub-count fraction; anything clamped away is dropped, not replayed later
        remX = if (abs(fx) > MAX_COUNT) 0f else fx - ix
        remY = if (abs(fy) > MAX_COUNT) 0f else fy - iy
        return ix to iy
    }

    fun reset() { remX = 0f; remY = 0f }

    companion object {
        /** Counts per dp at speed 1.0 (was 1.8 with up to 4x quadratic boost). */
        const val BASE = 0.8f
        const val SLOW = 0.25f   // dp/ms: below this the mapping is linear
        const val FAST = 1.5f    // dp/ms: boost saturates here
        const val MAX_BOOST = 1.6f
        const val MAX_COUNT = 48

        fun gain(dpPerMs: Float): Float {
            if (dpPerMs <= SLOW) return BASE
            val t = ((dpPerMs - SLOW) / (FAST - SLOW)).coerceIn(0f, 1f)
            val eased = t * t * (3 - 2 * t) // smoothstep: no kink at either end
            return BASE * (1f + (MAX_BOOST - 1f) * eased)
        }
    }
}
