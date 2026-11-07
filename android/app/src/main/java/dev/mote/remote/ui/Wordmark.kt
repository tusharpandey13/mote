package dev.mote.remote.ui

import android.graphics.BlurMaskFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mote.remote.ui.theme.LocalMote
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

private val LedEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

/** tokens.json components.wordmark.lit, per theme. */
private class Bulb(dark: Boolean) {
    val spill = if (dark) listOf(0f to Color(0x426CC4FF), 0.55f to Color(0x176CC4FF), 1f to Color(0x006CC4FF))
    else listOf(0f to Color(0x3840A0FF), 0.55f to Color(0x1440A0FF), 1f to Color(0x0040A0FF))
    /** (CSS blur radius dp, colour), outermost last. */
    val glows = if (dark) listOf(6f to Color(0xF28CCDFF), 16f to Color(0xA66CC4FF), 34f to Color(0x616CC4FF))
    else listOf(1f to Color(0x8C143C6E), 7f to Color(0xCC40A0FF), 16f to Color(0x8040A0FF), 32f to Color(0x4740A0FF))
    val bloom = if (dark) Color(0xFFFFF0DC) else Color(0xE6FFECD2)
    val core = if (dark) listOf(0f to 0xFF9ED6FF, 0.4f to 0xFFE6F5FF, 0.5f to 0xFFFFF3E2, 0.6f to 0xFFE6F5FF, 1f to 0xFF7CC6FF)
    else listOf(0f to 0xFF3D9BF0, 0.4f to 0xFFBFE3FF, 0.5f to 0xFFFFF1DC, 0.6f to 0xFFBFE3FF, 1f to 0xFF2F8BE0)
    val pulse = if (dark) Color(0xFF35556F) else Color(0xFF8FB3D6)
}

/** BlurMaskFilter radius for a CSS blur [blurPx] (sigma = blur / 2; Skia sigma = 0.57735 r + 0.5). */
private fun cssBlur(blurPx: Float) = ((blurPx / 2f - 0.5f) / 0.57735f).coerceAtLeast(0.5f)

/**
 * "MOTE" (Unbounded Black 19sp, tracking 2.5): an unlit grey deboss when disconnected, pulsing towards
 * dim blue while connecting, and a lightbulb when connected (rev 7 #70): light spill on the plate,
 * outer glow with falloff, a warm inner bloom, a warm-filament-to-LED-blue core and a glass specular
 * streak, with the seeded micro-flicker of design/mote-v1/flicker.js (off under reduced motion).
 * Paints and shaders are built in drawWithCache; per frame only alphas change.
 */
