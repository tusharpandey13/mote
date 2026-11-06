package dev.mote.remote.ui.theme

import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.CacheDrawScope
import androidx.compose.ui.draw.DrawResult
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import kotlin.random.Random

/*
 * Revision 4 material system (docs/design/design-language.md, "One material per control family").
 * Everything is drawn with drawWithCache: brushes, paths and paints are built once per size/theme,
 * the per-frame draw only replays them. Textures are three tiny bitmaps shared by the whole app.
 */

/** Shared tiled textures, generated once per process (no image assets, no per-frame allocation). */
object Textures {
    /** 128px tileable fractal noise, grey around 50% so Overlay both lightens and darkens; alpha 0.9. */
    val noise: ImageBitmap by lazy {
        val n = 128
        val rnd = Random(7)
        // tileable value noise at period 16 (soft mottling) mixed with per-pixel grain
        val g = 16
        val lattice = Array(g) { FloatArray(g) { rnd.nextFloat() } }
        fun smooth(t: Float) = t * t * (3 - 2 * t)
        val px = IntArray(n * n)
        for (y in 0 until n) for (x in 0 until n) {
            val fx = x * g / n.toFloat(); val fy = y * g / n.toFloat()
            val x0 = fx.toInt(); val y0 = fy.toInt()
            val tx = smooth(fx - x0); val ty = smooth(fy - y0)
            val a = lattice[y0 % g][x0 % g]; val b = lattice[y0 % g][(x0 + 1) % g]
            val c = lattice[(y0 + 1) % g][x0 % g]; val d = lattice[(y0 + 1) % g][(x0 + 1) % g]
            val v = (a + (b - a) * tx) * (1 - ty) + (c + (d - c) * tx) * ty
            val grey = (0.5f + (rnd.nextFloat() - 0.5f) * 0.75f + (v - 0.5f) * 0.35f).coerceIn(0f, 1f)
            val l = (grey * 255).toInt()
            px[y * n + x] = (230 shl 24) or (l shl 16) or (l shl 8) or l
        }
        android.graphics.Bitmap.createBitmap(px, n, n, android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap()
    }

    /** 3x1: one opaque white column, tinted per theme; the 1px/3px brushed hairline on the backplate. */
    val hairline: ImageBitmap by lazy {
        android.graphics.Bitmap.createBitmap(intArrayOf(-1, 0, 0), 3, 1, android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap()
    }

    /** 2x1: white@10% / black@6%, the 1px/2px brushed lines on metal thumbs. */
    val brushed: ImageBitmap by lazy {
        android.graphics.Bitmap.createBitmap(intArrayOf(0x1AFFFFFF, 0x0F000000), 2, 1, android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap()
    }

    /** Repeating brush; [scale] enlarges the tile (noise grain reads at roughly CSS-pixel size). */
    fun brush(bmp: ImageBitmap, scale: Float = 1f): ShaderBrush {
        val s = ImageShader(bmp, TileMode.Repeated, TileMode.Repeated)
        if (scale != 1f) s.setLocalMatrix(android.graphics.Matrix().apply { setScale(scale, scale) })
        return ShaderBrush(s)
    }
}

// ---------------------------------------------------------------------------------------------
// drawing helpers

/** Outline of [shape] at the current size as a Path. */
fun CacheDrawScope.outlinePath(shape: Shape, inset: Float = 0f): Path {
    val o = shape.createOutline(Size(size.width - 2 * inset, size.height - 2 * inset), layoutDirection, this)
    return Path().apply {
        when (o) {
            is Outline.Rectangle -> addRect(o.rect)
            is Outline.Rounded -> addRoundRect(o.roundRect)
            is Outline.Generic -> addPath(o.path)
        }
        if (inset != 0f) translate(Offset(inset, inset))
    }
}

/** A CSS-like drop shadow (offset + blur) drawn with a framework shadow layer; colour must be translucent. */
class SoftShadow(color: Color, blurPx: Float, dx: Float = 0f, dy: Float) {
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        this.color = android.graphics.Color.TRANSPARENT
        // CSS blur is ~2 sigma; Android's radius is ~1.7 sigma
        setShadowLayer((blurPx * 0.85f).coerceAtLeast(0.5f), dx, dy, color.copy(alpha = color.alpha.coerceAtMost(0.99f)).toArgb())
    }
}

fun DrawScope.drawShadow(path: Path, s: SoftShadow, alpha: Float = 1f) {
    if (alpha <= 0.01f) return
    s.paint.alpha = 255 // shadow alpha comes from the shadow colour
    drawIntoCanvas { cv ->
        if (alpha < 0.99f) cv.nativeCanvas.saveLayerAlpha(null, (alpha * 255).toInt())
        cv.nativeCanvas.drawPath(path.asAndroidPath(), s.paint)
        if (alpha < 0.99f) cv.nativeCanvas.restore()
    }
}

/** The ring outside [inner] (even-odd), used to cast an inner shadow into the shape. */
fun CacheDrawScope.frameAround(inner: Path, pad: Float): Path = Path().apply {
    fillType = PathFillType.EvenOdd
    addRect(Rect(-pad, -pad, size.width + pad, size.height + pad))
    addPath(inner)
}

/** Inner shadow: clip to [shape], then draw the frame's shadow falling inward. */
fun DrawScope.drawInnerShadow(shape: Path, frame: Path, s: SoftShadow, alpha: Float = 1f) {
    clipPath(shape) { drawShadow(frame, s, alpha) }
}

/** The 1px crescent along the top (dy > 0) or bottom (dy < 0) inner edge of [p]. */
fun crescent(p: Path, dy: Float): Path = Path().apply {
    val moved = Path().apply { addPath(p, Offset(0f, dy)) }
    op(p, moved, PathOperation.Difference)
}

/** The noise texture blended into a material at [alpha] (design: soft-light ~12%; Overlay is API 28-safe). */
fun DrawScope.drawGrain(brush: Brush, alpha: Float) {
    drawRect(brush, alpha = alpha, blendMode = BlendMode.Overlay)
}

private fun noiseScale(density: Float) = (density / 2f).coerceAtLeast(1f)

// ---------------------------------------------------------------------------------------------
// materials

enum class Tone { NORMAL, PRIMARY, MODIFIER, DANGER, GLASS }

/**
 * Plastic cap (raised): vertical gradient with grain, inner top highlight, bottom shade, a 1dp edge
 * and two soft shadows. [Tone.GLASS] switches to the acrylic transport material. Pressed inverts the
 * gradient, swaps the shadows for an inner shadow and sinks 1dp (depth is spring-animated by callers).
 */
fun Modifier.raised(
    shape: Shape,
    pressed: Boolean = false,
    tone: Tone = Tone.NORMAL,
    depth: Float = if (pressed) 1f else 0f,
): Modifier = composed {
    val c = LocalMote.current
    val (top, bot, edge) = when (tone) {
        Tone.NORMAL -> Triple(c.keyTop, c.keyBot, c.keyEdge)
        Tone.PRIMARY -> Triple(c.primTop, c.primBot, if (c.dark) Color.Black else Color(0xFF15161A))
        Tone.MODIFIER -> Triple(c.modTop, c.modBot, Color(0xFFB08C00))
        Tone.DANGER -> Triple(c.dangerContainer, c.dangerContainer, c.danger)
        Tone.GLASS -> Triple(c.glassTop, c.glassBot, if (c.dark) Color.Black else c.keyEdge)
    }
    val glass = tone == Tone.GLASS
    this
        .graphicsLayer {
            // press: sink 1dp and shrink to 0.96 (design 5.1)
            translationY = depth * 1.dp.toPx()
            val s = 1f - 0.04f * depth
            scaleX = s; scaleY = s
        }
        .drawWithCache {
            val p = outlinePath(shape)
            val edgeLine = Path().apply { addPath(p, Offset(0f, 1.dp.toPx())) }
            val near = SoftShadow(c.dshadow.copy(alpha = c.dshadow.alpha * if (glass) 1f else 0.62f), (if (glass) 6 else 3).dp.toPx(), dy = (if (glass) 4 else 2).dp.toPx())
            val far = SoftShadow(c.dshadow, (if (glass) 14 else 10).dp.toPx(), dy = (if (glass) 8 else 6).dp.toPx())
            onDrawBehind {
                val a = 1f - depth
                drawShadow(p, far, a)
                drawShadow(p, near, a)
                drawPath(edgeLine, edge.copy(alpha = edge.alpha * (if (c.dark) 0.95f else 0.8f) * a))
            }
        }
        .clip(shape)
        .drawWithCache {
            val p = outlinePath(shape)
            val fill = Brush.verticalGradient(listOf(top, bot))
            val fillPressed = Brush.verticalGradient(listOf(bot, top))
            val grain = Textures.brush(Textures.noise, noiseScale(density))
            val hiPath = crescent(p, 1.dp.toPx())
            val loPath = crescent(p, -1.dp.toPx())
            val frame = frameAround(p, 24.dp.toPx())
            val pressShadow = SoftShadow(Color.Black.copy(alpha = if (c.dark) 0.5f else 0.3f), 4.dp.toPx(), dy = 2.dp.toPx())
            // glass only
            val gloss = Brush.verticalGradient(
                0f to Color.White.copy(alpha = c.glossTop), 0.38f to Color.White.copy(alpha = c.glossMid),
                0.40f to Color.Transparent, 1f to Color.Transparent,
            )
            val glow = SoftShadow(c.glassGlow, 16.dp.toPx(), dy = (-8).dp.toPx())
            val rim = outlinePath(shape, 0.5.dp.toPx())
            val hi = when (tone) {
                Tone.PRIMARY -> Color.White.copy(alpha = 0.22f)
                Tone.GLASS -> Color.White.copy(alpha = c.glassSpec)
                else -> c.hi
            }
            onDrawWithContent {
                drawRect(if (pressed) fillPressed else fill)
                if (glass) {
                    drawRect(gloss, alpha = if (pressed) 0.6f else 1f)
                    drawInnerShadow(p, frame, glow)
                    drawPath(rim, Color.White.copy(alpha = c.glassRim), style = Stroke(1.dp.toPx()))
                } else if (tone != Tone.DANGER) {
                    drawGrain(grain, c.capNoise)
                }
                if (depth > 0.01f) drawInnerShadow(p, frame, pressShadow, depth)
                drawContent()
                if (!pressed) {
                    drawPath(hiPath, hi)
                    if (!glass) drawPath(loPath, c.lo)
                }
            }
        }
}

/** Recessed well: darker fill, inner shadow at the top lip, 1dp light bottom edge. */
fun Modifier.well(shape: Shape): Modifier = composed {
    val c = LocalMote.current
    this
        .clip(shape)
        .drawWithCache {
            val p = outlinePath(shape)
            val frame = frameAround(p, 24.dp.toPx())
            val inner = SoftShadow(c.wellShade, 6.dp.toPx(), dy = 2.dp.toPx())
            val bottom = crescent(p, -1.dp.toPx())
            val bottomHi = if (c.dark) Color.White.copy(alpha = 0.06f) else Color.White.copy(alpha = 0.85f)
            onDrawWithContent {
                drawRect(c.well)
                drawInnerShadow(p, frame, inner)
                drawPath(bottom, bottomHi)
                drawContent()
            }
        }
}

/**
 * Backplate: gradient, 1px/3px brushed hairline, the shared noise at 30% / 42% in Overlay, and an
 * elliptical vignette (radial-gradient 130% x 90% at 50% 38%).
 */
fun Modifier.chassis(): Modifier = composed {
    val c = LocalMote.current
    this.drawWithCache {
        val base = Brush.verticalGradient(listOf(c.chassisTop, c.chassisBot))
        val hair = Textures.brush(Textures.hairline)
        val hairTint = ColorFilter.tint(c.grain.copy(alpha = 1f), BlendMode.SrcIn)
        val grain = Textures.brush(Textures.noise, noiseScale(density))
        val cx = size.width / 2; val cy = size.height * 0.38f
        val rx = size.width * 1.3f; val ry = size.height * 0.9f
        val vig = Brush.radialGradient(
            0f to Color.Transparent, c.vignetteStop to Color.Transparent, 1f to c.vignette,
            center = Offset(cx, cy), radius = rx.coerceAtLeast(1f),
        )
        val sy = ry / rx.coerceAtLeast(1f)
        onDrawBehind {
            drawRect(base)
            drawRect(hair, alpha = c.grain.alpha, colorFilter = hairTint)
            drawGrain(grain, c.noiseAlpha)
            // squash a circular gradient into the ellipse; the rect is pre-stretched to still cover the screen
            scale(1f, sy, Offset(cx, cy)) {
                drawRect(vig, topLeft = Offset(0f, cy - cy / sy), size = Size(size.width, size.height / sy))
            }
        }
    }
}

/**
 * Brushed metal (slider thumbs): 4-stop gradient with a hard step at 50% (a turned edge), 1px/2px
 * brushed lines, 1dp edge, inner top highlight and a drop shadow. Light in both themes on purpose.
 */
fun Modifier.metal(shape: Shape, pressed: Boolean = false): Modifier = composed {
    val c = LocalMote.current
    this
        .drawWithCache {
            val p = outlinePath(shape)
            val near = SoftShadow(c.dshadow, 4.dp.toPx(), dy = 3.dp.toPx())
            val far = SoftShadow(c.dshadow, 10.dp.toPx(), dy = 6.dp.toPx())
            onDrawBehind {
                drawShadow(p, far, if (pressed) 0.5f else 1f)
                drawShadow(p, near)
            }
        }
        .clip(shape)
        .drawWithCache {
            val p = outlinePath(shape)
            val m = c.metal
            val fill = Brush.verticalGradient(0f to m[0], 0.48f to m[1], 0.52f to m[2], 1f to m[3])
            val lines = Textures.brush(Textures.brushed)
            val hi = crescent(p, 1.dp.toPx())
            val lo = crescent(p, -1.dp.toPx())
            val edge = outlinePath(shape, 0.5.dp.toPx())
            onDrawWithContent {
                drawRect(fill)
                drawRect(lines)
                if (pressed) drawRect(Color.Black.copy(alpha = 0.06f))
                drawPath(hi, Color.White.copy(alpha = 0.9f))
                drawPath(lo, Color.Black.copy(alpha = 0.25f))
                drawPath(edge, c.metalEdge, style = Stroke(1.dp.toPx()))
                drawContent()
            }
        }
}

/**
 * Matte rubber disc of radius [r] at [center] (D-pad and its OK cap): contact shadow, a side wall 3dp
 * below, the fill gradient, and a full-circle bevel (white@90% -> clear by 35% -> black@22%).
 * Callers build the brushes once in drawWithCache and pass them in.
 */
class Rubber(c: MoteColors, center: Offset, r: Float, density: Float, val pressed: Boolean = false) {
    private val wallDy = 3 * density
    val contact = SoftShadow(c.dshadow, 8 * density, dy = 6 * density)
    val contactPath = Path().apply { addOval(Rect(center, r)) }
    val wall = c.rubWall
    val fill = Brush.verticalGradient(listOf(c.rubTop, c.rubBot), startY = center.y - r, endY = center.y + r)
    val fillPressed = Brush.verticalGradient(listOf(c.rubBot, c.rubTop), startY = center.y - r, endY = center.y + r)
    val bevel = Brush.verticalGradient(
        0f to Color.White.copy(alpha = if (c.dark) 0.22f else 0.9f), 0.35f to Color.Transparent,
        0.7f to Color.Transparent, 1f to Color.Black.copy(alpha = if (c.dark) 0.5f else 0.22f),
        startY = center.y - r, endY = center.y + r,
    )
    private val ctr = center
    private val rad = r
    private val bevelW = 1.5f * density

    fun DrawScope.draw(pressedNow: Boolean = pressed) {
        drawShadow(contactPath, contact, if (pressedNow) 0.6f else 1f)
        val sink = if (pressedNow) wallDy * 0.5f else 0f
        drawCircle(wall, rad, Offset(ctr.x, ctr.y + wallDy))
        translate(top = sink) {
            drawCircle(if (pressedNow) fillPressed else fill, rad, ctr)
            drawCircle(bevel, rad - bevelW / 2, ctr, style = Stroke(bevelW))
        }
    }
}
