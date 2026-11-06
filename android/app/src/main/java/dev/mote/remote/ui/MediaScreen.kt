package dev.mote.remote.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.mote.remote.data.Macro
import dev.mote.remote.hid.Consumer
import dev.mote.remote.net.Remote
import dev.mote.remote.ui.theme.LocalMote
import dev.mote.remote.ui.theme.MIcon
import dev.mote.remote.ui.theme.Tone
import dev.mote.remote.ui.theme.raised
import dev.mote.remote.ui.theme.well
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt

@Composable
fun MediaScreen(favourites: List<Macro>, run: (Macro) -> Unit, onEditFavourites: () -> Unit) {
    val c = LocalMote.current
    BoxWithConstraints(Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp)) {
        // everything except the navigation row has a fixed height; the D-pad absorbs the rest
        val dpad = (maxHeight - 420.dp).coerceIn(150.dp, 208.dp)
        Column(Modifier.fillMaxSize()) {
            SectionHead("★ Favourites") {
                Text("EDIT", style = T.caps, color = c.subtle, modifier = Modifier.clickable(onClick = onEditFavourites).padding(4.dp))
            }
            Spacer(Modifier.height(6.dp))
            FavouriteStrip(favourites.take(4), run)
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                Box(Modifier.width(56.dp).height(dpad), contentAlignment = Alignment.BottomStart) {
                    Cap(Modifier.size(52.dp, 40.dp), RoundedCornerShape(13.dp), haptic = Haptic.TICK, description = "Escape", onDown = { Remote.tap("esc") }) {
                        Text("esc", style = T.keyS, color = c.muted)
                    }
                }
                DPad(dpad)
                ScrollLever(Modifier.width(56.dp).height(dpad))
            }
            Spacer(Modifier.weight(1f))
            Pair(Modifier.height(60.dp)) {
                Cap(Modifier.weight(1f).fillMaxHeight(), RoundedCornerShape(14.dp), haptic = Haptic.TICK, description = "Brightness down",
                    repeat = Repeat(), onDown = { Remote.consumer(Consumer.BRIGHTNESS_DOWN) }) { MIcon("sunDown", c.ink) }
                Cap(Modifier.weight(1f).fillMaxHeight(), RoundedCornerShape(14.dp), haptic = Haptic.TICK, description = "Brightness up",
                    repeat = Repeat(), onDown = { Remote.consumer(Consumer.BRIGHTNESS_UP) }) { MIcon("sunUp", c.ink) }
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Cap(Modifier.size(64.dp), RoundedCornerShape(20.dp), description = "Previous track", onClick = { Remote.consumer(Consumer.PREV) }) {
                    MIcon("prev", c.ink, size = 28.dp)
                }
                Box(Modifier.size(172.dp, 100.dp).well(RoundedCornerShape(30.dp)), contentAlignment = Alignment.Center) {
                    Cap(Modifier.size(156.dp, 84.dp), RoundedCornerShape(22.dp), tone = Tone.PRIMARY, description = "Play or pause",
                        onClick = { Remote.consumer(Consumer.PLAY_PAUSE) }) { MIcon("playPause", c.onPrim, size = 32.dp) }
                }
                Cap(Modifier.size(64.dp), RoundedCornerShape(20.dp), description = "Next track", onClick = { Remote.consumer(Consumer.NEXT) }) {
                    MIcon("next", c.ink, size = 28.dp)
                }
            }
            Spacer(Modifier.height(16.dp))
            Pair(Modifier.height(72.dp)) {
                Cap(Modifier.weight(1f).fillMaxHeight(), RoundedCornerShape(16.dp), haptic = Haptic.TICK, description = "Volume down",
                    repeat = Repeat(), onDown = { Remote.consumer(Consumer.VOL_DOWN) }) { MIcon("volDown", c.ink, size = 28.dp) }
                Cap(Modifier.width(76.dp).fillMaxHeight(), RoundedCornerShape(16.dp), description = "Mute",
                    onClick = { Remote.consumer(Consumer.MUTE) }) { MIcon("mute", c.ink) }
                Cap(Modifier.weight(1f).fillMaxHeight(), RoundedCornerShape(16.dp), haptic = Haptic.TICK, description = "Volume up",
                    repeat = Repeat(), onDown = { Remote.consumer(Consumer.VOL_UP) }) { MIcon("volUp", c.ink, size = 28.dp) }
            }
        }
    }
}

