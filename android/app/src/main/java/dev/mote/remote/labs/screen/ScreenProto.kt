package dev.mote.remote.labs.screen

import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Labs › Live screen messages (docs/labs/live-screen.md). Pure: unit-tested and used by the helper integration test. */
object ScreenProto {
    private fun obj(t: String) = JSONObject().put("t", t)

    /** Start (or restart) streaming the active display: up to [w] px wide, [fps], [credits] frames in flight. */
    fun start(w: Int = 800, fps: Int = 10, credits: Int = 2): String =
        obj("labs.screen.start").put("w", w.coerceIn(480, 960)).put("fps", fps.coerceIn(2, 15)).put("credits", credits.coerceIn(1, 3)).toString()
    fun stop(): String = obj("labs.screen.stop").toString()
    /** Returns the frame's credit; also the 0.5 s heartbeat that keeps the 2 s lease alive (any seq). */
    fun ack(seq: Int): String = obj("labs.screen.ack").put("seq", seq).toString()
    /** Click at the normalised point ([x], [y] in 0..1 of the frame) of [display]; [button] left / right, [count] 1 / 2. */
    fun click(x: Double, y: Double, button: String = "left", count: Int = 1, display: String? = null): String =
        obj("labs.screen.click").put("x", x.coerceIn(0.0, 1.0)).put("y", y.coerceIn(0.0, 1.0)).put("button", button)
            .put("count", count.coerceIn(1, 2)).apply { if (display != null) put("display", display) }.toString()

    class Frame(
        val seq: Int, val display: String, val w: Int, val h: Int, val jpeg: ByteArray,
        val cx: Float?, val cy: Float?, val fps: Double, val rttMs: Int, val ts: Long,
    )
    data class Started(val display: String?, val w: Int, val h: Int, val fps: Double, val encrypted: Boolean)

    sealed interface In
    data class FrameIn(val f: Frame) : In
    data class StartedIn(val s: Started) : In
    data class StoppedIn(val reason: String) : In
    data class ErrIn(val code: String, val msg: String) : In

    /** A [dev.mote.remote.labs.LabsBus] message to a live screen event, or null if it isn't one / is malformed. */
    fun parse(o: JSONObject): In? = runCatching {
        when (o.optString("t")) {
            "labs.screen.frame" -> {
                val jpeg = java.util.Base64.getDecoder().decode(o.getString("data"))
                if (jpeg.size < 4) return null
                FrameIn(Frame(
                    o.getInt("seq"), o.optString("display"), o.optInt("w"), o.optInt("h"), jpeg,
                    if (o.has("cx")) o.getDouble("cx").toFloat().coerceIn(0f, 1f) else null,
                    if (o.has("cy")) o.getDouble("cy").toFloat().coerceIn(0f, 1f) else null,
                    o.optDouble("fps", 0.0), o.optInt("rtt"), o.optLong("ts"),
                ))
            }
            "labs.screen.started" -> StartedIn(Started(
                o.optString("display").ifEmpty { null }, o.optInt("w"), o.optInt("h"), o.optDouble("fps", 0.0), o.optBoolean("encrypted"),
            ))
            "labs.screen.stopped" -> StoppedIn(o.optString("reason", "stop"))
            "err" -> if (o.optString("ref").startsWith("labs.screen.")) ErrIn(o.optString("code"), o.optString("msg")) else null
            else -> null
        }
    }.getOrNull()

    /** What to tell the user for a `stopped` reason or an error code; null = nothing (normal stop). */
    fun explain(reasonOrCode: String, msg: String = ""): String? = when (reasonOrCode) {
        "stop", "gone" -> null
        "paused", "sharing-paused" -> "Screen sharing is paused on the Mac (Mote Helper menu › Labs)."
        "off", "labs-off" -> "Turn on Labs › Live screen in the Mote Helper menu on the Mac."
        "taken" -> "Another device is viewing the screen."
        "timeout" -> "The stream timed out."
        "denied" -> "Allow Screen Recording for Mote Helper on the Mac (System Settings › Privacy & Security)."
        "unsupported" -> "Live screen needs Mote Helper over Wi-Fi or Tailscale."
        "unknown" -> "Update Mote Helper on the Mac to use Live screen."
        else -> msg.ifEmpty { "Live screen stopped ($reasonOrCode)." }
    }
}

