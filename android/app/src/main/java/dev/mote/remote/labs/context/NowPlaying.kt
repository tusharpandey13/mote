package dev.mote.remote.labs.context

import org.json.JSONObject

/**
 * What the Mac is playing, anchored to this phone's clock: the position was [elapsed] seconds at
 * [anchorMs] (a monotonic phone clock, passed in by the caller) and moves at [rate].
 */
class NowPlaying(
    val title: String,
    val artist: String,
    val app: String,
    val bundle: String?,
    val duration: Double,
    val elapsed: Double,
    val rate: Double,
    val anchorMs: Long,
    val art: ByteArray?,
    /** The base64 the [art] came from, so an unchanged picture isn't decoded again. */
    val artKey: String?,
) {
    val playing: Boolean get() = rate > 0.0

    fun position(nowMs: Long): Double {
        val p = elapsed + (nowMs - anchorMs).coerceAtLeast(0) / 1000.0 * rate
        return if (duration > 0) p.coerceIn(0.0, duration) else p.coerceAtLeast(0.0)
    }

    fun copyAt(elapsed: Double, anchorMs: Long) = NowPlaying(title, artist, app, bundle, duration, elapsed, rate, anchorMs, art, artKey)
}

/** The now-playing card's state (`labs.np` plus the pending seek). */
data class NpState(
    val enabled: Boolean = false,
    /** `fake` | `mediaremote` | `starting` | `unavailable` | `off` */
    val source: String = "off",
    val np: NowPlaying? = null,
    /** Seek paths the helper offers, best first (`mediaremote`, `fake`, `js`, `keys`). */
    val seek: List<String> = emptyList(),
    /** A seek we sent and the Mac hasn't confirmed yet: the scrubber shows it instead of the extrapolated position. */
    val pendingSeek: Double? = null,
    val pendingSinceMs: Long = 0,
    /** Last hint from the helper (e.g. "turn on Allow JavaScript from Apple Events"). */
    val note: String? = null,
) {
    /** True when the scrubber can set an absolute position (the Mac knows the duration). */
    val canScrub: Boolean get() = np != null && np.duration > 0

    fun displayPosition(nowMs: Long): Double? {
        val p = pendingSeek
        if (p != null && nowMs - pendingSinceMs < NpReducer.PENDING_MS) return p
        return np?.position(nowMs)
    }
}

sealed interface NpEvent {
    /** A `labs.np` message received at [recvMs]. */
    data class Update(val o: JSONObject, val recvMs: Long) : NpEvent
    data class SeekSent(val to: Double, val nowMs: Long) : NpEvent
    /** `labs.np.seek` reply: how the Mac seeked and a hint, if any. */
    data class SeekReply(val via: String, val to: Double?, val note: String?, val nowMs: Long) : NpEvent
    data class Error(val msg: String) : NpEvent
    data object Reset : NpEvent
}

/** Pure state reduction for the now-playing card. */
object NpReducer {
    /** How long a sent seek wins over the Mac's reports. */
    const val PENDING_MS = 3000L
    /** A report this close to the pending seek confirms it. */
    const val CONFIRM_S = 2.0

    fun reduce(s: NpState, e: NpEvent, decode: (String) -> ByteArray? = ::b64): NpState = when (e) {
        NpEvent.Reset -> NpState()
        is NpEvent.Error -> s.copy(note = e.msg, pendingSeek = null)
        is NpEvent.SeekSent -> s.copy(pendingSeek = e.to, pendingSinceMs = e.nowMs, note = null)
        is NpEvent.SeekReply -> {
            // Only MediaRemote reports the new position back; for keys / js move the anchor ourselves.
            val np = s.np
            val moved = if (np != null && e.to != null && e.via != "mediaremote" && e.via != "fake") np.copyAt(e.to, e.nowMs) else np
            s.copy(np = moved, note = e.note, pendingSeek = if (e.to == null) null else s.pendingSeek)
        }
        is NpEvent.Update -> update(s, e.o, e.recvMs, decode)
    }

    private fun update(s: NpState, o: JSONObject, recvMs: Long, decode: (String) -> ByteArray?): NpState {
        if (!o.optBoolean("enabled", false)) return NpState(enabled = false)
        val seek = o.optJSONArray("seek")?.let { a -> List(a.length()) { a.optString(it) }.filter { it.isNotEmpty() } }.orEmpty()
        val source = o.optString("source", "off")
        val j = o.optJSONObject("np") ?: return s.copy(enabled = true, source = source, seek = seek, np = null, pendingSeek = null)
        val elapsed = j.optDouble("elapsed", 0.0).let { if (it.isNaN()) 0.0 else it }
        val rate = j.optDouble("rate", 0.0).let { if (it.isNaN()) 0.0 else it.coerceIn(0.0, 4.0) }
        // Position at receive time: the Mac stamps `at` (when `elapsed` was true) and `now` (when it sent).
        val at = j.optLong("at", 0L)
        val macNow = o.optLong("now", 0L)
        val lag = if (at > 0 && macNow >= at) (macNow - at).coerceAtMost(24 * 3600_000L) / 1000.0 * rate else 0.0
        val artB64 = j.optString("art").ifEmpty { null }
        val prev = s.np
        val art = when {
            artB64 == null -> null
            prev?.artKey == artB64 -> prev.art
            else -> decode(artB64)
        }
        val np = NowPlaying(
            title = j.optString("title").take(200), artist = j.optString("artist").take(200), app = j.optString("app").take(64),
            bundle = j.optString("bundle").ifEmpty { null },
            duration = j.optDouble("duration", 0.0).let { if (it.isNaN() || it < 0) 0.0 else it },
            elapsed = elapsed + lag, rate = rate, anchorMs = recvMs, art = art, artKey = if (art != null) artB64 else null,
        )
        val pending = s.pendingSeek
        val confirmed = pending != null && (kotlin.math.abs(np.position(recvMs) - pending) <= CONFIRM_S || recvMs - s.pendingSinceMs >= PENDING_MS)
        return s.copy(enabled = true, source = source, seek = seek, np = np, pendingSeek = if (confirmed) null else pending)
    }

    private fun b64(s: String): ByteArray? = runCatching { java.util.Base64.getDecoder().decode(s) }.getOrNull()?.takeIf { it.isNotEmpty() }
}

/** "1:05", "1:02:03". */
fun formatTime(seconds: Double): String {
    val t = if (seconds.isNaN() || seconds < 0) 0L else seconds.toLong()
    val h = t / 3600; val m = (t % 3600) / 60; val sec = t % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}
