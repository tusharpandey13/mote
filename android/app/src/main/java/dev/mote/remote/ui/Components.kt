package dev.mote.remote.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mote.remote.hid.HidState
import dev.mote.remote.ui.theme.LocalMote
import dev.mote.remote.ui.theme.MIcon
import dev.mote.remote.ui.theme.Tone
import dev.mote.remote.ui.theme.Wordmark
import dev.mote.remote.ui.theme.raised
import dev.mote.remote.ui.theme.well
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Whether a host is connected; controls that need it are dimmed and reject taps otherwise. */
val LocalLinked = compositionLocalOf { false }

object T {
    val caps = TextStyle(fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.7.sp)
    val label = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold)
    val small = TextStyle(fontSize = 12.sp, lineHeight = 14.sp, fontWeight = FontWeight.SemiBold)
    val key = TextStyle(fontSize = 18.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium)
    val keyS = TextStyle(fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium)
    val title = TextStyle(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold)
    val body = TextStyle(fontSize = 15.sp, lineHeight = 22.sp)
}

/** Auto-repeat while held: 350 ms delay, 8/s, then 16/s after 1.2 s (design 5.5). */
data class Repeat(val delayMs: Long = 350, val slowMs: Long = 125, val fastMs: Long = 62, val fastAfterMs: Long = 1200)

/**
 * A raised cap that reacts on touch-down (haptic, pressed look, [onDown]) and commits [onClick]
 * on release inside. [onLongPress] fires after 450 ms and suppresses [onClick].
 */
@Composable
fun Cap(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(16.dp),
    tone: Tone = Tone.NORMAL,
    needsLink: Boolean = true,
    haptic: Haptic? = Haptic.CLICK,
    description: String? = null,
    forcePressed: Boolean = false,
    flat: Boolean = false,
    fill: Float = 0f,
    onDown: (() -> Unit)? = null,
    repeat: Repeat? = null,
    onLongPress: (() -> Unit)? = null,
    onRelease: (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val c = LocalMote.current
    val linked = LocalLinked.current
    val enabled = !needsLink || linked
    val h = haptics()
    val scope = rememberCoroutineScope()
    var pressed by remember { mutableStateOf(false) }
    val down by rememberUpdatedState(onDown)
    val click by rememberUpdatedState(onClick)
    val long by rememberUpdatedState(onLongPress)
    val release by rememberUpdatedState(onRelease)
    val en by rememberUpdatedState(enabled)
    val rep by rememberUpdatedState(repeat)
    val hap by rememberUpdatedState(haptic)
    val down0 = pressed || forcePressed
    val depth by animateFloatAsState(if (down0) 1f else 0f, Motion.press, label = "press")
    Box(
        modifier
            // input and semantics sit outside the press transform (scale 0.96), so the hit area stays full size
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown()
                    if (!en) { h(Haptic.REJECT); waitForUpOrCancellation(); return@awaitEachGesture }
                    pressed = true
                    hap?.let(h)
                    down?.invoke()
                    var longFired = false
                    var job: Job? = null
                    rep?.let { r ->
                        job = scope.launch {
                            delay(r.delayMs)
                            var t = 0L
                            while (true) {
                                down?.invoke(); h(Haptic.TICK)
                                val step = if (t > r.fastAfterMs) r.fastMs else r.slowMs
                                delay(step); t += step
                            }
                        }
                    }
                    if (long != null) job = scope.launch { delay(450); longFired = true; h(Haptic.HEAVY); long?.invoke() }
                    val up = waitForUpOrCancellation()
                    job?.cancel()
                    pressed = false
                    release?.invoke()
                    if (up != null && !longFired) click?.invoke()
                }
            }
            .semantics {
                role = Role.Button
                description?.let { contentDescription = it }
                onClick { (click ?: down)?.invoke(); true }
            }
            .then(if (flat && depth == 0f) Modifier.clip(shape) else Modifier.raised(shape, down0, tone, depth))
            .then(if (fill > 0f) Modifier.drawBehind { drawRect(Brush.verticalGradient(listOf(c.modTop, c.modBot)), alpha = fill) } else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.alpha(if (enabled) 1f else 0.38f), contentAlignment = Alignment.Center) { content() }
    }
}

