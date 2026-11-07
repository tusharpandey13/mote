package dev.mote.remote.labs.video

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.mote.remote.ui.Cap
import dev.mote.remote.ui.Haptic
import dev.mote.remote.ui.T
import dev.mote.remote.ui.theme.LocalMote
import dev.mote.remote.ui.theme.MIcon
import dev.mote.remote.ui.theme.Tone
import dev.mote.remote.ui.theme.metal
import dev.mote.remote.ui.theme.raised
import dev.mote.remote.ui.theme.well
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** Fixed height, so MediaScreen can budget it in place of Favourites + brightness. */
val WEB_VIDEO_PANEL_HEIGHT = 184.dp

private val SPEEDS = listOf(0.5, 1.0, 1.25, 1.5, 2.0)

/** Visible when the Labs flag is on (else [VideoBridge.state] stays null) and a tab reports a video. */
@Composable
fun webVideoVisible(): Boolean = VideoBridge.state.collectAsState().value?.active == true

/**
 * Labs preview: the main video of the Mac's browser tab (docs/labs/video-bridge.md). Title and host,
 * a scrubber (seeks on release), ±10 s, play / pause, speed, captions, skip, fullscreen, prev / next.
 */
@Composable
fun WebVideoPanel(modifier: Modifier = Modifier) {
    val c = LocalMote.current
    val st by VideoBridge.state.collectAsState()
    val s = st?.takeIf { it.active } ?: return
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(s) {
        now = SystemClock.elapsedRealtime()
        while (!s.paused) { delay(500); now = SystemClock.elapsedRealtime() }
    }
    val pos = VideoState.position(s, now)
    Column(
        modifier.fillMaxWidth().height(WEB_VIDEO_PANEL_HEIGHT).raised(RoundedCornerShape(22.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Row(Modifier.fillMaxWidth().height(24.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.well(RoundedCornerShape(8.dp)).padding(horizontal = 6.dp, vertical = 2.dp)) {
                Text("PREVIEW", style = T.caps, color = c.accent)
            }
            Spacer(Modifier.width(8.dp))
            Text(s.title.ifBlank { "Browser video" }, style = T.label, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (s.host.isNotBlank()) {
                Spacer(Modifier.width(8.dp))
                Text(s.host.removePrefix("www."), style = T.small, color = c.subtle, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(0.6f, fill = false))
            }
        }
        Row(Modifier.fillMaxWidth().height(32.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(clock(pos), style = T.small, color = c.muted, maxLines = 1)
            Spacer(Modifier.width(8.dp))
            Scrubber(s, pos, Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            Text(if (s.live || s.duration == null) "LIVE" else clock(s.duration), style = T.small, color = if (s.live) c.accent else c.muted, maxLines = 1)
        }
        Row(Modifier.fillMaxWidth().height(52.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (s.hasPrev) IconCap("prev", "Previous video") { VideoBridge.command("prev") }
            TextCap("−10", "Back 10 seconds", enabled = s.canSeek) { VideoBridge.command("seekBy", -10) }
            Cap(
                Modifier.weight(1.4f).fillMaxSize(), RoundedCornerShape(16.dp), tone = Tone.PRIMARY,
                description = if (s.paused) "Play video" else "Pause video", onClick = { VideoBridge.command("toggle") },
            ) { if (s.paused) MIcon("playPause", c.onPrim, size = 26.dp) else PauseGlyph() }
            TextCap("+10", "Forward 10 seconds", enabled = s.canSeek) { VideoBridge.command("seekBy", 10) }
            if (s.hasNext) IconCap("next", "Next video") { VideoBridge.command("next") }
        }
        Row(Modifier.fillMaxWidth().height(40.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TextCap(speedLabel(s.rate), "Playback speed ${speedLabel(s.rate)}, tap for the next speed") {
                VideoBridge.command("rate", SPEEDS.firstOrNull { it > s.rate + 0.01 } ?: SPEEDS.first())
            }
            if (s.captions != "none") {
                TextCap("CC", if (s.captions == "on") "Captions on, tap to turn off" else "Captions off, tap to turn on", lit = s.captions == "on") {
                    VideoBridge.command("captions")
                }
            }
            TextCap(if (s.fullscreen) "Exit full" else "Full", if (s.fullscreen) "Exit full screen" else "Full screen", lit = s.fullscreen) {
                VideoBridge.command("fullscreen")
            }
            s.skips.firstOrNull()?.let { k ->
                val label = "Skip ${if (k == "ad") "ad" else k}"
                TextCap(label, label, weight = 1.6f, lit = true) { VideoBridge.command("skip", k) }
            }
        }
    }
}

@Composable
private fun RowScope.IconCap(icon: String, description: String, onClick: () -> Unit) {
    val c = LocalMote.current
    Cap(Modifier.weight(1f).fillMaxSize(), RoundedCornerShape(16.dp), description = description, onClick = onClick) {
        MIcon(icon, c.ink, size = 22.dp)
    }
}

@Composable
private fun RowScope.TextCap(
    label: String, description: String, enabled: Boolean = true, lit: Boolean = false, weight: Float = 1f, onClick: () -> Unit,
) {
    val c = LocalMote.current
    Cap(
        Modifier.weight(weight).fillMaxSize().alpha(if (enabled) 1f else 0.38f).semantics { if (!enabled) disabled() },
        RoundedCornerShape(14.dp), haptic = if (enabled) Haptic.CLICK else Haptic.REJECT, description = description,
        forcePressed = lit, onClick = { if (enabled) onClick() },
    ) {
        Text(label, style = T.label, color = if (lit) c.accent else c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun PauseGlyph() {
    val c = LocalMote.current
    Canvas(Modifier.size(22.dp)) {
        val w = size.width * 0.26f
        val gap = size.width * 0.18f
        val x0 = (size.width - 2 * w - gap) / 2
        listOf(x0, x0 + w + gap).forEach { x ->
            drawRoundRect(c.onPrim, Offset(x, size.height * 0.12f), androidx.compose.ui.geometry.Size(w, size.height * 0.76f),
                androidx.compose.ui.geometry.CornerRadius(w * 0.3f))
        }
    }
}

/** Track in a well with an accent fill and a metal thumb; drag to preview, seek on release. */
@Composable
private fun Scrubber(s: VideoState, pos: Double, modifier: Modifier) {
    val c = LocalMote.current
    val d = s.duration
    val enabled = s.canSeek && d != null
    var drag by remember { mutableStateOf<Float?>(null) }
    val frac = drag ?: if (d != null && d > 0) (pos / d).toFloat().coerceIn(0f, 1f) else 0f
    val thumb = 18.dp
    BoxWithConstraints(
        modifier.height(28.dp).well(CircleShape)
            .semantics {
                contentDescription = "Video position"
                stateDescription = if (d == null) "Live" else "${clock(pos)} of ${clock(d)}"
                if (d != null) progressBarRangeInfo = ProgressBarRangeInfo(pos.toFloat().coerceIn(0f, d.toFloat()), 0f..d.toFloat())
                if (enabled) setProgress { v -> VideoBridge.command("seek", v.toDouble()) } else disabled()
            },
    ) {
        val density = LocalDensity.current
        val trackPx = with(density) { (maxWidth - thumb - 6.dp).toPx() }.coerceAtLeast(1f)
        val pad = with(density) { (thumb / 2 + 3.dp).toPx() }
        Canvas(
            Modifier.fillMaxSize().pointerInput(enabled, d, trackPx) {
                if (!enabled || d == null) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown()
                    fun at(x: Float) = ((x - pad) / trackPx).coerceIn(0f, 1f)
                    drag = at(down.position.x)
                    var lifted = false
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (!ch.pressed) { lifted = true; break }
                        drag = at(ch.position.x); ch.consume()
                    }
                    val f = drag
                    if (lifted && f != null) VideoBridge.command("seek", f.toDouble() * d)
                    drag = null
                }
            },
        ) {
            val cy = size.height / 2
            if (d != null) drawLine(c.accent, Offset(pad, cy), Offset(pad + trackPx * frac, cy), 4.dp.toPx(), cap = StrokeCap.Round)
        }
        if (d != null) {
            Box(
                Modifier.offset { IntOffset((3.dp.toPx() + trackPx * frac).roundToInt(), 5.dp.roundToPx()) }
                    .size(thumb).alpha(if (enabled) 1f else 0.38f).metal(CircleShape),
            )
        }
    }
}

private fun speedLabel(r: Double): String = if (r == r.roundToInt().toDouble()) "${r.roundToInt()}×" else "%.2g×".format(r)

/** m:ss, or h:mm:ss from an hour. */
internal fun clock(sec: Double): String {
    val t = sec.coerceAtLeast(0.0).toLong()
    val h = t / 3600; val m = t / 60 % 60; val s = t % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