/** A recessed well holding a row of caps (button pair/group). */
@Composable
private fun Pair(modifier: Modifier, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(
        modifier.fillMaxWidth().well(RoundedCornerShape(20.dp)).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        content = content,
    )
}

@Composable
private fun FavouriteStrip(favs: List<Macro>, run: (Macro) -> Unit) {
    Row(Modifier.fillMaxWidth().height(64.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        var used = 0f
        favs.forEach { m ->
            val span = if (m.kind == Macro.Kind.SPACES) 2f else 1f
            if (used + span > 4f) return@forEach
            used += span
            if (m.kind == Macro.Kind.SPACES) SpacesTile(Modifier.weight(span).fillMaxHeight(), compact = true)
            else MacroTile(m, Modifier.weight(span).fillMaxHeight(), compact = true, onRun = run)
        }
        if (used < 4f) Spacer(Modifier.weight(4f - used))
    }
}

private enum class Arm(val key: String) { UP("up"), RIGHT("right"), DOWN("down"), LEFT("left"), CENTER("return") }

/** Circular D-pad: arms send arrows (host repeats while held), center tap = Return, hold = Space. */
@Composable
private fun DPad(size: androidx.compose.ui.unit.Dp) {
    val c = LocalMote.current
    val h = haptics()
    val linked = LocalLinked.current
    val scope = rememberCoroutineScope()
    var pressed by remember { mutableStateOf<Arm?>(null) }
    val linkedNow = androidx.compose.runtime.rememberUpdatedState(linked)
    Box(
        Modifier
            .size(size)
            .semantics { contentDescription = "Direction pad" }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val r = this.size.width / 2f
                    val dx = down.position.x - r
                    val dy = down.position.y - r
                    val k = r / 104f
                    val d = hypot(dx, dy)
                    if (d > r) return@awaitEachGesture
                    if (!linkedNow.value) { h(Haptic.REJECT); waitForUpOrCancellation(); return@awaitEachGesture }
                    val arm = if (d < 42 * k) Arm.CENTER else {
                        val a = Math.toDegrees(atan2(dy, dx).toDouble())
                        when {
                            a >= -135 && a < -45 -> Arm.UP
                            a >= -45 && a < 45 -> Arm.RIGHT
                            a >= 45 && a < 135 -> Arm.DOWN
                            else -> Arm.LEFT
                        }
                    }
                    pressed = arm
                    var job: Job? = null
                    var longFired = false
                    if (arm == Arm.CENTER) {
                        h(Haptic.CLICK)
                        job = scope.launch { delay(500); longFired = true; h(Haptic.HEAVY); Remote.tap("space") }
                    } else {
                        h(Haptic.TICK)
                        Remote.keyDown(arm.key) // macOS applies its own key repeat while held
                    }
                    val up = waitForUpOrCancellation()
                    job?.cancel()
                    if (arm == Arm.CENTER) { if (up != null && !longFired) Remote.tap("return") } else Remote.keyUp(arm.key)
                    pressed = null
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val k = this.size.width / 208f
            val ctr = Offset(this.size.width / 2, this.size.height / 2)
            drawCircle(c.well, 104 * k, ctr)
            drawCircle(Brush.verticalGradient(listOf(c.wellShade, c.well), startY = 0f, endY = 12 * k), 104 * k, ctr)
            drawCircle(Brush.verticalGradient(listOf(c.keyTop, c.keyBot)), 96 * k, ctr)
            drawArc(c.hi, 200f, 140f, false, Offset(ctr.x - 95 * k, ctr.y - 95 * k), Size(190 * k, 190 * k), style = Stroke(1.dp.toPx()))
            Arm.entries.filter { it != Arm.CENTER }.forEachIndexed { i, arm ->
                rotate(i * 90f, ctr) {
                    if (pressed == arm) {
                        drawArc(c.lo, 225f, 90f, true, Offset(ctr.x - 96 * k, ctr.y - 96 * k), Size(192 * k, 192 * k))
                    }
                    val tri = Path().apply {
                        moveTo(ctr.x, ctr.y - 77 * k); lineTo(ctr.x + 7 * k, ctr.y - 67.5f * k); lineTo(ctr.x - 7 * k, ctr.y - 67.5f * k); close()
                    }
                    drawPath(tri, if (pressed == arm) c.accent else c.muted)
                }
            }
            for (i in 0 until 4) rotate(45f + i * 90f, ctr) {
                drawLine(c.outline, Offset(ctr.x, ctr.y - 44 * k), Offset(ctr.x, ctr.y - 80 * k), 1.5f * k)
            }
            drawCircle(c.well, 42 * k, ctr)
            val cp = pressed == Arm.CENTER
            drawCircle(Brush.verticalGradient(if (cp) listOf(c.keyBot, c.keyTop) else listOf(c.keyTop, c.keyBot), startY = ctr.y - 35 * k, endY = ctr.y + 35 * k), 35 * k, ctr)
            if (!cp) drawArc(c.hi, 200f, 140f, false, Offset(ctr.x - 34 * k, ctr.y - 34 * k), Size(68 * k, 68 * k), style = Stroke(1.dp.toPx()))
        }
        Text("OK", style = T.label, color = if (pressed == Arm.CENTER) c.accent else c.ink)
    }
}

