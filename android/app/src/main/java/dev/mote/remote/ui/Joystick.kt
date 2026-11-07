package dev.mote.remote.ui

import android.graphics.BlurMaskFilter
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.mote.remote.data.UiPrefs
import dev.mote.remote.hid.Hid
import dev.mote.remote.net.Remote
import dev.mote.remote.ui.theme.LocalMote
import dev.mote.remote.ui.theme.MetalRing
import dev.mote.remote.ui.theme.MoteColors
import dev.mote.remote.ui.theme.Rubber
import dev.mote.remote.ui.theme.Textures
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/*
 * Rev 7 / 7.1 D-pad (tokens.json components.dpad): a 236dp rubber disc whose 28dp arrow ring
 * (r82-110) sends arrow keys, around a 360° spring-back thumbstick that moves the pointer.
 * Geometry is in design units (1 = 1dp at 236dp); k scales it to the actual size.
 */

private const val DESIGN = 236f
private const val GATE = 82f
private const val TRAVEL = 20f

private enum class Arm(val key: String) { UP("up"), RIGHT("right"), DOWN("down"), LEFT("left") }

/** Live stick state, read only in the draw phase: deflection normalised by travel, press depth, intro ring. */
private class Stick {
    val x = Animatable(0f)
    val y = Animatable(0f)
    val depth = Animatable(0f)
    /** Onboarding ring phase: < 0 = off, else 0..2 (two pulses). */
    val intro = Animatable(-1f)
}

