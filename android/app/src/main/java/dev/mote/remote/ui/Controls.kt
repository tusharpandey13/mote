package dev.mote.remote.ui

import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mote.remote.ui.theme.LocalMote
import dev.mote.remote.ui.theme.MIcon
import dev.mote.remote.ui.theme.Wordmark
import dev.mote.remote.ui.theme.metal
import dev.mote.remote.ui.theme.raised
import dev.mote.remote.ui.theme.well
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** Engraving (light from the top): dark copy 1dp above, light copy 1dp below, fill on top. */
@Composable
fun EngravedText(text: String, size: TextUnit, tracking: TextUnit, fill: Color, modifier: Modifier = Modifier) {
    val c = LocalMote.current
    val style = TextStyle(fontFamily = Wordmark, fontWeight = FontWeight.Black, fontSize = size, letterSpacing = tracking)
    Box(modifier) {
        Text(text, style = style, color = c.engraveDark, modifier = Modifier.offset(y = (-1).dp))
        Text(text, style = style, color = c.engraveLight, modifier = Modifier.offset(y = 1.dp))
        Text(text, style = style, color = fill)
    }
}

/** Glass legend: ink in light; in dark an LED tint with a soft glow (blurred copy behind, API 31+). */
@Composable
fun GlassIcon(name: String, size: Dp) {
    val c = LocalMote.current
    Box(contentAlignment = Alignment.Center) {
        if (c.dark && Build.VERSION.SDK_INT >= 31) {
            MIcon(name, c.glassGlow.copy(alpha = 0.9f), size = size, modifier = Modifier.graphicsLayer {
                renderEffect = BlurEffect(6.dp.toPx(), 6.dp.toPx(), TileMode.Decal)
            })
        }
        MIcon(name, c.glassInk, size = size)
    }
}

/** The raised "Edit" cap (40dp tall, 48dp hit area): icon + 14sp label. */
@Composable
fun EditCap(label: String = "Edit", active: Boolean = false, onClick: () -> Unit) {
    val c = LocalMote.current
    Box(Modifier.heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
        Cap(Modifier.height(40.dp), RoundedCornerShape(13.dp), needsLink = false, haptic = Haptic.TICK, description = label,
            forcePressed = active, onClick = onClick) {
            Row(Modifier.padding(start = 10.dp, end = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                MIcon(if (active) "check" else "edit", if (active) c.accent else c.ink, size = 16.dp)
                Spacer(Modifier.width(6.dp))
                Text(label, style = T.label, color = if (active) c.accent else c.ink)
            }
        }
    }
}

/** Selection language: the selected segment is a raised cap, the others are flat; the track is a well. */
@Composable
fun <T> Segmented(options: List<T>, selected: T, label: (T) -> String, modifier: Modifier = Modifier, height: Dp = 46.dp, onSelect: (T) -> Unit) {
    val c = LocalMote.current
    Row(modifier.well(RoundedCornerShape(height / 2 + 1.dp)).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        options.forEach { o ->
            val on = o == selected
            Cap(
                Modifier.weight(1f).height(height - 8.dp), RoundedCornerShape((height - 8.dp) / 2 - 4.dp), needsLink = false,
                haptic = Haptic.TICK, flat = !on, description = label(o), onClick = { onSelect(o) },
            ) {
                Text(label(o), style = T.label.copy(fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium), color = if (on) c.ink else c.muted, maxLines = 1)
            }
        }
    }
}

/** On/off switch: a recessed track that lights with the accent; a plastic knob. */
@Composable
fun Switch(on: Boolean, description: String, onToggle: (Boolean) -> Unit) {
    val c = LocalMote.current
    val h = haptics()
    val x by animateFloatAsState(if (on) 1f else 0f, Motion.spatialFast(), label = "switch")
    Box(
        Modifier
            .size(52.dp, 32.dp)
            .semantics { role = Role.Switch; contentDescription = description; stateDescription = if (on) "On" else "Off" }
            .clickable { h(Haptic.TICK); onToggle(!on) }
            .well(CircleShape)
            .drawWithCache {
                val fill = c.accent
                onDrawBehind { if (x > 0f) drawRoundRect(fill.copy(alpha = x), cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2)) }
            },
    ) {
        Box(Modifier.offset { IntOffset((4.dp.toPx() + x * 20.dp.toPx()).roundToInt(), 4.dp.roundToPx()) }.size(24.dp).raised(CircleShape))
    }
}

