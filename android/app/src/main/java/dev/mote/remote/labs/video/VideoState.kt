package dev.mote.remote.labs.video

import org.json.JSONObject

/**
 * `labs.video.state` (docs/labs/video-bridge.md section 1), clamped. [time] is `currentTime` at
 * [receivedAt] (ms, caller's clock); use [VideoState.position] for the live position.
 */
data class VideoState(
    val bridge: String = "off",
    val active: Boolean = false,
    val title: String = "",
    val host: String = "",
    val site: String = "generic",
    /** Seconds; null when live or unknown. */
    val duration: Double? = null,
    val time: Double = 0.0,
    val paused: Boolean = true,
    val rate: Double = 1.0,
    val muted: Boolean = false,
    val volume: Double = 1.0,
    val fullscreen: Boolean = false,
    val live: Boolean = false,
    val seekable: Boolean = false,
    val hasNext: Boolean = false,
    val hasPrev: Boolean = false,
    /** `on` / `off` / `none`. */
    val captions: String = "none",
    /** Visible skip buttons: `ad` / `intro` / `recap` / `credits`. */
    val skips: List<String> = emptyList(),
    val receivedAt: Long = 0L,
) {
    /** The scrubber can seek: the page says so and the video has a finite length. */
    val canSeek: Boolean get() = seekable && !live && duration != null

    sealed interface Event {
        data class Received(val state: VideoState) : Event
        /** A command just sent: show its effect before the next state confirms it. */
        data class Optimistic(val cmd: String, val value: Any?, val nowMs: Long) : Event
        data object Disconnected : Event
    }

    companion object {
        const val RATE_MIN = 0.25
        const val RATE_MAX = 4.0
        const val TITLE_MAX = 200
        const val HOST_MAX = 100
        private val SKIPS = setOf("ad", "intro", "recap", "credits")
        private val CAPTIONS = setOf("on", "off", "none")

        private fun num(o: JSONObject, k: String): Double? =
            if (!o.has(k) || o.isNull(k)) null else (o.opt(k) as? Number)?.toDouble()?.takeIf { it.isFinite() }

        fun parse(o: JSONObject, nowMs: Long): VideoState {
            val arr = o.optJSONArray("skips")
            val skips = (0 until (arr?.length() ?: 0)).mapNotNull { arr?.optString(it)?.takeIf { s -> s in SKIPS } }.distinct()
            val duration = num(o, "duration")?.takeIf { it > 0 }
            val time = (num(o, "time") ?: 0.0).coerceAtLeast(0.0).let { if (duration != null) it.coerceAtMost(duration) else it }
            return VideoState(
                bridge = o.optString("bridge", "off").take(16),
                active = o.optBoolean("active"),
                title = o.optString("title").take(TITLE_MAX),
                host = o.optString("host").take(HOST_MAX),
                site = o.optString("site", "generic").take(16),
                duration = duration,
                time = time,
                paused = o.optBoolean("paused", true),
                rate = (num(o, "rate") ?: 1.0).coerceIn(RATE_MIN, RATE_MAX),
                muted = o.optBoolean("muted"),
                volume = (num(o, "volume") ?: 1.0).coerceIn(0.0, 1.0),
                fullscreen = o.optBoolean("fullscreen"),
                live = o.optBoolean("live"),
                seekable = o.optBoolean("seekable"),
                hasNext = o.optBoolean("hasNext"),
                hasPrev = o.optBoolean("hasPrev"),
                captions = o.optString("captions", "none").takeIf { it in CAPTIONS } ?: "none",
                skips = skips,
                receivedAt = nowMs,
            )
        }

        /** Extrapolated position in seconds: `time + elapsed × rate` while playing, clamped to 0..duration. */
        fun position(s: VideoState, nowMs: Long): Double {
            val t = if (s.paused) s.time else s.time + (nowMs - s.receivedAt).coerceAtLeast(0L) / 1000.0 * s.rate
            val d = s.duration
            return if (d != null) t.coerceIn(0.0, d) else t.coerceAtLeast(0.0)
        }

        private fun flag(value: Any?, current: Boolean): Boolean = value as? Boolean ?: !current
        private fun dbl(value: Any?): Double? = (value as? Number)?.toDouble()?.takeIf { it.isFinite() }

        /** null = no video (link down or bridge off). */
        fun reduce(state: VideoState?, e: Event): VideoState? = when (e) {
            is Event.Received -> e.state
            Event.Disconnected -> null
            is Event.Optimistic -> state?.let { optimistic(it, e) }
        }

        private fun optimistic(s: VideoState, e: Event.Optimistic): VideoState {
            // re-anchor the clock first, so changing paused / rate / time doesn't jump the extrapolation
            val now = s.copy(time = position(s, e.nowMs), receivedAt = e.nowMs)
            fun seekTo(t: Double) = now.copy(time = t.coerceAtLeast(0.0).let { x -> s.duration?.let { x.coerceAtMost(it) } ?: x })
            return when (e.cmd) {
                "play" -> now.copy(paused = false)
                "pause" -> now.copy(paused = true)
                "toggle" -> now.copy(paused = !s.paused)
                "seek" -> if (!s.canSeek) now else dbl(e.value)?.let(::seekTo) ?: now
                "seekBy" -> if (!s.canSeek) now else dbl(e.value)?.let { seekTo(now.time + it) } ?: now
                "rate" -> dbl(e.value)?.let { now.copy(rate = it.coerceIn(RATE_MIN, RATE_MAX)) } ?: now
                "volume" -> dbl(e.value)?.let { now.copy(volume = it.coerceIn(0.0, 1.0), muted = false) } ?: now
                "mute" -> now.copy(muted = flag(e.value, s.muted))
                "fullscreen" -> now.copy(fullscreen = flag(e.value, s.fullscreen))
                "captions" -> if (s.captions == "none") now else now.copy(captions = if (flag(e.value, s.captions == "on")) "on" else "off")
                "skip" -> now.copy(skips = (e.value as? String)?.let { k -> s.skips - k } ?: s.skips.drop(1))
                else -> now
            }
        }

        private fun base(t: String) = JSONObject().put("t", t)

        fun sub(on: Boolean): String = base("labs.video.sub").put("on", on).toString()

        /** [value]: null = absent (toggle for mute / fullscreen / captions, first button for skip). */
        fun cmd(cmd: String, value: Any? = null): String =
            base("labs.video.cmd").put("cmd", cmd).apply { if (value != null) put("value", value) }.toString()
    }
}