/** Status LED. While searching it pulses (alpha 0.4-1) and emits an expanding ring. */
@Composable
fun Led(color: Color, pulse: Boolean) {
    val animate = pulse && !reducedMotion()
    var a = 1f
    var ring = -1f
    if (animate) {
        val t = rememberInfiniteTransition(label = "pulse")
        a = t.animateFloat(0.4f, 1f, infiniteRepeatable(tween(Motion.PULSE_MS), RepeatMode.Reverse), label = "a").value
        ring = t.animateFloat(0f, 1f, infiniteRepeatable(tween(Motion.PULSE_MS * 2), RepeatMode.Restart), label = "ring").value
    }
    Box(Modifier.size(8.dp), contentAlignment = Alignment.Center) {
        if (ring >= 0f) {
            Box(
                Modifier
                    .size(8.dp)
                    .drawBehind {
                        drawCircle(color.copy(alpha = 0.6f * (1f - ring)), radius = size.minDimension / 2 * (1f + 1.6f * ring), style = Stroke(1.5.dp.toPx()))
                    },
            )
        }
        Box(
            Modifier
                .size(8.dp)
                .alpha(a)
                .shadow(4.dp, CircleShape, ambientColor = color, spotColor = color)
                .background(color, CircleShape),
        )
    }
}

/** "MOTE" debossed into the chassis: dark edge above, light highlight below. */
@Composable
fun WordmarkText() {
    val c = LocalMote.current
    val style = TextStyle(fontFamily = Wordmark, fontWeight = FontWeight.Black, fontSize = 15.sp, letterSpacing = 3.sp)
    Box {
        Text("MOTE", style = style, color = if (c.dark) Color.Black.copy(alpha = 0.75f) else Color(0x5914202A), modifier = Modifier.offset(y = (-1).dp))
        Text("MOTE", style = style, color = c.hi, modifier = Modifier.offset(y = 1.dp))
        Text("MOTE", style = style, color = c.engrave)
    }
}

enum class Mode(val label: String, val icon: String) {
    MEDIA("Media", "modeMedia"), MOUSE("Mouse", "modeMouse"), KEYS("Keys", "modeKeys"), MACROS("Macros", "modeMacros")
}

/**
 * Bottom mode rail: recessed track; the selected segment is a raised cap that slides between
 * positions on the spatial-default spring.
 */
@Composable
fun ModeRail(mode: Mode, onSelect: (Mode) -> Unit) {
    val c = LocalMote.current
    val h = haptics()
    Box(
        Modifier
            .fillMaxWidth()
            .background(c.surface1)
            .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 6.dp),
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth().height(60.dp).well(RoundedCornerShape(20.dp)).padding(4.dp)) {
            val gap = 4.dp
            val segW = (maxWidth - gap * 3) / 4
            val x by animateDpAsState((segW + gap) * mode.ordinal, Motion.spatialDefault(), label = "rail")
            Box(Modifier.offset { IntOffset(x.roundToPx(), 0) }.width(segW).fillMaxHeight().raised(RoundedCornerShape(16.dp)))
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                Mode.entries.forEach { m ->
                    val on = m == mode
                    val tint by animateColorAsState(if (on) c.accent else c.muted, Motion.effectMed(), label = "tint")
                    Column(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(16.dp))
                            .clickable(onClickLabel = "Switch to ${m.label}") { if (!on) { h(Haptic.HEAVY); onSelect(m) } },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        MIcon(m.icon, tint, size = 22.dp)
                        Text(m.label, style = T.small, color = if (on) c.ink else c.muted)
                    }
                }
            }
        }
    }
}

@Composable
fun SectionHead(text: String, modifier: Modifier = Modifier, trailing: @Composable () -> Unit = {}) {
    val c = LocalMote.current
    Row(modifier.fillMaxWidth().height(24.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text.uppercase(), style = T.caps, color = c.subtle, modifier = Modifier.weight(1f))
        trailing()
    }
}