@Composable
fun JoystickPad(size: Dp) {
    val c = LocalMote.current
    val h = haptics()
    val ctx = LocalContext.current
    val linked by rememberUpdatedState(LocalLinked.current)
    val cfg by rememberUpdatedState(LocalSettings.current)
    val reduced = reducedMotion()
    val scope = rememberCoroutineScope()
    val stick = remember { Stick() }
    var arm by remember { mutableStateOf<Arm?>(null) }
    val springBack = remember { spring<Float>(dampingRatio = 0.7f, stiffness = 800f) }

    // one-time onboarding pulse (rev 7.1): twice over 1200 ms each, then never again
    val introDone = LocalSettings.current.stickIntroDone
    LaunchedEffect(introDone) {
        if (introDone) return@LaunchedEffect
        if (!reduced) {
            delay(600)
            stick.intro.snapTo(0f)
            stick.intro.animateTo(2f, tween(2400, easing = LinearEasing))
            stick.intro.snapTo(-1f)
        }
        UiPrefs.update(ctx) { it.copy(stickIntroDone = true) }
    }

    fun leftClick() { h(Haptic.CLICK); Remote.click(Hid.BUTTON_LEFT) }
    fun rightClick() { h(Haptic.HEAVY); Remote.click(Hid.BUTTON_RIGHT) }

    Box(
        Modifier
            .size(size)
            .semantics { contentDescription = "Direction pad" }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val r = this.size.width / 2f
                    val k = this.size.width / DESIGN
                    val ox = down.position.x - r
                    val oy = down.position.y - r
                    val d = hypot(ox, oy)
                    if (d > 118 * k) return@awaitEachGesture
                    if (!linked) { h(Haptic.REJECT); waitForUpOrCancellation(); return@awaitEachGesture }
                    if (d >= GATE * k) {
                        // arrow ring: 90° sectors; the host repeats the held key
                        val a = Math.toDegrees(atan2(oy, ox).toDouble())
                        val which = when {
                            a >= -135 && a < -45 -> Arm.UP
                            a >= -45 && a < 45 -> Arm.RIGHT
                            a >= 45 && a < 135 -> Arm.DOWN
                            else -> Arm.LEFT
                        }
                        arm = which
                        h(Haptic.TICK)
                        Remote.keyDown(which.key)
                        waitForUpOrCancellation()
                        Remote.keyUp(which.key)
                        arm = null
                        return@awaitEachGesture
                    }
                    // thumbstick: deflection is the finger's offset from where it went down
                    down.consume()
                    val travel = TRAVEL * k
                    val press = StickPress().apply { down(down.uptimeMillis) }
                    val driver = StickDriver(cfg.pointerSpeed)
                    var nx = 0f; var ny = 0f
                    scope.launch { if (reduced) stick.depth.snapTo(1f) else stick.depth.animateTo(1f, Motion.press) }
                    val loop: Job = scope.launch {
                        var i = 0; var elapsed = 0L
                        while (isActive) {
                            // 120 Hz: 8, 8, 9 ms (25 ms per 3 ticks), each tick worth exactly 1/120 s
                            val step = if (i % 3 == 2) 9L else 8L
                            delay(step); elapsed += step; i++
                            val (mx, my) = driver.tick(nx, ny, 1f / StickCurve.RATE_HZ)
                            if (mx != 0 || my != 0) Remote.move(mx.toFloat(), my.toFloat())
                            if (press.tick(down.uptimeMillis + elapsed) == StickPress.Out.LONG) rightClick()
                        }
                    }
                    var lifted = false
                    var lastUp = down.uptimeMillis
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        lastUp = ch.uptimeMillis
                        if (!ch.pressed) { lifted = true; ch.consume(); break }
                        val fx = ch.position.x - down.position.x
                        val fy = ch.position.y - down.position.y
                        val len = hypot(fx, fy)
                        press.move(len / density)
                        val m = min(len, travel)
                        nx = if (len > 0f) fx / len * m / travel else 0f
                        ny = if (len > 0f) fy / len * m / travel else 0f
                        if (driver.edge(hypot(nx, ny))) h(Haptic.EDGE)
                        val tx = nx; val ty = ny
                        scope.launch { stick.x.snapTo(tx); stick.y.snapTo(ty) }
                        if (!press.clicking && stick.depth.targetValue > 0f) {
                            scope.launch { if (reduced) stick.depth.snapTo(0f) else stick.depth.animateTo(0f, Motion.press) }
                        }
                        ch.consume()
                    }
                    loop.cancel()
                    when (if (lifted) press.up(lastUp) else { press.cancel(); StickPress.Out.NONE }) {
                        StickPress.Out.TAP -> leftClick()
                        StickPress.Out.LONG -> rightClick()
                        else -> {}
                    }
                    scope.launch {
                        if (reduced) { stick.x.snapTo(0f); stick.y.snapTo(0f); stick.depth.snapTo(0f) } else {
                            launch { stick.x.animateTo(0f, springBack) }
                            launch { stick.y.animateTo(0f, springBack) }
                            stick.depth.animateTo(0f, Motion.press)
                        }
                    }
                }
            }
            .drawWithCache {
                val k = this.size.width / DESIGN
                val ctr = Offset(this.size.width / 2, this.size.height / 2)
                val parts = PadBody(c, ctr, k, density)
                onDrawBehind { with(parts) { draw(arm?.ordinal ?: -1) } }
            },
        contentAlignment = Alignment.Center,
    ) {
        Thumbstick(
            { stick.x.value }, { stick.y.value }, { stick.depth.value }, { stick.intro.value },
            onClick = { if (linked) leftClick() }, onRightClick = { if (linked) rightClick() },
        )
    }
}

