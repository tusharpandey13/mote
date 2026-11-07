package dev.mote.remote.ui

import kotlin.math.hypot
import kotlin.math.pow

/**
 * Joystick pointer maths (tokens.json components.dpad.joystick.interaction), kept free of Android so
 * it is unit-tested on the JVM.
 */
object StickCurve {
    const val DEADZONE = 0.12f
    const val EXPONENT = 1.8f
    /** Host px/s at full deflection with Pointer speed 1.0. */
    const val V_MAX = 1800f
    const val RATE_HZ = 120
    const val EDGE_AT = 0.98f
    const val EDGE_REARM = 0.90f

    /** Pointer speed (host px/s) for a normalised deflection [d] (0..1) and the Pointer speed setting. */
    fun velocity(d: Float, speed: Float = 1f): Float {
        val m = d.coerceIn(0f, 1f)
        if (m <= DEADZONE) return 0f
        return V_MAX * speed * ((m - DEADZONE) / (1f - DEADZONE)).pow(EXPONENT)
    }
}

/**
 * Turns stick deflection into relative pointer counts at a fixed rate. [dx], [dy] are the deflection
 * normalised by travel (the vector length is d); [dtS] the time since the last tick. Uses the
 * touchpad's [SubPixel] accumulator so slow drifts still move.
 */
class StickDriver(var speed: Float = 1f) {
    private val acc = SubPixel()
    private var edgeArmed = true

    fun tick(dx: Float, dy: Float, dtS: Float): Pair<Int, Int> {
        val d = hypot(dx, dy)
        if (d <= StickCurve.DEADZONE) return acc.add(0f, 0f)
        val v = StickCurve.velocity(d, speed) * dtS
        val k = v / d // unit direction times distance this tick
        return acc.add(dx * k, dy * k)
    }

    /** True once each time the deflection reaches the edge; re-arms below [StickCurve.EDGE_REARM]. */
    fun edge(d: Float): Boolean {
        if (edgeArmed && d >= StickCurve.EDGE_AT) { edgeArmed = false; return true }
        if (!edgeArmed && d < StickCurve.EDGE_REARM) edgeArmed = true
        return false
    }

    fun reset() { acc.reset(); edgeArmed = true }
}

/**
 * Tap vs. long-press on the stick: a lift under [TAP_MS] that moved under [SLOP_DP] is a left click;
 * holding [LONG_MS] without moving past the slop is a right click (once). Moving past the slop
 * makes the gesture a pure pointer move.
 */
class StickPress {
    enum class Out { NONE, TAP, LONG }

    private var t0 = 0L
    private var moved = false
    private var longFired = false
    private var active = false

    /** Pointer still within the click slop (the "pressed" look). */
    val clicking: Boolean get() = active && !moved && !longFired

    fun down(nowMs: Long) { t0 = nowMs; moved = false; longFired = false; active = true }

    /** [travelDp]: the finger's distance from where it went down. */
    fun move(travelDp: Float) { if (travelDp >= SLOP_DP) moved = true }

    /** Call periodically while held. */
    fun tick(nowMs: Long): Out {
        if (!active || moved || longFired) return Out.NONE
        if (nowMs - t0 >= LONG_MS) { longFired = true; return Out.LONG }
        return Out.NONE
    }

    fun up(nowMs: Long): Out {
        if (!active) return Out.NONE
        active = false
        if (longFired || moved) return Out.NONE
        if (nowMs - t0 >= LONG_MS) return Out.LONG // the timer lost the race with the lift
        return if (nowMs - t0 < TAP_MS) Out.TAP else Out.NONE
    }

    fun cancel() { active = false }

    companion object {
        const val TAP_MS = 200L
        const val SLOP_DP = 4f
        const val LONG_MS = 450L
    }
}
