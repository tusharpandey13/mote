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

/** Shared tiled textures (tokens.json `textures`), generated once per process: no assets, no per-frame allocation. */
object Textures {
    private fun bitmap(px: IntArray, w: Int, h: Int): ImageBitmap =
        android.graphics.Bitmap.createBitmap(px, w, h, android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap()

    /** Variance match to the mockup's feTurbulence, tuned with zoomed side-by-side screenshots. */
    private const val K = 0.34f
    private const val KB = 0.28f

    private fun grey(v: Float): Int { val l = (v.coerceIn(0f, 1f) * 255).toInt(); return (0xFF shl 24) or (l shl 16) or (l shl 8) or l }

    /**
     * `grain`: 128px speck noise, ~1dp cells. Value noise, 2 octaves (amp 1, 0.5), seed 7, normalised,
     * then contrast-stretched v' = clamp(2.4v - 0.7); opaque grey. At ~1 cell per pixel the octaves are
     * per-pixel lattice values, so this is the tileable equivalent.
     */
    val grain: ImageBitmap by lazy {
        val n = 128
        val rnd = Random(7)
        val o1 = FloatArray(n * n) { rnd.nextFloat() }
        val o2 = FloatArray(n * n) { rnd.nextFloat() }
        // raw lattice values have about twice the variance of interpolated (feTurbulence-like) noise
        bitmap(IntArray(n * n) { i -> grey(2.4f * (0.5f + ((o1[i] + 0.5f * o2[i]) / 1.5f - 0.5f) * K) - 0.7f) }, n, n)
    }

    /**
     * `brushed`: 512x128 horizontal streaks. Per row y a random r(y), then
     * v(x, y) = 0.75 r(y) + 0.25 noise1D(x / 250 + 17 y), seed 3; opaque grey.
     */
    val brushed: ImageBitmap by lazy {
        val w = 512; val h = 128
        val rnd = Random(3)
        val row = FloatArray(h) { rnd.nextFloat() }
        val lattice = FloatArray(256) { rnd.nextFloat() }
        fun noise1(t: Float): Float {
            val i = kotlin.math.floor(t).toInt(); val f = t - i
            val a = lattice[i and 255]; val b = lattice[(i + 1) and 255]
            val e = f * f * (3 - 2 * f)
            return a + (b - a) * e
        }
        bitmap(IntArray(w * h) { k ->
            val x = k % w; val y = k / w
            grey(0.5f + (0.75f * row[y] + 0.25f * noise1(x / 250f + y * 17f) - 0.5f) * KB)
        }, w, h)
    }

    /** Repeating brush drawn so 1 bitmap px = [scale] screen px (pass density for 1px = 1dp). */
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
    // the paint itself stays transparent; a translucent shadow colour supplies the shadow's alpha
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

/** A texture blended into a material with Overlay at [alpha] (tokens.json: layers of type "texture"). */
fun DrawScope.drawGrain(brush: Brush, alpha: Float) {
    if (alpha > 0f) drawRect(brush, alpha = alpha, blendMode = BlendMode.Overlay)
}

/** Horizontal (90°) sheen gradient across the width, from tokens.json `sheen.*.stops`. */
fun sheen(stops: List<Pair<Float, Color>>, width: Float): Brush =
    Brush.horizontalGradient(*stops.toTypedArray(), startX = 0f, endX = width)

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
        Tone.PRIMARY -> Triple(c.primTop, c.primBot, if (c.dark) Color(0xFF0C0D10) else Color(0xFF121316))
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
            val grain = Textures.brush(Textures.grain, density)
            val grainA = when (tone) { Tone.GLASS -> c.grainAlpha.glass; Tone.DANGER -> 0f; else -> c.grainAlpha.plastic }
            val primGloss = Brush.verticalGradient(0f to Color.White.copy(alpha = 0.16f), 0.45f to Color.Transparent, 1f to Color.Transparent)
            val hiPath = crescent(p, 1.dp.toPx())
            val loPath = crescent(p, -1.dp.toPx())
            val frame = frameAround(p, 24.dp.toPx())
            val pressShadow = SoftShadow(Color(0x73000000), 4.dp.toPx(), dy = 2.dp.toPx())
            // glass only
            val gloss = Brush.verticalGradient(
                0f to Color.White.copy(alpha = c.glossTop), 0.38f to Color.White.copy(alpha = c.glossMid),
                0.40f to Color.Transparent, 1f to Color.Transparent,
            )
            val glow = SoftShadow(c.glassGlow, 14.dp.toPx(), dy = (-6).dp.toPx())
            val rim = outlinePath(shape, 0.5.dp.toPx())
            val hi = when (tone) {
                Tone.PRIMARY -> Color(0x38FFFFFF)
                Tone.GLASS -> c.glassTopLine
                else -> c.hi
            }
            onDrawWithContent {
                // layer order (tokens.json materials): body, textures, glow/gloss, inner lines
                drawRect(if (pressed) fillPressed else fill)
                drawGrain(grain, grainA)
                if (glass) {
                    drawInnerShadow(p, frame, glow)
                    drawRect(gloss, alpha = if (pressed) 0.6f else 1f)
                    drawPath(rim, Color.White.copy(alpha = c.glassRim), style = Stroke(1.dp.toPx()))
                } else if (tone == Tone.PRIMARY) {
                    drawRect(primGloss)
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

/** Recessed well: darker fill (optional), inner shadow at the top lip, 1dp light bottom edge. */
fun Modifier.well(shape: Shape, fill: Boolean = true, bezel: Boolean = false): Modifier = composed {
    val c = LocalMote.current
    this
        .clip(shape)
        .drawWithCache {
            val p = outlinePath(shape)
            val frame = frameAround(p, 24.dp.toPx())
            val inner = SoftShadow(c.wellShade, 6.dp.toPx(), dy = 2.dp.toPx())
            val bottom = crescent(p, -1.dp.toPx())
            val bottomHi = if (c.dark) Color(0x0FFFFFFF) else Color(0xD9FFFFFF)
            val grain = Textures.brush(Textures.grain, density)
            val bezelFill = Brush.verticalGradient(listOf(c.bezelTop, c.bezelBot))
            onDrawWithContent {
                if (bezel) drawRect(bezelFill) else if (fill) drawRect(c.well)
                if (fill) drawGrain(grain, c.grainAlpha.rubber)
                drawInnerShadow(p, frame, inner)
                drawPath(bottom, bottomHi)
                drawContent()
            }
        }
}

/**
 * Backplate (tokens.json materials.backplate): gradient, horizontal brushed streaks (Overlay), a
 * horizontal sheen with two soft bands, grain (Overlay) and an elliptical vignette
 * (radial-gradient 130% x 90% at 50% 38%).
 */
fun Modifier.chassis(): Modifier = composed {
    val c = LocalMote.current
    this.drawWithCache {
        val base = Brush.verticalGradient(listOf(c.chassisTop, c.chassisBot))
        val brushed = Textures.brush(Textures.brushed, density)
        val sheenBrush = sheen(c.sheenPlate, size.width)
        val grain = Textures.brush(Textures.grain, density)
        val cx = size.width / 2; val cy = size.height * 0.38f
        val rx = size.width * 1.3f; val ry = size.height * 0.9f
        val vig = Brush.radialGradient(
            0f to Color.Transparent, c.vignetteStop to Color.Transparent, 1f to c.vignette,
            center = Offset(cx, cy), radius = rx.coerceAtLeast(1f),
        )
        val sy = ry / rx.coerceAtLeast(1f)
        onDrawBehind {
            drawRect(base)
            drawGrain(brushed, c.brushedPlate)
            drawRect(sheenBrush)
            drawGrain(grain, c.grainAlpha.plate)
            // squash a circular gradient into the ellipse; the rect is pre-stretched to still cover the screen
            scale(1f, sy, Offset(cx, cy)) {
                drawRect(vig, topLeft = Offset(0f, cy - cy / sy), size = Size(size.width, size.height / sy))
            }
        }
    }
}

/**
 * Brushed metal (slider thumbs, tokens.json materials.metal): 4-stop gradient with a hard step at 50%
 * (a turned edge), brushed streaks, one sheen band, grain, inner lines, 1dp edge and a drop shadow.
 * Light in both themes on purpose.
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
            val brushed = Textures.brush(Textures.brushed, density)
            val grain = Textures.brush(Textures.grain, density)
            val sheenBrush = sheen(c.sheenMetal, size.width)
            val hi = crescent(p, 1.dp.toPx())
            val lo = crescent(p, -1.dp.toPx())
            val edge = outlinePath(shape, 0.5.dp.toPx())
            onDrawWithContent {
                drawRect(fill)
                drawGrain(brushed, c.brushedMetal)
                drawRect(sheenBrush)
                drawGrain(grain, c.grainAlpha.metal)
                if (pressed) drawRect(Color.Black.copy(alpha = 0.06f))
                drawPath(hi, Color(0xE6FFFFFF))
                drawPath(lo, Color(0x40000000))
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
        0f to Color(0xE6FFFFFF), 0.35f to Color.Transparent, 0.7f to Color.Transparent, 1f to Color(0x38000000),
        startY = center.y - r, endY = center.y + r,
    )
    private val grain = Textures.brush(Textures.grain, density)
    private val grainA = c.grainAlpha.rubber
    private val ctr = center
    private val rad = r
    private val bevelW = 1.5f * density

    fun DrawScope.draw(pressedNow: Boolean = pressed) {
        drawShadow(contactPath, contact, if (pressedNow) 0.6f else 1f)
        val sink = if (pressedNow) wallDy * 0.5f else 0f
        drawCircle(wall, rad, Offset(ctr.x, ctr.y + wallDy))
        translate(top = sink) {
            drawCircle(if (pressedNow) fillPressed else fill, rad, ctr)
            drawCircle(grain, rad, ctr, alpha = grainA, blendMode = BlendMode.Overlay)
            drawCircle(bevel, rad - bevelW / 2, ctr, style = Stroke(bevelW))
        }
    }
}

/**
 * Machined bezel ring around the D-pad (tokens.json materials.metalRing): a sweep gradient (radial
 * brushed sheen, stops from 12 o'clock), brushed streaks and grain, a top inner line, and a soft
 * drop shadow; the gap between [inner] and the disc is a dark well.
 */
class MetalRing(c: MoteColors, private val center: Offset, private val outer: Float, private val inner: Float, density: Float) {
    private val shadow = SoftShadow(c.dshadow, 10 * density, dy = 6 * density)
    private val ring = Path().apply {
        fillType = PathFillType.EvenOdd
        addOval(Rect(center, outer)); addOval(Rect(center, inner))
    }
    private val outerPath = Path().apply { addOval(Rect(center, outer - 4 * density)) }
    // sweepGradient starts at 3 o'clock; rotate the colour list so the first stop sits at 12
    private val sweep = Brush.sweepGradient(c.ringSweep.drop(2) + c.ringSweep.subList(1, 3), center)
    private val brushed = Textures.brush(Textures.brushed, density)
    private val grain = Textures.brush(Textures.grain, density)
    private val brushedA = c.brushedMetal
    private val grainA = c.grainAlpha.metal
    private val hi = c.hi
    private val gap = c.well
    private val line = density

    fun DrawScope.draw() {
        drawShadow(outerPath, shadow)
        drawPath(ring, sweep)
        clipPath(ring) {
            drawRect(brushed, alpha = brushedA, blendMode = BlendMode.Overlay)
            drawRect(grain, alpha = grainA, blendMode = BlendMode.Overlay)
        }
        drawArc(hi, 200f, 140f, false, Offset(center.x - outer + line / 2, center.y - outer + line / 2),
            Size(2 * outer - line, 2 * outer - line), style = Stroke(line))
        drawCircle(gap, inner, center)
    }
}