/**
 * View math for the zoomable viewer. The image is fitted (letterboxed) into the box, then the user's
 * zoom [scale] (>= 1, around the box centre) and pan [offset] (px) are applied, like graphicsLayer.
 */
object ViewMath {
    data class Fit(val left: Float, val top: Float, val width: Float, val height: Float)

    fun fit(boxW: Float, boxH: Float, imgW: Float, imgH: Float): Fit {
        if (imgW <= 0f || imgH <= 0f || boxW <= 0f || boxH <= 0f) return Fit(0f, 0f, boxW, boxH)
        val s = min(boxW / imgW, boxH / imgH)
        val w = imgW * s; val h = imgH * s
        return Fit((boxW - w) / 2, (boxH - h) / 2, w, h)
    }

    /** A touch at ([px], [py]) in the box to the normalised image point, or null if it misses the image. */
    fun toNorm(px: Float, py: Float, boxW: Float, boxH: Float, imgW: Float, imgH: Float, scale: Float, ox: Float, oy: Float): Pair<Float, Float>? {
        val f = fit(boxW, boxH, imgW, imgH)
        val cx = boxW / 2; val cy = boxH / 2
        val qx = cx + (px - ox - cx) / scale
        val qy = cy + (py - oy - cy) / scale
        val nx = (qx - f.left) / f.width
        val ny = (qy - f.top) / f.height
        return if (nx in 0f..1f && ny in 0f..1f) nx to ny else null
    }

    /** The normalised image point to box px (inverse of [toNorm]; for the cursor overlay). */
    fun fromNorm(nx: Float, ny: Float, boxW: Float, boxH: Float, imgW: Float, imgH: Float, scale: Float, ox: Float, oy: Float): Pair<Float, Float> {
        val f = fit(boxW, boxH, imgW, imgH)
        val cx = boxW / 2; val cy = boxH / 2
        val qx = f.left + nx * f.width; val qy = f.top + ny * f.height
        return (cx + (qx - cx) * scale + ox) to (cy + (qy - cy) * scale + oy)
    }

    const val MAX_ZOOM = 6f

    /** Keeps the zoomed image covering the box where it can: pan limited to the overflow on each axis. */
    fun clampPan(ox: Float, oy: Float, scale: Float, boxW: Float, boxH: Float, imgW: Float, imgH: Float): Pair<Float, Float> {
        val f = fit(boxW, boxH, imgW, imgH)
        val mx = max(0f, (f.width * scale - boxW) / 2); val my = max(0f, (f.height * scale - boxH) / 2)
        return (ox.coerceIn(-mx, mx) + 0f) to (oy.coerceIn(-my, my) + 0f) // + 0f: no -0f
    }
}

/**
 * Double-tap as macOS sees it: the first tap is sent at once as a single click; a second tap within
 * [windowMs] and [slop] (normalised) of it becomes a count-2 click at the first tap's point.
 */
class TapCounter(private val windowMs: Long = 300, private val slop: Float = 0.03f) {
    private var lastAt = Long.MIN_VALUE / 2
    private var lastX = 0f; private var lastY = 0f
    private var lastCount = 0

    /** Returns (count, x, y) to send for a tap at ([x], [y]) at [nowMs]. */
    fun tap(x: Float, y: Float, nowMs: Long): Triple<Int, Float, Float> {
        val near = abs(x - lastX) <= slop && abs(y - lastY) <= slop
        return if (lastCount == 1 && nowMs - lastAt <= windowMs && near) {
            lastCount = 2; lastAt = nowMs
            Triple(2, lastX, lastY)
        } else {
            lastCount = 1; lastAt = nowMs; lastX = x; lastY = y
            Triple(1, x, y)
        }
    }
}