/** A plastic panel holding settings rows, separated by hairlines. */
@Composable
fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().raised(RoundedCornerShape(20.dp)).padding(horizontal = 14.dp, vertical = 4.dp), content = content)
}

@Composable
fun Hairline() {
    val c = LocalMote.current
    Box(Modifier.fillMaxWidth().height(1.dp).drawWithCache { onDrawBehind { drawRect(c.outline) } })
}

@Composable
fun SettingRow(title: String, subtitle: String? = null, trailing: @Composable () -> Unit) {
    val c = LocalMote.current
    Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = T.label.copy(fontSize = 15.sp), color = c.ink)
            if (subtitle != null) Text(subtitle, style = T.body.copy(fontSize = 13.sp, lineHeight = 18.sp), color = c.muted)
        }
        Spacer(Modifier.width(12.dp))
        trailing()
    }
}

/**
 * Horizontal machined slider (settings): a well with ticks, an accent fill up to the thumb and a
 * brushed-metal thumb. [steps] discrete values between [min] and [max].
 */
@Composable
fun MetalSlider(value: Float, min: Float, max: Float, steps: Int, description: String, onChange: (Float) -> Unit) {
    val c = LocalMote.current
    val h = haptics()
    val change by rememberUpdatedState(onChange)
    val cur by rememberUpdatedState(value)
    val thumbW = 48.dp
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(40.dp)
            .semantics { contentDescription = description; stateDescription = "%.2fx".format(value) }
            .well(RoundedCornerShape(20.dp)),
    ) {
        val density = LocalDensity.current
        val trackPx = with(density) { (maxWidth - thumbW - 8.dp).toPx() }
        val frac = ((value - min) / (max - min)).coerceIn(0f, 1f)
        fun valueAt(x: Float): Float {
            val f = ((x - with(density) { (thumbW / 2 + 4.dp).toPx() }) / trackPx).coerceIn(0f, 1f)
            val stepped = (f * (steps - 1)).roundToInt() / (steps - 1f)
            return min + stepped * (max - min)
        }
        Canvas(Modifier.fillMaxSize().pointerInput(trackPx) {
            awaitEachGesture {
                val down = awaitFirstDown()
                fun upd(x: Float) { val v = valueAt(x); if (v != cur) { h(Haptic.DETENT); change(v) } }
                upd(down.position.x)
                while (true) {
                    val ev = awaitPointerEvent()
                    val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                    if (!ch.pressed) break
                    upd(ch.position.x); ch.consume()
                }
            }
        }) {
            val x0 = (thumbW / 2 + 4.dp).toPx()
            val cy = size.height / 2
            for (i in 0 until steps) {
                val x = x0 + trackPx * i / (steps - 1)
                drawLine(c.subtle.copy(alpha = 0.6f), Offset(x, 6.dp.toPx()), Offset(x, 11.dp.toPx()), 1.dp.toPx())
                drawLine(c.subtle.copy(alpha = 0.6f), Offset(x, size.height - 11.dp.toPx()), Offset(x, size.height - 6.dp.toPx()), 1.dp.toPx())
            }
            drawLine(c.accent, Offset(6.dp.toPx(), cy), Offset(x0 + trackPx * frac, cy), 4.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
        }
        Box(
            Modifier
                .offset { IntOffset((4.dp.toPx() + trackPx * frac).roundToInt(), 4.dp.roundToPx()) }
                .size(thumbW, 32.dp)
                .metal(RoundedCornerShape(10.dp)),
        )
    }
}

/**
 * Machined vertical slider (rev 4): a slot with ticks every 14dp on both edges, a centre detent
 * line and tappable end markers that send one [onNotch] step. Holding and dragging the
 * spring-centred metal thumb repeats [onNotch] at a rate set by displacement (6dp deadzone,
 * 4 bands, a detent haptic on band change). An engraved [label] sits under the slot.
 */