/**
 * Spring-loaded scroll lever: drag the thumb; speed is proportional to displacement after a 6dp
 * deadzone (4 bands, TICK on band change). Chevrons send one notch. Up = toward the top of the
 * document with macOS natural scrolling (the default).
 */
@Composable
private fun ScrollLever(modifier: Modifier) {
    val c = LocalMote.current
    val h = haptics()
    val linked by androidx.compose.runtime.rememberUpdatedState(LocalLinked.current)
    val scope = rememberCoroutineScope()
    val offset = remember { Animatable(0f) }
    var dragging by remember { mutableStateOf(false) }
    val rates = intArrayOf(0, 4, 8, 16, 30)
    Column(modifier.well(RoundedCornerShape(18.dp)).padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth().height(36.dp).semantics { contentDescription = "Scroll up one notch" }.pointerInput(Unit) {
            awaitEachGesture { awaitFirstDown(); if (waitForUpOrCancellation() != null) { if (linked) { h(Haptic.TICK); Remote.scroll(-1, 0) } else h(Haptic.REJECT) } }
        }, contentAlignment = Alignment.Center) { MIcon("chevUp", c.muted, size = 20.dp) }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val travelPx = with(androidx.compose.ui.platform.LocalDensity.current) { ((maxHeight - 52.dp) / 2).toPx().coerceAtLeast(1f) }
            val deadPx = with(androidx.compose.ui.platform.LocalDensity.current) { 6.dp.toPx() }
            Box(
                Modifier
                    .fillMaxSize()
                    .semantics { contentDescription = "Scroll lever" }
                    .pointerInput(travelPx) {
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            if (!linked) { h(Haptic.REJECT); waitForUpOrCancellation(); return@awaitEachGesture }
                            dragging = true
                            val loop = scope.launch {
                                var band = 0
                                while (isActive) {
                                    val disp = offset.value
                                    val mag = (abs(disp) - deadPx).coerceAtLeast(0f)
                                    val nb = if (mag <= 0f) 0 else (1 + (mag / (travelPx - deadPx) * 4).toInt()).coerceAtMost(4)
                                    if (nb != band) { band = nb; h(Haptic.TICK) }
                                    if (band == 0) { delay(16); continue }
                                    Remote.scroll(if (disp < 0) -1 else 1, 0)
                                    delay(1000L / rates[band])
                                }
                            }
                            val startY = down.position.y
                            while (true) {
                                val ev = awaitPointerEvent()
                                val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                if (!ch.pressed) break
                                scope.launch { offset.snapTo((ch.position.y - startY).coerceIn(-travelPx, travelPx)) }
                                ch.consume()
                            }
                            loop.cancel()
                            dragging = false
                            scope.launch { offset.animateTo(0f, Motion.spatialFast()) }
                        }
                    },
            )
            Column(
                Modifier
                    .offset { IntOffset(0, offset.value.roundToInt()) }
                    .size(44.dp, 52.dp)
                    .raised(RoundedCornerShape(12.dp), dragging),
                verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) { repeat(3) { Box(Modifier.size(18.dp, 2.dp).well(RoundedCornerShape(1.dp))) } }
        }
        Box(Modifier.fillMaxWidth().height(36.dp).semantics { contentDescription = "Scroll down one notch" }.pointerInput(Unit) {
            awaitEachGesture { awaitFirstDown(); if (waitForUpOrCancellation() != null) { if (linked) { h(Haptic.TICK); Remote.scroll(1, 0) } else h(Haptic.REJECT) } }
        }, contentAlignment = Alignment.Center) { MIcon("chevDown", c.muted, size = 20.dp) }
    }
}
