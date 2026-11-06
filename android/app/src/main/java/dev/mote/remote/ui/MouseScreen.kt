package dev.mote.remote.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.mote.remote.hid.Hid
import dev.mote.remote.net.Remote
import dev.mote.remote.ui.theme.LocalMote
import dev.mote.remote.ui.theme.Tone
import dev.mote.remote.ui.theme.well
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.pow

/**
 * Touchpad: 1-finger move, tap = click, 2-finger tap = right click, 2-finger drag = scroll.
 * L / R buttons and a drag-lock toggle (holds the left button) sit underneath.
 */
@Composable
fun MouseScreen(banner: Boolean, onBannerDone: () -> Unit) {
    val c = LocalMote.current
    var dragLock by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { if (dragLock) Remote.setButton(Hid.BUTTON_LEFT, false) } }
    Column(Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp)) {
        if (banner) {
            Row(
                Modifier.fillMaxWidth().well(RoundedCornerShape(14.dp)).padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Turn on drag lock and move to select; turn it off to capture.", style = T.small, color = c.ink, modifier = Modifier.weight(1f))
                Cap(Modifier.height(36.dp).width(56.dp), RoundedCornerShape(10.dp), haptic = Haptic.TICK, description = "Escape",
                    onClick = { Remote.tap("esc"); onBannerDone() }) { Text("esc", style = T.keyS, color = c.muted) }
                Spacer(Modifier.width(6.dp))
                Cap(Modifier.height(36.dp).width(64.dp), RoundedCornerShape(10.dp), needsLink = false, haptic = Haptic.TICK,
                    description = "Done", onClick = onBannerDone) { Text("Done", style = T.keyS, color = c.accent) }
            }
            Spacer(Modifier.height(8.dp))
        }
        Touchpad(Modifier.weight(1f).fillMaxWidth(), dragLock)
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth().height(64.dp).well(RoundedCornerShape(20.dp)).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Cap(Modifier.weight(1f).fillMaxHeight(), RoundedCornerShape(16.dp), description = "Left button",
                onDown = { Remote.setButton(Hid.BUTTON_LEFT, true) }, onClick = { if (!dragLock) Remote.setButton(Hid.BUTTON_LEFT, false) }) {
                Text("L", style = T.label, color = c.muted)
            }
            Cap(Modifier.width(96.dp).fillMaxHeight(), RoundedCornerShape(16.dp), haptic = Haptic.HEAVY, forcePressed = dragLock,
                tone = Tone.NORMAL, description = if (dragLock) "Drag lock on" else "Drag lock off",
                onClick = { dragLock = !dragLock; Remote.setButton(Hid.BUTTON_LEFT, dragLock) }) {
                Text("Drag lock", style = T.small, color = if (dragLock) c.accent else c.muted)
            }
            Cap(Modifier.weight(1f).fillMaxHeight(), RoundedCornerShape(16.dp), description = "Right button",
                onDown = { Remote.setButton(Hid.BUTTON_RIGHT, true) }, onClick = { Remote.setButton(Hid.BUTTON_RIGHT, false) }) {
                Text("R", style = T.label, color = c.muted)
            }
        }
    }
}

@Composable
private fun Touchpad(modifier: Modifier, dragLock: Boolean) {
    val c = LocalMote.current
    val h = haptics()
    val linked by rememberUpdatedState(LocalLinked.current)
    val density = LocalDensity.current.density
    val scope = rememberCoroutineScope()
    var glow by remember { mutableStateOf<Offset?>(null) }
    val glowAlpha = remember { Animatable(0f) }
    val shape = RoundedCornerShape(28.dp)
    Box(
        modifier
            .well(shape)
            .then(if (dragLock) Modifier.border(2.dp, c.accent, shape) else Modifier)
            .semantics { contentDescription = "Touchpad" }
            .pointerInput(Unit) {
                val tapSlop = 8 * density
                val scrollStep = 20 * density
                awaitPointerEventScope {
                    while (true) {
                        // one gesture: from first finger down until all fingers are up
                        var ev = awaitPointerEvent()
                        val first = ev.changes.firstOrNull { it.pressed } ?: continue
                        if (!linked) { h(Haptic.REJECT); while (ev.changes.any { it.pressed }) ev = awaitPointerEvent(); continue }
                        val t0 = first.uptimeMillis
                        val start = HashMap<PointerId, Offset>()
                        var maxFingers = 0
                        var travel = 0f
                        var scrollAccY = 0f
                        var scrollAccX = 0f
                        var scrolledPx = 0f
                        glow = first.position
                        scope.launch { glowAlpha.snapTo(1f); glowAlpha.animateTo(0f, tween(160)) }
                        while (true) {
                            val down = ev.changes.filter { it.pressed }
                            down.forEach { start.putIfAbsent(it.id, it.position) }
                            maxFingers = maxOf(maxFingers, down.size)
                            if (down.isEmpty()) break
                            if (down.size == 1) {
                                val ch = down[0]
                                val d = ch.position - ch.previousPosition
                                val dt = (ch.uptimeMillis - ch.previousUptimeMillis).coerceAtLeast(1)
                                val dpx = d.x / density; val dpy = d.y / density
                                val speed = hypot(dpx, dpy) / dt // dp per ms
                                // gain 1.8x, quadratic above 600 dp/s
                                val gain = 1.8f * if (speed > 0.6f) (speed / 0.6f).pow(2).coerceAtMost(4f) else 1f
                                if (maxFingers == 1) Remote.move(dpx * gain, dpy * gain)
                                travel += hypot(d.x, d.y)
                            } else {
                                val avg = down.fold(Offset.Zero) { a, ch -> a + (ch.position - ch.previousPosition) } / down.size.toFloat()
                                travel += hypot(avg.x, avg.y)
                                scrollAccY += avg.y; scrollAccX += avg.x
                                // fingers up = wheel positive; the host applies natural scrolling
                                val wy = (-scrollAccY / scrollStep).toInt()
                                val wx = (scrollAccX / scrollStep).toInt()
                                if (wy != 0 || wx != 0) {
                                    Remote.scroll(wy, wx)
                                    scrollAccY += wy * scrollStep; scrollAccX -= wx * scrollStep
                                    scrolledPx += abs(wy * scrollStep) + abs(wx * scrollStep)
                                    if (scrolledPx >= 48 * density) { h(Haptic.TICK); scrolledPx = 0f }
                                }
                            }
                            ev.changes.forEach { it.consume() }
                            ev = awaitPointerEvent()
                        }
                        val dur = ev.changes.maxOf { it.uptimeMillis } - t0
                        if (travel < tapSlop && dur < 250) {
                            if (maxFingers >= 2) { h(Haptic.HEAVY); Remote.click(Hid.BUTTON_RIGHT) }
                            else if (!dragLock) { h(Haptic.CLICK); Remote.click(Hid.BUTTON_LEFT) }
                        }
                    }
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val pitch = 24.dp.toPx()
            var y = pitch
            while (y < size.height) {
                var x = pitch
                while (x < size.width) { drawCircle(c.dot, 1.dp.toPx(), Offset(x, y)); x += pitch }
                y += pitch
            }
            glow?.let { drawCircle(c.accent.copy(alpha = 0.25f * glowAlpha.value), 28.dp.toPx(), it) }
        }
        if (!LocalLinked.current) Text("Not connected", style = T.title, color = c.subtle, modifier = Modifier.align(Alignment.Center))
    }
}
