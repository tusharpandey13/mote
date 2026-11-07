package dev.mote.remote.labs.context

import android.graphics.BitmapFactory
import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mote.remote.labs.Labs
import dev.mote.remote.net.Remote
import dev.mote.remote.ui.Cap
import dev.mote.remote.ui.Haptic
import dev.mote.remote.ui.Mode
import dev.mote.remote.ui.T
import dev.mote.remote.ui.haptics
import dev.mote.remote.ui.theme.LocalMote
import dev.mote.remote.ui.theme.metal
import dev.mote.remote.ui.theme.well
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Runs the Smart remote lab for the whole app: the helper subscription and the auto-keyboard
 * (switches to Keys when a Mac text field gains focus, back when it loses it).
 */
@Composable
fun SmartRemoteEffects(mode: Mode, setMode: (Mode) -> Unit) {
    val ctx = LocalContext.current
    val enabled = remember { Labs.flow(ctx, Labs.SMART_REMOTE) }
    LaunchedEffect(Unit) { SmartRemote.run(enabled) }
    val on by enabled.collectAsState(initial = false)
    val auto by remember { Labs.bool(ctx, SmartRemote.AUTO_KEYBOARD, true) }.collectAsState(initial = true)
    val mc by SmartRemote.context.collectAsState()
    val curMode by rememberUpdatedState(mode)
    var ak by remember { mutableStateOf(AutoKeyboard()) }
    var back by remember { mutableStateOf(Mode.MEDIA) }
    LaunchedEffect(mc, on, auto) {
        val (next, action) = ak.next(mc, on && auto, curMode == Mode.KEYS)
        ak = next
        when (action) {
            AutoKeyboard.Action.OPEN -> { back = curMode; setMode(Mode.KEYS); SmartRemote.pendingFocus.value = true }
            AutoKeyboard.Action.CLOSE -> setMode(back)
            AutoKeyboard.Action.NONE -> Unit
        }
        SmartRemote.autoOpened.value = ak.opened
    }
    // Leaving Keys by hand ends the automatic session.
    LaunchedEffect(mode) { if (mode != Mode.KEYS && ak.opened) { ak = ak.copy(opened = false); SmartRemote.autoOpened.value = false } }
}

/** Small "PREVIEW" tag: every Labs surface carries it. */
@Composable
private fun PreviewTag() {
    val c = LocalMote.current
    Box(Modifier.clip(RoundedCornerShape(6.dp)).well(RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp)) {
        Text("PREVIEW", style = T.caps.copy(fontSize = 10.sp), color = c.accent)
    }
}

/** Above Keys while it was opened for a Mac text field: says why, and turns the behaviour off. */
@Composable
fun AutoKeyboardChip() {
    val ctx = LocalContext.current
    val c = LocalMote.current
    val scope = rememberCoroutineScope()
    val opened by SmartRemote.autoOpened.collectAsState()
    val mc by SmartRemote.context.collectAsState()
    if (!opened) return
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PreviewTag()
        Text(
            (if (mc.secure) "Password field on the Mac" else "Text field on the Mac") + (mc.app.takeIf { it.isNotEmpty() }?.let { " · $it" } ?: ""),
            style = T.small, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        Cap(Modifier.heightIn(min = 44.dp), RoundedCornerShape(12.dp), needsLink = false, description = "Stop auto-keyboard", onClick = {
            scope.launch { Labs.setBool(ctx, SmartRemote.AUTO_KEYBOARD, false) }
            SmartRemote.autoOpened.value = false
        }) { Text("Stop auto-keyboard", style = T.small, color = c.ink, modifier = Modifier.padding(horizontal = 12.dp)) }
    }
}

/**
 * Contextual panel above the Media screen: now playing (artwork, title, scrubber, ±10 s) and the
 * quick actions of the matching profile. Shows nothing for apps without a profile when nothing plays.
 */
