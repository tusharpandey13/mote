package dev.mote.remote.ui

/**
 * Prev/next cap: a tap skips the track; holding past [thresholdMs] seeks (fast-forward / rewind held
 * down) until the finger lifts. Cancel and dispose always release a held seek and never skip.
 */
class SeekHold(private val thresholdMs: Long = THRESHOLD_MS) {
    enum class Out { SKIP, SEEK_START, SEEK_STOP }

    private var downAt = -1L
    var seeking = false
        private set

    fun down(now: Long) { downAt = now; seeking = false }

    /** Called while held (from a timer); starts seeking once past the threshold. */
    fun tick(now: Long): Out? {
        if (downAt < 0 || seeking || now - downAt < thresholdMs) return null
        seeking = true
        return Out.SEEK_START
    }

    fun up(now: Long): Out? {
        if (downAt < 0) return null
        val held = now - downAt
        val wasSeeking = seeking
        downAt = -1; seeking = false
        return when {
            wasSeeking -> Out.SEEK_STOP
            held < thresholdMs -> Out.SKIP
            else -> null // the hold ended before the seek timer fired: neither skip nor seek
        }
    }

    fun cancel(): Out? {
        val wasSeeking = seeking
        downAt = -1; seeking = false
        return if (wasSeeking) Out.SEEK_STOP else null
    }

    companion object { const val THRESHOLD_MS = 400L }
}
