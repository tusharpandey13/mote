package dev.mote.remote.ui

import kotlin.math.abs

/**
 * Spaces tile gesture -> HID intent, independent of Compose and time source (unit-tested).
 *
 * - One deliberate slide = exactly one step: it fires once when the finger passes [thresholdPx]
 *   (40% of the tile width) and then locks until the finger lifts. No auto-repeat.
 * - After each step a [cooldownMs] window (the macOS Space slide animation) blocks further steps; a
 *   slide committed inside it is queued, at most one, and released by [tick] when the window ends.
 * - A tap (no movement past [slopPx]) is Mission Control.
 * - Lift and cancel always end with [Out.ReleaseAll] so no modifier can stay down on the host.
 */
class SpacesGesture(
    private val thresholdPx: Float,
    private val slopPx: Float,
    private val cooldownMs: Long = COOLDOWN_MS,
) {
    sealed interface Out {
        /** Send one ⌃← (right = false) or ⌃→ (right = true). */
        data class Step(val right: Boolean) : Out
        /** The slide passed the threshold (haptic detent + commit flash), whether sent now or queued. */
        data class Committed(val right: Boolean, val queued: Boolean) : Out
        data object MissionControl : Out
        data object ReleaseAll : Out
    }

    private var tracking = false
    private var startX = 0f
    private var moved = false
    private var fired = false
    private var lastStepAt = Long.MIN_VALUE / 2
    private var pending: Boolean? = null

    val hasPending: Boolean get() = pending != null

    fun inCooldown(now: Long) = now - lastStepAt < cooldownMs

    /** Milliseconds until the cooldown ends (0 if idle). */
    fun cooldownLeft(now: Long) = (cooldownMs - (now - lastStepAt)).coerceAtLeast(0)

    fun down(x: Float) {
        tracking = true; startX = x; moved = false; fired = false
    }

    fun move(x: Float, now: Long): List<Out> {
        if (!tracking) return emptyList()
        val dx = x - startX
        if (abs(dx) > slopPx) moved = true
        if (fired || abs(dx) < thresholdPx) return emptyList()
        fired = true // locked until lift
        val right = dx > 0
        return if (inCooldown(now)) {
            pending = right // a newer slide replaces an older queued one: at most one pending
            listOf(Out.Committed(right, queued = true))
        } else {
            lastStepAt = now
            listOf(Out.Committed(right, queued = false), Out.Step(right))
        }
    }

    fun up(now: Long): List<Out> {
        if (!tracking) return emptyList()
        tracking = false
        val out = mutableListOf<Out>()
        if (!moved) out += Out.MissionControl
        out += Out.ReleaseAll
        return out + tick(now)
    }

    /** The pointer stream was cancelled (e.g. the screen changed): drop the queue, release keys. */
    fun cancel(): List<Out> {
        tracking = false; pending = null
        return listOf(Out.ReleaseAll)
    }

    /** Releases a queued step once the cooldown has passed. */
    fun tick(now: Long): List<Out> {
        val p = pending ?: return emptyList()
        if (inCooldown(now)) return emptyList()
        pending = null
        lastStepAt = now
        return listOf(Out.Step(p))
    }

    /** Rubber-banded visual offset of the tile content for a raw finger offset [dx]. */
    fun follow(dx: Float): Float {
        val s = if (dx < 0) -1f else 1f
        val a = abs(dx)
        val inside = a.coerceAtMost(thresholdPx) * 0.45f
        val beyond = (a - thresholdPx).coerceAtLeast(0f) * 0.12f
        return s * (inside + beyond).coerceAtMost(thresholdPx * 0.6f)
    }

    companion object {
        const val COOLDOWN_MS = 450L
        const val THRESHOLD_FRACTION = 0.4f
    }
}