@Composable
fun SmartPanel() {
    val ctx = LocalContext.current
    val c = LocalMote.current
    val scope = rememberCoroutineScope()
    val on by remember { Labs.flow(ctx, Labs.SMART_REMOTE) }.collectAsState(initial = false)
    if (!on) return
    val helperUp by SmartRemote.helperUp.collectAsState()
    val mc by SmartRemote.context.collectAsState()
    val np by SmartRemote.nowPlaying.collectAsState()
    val auto by remember { Labs.bool(ctx, SmartRemote.AUTO_KEYBOARD, true) }.collectAsState(initial = true)
    val profile = ContextRules.match(mc, np.np?.bundle)
    val info = np.np

    val hint = when {
        !helperUp ->"Smart remote needs the Mote Helper over Wi-Fi or Tailscale."
        !mc.enabled && !np.enabled -> "Turn on Labs › Context & now playing in the Mote Helper menu on the Mac."
        mc.browser && mc.automation == "denied" -> "Allow Mote Helper to control ${mc.app} (System Settings › Privacy › Automation) to see the tab."
        else -> null
    }
    if (hint == null && info == null && profile == null) return

    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp)) {
        Row(Modifier.fillMaxWidth().height(28.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PreviewTag()
            val where = listOfNotNull(profile?.title, mc.app.takeIf { it.isNotEmpty() && it != profile?.title }).joinToString(" · ")
            Text(
                if (where.isEmpty()) "SMART REMOTE" else where.uppercase(), style = T.caps, color = c.subtle,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            if (!auto && mc.enabled) {
                Text(
                    "Auto-keyboard off · turn on", style = T.small, color = c.accent,
                    modifier = Modifier.clickable(onClickLabel = "Turn auto-keyboard on") { scope.launch { Labs.setBool(ctx, SmartRemote.AUTO_KEYBOARD, true) } }
                        .padding(vertical = 6.dp),
                )
            }
        }
        if (hint != null) {
            Text(hint, style = T.body.copy(fontSize = 13.sp, lineHeight = 18.sp), color = c.muted, modifier = Modifier.padding(vertical = 4.dp))
        }
        if (info != null) NowPlayingCard(np, info)
        np.note?.let { Text(it, style = T.small, color = c.muted, maxLines = 2, modifier = Modifier.padding(top = 4.dp)) }
        if (profile != null) {
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                profile.actions.forEach { a ->
                    Cap(Modifier.height(48.dp).widthIn(min = 72.dp), RoundedCornerShape(14.dp), haptic = Haptic.CLICK, description = a.description,
                        onClick = { Remote.tap(a.key, a.mods) }) {
                        Text(a.label, style = T.label, color = c.ink, maxLines = 1, modifier = Modifier.padding(horizontal = 12.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun NowPlayingCard(st: NpState, np: NowPlaying) {
    val c = LocalMote.current
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(np.playing, np.anchorMs, st.pendingSeek) {
        now = SystemClock.elapsedRealtime()
        while (np.playing || st.pendingSeek != null) { delay(250); now = SystemClock.elapsedRealtime() }
    }
    val pos = st.displayPosition(now) ?: 0.0
    Column(Modifier.fillMaxWidth().well(RoundedCornerShape(20.dp)).padding(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val bmp = remember(np.artKey) { np.art?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }.getOrNull() } }
            Box(Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)).well(RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                if (bmp != null) Image(bmp, contentDescription = "Artwork", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                else Text("♪", style = T.title, color = c.subtle)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(np.title.ifEmpty { "Playing" }, style = T.label, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val sub = listOf(np.artist, np.app).filter { it.isNotEmpty() }.distinct().joinToString(" · ")
                if (sub.isNotEmpty()) Text(sub, style = T.small, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(if (np.playing) "PLAYING" else "PAUSED", style = T.caps.copy(fontSize = 10.sp), color = if (np.playing) c.accent else c.subtle)
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Cap(Modifier.size(48.dp), CircleShape, haptic = Haptic.TICK, description = "Back 10 seconds", onClick = { SmartRemote.seekBy(-10) }) {
                Text("−10", style = T.small, color = c.ink)
            }
            Column(Modifier.weight(1f)) {
                var drag by remember { mutableStateOf<Double?>(null) }
                Scrubber(
                    position = drag ?: pos, duration = np.duration, enabled = st.canScrub,
                    onDrag = { drag = it }, onRelease = { drag = null; SmartRemote.seekTo(it) },
                )
                Row(Modifier.fillMaxWidth()) {
                    Text(formatTime(drag ?: pos), style = T.small.copy(fontSize = 11.sp), color = c.muted, modifier = Modifier.weight(1f))
                    Text(if (np.duration > 0) formatTime(np.duration) else "live", style = T.small.copy(fontSize = 11.sp), color = c.muted)
                }
            }
            Cap(Modifier.size(48.dp), CircleShape, haptic = Haptic.TICK, description = "Forward 10 seconds", onClick = { SmartRemote.seekBy(10) }) {
                Text("+10", style = T.small, color = c.ink)
            }
        }
    }
}

/** Draggable position bar: a well with an accent fill and a metal thumb. Sends one seek on release. */
@Composable
private fun Scrubber(position: Double, duration: Double, enabled: Boolean, onDrag: (Double) -> Unit, onRelease: (Double) -> Unit) {
    val c = LocalMote.current
    val h = haptics()
    val drag by rememberUpdatedState(onDrag)
    val release by rememberUpdatedState(onRelease)
    val frac = if (duration > 0) (position / duration).toFloat().coerceIn(0f, 1f) else 0f
    BoxWithConstraints(
        Modifier.fillMaxWidth().height(32.dp).semantics {
            contentDescription = "Playback position"
            stateDescription = "${formatTime(position)} of ${formatTime(duration)}"
            if (enabled) {
                progressBarRangeInfo = ProgressBarRangeInfo(frac, 0f..1f)
                setProgress { v -> release(v.coerceIn(0f, 1f) * duration); true }
            }
        },
        contentAlignment = Alignment.CenterStart,
    ) {
        val thumb = 20.dp
        val widthPx = constraints.maxWidth.toFloat()
        Canvas(
            Modifier.fillMaxSize().pointerInput(enabled, duration, widthPx) {
                if (!enabled) return@pointerInput
                val inset = thumb.toPx() / 2
                fun at(x: Float) = ((x - inset) / (widthPx - 2 * inset)).coerceIn(0f, 1f) * duration
                awaitEachGesture {
                    val down = awaitFirstDown()
                    h(Haptic.TICK)
                    var v = at(down.position.x); drag(v)
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (!ch.pressed) break
                        v = at(ch.position.x); drag(v); ch.consume()
                    }
                    h(Haptic.CLICK)
                    release(v)
                }
            },
        ) {
            val inset = thumb.toPx() / 2
            val cy = size.height / 2
            drawLine(c.outline, Offset(inset, cy), Offset(size.width - inset, cy), 6.dp.toPx(), cap = StrokeCap.Round)
            drawLine(c.accent, Offset(inset, cy), Offset(inset + (size.width - 2 * inset) * frac, cy), 6.dp.toPx(), cap = StrokeCap.Round)
        }
        if (enabled) {
            Box(
                Modifier.offset { IntOffset(((widthPx - thumb.toPx()) * frac).roundToInt(), 0) }.size(thumb).metal(CircleShape),
            )
        }
    }
}