/** The fixed parts of the pad: bezel, rubber disc, arrow ring, dividers and the stick's gate and well. */
private class PadBody(c: MoteColors, private val ctr: Offset, private val k: Float, density: Float) {
    private val bezel = MetalRing(c, ctr, 118 * k, 113 * k, density)
    private val disc = Rubber(c, ctr, 110 * k, density)
    private val tri = Path().apply {
        moveTo(ctr.x, ctr.y - 101 * k); lineTo(ctr.x + 6.5f * k, ctr.y - 92 * k); lineTo(ctr.x - 6.5f * k, ctr.y - 92 * k); close()
    }
    private val armRect = Rect(ctr, 110 * k)
    private val armShade = Color.Black.copy(alpha = if (c.dark) 0.22f else 0.07f)
    private val armInk = c.muted
    private val triStroke = Stroke(3 * k, join = StrokeJoin.Round)
    private val accent = c.accent
    private val divider = if (c.dark) Color.Black.copy(alpha = 0.45f) else c.rubWall.copy(alpha = 0.55f)
    private val gate = Brush.verticalGradient(0f to c.metal[0], 0.48f to c.metal[1], 0.52f to c.metal[2], 1f to c.metal[3],
        startY = ctr.y - GATE * k, endY = ctr.y + GATE * k)
    private val gateHi = c.hi
    private val wellFill = Brush.radialGradient(listOf(c.well, if (c.dark) Color(0xFF050506) else Color(0xFFA9ADB3)),
        center = Offset(ctr.x, ctr.y + (0.62f - 0.5f) * 154 * k), radius = 0.6f * 154 * k)
    private val wellShade = Brush.verticalGradient(
        0f to Color(0x8C000000), 0.45f to Color.Transparent, 0.8f to Color(0x00FFFFFF), 1f to Color(0x8CFFFFFF),
        startY = ctr.y - 77 * k, endY = ctr.y + 77 * k,
    )
    private val chevron = Path().apply {
        moveTo(ctr.x - 5 * k, ctr.y - 66 * k); lineTo(ctr.x, ctr.y - 71 * k); lineTo(ctr.x + 5 * k, ctr.y - 66 * k)
    }
    private val hint = c.subtle
    private val wide = Stroke(1.6f * k, cap = StrokeCap.Round, join = StrokeJoin.Round)

    fun DrawScope.draw(pressedArm: Int) {
        with(bezel) { draw() }
        with(disc) { draw() }
        for (i in 0 until 4) rotate(i * 90f, ctr) {
            if (pressedArm == i) drawArc(armShade, 225f, 90f, true, armRect.topLeft, armRect.size)
            val ink = if (pressedArm == i) accent else armInk
            drawPath(tri, ink)
            drawPath(tri, ink, style = triStroke) // mote.css .tri: fill + 3dp round-join stroke
        }
        for (i in 0 until 4) rotate(45f + i * 90f, ctr) {
            drawLine(divider, Offset(ctr.x, ctr.y - 85 * k), Offset(ctr.x, ctr.y - 105 * k), 1.5f * k, cap = StrokeCap.Round)
        }
        // gate lip and recessed well
        drawCircle(gate, GATE * k, ctr)
        drawCircle(gateHi, 81.5f * k, ctr, style = Stroke(k))
        drawCircle(wellFill, 77 * k, ctr)
        drawCircle(wellShade, 75.5f * k, ctr, style = Stroke(3 * k))
        for (i in 0 until 4) rotate(i * 90f, ctr) {
            drawPath(chevron, hint, alpha = 0.55f, style = wide)
            rotate(45f, ctr) { drawCircle(hint, 1.4f * k, Offset(ctr.x, ctr.y - 68 * k), alpha = 0.45f) }
        }
    }
}

private val POINTER = PathParser().parsePathString(
    "M6 3.8l12.4 6.9c.6.33.5 1.22-.15 1.42l-5.1 1.55a1 1 0 0 0-.62.53l-2.3 4.85c-.3.62-1.2.55-1.4-.1z",
).toPath()

/**
 * The thumbstick (rev 7.1, tokens.json components.dpad.joystick.stick): direction glow on the gate,
 * a cast shadow on the well floor, the rubber side wall, a domed cap with a knurled ring, a concave
 * dish, a specular glint and the engraved pointer legend. All inputs are read in the draw phase.
 */
