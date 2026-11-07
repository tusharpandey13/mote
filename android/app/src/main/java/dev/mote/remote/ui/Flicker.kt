package dev.mote.remote.ui

/**
 * Lightbulb wordmark micro-flicker (rev 7 #70, tokens.json components.wordmark.flicker), an exact
 * port of design/mote-v1/flicker.js: one mulberry32 stream seeded "MOTE"; per event three draws in
 * order (wait, depth, double); a 30 ms fall and 80 ms recovery; doubles add a 60 % dip at 170 ms.
 */
class Flicker(seed: Int = SEED) {
    private var a = seed

    /** mulberry32: identical to the JS (Int arithmetic wraps like `| 0` and `Math.imul`). */
    fun rnd(): Double {
        a += 0x6D2B79F5
        var t = (a xor (a ushr 15)) * (1 or a)
        t = (t + (t xor (t ushr 7)) * (61 or t)) xor t
        return ((t xor (t ushr 14)).toLong() and 0xFFFFFFFFL) / 4294967296.0
    }

    data class Event(val waitMs: Long, val depth: Double, val double: Boolean) {
        val lengthMs: Long get() = if (double) 280L else 110L
        /** Brightness 0..1 at [t] ms since the event started. */
        fun lum(t: Double): Double = when {
            t < 110 -> dip(t, depth)
            double && t >= 170 && t < 280 -> dip(t - 170, depth * 0.6)
            else -> 1.0
        }
    }

    fun next(): Event {
        val wait = 4000L + kotlin.math.floor(rnd() * 11001).toLong()
        val depth = 0.05 + rnd() * 0.15
        val dbl = rnd() < 0.3
        return Event(wait, depth, dbl)
    }

    companion object {
        const val SEED = 0x4D4F5445 // "MOTE"

        private fun easeOut(x: Double) = 1 - (1 - x) * (1 - x)
        private fun easeInOut(x: Double) = if (x < .5) 4 * x * x * x else 1 - Math.pow(-2 * x + 2, 3.0) / 2

        fun dip(t: Double, depth: Double): Double = when {
            t < 30 -> 1 - depth * easeOut(t / 30)
            t < 110 -> 1 - depth * (1 - easeInOut((t - 30) / 80))
            else -> 1.0
        }

        /** The filament core dims less than the glow. */
        fun coreAlpha(lum: Float) = 1f - 0.4f * (1f - lum)
    }
}