@Composable
fun MachinedSlider(
    label: String,
    upEnd: @Composable () -> Unit,
    downEnd: @Composable () -> Unit,
    upDescription: String,
    downDescription: String,
    thumbDescription: String,
    rates: IntArray,
    slotHeight: Dp,
    thumb: @Composable () -> Unit,
    onNotch: (up: Boolean) -> Unit,
) {
    val c = LocalMote.current
    val h = haptics()
    val linked by rememberUpdatedState(LocalLinked.current)
    val notch by rememberUpdatedState(onNotch)
    val reduced = reducedMotion()
    val scope = rememberCoroutineScope()
    val offset = remember { Animatable(0f) }
    var dragging by remember { mutableStateOf(false) }
    val endH = 40.dp
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(56.dp, slotHeight)
                .well(RoundedCornerShape(28.dp))
                .drawWithCache {
                    val tickTop = endH.toPx(); val tickBot = size.height - endH.toPx()
                    val pitch = 14.dp.toPx(); val w = 6.dp.toPx(); val inset = 7.dp.toPx()
                    val tick = c.subtle.copy(alpha = 0.55f)
                    val tickPx = 1.5.dp.toPx()
                    val centre = c.subtle.copy(alpha = 0.9f)
                    onDrawBehind {
                        var y = tickTop
                        while (y <= tickBot) {
                            drawLine(tick, Offset(inset, y), Offset(inset + w, y), tickPx)
                            drawLine(tick, Offset(size.width - inset - w, y), Offset(size.width - inset, y), tickPx)
                            y += pitch
                        }
                        val cy = size.height / 2
                        drawLine(centre, Offset(inset - 2.dp.toPx(), cy), Offset(size.width - inset + 2.dp.toPx(), cy), 2.dp.toPx())
                    }
                },
        ) {
            Column(Modifier.fillMaxSize()) {
                EndZone(endH, upDescription) { if (linked) { h(Haptic.TICK); notch(true) } else h(Haptic.REJECT) }
                    .let { m -> Box(m, contentAlignment = Alignment.Center) { upEnd() } }
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    val density = LocalDensity.current
                    val travelPx = with(density) { ((maxHeight - 60.dp) / 2 + 8.dp).toPx().coerceAtLeast(1f) }
                    val deadPx = with(density) { 6.dp.toPx() }
                    Box(
                        Modifier
                            .fillMaxSize()
                            .semantics { contentDescription = thumbDescription }
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
                                            if (nb != band) { band = nb; h(Haptic.DETENT) }
                                            if (band == 0) { delay(16); continue }
                                            notch(disp < 0)
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
                                    scope.launch { if (reduced) offset.snapTo(0f) else offset.animateTo(0f, Motion.spatialFast()) }
                                }
                            },
                    )
                    Box(
                        Modifier
                            .offset { IntOffset(0, offset.value.roundToInt()) }
                            .size(36.dp, 60.dp)
                            .metal(RoundedCornerShape(12.dp), dragging),
                        contentAlignment = Alignment.Center,
                    ) { thumb() }
                }
                EndZone(endH, downDescription) { if (linked) { h(Haptic.TICK); notch(false) } else h(Haptic.REJECT) }
                    .let { m -> Box(m, contentAlignment = Alignment.Center) { downEnd() } }
            }
        }
        Spacer(Modifier.height(8.dp))
        EngravedText(label, 9.sp, 2.sp, c.engraveLabel)
    }
}

@Composable
private fun EndZone(height: Dp, description: String, onTap: () -> Unit): Modifier {
    val tap by rememberUpdatedState(onTap)
    return Modifier.fillMaxWidth().height(height).semantics { role = Role.Button; contentDescription = description }.pointerInput(Unit) {
        awaitEachGesture { awaitFirstDown(); if (waitForUpOrCancellation() != null) tap() }
    }
}

/** Grip lines for the scroll thumb: three engraved bars. */
@Composable
fun GripLines() {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(3) {
            Box(Modifier.size(18.dp, 3.dp).drawWithCache {
                onDrawBehind {
                    drawRect(Color.Black.copy(alpha = 0.35f), size = size.copy(height = 2.dp.toPx()))
                    drawRect(Color.White.copy(alpha = 0.6f), topLeft = Offset(0f, 2.dp.toPx()), size = size.copy(height = 1.dp.toPx()))
                }
            })
        }
    }
}

/** Dashed empty slot ("+ Add favourite"). */
fun Modifier.dashedSlot(color: Color, radius: Dp): Modifier = this.drawWithCache {
    val stroke = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx())))
    val r = androidx.compose.ui.geometry.CornerRadius(radius.toPx())
    onDrawBehind { drawRoundRect(color, cornerRadius = r, style = stroke) }
}