@Composable
fun WordmarkText() {
    val c = LocalMote.current
    val ctx = LocalContext.current
    val density = LocalDensity.current
    val state = linkLight()
    val reduced = reducedMotion()
    val lit = animateFloatAsState(if (state == LinkLight.LIT) 1f else 0f, tween(400, easing = LedEasing), label = "led")
    val pulse = if (state == LinkLight.CONNECTING && !reduced) {
        rememberInfiniteTransition(label = "ledPulse").animateFloat(
            0f, 1f, infiniteRepeatable(tween(1600, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "p",
        )
    } else remember(state) { mutableStateOf(if (state == LinkLight.CONNECTING) 0.5f else 0f) }
    val lum = remember { mutableFloatStateOf(1f) }
    LaunchedEffect(state == LinkLight.LIT, reduced) {
        lum.floatValue = 1f
        if (state != LinkLight.LIT || reduced) return@LaunchedEffect
        val f = Flicker()
        while (true) {
            val e = f.next()
            delay(e.waitMs)
            val t0 = withFrameMillis { it }
            var t = 0L
            while (t < e.lengthMs) {
                lum.floatValue = e.lum(t.toDouble()).toFloat()
                t = withFrameMillis { it } - t0
            }
            lum.floatValue = 1f
        }
    }
    val base = remember(density) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = androidx.core.content.res.ResourcesCompat.getFont(ctx, dev.mote.remote.R.font.unbounded_black)
            textSize = with(density) { 19.sp.toPx() }
            letterSpacing = 2.5f / 19f
        }
    }
    val text = "MOTE"
    val fm = base.fontMetrics
    val wPx = base.measureText(text); val hPx = fm.descent - fm.ascent
    Box(
        Modifier
            .size(with(density) { wPx.toDp() }, with(density) { hPx.toDp() })
            .semantics { contentDescription = "Mote, " + state.name.lowercase() }
            .drawWithCache {
                val b = Bulb(c.dark)
                val y = -fm.ascent
                val one = 1.dp.toPx()
                val px1 = one
                val glows = b.glows.map { (blur, col) ->
                    Paint(base).apply {
                        maskFilter = BlurMaskFilter(cssBlur(blur * px1), BlurMaskFilter.Blur.NORMAL)
                        color = col.toArgb()
                    } to col.alpha
                }
                val bloom = Paint(base).apply {
                    maskFilter = BlurMaskFilter(cssBlur(2 * px1), BlurMaskFilter.Blur.NORMAL); color = b.bloom.toArgb()
                }
                val bloomA = b.bloom.alpha
                val core = Paint(base).apply {
                    shader = LinearGradient(0f, 0f, 0f, size.height, b.core.map { it.second.toInt() }.toIntArray(),
                        b.core.map { it.first }.toFloatArray(), Shader.TileMode.CLAMP)
                }
                // CSS linear-gradient(165deg): the line runs through the centre along (sin, -cos) of the angle
                val th = Math.toRadians(165.0)
                val dx = sin(th).toFloat(); val dy = -cos(th).toFloat()
                val half = (abs(size.width * dx) + abs(size.height * dy)) / 2
                val cx = size.width / 2; val cy = size.height / 2
                val spec = Paint(base).apply {
                    shader = LinearGradient(cx - dx * half, cy - dy * half, cx + dx * half, cy + dy * half,
                        intArrayOf(0x00FFFFFF, 0xB3FFFFFF.toInt(), 0x00FFFFFF), floatArrayOf(0.32f, 0.44f, 0.54f), Shader.TileMode.CLAMP)
                }
                // spill: radial (closest-side) over the mark inflated by 34dp x 22dp, squashed into an ellipse
                val rx = size.width / 2 + 34.dp.toPx(); val ry = size.height / 2 + 22.dp.toPx()
                val spill = Brush.radialGradient(*b.spill.toTypedArray(), center = Offset.Zero, radius = rx)
                val flat = Paint(base)
                onDrawBehind {
                    val l = lit.value
                    val p = pulse.value
                    val lm = lum.floatValue
                    if (l > 0f) translate(cx, cy) {
                        scale(1f, ry / rx, Offset.Zero) { drawCircle(spill, rx, Offset.Zero, alpha = l * lm) }
                    }
                    drawIntoCanvas { cv ->
                        val nc = cv.nativeCanvas
                        if (l < 1f) {
                            // unlit deboss: dark edge above, light edge below, then the (pulsing) fill
                            flat.color = c.engraveDark.copy(alpha = c.engraveDark.alpha * (1 - l)).toArgb(); nc.drawText(text, 0f, y - one, flat)
                            flat.color = c.engraveLight.copy(alpha = c.engraveLight.alpha * (1 - l)).toArgb(); nc.drawText(text, 0f, y + one, flat)
                            val fill = lerp(c.engrave, b.pulse, p)
                            flat.color = fill.copy(alpha = 1 - l).toArgb(); nc.drawText(text, 0f, y, flat)
                        }
                        if (l > 0f) {
                            for (i in glows.indices.reversed()) {
                                val (g, a) = glows[i]
                                g.alpha = (a * l * lm * 255).toInt(); nc.drawText(text, 0f, y, g)
                            }
                            bloom.alpha = (bloomA * l * lm * 255).toInt(); nc.drawText(text, 0f, y, bloom)
                            core.alpha = (l * Flicker.coreAlpha(lm) * 255).toInt(); nc.drawText(text, 0f, y, core)
                            spec.alpha = (l * 255).toInt(); nc.drawText(text, 0f, y, spec)
                        }
                    }
                }
            },
    )
}