@Composable
private fun BoxScope.Thumbstick(
    x: () -> Float, y: () -> Float, depth: () -> Float, intro: () -> Float,
    onClick: () -> Unit, onRightClick: () -> Unit,
) {
    val c = LocalMote.current
    Box(
        Modifier
            .matchParentSize()
            .drawWithCache {
                val k = size.width / DESIGN
                val ctr = Offset(size.width / 2, size.height / 2)
                fun blurPaint(sigma: Float, color: Color) = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    this.color = color.toArgb()
                    maskFilter = BlurMaskFilter(((sigma - 0.5f) / 0.57735f).coerceAtLeast(0.5f), BlurMaskFilter.Blur.NORMAL)
                }
                val shadowA = if (c.dark) 0.75f else 0.5f
                val shadowRest = blurPaint(5 * k, Color.Black.copy(alpha = shadowA))
                val shadowPressed = blurPaint(2.5f * k, Color.Black.copy(alpha = shadowA))
                val glowSoft = blurPaint(3 * k, c.accent).apply {
                    style = android.graphics.Paint.Style.STROKE; strokeWidth = 8 * k; strokeCap = android.graphics.Paint.Cap.ROUND
                }
                val glowOval = android.graphics.RectF(ctr.x - 79.5f * k, ctr.y - 79.5f * k, ctr.x + 79.5f * k, ctr.y + 79.5f * k)
                val glowRect = Rect(ctr, 79.5f * k)
                val spec = blurPaint(2.5f * k, Color.White.copy(alpha = if (c.dark) 0.18f else 0.55f))
                val well = Path().apply { addOval(Rect(ctr, 77 * k)) }
                // cap-local brushes (origin = cap centre)
                val r = 52 * k
                val wall = Brush.verticalGradient(listOf(c.rubBot, c.rubWall), startY = -r, endY = r)
                val stickHi = if (c.dark) Color(0xFF4A4D54) else Color(0xFFF4F5F7)
                val dome = Brush.radialGradient(0f to stickHi, 0.55f to c.rubTop, 1f to c.rubBot,
                    center = Offset(-0.08f * 2 * r, -0.2f * 2 * r), radius = 0.78f * 2 * r)
                val knurl = Path().apply {
                    for (i in 0 until 40) {
                        val a = Math.toRadians(i * 9.0 - 90)
                        val cs = cos(a).toFloat(); val sn = sin(a).toFloat()
                        moveTo(41 * k * cs, 41 * k * sn); lineTo(49 * k * cs, 49 * k * sn)
                    }
                }
                val bevel = Brush.verticalGradient(
                    0f to Color(0xE6FFFFFF), 0.35f to Color(0x00FFFFFF), 0.7f to Color(0x00000000), 1f to Color(0x38000000),
                    startY = -r, endY = r,
                )
                val dr = 38 * k
                val dishTop = if (c.dark) Color(0xFF17181B) else Color(0xFFB4B8BE)
                val dishBot = if (c.dark) Color(0xFF2E3035) else Color(0xFFDADCE0)
                val dish = Brush.verticalGradient(listOf(dishTop, dishBot), startY = -dr, endY = dr)
                val dishShade = Brush.radialGradient(0.45f to Color.Transparent, 1f to Color(0x4D000000),
                    center = Offset(0f, -0.2f * 2 * dr), radius = 0.7f * 2 * dr)
                val dishLip = Brush.verticalGradient(
                    0f to Color(0x8C000000), 0.45f to Color.Transparent, 0.8f to Color(0x00FFFFFF), 1f to Color(0x8CFFFFFF),
                    startY = -dr, endY = dr,
                )
                val dishPath = Path().apply { addOval(Rect(Offset.Zero, dr)) }
                val grain = Textures.brush(Textures.grain, density)
                val legend = Path().apply { addPath(POINTER) }
                val legendScale = 0.75f * k
                val legendStroke = Stroke(1.6f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                val knurlStroke = Stroke(1.6f * k, cap = StrokeCap.Round)
                val bevelStroke = Stroke(1.5f * k)
                val lipStroke = Stroke(2 * k)
                val glowStroke = Stroke(3 * k, cap = StrokeCap.Round)
                val introStroke = Stroke(2 * k)
                onDrawBehind {
                    val nx = x(); val ny = y(); val p = depth().coerceIn(0f, 1f)
                    val dx = nx * TRAVEL * k; val dy = ny * TRAVEL * k
                    val d = hypot(nx, ny).coerceAtMost(1f)
                    // direction glow on the gate (pressed: the whole ring)
                    val arcA = min(1f, 0.35f + d) * (d / StickCurve.DEADZONE).coerceIn(0f, 1f) * (1f - p)
                    drawIntoCanvas { cv ->
                        val nc = cv.nativeCanvas
                        if (arcA > 0.01f) {
                            val sweep = 30f + 60f * d
                            val start = Math.toDegrees(atan2(ny, nx).toDouble()).toFloat() - sweep / 2
                            glowSoft.alpha = (0.45f * arcA * 255).toInt()
                            nc.drawArc(glowOval, start, sweep, false, glowSoft)
                            drawArc(c.accent, start, sweep, false, glowRect.topLeft, glowRect.size, alpha = arcA, style = glowStroke)
                        }
                        if (p > 0.01f) {
                            val a = 0.75f * p
                            glowSoft.alpha = (0.45f * a * 255).toInt()
                            nc.drawOval(glowOval, glowSoft)
                            drawCircle(c.accent, 79.5f * k, ctr, alpha = a, style = glowStroke)
                        }
                        // cast shadow on the well floor, opposite the lean
                        clipPath(well) {
                            val sx = ctr.x + dx - 0.45f * dx
                            val sy = ctr.y + dy - 0.45f * dy + (9f - 6f * p) * k
                            if (p < 0.99f) { shadowRest.alpha = (shadowA * (1 - p) * 255).toInt(); nc.drawCircle(sx, sy, r, shadowRest) }
                            if (p > 0.01f) { shadowPressed.alpha = (shadowA * p * 255).toInt(); nc.drawCircle(sx, sy, r, shadowPressed) }
                        }
                    }
                    val capX = ctr.x + dx; val capY = ctr.y + dy + 2.5f * k * p
                    translate(capX, capY) {
                        val s = 1f - 0.03f * p
                        scale(s, s, Offset.Zero) {
                            translate(-0.22f * dx, (7f - 5f * p) * k - 0.22f * dy) { drawCircle(wall, r, Offset.Zero) }
                            drawCircle(dome, r, Offset.Zero)
                            drawPath(knurl, c.lo, style = knurlStroke)
                            drawCircle(bevel, 51.25f * k, Offset.Zero, style = bevelStroke)
                            drawCircle(dish, dr, Offset.Zero)
                            drawCircle(dishShade, dr, Offset.Zero)
                            clipPath(dishPath) { drawCircle(grain, dr, Offset.Zero, alpha = 0.3f, blendMode = BlendMode.Overlay) }
                            drawCircle(dishLip, dr, Offset.Zero, style = lipStroke)
                            rotate(-24f, Offset(-16 * k, -40 * k)) {
                                drawIntoCanvas { it.nativeCanvas.drawOval(-30 * k, -44 * k, -2 * k, -36 * k, spec) }
                            }
                            translate(-8.5f * k, -10f * k) {
                                scale(legendScale, legendScale, Offset.Zero) {
                                    translate(0f, 1.2f) { drawPath(legend, c.hi, style = legendStroke) }
                                    drawPath(legend, c.muted, style = legendStroke)
                                }
                            }
                        }
                        // onboarding ring: r56, scale 1 -> 1.35 while alpha 0.5 -> 0, twice
                        val t = intro()
                        if (t >= 0f) {
                            val f = t - t.toInt()
                            drawCircle(c.accent, 56 * k * (1 + 0.35f * f), Offset.Zero, alpha = 0.5f * (1 - f), style = introStroke)
                        }
                    }
                }
            },
    )
    // accessibility: the cap itself is a button (tap = click, custom action = right click)
    Box(
        Modifier
            .align(Alignment.Center)
            .fillMaxSize(104f / DESIGN)
            .semantics {
                role = Role.Button
                contentDescription = "Pointer stick. Drag to move the pointer, tap to click"
                onClick { onClick(); true }
                customActions = listOf(CustomAccessibilityAction("Right click") { onRightClick(); true })
            },
    )
}
